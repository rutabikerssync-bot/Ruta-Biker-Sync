package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerPositionValidityTest {
    private val secretKey = "rbs-peer-gps-validity-test-32b!".encodeToByteArray()

    @Test
    fun `peer sin fix previo no se dibuja aunque el heartbeat siga llegando`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)

        // (0,0) es un centinela, nunca una posición: sin fix previo no hay marcador.
        remoteEngine.emitLocalPosition(latitude = 0.0, longitude = 0.0, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertTrue(frame.markers.isEmpty())
        assertFalse(frame.gpsFixed)
        assertEquals(0, frame.ridersCount)

        val track = localEngine.peers.value.getValue(2u)
        assertEquals(0.0, track.lastLatitude, 0.0)
        assertEquals(0.0, track.lastLongitude, 0.0)
        assertEquals(0L, track.lastPositionEpochMs)
        assertFalse(track.isPositionRetained)
        assertFalse(track.isLost) // el heartbeat llegó: el peer sigue en línea

        assertFalse(LiveMapController.isRenderablePeerPosition(0.0, 0.0))
        assertFalse(LiveMapController.isRenderablePeerPosition(Double.NaN, 10.0))

        localEngine.stop()
        remoteEngine.stop()
    }

    @Test
    fun `peer con fallback CDMX sigue sin dibujarse`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)

        // Fallback conocido: no publicarlo como si el peer estuviera en CDMX.
        remoteEngine.emitLocalPosition(latitude = 19.4326, longitude = -99.1332, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val frame = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertTrue(frame.markers.isEmpty())
        assertFalse(frame.gpsFixed)
        assertEquals(0, frame.ridersCount)
        assertFalse(LiveMapController.isRenderablePeerPosition(19.4326, -99.1332))

        localEngine.stop()
        remoteEngine.stop()
    }

    @Test
    fun `peer con (0,0) tras posicion valida conserva el marcador con la ultima posicion`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)
        controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        val latitude = 10.349293
        val longitude = -67.034980
        remoteEngine.emitLocalPosition(latitude = latitude, longitude = longitude, speedKmh = 45, bearingDegrees = 90f, batteryPercent = 85)
        val withFix = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        val peerWithFix = withFix.markers.single { !it.isSelf }
        assertEquals(latitude, peerWithFix.displayLatitude, 0.0)
        assertEquals(longitude, peerWithFix.displayLongitude, 0.0)
        assertNull(peerWithFix.positionAgeSeconds)
        assertTrue(LiveMapController.isRenderablePeerPosition(latitude, longitude))

        // Caduca el fix GPS del emisor: publica el centinela (0,0) pero el peer sigue en línea.
        remoteEngine.emitLocalPosition(latitude = 0.0, longitude = 0.0, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 85)
        val retainedFrame = controller.flushMapFrame(nowEpochMs = 12_000L, force = true)
        val peerRetained = retainedFrame.markers.single { !it.isSelf }
        assertEquals(latitude, peerRetained.displayLatitude, 0.0)
        assertEquals(longitude, peerRetained.displayLongitude, 0.0)
        assertNotNull(peerRetained.positionAgeSeconds)

        val track = localEngine.peers.value.getValue(2u)
        assertEquals(latitude, track.lastLatitude, 0.0)
        assertEquals(longitude, track.lastLongitude, 0.0)
        assertTrue(track.lastPositionEpochMs > 0L)
        assertTrue(track.isPositionRetained)
        assertFalse(track.isLost)

        localEngine.stop()
        remoteEngine.stop()
    }

    @Test
    fun `peer online con posicion retenida sigue contando en ridersCount y solo se pierde por timeout`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)
        controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        remoteEngine.emitLocalPosition(latitude = 10.349293, longitude = -67.034980, speedKmh = 45, bearingDegrees = 90f, batteryPercent = 85)
        assertEquals(2, controller.flushMapFrame(nowEpochMs = 1_000L, force = true).ridersCount)

        // Varios heartbeats con (0,0) a lo largo de ~40 min: el marcador y el contador se mantienen.
        repeat(3) { index ->
            remoteEngine.emitLocalPosition(latitude = 0.0, longitude = 0.0, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 85)
            val stale = controller.flushMapFrame(nowEpochMs = (index + 1) * 10_000L, force = true)
            assertEquals(2, stale.ridersCount)
            assertNotNull(stale.markers.single { !it.isSelf }.positionAgeSeconds)
            assertFalse(localEngine.peers.value.getValue(2u).isLost)
        }

        // Regresión: sin heartbeats durante >15 s el peer sí se declara perdido.
        localEngine.evaluatePeerTimeouts(System.currentTimeMillis() + 20_000L)
        val lostFrame = controller.flushMapFrame(nowEpochMs = 60_000L, force = true)
        assertTrue(localEngine.peers.value.getValue(2u).isLost)
        assertEquals(1, lostFrame.ridersCount)

        localEngine.stop()
        remoteEngine.stop()
    }

    @Test
    fun `peer con GPS valido se dibuja en su posicion real`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)
        controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        val latitude = 10.349293
        val longitude = -67.034980
        remoteEngine.emitLocalPosition(latitude = latitude, longitude = longitude, speedKmh = 45, bearingDegrees = 90f, batteryPercent = 85)
        val frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)

        val remote = frame.markers.single { !it.isSelf }
        assertEquals(latitude, remote.displayLatitude, 0.0)
        assertEquals(longitude, remote.displayLongitude, 0.0)
        assertEquals(latitude, remote.targetLatitude, 0.0)
        assertEquals(longitude, remote.targetLongitude, 0.0)
        assertEquals(2, frame.ridersCount)
        assertTrue(LiveMapController.isRenderablePeerPosition(latitude, longitude))

        localEngine.stop()
        remoteEngine.stop()
    }
}
