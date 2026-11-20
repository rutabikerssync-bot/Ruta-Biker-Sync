package com.rutabikersync.app.core.ridelifecycle

import java.time.Instant

/** Fase operativa de una rodada. READY es derivada, no una acción del usuario. */
enum class RidePhase {
    SCHEDULED,
    READY,
    OUTBOUND,
    AT_DESTINATION,
    RETURNING,
    COMPLETED
}

/** Intención de asistencia; no representa presencia física ni llegada a casa. */
enum class RideRsvpStatus(val wireValue: String) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    NO_GO("no_go")
}

/** Error portable de regla: la capa HTTP puede usar httpStatus sin duplicar lógica. */
enum class RideLifecycleError(val httpStatus: Int) {
    FORBIDDEN(403),
    INVALID_PHASE(409),
    INVALID_RSVP_TRANSITION(409),
    OUTSIDE_DECISION_WINDOW(409),
    START_GATE_NOT_MET(409),
    HOME_NOT_ALLOWED(409),
    HOME_TERMINAL(409),
    COMPLETION_GATE_NOT_MET(409)
}

class RideLifecycleException(val error: RideLifecycleError) :
    IllegalStateException(error.name)

/** Datos de participación independientes: intención, presencia y timestamps. */
data class RideParticipant(
    val userId: String,
    val rsvp: RideRsvpStatus = RideRsvpStatus.PENDING,
    val onSiteAt: Instant? = null,
    val enRouteAt: Instant? = null,
    val homeAt: Instant? = null
)

/** Estado inmutable de rodada; las reglas nunca consultan reloj, Android o red. */
data class RideLifecycleState(
    val hostUserId: String,
    val startsAt: Instant,
    val participants: List<RideParticipant>,
    val phase: RidePhase = RidePhase.SCHEDULED,
    val outboundStartedAt: Instant? = null,
    val destinationReachedAt: Instant? = null,
    val returnStartedAt: Instant? = null,
    val completedAt: Instant? = null
)

/** Snapshot portable para reconstruir el estado desde servidor o caché. */
data class RideLifecycleSnapshot(
    val hostUserId: String,
    val startsAt: String,
    val phase: RidePhase,
    val outboundStartedAt: String?,
    val destinationReachedAt: String?,
    val returnStartedAt: String?,
    val completedAt: String?,
    val participants: List<RideParticipantSnapshot>
)

data class RideParticipantSnapshot(
    val userId: String,
    val rsvp: RideRsvpStatus,
    val onSiteAt: String?,
    val enRouteAt: String?,
    val homeAt: String?
)

object RideLifecycle {
    private const val RECONSIDER_WINDOW_SECONDS = 3_600L

    fun effectivePhase(state: RideLifecycleState): RidePhase = when {
        state.phase == RidePhase.SCHEDULED && canStartRoute(state) -> RidePhase.READY
        state.phase == RidePhase.READY && !canStartRoute(state) -> RidePhase.SCHEDULED
        else -> state.phase
    }

    /** READY exige asistentes reales; pendientes/no_go cuentan como excluidos. */
    fun canStartRoute(state: RideLifecycleState): Boolean {
        val confirmed = state.participants.filter { it.rsvp == RideRsvpStatus.CONFIRMED }
        return confirmed.isNotEmpty() && confirmed.all { it.onSiteAt != null }
    }

    fun updateRsvp(
        state: RideLifecycleState,
        actorUserId: String,
        targetUserId: String,
        target: RideRsvpStatus,
        now: Instant
    ): RideLifecycleState {
        requireSelf(actorUserId, targetUserId)
        val participant = state.participant(targetUserId)
        if (participant.rsvp == target) return state // Reintento idempotente.
        requireRule(participant.homeAt == null, RideLifecycleError.HOME_TERMINAL)
        requirePreStart(state)
        requireRule(now.isBefore(state.startsAt), RideLifecycleError.OUTSIDE_DECISION_WINDOW)

        if (participant.rsvp == RideRsvpStatus.NO_GO && target == RideRsvpStatus.CONFIRMED) {
            val windowStart = state.startsAt.minusSeconds(RECONSIDER_WINDOW_SECONDS)
            requireRule(!now.isBefore(windowStart), RideLifecycleError.OUTSIDE_DECISION_WINDOW)
        }

        val updated = participant.copy(
            rsvp = target,
            onSiteAt = if (target == RideRsvpStatus.CONFIRMED) participant.onSiteAt else null
        )
        return state.replaceParticipant(updated)
    }

    fun markOnSite(
        state: RideLifecycleState,
        actorUserId: String,
        targetUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireSelf(actorUserId, targetUserId)
        val participant = state.participant(targetUserId)
        if (participant.onSiteAt != null) return state // No reiniciar llegada.
        requireRule(participant.homeAt == null, RideLifecycleError.HOME_TERMINAL)
        requirePreStart(state)
        requireRule(
            participant.rsvp == RideRsvpStatus.CONFIRMED,
            RideLifecycleError.INVALID_RSVP_TRANSITION
        )
        return state.replaceParticipant(participant.copy(onSiteAt = now))
    }

    fun startRide(
        state: RideLifecycleState,
        actorUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireHost(state, actorUserId)
        if (state.phase == RidePhase.OUTBOUND) return state // Reintento idempotente.
        requireRule(
            effectivePhase(state) == RidePhase.READY,
            RideLifecycleError.START_GATE_NOT_MET
        )
        return state.copy(
            phase = RidePhase.OUTBOUND,
            outboundStartedAt = now,
            participants = state.participants.map { participant ->
                if (participant.rsvp == RideRsvpStatus.CONFIRMED) {
                    participant.copy(enRouteAt = participant.enRouteAt ?: now)
                } else {
                    participant
                }
            }
        )
    }

