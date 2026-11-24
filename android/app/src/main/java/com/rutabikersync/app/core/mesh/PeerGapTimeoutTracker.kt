package com.rutabikersync.app.core.mesh

/**
 * Ventana deslizante de gaps de llegada por peer. Se alimenta en cada heartbeat recibido
 * y [timeoutFor] devuelve el timeout recalibrado que debe usar `evaluatePeerTimeouts`.
 */
class PeerGapTimeoutTracker(
    private val windowSize: Int = PeerGapTimeoutPolicy.DEFAULT_WINDOW_SIZE
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
        PeerGapTimeoutPolicy.resolve(gaps[shortId]?.toList().orEmpty())

    @Synchronized
    fun reset() {
        gaps.clear()
    }
}
