package com.rutabikersync.app.core.mesh

/**
 * Ventana deslizante de gaps de llegada por peer. Se alimenta en cada heartbeat recibido
 * y [timeoutFor] devuelve el timeout recalibrado que debe usar `evaluatePeerTimeouts`.
 *
 * El proveedor de cadencia permite que el suelo del timeout siga al intervalo real del
 * `HeartbeatScheduler` (3/5/10 s nominales, pero ralentizable por carga en campo) sin
 * acoplar este tracker al servicio.
 */
class PeerGapTimeoutTracker(
    private val windowSize: Int = PeerGapTimeoutPolicy.DEFAULT_WINDOW_SIZE,
    private val emissionIntervalProvider: () -> Long = { PeerGapTimeoutPolicy.DEFAULT_EMISSION_INTERVAL_MS }
) {
    private val gaps = mutableMapOf<UShort, ArrayDeque<Long>>()

    @Synchronized
    fun recordGap(shortId: UShort, gapMs: Long) {
        if (gapMs <= 0L) return
        val window = gaps.getOrPut(shortId) { ArrayDeque() }
        window.addLast(gapMs)
        while (window.size > windowSize) window.removeFirst()
    }

    @Synchronized
    fun timeoutFor(shortId: UShort): Long =
        PeerGapTimeoutPolicy.resolveWithCadence(
            gaps[shortId]?.toList().orEmpty(),
            emissionIntervalProvider()
        )

    @Synchronized
    fun reset() {
        gaps.clear()
    }
}
