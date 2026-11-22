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
 * - **Detección de islas (Decisión 3.4 C):** cuando 2+ peers están fuera de contacto simultáneamente,
 *   se activa el estado de isla en UI/notificación inmediatamente, pero **solo se anuncia por TTS si la isla
 *   dura más de 60 segundos** (`ISLAND_TTS_THRESHOLD_MS = 60_000L`) para evitar ruido en estiramientos momentáneos del convoy.
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
        val lostPeersCount: Int = 0,
        val isIslandActive: Boolean = false,
        val islandStartedAtEpochMs: Long? = null,
        val islandDurationMs: Long = 0L,
        val islandTtsAnnouncedForCurrentEpisode: Boolean = false,
        val lastIslandTtsMessage: String? = null
    )

    private var islandStartedAtMs: Long? = null
    private var islandAnnouncedInEpisode: Boolean = false
    private val _islandTtsHistory = mutableListOf<String>()

    val islandTtsHistory: List<String>
        @Synchronized get() = _islandTtsHistory.toList()

    private val _state = MutableStateFlow(HealthSnapshot())
    val state: StateFlow<HealthSnapshot> = _state.asStateFlow()

    @Synchronized
    fun evaluateHealth(nowEpochMs: Long = System.currentTimeMillis()): HealthSnapshot {
        engine.evaluatePeerTimeouts(nowEpochMs)
        val peers = engine.peers.value
        val total = peers.size
        val lostCount = peers.values.count { it.isLost }
        val onlineCount = peers.values.count { it.receivedBeats > 0 && !it.isLost }

        // Una isla existe cuando se pierden 2 o más peers a la vez (o >= 50% de un grupo de >= 2 peers)
        val isIslandNow = total >= 2 && (lostCount >= 2 || lostCount * 2 >= total)

        if (!isIslandNow) {
            islandStartedAtMs = null
            islandAnnouncedInEpisode = false
            val snapshot = HealthSnapshot(
                onlinePeersCount = onlineCount,
                lostPeersCount = lostCount,
                isIslandActive = false,
                islandStartedAtEpochMs = null,
                islandDurationMs = 0L,
                islandTtsAnnouncedForCurrentEpisode = false,
                lastIslandTtsMessage = _islandTtsHistory.lastOrNull()
            )
            _state.value = snapshot
            return snapshot
        }

        val startMs = islandStartedAtMs ?: nowEpochMs.also { islandStartedAtMs = it }
        val durationMs = (nowEpochMs - startMs).coerceAtLeast(0L)

        // Decisión 3.4 (C): Solo anunciar por TTS si la isla dura más de 60 s (> 60_000 ms)
        if (durationMs > ISLAND_TTS_THRESHOLD_MS && !islandAnnouncedInEpisode) {
            islandAnnouncedInEpisode = true
            val ttsMsg = "Mesh parcial: $lostCount nodos fuera de contacto"
            _islandTtsHistory.add(ttsMsg)
            onIslandTtsAnnouncement?.invoke(ttsMsg)
        }

        val snapshot = HealthSnapshot(
            onlinePeersCount = onlineCount,
            lostPeersCount = lostCount,
            isIslandActive = true,
            islandStartedAtEpochMs = startMs,
            islandDurationMs = durationMs,
            islandTtsAnnouncedForCurrentEpisode = islandAnnouncedInEpisode,
            lastIslandTtsMessage = _islandTtsHistory.lastOrNull()
        )
        _state.value = snapshot
        return snapshot
    }

    @Synchronized
    fun reset() {
        islandStartedAtMs = null
        islandAnnouncedInEpisode = false
        _state.value = HealthSnapshot()
    }
}
