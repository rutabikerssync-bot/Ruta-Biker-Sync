package com.rutabikersync.app.feature.livemap

/**
 * Gracia visual de 60 s para un peer que acaba de perder señal.
 *
 * El motor declara `isLost` con su timeout calibrado y la notificación persistente sigue siendo
 * la verdad de malla (`0 riders conectados · 1 sin señal`). Esta clase solo decide si el marcador
 * del mapa se retira inmediatamente del contador del HUD: durante la gracia el peer se muestra
 * con estado [RiderRouteStatus.SIGNAL_GRACE], sigue contando como rider y su posición retenida
 * se conserva; transcurridos 60 s sin señal pasa a [RiderRouteStatus.OFF_MESH_LOST] y deja de contar.
 *
 * No relaja la detección de malla, no toca GATT/crypto/`HeartbeatFrame`/PTT y es pura y determinista.
 */
class PeerSignalGraceTracker(
    private val graceMs: Long = GRACE_MS
) {
    companion object {
        const val GRACE_MS: Long = 60_000L
    }

    private val startedAtByShortId = mutableMapOf<UShort, Long>()

    /**
     * Devuelve `true` mientras el peer recién perdido debe seguir contando en el HUD.
     * Un peer que recupera señal limpia su gracia; un peer en gracia que vuelve a caer no reinicia
     * el contador (evita gracia infinita por reenganches intermitentes).
     */
    fun isInGrace(shortId: UShort, isLost: Boolean, nowEpochMs: Long): Boolean {
        if (!isLost) {
            startedAtByShortId.remove(shortId)
            return false
        }
        val startedAt = startedAtByShortId.getOrPut(shortId) { nowEpochMs }
        return (nowEpochMs - startedAt) < graceMs
    }

    fun clear() {
        startedAtByShortId.clear()
    }
}
