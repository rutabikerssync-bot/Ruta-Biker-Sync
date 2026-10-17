package com.rutabikersync.app.core.protocol

import com.rutabikersync.app.core.transport.Transport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Paquete compacto de posición y eventos para BLE Advertising (~20 bytes exactos).
 *
 * Estructura exacta según DESIGN.md §3:
 * - Bytes 0..1  (2 B): ID corto del nodo emisor original (UShort, Big-Endian)
 * - Bytes 2..5  (4 B): Latitud en microgrados (Int, lat * 1e7)
 * - Bytes 6..9  (4 B): Longitud en microgrados (Int, lon * 1e7)
 * - Bytes 10..11(2 B): Velocidad en km/h (1 B unsigned, 0..255) + Rumbo discretizado (1 B unsigned, 0..255 -> 0..360°)
 * - Byte  12    (1 B): Batería (4 bits altos: 0..15) + Flags (4 bits bajos: bit3=SOS, bit2=batería<20%, bit1=BLE extendido, bit0=en movimiento)
 * - Bytes 13..15(3 B): Secuencia + TTL + Prioridad:
 *                      - Byte 13: 3 bits altos = Priority ordinal (0..5), 5 bits bajos = initialTtl (1..15)
 *                      - Bytes 14..15 (UShort): 12 bits altos = sequenceNumber (0..4095), 4 bits bajos = currentTtl (0..15)
 * - Bytes 16..19(4 B): Etiqueta de autenticación HMAC-SHA256 truncada a 4 bytes (sobre los primeros 16 bytes con currentTtl enmascarado)
 */
