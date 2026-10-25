package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import com.rutabikersync.app.core.transport.Transport
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
class MeshValidationPhase0Test {

    private val secretKey = "phase0-test-ride-secret-key-32b!".encodeToByteArray()

    @Test
    fun `packet serializes to exact 20 bytes and verifies HMAC auth tag`() {
        val packet = MeshPacket.createSigned(
            shortId = 42u,
            latitude = 19.4326077,
            longitude = -99.133208,
            speedKmh = 88,
            bearingDegrees = 180f,
            batteryPercent = 15, // < 20% -> activa flag isLowBattery
            isSos = true,
            isExtendedBle = true,
            priority = Transport.Priority.SOS,
            sequenceNumber = 513,
            ttl = 4,
            rideSecretKey = secretKey
        )

        val bytes = packet.toByteArray()
        assertEquals(20, bytes.size)

        val decoded = MeshPacket.fromByteArray(bytes)
        assertEquals(42u.toUShort(), decoded.shortId)
        assertEquals(19.4326077, decoded.latitude, 1e-6)
        assertEquals(-99.133208, decoded.longitude, 1e-6)
        assertEquals(88, decoded.speedKmh)
        assertEquals(180f, decoded.bearingDegrees, 1.5f)
        assertTrue(decoded.isSos)
        assertTrue(decoded.isLowBattery)
        assertTrue(decoded.isExtendedBle)
        assertTrue(decoded.isMoving)
        assertEquals(Transport.Priority.SOS, decoded.priority)
        assertEquals(513, decoded.sequenceNumber)
        assertEquals(4, decoded.initialTtl)
        assertEquals(4, decoded.currentTtl)
        assertTrue(decoded.verifyAuthTag(secretKey))

        // Si un atacante altera la latitud en tránsito, verifyAuthTag debe rechazarlo
        val tampered = bytes.copyOf()
        tampered[3] = (tampered[3] + 1).toByte()
        val tamperedPacket = MeshPacket.fromByteArray(tampered)
        assertFalse(tamperedPacket.verifyAuthTag(secretKey))
    }

    @Test
    fun `ttl decrements on relay and stops at 1 without loops`() {
        val original = MeshPacket.createSigned(
            shortId = 1u,
            latitude = 10.0,
            longitude = -70.0,
            speedKmh = 60,
            bearingDegrees = 90f,
            batteryPercent = 90,
            sequenceNumber = 10,
            ttl = 3,
            rideSecretKey = secretKey
        )
        assertEquals(1, original.hopsTaken)

        val hop1 = original.decrementTtlForRelay()
        assertNotNull(hop1)
        assertEquals(2, hop1!!.currentTtl)
        assertEquals(2, hop1.hopsTaken)
        // La etiqueta de autenticación sigue siendo válida tras decrementar currentTtl
        assertTrue(hop1.verifyAuthTag(secretKey))

        val hop2 = hop1.decrementTtlForRelay()
        assertNotNull(hop2)
        assertEquals(1, hop2!!.currentTtl)
        assertEquals(3, hop2.hopsTaken)
        assertTrue(hop2.verifyAuthTag(secretKey))

        // Cuando currentTtl llega a 1, el nodo receptor lo consume pero NO lo reenvía más
        val hop3 = hop2.decrementTtlForRelay()
        assertNull(hop3)
    }

