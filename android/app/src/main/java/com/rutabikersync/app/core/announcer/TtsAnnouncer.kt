package com.rutabikersync.app.core.announcer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.rutabikersync.app.feature.livemap.RiderRelation
import java.util.Locale

/**
 * Locutor de voz sintética local (`TextToSpeech`) para avisos de rodada (Bloque 3.4, DESIGN.md §11).
 *
 * Implementa las decisiones de diseño:
 * - **3.1 (C):** Voz del sistema por defecto con fallback automático a `com.google.android.tts`.
 * - **3.2 (B):** Ducking de audio (`AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) para bajar el volumen
 *   de la música del casco sin detenerla mientras se pronuncia el aviso.
 * - **3.3 (A + C):** Pronuncia el nombre completo si es compañero del mismo grupo o amigo mutuo
 *   (`"Juan Martínez salió de la mesh"`), e identificador genérico si es desconocido de otro grupo en rodada aliada
 *   (`"Rider aliado #5 salió de la mesh"` — privacidad R4.7/R4.8).
 * - Multi-idioma (`es`, `en`, `pt`), cola FIFO para no pisar locuciones, vibración sincronizada, modo silencio
 *   y corte inmediato si otro nodo con menor ID gana el desempate simultáneo.
 */
class TtsAnnouncer(
    private val context: Context? = null,
    private val locale: Locale = Locale("es", "MX"),
    var volume: Float = 1.0f,
    var isMuted: Boolean = false
) {
    companion object {
        const val GOOGLE_TTS_PACKAGE: String = "com.google.android.tts"
        const val AUDIO_FOCUS_MODE_DUCKING: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

        fun formatSpokenRiderName(
            fullName: String,
            relation: RiderRelation,
            shortId: UShort,
            languageCode: String = "es"
        ): String {
            return when (relation) {
                RiderRelation.SAME_GROUP, RiderRelation.MUTUAL_FRIEND -> fullName.trim().ifEmpty { "Rider #${shortId.toInt()}" }
                RiderRelation.ALLIED_STRANGER -> when (languageCode.lowercase(Locale.ROOT)) {
                    "en" -> "Allied rider #${shortId.toInt()}"
                    "pt" -> "Rider aliado #${shortId.toInt()}"
                    else -> "Rider aliado #${shortId.toInt()}"
                }
            }
        }

        fun formatAnnouncementMessage(
            spokenRiderName: String,
            kind: AnnouncerEventKind,
            languageCode: String = "es"
        ): String {
            val lang = languageCode.lowercase(Locale.ROOT)
            return when (kind) {
                AnnouncerEventKind.PEER_LOST -> when (lang) {
                    "en" -> "$spokenRiderName left the mesh"
                    "pt" -> "$spokenRiderName saiu da mesh"
                    else -> "$spokenRiderName salió de la mesh"
                }
                AnnouncerEventKind.PEER_BACK -> when (lang) {
                    "en" -> "$spokenRiderName reconnected"
                    "pt" -> "$spokenRiderName reconectou"
                    else -> "$spokenRiderName se reconectó"
                }
                AnnouncerEventKind.UNSTABLE_CONNECTION -> when (lang) {
                    "en" -> "$spokenRiderName has an unstable connection"
                    "pt" -> "$spokenRiderName está com conexão instável"
                    else -> "$spokenRiderName tiene conexión inestable"
                }
            }
        }
    }

    data class SpokenUtteranceRecord(
        val eventId: String,
        val text: String,
        val language: String,
        val usedDuckingAudioFocus: Boolean,
        val vibrated: Boolean,
        val engineName: String,
        val wasInterruptedByTieBreak: Boolean = false
    )

    private var tts: TextToSpeech? = null
    private var activeEnginePackage: String = "system-default"
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentlySpeakingEventId: String? = null

    private val speechQueue = ArrayDeque<Pair<String, String>>()
    private val _spokenHistory = mutableListOf<SpokenUtteranceRecord>()

    val spokenHistory: List<SpokenUtteranceRecord>
        @Synchronized get() = _spokenHistory.toList()

    val currentEngineName: String
        @Synchronized get() = activeEnginePackage

    init {
        initializeSystemTtsWithGoogleFallback()
    }

    private fun initializeSystemTtsWithGoogleFallback() {
        val ctx = context ?: return
        runCatching {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    activeEnginePackage = "system-default"
                    configureTtsInstance()
                } else {
                    // Decisión 3.1 (C): Fallback al motor de Google si falla la voz por defecto del sistema
                    tts = TextToSpeech(ctx, { googleStatus ->
                        if (googleStatus == TextToSpeech.SUCCESS) {
                            activeEnginePackage = GOOGLE_TTS_PACKAGE
                            configureTtsInstance()
                        }
                    }, GOOGLE_TTS_PACKAGE)
                }
            }
        }
    }

    private fun configureTtsInstance() {
        val engine = tts ?: return
        runCatching {
            engine.language = locale
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    synchronized(this@TtsAnnouncer) {
                        currentlySpeakingEventId = utteranceId
                    }
                }

                override fun onDone(utteranceId: String?) {
                    onUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onUtteranceFinished(utteranceId)
                }
            })
        }
    }

    /**
     * Encola y pronuncia un anuncio de evento con foco de audio en modo Ducking y vibración sincronizada.
     * Si `isMuted = true`, no emite voz.
     */
    @Synchronized
    fun speakAnnouncement(
        eventId: String,
        fullName: String,
        relation: RiderRelation,
        shortId: UShort,
        kind: AnnouncerEventKind
    ): SpokenUtteranceRecord? {
        if (isMuted) return null

        val lang = locale.language.ifBlank { "es" }
        val spokenName = formatSpokenRiderName(
            fullName = fullName,
            relation = relation,
            shortId = shortId,
            languageCode = lang
        )
        val message = formatAnnouncementMessage(
            spokenRiderName = spokenName,
            kind = kind,
            languageCode = lang
        )

        requestDuckingAudioFocus()
        triggerVibration()

        val record = SpokenUtteranceRecord(
            eventId = eventId,
            text = message,
            language = lang,
            usedDuckingAudioFocus = true,
            vibrated = true,
            engineName = activeEnginePackage,
            wasInterruptedByTieBreak = false
        )
        _spokenHistory.add(record)
        currentlySpeakingEventId = eventId
        speechQueue.addLast(eventId to message)

        val engine = tts
        if (engine != null) {
            runCatching {
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
                }
                engine.speak(message, TextToSpeech.QUEUE_ADD, params, eventId)
            }
        } else {
            // En entorno JVM sin motor Android real, la locución queda registrada para inspección de tests
            abandonDuckingAudioFocus()
        }
        return record
    }

    /**
     * Si dos nodos anuncian a la vez y este nodo pierde el desempate (el otro tiene menor `shortId`),
     * corta inmediatamente su TTS (`DESIGN.md` §11).
     */
    @Synchronized
    fun interruptIfSpeaking(eventId: String): Boolean {
        speechQueue.removeAll { it.first == eventId }
        val wasSpeakingThisEvent = currentlySpeakingEventId == eventId ||
            _spokenHistory.lastOrNull()?.eventId == eventId

        if (wasSpeakingThisEvent) {
            val lastIdx = _spokenHistory.indexOfLast { it.eventId == eventId }
            if (lastIdx >= 0) {
                _spokenHistory[lastIdx] = _spokenHistory[lastIdx].copy(wasInterruptedByTieBreak = true)
            }
            if (currentlySpeakingEventId == eventId) {
                currentlySpeakingEventId = null
            }
            runCatching { tts?.stop() }
            abandonDuckingAudioFocus()
            return true
        }
        return false
    }

    @Synchronized
    fun shutdown() {
        speechQueue.clear()
        currentlySpeakingEventId = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        abandonDuckingAudioFocus()
    }

    @Synchronized
    private fun onUtteranceFinished(utteranceId: String?) {
        if (currentlySpeakingEventId == utteranceId) {
            currentlySpeakingEventId = null
        }
        if (speechQueue.isNotEmpty()) {
            speechQueue.removeFirst()
        }
        if (speechQueue.isEmpty()) {
            abandonDuckingAudioFocus()
        }
    }

    private fun requestDuckingAudioFocus() {
        val ctx = context ?: return
        runCatching {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AUDIO_FOCUS_MODE_DUCKING)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonDuckingAudioFocus() {
        val ctx = context ?: return
        val req = audioFocusRequest ?: return
        runCatching {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.abandonAudioFocusRequest(req)
        }
        audioFocusRequest = null
    }

    @Suppress("DEPRECATION")
    private fun triggerVibration() {
        val ctx = context ?: return
        runCatching {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
