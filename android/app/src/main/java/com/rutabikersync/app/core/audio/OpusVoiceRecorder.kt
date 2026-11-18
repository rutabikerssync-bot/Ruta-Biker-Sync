package com.rutabikersync.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Grabador de clips de voz en formato Opus para intercomunicador PTT (DESIGN.md §5, TASKS.md Fase 5).
 *
 * Características:
 * - Duración máxima: 15 segundos con auto-parada.
 * - Formato: Opus nativo en contenedor Ogg (API 29+) a 12 kbps, o fallback AAC en API 26-28.
 * - Ducking automático mientras el micrófono está activo para no mezclar música del casco.
 * - Filtro anti-pulsaciones accidentales (< 400 ms).
 */
class OpusVoiceRecorder(
    private val context: Context,
    private val duckingManager: AudioDuckingManager = AudioDuckingManager(context)
) {

    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val elapsedSeconds: Int, val maxSeconds: Int = MAX_DURATION_SECONDS) : RecordingState()
        data class Completed(val clip: VoiceClipModel) : RecordingState()
        data class Discarded(val reason: String) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    companion object {
        const val MAX_DURATION_SECONDS = 15
        const val MIN_DURATION_MS = 400L
        const val OPUS_BITRATE_BPS = 12000 // 12 kbps
        const val SAMPLE_RATE_HZ = 16000   // 16 kHz mono (ideal voz)
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTimeMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var amplitudeRunnable: Runnable? = null
    private var autoStopRunnable: Runnable? = null

    /**
     * Inicia la grabación del clip de voz PTT.
     * @param senderShortId ID corto del nodo en la malla.
     * @param senderName Nombre de guerra del rider.
     * @param senderRole Rol del rider en el convoy.
     */
    fun startRecording(
        senderShortId: UShort = 1u,
        senderName: String = "Piloto",
        senderRole: String = "PILOTO",
        isEmergency: Boolean = false
    ): Boolean {
        if (_recordingState.value is RecordingState.Recording) {
            return false
        }

        return runCatching {
            duckingManager.requestDucking()

            val clipsDir = File(context.cacheDir, "voice_clips").apply { if (!exists()) mkdirs() }
            val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "opus" else "m4a"
            val file = File(clipsDir, "ptt_${UUID.randomUUID()}.$ext")
            currentOutputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(OPUS_BITRATE_BPS)
                setAudioChannels(1) // Mono
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTimeMs = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording(0)

            startTimers(senderShortId, senderName, senderRole, isEmergency)
            true
        }.getOrElse { e ->
            duckingManager.releaseDucking()
            currentOutputFile?.delete()
            currentOutputFile = null
            _recordingState.value = RecordingState.Error("Fallo al iniciar micrófono: ${e.message}")
            false
        }
    }

    private fun startTimers(
        senderShortId: UShort,
        senderName: String,
        senderRole: String,
        isEmergency: Boolean
    ) {
        tickerRunnable = object : Runnable {
            override fun run() {
                val elapsed = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt()
                if (elapsed <= MAX_DURATION_SECONDS && _recordingState.value is RecordingState.Recording) {
                    _recordingState.value = RecordingState.Recording(elapsed)
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.postDelayed(tickerRunnable!!, 1000L)

        // VU-meter en tiempo real: lee la amplitud máxima de MediaRecorder cada 50 ms (~20 fps)
        amplitudeRunnable = object : Runnable {
            override fun run() {
                if (_recordingState.value is RecordingState.Recording) {
                    val maxAmp = try {
                        mediaRecorder?.maxAmplitude ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    val normalized = (maxAmp.toFloat() / 24000f).coerceIn(0f, 1f)
                    _audioAmplitude.value = normalized
                    mainHandler.postDelayed(this, 50L)
                } else {
                    _audioAmplitude.value = 0f
                }
            }
        }
        mainHandler.post(amplitudeRunnable!!)

        autoStopRunnable = Runnable {
            if (_recordingState.value is RecordingState.Recording) {
                stopRecording(senderShortId, senderName, senderRole, isEmergency)
            }
        }
        mainHandler.postDelayed(autoStopRunnable!!, MAX_DURATION_SECONDS * 1000L)
    }

    /**
     * Detiene la grabación y devuelve el modelo del clip empaquetado.
     */
    fun stopRecording(
        senderShortId: UShort = 1u,
        senderName: String = "Piloto",
        senderRole: String = "PILOTO",
        isEmergency: Boolean = false
    ): VoiceClipModel? {
        val recorder = mediaRecorder ?: return null
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        amplitudeRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        _audioAmplitude.value = 0f

        var finalClip: VoiceClipModel? = null
        val durationMs = System.currentTimeMillis() - recordingStartTimeMs

        try {
            runCatching {
                recorder.stop()
                recorder.release()
            }
            mediaRecorder = null
            duckingManager.releaseDucking()

            val file = currentOutputFile
            if (durationMs < MIN_DURATION_MS || file == null || !file.exists() || file.length() == 0L) {
                file?.delete()
                currentOutputFile = null
                _recordingState.value = RecordingState.Discarded("Clip demasiado corto (< 400 ms)")
                return null
            }

            val durationSeconds = ((durationMs + 500) / 1000).toInt().coerceIn(1, MAX_DURATION_SECONDS)
            val audioBytes = runCatching { file.readBytes() }.getOrNull()

            val clip = VoiceClipModel(
                senderShortId = senderShortId,
                senderName = senderName,
                senderRole = senderRole,
                durationSeconds = durationSeconds,
                recordedEpochMs = System.currentTimeMillis(),
                filePath = file.absolutePath,
                audioBytes = audioBytes,
                hopsTaken = 1,
                isEmergencyPriority = isEmergency
            )
            finalClip = clip
            _recordingState.value = RecordingState.Completed(clip)
        } catch (e: Exception) {
            currentOutputFile?.delete()
            currentOutputFile = null
            _recordingState.value = RecordingState.Error("Error al detener grabación: ${e.message}")
        }

        return finalClip
    }

    fun cancelRecording() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        amplitudeRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        _audioAmplitude.value = 0f
        runCatching {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        }
        mediaRecorder = null
        duckingManager.releaseDucking()
        currentOutputFile?.delete()
        currentOutputFile = null
        _recordingState.value = RecordingState.Idle
    }

    fun resetState() {
        _recordingState.value = RecordingState.Idle
    }
}
