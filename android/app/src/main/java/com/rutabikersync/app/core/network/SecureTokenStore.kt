package com.rutabikersync.app.core.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Almacenamiento seguro de tokens de sesión en Android (Decisión D40).
 * - `accessToken` (30 min) vive únicamente en memoria volátil del proceso.
 * - `refreshToken` (30 días) se cifra con AES-256-GCM cuya clave maestra reside en
 *   `AndroidKeyStore` (`rbs_session_master_key_v1`) antes de guardarse en preferencias privadas,
 *   nunca en texto plano.
 */
interface TokenCipherStore {
    fun saveRefreshToken(refreshToken: String)
    fun loadRefreshToken(): String?
    fun clear()
}

class KeystoreEncryptedTokenStore(
    private val context: Context? = null
) : TokenCipherStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rbs_session_master_key_v1"
        private const val PREFS_NAME = "rbs_encrypted_session_store"
        private const val KEY_REFRESH_CIPHERTEXT = "refresh_token_aes256_gcm"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    // Respaldo en memoria cifrada para pruebas unitarias JVM donde AndroidKeyStore no está montado
    private val jvmFallbackKey: SecretKey by lazy {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        SecretKeySpec(raw, "AES")
    }
    private var jvmEncryptedBlob: String? = null

    override fun saveRefreshToken(refreshToken: String) {
        if (refreshToken.isBlank()) {
            clear()
            return
        }
        val encrypted = encryptAesGcm(refreshToken.toByteArray(Charsets.UTF_8))
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs != null) {
            prefs.edit().putString(KEY_REFRESH_CIPHERTEXT, encrypted).apply()
        } else {
            jvmEncryptedBlob = encrypted
        }
    }

    override fun loadRefreshToken(): String? {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val blob = if (prefs != null) {
            prefs.getString(KEY_REFRESH_CIPHERTEXT, null)
        } else {
            jvmEncryptedBlob
        } ?: return null
        return runCatching {
            String(decryptAesGcm(blob), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun clear() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs != null) {
            prefs.edit().remove(KEY_REFRESH_CIPHERTEXT).apply()
        }
        jvmEncryptedBlob = null
    }

    internal fun rawStoredCiphertextForAudit(): String? {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs != null) {
            prefs.getString(KEY_REFRESH_CIPHERTEXT, null)
        } else {
            jvmEncryptedBlob
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (context == null) return jvmFallbackKey
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
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
        } catch (_: Throwable) {
            jvmFallbackKey
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
