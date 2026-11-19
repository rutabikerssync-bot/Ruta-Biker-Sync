package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX distancia al líder: el formato debe ser honesto y ocultar el campo cuando
 * no hay un dato fiable, en lugar de mostrar metros absurdos.
 */
class LeaderDistanceFormatterTest {

    @Test
    fun `distancias cortas se muestran en metros`() {
        assertEquals("15 m", LeaderDistanceFormatter.format(15))
        assertEquals("320 m", LeaderDistanceFormatter.format(320))
        assertEquals("999 m", LeaderDistanceFormatter.format(999))
    }

    @Test
    fun `distancias largas se convierten a kilometros con un decimal`() {
        assertEquals("1.0 km", LeaderDistanceFormatter.format(1_000))
        assertEquals("3.5 km", LeaderDistanceFormatter.format(3_500))
        assertEquals("12.3 km", LeaderDistanceFormatter.format(12_345))
    }

    @Test
    fun `sin dato fiable se oculta el campo`() {
        assertNull(LeaderDistanceFormatter.format(null))
        assertNull(LeaderDistanceFormatter.format(0))
        assertNull(LeaderDistanceFormatter.format(-5))
        assertNull(LeaderDistanceFormatter.format(15, hasGpsFix = false))
        assertNull(LeaderDistanceFormatter.format(15, isLeader = true))
        // El valor reportado (3.586.952 m) es un punto de referencia falso, no una
        // distancia real de 3586 km: se oculta en lugar de presentarlo como válido.
        assertNull(LeaderDistanceFormatter.format(3_586_952))
    }

    @Test
    fun `valor absurdo se considera no plausible`() {
        assertFalse(LeaderDistanceFormatter.isPlausible(null))
        assertFalse(LeaderDistanceFormatter.isPlausible(0))
        assertTrue(LeaderDistanceFormatter.isPlausible(3_500))
        assertFalse(LeaderDistanceFormatter.isPlausible(3_586_952))
    }
}
