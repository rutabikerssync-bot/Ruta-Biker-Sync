package com.rutabikersync.app.core.mesh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rastreador direccional de métricas de pérdida de paquetes (PLR) por Peer.
 * Conforme a las directivas de la Fase 6 (Hito 3 · Etapa 1):
 * - Monitorea métricas aisladas para cada [senderShortId].
 * - Registra tramas recibidas, esperadas y perdidas a partir de saltos de secuencia monotónica.
 * - Soporta manejo de desbordamiento (wraparound) de uint16 (0..65535).
 * - Distingue duplicados (diff = 0) y reinicios de peer (diff > 32767).
 * - Permite notificar el fin de secuencia [recordStreamEnd] para contabilizar pérdidas en cola.
 */
class PeerDirectionalTracker {

    data class PeerStats(
        var lastBootEpoch: Int = -1,
        var lastSequenceNum: Int = -1,
        val receivedCount: AtomicInteger = AtomicInteger(0),
        val expectedCount: AtomicInteger = AtomicInteger(0),
        val lostCount: AtomicInteger = AtomicInteger(0)
    )

    private val peerStatsMap = ConcurrentHashMap<UShort, PeerStats>()

    fun reset() {
        peerStatsMap.clear()
    }

    /**
     * Registra un paquete recibido de [senderShortId] con [sequenceNum] (0..65535) y [bootEpoch] (0..255).
     */
    fun recordPacket(senderShortId: UShort, sequenceNum: Int, bootEpoch: Int = 0) {
        val stats = peerStatsMap.computeIfAbsent(senderShortId) { PeerStats() }
        synchronized(stats) {
            // 1. Detección de reinicio por cambio de época (bootEpoch diferente al último registrado para este peer)
            if (stats.lastBootEpoch != -1 && stats.lastBootEpoch != bootEpoch) {
                stats.lastBootEpoch = bootEpoch
                stats.lastSequenceNum = sequenceNum
                stats.receivedCount.set(1)
                stats.expectedCount.set(1)
                stats.lostCount.set(0)
                return
            }

            if (stats.lastBootEpoch == -1) {
                stats.lastBootEpoch = bootEpoch
            }

            val last = stats.lastSequenceNum
            if (last == -1) {
                // Primer paquete recibido de este peer
                stats.lastSequenceNum = sequenceNum
                stats.receivedCount.set(1)
                stats.expectedCount.set(1)
                stats.lostCount.set(0)
                return
            }

            val diff = (sequenceNum - last) and 0xFFFF
            when {
                diff == 0 -> {
                    // Mismo paquete recibido nuevamente (duplicado por BLE/antena). No es nueva pérdida ni nueva recepción esperada.
                }
                diff in 1..32767 -> {
                    // Avance de secuencia normal (incluye saltos o huecos)
                    stats.receivedCount.incrementAndGet()
                    stats.expectedCount.addAndGet(diff)
                    if (diff > 1) {
                        val lost = diff - 1
                        stats.lostCount.addAndGet(lost)
                    }
                    stats.lastSequenceNum = sequenceNum
                }
                else -> {
                    // diff > 32767 indica reinicio de secuencia del peer o paquete tardío fuera de orden
                    val reverseDiff = (last - sequenceNum) and 0xFFFF
                    if (reverseDiff in 1..100) {
                        // Paquete tardío fuera de orden: contabilizar como recibido y mitigar pérdida previa
                        stats.receivedCount.incrementAndGet()
                        if (stats.lostCount.get() > 0) {
                            stats.lostCount.decrementAndGet()
                        }
                    } else {
                        // Salto inverso sin cambio de época explícito: reinicio defensivo sin sumar 65000 falsas pérdidas
                        stats.lastSequenceNum = sequenceNum
                        stats.receivedCount.set(1)
                        stats.expectedCount.set(1)
                        stats.lostCount.set(0)
                    }
                }
            }
        }
    }

    /**
     * Registra el final de un stream si el emisor comunicó su [finalSequenceNum] esperado.
     */
    fun recordStreamEnd(senderShortId: UShort, finalSequenceNum: Int) {
        val stats = peerStatsMap[senderShortId] ?: return
        synchronized(stats) {
            val last = stats.lastSequenceNum
            if (last != -1) {
                val diff = (finalSequenceNum - last) and 0xFFFF
                if (diff in 1..32767) {
                    stats.expectedCount.addAndGet(diff)
                    stats.lostCount.addAndGet(diff)
                    stats.lastSequenceNum = finalSequenceNum
                }
            }
        }
    }

    fun getReceivedCount(senderShortId: UShort): Int =
        peerStatsMap[senderShortId]?.receivedCount?.get() ?: 0

    fun getExpectedCount(senderShortId: UShort): Int =
        peerStatsMap[senderShortId]?.expectedCount?.get() ?: 0

    fun getLostCount(senderShortId: UShort): Int =
        peerStatsMap[senderShortId]?.lostCount?.get() ?: 0

    /**
     * Calcula el Packet Loss Rate direccional: PLR = (Lost / Expected) * 100.
     */
    fun calculatePlr(senderShortId: UShort): Float {
        val stats = peerStatsMap[senderShortId] ?: return 0f
        val exp = stats.expectedCount.get()
        if (exp == 0) return 0f
        val lost = stats.lostCount.get()
        return (lost.toFloat() / exp.toFloat()) * 100f
    }

    fun getAllTrackedPeers(): Set<UShort> = peerStatsMap.keys.toSet()
}