    fun finishOutbound(
        state: RideLifecycleState,
        actorUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireHost(state, actorUserId)
        if (state.phase == RidePhase.AT_DESTINATION) return state
        requireRule(state.phase == RidePhase.OUTBOUND, RideLifecycleError.INVALID_PHASE)
        return state.copy(
            phase = RidePhase.AT_DESTINATION,
            destinationReachedAt = now
        )
    }

    fun startReturn(
        state: RideLifecycleState,
        actorUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireHost(state, actorUserId)
        if (state.phase == RidePhase.RETURNING) return state
        requireRule(state.phase == RidePhase.AT_DESTINATION, RideLifecycleError.INVALID_PHASE)
        requireRule(state.destinationReachedAt != null, RideLifecycleError.INVALID_PHASE)
        return state.copy(
            phase = RidePhase.RETURNING,
            returnStartedAt = now
        )
    }

    fun markHome(
        state: RideLifecycleState,
        actorUserId: String,
        targetUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireSelf(actorUserId, targetUserId)
        val participant = state.participant(targetUserId)
        if (participant.homeAt != null) return state // home es terminal e idempotente.
        requireRule(state.phase == RidePhase.RETURNING, RideLifecycleError.HOME_NOT_ALLOWED)
        requireRule(
            participant.rsvp == RideRsvpStatus.CONFIRMED && participant.enRouteAt != null,
            RideLifecycleError.HOME_NOT_ALLOWED
        )
        return state.replaceParticipant(participant.copy(homeAt = now))
    }

    fun completeRide(
        state: RideLifecycleState,
        actorUserId: String,
        now: Instant
    ): RideLifecycleState {
        requireHost(state, actorUserId)
        if (state.phase == RidePhase.COMPLETED) return state
        requireRule(state.phase == RidePhase.RETURNING, RideLifecycleError.INVALID_PHASE)
        val manifest = state.participants.filter { it.rsvp == RideRsvpStatus.CONFIRMED }
        requireRule(
            manifest.isNotEmpty() && manifest.all { it.homeAt != null },
            RideLifecycleError.COMPLETION_GATE_NOT_MET
        )
        return state.copy(
            phase = RidePhase.COMPLETED,
            completedAt = now
        )
    }

    fun snapshot(state: RideLifecycleState): RideLifecycleSnapshot = RideLifecycleSnapshot(
        hostUserId = state.hostUserId,
        startsAt = state.startsAt.toString(),
        phase = state.phase,
        outboundStartedAt = state.outboundStartedAt?.toString(),
        destinationReachedAt = state.destinationReachedAt?.toString(),
        returnStartedAt = state.returnStartedAt?.toString(),
        completedAt = state.completedAt?.toString(),
        participants = state.participants.map { participant ->
            RideParticipantSnapshot(
                userId = participant.userId,
                rsvp = participant.rsvp,
                onSiteAt = participant.onSiteAt?.toString(),
                enRouteAt = participant.enRouteAt?.toString(),
                homeAt = participant.homeAt?.toString()
            )
        }
    )

    /** Reconstrucción fiel: no recalcula decisiones, presencia ni timestamps. */
    fun restore(snapshot: RideLifecycleSnapshot): RideLifecycleState = RideLifecycleState(
        hostUserId = snapshot.hostUserId,
        startsAt = Instant.parse(snapshot.startsAt),
        participants = snapshot.participants.map { participant ->
            RideParticipant(
                userId = participant.userId,
                rsvp = participant.rsvp,
                onSiteAt = participant.onSiteAt?.let(Instant::parse),
                enRouteAt = participant.enRouteAt?.let(Instant::parse),
                homeAt = participant.homeAt?.let(Instant::parse)
            )
        },
        phase = snapshot.phase,
        outboundStartedAt = snapshot.outboundStartedAt?.let(Instant::parse),
        destinationReachedAt = snapshot.destinationReachedAt?.let(Instant::parse),
        returnStartedAt = snapshot.returnStartedAt?.let(Instant::parse),
        completedAt = snapshot.completedAt?.let(Instant::parse)
    )

    private fun RideLifecycleState.participant(userId: String): RideParticipant =
        participants.firstOrNull { it.userId == userId }
            ?: throw RideLifecycleException(RideLifecycleError.INVALID_RSVP_TRANSITION)

    private fun RideLifecycleState.replaceParticipant(
        updated: RideParticipant
    ): RideLifecycleState = copy(
        participants = participants.map { current ->
            if (current.userId == updated.userId) updated else current
        }
    )

    private fun requireSelf(actorUserId: String, targetUserId: String) {
        requireRule(actorUserId == targetUserId, RideLifecycleError.FORBIDDEN)
    }

    private fun requireHost(state: RideLifecycleState, actorUserId: String) {
        requireRule(actorUserId == state.hostUserId, RideLifecycleError.FORBIDDEN)
    }

    private fun requirePreStart(state: RideLifecycleState) {
        requireRule(
            state.phase == RidePhase.SCHEDULED || state.phase == RidePhase.READY,
            RideLifecycleError.INVALID_PHASE
        )
    }

    private fun requireRule(condition: Boolean, error: RideLifecycleError) {
        if (!condition) throw RideLifecycleException(error)
    }
}
