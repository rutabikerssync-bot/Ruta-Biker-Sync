package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitor de salud de peers, histéresis de reconexión (2 latidos consecutivos) y detección de islas
 * (Bloque 3.5, DESIGN.md §3 y §11, Decisión D56):
 * - **Histéresis:** un peer marcado `LOST` (tras `MeshRelayEngine.LOST_TIMEOUT_MS` = 40,5 s, derivado del
 *   intervalo de llegada medido en campo) requiere 2 latidos consecutivos
 *   (`RECONNECT_HYSTERESIS_BEATS = 2`) para volver a `ONLINE`.
 * - **Pérdida confirmada:** `isLost` del motor es pérdida *sospechada*. El monitor solo declara
 *   pérdida y active la isla cuando se agota la gracia (`PeerPresencePolicy`, sin cambiar umbrales).
 * - **Detección de islas (Decisión 3.4 C):** cuando 2+ peers están fuera de contacto simultáneamente,
 *   el estado de isla y el primer aviso TTS son inmediatos. Los 60 s
 *   (`ISLAND_TTS_THRESHOLD_MS`) gobiernan únicamente un posible **segundo** aviso, para no saturar.
 */
class PeerHealthMonitor(
    private val engine: MeshRelayEngine,
    private val onIslandTtsAnnouncement: ((String) -> Unit)? = null
) {
    companion object {
        const val ISLAND_TTS_THRESHOLD_MS: Long = 60_000L
        const val RECONNECT_HYSTERESIS_BEATS: Int = MeshRelayEngine.RECONNECT_HYSTERESIS_BEATS
    }

    data class HealthSnapshot(
        val onlinePeersCount: Int = 0,
        /** Pérdida sospechada (isLost crudo del motor), visible para servicios internos. */
        val suspectedLostPeersCount: Int = 0,
        /** Pérdida confirmada (gracia agotada): único criterio para isla y anuncios. */
        val lostPeersCount: Int = 0,
        val isIslandActive: Boolean = false,
        val islandStartedAtEpochMs: Long? = null,
        val islandDurationMs: Long = 0L,
        val islandTtsAnnouncedForCurrentEpisode: Boolean = false,
        val islandSecondTtsAnnouncedForCurrentEpisode: Boolean = false,
        val lastIslandTtsMessage: String? = null
    )

    private var islandStartedAtMs: Long? = null
    private var islandAnnouncedInEpisode = false
    private var islandSecondAnnouncedInEpisode = false
    private val _islandTtsHistory = mutableListOf<String>()

    val islandTtsHistory: List<String>
        @Synchronized get() = _islandTtsHistory.toList()

    private val _state = MutableStateFlow(HealthSnapshot())
    val state: StateFlow<HealthSnapshot> = _state.asStateFlow()

    @Synchronized
    fun evaluateHealth(nowEpochMs: Long = System.currentTimeMillis()): HealthSnapshot {
        engine.evaluatePeerTimeouts(nowEpochMs)
        val peers = engine.peers.value
        val presence = peers.mapValues { (_, track) -> PeerPresencePolicy.stateFor(track, nowEpochMs) }
        val suspectedLostCount = peers.values.count { it.isLost }
        val lostCount = presence.values.count(PeerPresencePolicy::isConfirmedLost)
        val onlineCount = peers.count { (id, track) ->
            track.receivedBeats > 0 && !PeerPresencePolicy.isConfirmedLost(presence.getValue(id))
        }

        // Una isla existe cuando se pierden 2 o más peers de forma confirmada a la vez
        // (o >= 50% de un grupo de >= 2 peers). La pérdida sospechada no basta.
        val isIslandNow = peers.size >= 2 && (lostCount >= 2 || lostCount * 2 >= peers.size)

        if (!isIslandNow) {
            islandStartedAtMs = null
            islandAnnouncedInEpisode = false
            islandSecondAnnouncedInEpisode = false
            val snapshot = HealthSnapshot(
                onlinePeersCount = onlineCount,
                suspectedLostPeersCount = suspectedLostCount,
                lostPeersCount = lostCount,
                isIslandActive = false,
                islandStartedAtEpochMs = null,
                islandDurationMs = 0L,
                islandTtsAnnouncedForCurrentEpisode = false,
                islandSecondTtsAnnouncedForCurrentEpisode = false,
                lastIslandTtsMessage = _islandTtsHistory.lastOrNull()
            )
            _state.value = snapshot
            return snapshot
        }

        val startMs = islandStartedAtMs ?: nowEpochMs.also { islandStartedAtMs = it }
        val durationMs = (nowEpochMs - startMs).coerceAtLeast(0L)

        // Primer aviso inmediato sobre pérdida confirmada. Los 60 s solo habilitan el segundo.
        var lastMessage = _islandTtsHistory.lastOrNull()
        if (!islandAnnouncedInEpisode) {
            islandAnnouncedInEpisode = true
            val ttsMsg = "Mesh parcial: $lostCount nodos fuera de contacto"
            _islandTtsHistory.add(ttsMsg)
            onIslandTtsAnnouncement?.invoke(ttsMsg)
            lastMessage = ttsMsg
        } else if (!islandSecondAnnouncedInEpisode && durationMs > ISLAND_TTS_THRESHOLD_MS) {
            islandSecondAnnouncedInEpisode = true
            val ttsMsg = "Mesh parcial: $lostCount nodos siguen fuera de contacto"
            _islandTtsHistory.add(ttsMsg)
            onIslandTtsAnnouncement?.invoke(ttsMsg)
            lastMessage = ttsMsg
        }

        val snapshot = HealthSnapshot(
            onlinePeersCount = onlineCount,
            suspectedLostPeersCount = suspectedLostCount,
            lostPeersCount = lostCount,
            isIslandActive = true,
            islandStartedAtEpochMs = startMs,
            islandDurationMs = durationMs,
            islandTtsAnnouncedForCurrentEpisode = islandAnnouncedInEpisode,
            islandSecondTtsAnnouncedForCurrentEpisode = islandSecondAnnouncedInEpisode,
            lastIslandTtsMessage = lastMessage
        )
        _state.value = snapshot
        return snapshot
    }

    @Synchronized
    fun reset() {
        islandStartedAtMs = null
        islandAnnouncedInEpisode = false
        islandSecondAnnouncedInEpisode = false
        _state.value = HealthSnapshot()
    }
}
