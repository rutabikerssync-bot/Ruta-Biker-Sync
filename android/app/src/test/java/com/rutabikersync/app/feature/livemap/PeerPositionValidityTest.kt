package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerPositionValidityTest {
    private val secretKey = "rbs-peer-gps-validity-test-32b!".encodeToByteArray()

    @Test
    fun `peer sin GPS real o con fallback CDMX no se dibuja`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)

        // (0,0) también es un peer sin fix: no es coordenada de tráfico real.
        remoteEngine.emitLocalPosition(latitude = 0.0, longitude = 0.0, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val noFixFrame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertTrue(noFixFrame.markers.isEmpty())
        assertFalse(noFixFrame.gpsFixed)

        // Fallback conocido: no publicarlo como si el peer estuviera en CDMX.
        remoteEngine.emitLocalPosition(latitude = 19.4326, longitude = -99.1332, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val fallbackFrame = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertTrue(fallbackFrame.markers.isEmpty())
        assertFalse(fallbackFrame.gpsFixed)
        assertEquals(0, fallbackFrame.ridersCount)

        assertFalse(LiveMapController.isRenderablePeerPosition(0.0, 0.0))
        assertFalse(LiveMapController.isRenderablePeerPosition(19.4326, -99.1332))
        assertFalse(LiveMapController.isRenderablePeerPosition(Double.NaN, 10.0))

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
