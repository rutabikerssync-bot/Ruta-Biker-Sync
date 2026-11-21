package com.rutabikersync.app.core.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reproductor local del patrón Morse SOS. No transporta voz ni audio por BLE. */
interface SosTonePlayer {
    val isPlaying: Boolean
    fun start()
    fun stop()

    companion object {
        const val DOT_DURATION_MS = 150L
        const val DASH_DURATION_MS = 450L
        const val ELEMENT_GAP_MS = 100L
        const val LETTER_GAP_MS = 250L
        const val CYCLE_REPEAT_GAP_MS = 1500L
    }
}

/** El booleano impide informar "reproduciendo" si el emisor no pudo emitir un beep. */
interface ToneEmitter {
    fun toneOn(durationMs: Long): Boolean
    fun toneOn(toneType: Int, durationMs: Long): Boolean = toneOn(durationMs)
    fun toneOff()
    fun release()
}

interface AudioFocusDelegate {
    val isFocusHeld: Boolean
    fun requestFocus(): Boolean
    fun releaseFocus()
}

fun interface PttStateProvider {
    fun isPttActive(): Boolean
}

/** Implementación con ownership por Job: un Job antiguo jamás libera el foco del nuevo. */
class DefaultSosTonePlayer(
    private val toneEmitter: ToneEmitter,
    private val focusDelegate: AudioFocusDelegate,
    private val pttStateProvider: PttStateProvider = PttStateProvider { false },
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val audibilityProvider: () -> Boolean = { true }
) : SosTonePlayer {

    private val stateLock = Any()
    private var playbackJob: Job? = null

    @Volatile
    private var playing = false
    override val isPlaying: Boolean get() = playing

    override fun start() {
        synchronized(stateLock) {
        if (playing) return
        if (pttStateProvider.isPttActive() || !audibilityProvider()) return
        if (!focusDelegate.requestFocus()) {
            Log.w(TAG, "SOS sin AudioFocus: se conserva feedback visual/háptico")
            return
        }

        playing = true
        val job = coroutineScope.launch(dispatcher) {
            try {
                while (currentCoroutineContext().isActive && playing) {
                    if (pttStateProvider.isPttActive()) break
                    if (!emitLetter(SosTonePlayer.DOT_DURATION_MS)) break
                    if (!waitGap(SosTonePlayer.LETTER_GAP_MS)) break
                    if (!emitLetter(SosTonePlayer.DASH_DURATION_MS)) break
                    if (!waitGap(SosTonePlayer.LETTER_GAP_MS)) break
                    if (!emitLetter(SosTonePlayer.DOT_DURATION_MS)) break
                    if (!waitGap(SosTonePlayer.CYCLE_REPEAT_GAP_MS)) break
                }
            } finally {
                finishOwnedPlayback(currentCoroutineContext()[Job])
            }
        }
        playbackJob = job
        // Un Scope cancelado puede no ejecutar ni siquiera el finally de launch.
        if (!coroutineScope.isActive || job.isCancelled) finishOwnedPlayback(job)
        Log.i(TAG, "SosTone START request job=${job.hashCode()}")
        }
    }

    override fun stop() = synchronized(stateLock) {
        val ownedJob = playbackJob
        playbackJob = null
        playing = false
        ownedJob?.cancel()
        cleanupOwnedResources()
        if (ownedJob != null) Log.i(TAG, "SosTone STOP immediate job=${ownedJob.hashCode()}")
    }

    private fun finishOwnedPlayback(job: Job?) = synchronized(stateLock) {
        if (job == null || playbackJob !== job) return
        playbackJob = null
        playing = false
        cleanupOwnedResources()
    }

    private fun cleanupOwnedResources() {
        runCatching { toneEmitter.toneOff() }
        runCatching { toneEmitter.release() }
            .onFailure { Log.w(TAG, "No se pudo liberar ToneEmitter: ${it.message}") }
        focusDelegate.releaseFocus()
    }

    private suspend fun emitLetter(durationMs: Long): Boolean {
        repeat(3) { index ->
            if (!playing || pttStateProvider.isPttActive() || !currentCoroutineContext().isActive) return false
            val emitted = synchronized(stateLock) {
                if (!playing) false else toneEmitter.toneOn(durationMs)
            }
            if (!emitted) {
                Log.e(TAG, "SosTone no pudo iniciar ToneGenerator: liberando foco")
                return false
            }
            if (!waitGap(durationMs)) return false
            synchronized(stateLock) { toneEmitter.toneOff() }
            if (index < 2 && !waitGap(SosTonePlayer.ELEMENT_GAP_MS)) return false
        }
        return true
    }

    private suspend fun waitGap(durationMs: Long): Boolean {
        if (!playing || !currentCoroutineContext().isActive) return false
        delay(durationMs)
        return playing && currentCoroutineContext().isActive
    }

    private companion object {
        const val TAG = "SosTonePlayer"
    }
}

