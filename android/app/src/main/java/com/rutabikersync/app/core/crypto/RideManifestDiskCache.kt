package com.rutabikersync.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * P0 auditoría 2026-11-25 — caché en disco del manifiesto firmado de la rodada.
 *
 * Motivo: [RideManifestKeyProvider] solo conservaba el manifiesto en RAM. Si el
 * proceso moría, el móvil se reiniciaba o se perdía la red (backend caído), la
 * malla no podía arrancar porque el manifiesto exige HTTP. En la rodada del
 * 2026-11-25 eso dejó a los riders sin malla durante 83 minutos.
 *
 * Garantías:
 * - Se guarda el token firmado y las claves públicas del keyring. Nada se confía
 *   al leer: [RideManifestKeyProvider.resolveOffline] re-verifica firma Ed25519,
 *   expiración y pertenencia del participante antes de devolver nada.
 * - El blob se cifra con AES-256-GCM y una clave maestra en `AndroidKeyStore`
 *   (alias `rbs_ride_manifest_cache_v1`), mismo patrón que `SecureTokenStore`.
 * - Caducidad local de 24 h para no resucitar una rodada vieja aunque el `exp`
 *   del manifiesto sea mayor.
 * - En tests JVM (`context == null`) usa una clave JCE en memoria: el
 *   round-trip se prueba sin `AndroidKeyStore`.
 */
class RideManifestDiskCache(private val context: Context? = null) {

    @Serializable
    data class Entry(
        val rideId: String,
        val manifestToken: String,
        val publicKeys: Map<String, String>,
        val participantUid: String,
        val savedAtEpochSec: Long
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rbs_ride_manifest_cache_v1"
        const val PREFS_NAME = "rbs_ride_manifest_cache"
        private const val KEY_ENTRY_PREFIX = "manifest_"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Tope local: nunca se restaura una rodada cacheada hace más de 24 h. */
        const val MAX_CACHE_AGE_SEC: Long = 24L * 60L * 60L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun entryKey(rideId: String): String =
            KEY_ENTRY_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rideId.toByteArray(Charsets.UTF_8))
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Respaldo en memoria para tests JVM donde AndroidKeyStore no existe.
    private val jvmFallbackKey: SecretKey = run {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        SecretKeySpec(raw, "AES")
    }
    private val jvmBlobs = mutableMapOf<String, String>()

    fun save(entry: Entry) {
        val plaintext = json.encodeToString(Entry.serializer(), entry).toByteArray(Charsets.UTF_8)
        val blob = runCatching { encryptAesGcm(plaintext) }.getOrNull() ?: return
        val key = entryKey(entry.rideId)
        if (prefs != null) {
            prefs.edit().putString(key, blob).apply()
        } else {
            jvmBlobs[key] = blob
        }
    }

    /**
     * Devuelve la entrada solo si descifra, parsea, corresponde al `rideId` pedido
     * y no supera [MAX_CACHE_AGE_SEC]. Cualquier anomalía borra la entrada.
     */
    fun load(rideId: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Entry? {
        val key = entryKey(rideId)
        val blob = prefs?.getString(key, null) ?: jvmBlobs[key] ?: return null
        val entry = runCatching {
            val plaintext = decryptAesGcm(blob)
            json.decodeFromString(Entry.serializer(), String(plaintext, Charsets.UTF_8))
        }.getOrNull()
        if (entry == null || entry.rideId != rideId) {
            clear(rideId)
            return null
        }
        val age = nowEpochSec - entry.savedAtEpochSec
        if (age < 0 || age > MAX_CACHE_AGE_SEC) {
            clear(rideId)
            return null
        }
        return entry
    }

    fun clear(rideId: String) {
        val key = entryKey(rideId)
        if (prefs != null) {
            prefs.edit().remove(key).apply()
        } else {
            jvmBlobs.remove(key)
        }
    }

    fun clearAll() {
        if (prefs != null) {
            prefs.edit().clear().apply()
        } else {
            jvmBlobs.clear()
        }
    }

    /** Solo para auditoría/tests: nunca se expone a la UI. */
    internal fun rawStoredCiphertextForAudit(rideId: String): String? {
        val key = entryKey(rideId)
        return prefs?.getString(key, null) ?: jvmBlobs[key]
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (context == null) return jvmFallbackKey
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encryptAesGcm(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getOrCreateSecretKey()
        val iv: ByteArray
        if (context == null) {
            iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            iv = cipher.iv
        }
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
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
