package com.rutabikersync.app.core.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Trama de Telemetría y Heartbeat (Fase 6 · Hito 1 · BLE_MESH_ARCHITECTURE_HITO_0.md §5.2).
 * Formato Wire de 24 Bytes Totales:
 * - Bytes 0..8:   MeshHeader (9 Bytes, packet_type = TYPE_HEARTBEAT 0x1)
 * - Bytes 9..12:  latitude (int32, 4 Bytes, fixed-point factor 1e7)
 * - Bytes 13..16: longitude (int32, 4 Bytes, fixed-point factor 1e7)
 * - Byte 17:      speed_kmh (uint8, 1 Byte, 0..255 km/h)
 * - Byte 18:      heading_deg / 2 (uint8, 1 Byte, 0..180 cubriendo 0..360°)
 * - Byte 19:      battery (4 bits: bits 7..4) | flags (4 bits: bits 3..0) (1 Byte)
 * - Byte 20:      boot_epoch (uint8, 1 Byte, 0..255 contador monotónico de reinicio de nodo)
 * - Bytes 21..23: auth_tag_trunc (uint24, 3 Bytes, placeholder 0x000000 en Hito 1/3)
 */
data class HeartbeatFrame(
    val header: MeshHeader,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Int,
    val headingDeg: Float,
    val batteryPercent: Int,
    val flags: Int = FLAG_NONE,
    val bootEpoch: Int = 0,
    val authTagTrunc: Int = 0x00000000
) {
    init {
        require(header.packetType == MeshHeader.TYPE_HEARTBEAT) {
            "El packetType en MeshHeader debe ser TYPE_HEARTBEAT (0x1), recibido: ${header.packetType}"
        }
        require(speedKmh in 0..255) { "speedKmh fuera de rango (0..255): $speedKmh" }
        require(batteryPercent in 0..100) { "batteryPercent fuera de rango (0..100): $batteryPercent" }
        require(flags in 0..0x0F) { "flags fuera de rango 4-bit (0..15): $flags" }
        require(bootEpoch in 0..255) { "bootEpoch fuera de rango (0..255): $bootEpoch" }
    }

    val isMedicalSheetShared: Boolean
        get() = (flags and FLAG_MEDICAL_SHARED) != 0

    val isOffRoute: Boolean
        get() = (flags and FLAG_OFF_ROUTE) != 0

    val isVoluntaryStop: Boolean
        get() = (flags and FLAG_VOLUNTARY_STOP) != 0

    val isVoiceActive: Boolean
        get() = (flags and FLAG_VOICE_ACTIVE) != 0

    companion object {
        const val PAYLOAD_SIZE_BYTES = 15
        const val FRAME_SIZE_BYTES = MeshHeader.HEADER_SIZE_BYTES + PAYLOAD_SIZE_BYTES // 24 Bytes exactos

        const val FLAG_NONE = 0x00
        const val FLAG_MEDICAL_SHARED = 0x01   // bit 0: Ficha médica compartida en bóveda
        const val FLAG_OFF_ROUTE = 0x02        // bit 1: Desviado de ruta
        const val FLAG_VOLUNTARY_STOP = 0x04   // bit 2: Detenido voluntario
        const val FLAG_VOICE_ACTIVE = 0x08     // bit 3: Canal PTT / voz activa

        private const val COORD_SCALE = 1e7

        fun fromByteArray(bytes: ByteArray, offset: Int = 0): HeartbeatFrame {
            require(bytes.size >= offset + FRAME_SIZE_BYTES) {
                "Buffer insuficiente para HeartbeatFrame: tamaño ${bytes.size}, requerido ${offset + FRAME_SIZE_BYTES}"
            }
            val header = MeshHeader.fromByteArray(bytes, offset)
            val payloadOffset = offset + MeshHeader.HEADER_SIZE_BYTES
            val buffer = ByteBuffer.wrap(bytes, payloadOffset, PAYLOAD_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)

            val latInt = buffer.int
            val lonInt = buffer.int
            val speed = buffer.get().toInt() and 0xFF
            val headingRaw = buffer.get().toInt() and 0xFF
            val batFlagsByte = buffer.get().toInt() and 0xFF
            val bootEpoch = buffer.get().toInt() and 0xFF
            val tagHigh = buffer.short.toInt() and 0xFFFF
            val tagLow = buffer.get().toInt() and 0xFF
            val authTag = (tagHigh shl 8) or tagLow

            val latitude = latInt / COORD_SCALE
            val longitude = lonInt / COORD_SCALE
            val headingDeg = (headingRaw * 2.0f).coerceIn(0f, 360f)
            val batRaw = (batFlagsByte ushr 4) and 0x0F
            val batteryPercent = (batRaw * 100 / 15).coerceIn(0, 100)
            val flags = batFlagsByte and 0x0F

            return HeartbeatFrame(
                header = header,
                latitude = latitude,
                longitude = longitude,
                speedKmh = speed,
                headingDeg = headingDeg,
                batteryPercent = batteryPercent,
                flags = flags,
                bootEpoch = bootEpoch,
                authTagTrunc = authTag
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(FRAME_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.toByteArray())

        // Coordenadas fijas con factor 1e7
        val latInt = (latitude * COORD_SCALE).roundToInt()
        val lonInt = (longitude * COORD_SCALE).roundToInt()
        buffer.putInt(latInt)
        buffer.putInt(lonInt)

        // Velocidad y Rumbo (dividido entre 2)
        buffer.put((speedKmh and 0xFF).toByte())
        val headingRaw = ((headingDeg / 2.0f).roundToInt()).coerceIn(0, 180)
        buffer.put((headingRaw and 0xFF).toByte())

        // Batería (4 bits) + Flags (4 bits)
        val bat4Bits = ((batteryPercent * 15 + 50) / 100).coerceIn(0, 15)
        val batFlagsByte = ((bat4Bits and 0x0F) shl 4) or (flags and 0x0F)
        buffer.put(batFlagsByte.toByte())

        // Boot Epoch (1 Byte, 0..255 contador de reinicio de nodo)
        buffer.put((bootEpoch and 0xFF).toByte())

        // auth_tag_trunc (3 Bytes)
        buffer.putShort(((authTagTrunc ushr 8) and 0xFFFF).toShort())
        buffer.put((authTagTrunc and 0xFF).toByte())

        return buffer.array()
    }
}

