package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidencia unitaria de que el chat táctico usa el MISMO motor y la MISMA clave que los
 * heartbeats: el paquete cifrado por `emitTextMessage` es descifrado por otro nodo
 * mediante `processIncomingPacket` → `onTextMessageReceived`.
 */
class TacticalChatMeshIntegrationTest {

    private class CapturingTransport : Transport {
        override val name: String = "TEST_BLE"
        val sent = mutableListOf<ByteArray>()
        private val _incoming = MutableSharedFlow<ByteArray>()
        override val incoming = _incoming.asSharedFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sent.add(payload.copyOf())
        }
    }

    @Test
    fun `emitTextMessage cifra y el peer descifra con la misma clave de rodada`() = runBlocking {
        val key = ByteArray(32) { (it + 1).toByte() }
        val txTransport = CapturingTransport()
        val tx = MeshRelayEngine(
            localShortId = 100u,
            transport = txTransport,
            rideSecretKey = key,
            relayScope = this
        ,
        requireAuthorization = false
        )

        val packet = tx.emitTextMessage(TextMessagePacket.BROADCAST_TARGET, "hola")
        assertNotNull(packet)
        assertEquals(100u.toUShort(), packet!!.header.senderShortId)
        delay(50) // el transport.send se lanza en relayScope (fire-and-forget)
        assertEquals(MeshHeader.TYPE_TEXT_MESSAGE, packet.header.packetType)
        assertEquals(1, txTransport.sent.size)

        val wire = txTransport.sent.first()
        val parsed = TextMessagePacket.fromByteArray(wire)
        assertEquals(TextMessagePacket.BROADCAST_TARGET, parsed.targetShortId)
        assertEquals(4, parsed.ciphertext.size)
        assertTrue("El payload en el aire debe ser cifrado (no contener 'hola')",
            !String(wire, Charsets.ISO_8859_1).contains("hola"))

        var receivedText: String? = null
        var receivedSender: UShort? = null
        val rx = MeshRelayEngine(
            localShortId = 200u,
            rideSecretKey = key
        ,
        requireAuthorization = false
        )
        rx.onTextMessageReceived = { p, text ->
            receivedSender = p.header.senderShortId
            receivedText = text
        }

        val decision = rx.processIncomingPacket(wire)
        assertTrue("Decisión: $decision", decision !is MeshRelayEngine.RelayDecision.DroppedMalformed)
        assertEquals(100u.toUShort(), receivedSender)
        assertEquals("hola", receivedText)
    }

    @Test
    fun `mensaje dirigido a otro nodo no se entrega localmente`() = runBlocking {
        val key = ByteArray(32) { (it + 9).toByte() }
        val txTransport = CapturingTransport()
        val tx = MeshRelayEngine(localShortId = 100u, transport = txTransport, rideSecretKey = key, relayScope = this, requireAuthorization = false)
        tx.emitTextMessage(999u, "privado")
        delay(50)

        var delivered = false
        val rx = MeshRelayEngine(localShortId = 200u, rideSecretKey = key, requireAuthorization = false)
        rx.onTextMessageReceived = { _, _ -> delivered = true }
        rx.processIncomingPacket(txTransport.sent.first())
        assertEquals(false, delivered)
    }
}
