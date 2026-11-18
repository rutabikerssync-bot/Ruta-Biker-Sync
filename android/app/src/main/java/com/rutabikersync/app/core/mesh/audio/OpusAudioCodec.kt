package com.rutabikersync.app.core.mesh.audio

import io.github.jaredmdobson.OpusApplication
import io.github.jaredmdobson.OpusDecoder as ConcentusDecoder
import io.github.jaredmdobson.OpusEncoder as ConcentusEncoder
import io.github.jaredmdobson.OpusSignal
import java.util.Arrays

/**
 * Parámetros estándar para codificación y decodificación de voz en malla BLE (Hito 2).
 * Conforme a §5 y §7 del Pliego de Arquitectura:
 * - Frecuencia de muestreo: 16,000 Hz (Wideband Voice).
 * - Canal: Mono (1 canal).
 * - Duración de frame: 20 ms.
 *   - A 16 kHz: 320 muestras por frame (640 bytes PCM 16-bit).
 * - Bitrate configurable: 16 kbps a 24 kbps.
 *   - A 16 kbps: ~40 bytes por frame Opus.
 *   - A 24 kbps: ~60 bytes por frame Opus.
 */
object OpusConfig {
    const val SAMPLE_RATE_HZ: Int = 16000
    const val CHANNELS: Int = 1
    const val FRAME_DURATION_MS: Int = 20
    const val FRAME_SIZE_SAMPLES: Int = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000 // 320 muestras
    const val FRAME_SIZE_BYTES: Int = FRAME_SIZE_SAMPLES * 2 // 640 bytes PCM 16-bit
    const val DEFAULT_BITRATE_BPS: Int = 20000 // 20 kbps (equilibrio voz/radio)
    const val MAX_PACKET_BYTES: Int = 120 // Margen seguro para 20ms a 24kbps
}

interface AudioEncoder {
    fun encode(pcm16: ShortArray): ByteArray
    fun reset()
    fun close()
}

interface AudioDecoder {
    fun decode(opusData: ByteArray): ShortArray
    fun decodePlc(): ShortArray
    fun reset()
    fun close()
}

/**
 * Implementación de códec Opus basada en Concentus (Java puro).
 * Proporciona paridad exacta entre la JVM (para pruebas unitarias) y dispositivos Android,
 * además de funcionar como fallback determinista cuando MediaCodec no soporta perfiles específicos de bitrate.
 */
class ConcentusOpusEncoder(
    private val sampleRate: Int = OpusConfig.SAMPLE_RATE_HZ,
    private val channels: Int = OpusConfig.CHANNELS,
    private val bitrateBps: Int = OpusConfig.DEFAULT_BITRATE_BPS,
    private val frameSize: Int = OpusConfig.FRAME_SIZE_SAMPLES
) : AudioEncoder {

    private var encoder: ConcentusEncoder? = null
    private val outputBuffer = ByteArray(OpusConfig.MAX_PACKET_BYTES)

    init {
        initEncoder()
    }

    private fun initEncoder() {
        val enc = ConcentusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
        enc.bitrate = bitrateBps
        enc.signalType = OpusSignal.OPUS_SIGNAL_VOICE
        enc.complexity = 5 // Balance óptimo entre fidelidad y consumo CPU en smartphone
        encoder = enc
    }

    override fun encode(pcm16: ShortArray): ByteArray {
        val enc = encoder ?: throw IllegalStateException("ConcentusOpusEncoder cerrado")
        require(pcm16.size == frameSize) {
            "El tamaño de PCM debe coincidir con frameSize=$frameSize (recibido: ${pcm16.size})"
        }
        val bytesEncoded = enc.encode(pcm16, 0, frameSize, outputBuffer, 0, outputBuffer.size)
        return outputBuffer.copyOf(bytesEncoded)
    }

    override fun reset() {
        encoder?.resetState()
    }

    override fun close() {
        encoder = null
        Arrays.fill(outputBuffer, 0.toByte())
    }
}

class ConcentusOpusDecoder(
    private val sampleRate: Int = OpusConfig.SAMPLE_RATE_HZ,
    private val channels: Int = OpusConfig.CHANNELS,
    private val frameSize: Int = OpusConfig.FRAME_SIZE_SAMPLES
) : AudioDecoder {

    private var decoder: ConcentusDecoder? = null
    private val pcmBuffer = ShortArray(frameSize)

    init {
        initDecoder()
    }

    private fun initDecoder() {
        decoder = ConcentusDecoder(sampleRate, channels)
    }

    override fun decode(opusData: ByteArray): ShortArray {
        val dec = decoder ?: throw IllegalStateException("ConcentusOpusDecoder cerrado")
        val samplesDecoded = dec.decode(opusData, 0, opusData.size, pcmBuffer, 0, frameSize, false)
        return pcmBuffer.copyOf(samplesDecoded)
    }

    /**
     * Packet Loss Concealment (PLC): Sintetiza el audio del frame perdido
     * extrapolando armónicos y energía de las tramas anteriores para evitar silencios bruscos o clics.
     */
    override fun decodePlc(): ShortArray {
        val dec = decoder ?: throw IllegalStateException("ConcentusOpusDecoder cerrado")
        val samplesDecoded = dec.decode(null, 0, 0, pcmBuffer, 0, frameSize, true)
        return pcmBuffer.copyOf(samplesDecoded)
    }

    override fun reset() {
        decoder?.resetState()
    }

    override fun close() {
        decoder = null
        Arrays.fill(pcmBuffer, 0.toShort())
    }
}
