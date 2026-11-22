package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.mesh.text.TacticalChatMessage
import com.rutabikersync.app.core.mesh.text.TacticalChatStore
import com.rutabikersync.app.core.network.ClubApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Corte 2A de redundancia BLE ↔ 4G: canal efímero de chat y señales cuando el convoy
 * se estira y la malla BLE ya no alcanza.
 *
 * Reglas innegociables:
 * - BLE sigue siendo el canal primario; este despachador solo publica lo que llega por HTTP
 *   y solo hace uplink cuando el envío BLE falla explícitamente.
 * - No toca GATT, cifrado, PTT, liderazgo, `TacticalChatStore` ni la pantalla de chat.
 * - Cero persistencia: dedupe en RAM con ventana de 10 s y purga al detener la rodada (R12).
 * - Todo mensaje entrante se etiqueta con `[4G]` para no confundirlo con tráfico de malla.
 */
class HybridChatDispatcher(
    private val selfShortId: UShort = 65_535u,
    private val fetch: suspend (rideId: String, since: Long) -> Result<List<ClubApiClient.RideCommsItem>>,
    private val post: suspend (rideId: String, draft: ClubApiClient.RideCommsDraft) -> Result<ClubApiClient.ApiResponse>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pollDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = 5_000L,
    private val dedupeWindowMs: Long = 10_000L,
    private val signalKindOfCode: (Int) -> ConvoySignalKind? = { ConvoySignalKind.fromCode(it.toByte()) },
    private val onSignal: (MeshRelayEngine.IncomingConvoySignal) -> Unit = { RideService.notifyIncomingConvoySignal(it) }
) {
    @Volatile
    private var activeRideId: String? = null
    private var pollJob: Job? = null
    private var lastSeenAt = 0L

    private val seenIds = LinkedHashMap<String, Long>()

    fun start(rideId: String?, scope: CoroutineScope) {
        stop()
        if (rideId.isNullOrBlank() || runCatching { UUID.fromString(rideId) }.isFailure) return
        activeRideId = rideId
        pollJob = scope.launch(pollDispatcher) {
            while (isActive) {
                pollOnce(rideId)
                delay(pollIntervalMs)
            }
        }
    }

    /** R12: cancela el sondeo y limpia solo el estado efímero de este despachador. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        activeRideId = null
        lastSeenAt = 0L
        synchronized(seenIds) { seenIds.clear() }
    }

    /** Un sondeo HTTP. Nunca lanza excepciones hacia el frame de mapa ni hacia la UI. */
    suspend fun pollOnce(rideId: String) {
        val items = runCatching { fetch(rideId, lastSeenAt) }.getOrNull()?.getOrNull() ?: return
        if (items.isEmpty()) return

        val now = clock()
        var newest = lastSeenAt
        items.forEach { item ->
            if (item.createdAtMillis <= lastSeenAt) return@forEach
            deliver(item, now)
            if (item.createdAtMillis > newest) newest = item.createdAtMillis
        }
        // -1 evita perder dos items con el mismo milisegundo; el dedupe por id los absorbe.
        lastSeenAt = maxOf(lastSeenAt, newest - 1)
    }

    /**
     * Entrega un mensaje HTTP a los canales existentes. `true` cuando es nuevo.
     * El dedupe es de dos capas: id (10 s) y el LRU por contenido del propio store.
     */
    fun deliver(item: ClubApiClient.RideCommsItem, now: Long = clock()): Boolean {
        if (item.senderShortId == selfShortId) return false
        if (now - item.createdAtMillis > MAX_TTL_MS) return false
        if (!rememberId(item.id, now)) return false

        when (item.kind) {
            "chat" -> {
                val text = item.text ?: return false
                if (text.isBlank()) return false
                TacticalChatStore.addMessage(
                    TacticalChatMessage(
                        id = item.id,
                        senderShortId = item.senderShortId,
                        senderName = "Piloto #${item.senderShortId.toInt()} [4G]",
                        targetShortId = item.targetShortId ?: BROADCAST_TARGET,
                        text = text,
                        timestampMs = item.createdAtMillis,
                        isOutgoing = false
                    )
                )
            }
            "signal" -> {
                val kind = item.signalKind?.let(signalKindOfCode) ?: return false
                onSignal(
                    MeshRelayEngine.IncomingConvoySignal(
                        senderShortId = item.senderShortId,
                        signalKind = kind,
                        sequenceNumber = item.sequence ?: 0,
                        hopsTaken = 0
                    )
                )
            }
            else -> return false
        }
        return true
    }

    /** Uplink de chat: solo se llama cuando `dispatchSendTextMessage` devolvió `false`. */
    suspend fun publishChat(targetShortId: UShort, text: String) {
        val rideId = activeRideId ?: return
        val draft = ClubApiClient.RideCommsDraft(
            id = UUID.randomUUID().toString(),
            kind = "chat",
            senderShortId = selfShortId,
            targetShortId = targetShortId,
            text = text,
            ttlSeconds = DEFAULT_TTL_SECONDS
        )
        runCatching { post(rideId, draft) }
    }

    /** Uplink de señal rápida: mismo contrato efímero, sin tocar el motor BLE. */
    suspend fun publishSignal(signal: ConvoySignalKind) {
        val rideId = activeRideId ?: return
        val draft = ClubApiClient.RideCommsDraft(
            id = "signal-$rideId-${selfShortId.toInt()}-${signal.code}-${clock()}",
            kind = "signal",
            senderShortId = selfShortId,
            signalKind = signal.code.toInt(),
            sequence = 0,
            ttlSeconds = DEFAULT_TTL_SECONDS
        )
        runCatching { post(rideId, draft) }
    }

    private fun rememberId(id: String, now: Long): Boolean {
        synchronized(seenIds) {
            seenIds.entries.removeAll { now - it.value > dedupeWindowMs }
            val last = seenIds[id]
            seenIds[id] = now
            return last == null || now - last >= dedupeWindowMs
        }
    }

    private companion object {
        val BROADCAST_TARGET: UShort = 65_535u
        const val DEFAULT_TTL_SECONDS = 60
        const val MAX_TTL_MS = 120_000L
    }
}
