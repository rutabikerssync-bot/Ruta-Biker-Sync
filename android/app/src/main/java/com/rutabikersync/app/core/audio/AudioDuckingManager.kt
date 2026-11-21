package com.rutabikersync.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Gestor de Atenuación de Audio (Audio Ducking) para comunicaciones en casco (DESIGN.md §5).
 *
 * Características:
 * - Reduce automáticamente el volumen de la música (Spotify, YouTube Music, etc.) mientras
 *   se reproduce un clip de voz entrante o mientras el rider transmite por PTT.
 * - Enruta el audio hacia el intercomunicador Bluetooth del casco (A2DP/HFP/SCO) según el perfil activo.
 * - Restaura el volumen de fondo de forma inmediata al finalizar la reproducción o transmisión.
 */
class AudioDuckingManager(
    context: Context,
    private val onFocusAvailabilityChanged: ((Boolean) -> Unit)? = null
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var isDuckingActive = false

    val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * Solicita atenuación (ducking) de la música externa para reproducir un aviso o clip PTT.
     * @return true si el foco de audio fue concedido o aceptado.
     */
    fun requestDucking(): Boolean {
        val am = audioManager ?: return false
        if (isDuckingActive) return true

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                isDuckingActive = false
                                focusRequest = null
                                onFocusAvailabilityChanged?.invoke(false)
                            }
                            AudioManager.AUDIOFOCUS_GAIN,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                                isDuckingActive = true
                                onFocusAvailabilityChanged?.invoke(true)
                            }
                        }
                    }
                    .build()
                focusRequest = req
                val result = am.requestAudioFocus(req)
                isDuckingActive = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                isDuckingActive
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                isDuckingActive = false
                                focusRequest = null
                                onFocusAvailabilityChanged?.invoke(false)
                            }
                            AudioManager.AUDIOFOCUS_GAIN,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                                isDuckingActive = true
                                onFocusAvailabilityChanged?.invoke(true)
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                isDuckingActive = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                isDuckingActive
            }
        }.getOrDefault(false)
    }

    /**
     * Abandona el foco de audio restaurando el volumen normal de la música y aplicaciones externas.
     */
    fun releaseDucking() {
        val am = audioManager ?: return
        if (!isDuckingActive) return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        isDuckingActive = false
    }

    fun isCurrentlyDucked(): Boolean = isDuckingActive
}
