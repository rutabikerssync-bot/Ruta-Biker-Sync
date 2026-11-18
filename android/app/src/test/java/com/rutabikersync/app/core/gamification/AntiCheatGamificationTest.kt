package com.rutabikersync.app.core.gamification

import com.rutabikersync.app.feature.badges.BikerRank
import com.rutabikersync.app.feature.badges.GamificationCalculator
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de Pruebas Unitarias para Anti-Cheat Avanzado y Gamificación Off-Grid
 * (Fase 6 · Hito 5 · Etapa 4).
 *
 * Cobertura de Pruebas:
 * 1. `testAntiCheatSpeedExceeding200KmhRejected`: Velocidad > 200 km/h rechazada por teletransporte o glitch.
 * 2. `testAntiCheatNormalSpeedAccepted`: Velocidad razonable (100 km/h) aceptada normalmente.
 * 3. `testAntiCheatMeshKmExceedingTotalKmRejected`: Si meshKm > totalKm, la rodada es rechazada.
 * 4. `testAntiCheatInsufficientPeerPresenceRejected`: Menos de 2 heartbeats de compañeros rechaza la rodada.
 * 5. `testAntiCheatRideUnder30MinutesRejected`: Rodadas de menos de 30 minutos son descartadas para gamificación.
 * 6. `testAntiCheatMissingOrInvalidLeaderSignatureRejected`: Sin firma válida del Líder no se valida la rodada.
 * 7. `testAntiCheatValidLeaderSignatureAccepted`: Firma Ed25519 válida del Líder aprueba la rodada.
 * 8. `testWitnessConsensusExceeding20PctMarksAudited`: Discrepancia > 20% entre testigos marca rodada para auditoría.
 * 9. `testWitnessConsensusWithin20PctNotAudited`: Testigos con discrepancia <= 20% aprueban sin auditoría.
 * 10. `testCalculateRideXpStandard`: 10 base + 1/50km mesh + 1/10 relays + 10/SOS assist.
 * 11. `testCalculateRideXpAuditedGivesOnlyBaseScore`: Rodada bajo auditoría solo otorga 10 XP base.
 * 12. `testBikerRankProgressionAllTiers`: Novato (0) -> Rider (100) -> Veterano (500) -> Élite (2000) -> Leyenda (5000).
 * 13. `testEvaluateRideBadgesSpecialConditions`: Desbloqueo de 'sin_cobertura', 'piloto_protegido', 'convoy_seguro', 'primer_sos'.
 * 14. `testFullSummaryValidationApproved`: Resumen completo válido pasa todas las reglas anti-cheat.
 * 15. `testFullSummaryValidationTamperedFails`: Resumen adulterado es rechazado con 0 XP.
 */
class AntiCheatGamificationTest {

    private val validator = AntiCheatValidator()
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val leaderPublicKey = keyPair.public.encoded
    private val leaderPrivateKey = keyPair.private

