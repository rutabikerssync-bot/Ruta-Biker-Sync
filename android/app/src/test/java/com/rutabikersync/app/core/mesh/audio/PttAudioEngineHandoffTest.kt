package com.rutabikersync.app.core.mesh.audio

import com.rutabikersync.app.core.mesh.MeshHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suite de pruebas unitarias para [PttAudioEngine].
 * Conforme a los requisitos de la Fase 6 (Hito 3 · Etapa 1):
 * - Verificación de supresión estricta de loopback (descarte de paquetes propios senderShortId == localShortId).
 * - Verificación de handoff de paquetes de peers y llamada a [AudioFocusController.requestPttFocus].
 * - Liberación limpia de recursos y AudioFocus al invocar [PttAudioEngine.release].
 */
class PttAudioEngineHandoffTest {

    private class FakeAudioFocusController : AudioFocusController {
        var requestCalls = 0
        var abandonCalls = 0

        override fun requestPttFocus(): Boolean {
            requestCalls++
            return true
        }

        override fun abandonPttFocus() {
            abandonCalls++
        }
    }

    private lateinit var fakeFocus: FakeAudioFocusController
    private lateinit var scope: CoroutineScope
    private val key = ByteArray(32) { (it + 0xA5).toByte() }
    private val clubId: UInt = 0x52415301u
    private val localId: UShort = 1u

    @Before
    fun setUp() {
        fakeFocus = FakeAudioFocusController()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @Test
    fun testLoopbackSuppressionDropsOwnPackets() {
        val engine = PttAudioEngine(
            scope = scope,
            clubNetworkId = clubId,
            localShortId = localId,
            clubPttKey = key,
            audioFocusController = fakeFocus,
            onSendVoicePacket = {}
        )

        // Paquete con senderShortId = 1 (idéntico a localShortId)
        val ownHeader = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = localId,
            packetType = MeshHeader.TYPE_PTT_VOICE,
            initTtl = 1,
            currentTtl = 1,
            sequenceNum = 42u
        )
        val nonce = VoiceCrypto.buildDeterministicNonce(clubId.toInt(), localId.toInt(), 42, 0)
        val encryptedPayload = VoiceCrypto.encrypt(key, nonce, byteArrayOf(0x01, 0x02, 0x03))

        val ownPacket = VoicePacket(
            header = ownHeader,
            frameIndex = 0,
            isLastFrame = false,
            nonce = nonce,
            encryptedPayloadWithTag = encryptedPayload
        )

        engine.onVoicePacketReceived(ownPacket)

        assertEquals("Los paquetes propios NO deben incrementar el contador de recibidos", 0, engine.voicePacketsReceived.get())
        assertEquals("Los paquetes propios NO deben solicitar AudioFocus", 0, fakeFocus.requestCalls)
        assertEquals("El jitter buffer NO debe recibir tramas propias", 0, engine.jitterBuffer.totalFramesReceived)

        engine.release()
    }

    @Test
    fun testPeerPacketTriggersProcessingAndRequestsAudioFocus() {
        val engine = PttAudioEngine(
            scope = scope,
            clubNetworkId = clubId,
            localShortId = localId,
            clubPttKey = key,
            audioFocusController = fakeFocus,
            onSendVoicePacket = {}
        )

        val peerId: UShort = 2u
        val peerHeader = MeshHeader(
            clubNetworkId = clubId,
            senderShortId = peerId,
            packetType = MeshHeader.TYPE_PTT_VOICE,
            initTtl = 1,
            currentTtl = 1,
            sequenceNum = 1u
        )
        val nonce = VoiceCrypto.buildDeterministicNonce(clubId.toInt(), peerId.toInt(), 1, 0)
        val dummyOpus = byteArrayOf(0x48, 0x00, 0x01, 0x02)
        val encryptedPayload = VoiceCrypto.encrypt(key, nonce, dummyOpus)

        val peerPacket = VoicePacket(
            header = peerHeader,
            frameIndex = 0,
            isLastFrame = false,
            nonce = nonce,
            encryptedPayloadWithTag = encryptedPayload
        )

        engine.onVoicePacketReceived(peerPacket)

        assertEquals("El paquete del peer debe contabilizarse como recibido", 1, engine.voicePacketsReceived.get())
        assertEquals("Debe haberse insertado en el JitterBuffer", 1, engine.jitterBuffer.totalFramesReceived)
        assertTrue("Debe solicitar AudioFocus al recibir audio de un peer", fakeFocus.requestCalls >= 1)

        engine.release()
        assertTrue("Al liberar el motor debe abandonar AudioFocus", fakeFocus.abandonCalls >= 1)
    }
}

