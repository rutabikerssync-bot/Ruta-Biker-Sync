package com.rutabikersync.app.core.mesh.text

import com.rutabikersync.app.core.mesh.DeduplicationTable
import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Suite de Pruebas Unitarias para Mensajería de Texto Off-Grid (Fase 6 · Hito 5 · Etapa 1).
 *
 * Valida:
 * 1. Formato Wire compacto ≤ 168B y round-trip binario.
 * 2. Cifrado autenticado ChaCha20-Poly1305 con clave de rodada de 256 bits (RFC 8439).
 * 3. Detección y rechazo de manipulación en ciphertext o authTag (AEADBadTagException).
 * 4. Límites de tamaño: máx 120 bytes UTF-8 permitido, rechazo de >120 bytes y vacío.
 * 5. Enrutamiento Unicast: consumo local estricto sin retransmisión si target == localShortId.
 * 6. Enrutamiento Broadcast: consumo local y retransmisión si target == 0xFFFF.
 * 7. Enrutamiento Relay Terceros: retransmisión con decremento de TTL e incremento de hopCount.
 * 8. Supresión de duplicados por DeduplicationTable LRU.
 * 9. Límite estricto de saltos (hopCount >= 5 -> DroppedHopLimitExceeded).
 * 10. Simulación multi-salto determinista en cadena lineal N1 -> N2 -> N3 -> N4.
 */
class TextMessageMeshTest {

    private val testRideKey = ByteArray(32) { (it + 1).toByte() } // Clave de prueba de 256 bits
    private val wrongKey = ByteArray(32) { (it + 99).toByte() }

    private val testNetworkId: UInt = 0x1337u
    private val senderId: UShort = 1u
    private val recipientId: UShort = 2u

    private fun createHeader(
        sender: UShort = senderId,
        seq: UShort = 100u,
        ttl: Int = 5,
        type: Int = MeshHeader.TYPE_TEXT_MESSAGE
    ): MeshHeader = MeshHeader(
        clubNetworkId = testNetworkId,
        senderShortId = sender,
        packetType = type,
        initTtl = 5,
        currentTtl = ttl,
        sequenceNum = seq
    )

    @Test
    fun testSerializationRoundTrip() {
        val header = createHeader()
        val text = "Aceite en carril derecho km 42"
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = text,
            header = header,
            targetShortId = recipientId,
            hopCount = 0
        )

        val wireBytes = packet.toByteArray()
        assertTrue("El tamaño del paquete (${wireBytes.size}B) debe ser <= 168 bytes", wireBytes.size <= 168)
        assertTrue("El tamaño debe ser >= MIN_PACKET_SIZE (42B)", wireBytes.size >= TextMessagePacket.MIN_PACKET_SIZE)