    private fun sign(data: ByteArray, priv: PrivateKey = leaderPrivateKey): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(priv)
        s.update(data)
        return s.sign()
    }

    private fun createValidSummary(
        durationMs: Long = 35 * 60 * 1000L, // 35 minutos
        totalKm: Double = 60.0,
        meshKm: Double = 55.0,
        relays: Int = 35,
        sos: Int = 1,
        offlineMin: Int = 32,
        heartbeats: Int = 10,
        role: String = "rider",
        vaultCompleted: Boolean = true,
        medicalShared: Boolean = true,
        witnessReports: List<WitnessReport> = emptyList()
    ): RideSummary {
        val unsigned = RideSummary(
            rideId = "ride-ac-2026",
            userId = "user-101",
            durationMs = durationMs,
            totalKm = totalKm,
            meshKm = meshKm,
            relayCount = relays,
            sosAssistCount = sos,
            offlineMinutes = offlineMin,
            peerHeartbeatsReceived = heartbeats,
            leaderPublicKey = leaderPublicKey,
            leaderSignature = ByteArray(64),
            witnessReports = witnessReports,
            role = role,
            vaultCompleted = vaultCompleted,
            medicalSheetShared = medicalShared
        )
        val sig = sign(unsigned.toSignableBytes())
        return unsigned.copy(leaderSignature = sig)
    }

    @Test
    fun testAntiCheatSpeedExceeding200KmhRejected() {
        // Recorre 15 km en 2 minutos = 450 km/h (irreal / teletransporte GPS)
        val distanceMeters = 15_000.0
        val elapsedMs = 2 * 60 * 1000L
        val isValid = validator.validateGpsSpeed(distanceMeters, elapsedMs)
        assertFalse("Velocidad de 450 km/h debe ser rechazada", isValid)
    }

    @Test
    fun testAntiCheatNormalSpeedAccepted() {
        // Recorre 5 km en 3 minutos = 100 km/h (velocidad estándar motera en autopista)
        val distanceMeters = 5_000.0
        val elapsedMs = 3 * 60 * 1000L
        val isValid = validator.validateGpsSpeed(distanceMeters, elapsedMs)
        assertTrue("Velocidad de 100 km/h debe ser aceptada", isValid)
    }

    @Test
    fun testAntiCheatMeshKmExceedingTotalKmRejected() {
        // meshKm (80.0) mayor que totalKm (50.0)
        assertFalse(validator.validateMeshConsistency(meshKm = 80.0, totalKm = 50.0))
        assertTrue(validator.validateMeshConsistency(meshKm = 50.0, totalKm = 50.0))
        assertTrue(validator.validateMeshConsistency(meshKm = 40.0, totalKm = 50.0))
    }

    @Test
    fun testAntiCheatInsufficientPeerPresenceRejected() {
        // Menos de 2 heartbeats de compañeros
        assertFalse(validator.validateMinPeerPresence(peerHeartbeatsCount = 0))
        assertFalse(validator.validateMinPeerPresence(peerHeartbeatsCount = 1))
        assertTrue(validator.validateMinPeerPresence(peerHeartbeatsCount = 2))
        assertTrue(validator.validateMinPeerPresence(peerHeartbeatsCount = 15))
    }

    @Test
    fun testAntiCheatRideUnder30MinutesRejected() {
        // 29 minutos = rechazada
        assertFalse(validator.validateMinDuration(29 * 60 * 1000L))
        // 30 minutos exactos = aceptada
        assertTrue(validator.validateMinDuration(30 * 60 * 1000L))
        // 45 minutos = aceptada
        assertTrue(validator.validateMinDuration(45 * 60 * 1000L))
    }

    @Test
    fun testAntiCheatMissingOrInvalidLeaderSignatureRejected() {
        val summary = createValidSummary()
        val tamperedSig = ByteArray(64) { 0x00 }
        val invalidSummary = summary.copy(leaderSignature = tamperedSig)

        val result = validator.validateRideSummary(invalidSummary)
        assertFalse("Rodada con firma inválida del Líder debe ser rechazada", result.isValid)
        assertTrue(result.rejectionReason?.contains("Firma criptográfica") == true)
    }

    @Test
    fun testAntiCheatValidLeaderSignatureAccepted() {
        val summary = createValidSummary()
        val result = validator.validateRideSummary(summary)
        assertTrue("Rodada con firma Ed25519 legítima debe ser aprobada", result.isValid)
        assertFalse("No debe marcarse como auditada", result.isAudited)
    }

    @Test
    fun testWitnessConsensusExceeding20PctMarksAudited() {
        // Testigo 1 reporta 50 km, Testigo 2 reporta 70 km (discrepancia > 28%)
        val w1 = WitnessReport(witnessShortId = 2u, reportedKm = 50.0)
        val w2 = WitnessReport(witnessShortId = 3u, reportedKm = 70.0)
        val consensus = validator.validateWitnessConsensus(reportedMeshKm = 50.0, witnessReports = listOf(w1, w2))

        assertTrue("Discrepancia > 20% debe marcar la rodada para auditoría", consensus.isAudited)
        assertTrue(consensus.maxDiscrepancy > 0.20)
    }

    @Test
    fun testWitnessConsensusWithin20PctNotAudited() {
        // Testigo 1 reporta 50 km, Testigo 2 reporta 54 km (discrepancia ~7% <= 20%)
        val w1 = WitnessReport(witnessShortId = 2u, reportedKm = 50.0)
        val w2 = WitnessReport(witnessShortId = 3u, reportedKm = 54.0)
        val consensus = validator.validateWitnessConsensus(reportedMeshKm = 51.0, witnessReports = listOf(w1, w2))

        assertFalse("Discrepancia pequeña (<= 20%) no debe marcar auditoría", consensus.isAudited)
    }

    @Test
    fun testCalculateRideXpStandard() {
        // Base: 10 XP
        // 100 km mesh = +2 XP (100 / 50)
        // 35 relays = +3 XP (35 / 10)
        // 1 auxilio SOS = +10 XP
        // Rol Capitán = +5 XP
        // Total esperado: 10 + 2 + 3 + 10 + 5 = 30 XP
        val summary = createValidSummary(
            meshKm = 100.0,
            relays = 35,
            sos = 1,
            role = "capitan"
        )
        val xp = GamificationCalculator.calculateRideXp(summary, isAudited = false)
        assertEquals(30, xp)
    }

    @Test
    fun testCalculateRideXpAuditedGivesOnlyBaseScore() {
        // Si la rodada fue marcada bajo auditoría por discrepancia de testigos,
        // solo se otorga la puntuación base de 10 XP (cero bonos de km o relays)
        val summary = createValidSummary(
            meshKm = 100.0,
            relays = 50,
            sos = 2,
            role = "capitan"
        )
        val xp = GamificationCalculator.calculateRideXp(summary, isAudited = true)
        assertEquals(10, xp)
    }

    @Test
    fun testBikerRankProgressionAllTiers() {
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(0))
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(99))

        assertEquals(BikerRank.RIDER, BikerRank.fromXp(100))
        assertEquals(BikerRank.RIDER, BikerRank.fromXp(499))

        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(500))
        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(1999))

        assertEquals(BikerRank.ELITE, BikerRank.fromXp(2000))
        assertEquals(BikerRank.ELITE, BikerRank.fromXp(4999))

        assertEquals(BikerRank.LEYENDA, BikerRank.fromXp(5000))
        assertEquals(BikerRank.LEYENDA, BikerRank.fromXp(25000))

        assertEquals(BikerRank.RIDER, BikerRank.nextRank(BikerRank.NOVATO))
        assertEquals(BikerRank.VETERANO, BikerRank.nextRank(BikerRank.RIDER))
        assertEquals(BikerRank.ELITE, BikerRank.nextRank(BikerRank.VETERANO))
        assertEquals(BikerRank.LEYENDA, BikerRank.nextRank(BikerRank.ELITE))
        assertNull(BikerRank.nextRank(BikerRank.LEYENDA))
    }

    @Test
    fun testEvaluateRideBadgesSpecialConditions() {
        val summary = createValidSummary(
            offlineMin = 45,       // >= 30 min -> 'sin_cobertura'
            vaultCompleted = true,  // -> 'piloto_protegido'
            medicalShared = true,   // -> 'convoy_seguro'
            sos = 1                // -> 'primer_sos'
        )

        val badges = GamificationCalculator.evaluateRideBadges(summary, isAudited = false)
        assertTrue(badges.contains("primera_rodada"))
        assertTrue(badges.contains("sin_cobertura"))
        assertTrue(badges.contains("piloto_protegido"))
        assertTrue(badges.contains("convoy_seguro"))
        assertTrue(badges.contains("primer_sos"))
    }

    @Test
    fun testFullSummaryValidationApproved() {
        val summary = createValidSummary()
        val result = validator.validateRideSummary(summary)
        assertTrue(result.isValid)
        assertFalse(result.isAudited)
        assertNull(result.rejectionReason)
    }

    @Test
    fun testFullSummaryValidationTamperedFails() {
        // Resumen adulterado con duración de 10 minutos (< 30 min) y meshKm > totalKm
        val tampered = createValidSummary(
            durationMs = 10 * 60 * 1000L,
            totalKm = 20.0,
            meshKm = 45.0
        )
        val result = validator.validateRideSummary(tampered)
        assertFalse("Resumen adulterado debe ser rechazado por anti-cheat", result.isValid)
        assertNotNull(result.rejectionReason)
    }
}
