package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosBloodType
import com.rutabikersync.app.core.mesh.sos.SosIncidentCode
import com.rutabikersync.app.core.mesh.sos.SosPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Suite de Pruebas Unitarias para Relay SOS Prioritario, Flujo de ACK y Simulación Multi-Salto (Hito 4 · Etapa 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosMultiHopSimulationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val clubId: UInt = 0x52425301u

    private lateinit var crypto: Ed25519SosCrypto
    private val keyPairs = mutableMapOf<UShort, KeyPair>()
    private val manifestResolver = InMemorySosPublicKeyResolver()

    @Before
    fun setUp() {
        crypto = Ed25519SosCrypto(context = null)
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        for (i in 1..6) {
            val id = i.toUShort()
            val pair = kpg.generateKeyPair()
            keyPairs[id] = pair
            manifestResolver.registerPeer(id, pair.public)
        }
    }

    /**
     * Nodo simulado de la red de malla para pruebas de emergencia.
     */
    private class SimulatedSosNode(
        val shortId: UShort,
        val clubId: UInt,
        val crypto: Ed25519SosCrypto,
        val resolver: InMemorySosPublicKeyResolver,
        val neighbors: MutableList<SimulatedSosNode> = mutableListOf()
    ) {
        val receivedSosPackets = ConcurrentLinkedQueue<SosPacket>()
        val receivedAckPackets = ConcurrentLinkedQueue<SosAckPacket>()
        val allDecisions = ConcurrentLinkedQueue<MeshRelayEngine.RelayDecision>()

        val relayEngine = MeshRelayEngine(
            localShortId = shortId,
            clubNetworkId = clubId
        ,
        requireAuthorization = false
        ).apply {
            this.sosCrypto = crypto
            this.sosPublicKeyResolver = resolver
            this.onSosReceived = { sos -> receivedSosPackets.add(sos) }
            this.onSosAckReceived = { ack -> receivedAckPackets.add(ack) }
        }

        fun onReceiveRaw(bytes: ByteArray, immediateSenderId: UShort) {
            val decision = relayEngine.processIncomingPacket(
                rawBytes = bytes,
                immediateSenderShortId = immediateSenderId
            )
            allDecisions.add(decision)

            when (decision) {
                is MeshRelayEngine.RelayDecision.Relayed -> {
                    for (neighbor in neighbors) {
                        if (neighbor.shortId != immediateSenderId) {
                            neighbor.onReceiveRaw(decision.relayedBytes, shortId)
                        }
                    }
                }
                else -> { /* Consumo local o descarte */ }
            }
        }
    }

    private fun buildLinearChain(count: Int): List<SimulatedSosNode> {
        val nodes = (1..count).map { i ->
            val id = i.toUShort()
            SimulatedSosNode(id, clubId, crypto, manifestResolver)
        }
        for (i in 0 until count - 1) {
            nodes[i].neighbors.add(nodes[i + 1])
            nodes[i + 1].neighbors.add(nodes[i])
        }
        return nodes
    }

    @Test
    fun testFiveNodeChainSosPropagationAndAckReturn() {
        // Convoy lineal: N1 (accidentado) <-> N2 <-> N3 <-> N4 <-> N5 (Líder)
        val nodes = buildLinearChain(5)
        val n1 = nodes[0]
        val n2 = nodes[1]
        val n3 = nodes[2]
        val n4 = nodes[3]
        val n5 = nodes[4]

        // 1. N1 emite SOS con TTL=5 y hopCount=0 firmado con su clave privada
        val unsignedSos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = n1.shortId,
            sequenceNum = 101_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1731152000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val signedSos = crypto.signSos(unsignedSos, keyPairs.getValue(n1.shortId).private)

        // N1 propaga hacia sus vecinos físicos (N2)
        n2.onReceiveRaw(signedSos.toByteArray(), n1.shortId)

        // Verificación en N5 (Líder): debe haber recibido el SOS a través de 4 saltos
        assertEquals(1, n5.receivedSosPackets.size)
        val sosAtLeader = n5.receivedSosPackets.first()
        assertEquals(n1.shortId, sosAtLeader.header.senderShortId)
        assertEquals(101_u.toUShort(), sosAtLeader.header.sequenceNum)
        assertEquals(SosIncidentCode.FALL_AUTOMATIC, sosAtLeader.incident)
        assertEquals(SosBloodType.O_POSITIVE, sosAtLeader.blood)

        // Verificación de Opción B: hopCount acumulado en cada salto
        assertEquals(3.toByte(), sosAtLeader.hopCount) // N1(0) -> N2(1) -> N3(2) -> N4(3) -> N5
        assertTrue("La firma Ed25519 del accidentado N1 debe ser 100% válida en el Líder N5",
            crypto.verifySos(sosAtLeader, keyPairs.getValue(n1.shortId).public))

        // 2. N5 (Líder) responde con un SosAckPacket de vuelta hacia N1
        val unsignedAck = SosAckPacket.create(
            clubNetworkId = clubId,
            ackerShortId = n5.shortId,
            sequenceNum = 501_u,
            originalSenderShortId = n1.shortId,
            originalSequenceNum = 101_u,
            ackTimestampEpochSec = 1731152002,
            signature = ByteArray(64),
            currentTtl = 5
        )
        val signedAck = crypto.signSosAck(unsignedAck, keyPairs.getValue(n5.shortId).private)

        // N5 propaga el ACK hacia N4 de retorno
        n4.onReceiveRaw(signedAck.toByteArray(), n5.shortId)

        // Verificación en N1: el ACK debió propagarse N5 -> N4 -> N3 -> N2 -> N1
        assertEquals(1, n1.receivedAckPackets.size)
        val ackAtN1 = n1.receivedAckPackets.first()
        assertEquals(n5.shortId, ackAtN1.ackerShortId)
        assertEquals(n1.shortId, ackAtN1.originalSenderShortId)
        assertEquals(101_u.toUShort(), ackAtN1.originalSequenceNum)
        assertTrue("La firma del Líder en el ACK debe ser verificada exitosamente en N1",
            crypto.verifySosAck(ackAtN1, keyPairs.getValue(n5.shortId).public))
    }

    @Test
    fun testSosSignatureRemainsValidAcrossAllNodesOptionB() {
        val nodes = buildLinearChain(5)
        val n1 = nodes[0]

        val unsignedSos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = n1.shortId,
            sequenceNum = 200_u,
            latitude = 40.4168,
            longitude = -3.7038,
            incident = SosIncidentCode.VEHICLE_COLLISION,
            bloodType = SosBloodType.AB_NEGATIVE,
            timestampEpochSec = 1731153000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val signedSos = crypto.signSos(unsignedSos, keyPairs.getValue(n1.shortId).private)

        // Enviar a N2
        nodes[1].onReceiveRaw(signedSos.toByteArray(), n1.shortId)

        // Verificar que N2, N3, N4 y N5 validaron positivamente la firma de N1
        for (i in 1..4) {
            val node = nodes[i]
            assertEquals("Nodo ${node.shortId} debe haber recibido 1 SOS", 1, node.receivedSosPackets.size)
            val pkt = node.receivedSosPackets.first()
            assertTrue("Firma debe ser válida en Nodo ${node.shortId}",
                crypto.verifySos(pkt, keyPairs.getValue(n1.shortId).public))
        }
    }

    @Test
    fun testSosHopLimitExceededDropsPacket() {
        val node = SimulatedSosNode(2_u, clubId, crypto, manifestResolver)

        // SOS con hopCount = 5 (límite máximo permitido)
        val sosHop5 = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = 1_u,
            sequenceNum = 300_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1731154000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 5.toByte()
        )
        val signed = crypto.signSos(sosHop5, keyPairs.getValue(1_u).private)

        val decision = node.relayEngine.processIncomingPacket(signed.toByteArray(), 1_u)
        assertTrue("Paquete con hopCount >= 5 debe ser descartado por límite de saltos",
            decision is MeshRelayEngine.RelayDecision.DroppedHopLimitExceeded)
        assertEquals(5, (decision as MeshRelayEngine.RelayDecision.DroppedHopLimitExceeded).hopCount)
    }

    @Test
    fun testSosWithInvalidSignatureIsDroppedBeforeRelay() {
        val node = SimulatedSosNode(2_u, clubId, crypto, manifestResolver)

        val invalidSos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = 1_u,
            sequenceNum = 301_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1731154000,
            signature = ByteArray(64) { 0xFF.toByte() }, // Firma deliberadamente inválida
            currentTtl = 5
        )

        val decision = node.relayEngine.processIncomingPacket(invalidSos.toByteArray(), 1_u)
        assertTrue("SOS con firma inválida debe ser descartado como DroppedInvalidSignature",
            decision is MeshRelayEngine.RelayDecision.DroppedInvalidSignature)
    }

    @Test
    fun testSosAckWithInvalidSignatureIsDroppedBeforeRelay() {
        val node = SimulatedSosNode(2_u, clubId, crypto, manifestResolver)

        val invalidAck = SosAckPacket.create(
            clubNetworkId = clubId,
            ackerShortId = 5_u,
            sequenceNum = 601_u,
            originalSenderShortId = 1_u,
            originalSequenceNum = 301_u,
            ackTimestampEpochSec = 1731154005,
            signature = ByteArray(64) { 0xAA.toByte() }, // Firma corrupta
            currentTtl = 5
        )

        val decision = node.relayEngine.processIncomingPacket(invalidAck.toByteArray(), 5_u)
        assertTrue("ACK con firma inválida debe ser descartado como DroppedInvalidSignature",
            decision is MeshRelayEngine.RelayDecision.DroppedInvalidSignature)
    }

    @Test
    fun testSosOwnPacketLoopbackSuppression() {
        val node = SimulatedSosNode(1_u, clubId, crypto, manifestResolver)

        val ownSos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = 1_u, // Mismo shortId local
            sequenceNum = 401_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.A_POSITIVE,
            timestampEpochSec = 1731155000,
            signature = ByteArray(64)
        )
        val signed = crypto.signSos(ownSos, keyPairs.getValue(1_u).private)

        val decision = node.relayEngine.processIncomingPacket(signed.toByteArray(), 2_u)
        assertTrue("SOS emitido por el propio nodo debe descartarse por loopback",
            decision is MeshRelayEngine.RelayDecision.DroppedOwnPacket)
    }

    @Test
    fun testSosBroadcasterStopsImmediatelyUponReceivingAck() = testScope.runTest {
        val node = SimulatedSosNode(1_u, clubId, crypto, manifestResolver)

        val sos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = 1_u,
            sequenceNum = 501_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1731156000,
            signature = ByteArray(64)
        )
        val signedSos = crypto.signSos(sos, keyPairs.getValue(1_u).private)

        val transmittedFrames = mutableListOf<ByteArray>()
        val job = node.relayEngine.startSosBroadcaster(
            sosPacket = signedSos,
            scope = this,
            intervalMs = 500L,
            durationSeconds = 60,
            onTransmit = { transmittedFrames.add(it) }
        )

        testScheduler.advanceTimeBy(1100L)
        assertTrue("Debe haber emitido frames en ráfaga periódica", transmittedFrames.size >= 2)
        assertTrue("El job de transmisión periódica debe estar activo", job.isActive)

        // Ahora N1 recibe un ACK legítimo de N5 confirmando su SOS
        val ack = SosAckPacket.create(
            clubNetworkId = clubId,
            ackerShortId = 5_u,
            sequenceNum = 801_u,
            originalSenderShortId = 1_u,
            originalSequenceNum = 501_u,
            ackTimestampEpochSec = 1731156002,
            signature = ByteArray(64)
        )
        val signedAck = crypto.signSosAck(ack, keyPairs.getValue(5_u).private)

        val decision = node.relayEngine.processIncomingPacket(signedAck.toByteArray(), 2_u)
        assertTrue("El ACK para nosotros debe consumirse localmente",
            decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)

        // Verificamos que el job de emisión periódica de SOS se detuvo de inmediato
        assertFalse("Al recibir el ACK, el transmisor de SOS debe cancelarse", job.isActive)
        assertEquals(1, node.receivedAckPackets.size)
    }

    @Test
    fun testSosAckForDifferentNodeIsRelayed() {
        // N2 recibe un ACK emitido por N5 pero dirigido a N1
        val n2 = SimulatedSosNode(2_u, clubId, crypto, manifestResolver)

        val ackForN1 = SosAckPacket.create(
            clubNetworkId = clubId,
            ackerShortId = 5_u,
            sequenceNum = 901_u,
            originalSenderShortId = 1_u, // Para N1
            originalSequenceNum = 101_u,
            ackTimestampEpochSec = 1731157000,
            signature = ByteArray(64),
            currentTtl = 4
        )
        val signedAck = crypto.signSosAck(ackForN1, keyPairs.getValue(5_u).private)

        val decision = n2.relayEngine.processIncomingPacket(signedAck.toByteArray(), 3_u)
        assertTrue("N2 debe retransmitir el ACK hacia N1", decision is MeshRelayEngine.RelayDecision.Relayed)
        val relayed = decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(3, relayed.newTtl)
        assertTrue("ACK debe tener prioridad alta", relayed.isHighPriority)
    }

    @Test
    fun testHighPrioritySosDecisionFlag() {
        val n2 = SimulatedSosNode(2_u, clubId, crypto, manifestResolver)

        val sos = SosPacket.create(
            clubNetworkId = clubId,
            senderShortId = 1_u,
            sequenceNum = 777_u,
            latitude = 19.4326,
            longitude = -99.1332,
            incident = SosIncidentCode.MECHANICAL_FAILURE,
            bloodType = SosBloodType.B_NEGATIVE,
            timestampEpochSec = 1731158000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val signed = crypto.signSos(sos, keyPairs.getValue(1_u).private)

        val decision = n2.relayEngine.processIncomingPacket(signed.toByteArray(), 1_u)
        assertTrue(decision is MeshRelayEngine.RelayDecision.Relayed)
        val relayed = decision as MeshRelayEngine.RelayDecision.Relayed
        assertTrue("Relay de SOS debe marcar isHighPriority = true", relayed.isHighPriority)
        assertEquals(MeshRelayEngine.SOS_RELAY_JITTER_MS, relayed.jitterMs)
        assertEquals(1, relayed.hopCount)
    }

    @Test
    fun testMultipleConsecutiveSosAndAcksPropagateCorrectly() {
        val nodes = buildLinearChain(5)
        val n1 = nodes[0]
        val n5 = nodes[4]

        for (seq in 1..3) {
            val sos = SosPacket.create(
                clubNetworkId = clubId,
                senderShortId = n1.shortId,
                sequenceNum = seq.toUShort(),
                latitude = 19.4326,
                longitude = -99.1332,
                incident = SosIncidentCode.MANUAL_BUTTON,
                bloodType = SosBloodType.O_POSITIVE,
                timestampEpochSec = 1731159000 + seq,
                signature = ByteArray(64),
                currentTtl = 5,
                hopCount = 0
            )
            val signedSos = crypto.signSos(sos, keyPairs.getValue(n1.shortId).private)
            nodes[1].onReceiveRaw(signedSos.toByteArray(), n1.shortId)

            val ack = SosAckPacket.create(
                clubNetworkId = clubId,
                ackerShortId = n5.shortId,
                sequenceNum = (1000 + seq).toUShort(),
                originalSenderShortId = n1.shortId,
                originalSequenceNum = seq.toUShort(),
                ackTimestampEpochSec = 1731159005 + seq,
                signature = ByteArray(64),
                currentTtl = 5
            )
            val signedAck = crypto.signSosAck(ack, keyPairs.getValue(n5.shortId).private)
            nodes[3].onReceiveRaw(signedAck.toByteArray(), n5.shortId)
        }

        assertEquals(3, n5.receivedSosPackets.size)
        assertEquals(3, n1.receivedAckPackets.size)
    }
}

