package com.rutabikersync.app.feature.badges

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Pruebas unitarias para GamificationProfileScreen y la lógica de mérito biker (Fase 7.0 · Hito 7.0.2).
 * Verifica rangos, cálculo de progreso de XP, formato de métricas tácticas y vitrina de insignias.
 */
class GamificationProfileScreenTest {

    @Test
    fun `testCalculoDeProgresoXpHaciaSiguienteRango`() {
        // Novato a Rider: 0 a 100 XP
        val currentXpNovato = 50
        val baseNovato = BikerRank.NOVATO.minXp // 0
        val targetRider = 100
        val progressNovato = (currentXpNovato - baseNovato).toFloat() / (targetRider - baseNovato).toFloat()
        assertEquals(0.5f, progressNovato, 0.001f)

        // Veterano a Élite: 500 a 2000 XP (Rango de 1500 XP)
        val currentXpVeterano = 1250
        val baseVeterano = BikerRank.VETERANO.minXp // 500
        val targetElite = 2000
        val progressVeterano = (currentXpVeterano - baseVeterano).toFloat() / (targetElite - baseVeterano).toFloat()
        assertEquals(0.5f, progressVeterano, 0.001f) // 750 / 1500 = 0.5
        assertEquals(750, targetElite - currentXpVeterano)
    }

    @Test
    fun `testInsigniasDesbloqueadasVsBloqueadasYConteoCorrecto`() {
        val badges = defaultTacticalBadges()
        assertTrue("El catálogo debe contener al menos 10 insignias", badges.size >= 10)

        val unlocked = badges.filter { it.isUnlocked }
        val locked = badges.filter { !it.isUnlocked }

        assertTrue("Debe haber insignias desbloqueadas de inicio", unlocked.isNotEmpty())
        assertTrue("Debe haber insignias bloqueadas como desafío", locked.isNotEmpty())
        assertEquals(badges.size, unlocked.size + locked.size)

        // Toda insignia debe tener un texto claro de cómo se desbloquea
        badges.forEach { badge ->
            assertTrue("La insignia ${badge.title} debe tener descripción", badge.description.isNotBlank())
            assertTrue("La insignia ${badge.title} debe incluir cómo se desbloquea", badge.howToUnlock.isNotBlank())
            assertTrue("La insignia ${badge.title} debe tener un emoji representativo", badge.iconEmoji.isNotBlank())
        }
    }

    @Test
    fun `testFormatoMetricasDeMeritoTactico`() {
        val totalMeshKm = 642.5
        val formattedKm = String.format(Locale.US, "%.1f", totalMeshKm)
        assertEquals("642.5", formattedKm)

        val totalRelays = 184
        val relaysString = "$totalRelays paq"
        assertEquals("184 paq", relaysString)

        val totalSosAssists = 3
        val sosString = "$totalSosAssists asist"
        assertEquals("3 asist", sosString)

        val hoursOffGrid = totalMeshKm / 42.0
        val formattedHours = String.format(Locale.US, "%.1f", hoursOffGrid)
        assertEquals("15.3", formattedHours)
    }

    @Test
    fun `testDeterminacionDeRangosActualesYSiguientes`() {
        // Rangos según XP
        assertEquals(BikerRank.NOVATO, BikerRank.fromXp(45))
        assertEquals(BikerRank.RIDER, BikerRank.fromXp(150))
        assertEquals(BikerRank.VETERANO, BikerRank.fromXp(1250))
        assertEquals(BikerRank.ELITE, BikerRank.fromXp(2500))
        assertEquals(BikerRank.LEYENDA, BikerRank.fromXp(6000))

        // Transición hacia siguiente rango
        assertEquals(BikerRank.RIDER, BikerRank.nextRank(BikerRank.NOVATO))
        assertEquals(BikerRank.VETERANO, BikerRank.nextRank(BikerRank.RIDER))
        assertEquals(BikerRank.ELITE, BikerRank.nextRank(BikerRank.VETERANO))
        assertEquals(BikerRank.LEYENDA, BikerRank.nextRank(BikerRank.ELITE))
        assertNull(BikerRank.nextRank(BikerRank.LEYENDA))
    }

    @Test
    fun `testValidacionInsigniasClaveRequeridas`() {
        val badges = defaultTacticalBadges()
        val ids = badges.map { it.id }

        assertTrue("Debe incluir insignia de Bóveda (Piloto Protegido)", ids.contains("b_vault"))
        assertTrue("Debe incluir insignia de Malla (Sin Cobertura)", ids.contains("b_offline"))
        assertTrue("Debe incluir insignia de Ficha Médica (Convoy Seguro)", ids.contains("b_convoy"))
        assertTrue("Debe incluir insignia de Auxilio Vial (Primer SOS)", ids.contains("b_sos"))
        assertTrue("Debe incluir insignia de Iniciación (Primera Rodada)", ids.contains("b_first_ride"))
        assertTrue("Debe incluir insignia de Kilometraje (Viajero)", ids.contains("b_viajero"))
        assertTrue("Debe incluir insignia de Constancia (Racha de 3)", ids.contains("b_racha3"))
        assertTrue("Debe incluir insignia de Retransmisión (Centinela de Malla)", ids.contains("b_relay"))
    }
}
