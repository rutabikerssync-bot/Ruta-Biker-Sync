package com.rutabikersync.app.core.mesh.audio

import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.BleFragmentationCodec
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidencia unitaria de que la voz PTT usa el MISMO MeshRelayEngine activo y la MISMA
 * fragmentación de 24 B del chat táctico. El cifrado ChaCha20-Poly1305 se genera en
 * PttAudioEngine y viaja dentro de VoicePacket: aquí solo se comprueba transporte/RX.
 */
class VoiceMeshRelayTest {

    private class CapturingTransport : Transport {
        override val name: String = "TEST_BLE_VOICE"
        val sent = mutableListOf<Pair<ByteArray, Transport.Priority>>()
        private val _incoming = MutableSharedFlow<ByteArray>()
        override val incoming = _incoming.asSharedFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sent.add(payload.copyOf() to priority)
        }
    }

    private fun voicePacket(
        frameIndex: Int,
        last: Boolean = false,
        senderShortId: UShort = 100u
    ): VoicePacket {
        val key = ByteArray(32) { (it + 0xA5).toByte() }
        val nonce = VoiceCrypto.buildDeterministicNonce(
            clubNetworkId = 0x52425301.toInt(),
            senderShortId = senderShortId.toInt(),
            sequenceNum = 7,
            frameIndex = frameIndex
        )
        return VoicePacket(
            header = MeshHeader(
                clubNetworkId = 0x52425301u,
                senderShortId = senderShortId,
                packetType = MeshHeader.TYPE_PTT_VOICE,
                initTtl = 1,
                currentTtl = 1,
                sequenceNum = 7u
            ),
            frameIndex = frameIndex,
            isLastFrame = last,
            nonce = nonce,
            encryptedPayloadWithTag = VoiceCrypto.encrypt(key, nonce, ByteArray(56) { it.toByte() })
        )
    }

    @Test
    fun `emitVoicePacket fragmenta con 24B y prioridad VOICE`() = runBlocking {
        val transport = CapturingTransport()
        val tx = MeshRelayEngine(
            localShortId = 100u,
            transport = transport,
            relayScope = this
        )
        val packet = voicePacket(frameIndex = 0)
        assertTrue(packet.toBinary().size > BleFragmentationCodec.MAX_MANUFACTURER_PAYLOAD_BYTES)

        assertTrue(tx.emitVoicePacket(packet))
        delay(50)
        assertEquals(1, transport.sent.size)
        assertEquals(Transport.Priority.VOICE, transport.sent.first().second)
        assertEquals(1, tx.voicePacketsEmitted.get())

        // Simula exactamente lo que hace BleAdvTransport: fragmenta y reensambla.
        val frames = BleFragmentationCodec.fragment(transport.sent.first().first, 1234)
        assertTrue(frames.size > 1)
        frames.forEach { assertTrue(it.size <= BleFragmentationCodec.MAX_MANUFACTURER_PAYLOAD_BYTES) }
        val reassembler = BleFragmentationCodec.Reassembler()
        val reassembled = frames.mapNotNull { reassembler.feed(it) }.single()
        assertEquals(transport.sent.first().first.asList(), reassembled.asList())
    }

    @Test
    fun `peer recibe VoicePacket reensamblado aunque comparta sequenceNum`() = runBlocking {
        val transport = CapturingTransport()
        val tx = MeshRelayEngine(localShortId = 100u, transport = transport, relayScope = this)
        val rx = MeshRelayEngine(localShortId = 200u)

        val received = mutableListOf<VoicePacket>()
        val unsubscribe = rx.onVoiceReceived { received.add(it) }

        // Dos frames de la misma ráfaga: mismo sequenceNum, distinto frameIndex.
        tx.emitVoicePacket(voicePacket(frameIndex = 0))
        tx.emitVoicePacket(voicePacket(frameIndex = 1, last = true))
        delay(50)

        val reassembler = BleFragmentationCodec.Reassembler()
        transport.sent.forEach { (wire, _) ->
            BleFragmentationCodec.fragment(wire, 4321).forEach { frame ->
                reassembler.feed(frame)?.let { restored ->
                    rx.processIncomingPacket(restored)
                }
            }
        }

        assertEquals(2, received.size)
        assertEquals(0, received[0].frameIndex)
        assertEquals(1, received[1].frameIndex)
        assertTrue(received[1].isLastFrame)
        assertEquals(2, rx.voicePacketsReceived.get())

        unsubscribe()
        tx.emitVoicePacket(voicePacket(frameIndex = 2))
        delay(50)
        assertEquals(2, received.size)
    }

    @Test
    fun `no se entregan paquetes de voz propios ni de otra red`() = runBlocking {
        val transport = CapturingTransport()
        val tx = MeshRelayEngine(localShortId = 100u, transport = transport, relayScope = this)
        tx.emitVoicePacket(voicePacket(frameIndex = 0))
        delay(50)
        val wire = transport.sent.first().first

        var loopbackDelivered = false
        val self = MeshRelayEngine(localShortId = 100u)
        self.onVoiceReceived { loopbackDelivered = true }
        self.processIncomingPacket(wire)
        assertFalse(loopbackDelivered)

        var wrongNetworkDelivered = false
        val other = MeshRelayEngine(localShortId = 200u, clubNetworkId = 0x99999999u)
        other.onVoiceReceived { wrongNetworkDelivered = true }
        other.processIncomingPacket(wire)
        assertFalse(wrongNetworkDelivered)
    }
}