    @Test
    fun `5-rider convoy relays multi-hop packets without duplicates and computes delivery and avgHops`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u, 5u)
        medium.connectChain(nodeIds)

        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey,
                localDeviceModel = "TestPhone-$id"
            ).also { it.start(backgroundScope) }
        }

        // El nodo 5 (cierre/sweep) emite su posición con TTL = 4 para alcanzar al líder (nodo 1) a 4 saltos
        engines.getValue(5u).emitLocalPosition(
            latitude = 19.40,
            longitude = -99.10,
            speedKmh = 70,
            bearingDegrees = 0f,
            batteryPercent = 80,
            ttl = 4
        )

        val leaderReport = engines.getValue(1u).metrics.value
        // El líder (nodo 1) debe haber recibido el paquete del nodo 5 tras 4 saltos (5->4->3->2->1)
        assertEquals(1, leaderReport.packetsReceivedValid)
        assertNotNull(leaderReport.avgHops)
        assertEquals(4.0f, leaderReport.avgHops!!, 0.01f)
        assertNotNull(leaderReport.heartbeatsDeliveredPct)
        assertEquals(100.0f, leaderReport.heartbeatsDeliveredPct!!, 0.01f)

        // Un nodo solitario sin paquetes recibidos (ej. el propio emisor nodo 5 si nadie más emitió)
        // debe reportar null en heartbeatsDeliveredPct y avgHops (en pantalla "N/D", en JSON null)
        val soloSenderReport = engines.getValue(5u).metrics.value
        assertEquals(0, soloSenderReport.packetsReceivedValid)
        assertNull(soloSenderReport.heartbeatsDeliveredPct)
        assertNull(soloSenderReport.avgHops)
        val soloJson = engines.getValue(5u).exportReportJson()
        assertTrue(soloJson.contains("\"heartbeatsDeliveredPct\": null"))
        assertTrue(soloJson.contains("\"avgHops\": null"))

        // Si el nodo 2 vuelve a retransmitir el mismo paquete (duplicado), el nodo 1 lo descarta
        val duplicateRaw = MeshPacket.createSigned(
            shortId = 5u,
            latitude = 19.40,
            longitude = -99.10,
            speedKmh = 70,
            bearingDegrees = 0f,
            batteryPercent = 80,
            sequenceNumber = 0,
            ttl = 4,
            rideSecretKey = secretKey
        ).toByteArray()
        engines.getValue(1u).onRawPacketReceived(duplicateRaw)
        assertEquals(1, engines.getValue(1u).metrics.value.packetsReceivedValid)
        assertTrue(engines.getValue(1u).metrics.value.duplicatesDropped >= 1)

        engines.values.forEach { it.stop() }
    }

    @Test
    fun `heartbeat timeout detects peer loss at 5 missed beats with reconnect hysteresis and island split`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(
            localShortId = 1u,
            transport = medium.createNodeTransport(1u),
            rideSecretKey = secretKey
        )

        val t0 = 1_000_000L
        for (peerId in listOf<UShort>(2u, 3u, 4u)) {
            val pkt = MeshPacket.createSigned(
                shortId = peerId,
                latitude = 19.0,
                longitude = -99.0,
                speedKmh = 50,
                bearingDegrees = 0f,
                batteryPercent = 90,
                sequenceNumber = 1,
                ttl = 3,
                rideSecretKey = secretKey
            )
            engine.onRawPacketReceived(pkt.toByteArray(), nowEpochMs = t0)
        }

        // A los 9 segundos (3 latidos) nadie está perdido aún
        val lostAt9s = engine.evaluatePeerTimeouts(nowEpochMs = t0 + 9_000L)
        assertTrue(lostAt9s.isEmpty())

        // A los 15 segundos (5 latidos perdidos) los 3 peers pasan a estado perdido y se registra 1 evento de isla
        val lostAt15s = engine.evaluatePeerTimeouts(nowEpochMs = t0 + 15_000L)
        assertEquals(3, lostAt15s.size)
        assertEquals(1, engine.metrics.value.islandEvents)

        // Histéresis: el primer latido de regreso del nodo 2 aún no limpia isLost (evita falsos regresos)
        val recoveryBeat1 = MeshPacket.createSigned(
            shortId = 2u,
            latitude = 19.01,
            longitude = -99.01,
            speedKmh = 55,
            bearingDegrees = 0f,
            batteryPercent = 88,
            sequenceNumber = 2,
            ttl = 3,
            rideSecretKey = secretKey
        )
        engine.onRawPacketReceived(recoveryBeat1.toByteArray(), nowEpochMs = t0 + 16_000L)
        assertTrue(engine.peers.value.getValue(2u).isLost)

        // El segundo latido consecutivo limpia isLost y confirma la reconexión estable
        val recoveryBeat2 = recoveryBeat1.copy(
            sequenceNumber = 3,
            authTag = MeshPacket.createSigned(
                shortId = 2u,
                latitude = 19.01,
                longitude = -99.01,
                speedKmh = 55,
                bearingDegrees = 0f,
                batteryPercent = 88,
                sequenceNumber = 3,
                ttl = 3,
                rideSecretKey = secretKey
            ).authTag
        )
        engine.onRawPacketReceived(recoveryBeat2.toByteArray(), nowEpochMs = t0 + 19_000L)
        assertFalse(engine.peers.value.getValue(2u).isLost)
    }

    @Test
    fun `delivery percentage case 1 - no peers returns null for N_D`() {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(
            localShortId = 1u,
            transport = medium.createNodeTransport(1u),
            rideSecretKey = secretKey
        )

        val report = engine.metrics.value
        assertEquals(1, report.participants) // solo el nodo local (0 pares)
        assertEquals(0, report.heartbeatsExpected)
        assertEquals(0, report.heartbeatsReceived)
        assertNull(report.heartbeatsDeliveredPct)
        assertNull(report.avgHops)
        assertTrue(engine.exportReportJson().contains("\"heartbeatsDeliveredPct\": null"))
    }

    @Test
    fun `delivery percentage case 2 - peer registered with 0 expected beats returns null for N_D`() {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(
            localShortId = 1u,
            transport = medium.createNodeTransport(1u),
            rideSecretKey = secretKey
        )

        engine.registerExpectedPeer(shortId = 2u, nowEpochMs = 1_000L, initialExpectedBeats = 0)
        val report = engine.metrics.value
        assertEquals(2, report.participants) // 1 par registrado
        assertEquals(0, report.heartbeatsExpected)
        assertEquals(0, report.heartbeatsReceived)
        assertNull(report.heartbeatsDeliveredPct)
        assertNull(report.avgHops)
        assertTrue(engine.exportReportJson().contains("\"heartbeatsDeliveredPct\": null"))
    }

    @Test
    fun `delivery percentage case 3 - expected beats greater than 0 and 0 received returns 0_0 percent`() {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(
            localShortId = 1u,
            transport = medium.createNodeTransport(1u),
            rideSecretKey = secretKey
        )

        val t0 = 10_000L
        engine.registerExpectedPeer(shortId = 2u, nowEpochMs = t0, initialExpectedBeats = 0)
        // Tras 15 s (5 latidos perdidos) sin recibir nada del par 2: expectedBeats = 5, receivedBeats = 0
        val newlyLost = engine.evaluatePeerTimeouts(nowEpochMs = t0 + 15_000L)
        assertEquals(listOf(2u.toUShort()), newlyLost)

        val report = engine.metrics.value
        assertEquals(2, report.participants)
        assertEquals(5, report.heartbeatsExpected)
        assertEquals(0, report.heartbeatsReceived)
        assertNotNull(report.heartbeatsDeliveredPct)
        assertEquals(0.0f, report.heartbeatsDeliveredPct!!, 0.001f)
        assertNull(report.avgHops)
        assertTrue(engine.exportReportJson().contains("\"heartbeatsDeliveredPct\": 0.0"))
    }

    @Test
    fun `ble capabilities resolveEffectiveMode honors forced Legacy, Extended 1M and Extended Coded PHY`() {
        val fullCaps = BleAdvTransport.BleCapabilities(
            bluetoothSupported = true,
            bluetoothEnabled = true,
            multipleAdvertisementSupported = true,
            extendedAdvertisingSupported = true,
            le2MPhySupported = true,
            codedPhySupported = true,
            maxAdvertisingDataLength = 1650
        )

        assertEquals(
            "LEGACY_1M",
            fullCaps.resolveEffectiveMode(BleAdvTransport.TransportModeOverride.LEGACY_1M)
        )
        assertEquals(
            "EXTENDED_1M",
            fullCaps.resolveEffectiveMode(BleAdvTransport.TransportModeOverride.EXTENDED_1M)
        )
        assertEquals(
            "EXTENDED_CODED_PHY",
            fullCaps.resolveEffectiveMode(BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY)
        )
    }
}
