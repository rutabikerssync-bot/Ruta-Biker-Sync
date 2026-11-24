package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.announcer.AnnouncerEventKind
import com.rutabikersync.app.core.announcer.AnnouncerTracker
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.PeerGracePolicy
import com.rutabikersync.app.core.mesh.PeerPresencePolicy
import com.rutabikersync.app.core.mesh.PeerPresenceState
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

    @Test
    fun `tres estados coherentes entre HUD mapa notificacion y announcer`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MeshRelayEngine(
                localShortId = 1u,
                rideSecretKey = secretKey,
                heartbeatIntervalProvider = { 18_000L }
            )
            val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
            controller.bindToMeshEngine(engine)
            controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)
            val tracker = AnnouncerTracker()

            val t0 = 1_000_000L
            engine.onRawPacketReceived(heartbeat(1).toByteArray(), nowEpochMs = t0)
            val activeTrack = engine.peers.value.getValue(2u)
            assertEquals(PeerPresenceState.ACTIVE, PeerPresencePolicy.stateFor(activeTrack, t0))
            assertEquals(2, controller.flushMapFrame(nowEpochMs = t0, force = true).ridersCount)
            assertEquals("", PeerPresencePolicy.notificationSuffix(0, 0))
            assertTrue(tracker.onPeerMapUpdated(engine.peers.value, t0).isEmpty())

            // Timeout recalibrado = 150 s (gap medido). Al vencer entra en gracia, no en pérdida.
            val t1 = t0 + 150_000L
            engine.onRawPacketReceived(heartbeat(2).toByteArray(), nowEpochMs = t1)
            val lossAt = t1 + 150_000L
            assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = lossAt))
            val staleTrack = engine.peers.value.getValue(2u)
            assertEquals(PeerPresenceState.STALE_GRACE, PeerPresencePolicy.stateFor(staleTrack, lossAt))

            val staleFrame = controller.flushMapFrame(nowEpochMs = lossAt, force = true)
            assertEquals(2, staleFrame.ridersCount)
            assertEquals(
                RiderRouteStatus.STALE_GRACE,
                staleFrame.markers.first { it.shortId == 2u.toUShort() }.routeStatus
            )
            assertEquals(" · 1 sin señal temporal", PeerPresencePolicy.notificationSuffix(1, 0))
            assertTrue(tracker.onPeerMapUpdated(engine.peers.value, lossAt).isEmpty())

            // Agotada la gracia: las tres vistas y el announcer coinciden en LOST.
            val lostAt = lossAt + PeerGracePolicy.graceMsFor(staleTrack.effectiveTimeoutMs)
            assertEquals(PeerPresenceState.LOST, PeerPresencePolicy.stateFor(staleTrack, lostAt))
            val lostFrame = controller.flushMapFrame(nowEpochMs = lostAt, force = true)
            assertEquals(1, lostFrame.ridersCount)
            assertEquals(
                RiderRouteStatus.OFF_MESH_LOST,
                lostFrame.markers.first { it.shortId == 2u.toUShort() }.routeStatus
            )
            assertEquals(" · 1 sin señal", PeerPresencePolicy.notificationSuffix(0, 1))
            val transitions = tracker.onPeerMapUpdated(engine.peers.value, lostAt)
            assertEquals(1, transitions.size)
            assertEquals(AnnouncerEventKind.PEER_LOST, transitions.first().kind)
            engine.stop()
        }

    @Test
    fun `recuperacion durante gracia vuelve a ACTIVE sin anunciar reconexion`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MeshRelayEngine(
                localShortId = 1u,
                rideSecretKey = secretKey,
                heartbeatIntervalProvider = { 18_000L }
            )
            val tracker = AnnouncerTracker()
            val t0 = 1_000_000L
            val t1 = t0 + 150_000L
            engine.onRawPacketReceived(heartbeat(1).toByteArray(), nowEpochMs = t0)
            engine.onRawPacketReceived(heartbeat(2).toByteArray(), nowEpochMs = t1)
            val lossAt = t1 + 150_000L
            assertEquals(listOf(2u.toUShort()), engine.evaluatePeerTimeouts(nowEpochMs = lossAt))
            assertTrue(tracker.onPeerMapUpdated(engine.peers.value, lossAt).isEmpty())

            // Primer latido tras la pérdida: sigue en gracia por la histéresis de 2 latidos.
            val firstRecoveryAt = lossAt + 1_000L
            engine.onRawPacketReceived(heartbeat(3).toByteArray(), nowEpochMs = firstRecoveryAt)
            val oneBeat = engine.peers.value.getValue(2u)
            assertEquals(
                PeerPresenceState.STALE_GRACE,
                PeerPresencePolicy.stateFor(oneBeat, firstRecoveryAt)
            )
            assertTrue(tracker.onPeerMapUpdated(engine.peers.value, firstRecoveryAt).isEmpty())

            // Segundo latido: ACTIVE sin PEER_BACK (nunca hubo pérdida confirmada).
            val secondRecoveryAt = lossAt + 2_000L
            engine.onRawPacketReceived(heartbeat(4).toByteArray(), nowEpochMs = secondRecoveryAt)
            val recovered = engine.peers.value.getValue(2u)
            assertEquals(
                PeerPresenceState.ACTIVE,
                PeerPresencePolicy.stateFor(recovered, secondRecoveryAt)
            )
            assertTrue(
                tracker.onPeerMapUpdated(engine.peers.value, secondRecoveryAt)
                    .none { it.kind == AnnouncerEventKind.PEER_BACK }
            )
            engine.stop()
        }

    @Test
    fun `rider en movimiento con perdidas intermitentes no genera desconexiones falsas`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey)
            val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
            controller.bindToMeshEngine(engine)
            controller.corridorReferenceLon = -67.034980
            controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

            fun movingHeartbeat(sequence: Int, lat: Double, speed: Int): MeshPacket = MeshPacket.createSigned(
                shortId = 2u,
                latitude = lat,
                longitude = -67.034980,
                speedKmh = speed,
                bearingDegrees = 90f,
                batteryPercent = 85,
                sequenceNumber = sequence,
                ttl = 3,
                rideSecretKey = secretKey
            )

            val t0 = 1_000_000L
            val envios = listOf(
                t0 to 10.349293,
                t0 + 20_000L to 10.349340,
                t0 + 40_000L to 10.349387,
                // Un gap de 60 s eleva el timeout a 60 s; no basta para declarar pérdida.
                t0 + 100_000L to 10.349480,
                t0 + 120_000L to 10.349527,
                t0 + 140_000L to 10.349574
            )
            envios.forEachIndexed { index, (whenMs, lat) ->
                engine.onRawPacketReceived(movingHeartbeat(index + 1, lat, 50).toByteArray(), nowEpochMs = whenMs)
            }
            engine.evaluatePeerTimeouts(nowEpochMs = t0 + 150_000L)

            val track = engine.peers.value.getValue(2u)
            assertEquals(PeerPresenceState.ACTIVE, PeerPresencePolicy.stateFor(track, t0 + 150_000L))
            val frame = controller.flushMapFrame(nowEpochMs = t0 + 150_000L, force = true)
            assertEquals(2, frame.ridersCount)
            assertEquals(
                RiderRouteStatus.ON_ROUTE,
                frame.markers.first { it.shortId == 2u.toUShort() }.routeStatus
            )
            engine.stop()
        }
}
