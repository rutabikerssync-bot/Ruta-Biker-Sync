package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone
import kotlin.math.roundToInt

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
    private val dedupCapacity: Int = 2048,
    @Volatile var relayEnabled: Boolean = true
) {
    companion object {
        const val HEARTBEAT_INTERVAL_MS: Long = 3_000L
        const val LOST_THRESHOLD_BEATS: Int = 5          // 5 * 3 s = 15 s (rango 4–5 latidos de DESIGN.md §3)
        const val LOST_TIMEOUT_MS: Long = HEARTBEAT_INTERVAL_MS * LOST_THRESHOLD_BEATS
        const val RECONNECT_HYSTERESIS_BEATS: Int = 2    // Histéresis para evitar falsas alarmas en curvas/túneles

        private val utcFormatter = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun formatUtc(epochMs: Long): String = utcFormatter.get()!!.format(Date(epochMs))
    }

    data class PeerTrack(
        val shortId: UShort,
        val lastLatitude: Double,
        val lastLongitude: Double,
        val lastSpeedKmh: Int,
        val lastBatteryPercent: Int,
        val isSos: Boolean,
        val lastSeenEpochMs: Long,
        val firstSequence: Int = -1,
        val lastSequence: Int = -1,
        val expectedBeats: Int,
        val receivedBeats: Int,
        val consecutiveAfterLossBeats: Int,
        val isLost: Boolean,
        val lastHops: Int
    )

    data class PacketLogEntry(
        val timestampUtc: String,
        val senderShortId: Int,
        val sequenceNumber: Int,
        val ttl: Int,
        val hops: Int,
        val rssi: Int?,
        val transportMode: String,
        val isDuplicate: Boolean = false,
        val direction: String = "RX" // "TX", "RX", "RELAY_TX"
    )

    /**
     * Reporte individual de tasa de entrega para cada par observado en la mesh.
     * Permite comparar directamente ráfagas emitidas vs oídas por par sin depender de cálculos globales.
     */
    @Serializable
    data class PeerDeliveryReport(
        val peerShortId: Int,
        val firstSequenceObserved: Int?,
        val lastSequenceObserved: Int?,
        val burstsEmittedByPeer: Int,
        val burstsHeardFromPeer: Int,
        val peerDeliveryRatePct: Float,
        val isLost: Boolean
    )

    /**
     * Señal corta `ANNOUNCED(eventId, announcerShortId)` de prioridad alta (`Transport.Priority.PEER_EVENT`)
     * para el algoritmo de Anunciador Único (DESIGN.md §11).
     */
    data class AnnouncedMeshSignal(
        val eventId: String,
        val announcerShortId: UShort,
        val subjectShortId: UShort,
        val eventKindCode: Int,
        val eventCycle: Int
    )

    @Serializable
    data class MeshFieldReport(
        val localNodeId: Int,
        val transportName: String,
        val participants: Int,
        val heartbeatsExpected: Int,
        val heartbeatsReceived: Int,
        val heartbeatsDeliveredPct: Float?,
        val avgHops: Float?,
        val islandEvents: Int,
        val packetsEmitted: Int,
        val packetsReceivedValid: Int,
        val packetsRelayed: Int,
        val duplicatesDropped: Int,
        val invalidAuthDropped: Int,
        val activeLostPeers: List<Int>,
        val deviceModels: Map<String, Int>,
        val peerReports: List<PeerDeliveryReport> = emptyList()
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

    private val packetLogs = mutableListOf<PacketLogEntry>()

    private var collectJob: Job? = null

    private val _metrics = MutableStateFlow(buildReport())
    val metrics: StateFlow<MeshFieldReport> = _metrics.asStateFlow()

    private val _peers = MutableStateFlow<Map<UShort, PeerTrack>>(emptyMap())
    val peers: StateFlow<Map<UShort, PeerTrack>> = _peers.asStateFlow()

    private val _announcedSignals = MutableSharedFlow<AnnouncedMeshSignal>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val announcedSignals: SharedFlow<AnnouncedMeshSignal> = _announcedSignals.asSharedFlow()

    private fun logPacketEntryLocked(entry: PacketLogEntry) {
        if (packetLogs.size >= 10_000) {
            packetLogs.removeAt(0)
        }
        packetLogs.add(entry)
    }

    fun getPacketLogs(): List<PacketLogEntry> = synchronized(this) { packetLogs.toList() }

    fun exportPacketsCsv(): String {
        val entries = getPacketLogs()
        val sb = StringBuilder()
        sb.append("timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction\n")
        for (e in entries) {
            sb.append("${e.timestampUtc},${e.senderShortId},${e.sequenceNumber},${e.ttl},${e.hops},${e.rssi ?: ""},${e.transportMode},${e.isDuplicate},${e.direction}\n")
        }
        return sb.toString()
    }

    suspend fun start(scope: CoroutineScope) {
        transport.start()
        collectJob?.cancel()
        val pktFlow = transport.incomingPackets
        if (pktFlow != null) {
            collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pktFlow.collect { pkt ->
                    onRawPacketReceived(pkt.bytes, pkt.timestampEpochMs, pkt.rssi)
                }
            }
        } else {
            collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.incoming.collect { raw ->
                    onRawPacketReceived(raw, System.currentTimeMillis(), null)
                }
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

        val utcIso = formatUtc(System.currentTimeMillis())
        synchronized(this) {
            seenKeys[packet.dedupKey] = true
            packetsEmitted++
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = utcIso,
                    senderShortId = localShortId.toInt(),
                    sequenceNumber = seq,
                    ttl = ttl,
                    hops = 0,
                    rssi = null,
                    transportMode = transport.name,
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Emite una señal corta `ANNOUNCED(eventId, mi_id)` con prioridad alta (`Transport.Priority.PEER_EVENT`)
     * para cancelar anuncios pendientes de otros nodos en la mesh (DESIGN.md §11).
     */
    suspend fun emitAnnouncedSignal(
        subjectShortId: UShort,
        eventKindCode: Int,
        eventCycle: Int,
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): MeshPacket {
        val seq = sequenceCounter
        sequenceCounter = (sequenceCounter + 1) and MeshPacket.MAX_SEQUENCE_NUMBER

        val encodedLatMicro = ((subjectShortId.toInt() and 0xFFFF) shl 8) or (eventKindCode and 0xFF)
        val encodedLonMicro = eventCycle

        val packet = MeshPacket.createSigned(
            shortId = localShortId,
            latitude = encodedLatMicro / 1e7,
            longitude = encodedLonMicro / 1e7,
            speedKmh = 0,
            bearingDegrees = 0f,
            batteryPercent = 100,
            isSos = false,
            isExtendedBle = false,
            priority = Transport.Priority.PEER_EVENT,
            sequenceNumber = seq,
            ttl = ttl,
            rideSecretKey = rideSecretKey
        )

        val utcIso = formatUtc(System.currentTimeMillis())
        synchronized(this) {
            seenKeys[packet.dedupKey] = true
            packetsEmitted++
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = utcIso,
                    senderShortId = localShortId.toInt(),
                    sequenceNumber = seq,
                    ttl = ttl,
                    hops = 0,
                    rssi = null,
                    transportMode = transport.name,
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Procesa un paquete entrante crudo (20 bytes), actualiza métricas de entrega/saltos,
     * registra la entrada en el log CSV y reenvía por inundación controlada si corresponde.
     */
    suspend fun onRawPacketReceived(
        raw: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis(),
        rssi: Int? = null
    ) {
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
        var decodedAnnouncedSignal: AnnouncedMeshSignal? = null
        val utcIso = formatUtc(nowEpochMs)
        synchronized(this) {
            val isDuplicate = seenKeys.containsKey(packet.dedupKey)
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = utcIso,
                    senderShortId = packet.shortId.toInt(),
                    sequenceNumber = packet.sequenceNumber,
                    ttl = packet.currentTtl,
                    hops = packet.hopsTaken,
                    rssi = rssi,
                    transportMode = transport.name,
                    isDuplicate = isDuplicate,
                    direction = "RX"
                )
            )

            if (isDuplicate) {
                duplicatesDropped++
                refreshStateFlowsLocked()
                return
            }
            seenKeys[packet.dedupKey] = true
            packetsReceivedValid++
            totalHopsAccumulated += packet.hopsTaken

            if (packet.priority == Transport.Priority.PEER_EVENT) {
                val latMicro = (packet.latitude * 1e7).roundToInt()
                val lonMicro = (packet.longitude * 1e7).roundToInt()
                val subjectId = ((latMicro ushr 8) and 0xFFFF).toUShort()
                val kindCode = latMicro and 0xFF
                val cycle = lonMicro
                decodedAnnouncedSignal = AnnouncedMeshSignal(
                    eventId = "EVT:$kindCode:${subjectId.toInt()}:$cycle",
                    announcerShortId = packet.shortId,
                    subjectShortId = subjectId,
                    eventKindCode = kindCode,
                    eventCycle = cycle
                )
            } else {
                updatePeerTrackLocked(packet, nowEpochMs)
            }

            val decremented = if (relayEnabled) packet.decrementTtlForRelay() else null
            if (decremented != null) {
                relayQueue.add(QueuedRelay(decremented, enqueueSequence++))
                relayCandidate = relayQueue.poll()?.packet
                if (relayCandidate != null) {
                    packetsRelayed++
                }
            }
            refreshStateFlowsLocked()
        }

        decodedAnnouncedSignal?.let { signal ->
            _announcedSignals.tryEmit(signal)
        }

        relayCandidate?.let { toRelay ->
            val utcIsoRelay = formatUtc(System.currentTimeMillis())
            synchronized(this) {
                logPacketEntryLocked(
                    PacketLogEntry(
                        timestampUtc = utcIsoRelay,
                        senderShortId = toRelay.shortId.toInt(),
                        sequenceNumber = toRelay.sequenceNumber,
                        ttl = toRelay.currentTtl,
                        hops = toRelay.hopsTaken,
                        rssi = null,
                        transportMode = transport.name,
                        isDuplicate = false,
                        direction = "RELAY_TX"
                    )
                )
            }
            transport.send(toRelay.toByteArray(), toRelay.priority)
        }
    }

    /**
     * Evalúa qué nodos han superado el umbral de 4–5 heartbeats sin señal (~15 s)
     * y contabiliza eventos de partición (islas) cuando >=50% de los peers conocidos se pierden a la vez.
     *
     * En caso de par registrado que nunca se oye (receivedBeats == 0), calcula expectedBeats por tiempo
     * para asegurar 0.0% de entrega tras el timeout. En pares que ya han sido observados (receivedBeats > 0),
     * expectedBeats se calcula por el lapso de secuencia observada (última seq - primera seq + 1)
     * sin distorsión por desfase de inicio o fin de la prueba.
     */
    fun evaluatePeerTimeouts(nowEpochMs: Long = System.currentTimeMillis()): List<UShort> {
        val newlyLost = mutableListOf<UShort>()
        synchronized(this) {
            for ((id, track) in peerMap.entries) {
                val elapsed = (nowEpochMs - track.lastSeenEpochMs).coerceAtLeast(0L)
                if (track.receivedBeats == 0) {
                    val beatsSinceStart = (elapsed / HEARTBEAT_INTERVAL_MS).toInt()
                    val lostNow = elapsed >= LOST_TIMEOUT_MS
                    peerMap[id] = track.copy(
                        expectedBeats = maxOf(track.expectedBeats, beatsSinceStart),
                        isLost = track.isLost || lostNow,
                        consecutiveAfterLossBeats = 0
                    )
                    if (lostNow && !track.isLost) {
                        newlyLost.add(id)
                    }
                } else if (!track.isLost && elapsed >= LOST_TIMEOUT_MS) {
                    peerMap[id] = track.copy(
                        isLost = true,
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

    /**
     * Registra un par conocido del manifiesto de rodada antes de recibir su primer paquete.
     * - Si `initialExpectedBeats == 0`, hay par registrado pero aún 0 latidos esperados (`heartbeatsDeliveredPct = null` / `"N/D"`).
     * - Cuando `expectedBeats > 0` (por `initialExpectedBeats > 0` o tras `evaluatePeerTimeouts`) y `receivedBeats == 0`,
     *   `heartbeatsDeliveredPct` reporta `0.0f` (entrega real del 0 % con color de fallo).
     */
    fun registerExpectedPeer(
        shortId: UShort,
        nowEpochMs: Long = System.currentTimeMillis(),
        initialExpectedBeats: Int = 0
    ) {
        if (shortId == localShortId) return
        synchronized(this) {
            if (!peerMap.containsKey(shortId)) {
                peerMap[shortId] = PeerTrack(
                    shortId = shortId,
                    lastLatitude = 0.0,
                    lastLongitude = 0.0,
                    lastSpeedKmh = 0,
                    lastBatteryPercent = 0,
                    isSos = false,
                    lastSeenEpochMs = nowEpochMs,
                    firstSequence = -1,
                    lastSequence = -1,
                    expectedBeats = initialExpectedBeats.coerceAtLeast(0),
                    receivedBeats = 0,
                    consecutiveAfterLossBeats = 0,
                    isLost = initialExpectedBeats >= LOST_THRESHOLD_BEATS,
                    lastHops = 0
                )
                refreshStateFlowsLocked()
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun exportReportJson(): String {
        val json = Json {
            prettyPrint = true
            explicitNulls = true
        }
        return json.encodeToString(metrics.value)
    }

    private fun updatePeerTrackLocked(packet: MeshPacket, nowEpochMs: Long) {
        val existing = peerMap[packet.shortId]
        val firstSeq = if (existing == null || existing.receivedBeats == 0 || existing.firstSequence < 0) {
            packet.sequenceNumber
        } else {
            existing.firstSequence
        }
        val lastSeq = if (existing == null || existing.receivedBeats == 0 || existing.lastSequence < 0) {
            packet.sequenceNumber
        } else {
            val seqDiff = (packet.sequenceNumber - existing.lastSequence) and MeshPacket.MAX_SEQUENCE_NUMBER
            if (seqDiff < 2048) packet.sequenceNumber else existing.lastSequence
        }
        val seqSpan = ((lastSeq - firstSeq) and MeshPacket.MAX_SEQUENCE_NUMBER) + 1
        val newReceived = (existing?.receivedBeats ?: 0) + 1
        val newExpected = maxOf(newReceived, seqSpan)

        // Histéresis al reconectar: requiere RECONNECT_HYSTERESIS_BEATS latidos seguidos para limpiar isLost
        val consecutiveRecovery = if (existing?.isLost == true) existing.consecutiveAfterLossBeats + 1 else 0
        val stillLost = if (existing?.isLost == true) consecutiveRecovery < RECONNECT_HYSTERESIS_BEATS else false

        peerMap[packet.shortId] = PeerTrack(
            shortId = packet.shortId,
            lastLatitude = packet.latitude,
            lastLongitude = packet.longitude,
            lastSpeedKmh = packet.speedKmh,
            lastBatteryPercent = packet.batteryPercent,
            isSos = packet.isSos,
            lastSeenEpochMs = nowEpochMs,
            firstSequence = firstSeq,
            lastSequence = lastSeq,
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
        val deliveryPct: Float? = if (peerMap.isEmpty() || totalExpected <= 0) {
            null
        } else {
            ((totalReceived.toFloat() / totalExpected.toFloat()) * 100f).coerceIn(0f, 100f)
        }
        val avgHops: Float? = if (packetsReceivedValid > 0) {
            totalHopsAccumulated.toFloat() / packetsReceivedValid.toFloat()
        } else {
            null
        }
        val peerReports = peerMap.values.map { track ->
            val seqSpan = if (track.firstSequence >= 0 && track.lastSequence >= 0) {
                ((track.lastSequence - track.firstSequence) and MeshPacket.MAX_SEQUENCE_NUMBER) + 1
            } else {
                track.expectedBeats
            }
            val expected = maxOf(track.receivedBeats, seqSpan)
            val pct = if (expected > 0) {
                ((track.receivedBeats.toFloat() / expected.toFloat()) * 100f).coerceIn(0f, 100f)
            } else {
                null
            }
            PeerDeliveryReport(
                peerShortId = track.shortId.toInt(),
                firstSequenceObserved = if (track.firstSequence >= 0) track.firstSequence else null,
                lastSequenceObserved = if (track.lastSequence >= 0) track.lastSequence else null,
                burstsEmittedByPeer = expected,
                burstsHeardFromPeer = track.receivedBeats,
                peerDeliveryRatePct = pct ?: 0f,
                isLost = track.isLost
            )
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
            deviceModels = deviceModels.toMap(),
            peerReports = peerReports
        )
    }
}
