package com.rutabikersync.app.core.announcer

import com.rutabikersync.app.core.mesh.MeshRelayEngine

/**
 * Tipo de evento de anunciador único (DESIGN.md §11).
 */
enum class AnnouncerEventKind(val wireCode: Int) {
    PEER_LOST(1),
    PEER_BACK(2),
    UNSTABLE_CONNECTION(3);

    companion object {
        fun fromWireCode(code: Int): AnnouncerEventKind =
            entries.firstOrNull { it.wireCode == code } ?: PEER_LOST
    }
}

/**
 * Transición detectada en el estado de conectividad de un rider para alimentar a `UniqueAnnouncer`.
 */
data class PeerConnectivityTransition(
    val subjectShortId: UShort,
    val kind: AnnouncerEventKind,
    val eventCycle: Int,
    val eventId: String,
    val proximityRank: Int,
    val lossCountInWindow: Int
)

/**
 * Seguimiento por rider para el Anunciador Único (`DESIGN.md` §11):
 * - Guarda última posición conocida y último salto (`lastHops`) para calcular la cercanía reciente
 *   (`proximityRank`: 0 = contacto directo de 1 salto, 1 = 2 saltos, etc.).
 * - Registra cada pérdida en una ventana deslizante de 10 minutos (`UNSTABLE_WINDOW_MS = 600_000L`).
 * - Decisión 3.4 (C): Solo cuando un rider se pierde 3 veces en 10 minutos (`UNSTABLE_LOSS_THRESHOLD = 3`),
 *   agrupa el evento como `UNSTABLE_CONNECTION` ("X tiene conexión inestable") y evita repetirlo en esa ventana.
 */
class AnnouncerTracker {
    companion object {
        const val UNSTABLE_WINDOW_MS: Long = 10 * 60 * 1_000L // 10 minutos (DESIGN.md §11)
        const val UNSTABLE_LOSS_THRESHOLD: Int = 3           // 3 pérdidas en 10 min -> "conexión inestable"

        fun buildEventId(
            kind: AnnouncerEventKind,
            subjectShortId: UShort,
            eventCycle: Int
        ): String = "EVT:${kind.wireCode}:${subjectShortId.toInt()}:$eventCycle"
    }

    data class RiderTrackState(
        val shortId: UShort,
        val lastLatitude: Double,
        val lastLongitude: Double,
        val lastSeenEpochMs: Long,
        val lastHops: Int,
        val isLost: Boolean,
        val lossCycle: Int,
        val reconnectCycle: Int,
        val lossTimestampsEpochMs: List<Long>,
        val unstableAnnouncedInCurrentWindow: Boolean
    )

    private val riderStates = mutableMapOf<UShort, RiderTrackState>()

    @Synchronized
    fun getRiderState(shortId: UShort): RiderTrackState? = riderStates[shortId]

    @Synchronized
    fun reset() {
        riderStates.clear()
    }

