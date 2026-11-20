package com.rutabikersync.app.feature.routes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMetadataRulesTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val futureLocal = LocalDateTime.of(2026, 11, 21, 8, 30)

    @Test
    fun `titulo vacio o corto se rechaza`() {
        assertTrue(RideMetadataRules.validate("  ", null, futureLocal, ZoneOffset.UTC, now).isFailure)
        assertTrue(RideMetadataRules.validate("ab", null, futureLocal, ZoneOffset.UTC, now).isFailure)
    }

    @Test
    fun `titulo valido se recorta`() {
        val result = RideMetadataRules.validate("  Ruta Ávila  ", null, futureLocal, ZoneOffset.UTC, now)
        assertTrue(result.isSuccess)
        assertEquals("Ruta Ávila", result.getOrThrow().title)
    }

    @Test
    fun `titulo demasiado largo se rechaza`() {
        val title = "a".repeat(RideMetadataRules.MAX_TITLE_LENGTH + 1)
        assertTrue(RideMetadataRules.validate(title, null, futureLocal, ZoneOffset.UTC, now).isFailure)
    }

    @Test
    fun `fecha y hora deben ser futuras`() {
        val past = LocalDateTime.of(2026, 11, 20, 12, 0)
        assertTrue(RideMetadataRules.validate("Rodada", null, past, ZoneOffset.UTC, now).isFailure)
        assertTrue(RideMetadataRules.validate("Rodada", null, futureLocal, ZoneOffset.UTC, now).isSuccess)
    }

    @Test
    fun `descripcion vacia se normaliza a null`() {
        val result = RideMetadataRules.validate("Rodada", "   ", futureLocal, ZoneOffset.UTC, now)
        assertNull(result.getOrThrow().description)
        assertNull(RideMetadataRules.normalizeDescription(null))
    }

    @Test
    fun `descripcion valida se recorta`() {
        val result = RideMetadataRules.validate(
            "Rodada", "  Briefing y salida en convoy  ", futureLocal, ZoneOffset.UTC, now
        )
        assertEquals("Briefing y salida en convoy", result.getOrThrow().description)
    }

    @Test
    fun `fecha local se convierte al Instant UTC correcto`() {
        val result = RideMetadataRules.validate(
            "Rodada", null, futureLocal, ZoneId.of("America/Caracas"), now
        )
        assertEquals(Instant.parse("2026-11-21T12:30:00Z"), result.getOrThrow().startsAt)
    }
}
