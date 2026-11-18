package com.rutabikersync.app.core.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Cola de Reproducción Ordenada y Borrado Efímero de Clips de Voz PTT (DESIGN.md §5, TASKS.md Fase 5).
 *
 * Reglas de diseño biker:
 * - Cola ordenada FIFO para clips regulares.
 * - Clips con prioridad de emergencia (SOS) adelantan inmediatamente al frente de la cola.
 * - Ducking automático de música de fondo durante toda la duración del mensaje.
 * - Salida al casco por Bluetooth A2DP/HFP gracias a USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.
 * - Borrado seguro de archivo: Inmediatamente tras reproducirse, el archivo de audio se elimina del disco.
 * - Descarte por inactividad: Si la rodada termina, todos los clips pendientes se borran y descartan.
 */
class VoiceClipPlayerQueue(
    private val context: Context,
    private val duckingManager: AudioDuckingManager = AudioDuckingManager(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    private val queue = ConcurrentLinkedDeque<VoiceClipModel>()
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _currentlyPlaying = MutableStateFlow<VoiceClipModel?>(null)
    val currentlyPlaying: StateFlow<VoiceClipModel?> = _currentlyPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val _mutedSenders = MutableStateFlow<Set<UShort>>(emptySet())
    val mutedSenders: StateFlow<Set<UShort>> = _mutedSenders.asStateFlow()

    private var isRideActive = true

    fun setRideActive(active: Boolean) {
        isRideActive = active
        if (!active) {
            discardAll()
        }
    }

    /**
     * Silencia o des-silencia localmente a un rider en la rodada activa (RULES R13.3).
     * Si el rider está hablando en ese instante, detiene de inmediato la reproducción.
     */
    fun toggleMuteSender(senderShortId: UShort): Boolean {
        val current = _mutedSenders.value
        val isMuting = !current.contains(senderShortId)
        _mutedSenders.value = if (isMuting) current + senderShortId else current - senderShortId
        if (isMuting && _currentlyPlaying.value?.senderShortId == senderShortId) {
            _currentlyPlaying.value?.let { finishClip(it) }
        }
        return isMuting
    }

    fun isSenderMuted(senderShortId: UShort): Boolean = _mutedSenders.value.contains(senderShortId)

    /**
     * Encola un nuevo clip recibido por la malla o generado localmente.
     */
    fun enqueue(clip: VoiceClipModel) {
        if (!isRideActive || isSenderMuted(clip.senderShortId)) {
            clip.deleteFile()
            return
        }

        if (clip.isEmergencyPriority) {
            queue.addFirst(clip)
        } else {
            queue.addLast(clip)
        }
        _queuedCount.value = queue.size

        if (_currentlyPlaying.value == null) {
            playNext()
        }
    }

    private fun playNext() {
        if (!isRideActive) {
            discardAll()
            return
        }

        val nextClip = queue.pollFirst()
        _queuedCount.value = queue.size

        if (nextClip == null) {
            _currentlyPlaying.value = null
            _playbackProgress.value = 0f
            duckingManager.releaseDucking()
            return
        }

        val file = File(nextClip.filePath)
        if (!file.exists() || file.length() == 0L) {
            playNext()
            return
        }

        _currentlyPlaying.value = nextClip
        _playbackProgress.value = 0f
        duckingManager.requestDucking()

        runCatching {
            mediaPlayer?.release()
            val player = MediaPlayer().apply {
                setAudioAttributes(duckingManager.audioAttributes)
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    finishClip(nextClip)
                }
                setOnErrorListener { _, _, _ ->
                    finishClip(nextClip)
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player

            startProgressTracker(player, nextClip.durationSeconds)
        }.onFailure {
            finishClip(nextClip)
        }
    }

    private fun startProgressTracker(player: MediaPlayer, estimatedSeconds: Int) {
        progressJob?.cancel()
        progressJob = scope.launch {
            val totalDurationMs = runCatching { player.duration }.getOrNull()?.takeIf { it > 0 }
                ?: (estimatedSeconds * 1000).coerceAtLeast(1000)

            while (isActive && _currentlyPlaying.value != null) {
                val currentPos = runCatching { player.currentPosition }.getOrDefault(0)
                _playbackProgress.value = (currentPos.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                delay(100L)
            }
        }
    }

    private fun finishClip(clip: VoiceClipModel) {
        progressJob?.cancel()
        progressJob = null

        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null

        // Regla no negociable: Se reproduce y se borra inmediatamente
        clip.deleteFile()

        _playbackProgress.value = 1f
        _currentlyPlaying.value = null

        // Avanzar al siguiente clip en la cola
        playNext()
    }

    /**
     * Descarta toda la cola pendiente y elimina los archivos físicos del almacenamiento.
     */
    fun discardAll() {
        progressJob?.cancel()
        progressJob = null

        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        duckingManager.releaseDucking()

        _currentlyPlaying.value?.deleteFile()
        _currentlyPlaying.value = null
        _playbackProgress.value = 0f

        while (true) {
            val clip = queue.pollFirst() ?: break
            clip.deleteFile()
        }
        _queuedCount.value = 0
    }
}
