package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
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

    @Test
    fun `nextRoute selecciona la publicada futura mas cercana y omite pasadas o no parseables`() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val routes = listOf(
            ClubRouteEntity(
                id = "past",
                groupId = "g1",
                createdBy = "u1",
                title = "Pasada",
                startsAt = "2026-11-19T10:00:00Z",
                status = "published"
            ),
            ClubRouteEntity(
                id = "draft-future",
                groupId = "g1",
                createdBy = "u1",
                title = "Borrador",
                startsAt = "2026-11-21T10:00:00Z",
                status = "draft"
            ),
            ClubRouteEntity(
                id = "far",
                groupId = "g1",
                createdBy = "u1",
                title = "Lejana",
                startsAt = "2026-11-25T10:00:00Z",
                status = "published"
            ),
            ClubRouteEntity(
                id = "next",
                groupId = "g1",
                createdBy = "u1",
                title = "Proxima",
                startsAt = "2026-11-21T09:00:00Z",
                status = "published"
            ),
            ClubRouteEntity(
                id = "bad-date",
                groupId = "g1",
                createdBy = "u1",
                title = "Sin fecha valida",
                startsAt = "fecha-rara",
                status = "published"
            )
        )

        val next = ClubRsvpUi.nextRoute(routes, now)
        assertEquals("next", next?.id)
    }

    @Test
    fun `nextRoute devuelve null cuando no hay rutas publicadas futuras`() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val routes = listOf(
            ClubRouteEntity(
                id = "past",
                groupId = "g1",
                createdBy = "u1",
                title = "Pasada",
                startsAt = "2026-11-19T10:00:00Z",
                status = "published"
            )
        )
        assertNull(ClubRsvpUi.nextRoute(routes, now))
        assertNull(ClubRsvpUi.nextRoute(emptyList(), now))
    }

    @Test
    fun `formatRouteDateTime formatea ISO en zona indicada y maneja valores invalidos`() {
        val utc = ZoneId.of("UTC")
        val locale = Locale.US
        val formatted = ClubRsvpUi.formatRouteDateTime(
            startsAt = "2026-11-21T09:30:00Z",
            full = true,
            locale = locale,
            zoneId = utc
        )
        assertTrue(formatted.contains("2026"))
        assertTrue(formatted.contains("09:30"))

        val short = ClubRsvpUi.formatRouteDateTime(
            startsAt = "2026-11-21T09:30:00Z",
            full = false,
            locale = locale,
            zoneId = utc
        )
        assertTrue(short.contains("09:30"))

        assertEquals("Sin hora", ClubRsvpUi.formatRouteDateTime(null, zoneId = utc))
        assertEquals("fecha-rara", ClubRsvpUi.formatRouteDateTime("fecha-rara", zoneId = utc))
    }

    @Test
    fun `descriptionSnippet recorta y devuelve null si no hay descripcion`() {
        assertNull(ClubRsvpUi.descriptionSnippet(null))
        assertNull(ClubRsvpUi.descriptionSnippet("   "))
        assertEquals("Hola", ClubRsvpUi.descriptionSnippet("Hola"))
        val long = "A".repeat(120)
        val snippet = ClubRsvpUi.descriptionSnippet(long, maxChars = 20)
        assertEquals(21, snippet?.length) // 20 chars + ellipsis
        assertTrue(snippet!!.endsWith("…"))
    }

    @Test
    fun `acciones RSVP iniciales permiten confirmar o no ir`() {
        assertEquals(
            listOf(RsvpAction.CONFIRM, RsvpAction.NO_GO),
            ClubRsvpUi.getAvailableRsvpActions(null, "2026-11-21T10:00:00Z", Instant.parse("2026-11-20T10:00:00Z"))
        )
        assertEquals(
            listOf(RsvpAction.CONFIRM, RsvpAction.NO_GO),
            ClubRsvpUi.getAvailableRsvpActions("pending", "2026-11-21T10:00:00Z", Instant.parse("2026-11-20T10:00:00Z"))
        )
    }

    @Test
    fun `confirmado lejos de la salida no muestra acciones y cerca o pasado muestra en ruta`() {
        val startsAt = "2026-11-21T10:00:00Z"
        val tomorrowFar = Instant.parse("2026-11-20T08:00:00Z") // >1h antes
        assertEquals(emptyList<RsvpAction>(), ClubRsvpUi.getAvailableRsvpActions("confirmed", startsAt, tomorrowFar))

        val withinHour = Instant.parse("2026-11-21T09:30:00Z") // 30 min antes
        assertEquals(listOf(RsvpAction.EN_ROUTE), ClubRsvpUi.getAvailableRsvpActions("confirmed", startsAt, withinHour))

        val afterStart = Instant.parse("2026-11-21T10:30:00Z")
        assertEquals(listOf(RsvpAction.EN_ROUTE), ClubRsvpUi.getAvailableRsvpActions("confirmed", startsAt, afterStart))
    }

    @Test
    fun `en ruta permite marcar llegada y en casa no ofrece acciones`() {
        val now = Instant.parse("2026-11-21T11:00:00Z")
        assertEquals(
            listOf(RsvpAction.HOME),
            ClubRsvpUi.getAvailableRsvpActions("en_route", "2026-11-21T10:00:00Z", now)
        )
        assertEquals(
            emptyList<RsvpAction>(),
            ClubRsvpUi.getAvailableRsvpActions("home", "2026-11-21T10:00:00Z", now)
        )
    }

    @Test
    fun `statusLabel reconoce en_route`() {
        assertEquals("En ruta", ClubRsvpUi.statusLabel("en_route"))
    }
}
