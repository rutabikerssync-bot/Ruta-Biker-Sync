package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerTimeoutCalibratorTest {

    private fun calibratorWith(gapsSeconds: List<Long>): PeerTimeoutCalibrator {
        val calibrator = PeerTimeoutCalibrator()
        gapsSeconds.forEach { calibrator.recordGap(it * 1_000L) }
        return calibrator
    }

    @Test
    fun `percentil95 de 80s da timeout de 80s`() {
        // 20 gaps: 19 de 1 s y 1 de 80 s -> el percentil 95 upper toma el valor extremo (80 s).
        val gaps = List(19) { 1L } + listOf(80L)
        val calibrator = calibratorWith(gaps)

        assertEquals(PeerTimeoutCalibrator.DEFAULT_WINDOW_SIZE, calibrator.gapCount())
        assertEquals(80_000L, calibrator.effectiveTimeoutMs())
    }

    @Test
    fun `percentil95 de 30s respeta el minimo de 40_5s`() {
        val calibrator = calibratorWith(List(20) { 30L })

        assertEquals(PeerTimeoutCalibrator.MIN_TIMEOUT_MS, calibrator.effectiveTimeoutMs())
        assertEquals(MeshRelayEngine.LOST_TIMEOUT_MS, calibrator.effectiveTimeoutMs())
    }

    @Test
    fun `percentil95 de 120s respeta el techo de 90s`() {
        val calibrator = calibratorWith(List(20) { 120L })

        assertEquals(PeerTimeoutCalibrator.MAX_TIMEOUT_MS, calibrator.effectiveTimeoutMs())
    }

    @Test
    fun `ventana deslizante conserva solo los ultimos 20 gaps`() {
        val calibrator = PeerTimeoutCalibrator()
        repeat(25) { calibrator.recordGap(10_000L) }

        assertEquals(PeerTimeoutCalibrator.DEFAULT_WINDOW_SIZE, calibrator.gapCount())
        assertEquals(PeerTimeoutCalibrator.MIN_TIMEOUT_MS, calibrator.effectiveTimeoutMs())
    }

    @Test
    fun `menos de cinco muestras usa el minimo historico`() {
        val calibrator = calibratorWith(listOf(100L, 100L, 100L, 100L))

        assertEquals(4, calibrator.gapCount())
        assertEquals(PeerTimeoutCalibrator.MIN_TIMEOUT_MS, calibrator.effectiveTimeoutMs())
    }
}
