package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

/** FIX B + Fase 2 · recalibración del timeout por percentil 95 y cadencia real. */
class PeerGapTimeoutPolicyTest {

    @Test
    fun `percentil95 de 80 s fija el timeout en 80 s`() {
        val gaps = listOf(27_000L, 30_000L, 48_000L, 80_000L)
        assertEquals(80_000L, PeerGapTimeoutPolicy.percentile95(gaps))
        assertEquals(80_000L, PeerGapTimeoutPolicy.resolve(gaps))
    }

    @Test
    fun `percentil95 de 30 s respeta el minimo de 40_5 s`() {
        val gaps = listOf(27_000L, 30_000L)
        assertEquals(30_000L, PeerGapTimeoutPolicy.percentile95(gaps))
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, PeerGapTimeoutPolicy.resolve(gaps))
    }

    @Test
    fun `percentil95 de 120 s respeta el techo de 180 s`() {
        val gaps = listOf(27_000L, 120_000L)
        assertEquals(120_000L, PeerGapTimeoutPolicy.percentile95(gaps))
        assertEquals(120_000L, PeerGapTimeoutPolicy.resolve(gaps))
    }

    @Test
    fun `gap de 200 s respeta el techo de 180 s`() {
        assertEquals(180_000L, PeerGapTimeoutPolicy.resolve(listOf(200_000L)))
    }

    @Test
    fun `sin historial de gaps se usa el timeout derivado`() {
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, PeerGapTimeoutPolicy.resolve(emptyList()))
    }

    @Test
    fun `cadencia real de 18 s eleva el suelo a 54 s sin inflar el percentil`() {
        assertEquals(54_000L, PeerGapTimeoutPolicy.resolveWithCadence(emptyList(), 18_000L))
        assertEquals(
            150_000L,
            PeerGapTimeoutPolicy.resolveWithCadence(listOf(150_000L), 18_000L)
        )
    }

    @Test
    fun `tracker usa la cadencia inyectada`() {
        val tracker = PeerGapTimeoutTracker(emissionIntervalProvider = { 18_000L })
        assertEquals(54_000L, tracker.timeoutFor(2u))
        tracker.recordGap(2u, 100_000L)
        assertEquals(100_000L, tracker.timeoutFor(2u))
    }

    @Test
    fun `tracker conserva solo la ventana y permite reiniciar`() {
        val tracker = PeerGapTimeoutTracker(windowSize = 2)
        tracker.recordGap(2u, 30_000L)
        tracker.recordGap(2u, 80_000L)
        assertEquals(80_000L, tracker.timeoutFor(2u))
        tracker.recordGap(2u, 27_000L)
        assertEquals(80_000L, tracker.timeoutFor(2u)) // la ventana [80k, 27k] aún recuerda el gap
        tracker.recordGap(2u, 27_000L)
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, tracker.timeoutFor(2u)) // ahora [27k, 27k]
        tracker.recordGap(2u, -5L)
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, tracker.timeoutFor(2u))
        tracker.reset()
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, tracker.timeoutFor(2u))
    }
}
