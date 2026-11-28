package com.rutabikersync.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Auditoría integral 2026-11-27 · riesgo #2 — anti-replay persistente.
 *
 * `SensitiveFrameEnvelope.ReplayGuard` y `RideMeshAuthorization.acceptSequence` solo
 * vivían en RAM: tras un reinicio del proceso un atacante podía reinyectar tramas
 * capturadas. Este store guarda, por `(rideId, senderShortId)`, la última secuencia
 * aceptada y una ventana acotada de nonces vistos.
 *
 * Garantías:
 * - AES-256-GCM con clave maestra en `AndroidKeyStore` (alias
 *   `rbs_ride_replay_state_v1`), el mismo patrón que [RideManifestDiskCache].
 * - El estado se borra **solo** al cerrar la rodada; un reinicio del servicio lo conserva.
 * - Cualquier anomalía al descifrar/parsear descarta la entrada (fail-closed para
 *   esa entrada: se tratará como emisor nuevo, sin nonces antiguos aceptados).
 * - En tests JVM (`context == null`) usa una clave JCE compartida por proceso y un
 *   mapa en memoria, para poder simular reinicios entre instancias.
 *
 * No toca el wire format BLE, GATT, `HeartbeatFrame` ni PTT.
 */
class RideReplayStateStore(private val context: Context? = null) {

    @Serializable
    data class SenderState(
        val rideId: String,
        val senderShortId: Int,
        val lastSequence: Int,
        val hasSequence: Boolean = true,
        val recentNonces: List<String> = emptyList(),
        val updatedAtEpochSec: Long = 0L
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rbs_ride_replay_state_v1"
        const val PREFS_NAME = "rbs_ride_replay_state"
        private const val KEY_PREFIX = "sender_"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Ventana acotada: 512 nonces por emisor (~24 KiB cifrados como máximo). */
        const val MAX_NONCES_PER_SENDER = 512

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Compartidos por proceso para que los tests JVM puedan simular un reinicio. */
        private val jvmFallbackKey: SecretKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { SecretKeySpec(it, "AES") }
        private val jvmBlobs = ConcurrentHashMap<String, String>()

        private fun ridePrefix(rideId: String): String =
            KEY_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rideId.toByteArray(Charsets.UTF_8)) + "_"

        private fun entryKey(rideId: String, shortId: Int): String =
            ridePrefix(rideId) + "%04x".format(shortId and 0xFFFF)
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Devuelve el estado solo si descifra y corresponde exactamente a la clave pedida. */
    fun load(rideId: String, shortId: Int): SenderState? {
        val key = entryKey(rideId, shortId)
        val blob = readBlob(key) ?: return null
        val state = runCatching {
            val plaintext = decryptAesGcm(blob)
            json.decodeFromString(SenderState.serializer(), String(plaintext, Charsets.UTF_8))
        }.getOrNull()
        if (state == null || state.rideId != rideId || state.senderShortId != (shortId and 0xFFFF)) {
            removeBlob(key)
            return null
        }
        return state.copy(recentNonces = state.recentNonces.takeLast(MAX_NONCES_PER_SENDER))
    }

    /** `false` significa que no se pudo persistir: el llamador debe fallar cerrado. */
    fun save(state: SenderState): Boolean {
        require(state.rideId.isNotBlank()) { "rideId vacío en el estado anti-replay" }
        val shortId = state.senderShortId and 0xFFFF
        val trimmed = state.copy(
            senderShortId = shortId,
            recentNonces = state.recentNonces.takeLast(MAX_NONCES_PER_SENDER)
        )
        val blob = runCatching {
            val plaintext = json.encodeToString(SenderState.serializer(), trimmed)
                .toByteArray(Charsets.UTF_8)
            encryptAesGcm(plaintext)
        }.getOrNull() ?: return false
        writeBlob(entryKey(state.rideId, shortId), blob)
        return true
    }

    /** Limpia todos los emisores de una rodada al cerrarla. */
    fun clear(rideId: String) {
        val prefix = ridePrefix(rideId)
        if (prefs != null) {
            val keys = prefs.all.keys.filter { it.startsWith(prefix) }
            if (keys.isNotEmpty()) {
                prefs.edit().apply { keys.forEach { remove(it) } }.apply()
            }
        } else {
            jvmBlobs.keys.filter { it.startsWith(prefix) }.forEach { jvmBlobs.remove(it) }
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
    internal fun rawStoredCiphertextForAudit(rideId: String, shortId: Int): String? =
        readBlob(entryKey(rideId, shortId))

    /** Solo tests JVM: simula manipulación del blob cifrado sin acceder a SharedPreferences. */
    internal fun replaceStoredCiphertextForAudit(rideId: String, shortId: Int, blob: String) {
        writeBlob(entryKey(rideId, shortId), blob)
    }

    private fun readBlob(key: String): String? = prefs?.getString(key, null) ?: jvmBlobs[key]

    private fun writeBlob(key: String, blob: String) {
        if (prefs != null) prefs.edit().putString(key, blob).apply() else jvmBlobs[key] = blob
    }

    private fun removeBlob(key: String) {
        if (prefs != null) prefs.edit().remove(key).apply() else jvmBlobs.remove(key)
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
