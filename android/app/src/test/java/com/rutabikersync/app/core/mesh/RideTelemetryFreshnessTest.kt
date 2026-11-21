package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RideTelemetryFreshnessTest {

    @Test
    fun `un fix expirado inhibe coordenadas BLE sin detener la malla`() = runTest {
        val startMs = 100_000L
        val controller = RideSessionController(
            scope = backgroundScope,
            requireValidPositionForHeartbeat = true
        )
        controller.startRide(
            rideId = "gps-freshness-test",
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false,
            nowEpochMs = startMs
        )
        controller.updateLocalTelemetry(
            latitude = 10.349246,
            longitude = -67.034904,
            speedKmh = 0,
            observedAtEpochMs = startMs
        )

        controller.emitHeartbeatNow(nowEpochMs = startMs + 1_000L)
        assertEquals(1, controller.relayEngine?.metrics?.value?.packetsEmitted)

        controller.emitHeartbeatNow(
            nowEpochMs = startMs + LocalGpsPositionPolicy.MAX_FIX_AGE_MS + 1_000L
        )
        assertEquals(
            "Sin fix fresco debe publicarse la identidad, no la coordenada vencida",
            2,
            controller.relayEngine?.metrics?.value?.packetsEmitted
        )
        assertEquals(true, controller.relayEngine?.isRunning)

        controller.updateLocalTelemetry(
            latitude = 10.349250,
            longitude = -67.034900,
            speedKmh = 1,
            observedAtEpochMs = startMs + LocalGpsPositionPolicy.MAX_FIX_AGE_MS + 2_000L
        )
        controller.emitHeartbeatNow(nowEpochMs = startMs + LocalGpsPositionPolicy.MAX_FIX_AGE_MS + 2_000L)
        assertEquals(3, controller.relayEngine?.metrics?.value?.packetsEmitted)

        controller.stopAll()
    }

    @Test
    fun `sin fix fresco el peer recibe identidad con coordenada centinela`() = runTest {
        val startMs = 100_000L
        val transmitter = RideSessionController(
            scope = backgroundScope,
            requireValidPositionForHeartbeat = true
        )
        val receiver = RideSessionController(
            scope = backgroundScope,
            requireValidPositionForHeartbeat = true
        )
        transmitter.startRide(
            rideId = "gps-sentinel-test-tx",
            localShortId = 1u,
            forceSimulatedTransport = true,
            expectedPeerShortIds = setOf(2u),
            enablePeriodicHeartbeatLoop = false,
            nowEpochMs = startMs
        )
        receiver.startRide(
            rideId = "gps-sentinel-test-rx",
            localShortId = 2u,
            forceSimulatedTransport = true,
            expectedPeerShortIds = setOf(1u),
            enablePeriodicHeartbeatLoop = false,
            nowEpochMs = startMs
        )
        transmitter.updateLocalTelemetry(
            latitude = 10.349246,
            longitude = -67.034904,
            speedKmh = 0,
            observedAtEpochMs = startMs
        )

        transmitter.emitHeartbeatNow(
            nowEpochMs = startMs + LocalGpsPositionPolicy.MAX_FIX_AGE_MS + 1_000L
        )

        val track = receiver.relayEngine?.peers?.value?.get(1u)
        assertEquals(true, track != null)
        assertEquals(0.0, track!!.lastLatitude, 0.0)
        assertEquals(0.0, track.lastLongitude, 0.0)
        assertEquals(false, MeshTelemetry.isValidPosition(track.lastLatitude, track.lastLongitude))

        transmitter.stopAll()
        receiver.stopAll()
    }
}