data class MeshPacket(
    val shortId: UShort,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Int,
    val bearingDegrees: Float,
    val batteryPercent: Int,
    val isSos: Boolean,
    val isLowBattery: Boolean = batteryPercent < 20,
    val isExtendedBle: Boolean = false,
    val isMoving: Boolean = speedKmh >= 3,
    val priority: Transport.Priority = if (isSos) Transport.Priority.SOS else Transport.Priority.POSITION,
    val sequenceNumber: Int,
    val initialTtl: Int,
    val currentTtl: Int,
    val authTag: Int
) {
    init {
        require(sequenceNumber in 0..MAX_SEQUENCE_NUMBER) { "sequenceNumber fuera de rango (0..$MAX_SEQUENCE_NUMBER)" }
        require(initialTtl in 1..MAX_TTL) { "initialTtl fuera de rango (1..$MAX_TTL)" }
        require(currentTtl in 0..MAX_TTL) { "currentTtl fuera de rango (0..$MAX_TTL)" }
        require(currentTtl <= initialTtl) { "currentTtl ($currentTtl) no puede superar initialTtl ($initialTtl)" }
    }

    /** Número de saltos recorridos hasta el momento (1 = directo del emisor). */
    val hopsTaken: Int
        get() = (initialTtl - currentTtl) + 1

    /** Clave única de deduplicación (emisor + secuencia de 12 bits). */
    val dedupKey: Long
        get() = ((shortId.toLong() and 0xFFFFL) shl 16) or (sequenceNumber.toLong() and 0x0FFFL)

    /** Devuelve una copia con TTL decrementado en 1 para reenvío por inundación controlada. */
    fun decrementTtlForRelay(): MeshPacket? {
        if (currentTtl <= 1) return null
        return copy(currentTtl = currentTtl - 1)
    }

    fun toByteArray(): ByteArray {
        val header = encodeHeaderBytes(
            shortId = shortId,
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            bearingDegrees = bearingDegrees,
            batteryPercent = batteryPercent,
            isSos = isSos,
            isLowBattery = isLowBattery,
            isExtendedBle = isExtendedBle,
            isMoving = isMoving,
            priority = priority,
            initialTtl = initialTtl,
            sequenceNumber = sequenceNumber,
            currentTtl = currentTtl
        )
        return ByteBuffer.allocate(PACKET_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(authTag)
            .array()
    }

    fun verifyAuthTag(rideSecretKey: ByteArray): Boolean {
        val wireHeader = encodeHeaderBytes(
            shortId = shortId,
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            bearingDegrees = bearingDegrees,
            batteryPercent = batteryPercent,
            isSos = isSos,
            isLowBattery = isLowBattery,
            isExtendedBle = isExtendedBle,
            isMoving = isMoving,
            priority = priority,
            initialTtl = initialTtl,
            sequenceNumber = sequenceNumber,
            currentTtl = 0
        )
        val expected = computeAuthTagFromCanonicalHeader(wireHeader, rideSecretKey)
        return authTag == expected
    }

    companion object {
        const val PACKET_SIZE_BYTES: Int = 20
        const val MAX_SEQUENCE_NUMBER: Int = 4095
        const val MAX_TTL: Int = 15
        const val DEFAULT_TTL: Int = 4

        fun createSigned(
            shortId: UShort,
            latitude: Double,
            longitude: Double,
            speedKmh: Int,
            bearingDegrees: Float,
            batteryPercent: Int,
            isSos: Boolean = false,
            isExtendedBle: Boolean = false,
            priority: Transport.Priority = if (isSos) Transport.Priority.SOS else Transport.Priority.POSITION,
            sequenceNumber: Int,
            ttl: Int = DEFAULT_TTL,
            rideSecretKey: ByteArray
        ): MeshPacket {
            val lowBat = batteryPercent < 20
            val moving = speedKmh.coerceIn(0, 255) >= 3
            val canonicalHeader = encodeHeaderBytes(
                shortId = shortId,
                latitude = latitude,
                longitude = longitude,
                speedKmh = speedKmh,
                bearingDegrees = bearingDegrees,
                batteryPercent = batteryPercent,
                isSos = isSos,
                isLowBattery = lowBat,
                isExtendedBle = isExtendedBle,
                isMoving = moving,
                priority = priority,
                initialTtl = ttl,
                sequenceNumber = sequenceNumber,
                currentTtl = 0
            )
            val tag = computeAuthTagFromCanonicalHeader(canonicalHeader, rideSecretKey)

            val fullBytes = ByteBuffer.allocate(PACKET_SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(
                    encodeHeaderBytes(
                        shortId = shortId,
                        latitude = latitude,
                        longitude = longitude,
                        speedKmh = speedKmh,
                        bearingDegrees = bearingDegrees,
                        batteryPercent = batteryPercent,
                        isSos = isSos,
                        isLowBattery = lowBat,
                        isExtendedBle = isExtendedBle,
                        isMoving = moving,
                        priority = priority,
                        initialTtl = ttl,
                        sequenceNumber = sequenceNumber,
                        currentTtl = ttl
                    )
                )
                .putInt(tag)
                .array()

            return fromByteArray(fullBytes)
        }

        fun fromByteArray(bytes: ByteArray): MeshPacket {
            require(bytes.size == PACKET_SIZE_BYTES) {
                "Tamaño inválido de paquete BLE: esperado $PACKET_SIZE_BYTES bytes, recibido ${bytes.size}"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val shortId = buffer.short.toUShort()
            val latMicro = buffer.int
            val lonMicro = buffer.int
            val speedKmh = buffer.get().toInt() and 0xFF
            val bearingByte = buffer.get().toInt() and 0xFF
            val batteryAndFlags = buffer.get().toInt() and 0xFF
            val prioAndInitTtl = buffer.get().toInt() and 0xFF
            val seqAndCurrTtl = buffer.short.toInt() and 0xFFFF
            val authTag = buffer.int

            val batteryNibble = (batteryAndFlags ushr 4) and 0x0F
            val flagsNibble = batteryAndFlags and 0x0F
            val isSos = (flagsNibble and 0b1000) != 0
            val isLowBattery = (flagsNibble and 0b0100) != 0
            val isExtendedBle = (flagsNibble and 0b0010) != 0
            val isMoving = (flagsNibble and 0b0001) != 0

            val priorityOrdinal = ((prioAndInitTtl ushr 5) and 0x07)
                .coerceIn(0, Transport.Priority.entries.lastIndex)
            val initialTtl = (prioAndInitTtl and 0x1F).coerceIn(1, MAX_TTL)
            val sequenceNumber = (seqAndCurrTtl ushr 4) and 0x0FFF
            val currentTtl = (seqAndCurrTtl and 0x0F).coerceIn(0, initialTtl)

            return MeshPacket(
                shortId = shortId,
                latitude = latMicro / 1e7,
                longitude = lonMicro / 1e7,
                speedKmh = speedKmh,
                bearingDegrees = (bearingByte * 360f) / 256f,
                batteryPercent = ((batteryNibble * 100f) / 15f).roundToInt(),
                isSos = isSos,
                isLowBattery = isLowBattery,
                isExtendedBle = isExtendedBle,
                isMoving = isMoving,
                priority = Transport.Priority.entries[priorityOrdinal],
                sequenceNumber = sequenceNumber,
                initialTtl = initialTtl,
                currentTtl = currentTtl,
                authTag = authTag
            )
        }

        private fun encodeHeaderBytes(
            shortId: UShort,
            latitude: Double,
            longitude: Double,
            speedKmh: Int,
            bearingDegrees: Float,
            batteryPercent: Int,
            isSos: Boolean,
            isLowBattery: Boolean,
            isExtendedBle: Boolean,
            isMoving: Boolean,
            priority: Transport.Priority,
            initialTtl: Int,
            sequenceNumber: Int,
            currentTtl: Int
        ): ByteArray {
            val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(shortId.toShort())
            buffer.putInt((latitude * 1e7).roundToInt())
            buffer.putInt((longitude * 1e7).roundToInt())

            val clampedSpeed = speedKmh.coerceIn(0, 255)
            val normalizedBearing = (((bearingDegrees % 360f) + 360f) % 360f)
            val bearingByte = ((normalizedBearing / 360f) * 256f).roundToInt().coerceIn(0, 255)
            buffer.put(clampedSpeed.toByte())
            buffer.put(bearingByte.toByte())

            val batteryNibble = ((batteryPercent.coerceIn(0, 100) * 15f) / 100f).roundToInt().coerceIn(0, 15)
            var flagsNibble = 0
            if (isSos) flagsNibble = flagsNibble or 0b1000
            if (isLowBattery) flagsNibble = flagsNibble or 0b0100
            if (isExtendedBle) flagsNibble = flagsNibble or 0b0010
            if (isMoving) flagsNibble = flagsNibble or 0b0001
            val batteryAndFlags = ((batteryNibble shl 4) or flagsNibble).toByte()
            buffer.put(batteryAndFlags)

            val prioAndInitTtl = (((priority.ordinal and 0x07) shl 5) or (initialTtl and 0x1F)).toByte()
            val seqAndCurrTtl = (((sequenceNumber and 0x0FFF) shl 4) or (currentTtl and 0x0F)).toShort()
            buffer.put(prioAndInitTtl)
            buffer.putShort(seqAndCurrTtl)
            return buffer.array()
        }

        private fun computeAuthTagFromCanonicalHeader(
            canonical16Bytes: ByteArray,
            rideSecretKey: ByteArray
        ): Int {
            val masked = canonical16Bytes.copyOf()
            // Asegurar que los 4 bits bajos de currentTtl (byte 15) estén a 0 para que el tag sea invariante entre saltos
            masked[15] = (masked[15].toInt() and 0xF0).toByte()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(rideSecretKey, "HmacSHA256"))
            val digest = mac.doFinal(masked)
            return ByteBuffer.wrap(digest, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        }
    }
}
