package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSignalGraceTrackerTest {

    private val peer = 22_873u.toUShort()

    @Test
    fun `peer con gap de 50s sigue contando durante 60s y luego se declara perdido`() {
        val tracker = PeerSignalGraceTracker()
        // El gap de 50 s hace que el motor marque isLost en este instante.
        val lossDetectedAt = 1_000_000L

        assertTrue(tracker.isInGrace(peer, isLost = true, nowEpochMs = lossDetectedAt))
        assertTrue(tracker.isInGrace(peer, isLost = true, nowEpochMs = lossDetectedAt + 50_000L))

        // Justo antes de los 60 s desde la detección sigue en gracia y contando en el HUD.
        assertTrue(tracker.isInGrace(peer, isLost = true, nowEpochMs = lossDetectedAt + 59_999L))

        // A los 60 s se agota la gracia: el marcador pasa a OFF_MESH_LOST y deja de contar.
        assertFalse(tracker.isInGrace(peer, isLost = true, nowEpochMs = lossDetectedAt + PeerSignalGraceTracker.GRACE_MS))
        assertFalse(tracker.isInGrace(peer, isLost = true, nowEpochMs = lossDetectedAt + 61_000L))
    }

    @Test
    fun `peer con senal restored no entra en gracia`() {
        val tracker = PeerSignalGraceTracker()

        assertFalse(tracker.isInGrace(peer, isLost = false, nowEpochMs = 1_000L))
        assertFalse(tracker.isInGrace(peer, isLost = false, nowEpochMs = 70_000L))
    }

    @Test
    fun `recuperacion tras la gracia limpia el estado`() {
        val tracker = PeerSignalGraceTracker()
        val lostAt = 500_000L

        assertTrue(tracker.isInGrace(peer, isLost = true, nowEpochMs = lostAt + 10_000L))
        assertFalse(tracker.isInGrace(peer, isLost = false, nowEpochMs = lostAt + 20_000L))
        // Una nueva caida inicia una gracia nueva, no hereda la anterior.
        assertTrue(tracker.isInGrace(peer, isLost = true, nowEpochMs = lostAt + 30_000L))
    }
}
