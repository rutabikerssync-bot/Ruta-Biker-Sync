package com.rutabikersync.app.core.audio

import android.content.Context
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Catálogo unificado de Señales Rápidas de Convoy (Hito 2).
 * Unifica el identificador, texto visual, ícono, y patrón de tono audible.
 */
enum class ConvoySignalKind(
    val code: Byte,
    val title: String,
    val subtitle: String,
    val icon: String,
    val toneType: Int,
    val pulseCount: Int,
    val pulseDurationMs: Long,
    val gapMs: Long = 100L
) {
    OBSTACLE(
        code = 1,
        title = "Obstáculo en la vía",
        subtitle = "Bache, grava o escombros",
        icon = "⚠",
        toneType = ToneGenerator.TONE_PROP_NACK,
        pulseCount = 1,
        pulseDurationMs = 300L,
        gapMs = 100L
    ),
    REDUCE_SPEED(
        code = 2,
        title = "Reducir velocidad",
        subtitle = "Frenado fuerte / Peligro",
        icon = "🛑",
        toneType = ToneGenerator.TONE_PROP_BEEP2,
        pulseCount = 2,
        pulseDurationMs = 100L,
        gapMs = 60L
    ),
    REGROUP(
        code = 3,
        title = "Reagrupar convoy",
        subtitle = "Cerrar filas de convoy",
        icon = "🔁",
        toneType = ToneGenerator.TONE_PROP_BEEP,
        pulseCount = 2,
        pulseDurationMs = 220L,
        gapMs = 140L
    ),
    GAS_STOP(
        code = 4,
        title = "Parada de gasolina",
        subtitle = "Estación de servicio próxima",
        icon = "⛽",
        toneType = ToneGenerator.TONE_PROP_ACK,
        pulseCount = 1,
        pulseDurationMs = 520L,
        gapMs = 120L
    ),
    POLICE_CHECKPOINT(
        code = 5,
        title = "Retén / Precaución",
        subtitle = "Control o alerta en la vía",
        icon = "👮",
        toneType = ToneGenerator.TONE_PROP_PROMPT,
        pulseCount = 3,
        pulseDurationMs = 90L,
        gapMs = 60L
    );

    val fullLabel: String get() = "$icon $title"

    companion object {
        fun fromCode(code: Byte): ConvoySignalKind? = entries.firstOrNull { it.code == code }
    }
}

/** Reproductor de señales informativas breves del mapa. */
interface ConvoySignalAudioPlayer {
    val isPlaying: Boolean
    fun playSignal(kind: ConvoySignalKind)
    fun playTone(toneType: Int, durationMs: Long)
    fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long = 100L)
    fun stop()

    /** Libera el ToneGenerator y el foco; el reproductor queda inactivo. */
    fun release()
}

/**
 * Implementación de reproducción de señales de convoy con control de foco,
 * supresión estricta si SOS o PTT están activos, e interrupción limpia.
 */
