package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.audio.VoiceCrypto
import com.rutabikersync.app.core.mesh.audio.VoicePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Suite de pruebas unitarias para el Motor de Enrutamiento Multi-Salto con Flooding Controlado ([MeshRelayEngine]).
 * Conforme a las especificaciones de la Fase 6 (Hito 3 · Etapa 2):
 * 1. Simulación determinista en JVM de un convoy lineal de 5 nodos: N1 -> N2 -> N3 -> N4 -> N5.
 * 2. Semántica de TTL "Opción A" (decremento antes de retransmitir) con descarte garantizado cuando TTL <= 1.
 * 3. Ruptura de cadena: Si N3 cae, el mensaje no alcanza a N4 ni N5.
 * 4. Supresión de tormentas de broadcast y rebotes circulares mediante [DeduplicationTable].
 * 5. Integridad de payloads para Heartbeats y VoicePackets (voz PTT).
 * 6. Jitter de retransmisión estrictamente acotado en 10..35 ms.
 */
class MultiHopFloodingTest {

    private val clubId: UInt = 0x52425301u // "RBS1"

    /**
     * Nodo simulado en la cadena de malla.
     */
    private class SimulatedMeshNode(
        val shortId: UShort,
        val clubId: UInt,
        val neighbors: MutableList<SimulatedMeshNode> = mutableListOf()
    ) {
        val relayEngine = MeshRelayEngine(
            localShortId = shortId,
            clubNetworkId = clubId
        )

        val receivedPackets = ConcurrentLinkedQueue<MeshHeader>()
        var isDown = false

        fun onReceiveRaw(bytes: ByteArray, immediateSenderId: UShort) {
            if (isDown) return

            val decision = relayEngine.processIncomingPacket(
                rawBytes = bytes,
                immediateSenderShortId = immediateSenderId
            )

            val header = MeshHeader.fromByteArray(bytes)
            receivedPackets.add(header)

            when (decision) {
                is MeshRelayEngine.RelayDecision.Relayed -> {
                    // Propagar a los nodos vecinos en el rango de radio físico
                    for (neighbor in neighbors) {
                        if (neighbor.shortId != immediateSenderId && !neighbor.isDown) {
                            neighbor.onReceiveRaw(decision.relayedBytes, shortId)
                        }
                    }
                }
                else -> {
                    // Consumido localmente o descartado por loopback/dedup/TTL agotado
                }
            }
        }
    }

    @Test
    fun testFiveNodeChainRelaysPacketWithTtlFiveToNodeFive() {
        // Topología en cadena: N1 <-> N2 <-> N3 <-> N4 <-> N5
        val n1 = SimulatedMeshNode(1u, clubId)
        val n2 = SimulatedMeshNode(2u, clubId)
        val n3 = SimulatedMeshNode(3u, clubId)
        val n4 = SimulatedMeshNode(4u, clubId)
        val n5 = SimulatedMeshNode(5u, clubId)

        // Conexiones de radio bidireccionales adyacentes
        n1.neighbors.add(n2)
        n2.neighbors.addAll(listOf(n1, n3))
        n3.neighbors.addAll(listOf(n2, n4))
        n4.neighbors.addAll(listOf(n3, n5))
        n5.neighbors.add(n4)

        // N1 emite un paquete con initTtl = 5, currentTtl = 5
        val originalHeader = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = n1.shortId,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = 1u
        )
        val heartbeat = HeartbeatFrame(
            header = originalHeader,
            latitude = 10.4806,
            longitude = -66.9036,
            speedKmh = 72,
            headingDeg = 180f,
            batteryPercent = 95,
            flags = 0
        )
        val wireBytes = heartbeat.toByteArray()

        // Iniciar transmisión desde N1 hacia su vecino directo N2
        n2.onReceiveRaw(wireBytes, n1.shortId)

        // Verificaciones nodo por nodo:
        // 1. N2 recibió el paquete con currentTtl = 5
        val n2Headers = n2.receivedPackets.filter { it.sequenceNum == 1u.toUShort() }
        assertEquals(1, n2Headers.size)
        assertEquals(5, n2Headers.first().currentTtl)

        // 2. N3 recibió el paquete retransmitido por N2 con currentTtl = 4
        val n3Headers = n3.receivedPackets.filter { it.sequenceNum == 1u.toUShort() }
        assertEquals(1, n3Headers.size)
        assertEquals(4, n3Headers.first().currentTtl)

        // 3. N4 recibió el paquete retransmitido por N3 con currentTtl = 3
        val n4Headers = n4.receivedPackets.filter { it.sequenceNum == 1u.toUShort() }
        assertEquals(1, n4Headers.size)
        assertEquals(3, n4Headers.first().currentTtl)

        // 4. N5 recibió el paquete retransmitido por N4 con currentTtl = 2
        val n5Headers = n5.receivedPackets.filter { it.sequenceNum == 1u.toUShort() }
        assertEquals(1, n5Headers.size)
        assertEquals(2, n5Headers.first().currentTtl)

