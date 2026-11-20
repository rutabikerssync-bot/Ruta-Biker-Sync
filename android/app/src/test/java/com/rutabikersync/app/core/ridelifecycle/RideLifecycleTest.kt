package com.rutabikersync.app.core.ridelifecycle

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLifecycleTest {
    private val startsAt = Instant.parse("2026-11-21T14:00:00Z")
    private val windowStart = startsAt.minusSeconds(3600)
    private val beforeWindow = windowStart.minusSeconds(1)

    private fun participant(
        id: String,
        rsvp: RideRsvpStatus = RideRsvpStatus.PENDING,
        onSiteAt: Instant? = null
    ) = RideParticipant(userId = id, rsvp = rsvp, onSiteAt = onSiteAt)

    private fun state(
        vararg participants: RideParticipant,
        phase: RidePhase = RidePhase.SCHEDULED
    ) = RideLifecycleState(
        hostUserId = "host",
        startsAt = startsAt,
        participants = participants.toList(),
        phase = phase
    )

    private fun readyState(vararg extraParticipants: RideParticipant): RideLifecycleState = state(
        participant("host", RideRsvpStatus.CONFIRMED, windowStart),
        *extraParticipants
    )

    private fun error(codeNe: () -> Unit): RideLifecycleException =
        assertThrows(RideLifecycleException::class.java, codeNe)

    @Test
    fun `01 pendiente puede elegir asistir o no asistir`() {
        val initial = state(participant("member"))
        val confirmed = RideLifecycle.updateRsvp(
            initial, "member", "member", RideRsvpStatus.CONFIRMED, beforeWindow
        )
        assertEquals(RideRsvpStatus.CONFIRMED, confirmed.participants.single().rsvp)

        val declined = RideLifecycle.updateRsvp(
            initial, "member", "member", RideRsvpStatus.NO_GO, beforeWindow
        )
        assertEquals(RideRsvpStatus.NO_GO, declined.participants.single().rsvp)
    }

    @Test
    fun `02 no_go no puede reconsiderar antes de T-1h`() {
        val initial = state(participant("member", RideRsvpStatus.NO_GO))
        val error = error {
            RideLifecycle.updateRsvp(
                initial, "member", "member", RideRsvpStatus.CONFIRMED, beforeWindow
            )
        }
        assertEquals(RideLifecycleError.OUTSIDE_DECISION_WINDOW, error.error)
        assertEquals(409, error.error.httpStatus)
        assertEquals(RideRsvpStatus.NO_GO, initial.participants.single().rsvp)
    }

    @Test
    fun `03 no_go puede cambiar a confirmed exactamente en T-1h y dentro de ventana`() {
        val initial = state(participant("member", RideRsvpStatus.NO_GO))
        val atBoundary = RideLifecycle.updateRsvp(
            initial, "member", "member", RideRsvpStatus.CONFIRMED, windowStart
        )
        assertEquals(RideRsvpStatus.CONFIRMED, atBoundary.participants.single().rsvp)

        val insideWindow = RideLifecycle.updateRsvp(
            initial,
            "member",
            "member",
            RideRsvpStatus.CONFIRMED,
            startsAt.minusSeconds(1)
        )
        assertEquals(RideRsvpStatus.CONFIRMED, insideWindow.participants.single().rsvp)
    }

    @Test
    fun `04 no_go no puede cambiar a confirmed desde startsAt`() {
        val initial = state(participant("member", RideRsvpStatus.NO_GO))
        val error = error {
            RideLifecycle.updateRsvp(
                initial, "member", "member", RideRsvpStatus.CONFIRMED, startsAt
            )
        }
        assertEquals(RideLifecycleError.OUTSIDE_DECISION_WINDOW, error.error)
    }

    @Test
    fun `05 cero confirmados no habilita inicio`() {
        val initial = state(participant("pending"), participant("declined", RideRsvpStatus.NO_GO))
        assertFalse(RideLifecycle.canStartRoute(initial))
        assertEquals(RidePhase.SCHEDULED, RideLifecycle.effectivePhase(initial))
    }

    @Test
    fun `06 pendientes y no_go no bloquean inicio`() {
        val initial = readyState(
            participant("pending"),
            participant("declined", RideRsvpStatus.NO_GO)
        )
        assertTrue(RideLifecycle.canStartRoute(initial))
        assertEquals(RidePhase.READY, RideLifecycle.effectivePhase(initial))
    }

    @Test
    fun `07 todos los confirmados deben llegar al sitio`() {
        val initial = readyState(participant("member", RideRsvpStatus.CONFIRMED))
        val hostReady = RideLifecycle.effectivePhase(initial)
        assertEquals(RidePhase.SCHEDULED, hostReady)

        val arrived = RideLifecycle.markOnSite(initial, "member", "member", windowStart)
        assertEquals(windowStart, arrived.participant("member").onSiteAt)
        assertEquals(RidePhase.READY, RideLifecycle.effectivePhase(arrived))
    }

    @Test
    fun `08 un nuevo confirmado vuelve a bloquear inicio`() {
        val initial = readyState(participant("member", RideRsvpStatus.NO_GO))
        assertEquals(RidePhase.READY, RideLifecycle.effectivePhase(initial))

        val changed = RideLifecycle.updateRsvp(
            initial, "member", "member", RideRsvpStatus.CONFIRMED, windowStart
        )
        assertEquals(RidePhase.SCHEDULED, RideLifecycle.effectivePhase(changed))
        assertFalse(RideLifecycle.canStartRoute(changed))
    }

    @Test
    fun `09 un participante no puede modificar a otro`() {
        val initial = state(participant("host"), participant("member"))
        val rsvpError = error {
            RideLifecycle.updateRsvp(
                initial, "host", "member", RideRsvpStatus.CONFIRMED, beforeWindow
            )
        }
        assertEquals(RideLifecycleError.FORBIDDEN, rsvpError.error)
        assertEquals(403, rsvpError.error.httpStatus)

        val siteError = error {
            RideLifecycle.markOnSite(initial, "host", "member", beforeWindow)
        }
        assertEquals(RideLifecycleError.FORBIDDEN, siteError.error)
    }

    @Test
    fun `10 inicio sin todos los confirmados en sitio devuelve 409`() {
        val initial = readyState(participant("member", RideRsvpStatus.CONFIRMED))
        val error = error { RideLifecycle.startRide(initial, "host", startsAt) }
        assertEquals(RideLifecycleError.START_GATE_NOT_MET, error.error)
        assertEquals(409, error.error.httpStatus)
        assertNull(initial.outboundStartedAt)
        assertEquals(RidePhase.SCHEDULED, initial.phase)
    }

    @Test
    fun `11 retorno antes de finalizar ida devuelve 409`() {
        val outbound = RideLifecycle.startRide(readyState(), "host", startsAt)
        val error = error { RideLifecycle.startReturn(outbound, "host", startsAt.plusSeconds(60)) }
        assertEquals(RideLifecycleError.INVALID_PHASE, error.error)
        assertEquals(409, error.error.httpStatus)
        assertNull(outbound.returnStartedAt)
    }

    @Test
    fun `12 casa antes del retorno devuelve 409`() {
        val outbound = RideLifecycle.startRide(readyState(), "host", startsAt)
        val atDestination = RideLifecycle.finishOutbound(outbound, "host", startsAt.plusSeconds(60))
        val error = error {
            RideLifecycle.markHome(atDestination, "host", "host", startsAt.plusSeconds(120))
        }
        assertEquals(RideLifecycleError.HOME_NOT_ALLOWED, error.error)
        assertEquals(409, error.error.httpStatus)
        assertNull(atDestination.participant("host").homeAt)
    }

    @Test
    fun `13 home es terminal`() {
        val outbound = RideLifecycle.startRide(readyState(), "host", startsAt)
        val atDestination = RideLifecycle.finishOutbound(outbound, "host", startsAt.plusSeconds(60))
        val returning = RideLifecycle.startReturn(atDestination, "host", startsAt.plusSeconds(120))
        val home = RideLifecycle.markHome(returning, "host", "host", startsAt.plusSeconds(180))

        val retryHome = RideLifecycle.markHome(home, "host", "host", startsAt.plusSeconds(240))
        assertEquals(home, retryHome)
        assertEquals(startsAt.plusSeconds(180), retryHome.participant("host").homeAt)

        val error = error {
            RideLifecycle.updateRsvp(
                retryHome, "host", "host", RideRsvpStatus.NO_GO, startsAt.plusSeconds(300)
            )
        }
        assertEquals(RideLifecycleError.HOME_TERMINAL, error.error)
    }

    @Test
    fun `14 reintentos no duplican ni reinician timestamps`() {
        val initial = readyState(participant("pending"))
        val startedAt = startsAt.minusSeconds(30)
        val destinationAt = startsAt.plusSeconds(1000)
        val returnAt = startsAt.plusSeconds(1100)
        val homeAt = startsAt.plusSeconds(2000)

        val started = RideLifecycle.startRide(initial, "host", startedAt)
        val startedRetry = RideLifecycle.startRide(started, "host", startedAt.plusSeconds(20))
        assertEquals(startedAt, startedRetry.outboundStartedAt)
        assertEquals(startedAt, startedRetry.participant("host").enRouteAt)

        val destination = RideLifecycle.finishOutbound(startedRetry, "host", destinationAt)
        val destinationRetry = RideLifecycle.finishOutbound(destination, "host", destinationAt.plusSeconds(20))
        assertEquals(destinationAt, destinationRetry.destinationReachedAt)

        val returning = RideLifecycle.startReturn(destinationRetry, "host", returnAt)
        val returnRetry = RideLifecycle.startReturn(returning, "host", returnAt.plusSeconds(20))
        assertEquals(returnAt, returnRetry.returnStartedAt)

        val home = RideLifecycle.markHome(returnRetry, "host", "host", homeAt)
        val homeRetry = RideLifecycle.markHome(home, "host", "host", homeAt.plusSeconds(20))
        assertEquals(homeAt, homeRetry.participant("host").homeAt)
        assertEquals(initial.participants.size, homeRetry.participants.size)
    }

    @Test
    fun `15 snapshot restaurado conserva fase estados y timestamps tras reinicio`() {
        val outbound = RideLifecycle.startRide(readyState(participant("pending")), "host", startsAt)
        val destination = RideLifecycle.finishOutbound(outbound, "host", startsAt.plusSeconds(1000))
        val returning = RideLifecycle.startReturn(destination, "host", startsAt.plusSeconds(1100))
        val withHome = RideLifecycle.markHome(returning, "host", "host", startsAt.plusSeconds(2000))

        val restored = RideLifecycle.restore(RideLifecycle.snapshot(withHome))
        assertEquals(withHome, restored)
        assertEquals(RidePhase.RETURNING, RideLifecycle.effectivePhase(restored))
        assertEquals(withHome.participants, restored.participants)
        assertEquals(withHome.returnStartedAt, restored.returnStartedAt)
    }

    private fun RideLifecycleState.participant(userId: String): RideParticipant =
        participants.single { it.userId == userId }
}
