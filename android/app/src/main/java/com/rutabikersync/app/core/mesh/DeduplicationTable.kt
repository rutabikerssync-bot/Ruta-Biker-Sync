package com.rutabikersync.app.core.mesh

import java.util.LinkedHashMap

/**
 * Tabla LRU de Deduplicación en Memoria (Fase 6 · Hito 1 · BLE_MESH_ARCHITECTURE_HITO_0.md §6.1).
 *
 * Evita bucles de tormenta de difusión y retransmisión redundante:
 * - Capacidad máxima: 1,024 entradas (política LRU estricta).
 * - TTL de purga: 30,000 ms (30 segundos).
 * - Clave: (senderShortId, sequenceNum) empaquetados en un Long de 32 bits efectivos.
 */
class DeduplicationTable(
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 1024
        const val DEFAULT_ENTRY_TTL_MS = 30_000L // 30 segundos
    }

    private val lock = Any()

    // LinkedHashMap con accessOrder = true para mantener orden LRU
    private val lruMap = object : LinkedHashMap<Long, Long>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean {
            return size > maxCapacity
        }
    }

    /**
     * Evalúa si un paquete recibido debe procesarse o descartarse por duplicado.
     * Si no ha sido visto o ya expiró (> 30s), se registra con la marca temporal actual y devuelve true.
     * Si ya existe y está vigente, se renueva su posición LRU y devuelve false (descartar).
     */
    fun shouldProcessAndMark(
        senderShortId: UShort,
        sequenceNum: UShort,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = buildKey(senderShortId, sequenceNum)
        synchronized(lock) {
            val lastSeen = lruMap[key]
            if (lastSeen != null && (nowMs - lastSeen) <= entryTtlMs) {
                // Duplicado dentro de la ventana de validez
                return false
            }
            // Entrada nueva o expirada: registrar y aceptar
            lruMap[key] = nowMs
            return true
        }
    }

    /**
     * Purga manual o periódica de entradas que hayan superado el TTL de 30 segundos.
     * Retorna el número de entradas eliminadas.
     */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            var purged = 0
            val iterator = lruMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMs - entry.value > entryTtlMs) {
                    iterator.remove()
                    purged++
                }
            }
            return purged
        }
    }

    val size: Int
        get() = synchronized(lock) { lruMap.size }

    fun clear() {
        synchronized(lock) { lruMap.clear() }
    }

    fun containsKey(senderShortId: UShort, sequenceNum: UShort): Boolean {
        val key = buildKey(senderShortId, sequenceNum)
        return synchronized(lock) { lruMap.containsKey(key) }
    }

    private fun buildKey(senderShortId: UShort, sequenceNum: UShort): Long {
        return ((senderShortId.toLong() and 0xFFFFL) shl 16) or (sequenceNum.toLong() and 0xFFFFL)
    }
}

