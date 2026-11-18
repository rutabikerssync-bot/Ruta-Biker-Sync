package com.rutabikersync.app.feature.sos

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gestor de la cuenta regresiva de 20 segundos para alertas de emergencia y falsa alarma.
 * (Fase 5 - DESIGN.md §8, SCREENS.md C4).
 */
class SosCountdownManager(
    private val context: Context? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val onSosConfirmed: (SosTriggerType) -> Unit = {}
) {
    companion object {
        private const val TAG = "SosCountdownManager"
        const val DEFAULT_COUNTDOWN_SECONDS = 20

        @Volatile
        private var instance: SosCountdownManager? = null

        fun getInstance(
            context: Context? = null,
            onSosConfirmed: (SosTriggerType) -> Unit = {}
        ): SosCountdownManager {
            return instance ?: synchronized(this) {
                instance ?: SosCountdownManager(
                    context = context?.applicationContext ?: context,
                    onSosConfirmed = onSosConfirmed
                ).also { instance = it }
            }
        }
    }

    sealed class State {
        object Idle : State()
        data class Counting(val remainingSeconds: Int, val triggerType: SosTriggerType) : State()
        data class Confirmed(val triggerType: SosTriggerType) : State()
        data class Cancelled(val triggerType: SosTriggerType, val reason: String) : State()
    }

    private val _countdownState = MutableStateFlow<State>(State.Idle)
    val countdownState: StateFlow<State> = _countdownState.asStateFlow()

    private var countdownJob: Job? = null

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = context?.let { ctx ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Inicia la cuenta regresiva de 20 segundos.
     */
    fun startCountdown(triggerType: SosTriggerType, initialSeconds: Int = DEFAULT_COUNTDOWN_SECONDS) {
        if (_countdownState.value is State.Counting) {
            Log.w(TAG, "Ya existe una cuenta regresiva activa en curso")
            return
        }

        countdownJob?.cancel()
        Log.w(TAG, "Iniciando cuenta regresiva SOS ($initialSeconds s) por $triggerType")
        _countdownState.value = State.Counting(initialSeconds, triggerType)

        countdownJob = coroutineScope.launch {
            var seconds = initialSeconds
            while (seconds > 0) {
                _countdownState.value = State.Counting(seconds, triggerType)
                vibrateAlertTick(seconds)
                delay(1000L)
                seconds--
            }

            // Si expiraron los 20 segundos sin cancelación: Confirmar SOS
            Log.e(TAG, "Cuenta regresiva de 20s expirada. CONFIRMANDO SOS...")
            _countdownState.value = State.Confirmed(triggerType)
            vibrateSosConfirmed()
            onSosConfirmed(triggerType)
        }
    }

    /**
     * Cancela la cuenta regresiva (Falsa alarma, el usuario está bien).
     */
    fun cancelCountdown(reason: String = "Cancelado por el usuario (Estoy bien)") {
        val current = _countdownState.value
        if (current !is State.Counting) return

        val trigger = current.triggerType
        Log.i(TAG, "Cuenta regresiva cancelada: $reason")
        countdownJob?.cancel()
        countdownJob = null

        _countdownState.value = State.Cancelled(trigger, reason)
        vibrateCancelled()

        // Volver a reposo tras breve feedback
        coroutineScope.launch {
            delay(1500L)
            _countdownState.value = State.Idle
        }
    }

    /**
     * Confirmación manual inmediata sin esperar los 20 segundos restantes.
     */
    fun confirmImmediately() {
        val current = _countdownState.value
        val trigger = if (current is State.Counting) current.triggerType else SosTriggerType.MANUAL

        Log.e(TAG, "SOS confirmado inmediatamente por el usuario")
        countdownJob?.cancel()
        countdownJob = null

        _countdownState.value = State.Confirmed(trigger)
        vibrateSosConfirmed()
        onSosConfirmed(trigger)
    }

    /**
     * Resetea el estado a reposo.
     */
    fun resetToIdle() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownState.value = State.Idle
    }

    private fun vibrateAlertTick(secondsLeft: Int) {
        vibrator?.let { v ->
            if (!v.hasVibrator()) return@let
            val intensity = if (secondsLeft <= 5) 255 else 180
            val duration = if (secondsLeft <= 5) 400L else 200L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, intensity))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        }
    }

    private fun vibrateSosConfirmed() {
        vibrator?.let { v ->
            if (!v.hasVibrator()) return@let
            // Patrón SOS en código morse: · · · — — — · · ·
            val timings = longArrayOf(0, 150, 100, 150, 100, 150, 200, 400, 100, 400, 100, 400, 200, 150, 100, 150, 100, 150)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        }
    }

    private fun vibrateCancelled() {
        vibrator?.let { v ->
            if (!v.hasVibrator()) return@let
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100L, 80))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(100L)
            }
        }
    }
}