/** Emisor Android reconstruible: stop libera, pero el próximo incidente puede crear otro. */
class AndroidToneEmitter(
    streamType: Int = AudioManager.STREAM_ALARM,
    volume: Int = ToneGenerator.MAX_VOLUME
) : ToneEmitter {
    private val lock = Any()
    private val stream = streamType
    private val outputVolume = volume
    private var toneGenerator: ToneGenerator? = null

    override fun toneOn(durationMs: Long): Boolean = toneOn(ToneGenerator.TONE_PROP_BEEP, durationMs)

    override fun toneOn(toneType: Int, durationMs: Long): Boolean = synchronized(lock) {
        val generator = toneGenerator ?: runCatching {
            ToneGenerator(stream, outputVolume)
        }.onFailure {
            Log.e(TAG, "ToneGenerator no disponible: ${it.message}")
        }.getOrNull() ?: return false
        toneGenerator = generator
        val success = runCatching {
            generator.startTone(toneType, durationMs.toInt())
        }.getOrElse {
            Log.e(TAG, "startTone falló: ${it.message}")
            false
        }
        if (success) Log.i(TAG, "ToneOn toneType=$toneType durationMs=$durationMs result=$success stream=$stream")
        else Log.e(TAG, "ToneOn rechazado por ToneGenerator")
        success
    }

    override fun toneOff() {
        synchronized(lock) {
            runCatching { toneGenerator?.stopTone() }
        }
    }

    override fun release() {
        synchronized(lock) {
            val generator = toneGenerator
            toneGenerator = null
            runCatching { generator?.stopTone() }
            runCatching { generator?.release() }
                .onFailure { Log.w(TAG, "ToneGenerator.release falló: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "SosTonePlayer"
    }
}

class DuckingFocusDelegate(
    private val audioDuckingManager: AudioDuckingManager
) : AudioFocusDelegate {
    override val isFocusHeld: Boolean
        get() = audioDuckingManager.isCurrentlyDucked()
    override fun requestFocus(): Boolean = audioDuckingManager.requestDucking()
    override fun releaseFocus() = audioDuckingManager.releaseDucking()
}

/** Respeta silencio/volumen/DND sin subir el volumen ni pedir bypass de alarmas. */
class AndroidSosAudibilityPolicy(context: Context) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.applicationContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

    fun isAudible(): Boolean {
        val volumeAvailable = (audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0) > 0
        val dndAllowsAlarm = when (notificationManager?.currentInterruptionFilter) {
            null,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            else -> false
        }
        return volumeAvailable && dndAllowsAlarm
    }
}

/**
 * Hub del proceso: alcance de aplicación, sin Scope ni Context de una Activity/Composable.
 * Recibe SOS de cualquier MeshRelayEngine activo, para que el tono se escuche en cada nodo.
 */
object SosToneHub {
    @Volatile
    private var coordinator: SosAlarmCoordinator? = null
    private val lock = Any()
    private val pttJobs = mutableMapOf<String, Job>()
    private val _latestRemoteSos = MutableStateFlow<SosPacket?>(null)
    val latestRemoteSos: StateFlow<SosPacket?> = _latestRemoteSos.asStateFlow()

    // Compatibilidad de pruebas previas; no es dueño del alcance de reproducción.
    @Volatile
    var pttStateProvider: PttStateProvider = PttStateProvider { false }

    @Volatile
    private var customPlayerForTesting: SosTonePlayer? = null

    fun ensureInitialized(
        context: Context,
        coroutineScope: CoroutineScope? = null,
        externalPttStateProvider: PttStateProvider? = null
    ) {
        if (externalPttStateProvider != null) {
            pttStateProvider = externalPttStateProvider
            coordinator?.setPttActive("legacy-provider", externalPttStateProvider.isPttActive())
        }
        synchronized(lock) {
            if (coordinator != null) {
                coordinator?.reconcile()
                return
            }
            val appContext = context.applicationContext
            val player = customPlayerForTesting ?: DefaultSosTonePlayer(
                toneEmitter = AndroidToneEmitter(),
                focusDelegate = DuckingFocusDelegate(
                    AudioDuckingManager(appContext) { focusAvailable ->
                        coordinator?.setFocusAvailable(focusAvailable)
                    }
                ),
                pttStateProvider = { false },
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                audibilityProvider = AndroidSosAudibilityPolicy(appContext)::isAudible
            )
            coordinator = SosAlarmCoordinator(player)
        }
    }

    @Synchronized
    fun setCustomPlayerForTesting(player: SosTonePlayer?) {
        customPlayerForTesting = player
        pttJobs.values.forEach { it.cancel() }
        pttJobs.clear()
        coordinator?.stopAll()
        coordinator = player?.let { SosAlarmCoordinator(it) }
        _latestRemoteSos.value = null
    }

    fun setLocalActive(active: Boolean) {
        coordinator?.setLocalActive(active)
    }

    fun onRemoteSos(packet: SosPacket) {
        val accepted = coordinator?.onRemoteSos(
            SosIncidentKey(packet.header.senderShortId, packet.header.sequenceNum)
        ) == true
        if (accepted) {
            _latestRemoteSos.value = packet
            Log.i(TAG, "SOS remoto aceptado para tono sender=${packet.header.senderShortId}")
        }
    }

    fun onAck(ack: SosAckPacket) {
        onAck(ack.originalSenderShortId, ack.originalSequenceNum)
    }

    fun onAck(senderShortId: UShort, sequenceNum: UShort) {
        coordinator?.onAck(SosIncidentKey(senderShortId, sequenceNum))
        if (_latestRemoteSos.value?.header?.let {
            it.senderShortId == senderShortId && it.sequenceNum == sequenceNum
        } == true) {
            _latestRemoteSos.value = null
        }
    }

    /** Solo una transición real true→false cancela remotamente; no un heartbeat viejo. */
    fun onPeerSosStateChanged(senderShortId: UShort, wasActive: Boolean, isActive: Boolean) {
        if (wasActive && !isActive) {
            coordinator?.onSenderResolved(senderShortId)
            if (_latestRemoteSos.value?.header?.senderShortId == senderShortId) {
                _latestRemoteSos.value = null
            }
        }
    }

    fun dismissRemoteForCabina(packet: SosPacket) {
        if (_latestRemoteSos.value?.header == packet.header) _latestRemoteSos.value = null
    }

    fun observePttSource(source: String, active: Flow<Boolean>, scope: CoroutineScope) {
        synchronized(lock) {
            pttJobs.remove(source)?.cancel()
            val job = scope.launch {
                active.distinctUntilChanged().collect { isActive ->
                    coordinator?.setPttActive(source, isActive)
                }
            }
            pttJobs[source] = job
            job.invokeOnCompletion {
                coordinator?.setPttActive(source, false)
                synchronized(lock) {
                    if (pttJobs[source] === job) pttJobs.remove(source)
                }
            }
        }
    }

    fun setPttActive(source: String, active: Boolean) {
        coordinator?.setPttActive(source, active)
    }

    fun silenceForCabinaExit() {
        coordinator?.silenceForCabinaExit()
    }

    fun stopAll() {
        synchronized(lock) {
            pttJobs.values.forEach { it.cancel() }
            pttJobs.clear()
        }
        _latestRemoteSos.value = null
        coordinator?.stopAll()
    }

    // Compatibilidad puntual; los llamadores nuevos usan estados explícitos.
    fun start() = setLocalActive(true)
    fun stop() = silenceForCabinaExit()

    val isPlaying: Boolean
        get() = coordinator?.isPlaying == true

    private const val TAG = "SosToneHub"
}
