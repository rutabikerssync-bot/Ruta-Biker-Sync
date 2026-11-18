package com.rutabikersync.app.core.gamification

import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.math.abs

/**
 * Validador Anti-Cheat para la Integración de Gamificación (Fase 6 · Hito 5 · Etapa 4).
 *
 * Implementa las 6 Reglas Anti-Cheat del Sistema:
 * 1. **Velocidad Máxima Razonable:** Si la velocidad promedio entre puntos GPS excede 200 km/h,
 *    el tramo se descarta o invalida (descarte de teletransportes GPS o vuelos de avión).
 * 2. **Consistencia de Malla:** `meshKm` debe ser estrictamente menor o igual a `totalKm`.
 * 3. **Presencia Mínima de Convoy:** Requiere un mínimo de 2 heartbeats de compañeros durante la rodada.
 * 4. **Firma Criptográfica del Líder:** El resumen de la rodada debe estar firmado con la clave Ed25519 del Líder.
 * 5. **Consenso de Testigos:** Si 2 o más testigos discrepan en más del 20% del kilometraje reportado,
 *    la rodada se marca para auditoría ([ValidationResult.isAudited] = true) y solo se otorga puntuación base.
 * 6. **Ventana de Tiempo Mínima:** Requiere al menos 30 minutos de rodada activa ([MIN_RIDE_DURATION_MS])
 *    para evitar spam de rodadas cortas ficticias.
 */