    /**
     * Compara el mapa de peers actual de `MeshRelayEngine` contra el estado previo y devuelve
     * las transiciones `PEER_LOST`, `UNSTABLE_CONNECTION` o `PEER_BACK` recién ocurridas.
     */
    @Synchronized
    fun onPeerMapUpdated(
        peerMap: Map<UShort, MeshRelayEngine.PeerTrack>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<PeerConnectivityTransition> {
        val transitions = mutableListOf<PeerConnectivityTransition>()

        for ((shortId, track) in peerMap) {
            val prev = riderStates[shortId]
            val proximityRank = (track.lastHops - 1).coerceAtLeast(0)

            if (prev == null) {
                val initialLossList = if (track.isLost) listOf(nowEpochMs) else emptyList()
                val cycle = if (track.isLost) 1 else 0
                riderStates[shortId] = RiderTrackState(
                    shortId = shortId,
                    lastLatitude = track.lastLatitude,
                    lastLongitude = track.lastLongitude,
                    lastSeenEpochMs = track.lastSeenEpochMs,
                    lastHops = track.lastHops,
                    isLost = track.isLost,
                    lossCycle = cycle,
                    reconnectCycle = 0,
                    lossTimestampsEpochMs = initialLossList,
                    unstableAnnouncedInCurrentWindow = false
                )
                if (track.isLost) {
                    val eventId = buildEventId(AnnouncerEventKind.PEER_LOST, shortId, cycle)
                    transitions.add(
                        PeerConnectivityTransition(
                            subjectShortId = shortId,
                            kind = AnnouncerEventKind.PEER_LOST,
                            eventCycle = cycle,
                            eventId = eventId,
                            proximityRank = proximityRank,
                            lossCountInWindow = 1
                        )
                    )
                }
                continue
            }

            // Caso 1: Estaba conectado y pasa a perdido -> PEER_LOST (o UNSTABLE_CONNECTION si llega a 3 en 10 min)
            if (!prev.isLost && track.isLost) {
                val prunedLosses = prev.lossTimestampsEpochMs
                    .filter { nowEpochMs - it <= UNSTABLE_WINDOW_MS } + nowEpochMs
                val nextLossCycle = prev.lossCycle + 1
                val lossCount = prunedLosses.size

                if (lossCount >= UNSTABLE_LOSS_THRESHOLD) {
                    // Decisión 3.4 (C): Al llegar a 3 pérdidas en 10 min se anuncia una vez "conexión inestable"
                    val shouldEmitUnstable = !prev.unstableAnnouncedInCurrentWindow
                    riderStates[shortId] = prev.copy(
                        lastLatitude = track.lastLatitude,
                        lastLongitude = track.lastLongitude,
                        lastSeenEpochMs = track.lastSeenEpochMs,
                        lastHops = track.lastHops,
                        isLost = true,
                        lossCycle = nextLossCycle,
                        lossTimestampsEpochMs = prunedLosses,
                        unstableAnnouncedInCurrentWindow = true
                    )
                    if (shouldEmitUnstable) {
                        val eventId = buildEventId(AnnouncerEventKind.UNSTABLE_CONNECTION, shortId, nextLossCycle)
                        transitions.add(
                            PeerConnectivityTransition(
                                subjectShortId = shortId,
                                kind = AnnouncerEventKind.UNSTABLE_CONNECTION,
                                eventCycle = nextLossCycle,
                                eventId = eventId,
                                proximityRank = proximityRank,
                                lossCountInWindow = lossCount
                            )
                        )
                    }
                } else {
                    riderStates[shortId] = prev.copy(
                        lastLatitude = track.lastLatitude,
                        lastLongitude = track.lastLongitude,
                        lastSeenEpochMs = track.lastSeenEpochMs,
                        lastHops = track.lastHops,
                        isLost = true,
                        lossCycle = nextLossCycle,
                        lossTimestampsEpochMs = prunedLosses
                    )
                    val eventId = buildEventId(AnnouncerEventKind.PEER_LOST, shortId, nextLossCycle)
                    transitions.add(
                        PeerConnectivityTransition(
                            subjectShortId = shortId,
                            kind = AnnouncerEventKind.PEER_LOST,
                            eventCycle = nextLossCycle,
                            eventId = eventId,
                            proximityRank = proximityRank,
                            lossCountInWindow = lossCount
                        )
                    )
                }
                continue
            }

            // Caso 2: Estaba perdido y vuelve a estar conectado -> PEER_BACK
            if (prev.isLost && !track.isLost) {
                val prunedLosses = prev.lossTimestampsEpochMs.filter { nowEpochMs - it <= UNSTABLE_WINDOW_MS }
                val nextReconnectCycle = prev.reconnectCycle + 1
                val resetUnstableFlag = prunedLosses.size < UNSTABLE_LOSS_THRESHOLD
                riderStates[shortId] = prev.copy(
                    lastLatitude = track.lastLatitude,
                    lastLongitude = track.lastLongitude,
                    lastSeenEpochMs = track.lastSeenEpochMs,
                    lastHops = track.lastHops,
                    isLost = false,
                    reconnectCycle = nextReconnectCycle,
                    lossTimestampsEpochMs = prunedLosses,
                    unstableAnnouncedInCurrentWindow = if (resetUnstableFlag) false else prev.unstableAnnouncedInCurrentWindow
                )
                // Si no está silenciado por conexión inestable repetida, anuncia reconexión
                if (!prev.unstableAnnouncedInCurrentWindow) {
                    val eventId = buildEventId(AnnouncerEventKind.PEER_BACK, shortId, nextReconnectCycle)
                    transitions.add(
                        PeerConnectivityTransition(
                            subjectShortId = shortId,
                            kind = AnnouncerEventKind.PEER_BACK,
                            eventCycle = nextReconnectCycle,
                            eventId = eventId,
                            proximityRank = proximityRank,
                            lossCountInWindow = prunedLosses.size
                        )
                    )
                }
                continue
            }

            // Caso 3: Sin cambio en `isLost`, actualizar posición y saltos recientes
            riderStates[shortId] = prev.copy(
                lastLatitude = track.lastLatitude,
                lastLongitude = track.lastLongitude,
                lastSeenEpochMs = track.lastSeenEpochMs,
                lastHops = track.lastHops
            )
        }

        return transitions
    }
}
