package com.rutabikersync.app.feature.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de la lógica de Gamificación y Progresión (Fase 6).
 * Valida GAMIFICATION.md (§2, §3, §4, §5, §6, §7), RULES.md (§8) y D48, D49, P9.1, P9.3.
 */
class GamificationPhase6Test {

    @Test
    fun testBikerRanksByXpThresholds() {
        // Novato: 0 a 99 XP
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(0))
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(50))
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(99))

        // Rider: 100 a 499 XP
        assertEquals(BikerRank.RIDER, BikerRank.fromXp(100))
        assertEquals(BikerRank.RIDER, BikerRank.fromXp(250))
        assertEquals(BikerRank.RIDER, BikerRank.fromXp(499))

        // Veterano: 500 a 1.999 XP
        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(500))
        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(1200))
        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(1999))

        // Élite: 2.000 a 4.999 XP
        assertEquals(BikerRank.ELITE, BikerRank.fromXp(2000))
        assertEquals(BikerRank.ELITE, BikerRank.fromXp(3500))
        assertEquals(BikerRank.ELITE, BikerRank.fromXp(4999))

        // Leyenda: 5.000+ XP
        assertEquals(BikerRank.LEYENDA, BikerRank.fromXp(5000))
        assertEquals(BikerRank.LEYENDA, BikerRank.fromXp(15000))
    }

    @Test
    fun testRankProgressionSequence() {
        assertEquals(BikerRank.RIDER, BikerRank.nextRank(BikerRank.NOVATO))
        assertEquals(BikerRank.VETERANO, BikerRank.nextRank(BikerRank.RIDER))
        assertEquals(BikerRank.ELITE, BikerRank.nextRank(BikerRank.VETERANO))
        assertEquals(BikerRank.LEYENDA, BikerRank.nextRank(BikerRank.ELITE))
        assertNull(BikerRank.nextRank(BikerRank.LEYENDA))
    }

    @Test
    fun testRideXpCalculationBaseAndMileage() {
        // 10 XP base + 0 (menos de 50 km)
        val xpShort = GamificationCalculator.calculateRideXp(distanceKm = 35.0, role = "rider")
        assertEquals(10, xpShort)

        // 10 base + 2 (120 km / 50 = 2)
        val xpMedium = GamificationCalculator.calculateRideXp(distanceKm = 120.0, role = "rider")
        assertEquals(12, xpMedium)

        // 10 base + 5 (250 km / 50 = 5)
        val xpLong = GamificationCalculator.calculateRideXp(distanceKm = 250.0, role = "rider")
        assertEquals(15, xpLong)
    }

    @Test
    fun testRideXpCalculationWithConvoyRolesAndBonuses() {
        // Capitán de ruta: +5 XP
        val xpCaptain = GamificationCalculator.calculateRideXp(distanceKm = 100.0, role = "capitan")
        // 10 base + 2 km + 5 capitan = 17
        assertEquals(17, xpCaptain)

        // Sweep (Barredor): +3 XP
        val xpSweep = GamificationCalculator.calculateRideXp(distanceKm = 100.0, role = "sweep")
        // 10 base + 2 km + 3 sweep = 15
        assertEquals(15, xpSweep)

        // Scout: +2 XP
        val xpScout = GamificationCalculator.calculateRideXp(distanceKm = 100.0, role = "scout")
        // 10 base + 2 km + 2 scout = 14
        assertEquals(14, xpScout)

        // Auxilio vial SOS (+10 XP) y bonificación de Racha 3 meses (+20 XP)
        val xpFullBonus = GamificationCalculator.calculateRideXp(
            distanceKm = 150.0, // +3 km
            role = "capitan",   // +5
            answeredSos = true, // +10
            streak3MonthsUnlocked = true // +20
        )
        // 10 + 3 + 5 + 10 + 20 = 48
        assertEquals(48, xpFullBonus)
    }

    @Test
    fun testNextMileageMilestoneProgression() {
        // Con 0 km acumulados -> objetivo 10 km (Primeros Pasos)
        val (badge1, target1, rem1) = GamificationCalculator.getNextMileageMilestone(0.0)
        assertEquals("Primeros Pasos", badge1)
        assertEquals(10.0, target1, 0.001)
        assertEquals(10.0, rem1, 0.001)

        // Con 45 km -> objetivo 50 km (Rodador), faltan 5 km
        val (badge2, target2, rem2) = GamificationCalculator.getNextMileageMilestone(45.0)
        assertEquals("Rodador", badge2)
        assertEquals(50.0, target2, 0.001)
        assertEquals(5.0, rem2, 0.001)

        // Con 4.800 km -> objetivo 5.000 km (Aventurero), faltan 200 km
        val (badge3, target3, rem3) = GamificationCalculator.getNextMileageMilestone(4800.0)
        assertEquals("Aventurero", badge3)
        assertEquals(5000.0, target3, 0.001)
        assertEquals(200.0, rem3, 0.001)

        // Con 120.000 km -> objetivo Legendario alcanzado
        val (badgeMax, targetMax, remMax) = GamificationCalculator.getNextMileageMilestone(120000.0)
        assertEquals("Legendario", badgeMax)
        assertEquals(100000.0, targetMax, 0.001)
        assertEquals(0.0, remMax, 0.001)
    }

    @Test
    fun testBadgeItemPropertiesAndFamilies() {
        val badge = BadgeItem(
            id = "test_1",
            code = "primeros_pasos",
            name = "Primeros Pasos",
            description = "10 km acumulados",
            family = BadgeFamily.KILOMETRAJE,
            iconRes = 0,
            unlocked = true,
            currentProgress = 10.0,
            maxProgress = 10.0,
            unit = "km"
        )

        assertTrue(badge.unlocked)
        assertEquals(BadgeFamily.KILOMETRAJE, badge.family)
        assertEquals(10.0, badge.currentProgress, 0.001)
    }
}
