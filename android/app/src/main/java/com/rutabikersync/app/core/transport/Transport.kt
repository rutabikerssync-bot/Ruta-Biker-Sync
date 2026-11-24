package com.rutabikersync.app.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * Medio de transporte intercambiable (BLE advertising, Nearby, LoRa en el futuro).
 * Definido según DESIGN.md §2-3 y setup.sh.
 */
interface Transport {
    val name: String
    suspend fun start()
    suspend fun stop()
    suspend fun send(payload: ByteArray, priority: Priority)

    /**
     * Envío de heartbeat de posición. El transporte puede usar un perfil de ráfaga más corto
     * que el de los paquetes sensibles; por delegar en [send] en la implementación por defecto,
     * los transports simulados y GATT no necesitan cambios.
     */
    suspend fun sendHeartbeat(payload: ByteArray, priority: Priority) = send(payload, priority)

    val incoming: Flow<ByteArray>
    val incomingPackets: Flow<IncomingPacket>? get() = null

    data class IncomingPacket(
        val bytes: ByteArray,
        val rssi: Int? = null,
        val timestampEpochMs: Long = System.currentTimeMillis(),
        val senderAddressSuffix: String? = null,
        val scanTimestampNanos: Long? = null
    )

    /**
     * Orden de prioridad estricto de DESIGN.md §3:
     * SOS > pérdida/reconexión (PEER_EVENT) > posición (POSITION) > ruta (ROUTE) > voz (VOICE) > estado (STATUS)
     */
    enum class Priority {
        SOS,
        PEER_EVENT,
        POSITION,
        ROUTE,
        VOICE,
        STATUS
    }
}
