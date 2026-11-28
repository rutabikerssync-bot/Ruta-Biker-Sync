package com.rutabikersync.app.core.mesh.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class PttState {
    object Idle : PttState()
    data class Transmitting(val frameIndex: Int, val packetsSent: Int) : PttState()
    data class Receiving(val senderShortId: Int, val frameIndex: Int, val packetsReceived: Int) : PttState()
}

/**
 * Motor de Audio PTT para captura, codificación, transmisión, decodificación y reproducción.
 * Conforme al Pliego de Arquitectura (Hito 2 y Hito 3 · Etapa 1):
 * - Audio PCM 16-bit, 16 kHz Mono (Frames de 20 ms = 320 muestras).
 * - Codec Opus (Concentus / MediaCodec) a 20 kbps (~40-60B por frame).
 * - Cifrado ChaCha20-Poly1305 por frame con zeroing de buffers (R12).
 * - JitterBuffer adaptativo (40-80 ms) con timeout estricto (60 ms) y Packet Loss Concealment (PLC).
 * - Descarte estricto de paquetes propios (loopback suppression).
 * - Gestión desacoplada de AudioFocus ([AudioFocusController]).
 */
class PttAudioEngine(
    private val scope: CoroutineScope,
    private val clubNetworkId: UInt = 0x52415301u,
    val localShortId: UShort = 1u,
    // P0.4: sin clave derivada del manifiesto NO se cifra voz. El valor por defecto es null
    // a propósito: nunca más una clave maestra fija y predecible.
    private val clubPttKey: ByteArray? = null,
    val audioFocusController: AudioFocusController? = null,
    private val onSendVoicePacket: (VoicePacket) -> Unit
) {
    companion object {
        private const val TAG = "PttAudioEngine"
        /** Duración máxima de una ráfaga PTT (misma que OpusVoiceRecorder). */
        const val MAX_TALK_SECONDS = 15
    }

    private val _pttState = MutableStateFlow<PttState>(PttState.Idle)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    private fun pttKeyOrFailClosed(): ByteArray? {
        val key = clubPttKey
        if (key == null || key.size != 32) {
            Log.w(TAG, "FAIL_CLOSED_PTT_KEY_UNAVAILABLE: PTT deshabilitado sin clave del manifiesto")
            return null
        }
        return key
    }

    private val isTransmitting = AtomicBoolean(false)
    private val isReceiving = AtomicBoolean(false)
    private val sequenceCounter = AtomicInteger(1)

    /**
     * La UI de LiveMap es una pantalla muy pesada: publicar el estado PTT en cada trama
     * (20 ms) bloquea el hilo principal y llega a impedir incluso el tap de "parar".
     * Se publica como máximo 1 vez por segundo; el audio y el cifrado son independientes.
     */
    private var lastUiStatePublishMs = 0L
    private fun publishTransmittingState(frameIndex: Int, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastUiStatePublishMs < 1000L) return
        lastUiStatePublishMs = nowMs
        _pttState.value = PttState.Transmitting(frameIndex = frameIndex, packetsSent = voicePacketsSent.get())
    }
    private fun publishReceivingState(senderId: Int, frameIndex: Int, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastUiStatePublishMs < 1000L) return
        lastUiStatePublishMs = nowMs
        _pttState.value = PttState.Receiving(
            senderShortId = senderId,
            frameIndex = frameIndex,
            packetsReceived = voicePacketsReceived.get()
        )
    }

    // Métricas de paquetes de voz
    val voicePacketsSent = AtomicInteger(0)
    val voicePacketsReceived = AtomicInteger(0)
    val voicePacketsLost = AtomicInteger(0)

    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val encoder = ConcentusOpusEncoder()
    private val decoder = ConcentusOpusDecoder()
    val jitterBuffer = JitterBuffer(initialBufferFrames = 2, maxBufferFrames = 4, maxWaitTimeMs = 60L)

    /**
     * Inicia la transmisión PTT (Pulsar para hablar).
     */
    @SuppressLint("MissingPermission")
    fun startTalking() {
        if (pttKeyOrFailClosed() == null) {
            _pttState.value = PttState.Idle
            return
        }
        if (!isTransmitting.compareAndSet(false, true)) {
            return
        }
        Log.i(TAG, "PTT: Iniciando transmisión de audio...")
        lastUiStatePublishMs = System.currentTimeMillis()
        _pttState.value = PttState.Transmitting(frameIndex = 0, packetsSent = voicePacketsSent.get())

        recordJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                OpusConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(OpusConfig.FRAME_SIZE_BYTES * 4)

            val pcmFrame = ShortArray(OpusConfig.FRAME_SIZE_SAMPLES)
            val key = pttKeyOrFailClosed() ?: return@launch
            val currentSeq = sequenceCounter.getAndIncrement() and 0xFFFF
            var frameIdx = 0

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    OpusConfig.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                audioRecord?.startRecording()
                encoder.reset()

                while (isActive && isTransmitting.get()) {
                    val samplesRead = audioRecord?.read(pcmFrame, 0, OpusConfig.FRAME_SIZE_SAMPLES) ?: 0
                    if (samplesRead == OpusConfig.FRAME_SIZE_SAMPLES) {
                        // 1. Codificar con Opus
                        val opusBytes = encoder.encode(pcmFrame)

                        // 2. Cifrar con ChaCha20-Poly1305
                        val nonce = VoiceCrypto.buildDeterministicNonce(
                            clubNetworkId = clubNetworkId.toInt(),
                            senderShortId = localShortId.toInt(),
                            sequenceNum = currentSeq,
                            frameIndex = frameIdx
                        )
                        val encryptedPayload = VoiceCrypto.encrypt(key, nonce, opusBytes)

                        // 3. Empaquetar y despachar por radio BLE
                        val header = com.rutabikersync.app.core.mesh.MeshHeader(
                            clubNetworkId = clubNetworkId,
                            senderShortId = localShortId,
                            packetType = com.rutabikersync.app.core.mesh.MeshHeader.TYPE_PTT_VOICE,
                            initTtl = 1,
                            currentTtl = 1,
                            sequenceNum = currentSeq.toUShort()
                        )
                        val packet = VoicePacket(
                            header = header,
                            frameIndex = frameIdx,
                            isLastFrame = false,
                            nonce = nonce,
                            encryptedPayloadWithTag = encryptedPayload
                        )

                        onSendVoicePacket(packet)
                        voicePacketsSent.incrementAndGet()
                        publishTransmittingState(frameIdx)
                        frameIdx++
                    }
                }

                // Enviar último frame marcando isLastFrame = true
                val finalNonce = VoiceCrypto.buildDeterministicNonce(
                    clubNetworkId = clubNetworkId.toInt(),
                    senderShortId = localShortId.toInt(),
                    sequenceNum = currentSeq,
                    frameIndex = frameIdx
                )
                val dummyOpus = encoder.encode(ShortArray(OpusConfig.FRAME_SIZE_SAMPLES))
                val finalEncrypted = VoiceCrypto.encrypt(key, finalNonce, dummyOpus)
                val finalHeader = com.rutabikersync.app.core.mesh.MeshHeader(
                    clubNetworkId = clubNetworkId,
                    senderShortId = localShortId,
                    packetType = com.rutabikersync.app.core.mesh.MeshHeader.TYPE_PTT_VOICE,
                    initTtl = 1,
                    currentTtl = 1,
                    sequenceNum = currentSeq.toUShort()
                )
                onSendVoicePacket(
                    VoicePacket(
                        header = finalHeader,
                        frameIndex = frameIdx,
                        isLastFrame = true,
                        nonce = finalNonce,
                        encryptedPayloadWithTag = finalEncrypted
                    )
                )
                voicePacketsSent.incrementAndGet()
            } catch (e: Exception) {
                Log.w(TAG, "Error en captura PTT: ${e.message}")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error liberando audioRecord: ${e.message}")
                }
                VoiceCrypto.zeroBuffer(pcmFrame)
                _pttState.value = PttState.Idle
            }
        }
    }

    /**
     * Detiene la transmisión PTT (Liberar para enviar).
     */
    fun stopTalking() {
        if (isTransmitting.compareAndSet(true, false)) {
            Log.i(TAG, "PTT: Transmisión finalizada por el usuario.")
            recordJob?.cancel()
            _pttState.value = PttState.Idle
        }
    }

    /**
     * Procesa un paquete de voz entrante desde la radio BLE.
     */
    fun onVoicePacketReceived(packet: VoicePacket) {
        // Regla 1: Descartar inmediatamente paquetes propios (supresión estricta de loopback)
        if (packet.header.senderShortId == localShortId) {
            return
        }

        // Regla 2: Evitar interrupción si el nodo local se encuentra transmitiendo
        if (isTransmitting.get()) {
            return
        }

        val key = pttKeyOrFailClosed() ?: run {
            voicePacketsLost.incrementAndGet()
            return
        }
        voicePacketsReceived.incrementAndGet()
        val senderId = packet.header.senderShortId.toInt()

        try {
            // 1. Descifrar con ChaCha20-Poly1305
            val decryptedOpus = VoiceCrypto.decrypt(
                key = key,
                nonce = packet.nonce,
                ciphertextAndTag = packet.encryptedPayloadWithTag
            )

            // 2. Insertar en JitterBuffer
            jitterBuffer.push(
                frameIndex = packet.frameIndex,
                opusPayload = decryptedOpus,
                isLast = packet.isLastFrame
            )

            publishReceivingState(senderId, packet.frameIndex)

            // 3. Iniciar bucle de reproducción si no está activo
            startPlaybackLoopIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "Error descifrando o procesando paquete de voz: ${e.message}")
            voicePacketsLost.incrementAndGet()
        }
    }

    @Synchronized
    private fun startPlaybackLoopIfNeeded() {
        if (!isReceiving.compareAndSet(false, true)) {
            return
        }

        audioFocusController?.requestPttFocus()

        playbackJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioTrack.getMinBufferSize(
                OpusConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(OpusConfig.FRAME_SIZE_BYTES * 4)

            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OpusConfig.SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()
                decoder.reset()

                var emptyBufferWaitCount = 0
                while (isActive && isReceiving.get()) {
                    val pcm = jitterBuffer.pullNextFrame(decoder)
                    if (pcm != null) {
                        emptyBufferWaitCount = 0
                        track.write(pcm, 0, pcm.size)
                        VoiceCrypto.zeroBuffer(pcm)
                    } else {
                        if (jitterBuffer.isEndOfStream) {
                            Log.i(TAG, "Fin de recepción de audio PTT.")
                            break
                        }
                        emptyBufferWaitCount++
                        // Con fragmentación BLE las tramas de voz llegan separadas ~2 s.
                        // 350 ms cerraba el AudioTrack entre tramas y borraba el banner de recepción.
                        if (emptyBufferWaitCount > 250) { // 250 * 10ms = 2,5 s sin tramas entrantes
                            Log.i(TAG, "Fin de ráfaga de voz PTT por inactividad.")
                            break
                        }
                        // Breve espera para que el jitter buffer acumule tramas
                        kotlinx.coroutines.delay(10)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error en reproducción PTT: ${e.message}")
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error liberando audioTrack: ${e.message}")
                }
                audioFocusController?.abandonPttFocus()
                jitterBuffer.reset()
                isReceiving.set(false)
                _pttState.value = PttState.Idle
            }
        }
    }

    /**
     * Calcula el Packet Loss Rate (PLR) de voz.
     */
    fun calculateVoicePlr(): Float {
        return jitterBuffer.calculatePlr()
    }

    fun release() {
        stopTalking()
        playbackJob?.cancel()
        audioFocusController?.abandonPttFocus()
        encoder.close()
        decoder.close()
    }
}
