package com.rutabikersync.app.core.transport

/**
 * Canal de streaming de voz separado del transporte de malla por advertising.
 *
 * El advertising legacy sigue llevando chat, SOS, heartbeats y posición. La voz PTT
 * necesita un enlace con mucho más caudal (Opus 20 kbps ≈ 2 500 B/s frente a los
 * ~48 B/s medidos en advertising), por lo que se entrega por un transporte de
 * conexión (GATT) que expone esta interfaz.
 *
 * Implementación real: [GattVoiceTransport]. El fallback cuando no está listo sigue
 * siendo [BleAdvTransport.sendVoice], para no romper despliegues sin soporte GATT.
 */
interface VoiceStreamTransport : Transport {
    /** true cuando la conexión, el MTU y las notificaciones ya están negociados. */
    val isVoiceReady: Boolean

    /**
     * Envía un `VoicePacket` YA cifrado (ChaCha20-Poly1305) por el stream.
     * Devuelve false si la cola interna está llena; el llamador contabiliza el descarte.
     */
    suspend fun sendVoiceFrame(payload: ByteArray): Boolean
}
