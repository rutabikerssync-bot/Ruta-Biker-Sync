package com.rutabikersync.app.core.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Almacenamiento seguro de tokens de sesión en Android (Decisión D40).
 * - `accessToken` (30 min) vive únicamente en memoria volátil del proceso.
 * - `refreshToken` (30 días) se cifra con AES-256-GCM cuya clave maestra reside en
 *   `AndroidKeyStore` (`rbs_session_master_key_v1`) antes de guardarse en preferencias privadas
 *   excluidas de copias de seguridad (`backup_rules.xml` / `data_extraction_rules.xml`).
 * - NOTA DE HONESTIDAD (Ajuste 1 de Fase 2): En pruebas unitarias JVM (`context == null`),
 *   `AndroidKeyStore` del hardware no existe y se utiliza una clave AES-256-GCM en memoria JCE
 *   (`isUsingHardwareAndroidKeystore == false`). La prueba con hardware real reside en `androidTest`.
 * - RECUPERACIÓN SIN CIERRE FORZADO (Ajuste 2 de Fase 2): Si la clave de `AndroidKeyStore` se
 *   pierde o invalida (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`,
 *   `AEADBadTagException`), el almacén borra la entrada inválida y el texto cifrado, marca
 *   `requiresRelogin = true` e invoca `onKeyInvalidated` para pedir login de nuevo sin cerrarse.
 */
interface TokenCipherStore {
    val isUsingHardwareAndroidKeystore: Boolean
    val requiresRelogin: Boolean
    fun saveRefreshToken(refreshToken: String)
    fun loadRefreshToken(): String?
    fun clear()
}

class KeystoreEncryptedTokenStore(
    private val context: Context? = null,
    private val onKeyInvalidated: (() -> Unit)? = null
) : TokenCipherStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rbs_session_master_key_v1"
        const val PREFS_NAME = "rbs_encrypted_session_store"
        private const val KEY_REFRESH_CIPHERTEXT = "refresh_token_aes256_gcm"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    override val isUsingHardwareAndroidKeystore: Boolean
        get() = context != null

    @Volatile
    override var requiresRelogin: Boolean = false
        private set

    // Respaldo en memoria cifrada para pruebas unitarias JVM donde AndroidKeyStore no está montado
    private var jvmFallbackKey: SecretKey = generateRandomJvmKey()
    private var jvmEncryptedBlob: String? = null

    private fun generateRandomJvmKey(): SecretKey {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return SecretKeySpec(raw, "AES")
    }

    override fun saveRefreshToken(refreshToken: String) {
        if (refreshToken.isBlank()) {
            clear()
            return
        }
        try {
            val encrypted = encryptAesGcm(refreshToken.toByteArray(Charsets.UTF_8))
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs != null) {
                prefs.edit().putString(KEY_REFRESH_CIPHERTEXT, encrypted).apply()
            } else {
                jvmEncryptedBlob = encrypted
            }
            requiresRelogin = false
        } catch (e: Throwable) {
            handleKeyInvalidation(e)
        }
    }

    override fun loadRefreshToken(): String? {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val blob = if (prefs != null) {
            prefs.getString(KEY_REFRESH_CIPHERTEXT, null)
        } else {
            jvmEncryptedBlob
        } ?: return null

        return try {
            String(decryptAesGcm(blob), Charsets.UTF_8)
        } catch (e: Throwable) {
            handleKeyInvalidation(e)
            null
        }
    }

    override fun clear() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs != null) {
            prefs.edit().remove(KEY_REFRESH_CIPHERTEXT).apply()
        }
        jvmEncryptedBlob = null
    }

    /**
     * Simula la pérdida o invalidación de la clave maestra (p. ej. restauración de backup o cambio
     * de credenciales biométricas del dispositivo) para verificar que `loadRefreshToken()` no
     * cierra la app, limpia el token corrupto y solicita iniciar sesión de nuevo.
     */
    internal fun simulateKeyLossOrInvalidationForTest() {
        jvmFallbackKey = generateRandomJvmKey()
    }

    internal fun rawStoredCiphertextForAudit(): String? {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs != null) {
            prefs.getString(KEY_REFRESH_CIPHERTEXT, null)
        } else {
            jvmEncryptedBlob
        }
    }

    private fun handleKeyInvalidation(cause: Throwable) {
        val isKeyError = cause is KeyPermanentlyInvalidatedException ||
            cause is UnrecoverableKeyException ||
            cause is AEADBadTagException ||
            cause.cause is KeyPermanentlyInvalidatedException ||
            cause.cause is UnrecoverableKeyException ||
            cause.cause is AEADBadTagException

        // Eliminar la entrada corrupta del AndroidKeyStore para regenerarla limpia en el próximo login
        if (context != null && isKeyError) {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            }
        }
        clear()
        requiresRelogin = true
        onKeyInvalidated?.invoke()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (context == null) return jvmFallbackKey
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return if (existing != null) {
            existing.secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun encryptAesGcm(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getOrCreateSecretKey()
        if (context == null) {
            val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
        }
    }

    private fun decryptAesGcm(encoded: String): ByteArray {
        val combined = Base64.getUrlDecoder().decode(encoded)
        require(combined.size > GCM_IV_BYTES) { "INVALID_CIPHERTEXT" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
