package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.audio.VoiceCrypto
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
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
 * - Byte  3   (1 B): KIND: [KIND_SOS], [KIND_SOS_ACK], [KIND_MESH_PACKET] o [KIND_HEARTBEAT].
 * - Bytes 4..15 (12 B): nonce aleatorio criptográfico, generado por [VoiceCrypto.generateNonce].
 * - Bytes 16..N: ciphertext + tag Poly1305 de 16 bytes.
 *
 * El plaintext es el frame existente sin alterar: el HMAC-SHA256 de los `MeshPacket`
 * de 20 bytes y la firma Ed25519 de SOS/ACK se verifican DESPUÉS de descifrar.
 * La cabecera de 4 bytes se usa como AAD para evitar confusión de tipos.
 *
 * No modifica la lógica de [VoiceCrypto], `TextMessageCrypto`, GATT ni PTT. Los
 * heartbeats (MeshPacket de 20 B con prioridad POSITION) pasan a usar este sobre.
 */
object SensitiveFrameEnvelope {

    const val MAGIC_0: Byte = 0x53
    const val MAGIC_1: Byte = 0x46
    const val VERSION: Byte = 0x01

    const val KIND_SOS: Byte = 0x01
    const val KIND_SOS_ACK: Byte = 0x02
    const val KIND_MESH_PACKET: Byte = 0x03
    /** Heartbeat de posición: MeshPacket de 20 B con prioridad POSITION. */
    const val KIND_HEARTBEAT: Byte = 0x04
    /**
     * Control/ciclo firmado: mismo AEAD que KIND_MESH_PACKET con una firma Ed25519 de
     * 64 bytes concatenada al final. Solo se acepta en modo autorizado; el HMAC de 4 B
     * del MeshPacket queda como checksum interno, nunca como autorización.
     */
    const val KIND_SIGNED_CONTROL: Byte = 0x05

    const val KEY_SIZE_BYTES: Int = VoiceCrypto.KEY_SIZE_BYTES
    const val NONCE_SIZE_BYTES: Int = VoiceCrypto.NONCE_SIZE_BYTES
    const val TAG_SIZE_BYTES: Int = VoiceCrypto.TAG_SIZE_BYTES
    const val HEADER_BYTES: Int = 4 + NONCE_SIZE_BYTES
    const val SIGNATURE_SIZE_BYTES: Int = 64

    /** Dominio separado para que una firma de control no pueda reutilizarse como SOS/ACK. */
    private val SIGNED_CONTROL_DOMAIN = "RBS-SIGNED-CONTROL-V1".toByteArray(Charsets.US_ASCII)

    private val VALID_KINDS = setOf(
        KIND_SOS, KIND_SOS_ACK, KIND_MESH_PACKET, KIND_HEARTBEAT, KIND_SIGNED_CONTROL
    )

    /** El tipo del sobre se decide por la prioridad real del packet, nunca por el caller. */
    fun envelopeKindFor(packet: MeshPacket): Byte =
        if (packet.priority == Transport.Priority.POSITION && !packet.isSos) KIND_HEARTBEAT
        else KIND_MESH_PACKET

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
        // En KIND_SIGNED_CONTROL la firma viaja DESPUÉS del tag AEAD; incluirla en el
        // ciphertext haría fallar Poly1305. Los demás kinds conservan su tamaño exacto.
        val ciphertextEnd = frame.size - if (kind == KIND_SIGNED_CONTROL) SIGNATURE_SIZE_BYTES else 0
        val ciphertextAndTag = frame.copyOfRange(HEADER_BYTES, ciphertextEnd)
        return try {
            VoiceCrypto.decrypt(key, nonce, ciphertextAndTag, aadFor(kind))
        } finally {
            VoiceCrypto.zeroBuffer(nonce)
            VoiceCrypto.zeroBuffer(ciphertextAndTag)
        }
    }

    /**
     * Payload canónico firmado: dominio + cabecera del sobre + los 16 bytes del
     * MeshPacket con los 4 bits bajos de `currentTtl` enmascarados. Enmascarar el TTL
     * permite que un relay decrementa TTL pueda reenviar conservando la firma del emisor.
     */
    fun controlSignablePayload(meshPacket20Bytes: ByteArray): ByteArray {
        require(meshPacket20Bytes.size == MeshPacket.PACKET_SIZE_BYTES) {
            "El control firmado exige un MeshPacket de ${MeshPacket.PACKET_SIZE_BYTES} bytes"
        }
        val canonical = meshPacket20Bytes.copyOf()
        canonical[15] = (canonical[15].toInt() and 0xF0).toByte()
        return SIGNED_CONTROL_DOMAIN + byteArrayOf(MAGIC_0, MAGIC_1, VERSION, KIND_SIGNED_CONTROL) + canonical
    }

    /** Firma extraída del final del sobre; `null` si no es un sobre de control firmado. */
    fun signatureOf(frame: ByteArray): ByteArray? =
        if (kindOf(frame) == KIND_SIGNED_CONTROL && frame.size >= HEADER_BYTES + TAG_SIZE_BYTES + SIGNATURE_SIZE_BYTES) {
            frame.copyOfRange(frame.size - SIGNATURE_SIZE_BYTES, frame.size)
        } else {
            null
        }

    /** Envuelve un MeshPacket de control con ChaCha20-Poly1305 y su firma Ed25519. */
    fun wrapSigned(
        key: ByteArray,
        meshPacket20Bytes: ByteArray,
        signature: ByteArray,
        nonce: ByteArray = VoiceCrypto.generateNonce()
    ): ByteArray {
        require(signature.size == SIGNATURE_SIZE_BYTES) { "La firma Ed25519 debe ser de $SIGNATURE_SIZE_BYTES bytes" }
        require(meshPacket20Bytes.size == MeshPacket.PACKET_SIZE_BYTES) {
            "El control firmado exige un MeshPacket de ${MeshPacket.PACKET_SIZE_BYTES} bytes"
        }
        return wrap(key, KIND_SIGNED_CONTROL, meshPacket20Bytes, nonce) + signature
    }

    /** Devuelve el MeshPacket y la firma Ed25519; lanza si el tag AEAD es inválido. */
    @Throws(AEADBadTagException::class)
    fun unwrapSigned(key: ByteArray, frame: ByteArray): Pair<ByteArray, ByteArray> {
        require(kindOf(frame) == KIND_SIGNED_CONTROL) { "No es un sobre de control firmado" }
        val signature = signatureOf(frame)
            ?: throw AEADBadTagException("Firma Ed25519 ausente o truncada")
        return unwrap(key, frame) to signature
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