class AntiCheatValidator(
    val maxReasonableSpeedKmh: Double = MAX_REASONABLE_SPEED_KMH,
    val defaultMinDurationMs: Long = MIN_RIDE_DURATION_MS,
    val defaultMinPeerHeartbeats: Int = MIN_PEER_HEARTBEATS,
    val maxWitnessDiscrepancyPct: Double = MAX_WITNESS_DISCREPANCY_PCT
) {

    data class ValidationResult(
        val isValid: Boolean,
        val isAudited: Boolean,
        val rejectionReason: String? = null
    )

    data class WitnessConsensusResult(
        val isAudited: Boolean,
        val maxDiscrepancy: Double
    )

    /**
     * Regla 1: Valida que la velocidad entre dos lecturas GPS no supere el límite motero razonable (200 km/h).
     */
    fun validateGpsSpeed(
        distanceMeters: Double,
        elapsedMs: Long,
        maxSpeedKmh: Double = maxReasonableSpeedKmh
    ): Boolean {
        if (elapsedMs <= 0L || distanceMeters < 0.0) return false
        val hours = elapsedMs.toDouble() / 3_600_000.0
        val speedKmh = (distanceMeters / 1000.0) / hours
        return speedKmh <= maxSpeedKmh
    }

    /**
     * Regla 2: Valida que los kilómetros de malla no superen el total de la rodada.
     */
    fun validateMeshConsistency(meshKm: Double, totalKm: Double): Boolean {
        if (meshKm < 0.0 || totalKm < 0.0) return false
        // Tolerancia de punto flotante de 0.001 km (1 metro)
        return meshKm <= (totalKm + 0.001)
    }

    /**
     * Regla 3: Valida presencia mínima de compañeros de convoy.
     */
    fun validateMinPeerPresence(
        peerHeartbeatsCount: Int,
        minHeartbeats: Int = defaultMinPeerHeartbeats
    ): Boolean {
        return peerHeartbeatsCount >= minHeartbeats
    }

    /**
     * Regla 4: Valida la firma digital Ed25519 del Líder de la rodada.
     */
    fun validateLeaderSignature(
        signableBytes: ByteArray,
        signature: ByteArray,
        leaderPublicKeyBytes: ByteArray
    ): Boolean {
        if (signature.isEmpty() || leaderPublicKeyBytes.isEmpty() || signature.size != 64) {
            return false
        }
        return try {
            val publicKey = parseEd25519PublicKey(leaderPublicKeyBytes)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(signableBytes)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Regla 5: Valida el consenso de reportes de testigos de convoy.
     * Si la discrepancia supera el 20%, marca la rodada como auditada.
     */
    fun validateWitnessConsensus(
        reportedMeshKm: Double,
        witnessReports: List<WitnessReport>,
        maxDiscrepancyPct: Double = maxWitnessDiscrepancyPct
    ): WitnessConsensusResult {
        if (witnessReports.size < 2) {
            return WitnessConsensusResult(isAudited = false, maxDiscrepancy = 0.0)
        }

        var maxDiscrepancyFound = 0.0

        // Discrepancia entre los testigos
        for (i in witnessReports.indices) {
            for (j in (i + 1) until witnessReports.size) {
                val km1 = witnessReports[i].reportedKm
                val km2 = witnessReports[j].reportedKm
                val base = maxOf(km1, km2, 1.0)
                val diffPct = abs(km1 - km2) / base
                if (diffPct > maxDiscrepancyFound) {
                    maxDiscrepancyFound = diffPct
                }
            }
        }

        // Discrepancia con el valor reportado por el piloto
        for (w in witnessReports) {
            val base = maxOf(reportedMeshKm, w.reportedKm, 1.0)
            val diffPct = abs(w.reportedKm - reportedMeshKm) / base
            if (diffPct > maxDiscrepancyFound) {
                maxDiscrepancyFound = diffPct
            }
        }

        val isAudited = maxDiscrepancyFound > maxDiscrepancyPct
        return WitnessConsensusResult(isAudited = isAudited, maxDiscrepancy = maxDiscrepancyFound)
    }

    /**
     * Regla 6: Valida la duración mínima de la rodada (30 minutos por defecto).
     */
    fun validateMinDuration(
        durationMs: Long,
        minDurationMs: Long = defaultMinDurationMs
    ): Boolean {
        return durationMs >= minDurationMs
    }

    /**
     * Ejecuta la evaluación integral de todas las reglas anti-cheat sobre un [RideSummary].
     */
    fun validateRideSummary(
        summary: RideSummary,
        minDurationMs: Long = defaultMinDurationMs,
        minPeerHeartbeats: Int = defaultMinPeerHeartbeats
    ): ValidationResult {
        // 1. Duración mínima
        if (!validateMinDuration(summary.durationMs, minDurationMs)) {
            return ValidationResult(
                isValid = false,
                isAudited = false,
                rejectionReason = "RECHAZO: Duración de rodada insuficiente (< ${minDurationMs / 60000} minutos)"
            )
        }

        // 2. Consistencia de kilometraje (meshKm <= totalKm)
        if (!validateMeshConsistency(summary.meshKm, summary.totalKm)) {
            return ValidationResult(
                isValid = false,
                isAudited = false,
                rejectionReason = "RECHAZO: Inconsistencia de telemetría (meshKm ${summary.meshKm} > totalKm ${summary.totalKm})"
            )
        }

        // 3. Presencia mínima de compañeros en la malla
        if (!validateMinPeerPresence(summary.peerHeartbeatsReceived, minPeerHeartbeats)) {
            return ValidationResult(
                isValid = false,
                isAudited = false,
                rejectionReason = "RECHAZO: Presencia insuficiente de compañeros en la malla (< $minPeerHeartbeats heartbeats)"
            )
        }

        // 4. Firma digital del Líder
        val signableBytes = summary.toSignableBytes()
        if (!validateLeaderSignature(signableBytes, summary.leaderSignature, summary.leaderPublicKey)) {
            return ValidationResult(
                isValid = false,
                isAudited = false,
                rejectionReason = "RECHAZO: Firma criptográfica Ed25519 del Líder inválida o ausente"
            )
        }

        // 5. Consenso de testigos
        val consensus = validateWitnessConsensus(summary.meshKm, summary.witnessReports)

        return ValidationResult(
            isValid = true,
            isAudited = consensus.isAudited,
            rejectionReason = if (consensus.isAudited) "AUDITORÍA: Discrepancia entre testigos (${(consensus.maxDiscrepancy * 100).toInt()}%) > 20%" else null
        )
    }

    private fun parseEd25519PublicKey(rawOrSpki: ByteArray): PublicKey {
        val spkiBytes = if (rawOrSpki.size == 32) {
            Ed25519SosCrypto.ED25519_SPKI_HEADER + rawOrSpki
        } else {
            rawOrSpki
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiBytes))
    }

    companion object {
        /** Velocidad máxima admisible en carretera: 200 km/h */
        const val MAX_REASONABLE_SPEED_KMH: Double = 200.0

        /** Duración mínima para calificar a gamificación: 30 minutos (1.800.000 ms) */
        const val MIN_RIDE_DURATION_MS: Long = 30 * 60 * 1000L

        /** Mínimo de heartbeats de compañeros requeridos durante la rodada */
        const val MIN_PEER_HEARTBEATS: Int = 2

        /** Discrepancia máxima admitida entre testigos de convoy: 20% (0.20) */
        const val MAX_WITNESS_DISCREPANCY_PCT: Double = 0.20
    }
}
