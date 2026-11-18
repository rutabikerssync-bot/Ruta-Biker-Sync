package com.rutabikersync.app.core.mesh.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class OpusAudioCodecTest {

    @Test
    fun testEncodeAndDecodeOneSecondPcm() {
        val encoder = ConcentusOpusEncoder(
            sampleRate = OpusConfig.SAMPLE_RATE_HZ,
            channels = OpusConfig.CHANNELS,
            bitrateBps = OpusConfig.DEFAULT_BITRATE_BPS
        )
        val decoder = ConcentusOpusDecoder(
            sampleRate = OpusConfig.SAMPLE_RATE_HZ,
            channels = OpusConfig.CHANNELS
        )

        // 1 segundo = 50 frames de 20 ms (320 muestras por frame a 16 kHz)
        val totalFrames = 50
        var totalEncodedBytes = 0

        for (frameIdx in 0 until totalFrames) {
            // Generar onda senoidal sintética de 440 Hz (tono A4)
            val pcmFrame = ShortArray(OpusConfig.FRAME_SIZE_SAMPLES)
            for (i in pcmFrame.indices) {
                val time = (frameIdx * OpusConfig.FRAME_SIZE_SAMPLES + i).toDouble() / OpusConfig.SAMPLE_RATE_HZ
                pcmFrame[i] = (sin(2.0 * Math.PI * 440.0 * time) * 16000.0).toInt().toShort()
            }

            // Codificar con Opus
            val opusBytes = encoder.encode(pcmFrame)
            assertTrue("Frame de Opus debe ser compacto (entre 20 y 100 bytes)", opusBytes.size in 20..100)
            totalEncodedBytes += opusBytes.size

            // Decodificar con Opus
            val decodedPcm = decoder.decode(opusBytes)
            assertEquals("El frame decodificado debe tener 320 muestras exactas", OpusConfig.FRAME_SIZE_SAMPLES, decodedPcm.size)
        }

        // Bitrate promedio debe rondar los 16-24 kbps (2,000 - 3,000 bytes/seg)
        assertTrue(
            "Bitrate total de 1 segundo debe ser < 3,500 bytes (recibido: $totalEncodedBytes)",
            totalEncodedBytes in 1000..3500
        )

        encoder.close()
        decoder.close()
    }

    @Test
    fun testPacketLossConcealmentPlc() {
        val encoder = ConcentusOpusEncoder()
        val decoder = ConcentusOpusDecoder()

        // Enviar 2 frames para inicializar los estados armónicos del decodificador
        val pcm = ShortArray(OpusConfig.FRAME_SIZE_SAMPLES) { 1000 }
        val opusFrame = encoder.encode(pcm)
        decoder.decode(opusFrame)

        // Simular pérdida del siguiente frame: invocar PLC
        val plcPcm = decoder.decodePlc()
        assertNotNull(plcPcm)
        assertEquals(OpusConfig.FRAME_SIZE_SAMPLES, plcPcm.size)

        encoder.close()
        decoder.close()
    }
}

