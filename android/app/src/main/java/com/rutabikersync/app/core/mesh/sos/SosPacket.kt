package com.rutabikersync.app.core.mesh.sos

import com.rutabikersync.app.core.mesh.MeshHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Código de tipología de incidente SOS (Fase 6 · Hito 4 · BLE_MESH_ARCHITECTURE_HITO_0.md §5.3).
 */
enum class SosIncidentCode(val code: Byte, val description: String) {
    FALL_AUTOMATIC(0x01.toByte(), "Caída automática por sensores"),
    MANUAL_BUTTON(0x02.toByte(), "Botón SOS manual"),
    MECHANICAL_FAILURE(0x03.toByte(), "Avería mecánica crítica"),
    VEHICLE_COLLISION(0x04.toByte(), "Colisión vehicular");

    companion object {
        fun fromCode(code: Byte): SosIncidentCode =
            entries.firstOrNull { it.code == code } ?: MANUAL_BUTTON
    }
}

/**
 * Identificador enum de grupo sanguíneo (Fase 6 · Hito 4 · BLE_MESH_ARCHITECTURE_HITO_0.md §5.3).
 */
enum class SosBloodType(val code: Byte, val displayName: String) {
    O_POSITIVE(1.toByte(), "O+"),
    O_NEGATIVE(2.toByte(), "O-"),
    A_POSITIVE(3.toByte(), "A+"),
    A_NEGATIVE(4.toByte(), "A-"),
    B_POSITIVE(5.toByte(), "B+"),
    B_NEGATIVE(6.toByte(), "B-"),
    AB_POSITIVE(7.toByte(), "AB+"),
    AB_NEGATIVE(8.toByte(), "AB-");

    companion object {
        fun fromCode(code: Byte): SosBloodType =
            entries.firstOrNull { it.code == code } ?: O_POSITIVE

        fun fromDisplay(display: String): SosBloodType =
            entries.firstOrNull { it.displayName.equals(display.trim(), ignoreCase = true) } ?: O_POSITIVE
    }
}

/**
 * Trama de Emergencia SOS (Fase 6 · Hito 4 · BLE_MESH_ARCHITECTURE_HITO_0.md §5.3).
 *
 * Prioridad absoluta en el medio físico (Coded PHY S=8 y Extended Advertising).
 * Formato Wire (Big-Endian, 88 Bytes exactos empaquetados):
 * - Bytes 0..8:   MeshHeader (9 Bytes, packetType = TYPE_SOS = 0x02)
 * - Bytes 9..12:  latitude (int32, 4 Bytes, fixed-point factor 1e7)
 * - Bytes 13..16: longitude (int32, 4 Bytes, fixed-point factor 1e7)
 * - Byte 17:      incident_code (uint8, 1 Byte: 0x01 Caída, 0x02 Botón, 0x03 Avería, 0x04 Colisión)
 * - Byte 18:      blood_type (uint8, 1 Byte: 1=O+, 2=O-, 3=A+, 4=A-, 5=B+, 6=B-, 7=AB+, 8=AB-)
 * - Bytes 19..22: timestamp_epoch_sec (uint32, 4 Bytes, UTC)
 * - Bytes 23..86: ed25519_signature (64 Bytes, firma asimétrica sobre los 14 bytes anteriores [9..22])
 * - Byte 87:      hop_count (uint8, 1 Byte, metadato de enrutamiento Opción B fuera de la firma)
 *
 * Total: 9 + 4 + 4 + 1 + 1 + 4 + 64 + 1 = 88 Bytes exactos. CERO fragmentación SAR.
 */
