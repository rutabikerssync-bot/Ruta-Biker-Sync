package com.rutabikersync.app.core.mesh.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Suite de pruebas unitarias para el timeout y Packet Loss Concealment (PLC) en [JitterBuffer].
 * Conforme a las directivas del Hito 2 y Hito 3 · Etapa 1:
 * - Parámetro configurable [maxWaitTimeMs] = 60 ms.
 * - Espera activa sin PLC si no ha transcurrido el timeout.
 * - Disparo garantizado de [AudioDecoder.decodePlc] al alcanzar los 60 ms de espera.
 * - Avance determinista del índice esperado de frame y registro de métricas de pérdida.
 */
class JitterBufferTimeoutTest {

    private class FakeAudioDecoder : AudioDecoder {
        var decodeCalls = 0
        var decodePlcCalls = 0

        override fun decode(opusData: ByteArray): ShortArray {
            decodeCalls++
            return ShortArray(OpusConfig.FRAME_SIZE_SAMPLES) { 1 }
        }

        override fun decodePlc(): ShortArray {
            decodePlcCalls++
            return ShortArray(OpusConfig.FRAME_SIZE_SAMPLES) { 0 }
        }

        override fun reset() {}
        override fun close() {}
    }

    private lateinit var decoder: FakeAudioDecoder
    private lateinit var jitterBuffer: JitterBuffer

    @Before
    fun setUp() {
        decoder = FakeAudioDecoder()
        jitterBuffer = JitterBuffer(
            initialBufferFrames = 2,
            maxBufferFrames = 4,
            maxWaitTimeMs = 60L
        )
    }

    @Test
    fun testTimeoutForcesPlcAfterSixtyMilliseconds() {
        // 1. Insertar frame 0 y frame 1 para satisfacer el pre-buffering inicial (40 ms)
        val dummyPayload = byteArrayOf(0x01, 0x02, 0x03)
        jitterBuffer.push(frameIndex = 0, opusPayload = dummyPayload)
        jitterBuffer.push(frameIndex = 1, opusPayload = dummyPayload)

        // 2. Extraer frame 0 en t = 0
        val pcm0 = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 0L)
        assertNotNull(pcm0)
        assertEquals(0, decoder.decodePlcCalls)
        assertEquals(1, decoder.decodeCalls)
        assertEquals(1, jitterBuffer.expectedFrameIndex)

        // 3. Extraer frame 1 en t = 20
        val pcm1 = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 20L)
        assertNotNull(pcm1)
        assertEquals(0, decoder.decodePlcCalls)
        assertEquals(2, decoder.decodeCalls)
        assertEquals(2, jitterBuffer.expectedFrameIndex)

        // 4. Ahora el búfer queda vacío. El frame 2 no ha llegado por radio BLE.
        // Primer sondeo en t = 40 (inicia el temporizador de espera en t=40)
        val waitAt40 = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 40L)
        assertNull("Debe retornar null porque está esperando en la ventana de timeout", waitAt40)
        assertEquals(0, decoder.decodePlcCalls)

        // 5. Sondeo en t = 80 (han transcurrido 40 ms < 60 ms de espera)
        val waitAt80 = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 80L)
        assertNull("Debe seguir esperando a los 40 ms de transcurso", waitAt80)
        assertEquals(0, decoder.decodePlcCalls)

        // 6. Sondeo en t = 105 (han transcurrido 65 ms >= 60 ms de espera)
        // El timeout ha expirado: DEBE disparar decodePlc() y avanzar expectedFrameIndex a 3
        val plcPcm = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 105L)
        assertNotNull("Debe sintetizar audio de reemplazo vía PLC tras el timeout de 60 ms", plcPcm)
        assertEquals(1, decoder.decodePlcCalls)
        assertEquals(1, jitterBuffer.totalFramesLost)
        assertEquals(1, jitterBuffer.totalFramesPlc)
        assertEquals(3, jitterBuffer.expectedFrameIndex)
    }

    @Test
    fun testHigherFrameTriggersImmediatePlcWithoutWaitingForTimeout() {
        // Enviar frame 0 y frame 1 para iniciar
        val dummyPayload = byteArrayOf(0x01, 0x02)
        jitterBuffer.push(frameIndex = 0, opusPayload = dummyPayload)
        jitterBuffer.push(frameIndex = 1, opusPayload = dummyPayload)

        jitterBuffer.pullNextFrame(decoder, currentTimeMs = 0L) // consume 0
        jitterBuffer.pullNextFrame(decoder, currentTimeMs = 20L) // consume 1

        // Llega el frame 3 directamente (frame 2 omitido/perdido)
        jitterBuffer.push(frameIndex = 3, opusPayload = dummyPayload)

        // Al pedir el frame 2, al ver que existe frame 3 (> 2), debe disparar PLC de inmediato en t=25ms
        val immediatePlc = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 25L)
        assertNotNull(immediatePlc)
        assertEquals(1, decoder.decodePlcCalls)
        assertEquals(1, jitterBuffer.totalFramesLost)
        assertEquals(3, jitterBuffer.expectedFrameIndex)

        // El siguiente llamado extrae el frame 3 legítimo sin PLC
        val frame3 = jitterBuffer.pullNextFrame(decoder, currentTimeMs = 45L)
        assertNotNull(frame3)
        assertEquals(1, decoder.decodePlcCalls)
        assertEquals(3, decoder.decodeCalls)
        assertEquals(4, jitterBuffer.expectedFrameIndex)
    }
}