        // 5. Todos los nodos preservan senderShortId = 1u original de N1
        assertEquals(n1.shortId, n2Headers.first().senderShortId)
        assertEquals(n1.shortId, n3Headers.first().senderShortId)
        assertEquals(n1.shortId, n4Headers.first().senderShortId)
        assertEquals(n1.shortId, n5Headers.first().senderShortId)
    }

    @Test
    fun testTtlExhaustedAtLastHopStopsRelayingWithoutLoop() {
        // N4 emite hacia N5 con currentTtl = 1
        val relayEngine = MeshRelayEngine(localShortId = 5u, clubNetworkId = clubId)

        val terminalHeader = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = 1u,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 5,
            currentTtl = 1,
            sequenceNum = 42u
        )
        val wireBytes = HeartbeatFrame(
            header = terminalHeader,
            latitude = 0.0,
            longitude = 0.0,
            speedKmh = 0,
            headingDeg = 0f,
            batteryPercent = 100,
            flags = 0
        ).toByteArray()

        val decision = relayEngine.processIncomingPacket(wireBytes)

        assertTrue("Con currentTtl=1 debe consumirse localmente sin retransmitir", decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals(1, (decision as MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted).finalTtl)
        assertEquals(0, relayEngine.totalPacketsRelayed.get())
        assertEquals(1, relayEngine.totalPacketsDroppedTtl.get())
    }

    @Test
    fun testBrokenChainAtNodeThreeBlocksTransmissionToNodesFourAndFive() {
        val n1 = SimulatedMeshNode(1u, clubId)
        val n2 = SimulatedMeshNode(2u, clubId)
        val n3 = SimulatedMeshNode(3u, clubId)
        val n4 = SimulatedMeshNode(4u, clubId)
        val n5 = SimulatedMeshNode(5u, clubId)

        n1.neighbors.add(n2)
        n2.neighbors.addAll(listOf(n1, n3))
        n3.neighbors.addAll(listOf(n2, n4))
        n4.neighbors.addAll(listOf(n3, n5))
        n5.neighbors.add(n4)

        // SIMULACIÓN: N3 cae o pierde cobertura de radio (batería agotada o túnel)
        n3.isDown = true

        val header = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = n1.shortId,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = 99u
        )
        val wireBytes = HeartbeatFrame(header, 0.0, 0.0, 0, 0f, 100, 0).toByteArray()

        n2.onReceiveRaw(wireBytes, n1.shortId)

        // N2 debió recibirlo
        assertEquals(1, n2.receivedPackets.size)
        // N3 estaba caído, por tanto N4 y N5 NO recibieron nada
        assertEquals(0, n4.receivedPackets.size)
        assertEquals(0, n5.receivedPackets.size)
    }

    @Test
    fun testDeduplicationSuppressesBroadcastStormsAndBackwardsLoop() {
        val relayEngine = MeshRelayEngine(localShortId = 2u, clubNetworkId = clubId)

        val header = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = 1u,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = 10u
        )
        val wireBytes = HeartbeatFrame(header, 0.0, 0.0, 0, 0f, 100, 0).toByteArray()

        // Primer arribo: se procesa y retransmite
        val decision1 = relayEngine.processIncomingPacket(wireBytes)
        assertTrue(decision1 is MeshRelayEngine.RelayDecision.Relayed)
        assertEquals(1, relayEngine.totalPacketsRelayed.get())

        // Segundo arribo del mismo paquete (rebote de antena o eco de vecino): debe descartarse inmediatamente
        val decision2 = relayEngine.processIncomingPacket(wireBytes)
        assertTrue(decision2 is MeshRelayEngine.RelayDecision.DroppedDuplicate)
        assertEquals(1, relayEngine.totalPacketsRelayed.get()) // No se vuelve a retransmitir
        assertEquals(1, relayEngine.totalPacketsDroppedDuplicate.get())
    }

    @Test
    fun testRelayPreservesVoicePacketPayloadAndJitterIsBetweenTenAndThirtyFiveMs() {
        val relayEngine = MeshRelayEngine(localShortId = 2u, clubNetworkId = clubId)

        val key = ByteArray(32) { (it + 0xA5).toByte() }
        val nonce = VoiceCrypto.buildDeterministicNonce(clubId.toInt(), 1, 100, 0)
        val opusBytes = byteArrayOf(0x48, 0x01, 0x02, 0x03, 0x04)
        val encryptedPayload = VoiceCrypto.encrypt(key, nonce, opusBytes)

        val header = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = 1u,
            packetType = MeshHeader.TYPE_PTT_VOICE,
            initTtl = 5,
            currentTtl = 4,
            sequenceNum = 100u
        )
        val voicePacket = VoicePacket(
            header = header,
            frameIndex = 0,
            isLastFrame = false,
            nonce = nonce,
            encryptedPayloadWithTag = encryptedPayload
        )
        val wireBytes = voicePacket.toBinary()

        val decision = relayEngine.processIncomingPacket(wireBytes)
        assertTrue(decision is MeshRelayEngine.RelayDecision.Relayed)

        val relayed = decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals("TTL debe haber decrementado de 4 a 3", 3, relayed.newTtl)

        // Verificar que el Jitter se encuentra estrictamente en [10..35] ms
        assertTrue(
            "Jitter (${relayed.jitterMs} ms) debe estar entre 10 y 35 ms",
            relayed.jitterMs in 10L..35L
        )

        // Deserializar el paquete retransmitido y verificar paridad criptográfica exacta
        val relayedVoicePacket = VoicePacket.fromBinary(relayed.relayedBytes)
        assertEquals(1u.toUShort(), relayedVoicePacket.header.senderShortId)
        assertEquals(MeshHeader.TYPE_PTT_VOICE, relayedVoicePacket.header.packetType)
        assertEquals(3, relayedVoicePacket.header.currentTtl)
        assertEquals(voicePacket.frameIndex, relayedVoicePacket.frameIndex)

        // El payload cifrado debe poder descifrarse con la misma clave y nonce intacto
        val decrypted = VoiceCrypto.decrypt(key, relayedVoicePacket.nonce, relayedVoicePacket.encryptedPayloadWithTag)
        assertArrayEquals(opusBytes, decrypted)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
