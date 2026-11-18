package com.rutabikersync.app.core.mesh.sos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de pruebas unitarias para el Motor de Proximidad del Radar SOS (Fase 7.1.5).
 */
class SosProximityEngineTest {

    @Test
    fun `test 1 - Haversine calcula con precision la distancia ortodromica entre coordenadas`() {
        // Caracas Centro a punto cercano (~1.98 km)
        val lat1 = 10.5000
        val lon1 = -66.9167
        val lat2 = 10.5120
        val lon2 = -66.9030

        val dist = SosProximityEngine.calculateDistanceKm(lat1, lon1, lat2, lon2)
        assertTrue("La distancia debe ser cercana a ~1.98 km", dist in 1.8..2.2)

        // Distancia a sí mismo debe ser 0.0
        val zeroDist = SosProximityEngine.calculateDistanceKm(lat1, lon1, lat1, lon1)
        assertEquals(0.0, zeroDist, 0.001)
    }

    @Test
    fun `test 2 - Bearing azimutal retorna los 8 rumbos cardinales con flechas tacticas`() {
        val baseLat = 10.5000
        val baseLon = -66.9167

        // Punto hacia el Norte
        val northBearing = SosProximityEngine.calculateBearing(baseLat, baseLon, baseLat + 0.05, baseLon)
        assertEquals("Norte ⬆", northBearing)

        // Punto hacia el Noreste
        val neBearing = SosProximityEngine.calculateBearing(baseLat, baseLon, baseLat + 0.03, baseLon + 0.03)
        assertEquals("Noreste ↗", neBearing)

        // Punto hacia el Este
        val eastBearing = SosProximityEngine.calculateBearing(baseLat, baseLon, baseLat, baseLon + 0.05)
        assertEquals("Este ➡", eastBearing)

        // Punto hacia el Sur
        val southBearing = SosProximityEngine.calculateBearing(baseLat, baseLon, baseLat - 0.05, baseLon)
        assertEquals("Sur ⬇", southBearing)

        // Punto hacia el Oeste
        val westBearing = SosProximityEngine.calculateBearing(baseLat, baseLon, baseLat, baseLon - 0.05)
        assertEquals("Oeste ⬅", westBearing)
    }

    @Test
    fun `test 3 - Filtro de radio de 5 km clasifica correctamente alertas dentro y fuera`() {
        assertTrue(SosProximityEngine.isWithinRadius(0.5, 5.0))
        assertTrue(SosProximityEngine.isWithinRadius(1.8, 5.0))
        assertTrue(SosProximityEngine.isWithinRadius(5.0, 5.0))

        assertFalse(SosProximityEngine.isWithinRadius(5.1, 5.0))
        assertFalse(SosProximityEngine.isWithinRadius(12.4, 5.0))
        assertFalse(SosProximityEngine.isWithinRadius(-0.1, 5.0))
    }

    @Test
    fun `test 4 - Formato de alerta genera banner motero Dark Elite sin filtrar coordenadas PII`() {
        val bannerText = SosProximityEngine.formatAlert(1.8, "Noreste ↗")
        assertEquals("🚨 SOS BIKER CERCANO — A 1.8 km al Noreste ↗", bannerText)

        // Asegurar que no expone coordenadas geográficas en texto plano
        assertFalse(bannerText.contains("lat", ignoreCase = true))
        assertFalse(bannerText.contains("lon", ignoreCase = true))
        assertFalse(bannerText.contains("10."))
        assertFalse(bannerText.contains("-66."))
    }

    @Test
    fun `test 5 - Patron haptico Morse SOS cumple exactamente con la norma internacional`() {
        val expectedPattern = longArrayOf(
            0,
            150, 100, 150, 100, 150, // S (... )
            300,                     // Pausa
            400, 100, 400, 100, 400, // O (--- )
            300,                     // Pausa
            150, 100, 150, 100, 150  // S (... )
        )

        assertArrayEquals(expectedPattern, SosProximityEngine.SOS_MORSE_VIBRATION_PATTERN)
        assertEquals(18, SosProximityEngine.SOS_MORSE_VIBRATION_PATTERN.size)
    }
}
