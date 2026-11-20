package com.rutabikersync.app.core.ridelifecycle

import com.rutabikersync.app.core.audio.VoiceClipMeshRelay
import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.sos.SosBloodType
import com.rutabikersync.app.core.mesh.sos.SosIncidentCode
import com.rutabikersync.app.core.mesh.sos.SosPacket
import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caso 16: las reglas nuevas no alteran los contratos reales BLE/chat/SOS/PTT. */
class RideLifecycleCommunicationsRegressionTest {
    private class CapturingTransport : Transport {
        override val name: String = "TEST_BLE"
        val sent = mutableListOf<ByteArray>()
        private val incomingFlow = MutableSharedFlow<ByteArray>()
        override val incoming = incomingFlow.asSharedFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sent += payload.copyOf()
        }
    }

    @Test
    fun `16 regresion BLE chat SOS PTT conserva los contratos wire existentes`() = runBlocking {
        // BLE: cabecera binaria exacta, sin cambio de layout GATT.
        val header = MeshHeader(
            clubNetworkId = 0x11223344u,
            senderShortId = 7u,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = 5,
            currentTtl = 5,
            sequenceNum = 42u
        )
        val headerBytes = header.toByteArray()
        assertEquals(MeshHeader.HEADER_SIZE_BYTES, headerBytes.size)
        assertEquals(header, MeshHeader.fromByteArray(headerBytes))

        // Chat: cifrado/descifrado real por dos MeshRelayEngine con la misma clave.
        val key = ByteArray(32) { (it + 1).toByte() }
        val transport = CapturingTransport()
        val sender = MeshRelayEngine(localShortId = 100u, relayScope = this, transport = transport, rideSecretKey = key)
        val chatPacket = sender.emitTextMessage(TextMessagePacket.BROADCAST_TARGET, "hola")
        assertNotNull(chatPacket)
        delay(50)
        assertEquals(1, transport.sent.size)
        val wire = transport.sent.single()
        assertTrue(!String(wire, Charsets.ISO_8859_1).contains("hola"))

        var receivedText: String? = null
        val receiver = MeshRelayEngine(localShortId = 200u, rideSecretKey = key)
        receiver.onTextMessageReceived = { _, text -> receivedText = text }
        val decision = receiver.processIncomingPacket(wire)
        assertTrue(decision !is MeshRelayEngine.RelayDecision.DroppedMalformed)
        assertEquals("hola", receivedText)

        // SOS: mismo paquete binario y firma de 64 bytes, sin cambiar criptografía.
        val sos = SosPacket.create(
            clubNetworkId = 0x11223344u,
            senderShortId = 7u,
            sequenceNum = 43u,
            latitude = 40.416775,
            longitude = -3.703790,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1_761_000_000,
            signature = ByteArray(64) { it.toByte() },
            currentTtl = 5,
            hopCount = 0
        )
        val sosBytes = sos.toByteArray()
        assertEquals(SosPacket.PACKET_SIZE_BYTES, sosBytes.size)
        assertEquals(sos, SosPacket.fromByteArray(sosBytes))

        // PTT: serialización existente de fragmentos de voz.
        val audio = ByteArray(24) { it.toByte() }
        val chunk = VoiceClipMeshRelay.VoiceChunkPacket(
            clipId = "clip-regression",
            senderShortId = 7u,
            senderName = "Test Rider",
            senderRole = "MEMBER",
            durationSeconds = 3,
            chunkIndex = 0,
            totalChunks = 1,
            hopsTaken = 0,
            isEmergency = false,
            chunkData = audio
        )
        val restored = VoiceClipMeshRelay.VoiceChunkPacket.fromByteArray(chunk.toByteArray())
        assertNotNull(restored)
        assertEquals(chunk.clipId, restored!!.clipId)
        assertEquals(chunk.chunkIndex, restored.chunkIndex)
        assertEquals(chunk.totalChunks, restored.totalChunks)
        assertArrayEquals(audio, restored.chunkData)
    }
}
