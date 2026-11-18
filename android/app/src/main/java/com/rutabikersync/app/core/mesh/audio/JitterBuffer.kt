package com.rutabikersync.app.core.mesh.audio

import java.util.concurrent.ConcurrentSkipListMap

/**
 * Búfer de Jitter adaptativo con Packet Loss Concealment (PLC).
 * Diseñado conforme a §7 del Pliego de Arquitectura:
 * - Profundidad de búfer: 40 ms a 80 ms (2 a 4 frames de 20 ms).
 * - Ordenamiento determinista por [frameIndex].
 * - Detección de pérdida de paquetes: si se detecta un hueco en la secuencia recibida
 *   o expira el tiempo de espera [maxWaitTimeMs], dispara [AudioDecoder.decodePlc] para sintetizar
 *   el audio faltante sin clics ni silencios abruptos.
 */
class JitterBuffer(
    val initialBufferFrames: Int = 2, // 40 ms de buffer inicial
    val maxBufferFrames: Int = 4,     // 80 ms de buffer máximo
    val maxWaitTimeMs: Long = 60L     // 60 ms de tiempo máximo de espera antes de forzar PLC
) {
    private val frames = ConcurrentSkipListMap<Int, ByteArray>()
    
    var expectedFrameIndex: Int = -1
        private set
    var isStarted: Boolean = false
        private set
    var isEndOfStream: Boolean = false
        private set

    private var waitStartTimeMs: Long = 0L

    // Métricas de calidad y PLR
    var totalFramesReceived: Int = 0
        private set
    var totalFramesLost: Int = 0
        private set
    var totalFramesPlc: Int = 0
        private set

    fun reset() {
        frames.clear()
        expectedFrameIndex = -1
        isStarted = false
        isEndOfStream = false
        totalFramesReceived = 0
        totalFramesLost = 0
        totalFramesPlc = 0
        waitStartTimeMs = 0L
    }

    /**
     * Inserta un frame Opus recibido en el búfer.
     */
    fun push(frameIndex: Int, opusPayload: ByteArray, isLast: Boolean = false) {
        totalFramesReceived++
        if (expectedFrameIndex == -1) {
            expectedFrameIndex = frameIndex
        }
        frames[frameIndex] = opusPayload
        if (isLast) {
            isEndOfStream = true
        }
    }

    /**
     * Entrega las siguientes 320 muestras PCM (20 ms) decodificadas.
     * Retorna null si aún está en fase de pre-buffering (acumulando hasta [initialBufferFrames]).
     * Si expira [maxWaitTimeMs] esperando un frame ausente, dispara PLC y avanza [expectedFrameIndex].
     */
    fun pullNextFrame(
        decoder: AudioDecoder,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ShortArray? {
        if (!isStarted) {
            // Esperar a acumular la profundidad inicial del búfer (40 ms = 2 frames) o fin de stream
            if (frames.size >= initialBufferFrames || (isEndOfStream && frames.isNotEmpty())) {
                isStarted = true
            } else {
                return null
            }
        }

        if (frames.isEmpty()) {
            if (isEndOfStream) {
                return null
            }
            // Búfer vacío pero stream no finalizado: evaluar timeout de espera
            if (waitStartTimeMs == 0L) {
                waitStartTimeMs = currentTimeMs
            }
            return if (currentTimeMs - waitStartTimeMs >= maxWaitTimeMs) {
                totalFramesLost++
                totalFramesPlc++
                expectedFrameIndex++
                waitStartTimeMs = 0L
                decoder.decodePlc()
            } else {
                null
            }
        }

        val currentPayload = frames.remove(expectedFrameIndex)
        return if (currentPayload != null) {
            // Frame presente en el búfer: decodificación normal
            expectedFrameIndex++
            waitStartTimeMs = 0L
            decoder.decode(currentPayload)
        } else {
            val hasHigherFrames = frames.keys.any { it > expectedFrameIndex }
            if (hasHigherFrames) {
                // Hueco en la secuencia (paquete perdido en radio BLE con tramas superiores recibidas):
                // activar síntesis PLC inmediatamente
                totalFramesLost++
                totalFramesPlc++
                expectedFrameIndex++
                waitStartTimeMs = 0L
                decoder.decodePlc()
            } else {
                // Aún no llega el siguiente frame: chequear timeout de espera
                if (waitStartTimeMs == 0L) {
                    waitStartTimeMs = currentTimeMs
                }
                if (currentTimeMs - waitStartTimeMs >= maxWaitTimeMs) {
                    totalFramesLost++
                    totalFramesPlc++
                    expectedFrameIndex++
                    waitStartTimeMs = 0L
                    decoder.decodePlc()
                } else {
                    null
                }
            }
        }
    }

    /**
     * Calcula la tasa de pérdida de paquetes (PLR) observada en este búfer.
     */
    fun calculatePlr(): Float {
        val totalExpected = totalFramesReceived + totalFramesLost
        if (totalExpected == 0) return 0f
        return (totalFramesLost.toFloat() / totalExpected.toFloat()) * 100f
    }
}
