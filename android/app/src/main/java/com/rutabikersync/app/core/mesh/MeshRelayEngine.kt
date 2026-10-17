package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.PriorityQueue

/**
 * Motor de reenvío por inundación controlada (Controlled Flooding), seguimiento de Heartbeats
 * y registro de métricas de campo para la Fase 0 (DESIGN.md §3 y BACKEND_LICENSING.md §7b).
 *
 * Reglas implementadas:
 * 1. Deduplicación estricta por (shortId, sequenceNumber): cada nodo retransmite un mensaje a lo sumo 1 vez.
 * 2. Decremento de TTL en cada salto: si currentTtl <= 1 al recibirlo, se procesa localmente pero NO se reenvía.
 * 3. Verificación de etiqueta de autenticación de 4 bytes antes de aceptar o reenviar.
 * 4. Cola priorizada según Transport.Priority (SOS > PEER_EVENT > POSITION > ROUTE > VOICE > STATUS).
 * 5. Heartbeat cada 3 s; declaración de pérdida tras 4–5 latidos perdidos (~12–15 s) con histéresis de reconexión.
 */
class MeshRelayEngine(
    val localShortId: UShort,
    private val transport: Transport,
    private val rideSecretKey: ByteArray,
    private val localDeviceModel: String = "Android-Generic",
    private val dedupCapacity: Int = 2048
) {
    companion object {
        const val HEARTBEAT_INTERVAL_MS: Long = 3_000L
        const val LOST_THRESHOLD_BEATS: Int = 5          // 5 * 3 s = 15 s (rango 4–5 latidos de DESIGN.md §3)
        const val LOST_TIMEOUT_MS: Long = HEARTBEAT_INTERVAL_MS * LOST_THRESHOLD_BEATS
        const val RECONNECT_HYSTERESIS_BEATS: Int = 2    // Histéresis para evitar falsas alarmas en curvas/túneles
    }

    data class PeerTrack(
        val shortId: UShort,
        val lastLatitude: Double,
        val lastLongitude: Double,
        val lastSpeedKmh: Int,
        val lastBatteryPercent: Int,
        val isSos: Boolean,
        val lastSeenEpochMs: Long,
        val lastSequence: Int,
        val expectedBeats: Int,
        val receivedBeats: Int,
        val consecutiveAfterLossBeats: Int,
        val isLost: Boolean,
        val lastHops: Int
    )

    @Serializable
    data class MeshFieldReport(
        val localNodeId: Int,
        val transportName: String,
        val participants: Int,
        val heartbeatsExpected: Int,
        val heartbeatsReceived: Int,
        val heartbeatsDeliveredPct: Float,
        val avgHops: Float,
        val islandEvents: Int,
        val packetsEmitted: Int,
        val packetsReceivedValid: Int,
        val packetsRelayed: Int,
        val duplicatesDropped: Int,
        val invalidAuthDropped: Int,
        val activeLostPeers: List<Int>,
        val deviceModels: Map<String, Int>
    )

    private data class QueuedRelay(
        val packet: MeshPacket,
        val enqueuedOrder: Long
    ) : Comparable<QueuedRelay> {
        override fun compareTo(other: QueuedRelay): Int {
            val prioCmp = this.packet.priority.ordinal.compareTo(other.packet.priority.ordinal)
            return if (prioCmp != 0) prioCmp else this.enqueuedOrder.compareTo(other.enqueuedOrder)
        }
    }

    private val seenKeys = object : LinkedHashMap<Long, Boolean>(dedupCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean {
            return size > dedupCapacity
        }
    }

    private val peerMap = mutableMapOf<UShort, PeerTrack>()
    private val relayQueue = PriorityQueue<QueuedRelay>()
    private val deviceModels = mutableMapOf(localDeviceModel to 1)

    private var sequenceCounter: Int = 0
    private var enqueueSequence: Long = 0L
    private var packetsEmitted: Int = 0
    private var packetsReceivedValid: Int = 0
    private var packetsRelayed: Int = 0
    private var duplicatesDropped: Int = 0
    private var invalidAuthDropped: Int = 0
    private var totalHopsAccumulated: Long = 0L
    private var islandEvents: Int = 0
    private var wasInIslandState: Boolean = false

    private var collectJob: Job? = null

    private val _metrics = MutableStateFlow(buildReport())
    val metrics: StateFlow<MeshFieldReport> = _metrics.asStateFlow()

    private val _peers = MutableStateFlow<Map<UShort, PeerTrack>>(emptyMap())
    val peers: StateFlow<Map<UShort, PeerTrack>> = _peers.asStateFlow()

    suspend fun start(scope: CoroutineScope) {
        transport.start()
        collectJob?.cancel()
        collectJob = scope.launch {
            transport.incoming.collect { raw ->
                onRawPacketReceived(raw, System.currentTimeMillis())
            }
        }
    }

    suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        transport.stop()
    }

    /**
     * Emite un paquete propio (heartbeat de posición o SOS) y lo registra en la tabla de deduplicación
     * para no retransmitir nuestro propio eco.
     */
    suspend fun emitLocalPosition(
        latitude: Double,
        longitude: Double,
        speedKmh: Int,
        bearingDegrees: Float,
        batteryPercent: Int,
        isSos: Boolean = false,
        isExtendedBle: Boolean = false,
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): MeshPacket {
        val seq = sequenceCounter
        sequenceCounter = (sequenceCounter + 1) and MeshPacket.MAX_SEQUENCE_NUMBER

        val packet = MeshPacket.createSigned(
            shortId = localShortId,
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            bearingDegrees = bearingDegrees,
            batteryPercent = batteryPercent,
            isSos = isSos,
            isExtendedBle = isExtendedBle,
            priority = if (isSos) Transport.Priority.SOS else Transport.Priority.POSITION,
            sequenceNumber = seq,
            ttl = ttl,
            rideSecretKey = rideSecretKey
        )

        synchronized(this) {
            seenKeys[packet.dedupKey] = true
            packetsEmitted++
        }
        transport.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Procesa un paquete entrante crudo (20 bytes), actualiza métricas de entrega/saltos,
     * y si corresponde reenviarlo (TTL > 1 y no visto antes), lo encola por prioridad y lo emite.
     */
    suspend fun onRawPacketReceived(raw: ByteArray, nowEpochMs: Long = System.currentTimeMillis()) {
        if (raw.size != MeshPacket.PACKET_SIZE_BYTES) return
        val packet = runCatching { MeshPacket.fromByteArray(raw) }.getOrNull() ?: return

        if (!packet.verifyAuthTag(rideSecretKey)) {
            synchronized(this) { invalidAuthDropped++ }
            refreshStateFlows()
            return
        }

        // Ignorar ecos de nuestro propio nodo
        if (packet.shortId == localShortId) {
            synchronized(this) { duplicatesDropped++ }
            refreshStateFlows()
            return
        }

        var relayCandidate: MeshPacket? = null
        synchronized(this) {
            if (seenKeys.containsKey(packet.dedupKey)) {
                duplicatesDropped++
                refreshStateFlowsLocked()
                return
            }
            seenKeys[packet.dedupKey] = true
            packetsReceivedValid++
            totalHopsAccumulated += packet.hopsTaken

            updatePeerTrackLocked(packet, nowEpochMs)

            val decremented = packet.decrementTtlForRelay()
            if (decremented != null) {
                relayQueue.add(QueuedRelay(decremented, enqueueSequence++))
                relayCandidate = relayQueue.poll()?.packet
                if (relayCandidate != null) {
                    packetsRelayed++
                }
            }
            refreshStateFlowsLocked()
        }

        relayCandidate?.let { toRelay ->
            transport.send(toRelay.toByteArray(), toRelay.priority)
        }
    }

    /**
     * Evalúa qué nodos han superado el umbral de 4–5 heartbeats sin señal (~15 s)
     * y contabiliza eventos de partición (islas) cuando >=50% de los peers conocidos se pierden a la vez.
     */
    fun evaluatePeerTimeouts(nowEpochMs: Long = System.currentTimeMillis()): List<UShort> {
        val newlyLost = mutableListOf<UShort>()
        synchronized(this) {
            for ((id, track) in peerMap.entries) {
                val elapsed = nowEpochMs - track.lastSeenEpochMs
                if (!track.isLost && elapsed >= LOST_TIMEOUT_MS) {
                    val missedBeats = (elapsed / HEARTBEAT_INTERVAL_MS).toInt().coerceAtLeast(1)
                    peerMap[id] = track.copy(
                        isLost = true,
                        expectedBeats = track.expectedBeats + missedBeats,
                        consecutiveAfterLossBeats = 0
                    )
                    newlyLost.add(id)
                }
            }

            val totalPeers = peerMap.size
            val lostCount = peerMap.values.count { it.isLost }
            val currentlySplit = totalPeers >= 2 && lostCount * 2 >= totalPeers
            if (currentlySplit && !wasInIslandState) {
                islandEvents++
                wasInIslandState = true
            } else if (!currentlySplit) {
                wasInIslandState = false
            }

            refreshStateFlowsLocked()
        }
        return newlyLost
    }

    fun registerPeerDeviceModel(model: String) {
        synchronized(this) {
            deviceModels[model] = (deviceModels[model] ?: 0) + 1
            refreshStateFlowsLocked()
        }
    }

    fun exportReportJson(): String {
        val json = Json { prettyPrint = true }
        return json.encodeToString(metrics.value)
    }

    private fun updatePeerTrackLocked(packet: MeshPacket, nowEpochMs: Long) {
        val existing = peerMap[packet.shortId]
        if (existing == null) {
            peerMap[packet.shortId] = PeerTrack(
                shortId = packet.shortId,
                lastLatitude = packet.latitude,
                lastLongitude = packet.longitude,
                lastSpeedKmh = packet.speedKmh,
                lastBatteryPercent = packet.batteryPercent,
                isSos = packet.isSos,
                lastSeenEpochMs = nowEpochMs,
                lastSequence = packet.sequenceNumber,
                expectedBeats = 1,
                receivedBeats = 1,
                consecutiveAfterLossBeats = 0,
                isLost = false,
                lastHops = packet.hopsTaken
            )
            return
        }

        val seqDelta = ((packet.sequenceNumber - existing.lastSequence) and MeshPacket.MAX_SEQUENCE_NUMBER)
            .coerceAtLeast(1)
        val newExpected = existing.expectedBeats + seqDelta
        val newReceived = existing.receivedBeats + 1

        // Histéresis al reconectar: requiere RECONNECT_HYSTERESIS_BEATS latidos seguidos para limpiar isLost
        val consecutiveRecovery = if (existing.isLost) existing.consecutiveAfterLossBeats + 1 else 0
        val stillLost = if (existing.isLost) consecutiveRecovery < RECONNECT_HYSTERESIS_BEATS else false

        peerMap[packet.shortId] = existing.copy(
            lastLatitude = packet.latitude,
            lastLongitude = packet.longitude,
            lastSpeedKmh = packet.speedKmh,
            lastBatteryPercent = packet.batteryPercent,
            isSos = packet.isSos,
            lastSeenEpochMs = nowEpochMs,
            lastSequence = packet.sequenceNumber,
            expectedBeats = newExpected,
            receivedBeats = newReceived,
            consecutiveAfterLossBeats = if (stillLost) consecutiveRecovery else 0,
            isLost = stillLost,
            lastHops = packet.hopsTaken
        )
    }

    private fun refreshStateFlows() {
        synchronized(this) {
            refreshStateFlowsLocked()
        }
    }

    private fun refreshStateFlowsLocked() {
        _peers.value = peerMap.toMap()
        _metrics.value = buildReport()
    }

    private fun buildReport(): MeshFieldReport {
        val totalExpected = peerMap.values.sumOf { it.expectedBeats }
        val totalReceived = peerMap.values.sumOf { it.receivedBeats }
        val deliveryPct = if (totalExpected > 0) {
            ((totalReceived.toFloat() / totalExpected.toFloat()) * 100f).coerceIn(0f, 100f)
        } else {
            100f
        }
        val avgHops = if (packetsReceivedValid > 0) {
            totalHopsAccumulated.toFloat() / packetsReceivedValid.toFloat()
        } else {
            0f
        }
        return MeshFieldReport(
            localNodeId = localShortId.toInt(),
            transportName = transport.name,
            participants = peerMap.size + 1,
            heartbeatsExpected = totalExpected,
            heartbeatsReceived = totalReceived,
            heartbeatsDeliveredPct = deliveryPct,
            avgHops = avgHops,
            islandEvents = islandEvents,
            packetsEmitted = packetsEmitted,
            packetsReceivedValid = packetsReceivedValid,
            packetsRelayed = packetsRelayed,
            duplicatesDropped = duplicatesDropped,
            invalidAuthDropped = invalidAuthDropped,
            activeLostPeers = peerMap.values.filter { it.isLost }.map { it.shortId.toInt() },
            deviceModels = deviceModels.toMap()
        )
    }
}
