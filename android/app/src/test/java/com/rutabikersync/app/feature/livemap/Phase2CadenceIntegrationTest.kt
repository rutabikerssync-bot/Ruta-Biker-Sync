package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.protocol.MeshPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 2 · el HUD tolera la cadencia ralentizada medida en campo (~18 s por latido)
 * sin declarar pérdida prematura, y sigue cayendo a "1 rider" cuando el enlace
 * realmente desaparece. No se inventa conectividad: se alarga la tolerancia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase2CadenceIntegrationTest {
    private val secretKey = "phase2-cadence-secret-key-32bytes!!".encodeToByteArray()

    private fun heartbeat(sequence: Int): MeshPacket = MeshPacket.createSigned(
        shortId = 2u,
        latitude = 10.349293,
        longitude = -67.034980,
        speedKmh = 45,
        bearingDegrees = 90f,
        batteryPercent = 85,
        sequenceNumber = sequence,
        ttl = 3,
        rideSecretKey = secretKey
    )

    @Test
    fun `cadencia 18 s con gap 150 s mantiene 2 riders durante timeout y gracia derivada`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MeshRelayEngine(
                localShortId = 1u,
                rideSecretKey = secretKey,
                heartbeatIntervalProvider = { 18_000L }
            )
            val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
            controller.bindToMeshEngine(engine)
            controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

            val t0 = 1_000_000L
            val t1 = t0 + 150_000L
            engine.onRawPacketReceived(heartbeat(1).toByteArray(), nowEpochMs = t0)
            engine.onRawPacketReceived(heartbeat(2).toByteArray(), nowEpochMs = t1)

            assertEquals(2, controller.flushMapFrame(nowEpochMs = t1, force = true).ridersCount)
            assertTrue(engine.evaluatePeerTimeouts(nowEpochMs = t1 + 100_000L).isEmpty())

            assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = t1 + 150_000L))
            val track = engine.peers.value.getValue(2u)
            assertEquals(150_000L, track.effectiveTimeoutMs)
            assertEquals(120_000L, PeerGracePolicy.graceMsFor(track.effectiveTimeoutMs))

            // Aún dentro de la gracia derivada: el marcador sigue visible y el HUD no cae.
            assertEquals(2, controller.flushMapFrame(nowEpochMs = t1 + 269_999L, force = true).ridersCount)
            // Agotada la gracia: cae a 1 rider (no se inventa enlace).
            assertEquals(1, controller.flushMapFrame(nowEpochMs = t1 + 270_001L, force = true).ridersCount)
            engine.stop()
        }

    @Test
    fun `gap de 200 s cae a 1 rider solo tras timeout de 180 s y gracia de 120 s`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MeshRelayEngine(
                localShortId = 1u,
                rideSecretKey = secretKey,
                heartbeatIntervalProvider = { 18_000L }
            )
            val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
            controller.bindToMeshEngine(engine)
            controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

            val t0 = 1_000_000L
            val t1 = t0 + 200_000L
            engine.onRawPacketReceived(heartbeat(1).toByteArray(), nowEpochMs = t0)
            engine.onRawPacketReceived(heartbeat(2).toByteArray(), nowEpochMs = t1)

            assertEquals(180_000L, engine.peers.value.getValue(2u).effectiveTimeoutMs)
            assertTrue(engine.evaluatePeerTimeouts(nowEpochMs = t1 + 179_999L).isEmpty())
            assertEquals(2, controller.flushMapFrame(nowEpochMs = t1 + 179_999L, force = true).ridersCount)

            assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = t1 + 180_000L))
            assertEquals(2, controller.flushMapFrame(nowEpochMs = t1 + 299_999L, force = true).ridersCount)
            assertEquals(1, controller.flushMapFrame(nowEpochMs = t1 + 300_001L, force = true).ridersCount)
            engine.stop()
        }
}
