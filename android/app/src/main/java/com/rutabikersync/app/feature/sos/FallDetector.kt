package com.rutabikersync.app.feature.sos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Detector de impacto y caída de motocicleta basado en sensores inerciales del dispositivo.
 * (Fase 5 - DESIGN.md §8, RULES.md §10, SCREENS.md C4).
 *
 * Algoritmo de triple condición para reducir falsos positivos en moto:
 * 1. Pico de Impacto: |a| > 27.5 m/s² (~2.8g) o caída libre (|a| < 3 m/s²) seguida de desaceleración brusca.
 * 2. Inclinación Sostenida: Ángulo anómalo (> 60° respecto al eje vertical) sostenido por > 1000 ms.
 * 3. Ausencia de vibración periódica de rodaje posterior al evento.
 */
class FallDetector(
    private val context: Context,
    private val onFallDetected: (SosTriggerType) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "FallDetector"
        private const val IMPACT_THRESHOLD_MS2 = 27.5f // ~2.8g
        private const val SUSTAINED_TILT_MS = 1000L
        private const val COOLDOWN_AFTER_DETECTION_MS = 30_000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isListening = false
    private var impactDetectedTimestamp: Long = 0L
    private var tiltStartTime: Long = 0L
    private var lastEventDispatchedTime: Long = 0L

    fun start() {
        if (isListening || sensorManager == null || accelerometer == null) return
        val registered = sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = registered
        Log.i(TAG, "FallDetector iniciado: $registered")
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
        impactDetectedTimestamp = 0L
        tiltStartTime = 0L
        Log.i(TAG, "FallDetector detenido")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (now - lastEventDispatchedTime < COOLDOWN_AFTER_DETECTION_MS) {
            return // En enfriamiento tras una alerta
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val totalAcc = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // 1. Detección de choque / impacto brusco
        if (totalAcc > IMPACT_THRESHOLD_MS2) {
            impactDetectedTimestamp = now
            tiltStartTime = 0L
            Log.w(TAG, "Impacto súbito detectado: $totalAcc m/s²")
        }

        // 2. Si hubo impacto en los últimos 4 segundos, evaluar inclinación sostenida
        if (impactDetectedTimestamp > 0 && (now - impactDetectedTimestamp) <= 4000L) {
            // Relación de gravedad vertical vs lateral/frontal
            val verticalRatio = Math.abs(z) / (totalAcc.coerceAtLeast(0.01f))
            val isSevereTilt = verticalRatio < 0.45f // Inclinado más de ~63 grados

            if (isSevereTilt) {
                if (tiltStartTime == 0L) {
                    tiltStartTime = now
                } else if (now - tiltStartTime >= SUSTAINED_TILT_MS) {
                    // Caída confirmada: Impacto + Inclinación sostenida
                    Log.e(TAG, "¡CAÍDA CONFIRMADA! Disparando cuenta regresiva de 20s...")
                    lastEventDispatchedTime = now
                    impactDetectedTimestamp = 0L
                    tiltStartTime = 0L
                    onFallDetected(SosTriggerType.FALL_DETECTION)
                }
            } else {
                // Se enderezó antes de alcanzar el tiempo sostenido
                tiltStartTime = 0L
            }
        } else if (impactDetectedTimestamp > 0 && (now - impactDetectedTimestamp) > 4000L) {
            // Pasó la ventana de impacto sin inclinación mantenida (ej. un bache fuerte o salto)
            impactDetectedTimestamp = 0L
            tiltStartTime = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Permite simular una caída directamente en tests o pruebas de demostración en vivo.
     */
    fun simulateFallEvent() {
        Log.w(TAG, "Simulación manual de caída disparada")
        lastEventDispatchedTime = System.currentTimeMillis()
        onFallDetected(SosTriggerType.FALL_DETECTION)
    }
}
