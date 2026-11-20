package com.rutabikersync.app.feature.rides

import com.rutabikersync.app.core.clubroutes.RideRosterParticipant
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class RideParticipantActionKind {
    DECISIONS,
    CONFIRM,
    ON_SITE,
    NONE
}

data class RideParticipantAction(
    val kind: RideParticipantActionKind,
    val label: String,
    val enabled: Boolean
)

data class RideStartGate(
    val enabled: Boolean,
    val confirmedCount: Int,
    val missingCount: Int,
    val blockedMessage: String
)

/** Reglas puras de botones por fila y puerta de inicio; sin dependencias de Compose. */
object RideParticipantsUi {
    fun initials(displayName: String): String = displayName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "RB" }

    fun canReconsider(startsAt: String?, now: Instant = Instant.now()): Boolean {
        val start = parseInstant(startsAt) ?: return false
        return !now.isBefore(start.minusSeconds(3600)) && now.isBefore(start)
    }

    fun rowAction(
        participant: RideRosterParticipant,
        currentUserId: String,
        startsAt: String?,
        now: Instant = Instant.now()
    ): RideParticipantAction {
        val isOwn = participant.userId == currentUserId
        if (participant.homeAt != null || participant.status == "home") {
            return RideParticipantAction(RideParticipantActionKind.NONE, "En casa ✓", false)
        }
        if (participant.onSiteAt != null) {
            return RideParticipantAction(RideParticipantActionKind.NONE, "En el sitio ✓", false)
        }
        return when (participant.status) {
            "pending" -> if (isOwn) {
                RideParticipantAction(RideParticipantActionKind.DECISIONS, "Pendiente", true)
            } else {
                RideParticipantAction(RideParticipantActionKind.NONE, "Pendiente", false)
            }
            "confirmed" -> if (isOwn) {
                RideParticipantAction(RideParticipantActionKind.ON_SITE, "En el sitio", true)
            } else {
                RideParticipantAction(RideParticipantActionKind.NONE, "Confirmado", false)
            }
            "no_go" -> if (isOwn && canReconsider(startsAt, now)) {
                RideParticipantAction(RideParticipantActionKind.CONFIRM, "Cambiar a asistiré", true)
            } else {
                RideParticipantAction(RideParticipantActionKind.NONE, "No asistiré ✗", false)
            }
            "en_route" -> RideParticipantAction(RideParticipantActionKind.NONE, "En ruta", false)
            else -> RideParticipantAction(RideParticipantActionKind.NONE, participant.status, false)
        }
    }

    fun startGate(participants: List<RideRosterParticipant>): RideStartGate {
        val confirmed = participants.filter { it.status == "confirmed" }
        val missing = confirmed.count { it.onSiteAt == null }
        val enabled = confirmed.isNotEmpty() && missing == 0
        val message = when {
            confirmed.isEmpty() -> "Aún no hay asistentes confirmados"
            missing > 0 -> "Faltan $missing asistentes por llegar"
            else -> "Todos los asistentes están en el sitio"
        }
        return RideStartGate(enabled, confirmed.size, missing, message)
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }
}
