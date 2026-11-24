package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.MeshRelayEngine.Companion.HEARTBEAT_INTERVAL_MS
import com.rutabikersync.app.core.mesh.MeshRelayEngine.Companion.LOST_TIMEOUT_MS
import kotlin.math.ceil

/**
 * Recalibración del timeout de pérdida de peer a partir de los gaps reales de AdvRx (FIX B)
 * y de la cadencia efectiva de emisión local (Fase 2).
 *
 * En campo se midieron gaps de 48–149 s y una cadencia real de ~18–20 s por latido, muy por
 * encima del tick nominal de 3 s. El percentil 95 de los gaps sigue mandando, pero el suelo
 * se deriva ahora de `3 × intervalo local` para que un scheduler ralentizado no provoque
 * falsas pérdidas. El techo sube de 90 s a 180 s para absorber huecos prolongados sin
 * inventar conectividad: el peer se sigue marcando `isLost` al vencer el timeout.
 */
object PeerGapTimeoutPolicy {
    const val MIN_TIMEOUT_MS: Long = LOST_TIMEOUT_MS
    const val MAX_TIMEOUT_MS: Long = 180_000L
    const val DEFAULT_WINDOW_SIZE: Int = 16
    const val DEFAULT_EMISSION_INTERVAL_MS: Long = HEARTBEAT_INTERVAL_MS

    /** Tres latidos locales como mínimo, siguiendo la regla anti-decaimiento de DESIGN.md. */
    const val CADENCE_FACTOR: Long = 3L

    /** Percentil 95 por rango más cercano sobre una copia ordenada. */
    fun percentile95(gapsMs: List<Long>): Long {
        if (gapsMs.isEmpty()) return MIN_TIMEOUT_MS
        val sorted = gapsMs.sorted()
        val rank = ceil(sorted.size * 0.95).toInt().coerceAtLeast(1)
        return sorted[rank - 1].coerceAtLeast(0L)
    }

    /** Compatibilidad: cadencia nominal de 3 s. */
    fun resolve(gapsMs: List<Long>): Long =
        resolveWithCadence(gapsMs, DEFAULT_EMISSION_INTERVAL_MS)

    /** Timeout efectivo: nunca por debajo de 3 latidos locales ni por encima del techo. */
    fun resolveWithCadence(gapsMs: List<Long>, localIntervalMs: Long): Long {
        val cadenceFloor = localIntervalMs.coerceAtLeast(0L) * CADENCE_FACTOR
        return percentile95(gapsMs)
            .coerceAtLeast(MIN_TIMEOUT_MS)
            .coerceAtLeast(cadenceFloor)
            .coerceAtMost(MAX_TIMEOUT_MS)
    }
}
