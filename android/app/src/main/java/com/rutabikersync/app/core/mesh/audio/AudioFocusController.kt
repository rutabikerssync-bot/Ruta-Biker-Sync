package com.rutabikersync.app.core.mesh.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Controlador desacoplado de AudioFocus para transmisiones PTT.
 * Conforme a la auditoría del Hito 2:
 * - Solicita [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK] antes de reproducir audio PTT.
 * - Abandona el foco al finalizar la ráfaga.
 * - Desacoplado mediante interfaz para permitir tests deterministas en JVM pura.
 */
interface AudioFocusController {
    fun requestPttFocus(): Boolean
    fun abandonPttFocus()
}

class AndroidAudioFocusController(
    private val audioManager: AudioManager?
) : AudioFocusController {

    companion object {
        private const val TAG = "AudioFocusController"
    }

    private var focusRequest: AudioFocusRequest? = null

    override fun requestPttFocus(): Boolean {
        if (audioManager == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus change: $focusChange")
                    }
                    .build()

                focusRequest = request
                val res = audioManager.requestAudioFocus(request)
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Audio focus change (legacy): $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error solicitando AudioFocus: ${e.message}")
            false
        }
    }

    override fun abandonPttFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    focusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando AudioFocus: ${e.message}")
        }
    }
}

