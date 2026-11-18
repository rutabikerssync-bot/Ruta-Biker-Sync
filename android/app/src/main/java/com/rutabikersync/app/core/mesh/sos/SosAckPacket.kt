package com.rutabikersync.app.core.mesh.sos

import com.rutabikersync.app.core.mesh.MeshHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trama de Confirmación de Recepción de SOS (SosAckPacket) (Fase 6 · Hito 4).
 *
 * Transmitida por el Líder, Sargento o compañero que recibe y procesa una alerta SOS.
 * Permite al nodo emisor confirmar que su señal de auxilio fue captada y detener alarmas acústicas continuas.
 *
 * Formato Wire (Big-Endian, 83 Bytes exactos empaquetados):
 * - Bytes 0..8:   MeshHeader (9 Bytes, packetType = TYPE_SOS_ACK = 0x06)
 * - Bytes 9..10:  original_sender_short_id (uint16, 2 Bytes, nodo accidentado que emitió el SOS)
 * - Bytes 11..12: original_sequence_num (uint16, 2 Bytes, número de secuencia del SOS confirmado)
 * - Bytes 13..16: ack_timestamp_epoch_sec (uint32, 4 Bytes, UTC de emisión del ACK)
 * - Bytes 17..18: acker_short_id (uint16, 2 Bytes, nodo que confirma la recepción)
 * - Bytes 19..82: ed25519_signature (64 Bytes, firma asimétrica del acker sobre los 10 bytes anteriores [9..18])
 *
 * Total: 9 + 2 + 2 + 4 + 2 + 64 = 83 Bytes (<= 88 Bytes, cero fragmentación SAR).
 */
data class SosAckPacket(
    val header: MeshHeader,
    val originalSenderShortId: UShort,
    val originalSequenceNum: UShort,
    val ackTimestampEpochSec: Int,
    val ackerShortId: UShort,
    val signature: ByteArray
) {
    init {
        require(header.packetType == MeshHeader.TYPE_SOS_ACK) {
            "SosAckPacket requiere MeshHeader.packetType == TYPE_SOS_ACK (0x06), recibido: ${header.packetType}"
        }
        require(signature.size == SIGNATURE_SIZE_BYTES) {
            "Firma Ed25519 del ACK debe medir exactamente $SIGNATURE_SIZE_BYTES bytes, recibido: ${signature.size}"
        }
    }

    /**
     * Retorna los 10 bytes canónicos del payload del ACK sobre los cuales se calcula/verifica la firma Ed25519.
     */
    fun getSignableData(): ByteArray {
        val buffer = ByteBuffer.allocate(SIGNABLE_DATA_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(originalSenderShortId.toShort())
        buffer.putShort(originalSequenceNum.toShort())
        buffer.putInt(ackTimestampEpochSec)
        buffer.putShort(ackerShortId.toShort())
        return buffer.array()
    }

    /**
     * Serializa la trama completa a un arreglo de 83 bytes exactos.
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.toByteArray())
        buffer.putShort(originalSenderShortId.toShort())
        buffer.putShort(originalSequenceNum.toShort())
        buffer.putInt(ackTimestampEpochSec)
        buffer.putShort(ackerShortId.toShort())
        buffer.put(signature)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SosAckPacket

        if (header != other.header) return false
        if (originalSenderShortId != other.originalSenderShortId) return false
        if (originalSequenceNum != other.originalSequenceNum) return false
        if (ackTimestampEpochSec != other.ackTimestampEpochSec) return false
        if (ackerShortId != other.ackerShortId) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + originalSenderShortId.hashCode()
        result = 31 * result + originalSequenceNum.hashCode()
        result = 31 * result + ackTimestampEpochSec
        result = 31 * result + ackerShortId.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        const val PACKET_SIZE_BYTES = 83
        const val SIGNABLE_DATA_SIZE_BYTES = 10
        const val SIGNATURE_SIZE_BYTES = 64

        fun create(
            clubNetworkId: UInt,
            ackerShortId: UShort,
            sequenceNum: UShort,
            originalSenderShortId: UShort,
            originalSequenceNum: UShort,
            ackTimestampEpochSec: Int,
            signature: ByteArray,
            currentTtl: Int = 5
        ): SosAckPacket {
            val header = MeshHeader(
                clubNetworkId = clubNetworkId,
                senderShortId = ackerShortId,
                packetType = MeshHeader.TYPE_SOS_ACK,
                initTtl = maxOf(5, currentTtl),
                currentTtl = currentTtl,
                sequenceNum = sequenceNum
            )
            return SosAckPacket(
                header = header,
                originalSenderShortId = originalSenderShortId,
                originalSequenceNum = originalSequenceNum,
                ackTimestampEpochSec = ackTimestampEpochSec,
                ackerShortId = ackerShortId,
                signature = signature
            )
        }

        fun fromByteArray(bytes: ByteArray, offset: Int = 0): SosAckPacket {
            require(bytes.size >= offset + PACKET_SIZE_BYTES) {
                "Buffer insuficiente para SosAckPacket: tamaño ${bytes.size}, requerido ${offset + PACKET_SIZE_BYTES}"
            }
            val header = MeshHeader.fromByteArray(bytes, offset)
            val buffer = ByteBuffer.wrap(bytes, offset + MeshHeader.HEADER_SIZE_BYTES, PACKET_SIZE_BYTES - MeshHeader.HEADER_SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)

            val originalSender = buffer.short.toUShort()
            val originalSeq = buffer.short.toUShort()
            val ackTimestamp = buffer.int
            val acker = buffer.short.toUShort()
            val signature = ByteArray(SIGNATURE_SIZE_BYTES)
            buffer.get(signature)

            return SosAckPacket(
                header = header,
                originalSenderShortId = originalSender,
                originalSequenceNum = originalSeq,
                ackTimestampEpochSec = ackTimestamp,
                ackerShortId = acker,
                signature = signature
            )
        }
    }
}