data class SosPacket(
    val header: MeshHeader,
    val latitudeRaw: Int,
    val longitudeRaw: Int,
    val incidentCode: Byte,
    val bloodType: Byte,
    val timestampEpochSec: Int,
    val signature: ByteArray,
    val hopCount: Byte = 0
) {
    init {
        require(header.packetType == MeshHeader.TYPE_SOS) {
            "SosPacket requiere MeshHeader.packetType == TYPE_SOS (0x02), recibido: ${header.packetType}"
        }
        require(signature.size == SIGNATURE_SIZE_BYTES) {
            "Firma Ed25519 debe medir exactamente $SIGNATURE_SIZE_BYTES bytes, recibido: ${signature.size}"
        }
    }

    val latitude: Double get() = latitudeRaw / 1e7
    val longitude: Double get() = longitudeRaw / 1e7
    val incident: SosIncidentCode get() = SosIncidentCode.fromCode(incidentCode)
    val blood: SosBloodType get() = SosBloodType.fromCode(bloodType)

    /**
     * Retorna los 14 bytes canónicos del payload sobre los cuales se calcula/verifica la firma Ed25519.
     * Excluye deliberadamente el hopCount (Opción B) para permitir su incremento en tránsito sin alterar la firma.
     */
    fun getSignableData(): ByteArray {
        val buffer = ByteBuffer.allocate(SIGNABLE_DATA_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(latitudeRaw)
        buffer.putInt(longitudeRaw)
        buffer.put(incidentCode)
        buffer.put(bloodType)
        buffer.putInt(timestampEpochSec)
        return buffer.array()
    }

    /**
     * Serializa la trama completa a un arreglo de 88 bytes exactos.
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.toByteArray())
        buffer.putInt(latitudeRaw)
        buffer.putInt(longitudeRaw)
        buffer.put(incidentCode)
        buffer.put(bloodType)
        buffer.putInt(timestampEpochSec)
        buffer.put(signature)
        buffer.put(hopCount)
        return buffer.array()
    }

    /**
     * Genera una copia con hopCount incrementado y cabecera con nuevo TTL decrecido.
     * La firma original se mantiene intacta y válida puesto que no cubre el hopCount (Opción B).
     */
    fun copyWithIncrementedHop(newTtl: Byte): SosPacket {
        val nextHop = ((hopCount.toInt() and 0xFF) + 1).coerceAtMost(255).toByte()
        val newHeader = header.copyWithTtl(newTtl)
        return copy(header = newHeader, hopCount = nextHop)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SosPacket

        if (header != other.header) return false
        if (latitudeRaw != other.latitudeRaw) return false
        if (longitudeRaw != other.longitudeRaw) return false
        if (incidentCode != other.incidentCode) return false
        if (bloodType != other.bloodType) return false
        if (timestampEpochSec != other.timestampEpochSec) return false
        if (!signature.contentEquals(other.signature)) return false
        if (hopCount != other.hopCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + latitudeRaw
        result = 31 * result + longitudeRaw
        result = 31 * result + incidentCode.hashCode()
        result = 31 * result + bloodType.hashCode()
        result = 31 * result + timestampEpochSec
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + hopCount.hashCode()
        return result
    }

    companion object {
        const val PACKET_SIZE_BYTES = 88
        const val SIGNABLE_DATA_SIZE_BYTES = 14
        const val SIGNATURE_SIZE_BYTES = 64

        /**
         * Factor de conversión para coordenadas geográficas a enteros fijos de 32 bits (precisión ~1.1 cm).
         */
        const val GEO_SCALE_FACTOR = 1e7

        fun create(
            clubNetworkId: UInt,
            senderShortId: UShort,
            sequenceNum: UShort,
            latitude: Double,
            longitude: Double,
            incident: SosIncidentCode,
            bloodType: SosBloodType,
            timestampEpochSec: Int,
            signature: ByteArray,
            currentTtl: Int = 5,
            hopCount: Byte = 0
        ): SosPacket {
            val header = MeshHeader(
                clubNetworkId = clubNetworkId,
                senderShortId = senderShortId,
                packetType = MeshHeader.TYPE_SOS,
                initTtl = maxOf(5, currentTtl),
                currentTtl = currentTtl,
                sequenceNum = sequenceNum
            )
            val latRaw = (latitude * GEO_SCALE_FACTOR).toInt()
            val lonRaw = (longitude * GEO_SCALE_FACTOR).toInt()
            return SosPacket(
                header = header,
                latitudeRaw = latRaw,
                longitudeRaw = lonRaw,
                incidentCode = incident.code,
                bloodType = bloodType.code,
                timestampEpochSec = timestampEpochSec,
                signature = signature,
                hopCount = hopCount
            )
        }

        fun fromByteArray(bytes: ByteArray, offset: Int = 0): SosPacket {
            require(bytes.size >= offset + PACKET_SIZE_BYTES) {
                "Buffer insuficiente para SosPacket: tamaño ${bytes.size}, requerido ${offset + PACKET_SIZE_BYTES}"
            }
            val header = MeshHeader.fromByteArray(bytes, offset)
            val buffer = ByteBuffer.wrap(bytes, offset + MeshHeader.HEADER_SIZE_BYTES, PACKET_SIZE_BYTES - MeshHeader.HEADER_SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)

            val latRaw = buffer.int
            val lonRaw = buffer.int
            val incidentCode = buffer.get()
            val bloodType = buffer.get()
            val timestamp = buffer.int
            val signature = ByteArray(SIGNATURE_SIZE_BYTES)
            buffer.get(signature)
            val hopCount = buffer.get()

            return SosPacket(
                header = header,
                latitudeRaw = latRaw,
                longitudeRaw = lonRaw,
                incidentCode = incidentCode,
                bloodType = bloodType,
                timestampEpochSec = timestamp,
                signature = signature,
                hopCount = hopCount
            )
        }
    }
}

