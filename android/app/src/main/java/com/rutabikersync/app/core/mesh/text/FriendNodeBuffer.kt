package com.rutabikersync.app.core.mesh.text

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Búfer en RAM para Nodos Amigos (Store & Forward) en Malla BLE Off-Grid (Fase 6 · Hito 5 · Etapa 2).
 *
 * Características de Diseño:
 * - Capacidad estricta en memoria volátil: 256 KB ([MAX_CAPACITY_BYTES]).
 * - Cero persistencia en disco (no SQLite, no Room, no SharedPreferences).
 * - TTL de retención: 30 minutos ([DEFAULT_MESSAGE_TTL_MS] = 1,800,000 ms).
 * - Principio Zero-Knowledge: Almacena únicamente el blob cifrado [TextMessagePacket];
 *   el Nodo Amigo no tiene acceso al texto plano ni a las claves privadas de los usuarios.
 * - Almacenamiento Unicast exclusivo: Los mensajes broadcast (0xFFFF) no se retienen,
 *   se difunden de inmediato por flooding convencional.
 * - Cumplimiento R12: Al expirar o drenar, los datos se sanitizan con ceros ([Arrays.fill]).
 */
class FriendNodeBuffer(
    val maxCapacityBytes: Int = MAX_CAPACITY_BYTES,
    val messageTtlMs: Long = DEFAULT_MESSAGE_TTL_MS
) {

    /**
     * Entrada individual en el búfer de Store & Forward.
     */
    data class BufferedMessage(
        val packet: TextMessagePacket,
        val storedAtEpochMs: Long,
        val packetSizeBytes: Int
    )

    private val storage = ConcurrentHashMap<UShort, MutableList<BufferedMessage>>()
    private val currentAllocatedBytes = AtomicInteger(0)

    /**
     * Retorna los bytes actualmente ocupados en memoria volátil.
     */
    fun currentSizeBytes(): Int = currentAllocatedBytes.get()

    /**
     * Retorna el número total de mensajes pendientes retenidos en el búfer.
     */
    fun messageCount(): Int {
        var count = 0
        storage.values.forEach { list ->
            synchronized(list) {
                count += list.size
            }
        }
        return count
    }

    /**
     * Indica si existen mensajes pendientes retenidos para un destinatario específico.
     */
    fun hasMessagesFor(targetShortId: UShort): Boolean {
        val list = storage[targetShortId] ?: return false
        synchronized(list) {
            return list.isNotEmpty()
        }
    }

    /**
     * Retorna el número de mensajes retenidos en cola para un peer específico (Fase 7.0.2).
     */
    fun getPendingMessageCount(targetShortId: UShort): Int {
        val list = storage[targetShortId] ?: return 0
        synchronized(list) {
            return list.size
        }
    }

    /**
     * Intenta almacenar un [TextMessagePacket] unicast en el búfer del Nodo Amigo.
     *
     * @param packet Trama de mensaje de texto a almacenar
     * @param nowMs Marca temporal actual en milisegundos
     * @return `true` si el paquete fue almacenado con éxito; `false` si es broadcast o si el búfer está lleno
     */
    fun storeMessage(
        packet: TextMessagePacket,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        // Los mensajes broadcast no se retienen en Nodos Amigos (solo unicast táctico)
        if (packet.isBroadcast) {
            return false
        }

        val rawBytes = packet.toByteArray()
        val packetSize = rawBytes.size

        // Purgar mensajes expirados para liberar espacio antes de evaluar capacidad
        purgeExpired(nowMs)

        // Evaluar si cabe en el búfer de 256 KB
        if (currentAllocatedBytes.get() + packetSize > maxCapacityBytes) {
            return false // Búfer saturado
        }

        val list = storage.computeIfAbsent(packet.targetShortId) { mutableListOf() }
        synchronized(list) {
            list.add(BufferedMessage(packet, nowMs, packetSize))
            currentAllocatedBytes.addAndGet(packetSize)
        }

        return true
    }

    /**
     * Drena y recupera de forma atómica todos los mensajes retenidos para un peer que reaparece en rango.
     * Los mensajes son removidos del almacén y sus bytes descontados de la memoria ocupada.
     *
     * @param targetShortId ID corto del nodo que se descubrió en rango
     * @return Lista de [TextMessagePacket] pendientes en orden FIFO
     */
    fun drainForPeer(targetShortId: UShort): List<TextMessagePacket> {
        val list = storage.remove(targetShortId) ?: return emptyList()
        val result = mutableListOf<TextMessagePacket>()

        synchronized(list) {
            for (msg in list) {
                result.add(msg.packet)
                currentAllocatedBytes.addAndGet(-msg.packetSizeBytes)
            }
            list.clear()
        }

        return result
    }

    /**
     * Purga los mensajes cuyo tiempo de retención ha superado el TTL configurado (30 minutos).
     * Aplica sanitización con ceros para cumplir con la Regla R12.
     *
     * @param nowMs Marca temporal actual en milisegundos
     * @return Número de mensajes expirados y eliminados
     */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        var purgedCount = 0

        val iterator = storage.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val list = entry.value

            synchronized(list) {
                val listIterator = list.iterator()
                while (listIterator.hasNext()) {
                    val msg = listIterator.next()
                    if (nowMs - msg.storedAtEpochMs > messageTtlMs) {
                        // Sanitización forense en memoria RAM
                        Arrays.fill(msg.packet.ciphertext, 0.toByte())
                        Arrays.fill(msg.packet.authTag, 0.toByte())
                        currentAllocatedBytes.addAndGet(-msg.packetSizeBytes)
                        listIterator.remove()
                        purgedCount++
                    }
                }
                if (list.isEmpty()) {
                    iterator.remove()
                }
            }
        }

        return purgedCount
    }

    /**
     * Limpieza total y forzada del búfer (al cerrar rodada o reiniciar servicio).
     */
    fun clearAll() {
        storage.values.forEach { list ->
            synchronized(list) {
                for (msg in list) {
                    Arrays.fill(msg.packet.ciphertext, 0.toByte())
                    Arrays.fill(msg.packet.authTag, 0.toByte())
                }
                list.clear()
            }
        }
        storage.clear()
        currentAllocatedBytes.set(0)
    }

    companion object {
        /** 256 KB = 262,144 Bytes exactos en memoria volátil */
        const val MAX_CAPACITY_BYTES = 256 * 1024

        /** 30 minutos de retención máxima = 1,800,000 ms */
        const val DEFAULT_MESSAGE_TTL_MS = 30 * 60 * 1000L
    }
}
