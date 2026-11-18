package com.rutabikersync.app.core.mesh.sos

import com.rutabikersync.app.core.mesh.MeshHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Trama de Ficha Médica Cifrada en Malla (Fase 6 · Hito 4 · Etapa 3 · Regla R10.2).
 *
 * Transporta el paquete de rescate médico de emergencia (alergias, sangre, seguro, ICE)
 * cifrado de extremo a extremo mediante AES-256-GCM.
 *
 * Principio Zero-Knowledge de Relays Ciegos (Opción A):
 * - Los nodos regulares del convoy retransmiten el paquete sin poder descifrarlo.
 * - Únicamente el Líder Principal y el Sargento de Armas poseen la clave de rescate
 *   (`medicalRescueKey`) distribuida en el `manifest.json` firmado para descifrarla.
 *
 * Formato Wire (Big-Endian, 55 + N Bytes, <= 254 Bytes, CERO fragmentación SAR):
 * - Bytes 0..8:   MeshHeader (9 Bytes, packetType = TYPE_MEDICAL_SHEET = 0x07)
 * - Bytes 9..10:  owner_short_id (uint16, 2 Bytes, piloto dueño de la ficha)
 * - Bytes 11..26: ride_id (16 Bytes, UUID de la rodada activa)
 * - Bytes 27..38: iv (12 Bytes, nonce criptográfico AES-GCM)
 * - Bytes 39..54: auth_tag (16 Bytes, etiqueta de autenticación GCM de 128 bits)
 * - Bytes 55..N:  ciphertext (N Bytes, carga clínica cifrada)
 */
data class MedicalSheetPacket(
    val header: MeshHeader,
    val ownerShortId: UShort,
    val rideIdBytes: ByteArray,
    val iv: ByteArray,
    val authTag: ByteArray,
    val ciphertext: ByteArray
) {
    init {
        require(header.packetType == MeshHeader.TYPE_MEDICAL_SHEET) {
            "MedicalSheetPacket requiere MeshHeader.packetType == TYPE_MEDICAL_SHEET (0x07), recibido: ${header.packetType}"
        }
        require(rideIdBytes.size == RIDE_ID_BYTES) {
            "rideIdBytes debe medir exactamente $RIDE_ID_BYTES bytes, recibido: ${rideIdBytes.size}"
        }
        require(iv.size == GCM_IV_BYTES) {
            "iv debe medir exactamente $GCM_IV_BYTES bytes, recibido: ${iv.size}"
        }
        require(authTag.size == GCM_TAG_BYTES) {
            "authTag debe medir exactamente $GCM_TAG_BYTES bytes, recibido: ${authTag.size}"
        }
        require(ciphertext.isNotEmpty()) {
            "ciphertext no puede estar vacío"
        }
    }

    val rideIdUuid: UUID
        get() {
            val buf = ByteBuffer.wrap(rideIdBytes)
            val mostSig = buf.long
            val leastSig = buf.long
            return UUID(mostSig, leastSig)
        }

    /**
     * Serializa la trama a formato binario para emisión BLE Extended Advertising.
     */
    fun toByteArray(): ByteArray {
        val totalSize = FIXED_HEADER_AND_METADATA_BYTES + ciphertext.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.toByteArray())
        buffer.putShort(ownerShortId.toShort())
        buffer.put(rideIdBytes)
        buffer.put(iv)
        buffer.put(authTag)
        buffer.put(ciphertext)
        return buffer.array()
    }

    /**
     * Retorna una copia con TTL decrementado para retransmisión multi-salto.
     */
    fun copyWithDecrementedTtl(newTtl: Byte): MedicalSheetPacket {
        val newHeader = header.copyWithTtl(newTtl)
        return copy(header = newHeader)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedicalSheetPacket

        if (header != other.header) return false
        if (ownerShortId != other.ownerShortId) return false
        if (!rideIdBytes.contentEquals(other.rideIdBytes)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + ownerShortId.hashCode()
        result = 31 * result + rideIdBytes.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        const val RIDE_ID_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val FIXED_HEADER_AND_METADATA_BYTES = MeshHeader.HEADER_SIZE_BYTES + 2 + RIDE_ID_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES // 9 + 46 = 55 B

        fun uuidToBytes(uuid: UUID): ByteArray {
            val buf = ByteBuffer.allocate(RIDE_ID_BYTES).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(uuid.mostSignificantBits)
            buf.putLong(uuid.leastSignificantBits)
            return buf.array()
        }

        fun create(
            clubNetworkId: UInt,
            ownerShortId: UShort,
            sequenceNum: UShort,
            rideId: UUID,
            iv: ByteArray,
            authTag: ByteArray,
            ciphertext: ByteArray,
            currentTtl: Int = 5
        ): MedicalSheetPacket {
            val header = MeshHeader(
                clubNetworkId = clubNetworkId,
                senderShortId = ownerShortId,
                packetType = MeshHeader.TYPE_MEDICAL_SHEET,
                initTtl = maxOf(5, currentTtl),
                currentTtl = currentTtl,
                sequenceNum = sequenceNum
            )
            return MedicalSheetPacket(
                header = header,
                ownerShortId = ownerShortId,
                rideIdBytes = uuidToBytes(rideId),
                iv = iv,
                authTag = authTag,
                ciphertext = ciphertext
            )
        }

        fun fromByteArray(bytes: ByteArray, offset: Int = 0): MedicalSheetPacket {
            require(bytes.size >= offset + FIXED_HEADER_AND_METADATA_BYTES) {
                "Buffer insuficiente para MedicalSheetPacket: tamaño ${bytes.size}, requerido ${offset + FIXED_HEADER_AND_METADATA_BYTES}"
            }
            val header = MeshHeader.fromByteArray(bytes, offset)
            val buffer = ByteBuffer.wrap(bytes, offset + MeshHeader.HEADER_SIZE_BYTES, bytes.size - offset - MeshHeader.HEADER_SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)

            val ownerId = buffer.short.toUShort()
            val rideId = ByteArray(RIDE_ID_BYTES).also { buffer.get(it) }
            val iv = ByteArray(GCM_IV_BYTES).also { buffer.get(it) }
            val authTag = ByteArray(GCM_TAG_BYTES).also { buffer.get(it) }
            val cipherLen = buffer.remaining()
            require(cipherLen > 0) { "Ciphertext no puede tener longitud cero" }
            val ciphertext = ByteArray(cipherLen).also { buffer.get(it) }

            return MedicalSheetPacket(
                header = header,
                ownerShortId = ownerId,
                rideIdBytes = rideId,
                iv = iv,
                authTag = authTag,
                ciphertext = ciphertext
            )
        }
    }
}