        val deserialized = TextMessagePacket.fromByteArray(wireBytes, 0)
        assertEquals(packet.header.clubNetworkId, deserialized.header.clubNetworkId)
        assertEquals(packet.header.senderShortId, deserialized.header.senderShortId)
        assertEquals(packet.header.packetType, deserialized.header.packetType)
        assertEquals(packet.targetShortId, deserialized.targetShortId)
        assertEquals(packet.messageLength, deserialized.messageLength)
        assertEquals(packet.hopCount, deserialized.hopCount)
        assertTrue(packet.iv.contentEquals(deserialized.iv))
        assertTrue(packet.authTag.contentEquals(deserialized.authTag))
        assertTrue(packet.ciphertext.contentEquals(deserialized.ciphertext))
        assertEquals(packet, deserialized)
    }

    @Test
    fun testEncryptionAndDecryptionRoundTrip() {
        val header = createHeader()
        val originalText = "Esperando al grupo en la gasolinera. Parada de 15 min."
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = originalText,
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )

        assertTrue(packet.isBroadcast)
        val decryptedText = TextMessageCrypto.decrypt(testRideKey, packet)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testDecryptionFailsWithBadAuthTag() {
        val header = createHeader()
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje confidencial",
            header = header
        )

        // Corromper el tag de autenticación
        val corruptedTag = packet.authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0].toInt() xor 0xFF).toByte()
        val corruptedPacket = packet.copy(authTag = corruptedTag)

        try {
            TextMessageCrypto.decrypt(testRideKey, corruptedPacket)
            fail("Debe lanzar AEADBadTagException cuando el authTag está alterado")
        } catch (_: AEADBadTagException) {
            // Éxito: integridad verificada
        }
    }

    @Test
    fun testDecryptionFailsWithCorruptedCiphertext() {
        val header = createHeader()
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje intacto",
            header = header
        )

        // Corromper un byte del ciphertext
        val corruptedCiphertext = packet.ciphertext.copyOf()
        corruptedCiphertext[corruptedCiphertext.size - 1] = (corruptedCiphertext[corruptedCiphertext.size - 1].toInt() xor 0xAA).toByte()
        val corruptedPacket = packet.copy(ciphertext = corruptedCiphertext)

        try {
            TextMessageCrypto.decrypt(testRideKey, corruptedPacket)
            fail("Debe lanzar AEADBadTagException cuando el ciphertext está alterado")
        } catch (_: AEADBadTagException) {
            // Éxito: tamper detection verificado
        }
    }

    @Test
    fun testDecryptionFailsWithWrongKey() {
        val header = createHeader()
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Texto seguro",
            header = header
        )

        try {
            TextMessageCrypto.decrypt(wrongKey, packet)
            fail("Debe fallar al descifrar con una clave no correspondiente a la rodada")
        } catch (_: AEADBadTagException) {
            // Éxito esperado
        }
    }

    @Test
    fun testMaxPlaintext120BytesAllowed() {
        val header = createHeader()
        // Crear texto de exactamente 120 bytes ASCII
        val exact120BytesText = "A".repeat(120)
        assertEquals(120, exact120BytesText.toByteArray(Charsets.UTF_8).size)

        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = exact120BytesText,
            header = header
        )

        assertEquals(120, packet.ciphertext.size)
        val wireBytes = packet.toByteArray()
        assertTrue("El paquete no debe superar 168 bytes (actual: ${wireBytes.size}B)", wireBytes.size <= 168)

        val decrypted = TextMessageCrypto.decrypt(testRideKey, packet)
        assertEquals(exact120BytesText, decrypted)
    }

    @Test
    fun testPlaintextExceeding120BytesRejected() {
        val header = createHeader()
        val longText = "B".repeat(121)
        try {
            TextMessageCrypto.encrypt(
                key = testRideKey,
                plaintext = longText,
                header = header
            )
            fail("Debe rechazar texto > 120 bytes con IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("120 bytes"))
        }
    }

    @Test
    fun testEmptyPlaintextRejected() {
        val header = createHeader()
        try {
            TextMessageCrypto.encrypt(
                key = testRideKey,
                plaintext = "",
                header = header
            )
            fail("Debe rechazar texto vacío con IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("vacío"))
        }
    }

    @Test
    fun testUnicastTargetedToLocalNodeConsumedLocallyWithoutRelay() {
        var relayTransmitted = false
        var receivedPacket: TextMessagePacket? = null
        var decryptedContent: String? = null

        val engine = MeshRelayEngine(
            localShortId = recipientId, // Este nodo es el destinatario (ID 2)
            clubNetworkId = testNetworkId,
            onTransmitRelay = { relayTransmitted = true }
        ).apply {
            customRideSecretKey = testRideKey
            onTextMessageReceived = { pkt, text ->
                receivedPacket = pkt
                decryptedContent = text
            }
        }

        val header = createHeader(sender = senderId, ttl = 5)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje directo para nodo 2",
            header = header,
            targetShortId = recipientId // Unicast para nodo 2
        )

        val decision = engine.processIncomingPacket(packet.toByteArray())
        assertTrue("Debe consumirse localmente por ser unicast para este nodo", decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertFalse("NO debe retransmitirse a la red un mensaje unicast completado", relayTransmitted)
        assertNotNull(receivedPacket)
        assertEquals("Mensaje directo para nodo 2", decryptedContent)
    }

    @Test
    fun testBroadcastConsumedLocallyAndRelayed() {
        var localDecrypted: String? = null
        var relayedBytesReceived: ByteArray? = null

        val engine = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> relayedBytesReceived = bytes }
        ).apply {
            customRideSecretKey = testRideKey
            onTextMessageReceived = { _, text -> localDecrypted = text }
        }

        val header = createHeader(sender = 1u, ttl = 5)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Alerta general para todo el club",
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )

        val decision = engine.processIncomingPacket(packet.toByteArray())
        assertTrue("Broadcast debe retransmitirse", decision is MeshRelayEngine.RelayDecision.Relayed)
        val relayed = decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(4, relayed.newTtl)
        assertEquals(1, relayed.hopCount)
        assertEquals("Alerta general para todo el club", localDecrypted)
        assertNotNull(relayedBytesReceived)
    }

    @Test
    fun testUnicastTargetedToThirdPartyRelayedWithoutLocalConsumption() {
        var localDecrypted: String? = null
        var relayedBytesReceived: ByteArray? = null

        val engine = MeshRelayEngine(
            localShortId = 2u, // Nodo 2 es un repetidor intermedio
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> relayedBytesReceived = bytes }
        ).apply {
            customRideSecretKey = testRideKey
            onTextMessageReceived = { _, text -> localDecrypted = text }
        }

        val header = createHeader(sender = 1u, ttl = 5)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje secreto para nodo 3",
            header = header,
            targetShortId = 3u // Destinado a nodo 3 (no al nodo 2)
        )

        val decision = engine.processIncomingPacket(packet.toByteArray())
        assertTrue("Debe retransmitirse hacia el destino final", decision is MeshRelayEngine.RelayDecision.Relayed)
        val relayed = decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(4, relayed.newTtl)
        assertEquals(1, relayed.hopCount)
        assertNull("El repetidor intermedio no debe invocar callback local para unicast ajeno", localDecrypted)
        assertNotNull(relayedBytesReceived)
    }

    @Test
    fun testDuplicatePacketDropped() {
        val engine = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId
        )

        val header = createHeader(sender = 1u, seq = 42u, ttl = 5)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje único",
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )

        val raw = packet.toByteArray()
        val firstDecision = engine.processIncomingPacket(raw)
        assertTrue(firstDecision is MeshRelayEngine.RelayDecision.Relayed)

        val secondDecision = engine.processIncomingPacket(raw)
        assertTrue("La segunda copia debe descartarse por duplicada", secondDecision is MeshRelayEngine.RelayDecision.DroppedDuplicate)
        assertEquals(1, engine.totalPacketsDroppedDuplicate.get())
    }

    @Test
    fun testHopLimitExceededDropped() {
        val engine = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId
        )

        val header = createHeader(sender = 1u, ttl = 5)
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Mensaje que ya viajó 5 saltos",
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET,
            hopCount = 5.toByte() // Ya alcanzó el límite de 5 saltos
        )

        val decision = engine.processIncomingPacket(packet.toByteArray())
        assertTrue("Debe descartarse por límite de saltos", decision is MeshRelayEngine.RelayDecision.DroppedHopLimitExceeded)
        assertEquals(5, (decision as MeshRelayEngine.RelayDecision.DroppedHopLimitExceeded).hopCount)
    }

    @Test
    fun testTtlExhaustedConsumedLocally() {
        val engine = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId
        )

        val header = createHeader(sender = 1u, ttl = 1) // TTL=1 agotado
        val packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Último salto de vida",
            header = header,
            targetShortId = TextMessagePacket.BROADCAST_TARGET
        )

        val decision = engine.processIncomingPacket(packet.toByteArray())
        assertTrue("TTL=1 debe consumirse localmente sin retransmitir", decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals(1, engine.totalPacketsDroppedTtl.get())
    }

    @Test
    fun testMultiHopSimulationLinear4Nodes() {
        // Cadena lineal: N1 (Emisor) -> N2 (Relay) -> N3 (Relay) -> N4 (Destino Final)
        var n2Out: ByteArray? = null
        var n3Out: ByteArray? = null

        val node2 = MeshRelayEngine(
            localShortId = 2u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> n2Out = bytes }
        ).apply { customRideSecretKey = testRideKey }

        val node3 = MeshRelayEngine(
            localShortId = 3u,
            clubNetworkId = testNetworkId,
            onTransmitRelay = { bytes -> n3Out = bytes }
        ).apply { customRideSecretKey = testRideKey }

        var n4ReceivedText: String? = null
        var n4HopCountReceived: Int? = null

        val node4 = MeshRelayEngine(
            localShortId = 4u,
            clubNetworkId = testNetworkId
        ).apply {
            customRideSecretKey = testRideKey
            onTextMessageReceived = { pkt, text ->
                n4ReceivedText = text
                n4HopCountReceived = pkt.hopCount.toInt() and 0xFF
            }
        }

        // 1. N1 emite un mensaje unicast dirigido a N4 con TTL=5 y hopCount=0
        val n1Header = createHeader(sender = 1u, seq = 77u, ttl = 5)
        val n1Packet = TextMessageCrypto.encrypt(
            key = testRideKey,
            plaintext = "Convoy: Curva cerrada a la derecha",
            header = n1Header,
            targetShortId = 4u,
            hopCount = 0
        )

        // 2. N2 recibe el paquete desde N1
        val n2Decision = node2.processIncomingPacket(n1Packet.toByteArray())
        assertTrue(n2Decision is MeshRelayEngine.RelayDecision.Relayed)
        val n2Relayed = n2Decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(4, n2Relayed.newTtl)
        assertEquals(1, n2Relayed.hopCount)
        assertNotNull(n2Out)

        // 3. N3 recibe el paquete retransmitido por N2
        val n3Decision = node3.processIncomingPacket(n2Out!!)
        assertTrue(n3Decision is MeshRelayEngine.RelayDecision.Relayed)
        val n3Relayed = n3Decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(3, n3Relayed.newTtl)
        assertEquals(2, n3Relayed.hopCount)
        assertNotNull(n3Out)

        // 4. N4 recibe el paquete retransmitido por N3 (destino final)
        val n4Decision = node4.processIncomingPacket(n3Out!!)
        assertTrue(n4Decision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals("Convoy: Curva cerrada a la derecha", n4ReceivedText)
        assertEquals(2, n4HopCountReceived) // 2 saltos intermedios: N1 -> N2 (1) -> N3 (2) -> N4
    }
}
