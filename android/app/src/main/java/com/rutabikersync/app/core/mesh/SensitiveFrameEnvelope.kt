package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.audio.VoiceCrypto
import java.util.LinkedHashMap
import javax.crypto.AEADBadTagException

/**
 * Sobre autenticado para los frames BLE sensibles de la malla (P0 cierre).
 *
 * Wire format (Big-Endian, sin campos opcionales):
 * - Bytes 0..1 (2 B): MAGIC 0x53 0x46 ("SF"). No colisiona con 0xA5 0x5A de
 *   [com.rutabikersync.app.core.transport.BleFragmentationCodec] ni con 0x47 0x56 de
 *   [com.rutabikersync.app.core.transport.GattVoiceFramer].
 * - Byte  2   (1 B): VERSIÓN = 0x01.
 * - Byte  3   (1 B): KIND: [KIND_SOS], [KIND_SOS_ACK] o [KIND_MESH_PACKET].
 * - Bytes 4..15 (12 B): nonce aleatorio criptográfico, generado por [VoiceCrypto.generateNonce].
 * - Bytes 16..N: ciphertext + tag Poly1305 de 16 bytes.
 *
 * El plaintext es el frame existente sin alterar: el HMAC-SHA256 de los `MeshPacket`
 * de 20 bytes y la firma Ed25519 de SOS/ACK se verifican DESPUÉS de descifrar.
 * La cabecera de 4 bytes se usa como AAD para evitar confusión de tipos.
 *
 * No modifica la lógica de [VoiceCrypto], `TextMessageCrypto`, GATT, PTT ni heartbeats.
 */
object SensitiveFrameEnvelope {

    const val MAGIC_0: Byte = 0x53
    const val MAGIC_1: Byte = 0x46
    const val VERSION: Byte = 0x01

    const val KIND_SOS: Byte = 0x01
    const val KIND_SOS_ACK: Byte = 0x02
    const val KIND_MESH_PACKET: Byte = 0x03

    const val KEY_SIZE_BYTES: Int = VoiceCrypto.KEY_SIZE_BYTES
    const val NONCE_SIZE_BYTES: Int = VoiceCrypto.NONCE_SIZE_BYTES
    const val TAG_SIZE_BYTES: Int = VoiceCrypto.TAG_SIZE_BYTES
    const val HEADER_BYTES: Int = 4 + NONCE_SIZE_BYTES

    private val VALID_KINDS = setOf(KIND_SOS, KIND_SOS_ACK, KIND_MESH_PACKET)

    fun isEnvelope(frame: ByteArray): Boolean =
        frame.size >= HEADER_BYTES + TAG_SIZE_BYTES &&
            frame[0] == MAGIC_0 &&
            frame[1] == MAGIC_1 &&
            frame[2] == VERSION &&
            frame[3] in VALID_KINDS

    fun kindOf(frame: ByteArray): Byte? = if (isEnvelope(frame)) frame[3] else null

    fun nonceOf(frame: ByteArray): ByteArray? =
        if (isEnvelope(frame)) frame.copyOfRange(4, HEADER_BYTES) else null

    private fun aadFor(kind: Byte): ByteArray = byteArrayOf(MAGIC_0, MAGIC_1, VERSION, kind)

    /** Envuelve un frame existente con ChaCha20-Poly1305 reutilizando el cifrador de voz/PTT. */
    fun wrap(
        key: ByteArray,
        kind: Byte,
        plaintext: ByteArray,
        nonce: ByteArray = VoiceCrypto.generateNonce()
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de $KEY_SIZE_BYTES bytes" }
        require(plaintext.isNotEmpty()) { "El frame sensible no puede estar vacío" }
        require(kind in VALID_KINDS) { "Kind de frame sensible inválido: $kind" }
        require(nonce.size == NONCE_SIZE_BYTES) { "El nonce debe ser de $NONCE_SIZE_BYTES bytes" }

        val encrypted = VoiceCrypto.encrypt(key, nonce, plaintext, aadFor(kind))
        val frame = ByteArray(HEADER_BYTES + encrypted.size)
        frame[0] = MAGIC_0
        frame[1] = MAGIC_1
        frame[2] = VERSION
        frame[3] = kind
        nonce.copyInto(frame, 4)
        encrypted.copyInto(frame, HEADER_BYTES)
        VoiceCrypto.zeroBuffer(encrypted)
        return frame
    }

    /**
     * Verifica el tag AEAD y devuelve el frame original.
     * Lanza [AEADBadTagException] si el ciphertext, el nonce, el tipo o la clave fueron alterados.
     */
    @Throws(AEADBadTagException::class)
    fun unwrap(key: ByteArray, frame: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "La clave debe ser de $KEY_SIZE_BYTES bytes" }
        require(isEnvelope(frame)) { "Frame sensible inválido o versión no soportada" }

        val kind = frame[3]
        val nonce = frame.copyOfRange(4, HEADER_BYTES)
        val ciphertextAndTag = frame.copyOfRange(HEADER_BYTES, frame.size)
        return try {
            VoiceCrypto.decrypt(key, nonce, ciphertextAndTag, aadFor(kind))
        } finally {
            VoiceCrypto.zeroBuffer(nonce)
            VoiceCrypto.zeroBuffer(ciphertextAndTag)
        }
    }

    /**
     * Guardia LRU de nonces. Comprobar antes de descifrar; registrar solo después de
     * que el tag AEAD haya sido válido para no envenenar la caché con nonces forjados.
     */
    class ReplayGuard(private val capacity: Int = 2048) {

        private val seen = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > capacity
        }

        private fun ByteArray.asKey(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        @Synchronized
        fun isReplay(nonce: ByteArray): Boolean = seen.containsKey(nonce.asKey())

        @Synchronized
        fun registerIfNew(nonce: ByteArray): Boolean {
            val key = nonce.asKey()
            if (seen.containsKey(key)) return false
            seen[key] = true
            return true
        }

        @Synchronized
        fun clear() = seen.clear()
    }
}
