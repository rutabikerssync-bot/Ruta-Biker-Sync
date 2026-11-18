package com.rutabikersync.app.core.gamification

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reporte de testigo de rodada para consenso de kilometraje (Fase 6 · Hito 5 · Etapa 4).
 *
 * Permite a los compañeros de convoy certificar la distancia observada para mitigar
 * falsificaciones o adulteraciones individuales.
 */
data class WitnessReport(
    val witnessShortId: UShort,
    val reportedKm: Double,
    val signature: ByteArray = ByteArray(64)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WitnessReport) return false
        return witnessShortId == other.witnessShortId &&
                reportedKm == other.reportedKm &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = witnessShortId.hashCode()
        result = 31 * result + reportedKm.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Resumen consolidado de rodada para validación anti-cheat y gamificación (Fase 6 · Hito 5 · Etapa 4).
 *
 * Incluye métricas de malla (km de mesh, relays, auxilios SOS, minutos offline), firma digital
 * del Líder (Ed25519) y reportes opcionales de testigos.
 */
data class RideSummary(
    val rideId: String,
    val userId: String,
    val durationMs: Long,
    val totalKm: Double,
    val meshKm: Double,
    val relayCount: Int,
    val sosAssistCount: Int,
    val offlineMinutes: Int,
    val peerHeartbeatsReceived: Int,
    val leaderPublicKey: ByteArray,
    val leaderSignature: ByteArray,
    val witnessReports: List<WitnessReport> = emptyList(),
    val role: String = "rider",
    val vaultCompleted: Boolean = false,
    val medicalSheetShared: Boolean = false
) {
    /**
     * Serializa los campos canónicos inmutables de la rodada para verificación de la firma Ed25519.
     */
    fun toSignableBytes(): ByteArray {
        val rideIdBytes = rideId.toByteArray(Charsets.UTF_8)
        val userIdBytes = userId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            4 + rideIdBytes.size +
            4 + userIdBytes.size +
            8 + // durationMs
            8 + // totalKm
            8 + // meshKm
            4 + // relayCount
            4 + // sosAssistCount
            4 + // offlineMinutes
            4   // peerHeartbeatsReceived
        ).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(rideIdBytes.size)
        buffer.put(rideIdBytes)
        buffer.putInt(userIdBytes.size)
        buffer.put(userIdBytes)
        buffer.putLong(durationMs)
        buffer.putDouble(totalKm)
        buffer.putDouble(meshKm)
        buffer.putInt(relayCount)
        buffer.putInt(sosAssistCount)
        buffer.putInt(offlineMinutes)
        buffer.putInt(peerHeartbeatsReceived)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RideSummary) return false
        return rideId == other.rideId &&
                userId == other.userId &&
                durationMs == other.durationMs &&
                totalKm == other.totalKm &&
                meshKm == other.meshKm &&
                relayCount == other.relayCount &&
                sosAssistCount == other.sosAssistCount &&
                offlineMinutes == other.offlineMinutes &&
                peerHeartbeatsReceived == other.peerHeartbeatsReceived &&
                leaderPublicKey.contentEquals(other.leaderPublicKey) &&
                leaderSignature.contentEquals(other.leaderSignature) &&
                witnessReports == other.witnessReports &&
                role == other.role &&
                vaultCompleted == other.vaultCompleted &&
                medicalSheetShared == other.medicalSheetShared
    }

    override fun hashCode(): Int {
        var result = rideId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + totalKm.hashCode()
        result = 31 * result + meshKm.hashCode()
        result = 31 * result + relayCount
        result = 31 * result + sosAssistCount
        result = 31 * result + offlineMinutes
        result = 31 * result + peerHeartbeatsReceived
        result = 31 * result + leaderPublicKey.contentHashCode()
        result = 31 * result + leaderSignature.contentHashCode()
        result = 31 * result + witnessReports.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + vaultCompleted.hashCode()
        result = 31 * result + medicalSheetShared.hashCode()
        return result
    }
}
