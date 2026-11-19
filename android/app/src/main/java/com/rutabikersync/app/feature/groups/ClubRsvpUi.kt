package com.rutabikersync.app.feature.groups

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
}
