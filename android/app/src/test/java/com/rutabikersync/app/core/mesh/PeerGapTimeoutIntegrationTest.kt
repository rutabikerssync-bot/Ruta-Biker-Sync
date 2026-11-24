package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIX B · el timeout recalibrado se aplica en el motor y registra el instante de pérdida. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerGapTimeoutIntegrationTest {

    private val secretKey = "fixb-peer-timeout-secret-key-32!".encodeToByteArray()

    private fun heartbeat(shortId: UShort, sequence: Int): MeshPacket = MeshPacket.createSigned(
        shortId = shortId,
        latitude = 19.0,
        longitude = -99.0,
        speedKmh = 50,
        bearingDegrees = 0f,
        batteryPercent = 90,
        sequenceNumber = sequence,
        ttl = 3,
        rideSecretKey = secretKey
    )

    @Test
    fun `gap de 80 s eleva el timeout a 80 s y retrasa la perdida`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
        val t0 = 1_000_000L
        val t1 = t0 + 80_000L
        engine.onRawPacketReceived(heartbeat(2u, 1).toByteArray(), nowEpochMs = t0)
        engine.onRawPacketReceived(heartbeat(2u, 2).toByteArray(), nowEpochMs = t1)

        // Con el timeout fijo de 40,5 s ya estaría perdido a los 50 s.
        assertTrue(engine.evaluatePeerTimeouts(nowEpochMs = t1 + 50_000L).isEmpty())
        assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = t1 + 80_000L))
        engine.stop()
    }

    @Test
    fun `gap de 50 s marca perdido y registra el instante para la gracia`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
        val t0 = 1_000_000L
        val lossAt = t0 + 50_000L
        engine.onRawPacketReceived(heartbeat(2u, 1).toByteArray(), nowEpochMs = t0)

        assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = lossAt))
        val track = engine.peers.value.getValue(2u)
        assertTrue(track.isLost)
        assertEquals(lossAt, track.lostSinceEpochMs)
        engine.stop()
    }

    @Test
    fun `sin gaps historicos el timeout no baja de 40_5 s`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
        val t0 = 1_000_000L
        engine.onRawPacketReceived(heartbeat(2u, 1).toByteArray(), nowEpochMs = t0)

        assertTrue(engine.evaluatePeerTimeouts(t0 + MeshRelayEngine.LOST_TIMEOUT_MS - 1L).isEmpty())
        assertEquals(
            listOf(2u.toUShort()),
            engine.evaluatePeerTimeouts(t0 + MeshRelayEngine.LOST_TIMEOUT_MS)
        )
        engine.stop()
    }
}
