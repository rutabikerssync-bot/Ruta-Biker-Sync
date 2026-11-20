package com.rutabikersync.app.feature.groups

import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Lógica pura de la UI de RSVP de rutas de club (Fase 5 · Hito 3).
 *
 * Sin dependencias de Compose ni de Android: así el cableado del banner y las
 * reglas de estado se verifican con tests JVM, igual que el outbox.
 */
object ClubRsvpUi {

    const val STATUS_CONFIRMED = "confirmed"
    const val STATUS_HOME = "home"
    const val STATUS_NO_GO = "no_go"

    /** Los tres estados que el backend acepta en POST /v1/routes/:id/rsvp. */
    val rsvpStatuses: List<String> = listOf(STATUS_CONFIRMED, STATUS_HOME, STATUS_NO_GO)

    fun statusLabel(status: String): String = when (status) {
        STATUS_CONFIRMED -> "Asistiré"
        STATUS_HOME -> "En casa"
        STATUS_NO_GO -> "No iré"
        else -> status.replace('_', ' ').trim().ifEmpty { "Sin responder" }
    }

    fun isAnswered(status: String?): Boolean = !status.isNullOrBlank()

    /**
     * Mensaje efectivo del banner de club. Un RSVP pendiente tiene prioridad
     * sobre el health: es lo que el piloto debe ver justo al volver la red.
     */
    fun bannerMessage(statusBanner: String?, rsvpPendingCount: Int): String? =
        if (rsvpPendingCount > 0) "⏳ Pendiente de sincronizar ($rsvpPendingCount RSVP)"
        else statusBanner

    /** El banner pendiente se pinta en ámbar (advertencia), no en verde. */
    fun isPendingBanner(message: String?): Boolean =
        message?.startsWith("⏳ Pendiente de sincronizar") == true

    /**
     * Última intención gana, la misma regla del outbox. Tras un RSVP local la
     * fila se pinta de inmediato aunque la red esté caída.
     */
    fun resolveRouteStatus(current: String?, requested: String?): String? =
        requested ?: current

    /** Subtítulo de la fila: hora de inicio y respuesta local, si la hay. */
    fun routeSubtitle(startsAt: String?, status: String?): String {
        val parts = mutableListOf<String>()
        startsAt?.takeIf { it.isNotBlank() }?.let { parts += "Inicio: $it" }
        if (isAnswered(status)) parts += statusLabel(status!!)
        return parts.joinToString(" · ").ifEmpty { "Sin respuesta" }
    }

    fun parseRouteInstant(startsAt: String?): Instant? {
        if (startsAt.isNullOrBlank()) return null
        return try {
            Instant.parse(startsAt)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(startsAt).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Próxima rodada publicada y futura (D4): publicada, con startsAt parseable
     * y posterior a [now], ordenada por startsAt ascendente.
     */
    fun nextRoute(
        routes: List<ClubRouteEntity>,
        now: Instant = Instant.now()
    ): ClubRouteEntity? = routes
        .asSequence()
        .filter { it.status == "published" }
        .mapNotNull { route ->
            val start = parseRouteInstant(route.startsAt) ?: return@mapNotNull null
            if (start.isAfter(now)) route to start else null
        }
        .minByOrNull { it.second }
        ?.first

    fun formatRouteDateTime(
        startsAt: String?,
        full: Boolean = true,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val instant = parseRouteInstant(startsAt) ?: return startsAt?.takeIf { it.isNotBlank() } ?: "Sin hora"
        val pattern = if (full) "EEE dd MMM yyyy, HH:mm" else "dd MMM, HH:mm"
        return try {
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zoneId).format(instant)
        } catch (_: Exception) {
            startsAt ?: "Sin hora"
        }
    }

    fun descriptionSnippet(description: String?, maxChars: Int = 90): String? {
        val text = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }
}
