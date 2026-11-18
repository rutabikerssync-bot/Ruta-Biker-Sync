package com.rutabikersync.app.core.mesh.audio

import com.rutabikersync.app.core.mesh.MeshHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePacketTest {

    @Test
    fun testVoicePacketBinaryRoundTrip() {
        val header = MeshHeader(
            clubNetworkId = 0x52415301u,
            senderShortId = 1u,
            packetType = MeshHeader.TYPE_PTT_VOICE,
            initTtl = 1,
            currentTtl = 1,
            sequenceNum = 100u
        )
        val nonce = VoiceCrypto.buildDeterministicNonce(0x52415301, 1, 100, 0)
        val encryptedPayload = ByteArray(56) { (it * 3).toByte() }

        val packet = VoicePacket(
            header = header,
            frameIndex = 0,
            isLastFrame = true,
            nonce = nonce,
            encryptedPayloadWithTag = encryptedPayload
        )

        val wire = packet.toBinary()
        // 9B header + 2B frameIndex + 1B flags + 12B nonce + 56B payload = 80 bytes
        assertEquals(80, wire.size)

        val restored = VoicePacket.fromBinary(wire)
        assertEquals(packet.header, restored.header)
        assertEquals(packet.frameIndex, restored.frameIndex)
        assertEquals(packet.isLastFrame, restored.isLastFrame)
        assertArrayEquals(packet.nonce, restored.nonce)
        assertArrayEquals(packet.encryptedPayloadWithTag, restored.encryptedPayloadWithTag)
    }

    @Test
    fun testJitterBufferOrderingAndPlcOnSimulatedLoss() {
        val encoder = ConcentusOpusEncoder()
        val decoder = ConcentusOpusDecoder()
        val jitterBuffer = JitterBuffer(initialBufferFrames = 2, maxBufferFrames = 4)

        // Codificar 10 frames de audio
        val opusFrames = mutableListOf<ByteArray>()
        val dummyPcm = ShortArray(OpusConfig.FRAME_SIZE_SAMPLES) { 500 }
        for (i in 0 until 10) {
            opusFrames.add(encoder.encode(dummyPcm))
        }

        // Simular pérdida del frame index = 4 (enviar 0, 1, 2, 3, [4 perdido], 5, 6, 7, 8, 9)
        for (i in 0 until 10) {
            if (i == 4) continue // PERDIDO
            jitterBuffer.push(i, opusFrames[i], isLast = (i == 9))
        }

        var framesExtracted = 0

        while (true) {
            val pcm = jitterBuffer.pullNextFrame(decoder) ?: break
            framesExtracted++
            assertEquals(OpusConfig.FRAME_SIZE_SAMPLES, pcm.size)
        }

        // Se deben haber extraído 10 frames en total (9 recibidos + 1 sintetizado por PLC)
        assertEquals(10, framesExtracted)
        assertEquals(1, jitterBuffer.totalFramesLost)
        assertEquals(1, jitterBuffer.totalFramesPlc)
        assertTrue("PLR debe ser exactamente 10% (1 frame perdido de 10 esperados)", jitterBuffer.calculatePlr() in 9.9f..10.1f)

        encoder.close()
        decoder.close()
    }
}
