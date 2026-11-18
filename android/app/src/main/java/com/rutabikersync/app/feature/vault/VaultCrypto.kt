package com.rutabikersync.app.feature.vault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado AES-256-GCM y gestión criptográfica E2EE para la Bóveda Biker (Fase 5 · RULES.md §15, MEMORY.md D69).
 *
 * Modelo de Seguridad:
 * 1. Clave Maestra Simétrica (256 bits):
 *    - En dispositivo real: almacenada en preferencias privadas cifrada con una Key-Encrypting-Key (KEK)
 *      en AndroidKeyStore (`rbs_vault_kek_v1`).
 *    - En tests JVM (context == null): clave en memoria volátil sin hardware.
 * 2. Respaldo y Recuperación con Passphrase Fuerte:
 *    - Clave de recuperación derivada mediante BIP-0039 (12 palabras) y PBKDF2-HMAC-SHA256
 *      con 100,000 iteraciones + salt aleatorio de 16 bytes.
 *    - La clave maestra se cifra con esta K_recov y se sube como blob opaco al backend.
 * 3. Formato de Cifrado:
 *    - AES/GCM/NoPadding con IV aleatorio de 12 bytes y Authentication Tag de 128 bits.
 */
class VaultCrypto(
    private val context: Context? = null
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEK_ALIAS = "rbs_vault_kek_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val MASTER_KEY_BYTES = 32 // 256 bits

        private const val PREFS_NAME = "rbs_vault_keystore_prefs"
        private const val KEY_WRAPPED_MASTER_KEY = "wrapped_master_key_v1"
        private const val KEY_USER_SALT = "vault_user_salt_v1"
    }

    data class EncryptedPayload(
        val ciphertext: String, // Base64 sin el tag de 16 bytes
        val iv: String,         // Base64 (12 bytes)
        val authTag: String     // Base64 (16 bytes)
    ) {
        /**
         * Reensambla el IV + ciphertext + authTag para almacenamiento compacto local en SharedPreferences.
         */
        fun toCombinedBase64(): String {
            val ivBytes = Base64.getDecoder().decode(iv)
            val cipherBytes = Base64.getDecoder().decode(ciphertext)
            val tagBytes = Base64.getDecoder().decode(authTag)
            return Base64.getEncoder().encodeToString(ivBytes + cipherBytes + tagBytes)
        }

        companion object {
            fun fromCombinedBase64(combined: String): EncryptedPayload? {
                val bytes = try {
                    Base64.getDecoder().decode(combined)
                } catch (_: Exception) {
                    return null
                }
                if (bytes.size < GCM_IV_BYTES + GCM_TAG_BYTES) return null

                val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
                val tag = bytes.copyOfRange(bytes.size - GCM_TAG_BYTES, bytes.size)
                val cipher = bytes.copyOfRange(GCM_IV_BYTES, bytes.size - GCM_TAG_BYTES)

                return EncryptedPayload(
                    ciphertext = Base64.getEncoder().encodeToString(cipher),
                    iv = Base64.getEncoder().encodeToString(iv),
                    authTag = Base64.getEncoder().encodeToString(tag)
                )
            }
        }
    }

    data class RecoveryKeyBlob(
        val encryptedMasterKey: String,
        val iv: String,
        val authTag: String,
        val pbkdf2Salt: String,
        val pbkdf2Iterations: Int = 100_000
    )

    val isUsingHardwareKeystore: Boolean
        get() = context != null

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Clave de respaldo en memoria volátil para tests JVM sin Android Context
    private var jvmMemoryMasterKey: SecretKey? = null

    // -------------------------------------------------------------------------
    // API Criptográfica de la Clave Maestra
    // -------------------------------------------------------------------------

    /**
     * Obtiene la clave maestra simétrica actual (256 bits). Si no existe, genera una nueva.
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        if (context == null) {
            if (jvmMemoryMasterKey == null) {
                val raw = ByteArray(MASTER_KEY_BYTES)
                SecureRandom().nextBytes(raw)
                jvmMemoryMasterKey = SecretKeySpec(raw, "AES")
            }
            return jvmMemoryMasterKey!!
        }

        // Buscar master key envuelta en preferencias
        val wrappedBase64 = prefs?.getString(KEY_WRAPPED_MASTER_KEY, null)
        if (!wrappedBase64.isNullOrBlank()) {
            val unwrapped = unwrapMasterKey(wrappedBase64)
            if (unwrapped != null) {
                return unwrapped
            }
        }

        // Si no existe o falló el unwrap, generar una nueva master key de 256 bits
        val rawMasterKey = ByteArray(MASTER_KEY_BYTES)
        SecureRandom().nextBytes(rawMasterKey)
        val newKey = SecretKeySpec(rawMasterKey, "AES")
        wrapAndStoreMasterKey(rawMasterKey)
        return newKey
    }

    /**
     * Comprueba si el dispositivo ya cuenta con una clave maestra persistida en KeyStore o preferencias.
     */
    fun hasMasterKey(): Boolean {
        if (context == null) return jvmMemoryMasterKey != null
        val wrappedBase64 = prefs?.getString(KEY_WRAPPED_MASTER_KEY, null)
        return !wrappedBase64.isNullOrBlank()
    }

    /**
     * Reemplaza la clave maestra actual por una importada mediante recuperación BIP-0039.
     */
    @Synchronized
    fun setMasterKey(rawMasterKey: ByteArray) {
        require(rawMasterKey.size == MASTER_KEY_BYTES) { "La clave maestra debe ser de 32 bytes (256 bits)" }
        if (context == null) {
            jvmMemoryMasterKey = SecretKeySpec(rawMasterKey, "AES")
        } else {
            wrapAndStoreMasterKey(rawMasterKey)
        }
    }

    /**
     * Obtiene el salt de 16 bytes único por usuario. Si no existe, lo genera y persiste.
     */
    @Synchronized
    fun getOrCreateUserSalt(): String {
        if (context == null) {
            return "MDEyMzQ1Njc4OWFiY2RlZg==" // Salt fijo en JVM si no hay prefs
        }
        val existing = prefs?.getString(KEY_USER_SALT, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.getEncoder().encodeToString(saltBytes)
        prefs?.edit()?.putString(KEY_USER_SALT, saltBase64)?.apply()
        return saltBase64
    }

    fun setUserSalt(saltBase64: String) {
        prefs?.edit()?.putString(KEY_USER_SALT, saltBase64)?.apply()
    }

    // -------------------------------------------------------------------------
    // Cifrado y Descifrado de Documentos (Master Key)
    // -------------------------------------------------------------------------

    /**
     * Cifra un texto plano usando la clave maestra y AES-256-GCM.
     */
    fun encrypt(plaintext: String): String {
        val payload = encryptToPayload(plaintext.toByteArray(Charsets.UTF_8), getOrCreateMasterKey())
        return payload.toCombinedBase64()
    }

    /**
     * Descifra un blob combinado Base64 usando la clave maestra.
     */
    fun decrypt(blob: String): String? {
        val payload = EncryptedPayload.fromCombinedBase64(blob) ?: return null
        return try {
            val decryptedBytes = decryptPayload(payload, getOrCreateMasterKey())
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cifra datos binarios produciendo el objeto desglosado para el backend (ciphertext, iv, authTag).
     */
    fun encryptToPayload(plaintextBytes: ByteArray, key: SecretKey = getOrCreateMasterKey()): EncryptedPayload {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes generados de manera segura por el proveedor
        val cipherAndTag = cipher.doFinal(plaintextBytes)

        val cipherBytes = cipherAndTag.copyOfRange(0, cipherAndTag.size - GCM_TAG_BYTES)
        val tagBytes = cipherAndTag.copyOfRange(cipherAndTag.size - GCM_TAG_BYTES, cipherAndTag.size)

        return EncryptedPayload(
            ciphertext = Base64.getEncoder().encodeToString(cipherBytes),
            iv = Base64.getEncoder().encodeToString(iv),
            authTag = Base64.getEncoder().encodeToString(tagBytes)
        )
    }

    /**
     * Descifra un [EncryptedPayload] usando una clave simétrica específica.
     * @throws AEADBadTagException si el authTag o ciphertext han sido manipulados o la clave es incorrecta.
     */
    fun decryptPayload(payload: EncryptedPayload, key: SecretKey = getOrCreateMasterKey()): ByteArray {
        val ivBytes = Base64.getDecoder().decode(payload.iv)
        val cipherBytes = Base64.getDecoder().decode(payload.ciphertext)
        val tagBytes = Base64.getDecoder().decode(payload.authTag)

        val combinedCipherAndTag = cipherBytes + tagBytes

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        return cipher.doFinal(combinedCipherAndTag)
    }

    // -------------------------------------------------------------------------
    // Respaldo y Recuperación BIP-0039 (Master Key cifrada con K_recov)
    // -------------------------------------------------------------------------

    /**
     * Exporta la clave maestra cifrada con la clave de recuperación K_recov derivada de las 12 palabras BIP39.
     */
    fun exportRecoveryBlob(mnemonicWords: List<String>): RecoveryKeyBlob {
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)

        val kRecov = Bip39.deriveRecoveryKey(mnemonicWords, saltBytes)
        val masterKey = getOrCreateMasterKey()
        val payload = encryptToPayload(masterKey.encoded, kRecov)

        return RecoveryKeyBlob(
            encryptedMasterKey = payload.ciphertext,
            iv = payload.iv,
            authTag = payload.authTag,
            pbkdf2Salt = Base64.getEncoder().encodeToString(saltBytes),
            pbkdf2Iterations = 100_000
        )
    }

    /**
     * Restaura la clave maestra a partir de las 12 palabras BIP39 y el RecoveryKeyBlob descargado del backend.
     * @return true si la recuperación fue exitosa; false si la passphrase es incorrecta o el blob está corrupto.
     */
    fun restoreMasterKeyFromRecovery(mnemonicWords: List<String>, blob: RecoveryKeyBlob): Boolean {
        return try {
            val saltBytes = Base64.getDecoder().decode(blob.pbkdf2Salt)
            val kRecov = Bip39.deriveRecoveryKey(mnemonicWords, saltBytes)

            val payload = EncryptedPayload(
                ciphertext = blob.encryptedMasterKey,
                iv = blob.iv,
                authTag = blob.authTag
            )
            val rawMasterKey = decryptPayload(payload, kRecov)
            setMasterKey(rawMasterKey)
            android.util.Log.d("VaultCrypto", "restoreMasterKeyFromRecovery SUCCESS, masterKey set")
            true
        } catch (e: AEADBadTagException) {
            android.util.Log.e("VaultCrypto", "restoreMasterKeyFromRecovery AEADBadTagException: ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.e("VaultCrypto", "restoreMasterKeyFromRecovery Exception: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Envoltura con AndroidKeyStore (KEK)
    // -------------------------------------------------------------------------

    private fun wrapAndStoreMasterKey(rawMasterKey: ByteArray) {
        val kek = getOrCreateAndroidKeystoreKek()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val cipherAndTag = cipher.doFinal(rawMasterKey)
        val wrappedBytes = iv + cipherAndTag
        val wrappedBase64 = Base64.getEncoder().encodeToString(wrappedBytes)
        prefs?.edit()?.putString(KEY_WRAPPED_MASTER_KEY, wrappedBase64)?.apply()
    }

    private fun unwrapMasterKey(wrappedBase64: String): SecretKey? {
        return try {
            val wrappedBytes = Base64.getDecoder().decode(wrappedBase64)
            if (wrappedBytes.size < GCM_IV_BYTES + GCM_TAG_BYTES) return null

            val iv = wrappedBytes.copyOfRange(0, GCM_IV_BYTES)
            val cipherAndTag = wrappedBytes.copyOfRange(GCM_IV_BYTES, wrappedBytes.size)

            val kek = getOrCreateAndroidKeystoreKek()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            val rawKey = cipher.doFinal(cipherAndTag)
            SecretKeySpec(rawKey, "AES")
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateAndroidKeystoreKek(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEK_ALIAS, null)
        if (existing is SecretKey) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Puerta biométrica controlada en BiometricGate
                .build()
        )
        return keyGen.generateKey()
    }
}
