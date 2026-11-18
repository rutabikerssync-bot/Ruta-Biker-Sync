package com.rutabikersync.app.core.mesh.audio

import com.rutabikersync.app.core.mesh.MeshHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trama binaria de audio PTT para transmisión directa por radiofrecuencia BLE.
 * Conforme a §5 del Pliego de Arquitectura:
 * - MeshHeader: 9 bytes
 * - frameIndex: 2 bytes (UInt16, posición de la trama de 20ms en la transmisión)
 * - flags: 1 byte (bit 0: isLastFrame / fin de transmisión PTT)
 * - nonce: 12 bytes
 * - encryptedPayloadWithTag: N bytes (Opus cifrado + 16 bytes Poly1305)
 *
 * Tamaño total en el aire: 80 a 100 bytes (CERO SAR, empaquetado atómico sin fragmentación).
 */
data class VoicePacket(
    val header: MeshHeader,
    val frameIndex: Int,
    val isLastFrame: Boolean,
    val nonce: ByteArray,
    val encryptedPayloadWithTag: ByteArray
) {
    init {
        require(nonce.size == VoiceCrypto.NONCE_SIZE_BYTES) { "El nonce debe ser de 12 bytes" }
        require(encryptedPayloadWithTag.size >= VoiceCrypto.TAG_SIZE_BYTES) {
            "El payload cifrado debe incluir el tag de 16 bytes"
        }
        require(frameIndex in 0..65535) { "frameIndex debe estar entre 0 y 65535" }
    }

    fun toBinary(): ByteArray {
        val headerBytes = header.toByteArray()
        val totalSize = headerBytes.size + 2 + 1 + nonce.size + encryptedPayloadWithTag.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(headerBytes)
        buffer.putShort(frameIndex.toShort())
        val flags = if (isLastFrame) 0x01.toByte() else 0x00.toByte()
        buffer.put(flags)
        buffer.put(nonce)
        buffer.put(encryptedPayloadWithTag)

        return buffer.array()
    }

    companion object {
        fun fromBinary(raw: ByteArray): VoicePacket {
            require(raw.size >= MeshHeader.HEADER_SIZE_BYTES + 2 + 1 + VoiceCrypto.NONCE_SIZE_BYTES + VoiceCrypto.TAG_SIZE_BYTES) {
                "Longitud de paquete de voz insuficiente: ${raw.size} bytes"
            }

            val header = MeshHeader.fromByteArray(raw, 0)
            require(header.packetType == MeshHeader.TYPE_PTT_VOICE) {
                "packetType inválido para VoicePacket: ${header.packetType}"
            }

            val buffer = ByteBuffer.wrap(raw, MeshHeader.HEADER_SIZE_BYTES, raw.size - MeshHeader.HEADER_SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)

            val frameIndex = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get()
            val isLastFrame = (flags.toInt() and 0x01) != 0

            val nonce = ByteArray(VoiceCrypto.NONCE_SIZE_BYTES)
            buffer.get(nonce)

            val remaining = buffer.remaining()
            val encryptedPayload = ByteArray(remaining)
            buffer.get(encryptedPayload)

            return VoicePacket(
                header = header,
                frameIndex = frameIndex,
                isLastFrame = isLastFrame,
                nonce = nonce,
                encryptedPayloadWithTag = encryptedPayload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoicePacket) return false

        if (header != other.header) return false
        if (frameIndex != other.frameIndex) return false
        if (isLastFrame != other.isLastFrame) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!encryptedPayloadWithTag.contentEquals(other.encryptedPayloadWithTag)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + frameIndex
        result = 31 * result + isLastFrame.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + encryptedPayloadWithTag.contentHashCode()
        return result
    }
}
