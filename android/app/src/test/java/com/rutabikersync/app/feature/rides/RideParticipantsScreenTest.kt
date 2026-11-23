package com.rutabikersync.app.feature.rides

import com.rutabikersync.app.core.clubroutes.RideRosterParticipant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Fase 5 · contrato de la pantalla de participantes.
 *
 * El módulo no tiene dependencias de Compose UI test, así que la parte visual se
 * fija sobre el código fuente de la pantalla y la parte de reglas se ejecuta
 * sobre el helper puro [RideParticipantsUi]. La semántica de la ventana de 1 h
 * es la del backend (solo lectura): `now() <= starts_at - 1 hour`.
 */
class RideParticipantsScreenTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("No se encontró el fuente $relative")
    }

    private val screen = source(
        "app/src/main/java/com/rutabikersync/app/feature/rides/RideParticipantsScreen.kt"
    )

    private fun participant(
        userId: String = "yo",
        displayName: String = "Yo",
        clubRole: String? = "Miembro",
        isLeader: Boolean = false,
        status: String = "pending",
        onSiteAt: String? = null
    ) = RideRosterParticipant(
        userId = userId,
        displayName = displayName,
        clubRole = clubRole,
        isLeader = isLeader,
        status = status,
        onSiteAt = onSiteAt,
        homeAt = null,
        respondedAt = null
    )

    @Test
    fun `la fila muestra avatar nombre y cargo del roster real`() {
        assertTrue(screen.contains("RowAvatar(participant, accent)"))
        assertTrue(screen.contains("text = participant.displayName"))
        assertTrue(screen.contains("participant.clubRole"))
        assertEquals("RB", RideParticipantsUi.initials("Rutero Bikers"))
        assertEquals("RB", RideParticipantsUi.initials(" "))

        val participant = participant(userId = "u1", displayName = "The Developer")
        assertEquals("The Developer", participant.displayName)
        assertEquals("Miembro", participant.clubRole)
    }

    @Test
    fun `botones por fila asistire no asistire y en el sitio`() {
        assertTrue(screen.contains("\"Asistiré\""))
        assertTrue(screen.contains("\"No asistiré\""))
        assertTrue(screen.contains("onRsvp(\"no_go\")"))
        assertTrue(screen.contains("RideParticipantActionKind.ON_SITE -> onArrive()"))

        val base = participant()
        assertEquals(
            RideParticipantActionKind.DECISIONS,
            RideParticipantsUi.rowAction(base, "yo", null).kind
        )
        assertEquals(
            "En el sitio",
            RideParticipantsUi.rowAction(base.copy(status = "confirmed"), "yo", null).label
        )
    }

    @Test
    fun `no_go a confirmed respeta la ventana del backend antes de startsAt menos una hora`() {
        val start = Instant.parse("2026-11-25T12:00:00Z")
        assertTrue(RideParticipantsUi.canReconsider(start.toString(), start.minusSeconds(3601)))
        assertTrue(RideParticipantsUi.canReconsider(start.toString(), start.minusSeconds(3600)))
        assertFalse(RideParticipantsUi.canReconsider(start.toString(), start.minusSeconds(3599)))
        assertFalse(RideParticipantsUi.canReconsider(start.toString(), start))

        val noGo = participant(status = "no_go")
        assertEquals(
            RideParticipantActionKind.CONFIRM,
            RideParticipantsUi.rowAction(noGo, "yo", start.toString(), null, start.minusSeconds(3600)).kind
        )
        assertEquals(
            RideParticipantActionKind.NONE,
            RideParticipantsUi.rowAction(noGo, "yo", start.toString(), null, start.minusSeconds(3599)).kind
        )
    }

    @Test
    fun `iniciar ruta solo para lider con al menos un confirmado y todos en el sitio`() {
        assertTrue(screen.contains("\"Iniciar Ruta\""))
        assertTrue(screen.contains("currentUserIsLeader"))
        assertTrue(screen.contains("RideParticipantsUi.startGate"))

        fun participant(status: String, onSite: Boolean) = RideRosterParticipant(
            userId = status + onSite,
            displayName = "P",
            clubRole = "Miembro",
            isLeader = false,
            status = status,
            onSiteAt = if (onSite) "2026-11-25T11:00:00Z" else null,
            homeAt = null,
            respondedAt = null
        )

        assertFalse(RideParticipantsUi.startGate(emptyList()).enabled)
        assertFalse(RideParticipantsUi.startGate(listOf(participant("pending", false))).enabled)
        assertFalse(RideParticipantsUi.startGate(listOf(participant("confirmed", false))).enabled)
        assertFalse(
            RideParticipantsUi.startGate(
                listOf(participant("confirmed", true), participant("confirmed", false))
            ).enabled
        )
        assertTrue(
            RideParticipantsUi.startGate(
                listOf(participant("confirmed", true), participant("no_go", false))
            ).enabled
        )
    }

    @Test
    fun `el roster viene del endpoint real del backend`() {
        val client = source(
            "app/src/main/java/com/rutabikersync/app/core/network/ClubApiClient.kt"
        )
        val repository = source(
            "app/src/main/java/com/rutabikersync/app/core/clubroutes/ClubRoutesRepository.kt"
        )
        assertTrue(client.contains("getRideRoster"))
        assertTrue(repository.contains("api.getRideRoster(routeId)"))
        assertTrue(screen.contains("RideRosterParticipant"))
    }
}
