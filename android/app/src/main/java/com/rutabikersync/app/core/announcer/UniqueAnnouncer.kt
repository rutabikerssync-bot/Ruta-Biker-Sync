package com.rutabikersync.app.core.announcer

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.feature.livemap.LiveMapController
import com.rutabikersync.app.feature.livemap.RiderDirectoryEntry
import com.rutabikersync.app.feature.livemap.RiderRelation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Implementación del Algoritmo de Anunciador Único (`DESIGN.md` §11):
 *
 * ```
 * al detectar PEER_LOST(x, eventId):
 *     retardo = aleatorio(0, 1.0 s) + 0.5 s × posición_por_cercanía_reciente
 *     retardo = min(retardo, 3.0 s)
 *     esperar retardo
 *     si ya recibí ANNOUNCED(eventId): callar y terminar
 *     emitir ANNOUNCED(eventId, mi_id)     # prioridad alta (PEER_EVENT)
 *     decir por TTS
 *     contador[eventId] = 1
 *     si x sigue perdido a los 60 s y contador < 2: repetir una sola vez
 *
 * al recibir ANNOUNCED(eventId, otro): cancelar mi anuncio pendiente
 * al recibir PEER_BACK(x): cancelar anuncios pendientes y decir "x se reconectó" (también con un único anunciador)
 * ```
 *
 * Reglas adicionales de `DESIGN.md` §11:
 * - Si dos nodos anuncian a la vez, gana el de menor `shortId` y el otro corta su TTS en cuanto lo detecta.
 * - Máximo 2 anuncios por evento; si se pierde el mismo rider 3 veces en 10 min, se agrupa en `"X tiene conexión inestable"`.
 * - En partición por islas, cada isla elige su propio anunciador y al reunirse se unifican los eventos por `eventId`.
 */
