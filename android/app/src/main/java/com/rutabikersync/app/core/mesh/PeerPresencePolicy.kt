package com.rutabikersync.app.core.mesh

/**
 * Interpretación única del estado de un peer. Antes de este policy cada vista leía
 * `PeerTrack.isLost` a su manera: el HUD aplicaba la gracia, el mapa y la notificación no.
 *
 * Los tres estados son derivados: no añaden campos al protocolo ni cambian los umbrales
 * existentes. `isLost` sigue siendo el estado de transporte crudo (pérdida *sospechada* en
 * cuanto vence el timeout recalibrado); la pérdida *confirmada* llega al agotar la gracia.
 */
enum class PeerPresenceState {
    /** Heartbeats dentro del timeout: conectado. */
    ACTIVE,
    /** Timeout vencido pero dentro de la gracia: conectividad degradada, no desconexión. */
    STALE_GRACE,
    /** Gracia agotada: desconexión confirmada. */
    LOST
}

object PeerPresencePolicy {
    /**
     * Estado de presentación de un peer. La gracia se deriva del timeout efectivo que ya
     * calculó el motor; no se introducen temporizadores nuevos.
     */
    fun stateFor(track: MeshRelayEngine.PeerTrack, nowEpochMs: Long): PeerPresenceState {
        if (!track.isLost) return PeerPresenceState.ACTIVE
        val graceMs = PeerGracePolicy.graceMsFor(track.effectiveTimeoutMs)
        return if (PeerGracePolicy.isInGrace(nowEpochMs, track.lostSinceEpochMs, graceMs)) {
            PeerPresenceState.STALE_GRACE
        } else {
            PeerPresenceState.LOST
        }
    }

    /** Criterio para anuncios TTS, islas y eventos de pérdida: nunca una pérdida sospechada. */
    fun isConfirmedLost(state: PeerPresenceState): Boolean = state == PeerPresenceState.LOST

    /** Criterio para HUD y notificación: degradado cuenta como presente, confirmado no. */
    fun countsAsConnected(state: PeerPresenceState): Boolean = state != PeerPresenceState.LOST

    /**
     * Sufijo de la notificación de rodada. Centralizado para que el texto de "sin señal
     * temporal" y "sin señal" sea el mismo que consume el HUD y el mapa.
     */
    fun notificationSuffix(staleRiders: Int, lostRiders: Int): String = buildString {
        if (staleRiders > 0) append(" · $staleRiders sin señal temporal")
        if (lostRiders > 0) append(" · $lostRiders sin señal")
    }
}
