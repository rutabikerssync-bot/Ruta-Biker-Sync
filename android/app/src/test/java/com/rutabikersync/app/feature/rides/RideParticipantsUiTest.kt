package com.rutabikersync.app.feature.rides

import com.rutabikersync.app.core.clubroutes.RideRosterParticipant
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideParticipantsUiTest {
    private val now = Instant.parse("2026-11-21T13:30:00Z")
    private val startsAt = "2026-11-21T14:00:00Z"

    private fun participant(
        id: String,
        status: String,
        onSiteAt: String? = null,
        homeAt: String? = null
    ) = RideRosterParticipant(
        userId = id,
        displayName = "Rider $id",
        clubRole = null,
        isLeader = id == "leader",
        status = status,
        onSiteAt = onSiteAt,
        homeAt = homeAt,
        respondedAt = null
    )

    @Test
    fun `pendiente ofrece Asistire y No asistire solo al propio usuario`() {
        val own = RideParticipantsUi.rowAction(participant("member", "pending"), "member", startsAt, now)
        assertEquals(RideParticipantActionKind.DECISIONS, own.kind)
        assertTrue(own.enabled)

        val other = RideParticipantsUi.rowAction(participant("other", "pending"), "member", startsAt, now)
        assertEquals(RideParticipantActionKind.NONE, other.kind)
        assertFalse(other.enabled)
    }

    @Test
    fun `confirmado propio puede marcar En el sitio y repetido queda deshabilitado`() {
        val participant = participant("member", "confirmed")
        val action = RideParticipantsUi.rowAction(participant, "member", startsAt, now)
        assertEquals(RideParticipantActionKind.ON_SITE, action.kind)
        assertEquals("En el sitio", action.label)

        val arrived = RideParticipantsUi.rowAction(
            participant.copy(onSiteAt = now.toString()), "member", startsAt, now
        )
        assertEquals("En el sitio ✓", arrived.label)
        assertFalse(arrived.enabled)
    }

    @Test
    fun `no_go permite reconsiderar con mas de 1h de anticipacion y hasta T-1h`() {
        val participant = participant("member", "no_go")
        val twoHoursBefore = Instant.parse("2026-11-21T12:00:00Z")
        assertTrue(RideParticipantsUi.canReconsider(startsAt, twoHoursBefore))
        val action = RideParticipantsUi.rowAction(participant, "member", startsAt, null, twoHoursBefore)
        assertEquals(RideParticipantActionKind.CONFIRM, action.kind)
        assertEquals("Cambiar a asistiré", action.label)
        assertTrue(action.enabled)

        val exactlyOneHourBefore = Instant.parse("2026-11-21T13:00:00Z")
        assertTrue(RideParticipantsUi.canReconsider(startsAt, exactlyOneHourBefore))
    }

    @Test
    fun `no_go bloqueado a menos de 1h del inicio o posterior`() {
        val participant = participant("member", "no_go")
        val lessThanOneHour = Instant.parse("2026-11-21T13:00:01Z")
        assertFalse(RideParticipantsUi.canReconsider(startsAt, lessThanOneHour))
        val action = RideParticipantsUi.rowAction(participant, "member", startsAt, null, lessThanOneHour)
        assertEquals(RideParticipantActionKind.NONE, action.kind)
        assertEquals("No asistiré ✗", action.label)
        assertFalse(action.enabled)

        val thirtyMinBefore = Instant.parse("2026-11-21T13:30:00Z")
        assertFalse(RideParticipantsUi.canReconsider(startsAt, thirtyMinBefore))
        assertFalse(RideParticipantsUi.canReconsider(startsAt, Instant.parse(startsAt)))
    }

    @Test
    fun `retorno activo habilita boton Ya en casa para confirmados y en casa es terminal`() {
        val onSiteParticipant = participant("member", "confirmed", onSiteAt = now.toString())
        val action = RideParticipantsUi.rowAction(
            onSiteParticipant, "member", startsAt, returnStartedAt = now.toString(), now = now
        )
        assertEquals(RideParticipantActionKind.HOME, action.kind)
        assertEquals("Ya en casa", action.label)
        assertTrue(action.enabled)

        val home = RideParticipantsUi.rowAction(
            participant("member", "home", homeAt = now.toString()), "member", startsAt, returnStartedAt = now.toString(), now = now
        )
        assertEquals("En casa ✓", home.label)
        assertFalse(home.enabled)
    }

    @Test
    fun `inicio bloqueado con cero confirmados y uno que no llego`() {
        val zero = RideParticipantsUi.startGate(emptyList())
        assertFalse(zero.enabled)
        assertEquals("Aún no hay asistentes confirmados", zero.blockedMessage)

        val missing = RideParticipantsUi.startGate(listOf(participant("one", "confirmed")))
        assertFalse(missing.enabled)
        assertEquals(1, missing.missingCount)
        assertEquals("Faltan 1 asistentes por llegar", missing.blockedMessage)
    }

    @Test
    fun `inicio se habilita con un confirmado llegado e ignora pendientes y no_go`() {
        val gate = RideParticipantsUi.startGate(
            listOf(
                participant("leader", "confirmed", now.toString()),
                participant("pending", "pending"),
                participant("declined", "no_go")
            )
        )
        assertTrue(gate.enabled)
        assertEquals(1, gate.confirmedCount)
        assertEquals(0, gate.missingCount)
    }

    @Test
    fun `inicio con dos confirmados exige las dos presencias`() {
        val oneMissing = RideParticipantsUi.startGate(
            listOf(
                participant("leader", "confirmed", now.toString()),
                participant("member", "confirmed")
            )
        )
        assertFalse(oneMissing.enabled)
        assertEquals(1, oneMissing.missingCount)

        val allArrived = RideParticipantsUi.startGate(
            listOf(
                participant("leader", "confirmed", now.toString()),
                participant("member", "confirmed", now.plusSeconds(30).toString())
            )
        )
        assertTrue(allArrived.enabled)
    }
}

private fun String.toInstant(): Instant = Instant.parse(this)
