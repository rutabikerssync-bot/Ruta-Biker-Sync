package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.MeshRelayEngine.Companion.LOST_TIMEOUT_MS
import kotlin.math.ceil

/**
 * Recalibración del timeout de pérdida de peer a partir de los gaps reales de AdvRx (FIX B).
 *
 * El timeout fijo de 40,5 s deriva del intervalo observado de 27 s × 1,5, pero en campo se
 * midieron gaps de 48–149 s. Se usa el percentil 95 de los últimos N gaps, con un mínimo
 * de 40,5 s (no bajar de la referencia medida) y un techo de 90 s (no subir a 150 s,
 * porque retrasaría la detección de isla y de SOS).
 */
object PeerGapTimeoutPolicy {
    const val MIN_TIMEOUT_MS: Long = LOST_TIMEOUT_MS
    const val MAX_TIMEOUT_MS: Long = 90_000L
    const val DEFAULT_WINDOW_SIZE: Int = 16

    /** Percentil 95 por rango más cercano sobre una copia ordenada. */
    fun percentile95(gapsMs: List<Long>): Long {
        if (gapsMs.isEmpty()) return MIN_TIMEOUT_MS
        val sorted = gapsMs.sorted()
        val rank = ceil(sorted.size * 0.95).toInt().coerceAtLeast(1)
        return sorted[rank - 1].coerceAtLeast(0L)
    }

    /** Timeout efectivo: nunca por debajo del mínimo ni por encima del techo. */
    fun resolve(gapsMs: List<Long>): Long =
        percentile95(gapsMs).coerceAtLeast(MIN_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS)
}
