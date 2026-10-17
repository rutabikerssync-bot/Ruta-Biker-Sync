package com.rutabikersync.app.core.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bus de radio BLE simulado para el Modo Desarrollo y las pruebas de validación de Fase 0.
 *
 * Permite conectar N nodos virtuales (por ejemplo, una fila de 5 motos en carretera donde cada moto
 * solo alcanza a sus vecinos adyacentes) para verificar el reenvío con TTL, la deduplicación,
 * el porcentaje de heartbeats entregados y la partición en islas sin necesidad de varios teléfonos físicos.
 */
class SimulatedBleMedium {
    private val endpoints = mutableMapOf<UShort, SimulatedBleTransport>()
    private val adjacency = mutableMapOf<UShort, MutableSet<UShort>>()

    @Volatile
    var packetLossProbability: Float = 0f

    @Volatile
    var islandSplitActive: Boolean = false

    fun createNodeTransport(nodeId: UShort): SimulatedBleTransport {
        val transport = SimulatedBleTransport(nodeId, this)
        synchronized(this) {
            endpoints[nodeId] = transport
        }
        return transport
    }

    /**
     * Configura una formación en línea (convoy de motos):
     * Moto 1 <-> Moto 2 <-> Moto 3 <-> Moto 4 <-> Moto 5
     * de modo que los extremos necesitan multi-salto (TTL) para verse entre sí.
     */
    fun connectChain(nodeIds: List<UShort>) {
        synchronized(this) {
            adjacency.clear()
            for (i in nodeIds.indices) {
                val id = nodeIds[i]
                val neighbors = adjacency.getOrPut(id) { mutableSetOf() }
                if (i > 0) neighbors.add(nodeIds[i - 1])
                if (i < nodeIds.lastIndex) neighbors.add(nodeIds[i + 1])
            }
        }
    }

    internal fun broadcastFrom(
        senderId: UShort,
        payload: ByteArray,
        deterministicDropPredicate: ((sender: UShort, receiver: UShort, payload: ByteArray) -> Boolean)? = null
    ) {
        val receivers: List<SimulatedBleTransport>
        synchronized(this) {
            val allowedNeighbors = adjacency[senderId] ?: endpoints.keys.filter { it != senderId }
            receivers = allowedNeighbors.mapNotNull { targetId ->
                if (islandSplitActive && isAcrossIslandBoundary(senderId, targetId)) {
                    null
                } else {
                    endpoints[targetId]?.takeIf { it.isStarted }
                }
            }
        }

        for (rx in receivers) {
            if (deterministicDropPredicate?.invoke(senderId, rx.nodeId, payload) == true) continue
            rx.deliverFromMedium(payload.copyOf())
        }
    }

    private fun isAcrossIslandBoundary(a: UShort, b: UShort): Boolean {
        // Parte el grupo en dos islas: nodos <= 2 en la isla delantera y nodos >= 3 en la isla trasera
        val islandA = if (a.toInt() <= 2) 0 else 1
        val islandB = if (b.toInt() <= 2) 0 else 1
        return islandA != islandB
    }
}

class SimulatedBleTransport(
    val nodeId: UShort,
    private val medium: SimulatedBleMedium
) : Transport {

    @Volatile
    var isStarted: Boolean = false
        private set

    override val name: String = "SimulatedBleTransport(node=$nodeId)"

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    override suspend fun start() {
        isStarted = true
    }

    override suspend fun stop() {
        isStarted = false
    }

    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        if (!isStarted) return
        medium.broadcastFrom(nodeId, payload)
    }

    internal fun deliverFromMedium(payload: ByteArray) {
        if (!isStarted) return
        _incoming.tryEmit(payload)
    }
}
