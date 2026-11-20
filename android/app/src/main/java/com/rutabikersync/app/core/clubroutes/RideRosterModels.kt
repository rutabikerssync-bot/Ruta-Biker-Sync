package com.rutabikersync.app.core.clubroutes

/** Participante real de una rodada (datos del roster backend, no fixtures UI). */
data class RideRosterParticipant(
    val userId: String,
    val displayName: String,
    val clubRole: String?,
    val isLeader: Boolean,
    val status: String,
    val onSiteAt: String?,
    val homeAt: String?,
    val respondedAt: String?
)

/** Roster completo con fase operativa persistida por el backend. */
data class RideRoster(
    val routeId: String,
    val startsAt: String?,
    val outboundStartedAt: String?,
    val destinationReachedAt: String?,
    val returnStartedAt: String?,
    val completedAt: String?,
    val participants: List<RideRosterParticipant>
)
