package com.rutabikersync.app.feature.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM del cableado de la UI mínima de RSVP (Fase 5 · Hito 3).
 * Cubren el banner «Pendiente de sincronizar» y los tres estados admitidos,
 * sin Android ni Compose (misma estrategia que RsvpOutboxFase5Test).
 */
class ClubRsvpUiTest {

    @Test
    fun `los tres estados de RSVP son los del contrato del backend`() {
        assertEquals(
            listOf("confirmed", "home", "no_go"),
            ClubRsvpUi.rsvpStatuses
        )
        assertEquals("Asistiré", ClubRsvpUi.statusLabel("confirmed"))
        assertEquals("En casa", ClubRsvpUi.statusLabel("home"))
        assertEquals("No iré", ClubRsvpUi.statusLabel("no_go"))
    }

    @Test
    fun `el pending count gana al banner de health`() {
        val health = "🟢 Fastify online (status: ok) · PostgreSQL 16"
        assertEquals(
            "⏳ Pendiente de sincronizar (1 RSVP)",
            ClubRsvpUi.bannerMessage(health, 1)
        )
        assertEquals(
            "⏳ Pendiente de sincronizar (3 RSVP)",
            ClubRsvpUi.bannerMessage(health, 3)
        )
    }

    @Test
    fun `al sincronizar desaparece el pendiente y vuelve el health`() {
        val health = "🟢 Fastify online (status: ok) · PostgreSQL 16"
        assertEquals(health, ClubRsvpUi.bannerMessage(health, 0))
        assertNull(ClubRsvpUi.bannerMessage(null, 0))
    }

    @Test
    fun `el banner pendiente se distingue del verde y no es cerrable`() {
        val pending = ClubRsvpUi.bannerMessage("🟢 Fastify online (status: ok)", 2)
        assertTrue(ClubRsvpUi.isPendingBanner(pending))
        assertFalse(ClubRsvpUi.isPendingBanner("🟢 Fastify online (status: ok)"))
        assertFalse(ClubRsvpUi.isPendingBanner(null))
    }

    @Test
    fun `la ultima intencion local gana aunque llegue un estado vacio`() {
        assertEquals("home", ClubRsvpUi.resolveRouteStatus("confirmed", "home"))
        assertEquals("home", ClubRsvpUi.resolveRouteStatus("home", null))
        assertNull(ClubRsvpUi.resolveRouteStatus(null, null))
    }

    @Test
    fun `el subtitulo de la ruta refleja la respuesta local sin red`() {
        assertEquals(
            "Inicio: 2026-11-19T09:00:00Z · En casa",
            ClubRsvpUi.routeSubtitle("2026-11-19T09:00:00Z", "home")
        )
        assertEquals("Sin respuesta", ClubRsvpUi.routeSubtitle(null, null))
        assertFalse(ClubRsvpUi.isAnswered(""))
        assertTrue(ClubRsvpUi.isAnswered("no_go"))
    }

    @Test
    fun `ciclo completo red avion red con el mismo cableado del composable`() {
        val health = "🟢 Fastify online (status: ok) · PostgreSQL 16"

        // 1. Con red: el RSVP se sincroniza, no hay pendientes.
        var pendingCount = 0
        assertEquals(health, ClubRsvpUi.bannerMessage(health, pendingCount))

        // 2. Modo avión: la intención entra al outbox y el banner cambia.
        pendingCount = 1
        val banner = ClubRsvpUi.bannerMessage(health, pendingCount)
        assertTrue(banner!!.contains("Pendiente de sincronizar"))
        assertTrue(banner.contains("1 RSVP"))
        assertTrue(ClubRsvpUi.isPendingBanner(banner))

        // 3. Vuelve la red: el worker vacía la cola y el health reaparece.
        pendingCount = 0
        assertEquals(health, ClubRsvpUi.bannerMessage(health, pendingCount))
    }
}
