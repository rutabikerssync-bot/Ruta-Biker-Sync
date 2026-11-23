package com.rutabikersync.app.core.mesh

/**
 * Calibración del timeout de pérdida de peer a partir de la distribución real de llegadas.
 *
 * FIX B (2026-11-21) derivó 40,5 s de un intervalo observado de 27 s con factor 1,5. La medición
 * de 2026-11-23 mostró gaps reales de 48–149 s en Redmi, por lo que ese valor fijo generaba
 * `isLost` transitorios con señal BLE todavía presente. Este calibrador conserva una ventana
 * deslizante de los últimos gaps entre llegadas de paquetes de peers y calcula el percentil 95,
 * acotado entre [MIN_TIMEOUT_MS] (40,5 s, regla anti-decaimiento original) y [MAX_TIMEOUT_MS]
 * (90 s, techo de seguridad para no retrasar la detección de isla/SOS, Decisión 3.4 C).
 *
 * Es una clase pura y determinista: no usa reloj, coroutines ni Android. El motor registra los
 * gaps; la UI no consulta esta clase.
 */
class PeerTimeoutCalibrator(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val percentile: Double = DEFAULT_PERCENTILE
) {
    companion object {
        const val DEFAULT_WINDOW_SIZE: Int = 20
        const val DEFAULT_PERCENTILE: Double = 0.95

        /** Muestras mínimas antes de aplicar el percentil; por debajo se usa el mínimo histórico. */
        const val MIN_GAP_SAMPLES: Int = 5

        /** Regla anti-decaimiento de FIX B: 27 s × 3/2 = 40,5 s. */
        val MIN_TIMEOUT_MS: Long = MeshRelayEngine.LOST_TIMEOUT_MS

        /** Techo de seguridad: nunca 150 s, que retrasaría 2,5 min la detección de isla/SOS. */
        const val MAX_TIMEOUT_MS: Long = 90_000L
    }

    private val gapsMs = ArrayDeque<Long>()

    init {
        require(windowSize >= MIN_GAP_SAMPLES) { "windowSize debe ser >= $MIN_GAP_SAMPLES" }
        require(percentile in 0.5..1.0) { "percentile debe estar en 0.5..1.0" }
    }

    /** Registra un gap observado entre dos llegadas sucesivas. Los valores no positivos se ignoran. */
    fun recordGap(gapMs: Long) {
        if (gapMs <= 0L) return
        gapsMs.addLast(gapMs)
        while (gapsMs.size > windowSize) gapsMs.removeFirst()
    }

    /**
     * Timeout efectivo: percentil 95 de la ventana, nunca por debajo de 40,5 s ni por encima de 90 s.
     * Con menos de [MIN_GAP_SAMPLES] muestras devuelve directamente el mínimo histórico.
     */
    fun effectiveTimeoutMs(): Long {
        if (gapsMs.size < MIN_GAP_SAMPLES) return MIN_TIMEOUT_MS
        val sorted = gapsMs.sorted()
        // Percentil "upper": índice ceil(p·(n-1)). Sesgado hacia arriba a propósito para no
        // subestimar la cola (un solo gap extremo en la ventana debe elevar el timeout al techo).
        val index = kotlin.math.ceil(percentile * (sorted.size - 1)).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[index].coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    fun gapCount(): Int = gapsMs.size

    fun clear() {
        gapsMs.clear()
    }
}