class UniqueAnnouncer(
    private val scope: CoroutineScope,
    val localShortId: UShort,
    private val meshEngine: MeshRelayEngine,
    val tracker: AnnouncerTracker = AnnouncerTracker(),
    val ttsAnnouncer: TtsAnnouncer = TtsAnnouncer(),
    directoryEntries: List<RiderDirectoryEntry> = LiveMapController.defaultConvoyDirectory(),
    private val randomFractionProvider: (UShort, String) -> Double = { _, _ -> Random.nextDouble(0.0, 1.0) }
) {
    companion object {
        const val RANDOM_JITTER_MAX_MS: Long = 1_000L
        const val PROXIMITY_STEP_MS: Long = 500L
        const val MAX_INITIAL_DELAY_MS: Long = 3_000L
        const val REPEAT_REMINDER_DELAY_MS: Long = 60_000L
        const val MAX_ANNOUNCEMENTS_PER_EVENT: Int = 2

        fun computeDelayMs(proximityRank: Int, randomZeroToOne: Double): Long {
            val jitterMs = (randomZeroToOne.coerceIn(0.0, 1.0) * RANDOM_JITTER_MAX_MS.toDouble()).roundToLong()
            val rankPenaltyMs = PROXIMITY_STEP_MS * proximityRank.coerceAtLeast(0).toLong()
            return (jitterMs + rankPenaltyMs).coerceAtMost(MAX_INITIAL_DELAY_MS)
        }
    }

    data class AnnouncerOverlayBanner(
        val eventId: String,
        val subjectShortId: UShort,
        val kind: AnnouncerEventKind,
        val message: String,
        val announcedByShortId: UShort
    )

    private val directory = directoryEntries.associateBy { it.shortId }.toMutableMap()
    private val pendingJobs = mutableMapOf<String, Job>()
    private val reminderJobs = mutableMapOf<String, Job>()
    private val pendingLossEventIdsBySubject = mutableMapOf<UShort, MutableSet<String>>()
    private val announcedWinners = mutableMapOf<String, UShort>()
    private val announcementCounts = mutableMapOf<String, Int>()

    private var peersCollectJob: Job? = null
    private var signalsCollectJob: Job? = null

    @Volatile
    private var isActiveSession: Boolean = false

    private val _activeBanner = MutableStateFlow<AnnouncerOverlayBanner?>(null)
    val activeBanner: StateFlow<AnnouncerOverlayBanner?> = _activeBanner.asStateFlow()

    fun start() {
        if (isActiveSession) return
        isActiveSession = true

        peersCollectJob?.cancel()
        peersCollectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            meshEngine.peers.collect { peerMap ->
                if (!isActiveSession) return@collect
                val transitions = tracker.onPeerMapUpdated(peerMap, System.currentTimeMillis())
                for (transition in transitions) {
                    scheduleOrHandleTransition(transition)
                }
            }
        }

        signalsCollectJob?.cancel()
        signalsCollectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            meshEngine.announcedSignals.collect { signal ->
                if (!isActiveSession) return@collect
                onRemoteAnnouncedReceived(signal)
            }
        }
    }

    /**
     * Evalúa manualmente el mapa de peers con un timestamp explícito (útil en pruebas deterministas de ventana de 10 min).
     */
    fun processPeerSnapshotAt(
        peerMap: Map<UShort, MeshRelayEngine.PeerTrack>,
        nowEpochMs: Long
    ): List<PeerConnectivityTransition> {
        if (!isActiveSession) return emptyList()
        val transitions = tracker.onPeerMapUpdated(peerMap, nowEpochMs)
        for (transition in transitions) {
            scheduleOrHandleTransition(transition)
        }
        return transitions
    }

    fun scheduleOrHandleTransition(transition: PeerConnectivityTransition) {
        if (!isActiveSession) return

        // Si el rider se reconectó (PEER_BACK), cancelar cualquier anuncio o recordatorio pendiente de pérdida
        if (transition.kind == AnnouncerEventKind.PEER_BACK) {
            cancelPendingLossesForSubject(transition.subjectShortId)
        }

        synchronized(this) {
            if (announcedWinners.containsKey(transition.eventId) || pendingJobs.containsKey(transition.eventId)) {
                return
            }
            if (transition.kind == AnnouncerEventKind.PEER_LOST ||
                transition.kind == AnnouncerEventKind.UNSTABLE_CONNECTION
            ) {
                pendingLossEventIdsBySubject
                    .getOrPut(transition.subjectShortId) { mutableSetOf() }
                    .add(transition.eventId)
            }
        }

        val randomFraction = randomFractionProvider(localShortId, transition.eventId)
        val delayMs = computeDelayMs(transition.proximityRank, randomFraction)

        val job = scope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            executeAnnouncementIfStillUnclaimed(transition)
        }

        synchronized(this) {
            pendingJobs[transition.eventId] = job
        }
    }

    private suspend fun executeAnnouncementIfStillUnclaimed(transition: PeerConnectivityTransition) {
        if (!isActiveSession) return

        val entry = directory[transition.subjectShortId]
        val fullName = entry?.fullName ?: "Rider #${transition.subjectShortId.toInt()}"
        val relation = entry?.relation ?: RiderRelation.ALLIED_STRANGER

        synchronized(this) {
            pendingJobs.remove(transition.eventId)
            if (announcedWinners.containsKey(transition.eventId)) {
                return
            }
            // Si era una pérdida pero el rider ya regresó mientras esperábamos el retardo, callar y terminar
            if ((transition.kind == AnnouncerEventKind.PEER_LOST || transition.kind == AnnouncerEventKind.UNSTABLE_CONNECTION) &&
                tracker.getRiderState(transition.subjectShortId)?.isLost == false
            ) {
                return
            }

            announcedWinners[transition.eventId] = localShortId
            announcementCounts[transition.eventId] = 1
        }

        // 1. Emitir ANNOUNCED(eventId, mi_id) con prioridad alta (PEER_EVENT)
        meshEngine.emitAnnouncedSignal(
            subjectShortId = transition.subjectShortId,
            eventKindCode = transition.kind.wireCode,
            eventCycle = transition.eventCycle
        )

        // 2. Pronunciar por TTS
        val utterance = ttsAnnouncer.speakAnnouncement(
            eventId = transition.eventId,
            fullName = fullName,
            relation = relation,
            shortId = transition.subjectShortId,
            kind = transition.kind
        )

        val spokenText = utterance?.text ?: TtsAnnouncer.formatAnnouncementMessage(
            spokenRiderName = TtsAnnouncer.formatSpokenRiderName(fullName, relation, transition.subjectShortId),
            kind = transition.kind
        )
        _activeBanner.value = AnnouncerOverlayBanner(
            eventId = transition.eventId,
            subjectShortId = transition.subjectShortId,
            kind = transition.kind,
            message = spokenText,
            announcedByShortId = localShortId
        )

        // 3. Si es PEER_LOST, programar el único recordatorio a los 60 s (máximo 2 anuncios por evento)
        if (transition.kind == AnnouncerEventKind.PEER_LOST) {
            val reminderJob = scope.launch {
                delay(REPEAT_REMINDER_DELAY_MS)
                repeatLossAnnouncementAt60sIfStillLost(transition, fullName, relation)
            }
            synchronized(this) {
                reminderJobs[transition.eventId] = reminderJob
            }
        }
    }

    private fun repeatLossAnnouncementAt60sIfStillLost(
        transition: PeerConnectivityTransition,
        fullName: String,
        relation: RiderRelation
    ) {
        if (!isActiveSession) return
        val shouldSpeakRepeat = synchronized(this) {
            reminderJobs.remove(transition.eventId)
            val currentWinner = announcedWinners[transition.eventId]
            val currentCount = announcementCounts[transition.eventId] ?: 0
            val stillLost = tracker.getRiderState(transition.subjectShortId)?.isLost == true
            if (currentWinner == localShortId && stillLost && currentCount < MAX_ANNOUNCEMENTS_PER_EVENT) {
                announcementCounts[transition.eventId] = currentCount + 1
                true
            } else {
                false
            }
        }

        if (shouldSpeakRepeat) {
            ttsAnnouncer.speakAnnouncement(
                eventId = transition.eventId,
                fullName = fullName,
                relation = relation,
                shortId = transition.subjectShortId,
                kind = AnnouncerEventKind.PEER_LOST
            )
        }
    }

    /**
     * Al recibir `ANNOUNCED(eventId, otro)`:
     * - Si nuestro anuncio aún estaba en espera (`pendingJobs`), lo cancela y calla.
     * - Si ambos nodos anunciaron exactamente al mismo tiempo, gana el de menor `shortId` y el de mayor ID
     *   corta su TTS de inmediato (`DESIGN.md` §11).
     */
    fun onRemoteAnnouncedReceived(signal: MeshRelayEngine.AnnouncedMeshSignal) {
        synchronized(this) {
            val pending = pendingJobs.remove(signal.eventId)
            if (pending != null) {
                pending.cancel()
                announcedWinners[signal.eventId] = signal.announcerShortId
                updateBannerFromRemote(signal)
                return
            }

            val existingWinner = announcedWinners[signal.eventId]
            if (existingWinner == null) {
                announcedWinners[signal.eventId] = signal.announcerShortId
                updateBannerFromRemote(signal)
                return
            }

            // Desempate simultáneo: gana el nodo con menor shortId
            if (existingWinner == localShortId && signal.announcerShortId < localShortId) {
                ttsAnnouncer.interruptIfSpeaking(signal.eventId)
                reminderJobs.remove(signal.eventId)?.cancel()
                announcedWinners[signal.eventId] = signal.announcerShortId
                updateBannerFromRemote(signal)
            }
        }
    }

    /**
     * Unifica el registro de `eventId` ya anunciados cuando dos islas de la mesh vuelven a encontrarse (`DESIGN.md` §11).
     */
    @Synchronized
    fun mergeIslandEvents(remoteWinners: Map<String, UShort>) {
        for ((eventId, remoteWinner) in remoteWinners) {
            pendingJobs.remove(eventId)?.cancel()
            val localWinner = announcedWinners[eventId]
            if (localWinner == null || remoteWinner < localWinner) {
                if (localWinner == localShortId && remoteWinner < localShortId) {
                    ttsAnnouncer.interruptIfSpeaking(eventId)
                    reminderJobs.remove(eventId)?.cancel()
                }
                announcedWinners[eventId] = remoteWinner
            }
        }
    }

    @Synchronized
    fun cancelPendingLossesForSubject(subjectShortId: UShort) {
        val lossIds = pendingLossEventIdsBySubject.remove(subjectShortId).orEmpty()
        for (eventId in lossIds) {
            pendingJobs.remove(eventId)?.cancel()
            reminderJobs.remove(eventId)?.cancel()
        }
    }

    @Synchronized
    fun getAnnouncementCount(eventId: String): Int = announcementCounts[eventId] ?: 0

    @Synchronized
    fun getWinnerForEvent(eventId: String): UShort? = announcedWinners[eventId]

    @Synchronized
    fun exportAnnouncedWinners(): Map<String, UShort> = announcedWinners.toMap()

    /**
     * Cancela todos los `eventId` pendientes y apaga el TTS al detener la rodada (`RideService.stopAll`).
     */
    @Synchronized
    fun cancelAllAndStop() {
        isActiveSession = false
        peersCollectJob?.cancel()
        peersCollectJob = null
        signalsCollectJob?.cancel()
        signalsCollectJob = null

        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        reminderJobs.values.forEach { it.cancel() }
        reminderJobs.clear()
        pendingLossEventIdsBySubject.clear()
        _activeBanner.value = null
        tracker.reset()
        ttsAnnouncer.shutdown()
    }

    private fun updateBannerFromRemote(signal: MeshRelayEngine.AnnouncedMeshSignal) {
        val entry = directory[signal.subjectShortId]
        val fullName = entry?.fullName ?: "Rider #${signal.subjectShortId.toInt()}"
        val relation = entry?.relation ?: RiderRelation.ALLIED_STRANGER
        val kind = AnnouncerEventKind.fromWireCode(signal.eventKindCode)
        val spokenName = TtsAnnouncer.formatSpokenRiderName(fullName, relation, signal.subjectShortId)
        val msg = TtsAnnouncer.formatAnnouncementMessage(spokenName, kind)
        _activeBanner.value = AnnouncerOverlayBanner(
            eventId = signal.eventId,
            subjectShortId = signal.subjectShortId,
            kind = kind,
            message = msg,
            announcedByShortId = signal.announcerShortId
        )
    }
}
