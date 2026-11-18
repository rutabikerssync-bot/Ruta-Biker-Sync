package com.rutabikersync.app.core.mesh.text

import com.rutabikersync.app.core.mesh.MeshHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trama de Mensajería de Texto Táctica Off-Grid (Fase 6 · Hito 5 · Etapa 1).
 *
 * Formato Wire Compacto (Big-Endian, ≤ 161 bytes exactos, cero fragmentación SAR):
 * - Bytes 0..8:   MeshHeader (9 Bytes, TYPE_TEXT_MESSAGE = 0x04)
 * - Bytes 9..10:  targetShortId (uint16, 2 Bytes; 0xFFFF para broadcast a todo el convoy)
 * - Byte 11:      messageLength (uint8, 1 Byte; longitud del texto plano UTF-8, máx 120 bytes)
 * - Bytes 12..23: iv (12 Bytes; nonce criptográfico único generado por SecureRandom)
 * - Bytes 24..39: authTag (16 Bytes; tag Poly1305 para autenticación e integridad AEAD)
 * - Bytes 40..(40 + N - 1): ciphertext (N bytes, donde N == messageLength, máx 120 bytes)
 * - Byte 40 + N:  hopCount (uint8, 1 Byte; mutable para auditoría multi-salto)
 *
 * Tamaño Total: 41 + N bytes (42 bytes mínimo con 1 byte de texto, 161 bytes máximo con 120 bytes).
 * Cumple holgadamente el límite de 168 bytes para paquetes directos en BLE Extended Advertising.
 */
data class TextMessagePacket(
    val header: MeshHeader,
    val targetShortId: UShort,
    val messageLength: UByte,
    val iv: ByteArray,
    val authTag: ByteArray,
    val ciphertext: ByteArray,
    val hopCount: Byte = 0
) {
    init {
        require(iv.size == IV_SIZE_BYTES) { "iv debe tener $IV_SIZE_BYTES bytes, recibido ${iv.size}" }
        require(authTag.size == AUTH_TAG_SIZE_BYTES) { "authTag debe tener $AUTH_TAG_SIZE_BYTES bytes, recibido ${authTag.size}" }
        require(ciphertext.size == messageLength.toInt()) {
            "El tamaño de ciphertext (${ciphertext.size}) no coincide con messageLength ($messageLength)"
        }
        require(ciphertext.size in 1..MAX_PLAINTEXT_BYTES) {
            "Longitud del mensaje fuera de rango (1..$MAX_PLAINTEXT_BYTES): ${ciphertext.size}"
        }
    }

    val isBroadcast: Boolean
        get() = targetShortId == BROADCAST_TARGET

    /**
     * Serializa la trama completa a binario Big-Endian.
     */
    fun toByteArray(): ByteArray {
        val totalSize = FIXED_OVERHEAD_BYTES + ciphertext.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(header.toByteArray())
        buffer.putShort(targetShortId.toShort())
        buffer.put(messageLength.toByte())
        buffer.put(iv)
        buffer.put(authTag)
        buffer.put(ciphertext)
        buffer.put(hopCount)

        return buffer.array()
    }

    /**
     * Retorna una copia de la trama incrementando hopCount y actualizando el TTL de la cabecera.
     */
    fun copyWithIncrementedHop(newTtl: Byte): TextMessagePacket {
        val currentHops = (hopCount.toInt() and 0xFF) + 1
        return copy(
            header = header.copyWithTtl(newTtl),
            hopCount = currentHops.toByte()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextMessagePacket) return false

        if (header != other.header) return false
        if (targetShortId != other.targetShortId) return false
        if (messageLength != other.messageLength) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (hopCount != other.hopCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + targetShortId.hashCode()
        result = 31 * result + messageLength.hashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + hopCount.toInt()
        return result
    }

    companion object {
        const val BROADCAST_TARGET: UShort = 0xFFFFu

        const val IV_SIZE_BYTES = 12
        const val AUTH_TAG_SIZE_BYTES = 16
        const val MAX_PLAINTEXT_BYTES = 120

        /**
         * Overhead fijo:
         * MeshHeader (9B) + targetShortId (2B) + messageLength (1B) + iv (12B) + authTag (16B) + hopCount (1B) = 41 Bytes.
         */
        const val FIXED_OVERHEAD_BYTES = MeshHeader.HEADER_SIZE_BYTES + 2 + 1 + IV_SIZE_BYTES + AUTH_TAG_SIZE_BYTES + 1

        const val MIN_PACKET_SIZE = FIXED_OVERHEAD_BYTES + 1 // 42 Bytes
        const val MAX_PACKET_SIZE = FIXED_OVERHEAD_BYTES + MAX_PLAINTEXT_BYTES // 161 Bytes

        /**
         * Deserializa un [TextMessagePacket] desde un arreglo de bytes.
         */
        fun fromByteArray(bytes: ByteArray, offset: Int = 0): TextMessagePacket {
            require(bytes.size >= offset + MIN_PACKET_SIZE) {
                "Buffer insuficiente para TextMessagePacket: tamaño ${bytes.size}, requerido al menos ${offset + MIN_PACKET_SIZE}"
            }

            val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)

            val headerBytes = ByteArray(MeshHeader.HEADER_SIZE_BYTES)
            buffer.get(headerBytes)
            val header = MeshHeader.fromByteArray(headerBytes, 0)

            val targetId = buffer.short.toUShort()
            val msgLenByte = buffer.get()
            val msgLen = msgLenByte.toInt() and 0xFF

            require(msgLen in 1..MAX_PLAINTEXT_BYTES) {
                "messageLength inválido ($msgLen). Debe ser entre 1 y $MAX_PLAINTEXT_BYTES bytes"
            }

            val expectedTotal = FIXED_OVERHEAD_BYTES + msgLen
            require(bytes.size - offset >= expectedTotal) {
                "Buffer incompleto para TextMessagePacket: disponible ${bytes.size - offset}, esperado $expectedTotal"
            }

            val iv = ByteArray(IV_SIZE_BYTES)
            buffer.get(iv)

            val authTag = ByteArray(AUTH_TAG_SIZE_BYTES)
            buffer.get(authTag)

            val ciphertext = ByteArray(msgLen)
            buffer.get(ciphertext)

            val hopCount = buffer.get()

            return TextMessagePacket(
                header = header,
                targetShortId = targetId,
                messageLength = msgLen.toUByte(),
                iv = iv,
                authTag = authTag,
                ciphertext = ciphertext,
                hopCount = hopCount
            )
        }
    }
}
