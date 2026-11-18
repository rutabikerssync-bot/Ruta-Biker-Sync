package com.rutabikersync.app.core.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cabecera Universal de Malla (Fase 6 · Hito 1 y Hito 3 · BLE_MESH_ARCHITECTURE_HITO_0.md §5.1).
 *
 * Formato Wire (Big-Endian, 9 Bytes exactos empaquetados):
 * - Bytes 0..3: club_network_id (uint32, 4 Bytes)
 * - Bytes 4..5: sender_short_id (uint16, 2 Bytes)
 * - Byte 6:     type_ttl_byte (1 Byte):
 *               - Modo Legacy Hito 1 (Bit 7 = 0):
 *                 packet_type (4 bits: bits 7..4) | init_ttl (2 bits: bits 3..2) | current_ttl (2 bits: bits 1..0)
 *               - Modo Extendido Multi-Salto Hito 3 (Bit 7 = 1):
 *                 flag (1 bit: bit 7 = 1) | packet_type (3 bits: bits 6..4) | current_ttl (4 bits: bits 3..0, soporta 0..15)
 * - Bytes 7..8: sequence_num (uint16, 2 Bytes)
 */
data class MeshHeader(
    val clubNetworkId: UInt,
    val senderShortId: UShort,
    val packetType: Int,
    val initTtl: Int,
    val currentTtl: Int,
    val sequenceNum: UShort
) {
    init {
        require(packetType in 0..0x0F) { "packetType fuera de rango 4-bit (0..15): $packetType" }
        require(initTtl in 0..15) { "initTtl fuera de rango (0..15): $initTtl" }
        require(currentTtl in 0..15) { "currentTtl fuera de rango (0..15): $currentTtl" }
        require(currentTtl <= initTtl) { "currentTtl ($currentTtl) no puede superar initTtl ($initTtl)" }
    }

    companion object {
        const val HEADER_SIZE_BYTES = 9

        const val TYPE_HEARTBEAT = 0x1
        const val TYPE_SOS = 0x2
        const val TYPE_PTT_VOICE = 0x3
        const val TYPE_TEXT_MSG = 0x4
        const val TYPE_TEXT_MESSAGE = TYPE_TEXT_MSG // Alias oficial Hito 5 (0x04 en modo extendido 3-bit)
        const val TYPE_CONTROL_SYNC = 0x5
        const val TYPE_SOS_ACK = 0x6
        const val TYPE_MEDICAL_SHEET = 0x7

        fun fromByteArray(bytes: ByteArray, offset: Int = 0): MeshHeader {
            require(bytes.size >= offset + HEADER_SIZE_BYTES) {
                "Buffer insuficiente para MeshHeader: tamaño ${bytes.size}, requerido ${offset + HEADER_SIZE_BYTES}"
            }
            val buffer = ByteBuffer.wrap(bytes, offset, HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            val clubId = buffer.int.toUInt()
            val senderId = buffer.short.toUShort()
            val typeTtlByte = buffer.get().toInt() and 0xFF

            val packetType: Int
            val initTtl: Int
            val currentTtl: Int

            if ((typeTtlByte and 0x80) != 0) {
                // Modo Extendido Multi-Salto Hito 3 (Bit 7 = 1)
                packetType = (typeTtlByte ushr 4) and 0x07
                currentTtl = typeTtlByte and 0x0F
                initTtl = maxOf(5, currentTtl)
            } else {
                // Modo Legacy Hito 1 (Bit 7 = 0)
                packetType = (typeTtlByte ushr 4) and 0x0F
                initTtl = (typeTtlByte ushr 2) and 0x03
                currentTtl = typeTtlByte and 0x03
            }

            val seqNum = buffer.short.toUShort()

            return MeshHeader(
                clubNetworkId = clubId,
                senderShortId = senderId,
                packetType = packetType,
                initTtl = initTtl,
                currentTtl = currentTtl,
                sequenceNum = seqNum
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(clubNetworkId.toInt())
        buffer.putShort(senderShortId.toShort())

        val typeTtlByte = if (initTtl > 3 || currentTtl > 3) {
            // Modo Extendido Multi-Salto Hito 3 (Bit 7 = 1)
            0x80 or ((packetType and 0x07) shl 4) or (currentTtl and 0x0F)
        } else {
            // Modo Legacy Hito 1 (Bit 7 = 0)
            ((packetType and 0x0F) shl 4) or
                    ((initTtl and 0x03) shl 2) or
                    (currentTtl and 0x03)
        }
        buffer.put(typeTtlByte.toByte())
        buffer.putShort(sequenceNum.toShort())
        return buffer.array()
    }

    /**
     * Retorna una copia de la cabecera actualizando currentTtl.
     */
    fun copyWithTtl(newTtl: Byte): MeshHeader = copy(currentTtl = newTtl.toInt())
    fun copyWithTtl(newTtl: Int): MeshHeader = copy(currentTtl = newTtl)

    /**
     * Clave única de deduplicación para DeduplicationTable: (senderShortId, sequenceNum).
     */
    val dedupKey: Long
        get() = ((senderShortId.toLong() and 0xFFFFL) shl 16) or (sequenceNum.toLong() and 0xFFFFL)
}