class DefaultConvoySignalPlayer(
    private val toneEmitter: ToneEmitter,
    private val focusDelegate: AudioFocusDelegate,
    private val pttStateProvider: PttStateProvider = PttStateProvider { false },
    private val sosStateProvider: () -> Boolean = { false },
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val audibilityProvider: () -> Boolean = { true }
) : ConvoySignalAudioPlayer {

    private val stateLock = Any()
    private var playbackJob: Job? = null

    @Volatile
    private var playing = false
    override val isPlaying: Boolean get() = playing

    override fun playTone(toneType: Int, durationMs: Long) {
        playPattern(toneType, 1, durationMs, 0L)
    }

    override fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long) {
        synchronized(stateLock) {
            if (sosStateProvider() || pttStateProvider.isPttActive() || !audibilityProvider()) {
                return
            }
            stopInternal()
            if (!focusDelegate.requestFocus()) return
            playing = true
            val job = coroutineScope.launch(dispatcher) {
                try {
                    for (i in 0 until pulseCount) {
                        if (!currentCoroutineContext().isActive || !playing) break
                        if (sosStateProvider() || pttStateProvider.isPttActive()) break
                        val emitted = synchronized(stateLock) {
                            if (!playing) false else toneEmitter.toneOn(toneType, pulseDurationMs)
                        }
                        if (!emitted) break
                        delay(pulseDurationMs)
                        synchronized(stateLock) { toneEmitter.toneOff() }
                        if (i < pulseCount - 1) {
                            delay(gapMs)
                        }
                    }
                } finally {
                    finishOwnedPlayback(currentCoroutineContext()[Job])
                }
            }
            playbackJob = job
            if (!coroutineScope.isActive || job.isCancelled) finishOwnedPlayback(job)
        }
    }

    override fun playSignal(kind: ConvoySignalKind) {
        synchronized(stateLock) {
            // Prioridad absoluta: SOS y PTT suprimen cualquier señal informativa
            if (sosStateProvider() || pttStateProvider.isPttActive() || !audibilityProvider()) {
                Log.w(TAG, "playSignal ignorado: SOS/PTT activo o audio no audible")
                return
            }

            // Si hay un tono previo ejecutándose, abortar ordenadamente antes de iniciar el nuevo
            stopInternal()

            if (!focusDelegate.requestFocus()) {
                Log.w(TAG, "playSignal sin AudioFocus: se omite sonido")
                return
            }

            playing = true
            val job = coroutineScope.launch(dispatcher) {
                try {
                    for (i in 0 until kind.pulseCount) {
                        if (!currentCoroutineContext().isActive || !playing) break
                        if (sosStateProvider() || pttStateProvider.isPttActive()) break

                        val emitted = synchronized(stateLock) {
                            if (!playing) false else toneEmitter.toneOn(kind.toneType, kind.pulseDurationMs)
                        }
                        if (!emitted) break

                        delay(kind.pulseDurationMs)
                        synchronized(stateLock) { toneEmitter.toneOff() }

                        if (i < kind.pulseCount - 1) {
                            delay(kind.gapMs)
                        }
                    }
                } finally {
                    finishOwnedPlayback(currentCoroutineContext()[Job])
                }
            }
            playbackJob = job
            if (!coroutineScope.isActive || job.isCancelled) finishOwnedPlayback(job)
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            stopInternal()
        }
    }

    override fun release() {
        synchronized(stateLock) {
            stopInternal()
            runCatching { toneEmitter.release() }
            focusDelegate.releaseFocus()
        }
    }

    private fun stopInternal() {
        if (!playing && playbackJob == null) return
        val ownedJob = playbackJob
        playbackJob = null
        playing = false
        ownedJob?.cancel()
        cleanupResources()
    }

    private fun finishOwnedPlayback(job: Job?) = synchronized(stateLock) {
        if (job == null || playbackJob !== job) return
        playbackJob = null
        playing = false
        cleanupResources()
    }

    private fun cleanupResources() {
        runCatching { toneEmitter.toneOff() }
        focusDelegate.releaseFocus()
    }

    private companion object {
        const val TAG = "ConvoySignalAudioPlayer"
    }
}

/**
 * Hub singleton para coordinar la reproducción de señales desde Composable o background.
 */
object ConvoySignalAudioHub {
    @Volatile
    private var player: ConvoySignalAudioPlayer? = null
    private var appContext: Context? = null
    private val lock = Any()

    @Volatile
    var pttStateProvider: PttStateProvider = PttStateProvider { false }

    fun ensureInitialized(
        context: Context,
        externalPttStateProvider: PttStateProvider? = null,
        sosStateProvider: () -> Boolean = { SosToneHub.isPlaying }
    ) {
        if (externalPttStateProvider != null) {
            pttStateProvider = externalPttStateProvider
        }
        synchronized(lock) {
            if (player != null) return
            val appContext = context.applicationContext
            this.appContext = appContext
            val focusDelegate = DuckingFocusDelegate(AudioDuckingManager(appContext))
            val audibility = AndroidSosAudibilityPolicy(appContext)
            player = DefaultConvoySignalPlayer(
                toneEmitter = AndroidToneEmitter(),
                focusDelegate = focusDelegate,
                pttStateProvider = { pttStateProvider.isPttActive() },
                sosStateProvider = sosStateProvider,
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                audibilityProvider = audibility::isAudible
            )
        }
    }

    @Synchronized
    fun setCustomPlayerForTesting(customPlayer: ConvoySignalAudioPlayer?) {
        player?.stop()
        player = customPlayer
    }

    /** Tras un `release()` (fin de rodada), el siguiente sonido reconstruye el hub. */
    private fun activePlayer(): ConvoySignalAudioPlayer? {
        if (player == null) appContext?.let { ensureInitialized(it) }
        return player
    }

    fun playSignal(kind: ConvoySignalKind) {
        activePlayer()?.playSignal(kind)
    }

    fun playTone(toneType: Int, durationMs: Long) {
        activePlayer()?.playTone(toneType, durationMs)
    }

    fun playPattern(toneType: Int, pulseCount: Int, pulseDurationMs: Long, gapMs: Long = 100L) {
        activePlayer()?.playPattern(toneType, pulseCount, pulseDurationMs, gapMs)
    }

    fun stop() {
        player?.stop()
    }

    /** Libera el ToneGenerator; idempotente. */
    fun release() {
        synchronized(lock) {
            player?.release()
            player = null
        }
    }

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    /** True cuando el hub ya fue instalado por [AppAudioBootstrap]. */
    val isInitialized: Boolean
        get() = player != null
}
