package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.mesh.sos.DecryptedMedicalSheet
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.MedicalSheetPacket
import com.rutabikersync.app.core.mesh.sos.MedicalSheetStore
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosPacket
import com.rutabikersync.app.core.mesh.sos.SosPublicKeyResolver
import com.rutabikersync.app.core.mesh.audio.VoicePacket
import com.rutabikersync.app.core.mesh.text.TextMessageCrypto
import com.rutabikersync.app.core.mesh.text.TextMessagePacket
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.Transport
import com.rutabikersync.app.core.transport.VoiceStreamTransport
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
import java.util.concurrent.CopyOnWriteArrayList
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
    private val transport: Transport? = null,
    private val rideSecretKey: ByteArray = ByteArray(32),
    private val localDeviceModel: String = "Android-Generic",
    private val dedupCapacity: Int = 2048,
    @Volatile var relayEnabled: Boolean = true,
    val clubNetworkId: UInt = 0x52425301u,
    val deduplicationTable: DeduplicationTable = DeduplicationTable(),
    private val relayScope: CoroutineScope? = null,
    private val onTransmitRelay: ((ByteArray) -> Unit)? = null
) {
    /**
     * Constructor secundario para la arquitectura Fase 6 (BLE Mesh Off-Grid, Hito 3).
     */
    constructor(
        localShortId: UShort,
        clubNetworkId: UInt,
        deduplicationTable: DeduplicationTable = DeduplicationTable(),
        scope: CoroutineScope? = null,
        onTransmitRelay: ((ByteArray) -> Unit)? = null
    ) : this(
        localShortId = localShortId,
        transport = null,
        rideSecretKey = ByteArray(32),
        localDeviceModel = "Android-Generic",
        dedupCapacity = 2048,
        relayEnabled = true,
        clubNetworkId = clubNetworkId,
        deduplicationTable = deduplicationTable,
        relayScope = scope,
        onTransmitRelay = onTransmitRelay
    )
    companion object {
        const val HEARTBEAT_INTERVAL_MS: Long = 3_000L
        const val LOST_THRESHOLD_BEATS: Int = 5          // 5 * 3 s = 15 s (rango 4–5 latidos de DESIGN.md §3)
        const val LOST_TIMEOUT_MS: Long = HEARTBEAT_INTERVAL_MS * LOST_THRESHOLD_BEATS
        const val RECONNECT_HYSTERESIS_BEATS: Int = 2    // Histéresis para evitar falsas alarmas en curvas/túneles

        // Constantes de Flooding Multi-Salto Hito 3
        const val MIN_RELAY_JITTER_MS = 10L
        const val MAX_RELAY_JITTER_MS = 35L
        const val MAX_ALLOWED_TTL = 5
        const val SOS_RELAY_JITTER_MS = 5L
        const val SOS_ACK_RELAY_JITTER_MS = 5L
        const val MEDICAL_SHEET_RELAY_JITTER_MS = 5L
        const val MAX_SOS_HOP_LIMIT = 5

        const val CONVOY_SIGNAL_PREFIX = 0xC0
        const val CONVOY_SIGNAL_BROADCAST_TARGET: UShort = 0xFFFFu

        const val RIDE_LIFECYCLE_PREFIX = 0xD0
        const val RIDE_LIFECYCLE_BROADCAST_TARGET: UShort = 0xFFFFu

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
        val lastHops: Int,
        val lastRssiDbm: Int? = null,
        /**
         * Instante del último fix GPS válido recibido (0L si el peer nunca reportó uno).
         * Se conserva cuando llega un heartbeat con centinela (0,0) para que el mapa
         * pueda mostrar la última posición real con su antigüedad, sin inventar coordenadas.
         */
        val lastPositionEpochMs: Long = 0L,
        /** true cuando el último heartbeat trajo un centinela y se conservó el fix anterior. */
        val isPositionRetained: Boolean = false
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
        val direction: String = "RX", // "TX", "RX", "RELAY_TX"
        val advertisingMode: String = "BURST_1S",
        val advAddressSuffix: String? = null,
        val timestampNanos: Long? = null
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
        val isLost: Boolean,
        val distinctAdvAddressesCount: Int = 0
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

    /**
     * Señal rápida de convoy recibida por la malla BLE (Hito 2 · Propagación por Malla).
     */
    data class IncomingConvoySignal(
        val senderShortId: UShort,
        val signalKind: ConvoySignalKind,
        val sequenceNumber: Int,
        val hopsTaken: Int
    )

    /**
     * Eventos de ciclo de rodada para sincronización offline del convoy (Fase C).
     */
    enum class RideLifecycleEvent(val code: Byte, val label: String) {
        START_OUTBOUND(0x01, "Inicio de ruta"),
        ARRIVE_DESTINATION(0x02, "Llegamos a destino"),
        START_RETURN(0x03, "Inicio de retorno"),
        COMPLETE_RIDE(0x04, "Rodada finalizada"),
        SAFE_HOME(0x05, "Ya en casa");

        companion object {
            fun fromCode(code: Byte): RideLifecycleEvent? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * Notificación de ciclo de rodada recibida por la malla BLE (Fase C).
     */
    data class IncomingRideLifecycleEvent(
        val senderShortId: UShort,
        val event: RideLifecycleEvent,
        val sequenceNumber: Int,
        val hopsTaken: Int
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
        val peerReports: List<PeerDeliveryReport> = emptyList(),
        val distinctAdvAddressesPerPeer: Map<Int, Int> = emptyMap()
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

    /**
     * Veredicto de procesamiento y retransmisión para tramas de la Fase 6.
     */
    sealed class RelayDecision {
        data class Relayed(
            val newTtl: Int,
            val jitterMs: Long,
            val relayedBytes: ByteArray,
            val isHighPriority: Boolean = false,
            val hopCount: Int? = null
        ) : RelayDecision()

        object DroppedOwnPacket : RelayDecision()
        object DroppedWrongNetwork : RelayDecision()
        object DroppedDuplicate : RelayDecision()
        data class ConsumedLocallyTtlExhausted(
            val finalTtl: Int,
            val isHighPriority: Boolean = false,
            val packetType: Int = MeshHeader.TYPE_HEARTBEAT
        ) : RelayDecision()

        data class DroppedMalformed(val reason: String) : RelayDecision()
        data class DroppedInvalidSignature(val reason: String) : RelayDecision()
        data class DroppedHopLimitExceeded(val hopCount: Int) : RelayDecision()
    }

    // Métricas operativas de retransmisión Phase 6
    val totalPacketsReceived = java.util.concurrent.atomic.AtomicInteger(0)
    val totalPacketsRelayed = java.util.concurrent.atomic.AtomicInteger(0)
    val totalPacketsDroppedTtl = java.util.concurrent.atomic.AtomicInteger(0)
    val totalPacketsDroppedDuplicate = java.util.concurrent.atomic.AtomicInteger(0)
    val totalPacketsDroppedLoopback = java.util.concurrent.atomic.AtomicInteger(0)

    // Métricas y suscriptores de voz PTT (fix funcional: la voz usa la malla activa).
    val voicePacketsEmitted = java.util.concurrent.atomic.AtomicInteger(0)
    val voicePacketsReceived = java.util.concurrent.atomic.AtomicInteger(0)
    val voicePacketsDroppedBackpressure = java.util.concurrent.atomic.AtomicInteger(0)
    private val voiceTxPending = java.util.concurrent.atomic.AtomicInteger(0)
    private val voiceReceivedCallbacks = CopyOnWriteArrayList<(VoicePacket) -> Unit>()

    /**
     * Canal de voz opcional (GATT). Cuando está listo, los VoicePacket viajan por él;
     * si no, se mantiene el fallback anterior por advertising para no romper la malla.
     */
    @Volatile
    private var voiceStreamTransport: VoiceStreamTransport? = null

    fun setVoiceStreamTransport(transport: VoiceStreamTransport?) {
        voiceStreamTransport = transport
    }

    /**
     * Resuelve el shortId de malla asociado al sufijo de dirección anunciado por un heartbeat
     * autenticado. GattVoiceTransport lo usa para conectar SOLO con peers de la rodada y
     * para elegir rol por shortId (la dirección Bluetooth local de Android es anonimizada).
     */
    fun resolveShortIdForAddressSuffix(suffix: String): UShort? = synchronized(this) {
        peerAddresses.entries.firstOrNull { it.value.contains(suffix) }?.key?.toUShort()
    }

    /**
     * Cada VoicePacket tarda ~1,4 s en fragmentarse (5-6 fragmentos × 140 ms × 2 repeticiones).
     * El micrófono produce tramas cada 20 ms: sin límite, la cola crece sin control y el
     * receptor escucha audio con varios segundos de retraso. Con 2 envíos pendientes el
     * retardo máximo en radio se mantiene en ~3 s, dentro del objetivo de 1-3 s.
     */
    private val maxPendingVoiceSends = 2

    /**
     * Suscribe un receptor de [VoicePacket] ya reensamblado por el transporte.
     * Devuelve una función para cancelar la suscripción (útil desde un DisposableEffect).
     */
    fun onVoiceReceived(callback: (VoicePacket) -> Unit): () -> Unit {
        if (callback !in voiceReceivedCallbacks) voiceReceivedCallbacks.add(callback)
        return { voiceReceivedCallbacks.remove(callback) }
    }

    fun resetMetrics() {
        totalPacketsReceived.set(0)
        totalPacketsRelayed.set(0)
        totalPacketsDroppedTtl.set(0)
        totalPacketsDroppedDuplicate.set(0)
        totalPacketsDroppedLoopback.set(0)
        voicePacketsEmitted.set(0)
        voicePacketsReceived.set(0)
        voicePacketsDroppedBackpressure.set(0)
        deduplicationTable.clear()
    }

    /**
     * Procesa una trama binaria entrante de la arquitectura Fase 6 (con [MeshHeader]).
     * Aplica:
     * 1. Validación de clubNetworkId
     * 2. Supresión de loopback (descarte si senderShortId == localShortId)
     * 3. Deduplicación LRU previa para mitigar tormentas de broadcast
     * 4. Semántica de TTL "Opción A" (Decremento previo a retransmitir):
     *    - Si currentTtl <= 1: el paquete se consume localmente pero NO se retransmite.
     *    - Si currentTtl > 1: decrementa currentTtl en 1 y retransmite con jitter de 10..35 ms.
     *
     * Justificación Técnica del Jitter (10–35 ms):
     * El rango de 10 a 35 ms fue seleccionado en lugar de 5 a 25 ms tras evaluar el rendimiento del stack
     * HCI en controladores Bluetooth de gama de entrada (como Unisoc T606 en el TECNO KI5k).
     * El cambio de modo de escaneo a emisión Extended Advertising (setAdvertisingData) requiere entre 8 y 12 ms
     * para despachar comandos al chip de radio. Un jitter inferior a 10 ms satura la cola HCI provocando
     * colisiones de radio simultáneas entre nodos adyacentes del convoy.
     */
    /** Salida inyectada por RideService para reenviar bytes ya validados por su Transport activo. */
    var onAuthenticatedRelayTransmit: ((ByteArray) -> Unit)? = null

    private fun hasRelayTransmitter(): Boolean =
        onAuthenticatedRelayTransmit != null || onTransmitRelay != null

    private fun deliverAuthenticatedRelayFrame(bytes: ByteArray) {
        (onAuthenticatedRelayTransmit ?: onTransmitRelay)?.invoke(bytes)
    }

    var sosCrypto: Ed25519SosCrypto? = null
    var sosPublicKeyResolver: SosPublicKeyResolver? = null
    var onSosReceived: ((SosPacket) -> Unit)? = null
    var onSosAckReceived: ((SosAckPacket) -> Unit)? = null
    /** Observa también ACK para otros nodos: todos deben silenciar ese incidente real. */
    var onSosAckObserved: ((SosAckPacket) -> Unit)? = null
    var medicalSheetStore: MedicalSheetStore? = null
    var onMedicalSheetReceived: ((MedicalSheetPacket, DecryptedMedicalSheet?) -> Unit)? = null
    var customRideSecretKey: ByteArray? = null
    var onTextMessageReceived: ((TextMessagePacket, String?) -> Unit)? = null
    var onConvoySignalReceived: ((IncomingConvoySignal) -> Unit)? = null
    var onRideLifecycleEventReceived: ((IncomingRideLifecycleEvent) -> Unit)? = null
    var isFriendNodeEnabled: Boolean = false
    var friendNodeBuffer: com.rutabikersync.app.core.mesh.text.FriendNodeBuffer? = null
    var lpnMode: LowPowerNodeMode? = null

    @Volatile
    private var sosBroadcastJob: Job? = null

    fun startSosBroadcaster(
        sosPacket: SosPacket,
        scope: CoroutineScope,
        intervalMs: Long = 2000L,
        durationSeconds: Int = 60,
        onTransmit: (ByteArray) -> Unit
    ): Job {
        stopSosBroadcaster()
        val job = scope.launch {
            val startTime = System.currentTimeMillis()
            val maxDurationMs = durationSeconds * 1000L
            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                onTransmit(sosPacket.toByteArray())
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        sosBroadcastJob = job
        return job
    }

    fun stopSosBroadcaster() {
        sosBroadcastJob?.cancel()
        sosBroadcastJob = null
    }

    /**
     * Invocado cuando se descubre un peer en rango (ej. al recibir su HeartbeatFrame).
     * Si el nodo local actúa como Nodo Amigo ([isFriendNodeEnabled]), drena y retransmite
     * los mensajes de texto retenidos en el búfer de Store & Forward hacia el destinatario.
     *
     * @param discoveredShortId ID corto del nodo descubierto en rango
     * @param nowMs Marca temporal actual en milisegundos
     * @return Lista de paquetes [TextMessagePacket] drenados y retransmitidos
     */
    fun onPeerDiscovered(
        discoveredShortId: UShort,
        nowMs: Long = System.currentTimeMillis()
    ): List<TextMessagePacket> {
        if (!isFriendNodeEnabled) return emptyList()
        val buffer = friendNodeBuffer ?: return emptyList()
        val pending = buffer.drainForPeer(discoveredShortId)
        if (pending.isNotEmpty() && hasRelayTransmitter()) {
            for (msg in pending) {
                val newTtl = (msg.header.currentTtl - 1).coerceAtLeast(1)
                val relayed = msg.copyWithIncrementedHop(newTtl.toByte())
                totalPacketsRelayed.incrementAndGet()
                deliverAuthenticatedRelayFrame(relayed.toByteArray())
            }
        }
        return pending
    }

    fun processIncomingPacket(
        rawBytes: ByteArray,
        immediateSenderShortId: UShort? = null,
        nowMs: Long = System.currentTimeMillis()
    ): RelayDecision {
        totalPacketsReceived.incrementAndGet()

        if (rawBytes.size < MeshHeader.HEADER_SIZE_BYTES) {
            return RelayDecision.DroppedMalformed("Tamaño insuficiente: ${rawBytes.size} bytes")
        }

        val header = try {
            MeshHeader.fromByteArray(rawBytes, 0)
        } catch (e: Exception) {
            return RelayDecision.DroppedMalformed("Cabecera corrupta: ${e.message}")
        }

        // 1. Filtrado por Red de Club
        if (header.clubNetworkId != clubNetworkId) {
            if (header.packetType == MeshHeader.TYPE_PTT_VOICE) {
                try {
                    android.util.Log.w(
                        "MeshRelayEngine",
                        "VoicePacketRx descartado por red: got=${header.clubNetworkId.toInt()} " +
                            "expected=${clubNetworkId.toInt()} sender=${header.senderShortId}"
                    )
                } catch (_: Throwable) {}
            }
            return RelayDecision.DroppedWrongNetwork
        }

        // 2. Supresión de Loopback: Paquetes emitidos originalmente por este mismo nodo
        if (header.senderShortId == localShortId) {
            totalPacketsDroppedLoopback.incrementAndGet()
            return RelayDecision.DroppedOwnPacket
        }

        // 3. Enrutamiento por tipo de paquete
        when (header.packetType) {
            MeshHeader.TYPE_SOS -> {
                if (rawBytes.size < SosPacket.PACKET_SIZE_BYTES) {
                    return RelayDecision.DroppedMalformed("SosPacket tamaño insuficiente (${rawBytes.size} < ${SosPacket.PACKET_SIZE_BYTES})")
                }
                val sos = try {
                    SosPacket.fromByteArray(rawBytes, 0)
                } catch (e: Exception) {
                    return RelayDecision.DroppedMalformed("SosPacket parsing error: ${e.message}")
                }

                // Límite estricto de saltos (Max 5 hops para mitigar bucles y ataques)
                val currentHops = sos.hopCount.toInt() and 0xFF
                if (currentHops >= MAX_SOS_HOP_LIMIT) {
                    return RelayDecision.DroppedHopLimitExceeded(currentHops)
                }

                // Verificación de firma digital Ed25519 si hay resolver y crypto disponibles
                val resolver = sosPublicKeyResolver
                val crypto = sosCrypto
                var signatureValid = true
                if (resolver != null && crypto != null) {
                    val senderPubKey = resolver.resolvePublicKey(header.senderShortId)
                    if (senderPubKey != null) {
                        signatureValid = crypto.verifySos(sos, senderPubKey)
                        if (!signatureValid) {
                            try {
                                android.util.Log.w("MeshRelayEngine", "SOS_REJECTED: sender=${header.senderShortId}, hops=${sos.hopCount.toInt() and 0xFF}, sigValid=false")
                            } catch (_: Throwable) {}
                            return RelayDecision.DroppedInvalidSignature("Firma Ed25519 de SOS inválida para shortId=${header.senderShortId}")
                        }
                    }
                }

                try {
                    android.util.Log.i("MeshRelayEngine", "SOS_VERIFIED: sender=${header.senderShortId}, hops=${sos.hopCount.toInt() and 0xFF}, sigValid=$signatureValid, incident=${sos.incidentCode}, blood=${sos.bloodType}")
                } catch (_: Throwable) {}

                // Notificar a listeners locales (UI, Pantalla de emergencia, Alarma acústica)
                onSosReceived?.invoke(sos)

                // Supresión de tormentas mediante deduplicación
                val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
                if (!isNew) {
                    totalPacketsDroppedDuplicate.incrementAndGet()
                    return RelayDecision.DroppedDuplicate
                }

                // Semántica TTL Opción A
                if (header.currentTtl <= 1) {
                    totalPacketsDroppedTtl.incrementAndGet()
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = true,
                        packetType = MeshHeader.TYPE_SOS
                    )
                }

                // Retransmisión prioritaria: decrementa TTL e incrementa hopCount (Opción B)
                val newTtl = header.currentTtl - 1
                val relayedSos = sos.copyWithIncrementedHop(newTtl.toByte())
                val relayedBytes = relayedSos.toByteArray()
                val jitterMs = SOS_RELAY_JITTER_MS
                totalPacketsRelayed.incrementAndGet()

                if (relayScope != null && hasRelayTransmitter()) {
                    relayScope.launch {
                        kotlinx.coroutines.delay(jitterMs)
                        deliverAuthenticatedRelayFrame(relayedBytes)
                    }
                } else {
                    deliverAuthenticatedRelayFrame(relayedBytes)
                }

                return RelayDecision.Relayed(
                    newTtl = newTtl,
                    jitterMs = jitterMs,
                    relayedBytes = relayedBytes,
                    isHighPriority = true,
                    hopCount = relayedSos.hopCount.toInt() and 0xFF
                )
            }

            MeshHeader.TYPE_SOS_ACK -> {
                if (rawBytes.size < SosAckPacket.PACKET_SIZE_BYTES) {
                    return RelayDecision.DroppedMalformed("SosAckPacket tamaño insuficiente (${rawBytes.size} < ${SosAckPacket.PACKET_SIZE_BYTES})")
                }
                val ack = try {
                    SosAckPacket.fromByteArray(rawBytes, 0)
                } catch (e: Exception) {
                    return RelayDecision.DroppedMalformed("SosAckPacket parsing error: ${e.message}")
                }

                // Verificación de firma Ed25519 del ACK
                val resolver = sosPublicKeyResolver
                val crypto = sosCrypto
                if (resolver != null && crypto != null) {
                    val ackerPubKey = resolver.resolvePublicKey(ack.ackerShortId)
                    if (ackerPubKey != null) {
                        val isValid = crypto.verifySosAck(ack, ackerPubKey)
                        if (!isValid) {
                            return RelayDecision.DroppedInvalidSignature("Firma Ed25519 de ACK inválida para ackerShortId=${ack.ackerShortId}")
                        }
                    }
                }

                // La telemetría acústica de todos los nodos observa el mismo ACK; el callback
                // dirigido original se conserva para la semántica de emisor/broadcaster.
                onSosAckObserved?.invoke(ack)

                // Si el ACK es para este nodo (fuimos el emisor del SOS)
                if (ack.originalSenderShortId == localShortId) {
                    stopSosBroadcaster()
                    onSosAckReceived?.invoke(ack)
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = true,
                        packetType = MeshHeader.TYPE_SOS_ACK
                    )
                }

                // Supresión de duplicados para retransmisión
                val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
                if (!isNew) {
                    totalPacketsDroppedDuplicate.incrementAndGet()
                    return RelayDecision.DroppedDuplicate
                }

                // Semántica TTL Opción A
                if (header.currentTtl <= 1) {
                    totalPacketsDroppedTtl.incrementAndGet()
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = true,
                        packetType = MeshHeader.TYPE_SOS_ACK
                    )
                }

                // Retransmisión del ACK hacia el emisor original
                val newTtl = header.currentTtl - 1
                val relayedHeader = header.copy(currentTtl = newTtl)
                val relayedBytes = rawBytes.copyOf()
                val headerBytes = relayedHeader.toByteArray()
                System.arraycopy(headerBytes, 0, relayedBytes, 0, MeshHeader.HEADER_SIZE_BYTES)
                val jitterMs = SOS_ACK_RELAY_JITTER_MS
                totalPacketsRelayed.incrementAndGet()

                if (relayScope != null && hasRelayTransmitter()) {
                    relayScope.launch {
                        kotlinx.coroutines.delay(jitterMs)
                        deliverAuthenticatedRelayFrame(relayedBytes)
                    }
                } else {
                    deliverAuthenticatedRelayFrame(relayedBytes)
                }

                return RelayDecision.Relayed(
                    newTtl = newTtl,
                    jitterMs = jitterMs,
                    relayedBytes = relayedBytes,
                    isHighPriority = true
                )
            }

            MeshHeader.TYPE_MEDICAL_SHEET -> {
                if (rawBytes.size < MedicalSheetPacket.FIXED_HEADER_AND_METADATA_BYTES) {
                    return RelayDecision.DroppedMalformed("MedicalSheetPacket tamaño insuficiente (${rawBytes.size} < ${MedicalSheetPacket.FIXED_HEADER_AND_METADATA_BYTES})")
                }
                val medicalPacket = try {
                    MedicalSheetPacket.fromByteArray(rawBytes, 0)
                } catch (e: Exception) {
                    return RelayDecision.DroppedMalformed("MedicalSheetPacket parsing error: ${e.message}")
                }

                // Intentar descifrado si el nodo es Líder/Sargento autorizado
                val decrypted = medicalSheetStore?.decryptAndStore(medicalPacket)
                try {
                    android.util.Log.i("MeshRelayEngine", "MEDICAL_SHEET_RECEIVED: sender=${header.senderShortId}, owner=${medicalPacket.ownerShortId}, size=${rawBytes.size}B, decrypted=${decrypted != null}")
                } catch (_: Throwable) {}
                onMedicalSheetReceived?.invoke(medicalPacket, decrypted)

                // Supresión de tormentas mediante deduplicación
                val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
                if (!isNew) {
                    totalPacketsDroppedDuplicate.incrementAndGet()
                    return RelayDecision.DroppedDuplicate
                }

                // Semántica TTL Opción A
                if (header.currentTtl <= 1) {
                    totalPacketsDroppedTtl.incrementAndGet()
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = true,
                        packetType = MeshHeader.TYPE_MEDICAL_SHEET
                    )
                }

                // Retransmisión prioritaria (Zero-Knowledge relay ciego)
                val newTtl = header.currentTtl - 1
                val relayedPacket = medicalPacket.copyWithDecrementedTtl(newTtl.toByte())
                val relayedBytes = relayedPacket.toByteArray()
                val jitterMs = MEDICAL_SHEET_RELAY_JITTER_MS
                totalPacketsRelayed.incrementAndGet()

                if (relayScope != null && hasRelayTransmitter()) {
                    relayScope.launch {
                        kotlinx.coroutines.delay(jitterMs)
                        deliverAuthenticatedRelayFrame(relayedBytes)
                    }
                } else {
                    deliverAuthenticatedRelayFrame(relayedBytes)
                }

                return RelayDecision.Relayed(
                    newTtl = newTtl,
                    jitterMs = jitterMs,
                    relayedBytes = relayedBytes,
                    isHighPriority = true
                )
            }

            MeshHeader.TYPE_PTT_VOICE -> {
                // La voz NO se deduplica por (sender, sequence): todos los frames de una
                // ráfaga PTT comparten sequenceNum y solo cambia frameIndex.
                val voicePacket = try {
                    VoicePacket.fromBinary(rawBytes)
                } catch (e: Exception) {
                    return RelayDecision.DroppedMalformed("VoicePacket parsing error: ${e.message}")
                }
                voicePacketsReceived.incrementAndGet()
                try {
                    android.util.Log.i(
                        "MeshRelayEngine",
                        "VoicePacketRx sender=${header.senderShortId} frame=${voicePacket.frameIndex} " +
                            "last=${voicePacket.isLastFrame} bytes=${rawBytes.size}"
                    )
                } catch (_: Throwable) {}
                voiceReceivedCallbacks.forEach { callback ->
                    runCatching { callback(voicePacket) }
                }
                // TTL de voz = 1: consumir localmente, nunca retransmitir (evita tormentas).
                return RelayDecision.ConsumedLocallyTtlExhausted(
                    finalTtl = header.currentTtl,
                    isHighPriority = true,
                    packetType = header.packetType
                )
            }

            MeshHeader.TYPE_TEXT_MSG, MeshHeader.TYPE_TEXT_MESSAGE -> {
                if (rawBytes.size < TextMessagePacket.MIN_PACKET_SIZE) {
                    return RelayDecision.DroppedMalformed("TextMessagePacket tamaño insuficiente (${rawBytes.size} < ${TextMessagePacket.MIN_PACKET_SIZE})")
                }
                val textPacket = try {
                    TextMessagePacket.fromByteArray(rawBytes, 0)
                } catch (e: Exception) {
                    return RelayDecision.DroppedMalformed("TextMessagePacket parsing error: ${e.message}")
                }

                // Límite estricto de saltos (Max 5 hops para mitigar bucles)
                val currentHops = textPacket.hopCount.toInt() and 0xFF
                if (currentHops >= MAX_SOS_HOP_LIMIT) {
                    return RelayDecision.DroppedHopLimitExceeded(currentHops)
                }

                // Intentar descifrado si el mensaje está dirigido a este nodo o es broadcast
                val isForUs = textPacket.targetShortId == localShortId || textPacket.isBroadcast
                var decryptedText: String? = null
                if (isForUs) {
                    val key = customRideSecretKey ?: rideSecretKey
                    if (key.size == TextMessageCrypto.KEY_SIZE_BYTES) {
                        decryptedText = try {
                            TextMessageCrypto.decrypt(key, textPacket)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    onTextMessageReceived?.invoke(textPacket, decryptedText)
                }

                // Unicast dirigido exclusivamente a este nodo: consumir localmente y NO retransmitir
                if (textPacket.targetShortId == localShortId) {
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = false,
                        packetType = header.packetType
                    )
                }

                // Nodo Amigo (Store & Forward): si es unicast para un peer ausente, guardar en búfer RAM
                if (isFriendNodeEnabled && !textPacket.isBroadcast && textPacket.targetShortId != localShortId) {
                    friendNodeBuffer?.storeMessage(textPacket, nowMs)
                }

                // Modo LPN: nodos de bajo consumo no retransmiten tráfico de texto de terceros
                if (lpnMode?.isLpnActive == true) {
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = false,
                        packetType = header.packetType
                    )
                }

                // Supresión de tormentas mediante deduplicación
                val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
                if (!isNew) {
                    totalPacketsDroppedDuplicate.incrementAndGet()
                    return RelayDecision.DroppedDuplicate
                }

                // Semántica TTL Opción A
                if (header.currentTtl <= 1) {
                    totalPacketsDroppedTtl.incrementAndGet()
                    return RelayDecision.ConsumedLocallyTtlExhausted(
                        finalTtl = header.currentTtl,
                        isHighPriority = false,
                        packetType = header.packetType
                    )
                }

                // Retransmisión (Broadcast o Unicast para terceros): decrementa TTL e incrementa hopCount
                val newTtl = header.currentTtl - 1
                val relayedPacket = textPacket.copyWithIncrementedHop(newTtl.toByte())
                val relayedBytes = relayedPacket.toByteArray()
                val jitterMs = (MIN_RELAY_JITTER_MS..MAX_RELAY_JITTER_MS).random()
                totalPacketsRelayed.incrementAndGet()

                if (relayScope != null && hasRelayTransmitter()) {
                    relayScope.launch {
                        kotlinx.coroutines.delay(jitterMs)
                        deliverAuthenticatedRelayFrame(relayedBytes)
                    }
                } else {
                    deliverAuthenticatedRelayFrame(relayedBytes)
                }

                return RelayDecision.Relayed(
                    newTtl = newTtl,
                    jitterMs = jitterMs,
                    relayedBytes = relayedBytes,
                    isHighPriority = false,
                    hopCount = relayedPacket.hopCount.toInt() and 0xFF
                )
            }

            else -> {
                // Modo LPN: nodos de bajo consumo no retransmiten tráfico rutinario (PTT Voice o Heartbeats ajenos)
                if (lpnMode?.isLpnActive == true) {
                    return RelayDecision.ConsumedLocallyTtlExhausted(header.currentTtl)
                }

                // Heartbeats, PTT Voice, Control
                val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
                if (!isNew) {
                    totalPacketsDroppedDuplicate.incrementAndGet()
                    return RelayDecision.DroppedDuplicate
                }

                if (header.currentTtl <= 1) {
                    totalPacketsDroppedTtl.incrementAndGet()
                    return RelayDecision.ConsumedLocallyTtlExhausted(header.currentTtl)
                }

                val newTtl = header.currentTtl - 1
                val relayedHeader = header.copy(currentTtl = newTtl)
                val relayedBytes = rawBytes.copyOf()
                val headerBytes = relayedHeader.toByteArray()
                System.arraycopy(headerBytes, 0, relayedBytes, 0, MeshHeader.HEADER_SIZE_BYTES)

                val jitterMs = (MIN_RELAY_JITTER_MS..MAX_RELAY_JITTER_MS).random()
                totalPacketsRelayed.incrementAndGet()

                if (relayScope != null && hasRelayTransmitter()) {
                    relayScope.launch {
                        kotlinx.coroutines.delay(jitterMs)
                        deliverAuthenticatedRelayFrame(relayedBytes)
                    }
                } else {
                    deliverAuthenticatedRelayFrame(relayedBytes)
                }

                return RelayDecision.Relayed(
                    newTtl = newTtl,
                    jitterMs = jitterMs,
                    relayedBytes = relayedBytes
                )
            }
        }
    }


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
    private val peerAddresses = mutableMapOf<Int, MutableSet<String>>()

    private var collectJob: Job? = null

    val isRunning: Boolean
        get() = collectJob?.isActive == true

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

    private val _incomingConvoySignals = MutableSharedFlow<IncomingConvoySignal>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingConvoySignals: SharedFlow<IncomingConvoySignal> = _incomingConvoySignals.asSharedFlow()

    private val _incomingRideLifecycleEvents = MutableSharedFlow<IncomingRideLifecycleEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingRideLifecycleEvents: SharedFlow<IncomingRideLifecycleEvent> = _incomingRideLifecycleEvents.asSharedFlow()

    private fun currentAdvertisingMode(): String =
        (transport as? BleAdvTransport)?.advertisingDeliveryMode?.code ?: "BURST_1S"

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
        sb.append("timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction,advertising_mode,adv_addr_suffix,timestamp_nanos\n")
        for (e in entries) {
            sb.append("${e.timestampUtc},${e.senderShortId},${e.sequenceNumber},${e.ttl},${e.hops},${e.rssi ?: ""},${e.transportMode},${e.isDuplicate},${e.direction},${e.advertisingMode},${e.advAddressSuffix ?: ""},${e.timestampNanos ?: ""}\n")
        }
        return sb.toString()
    }

    suspend fun start(scope: CoroutineScope) {
        transport?.start()
        collectJob?.cancel()
        val pktFlow = transport?.incomingPackets
        if (pktFlow != null) {
            collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pktFlow.collect { pkt ->
                    if (pkt.bytes.size == MeshPacket.PACKET_SIZE_BYTES) {
                        onRawPacketReceived(
                            raw = pkt.bytes,
                            nowEpochMs = pkt.timestampEpochMs,
                            rssi = pkt.rssi,
                            senderAddressSuffix = pkt.senderAddressSuffix,
                            scanTimestampNanos = pkt.scanTimestampNanos
                        )
                    } else {
                        // Hito 7.0.2: texto/SOS/médico no son heartbeats de 20 B.
                        // processIncomingPacket descifra y dispara onTextMessageReceived.
                        processIncomingPacket(
                            rawBytes = pkt.bytes,
                            nowMs = pkt.timestampEpochMs
                        )
                    }
                }
            }
        } else {
            collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport?.incoming?.collect { raw ->
                    if (raw.size == MeshPacket.PACKET_SIZE_BYTES) {
                        onRawPacketReceived(raw, System.currentTimeMillis(), null)
                    } else {
                        processIncomingPacket(rawBytes = raw)
                    }
                }
            }
        }
    }

    suspend fun stop() {
        stopSosBroadcaster()
        collectJob?.cancel()
        collectJob = null
        transport?.stop()
    }

    /** Entrega el formato SOS existente al transporte activo, sin alterar firma ni wire format. */
    fun emitSosPacket(packet: SosPacket): Boolean {
        if (
            packet.header.senderShortId != localShortId ||
            packet.header.clubNetworkId != clubNetworkId ||
            !isRunning
        ) return false
        return enqueueSosControl(packet.header, packet.toByteArray())
    }

    /** Entrega un ACK real al transporte activo; true significa aceptado para emisión, no entrega remota. */
    fun emitSosAckPacket(packet: SosAckPacket): Boolean {
        if (
            packet.header.senderShortId != localShortId ||
            packet.ackerShortId != localShortId ||
            packet.header.clubNetworkId != clubNetworkId ||
            !isRunning
        ) return false
        return enqueueSosControl(packet.header, packet.toByteArray())
    }

    private fun enqueueSosControl(header: MeshHeader, bytes: ByteArray): Boolean {
        val transport = this.transport ?: return false
        val scope = relayScope ?: return false
        synchronized(this) {
            packetsEmitted++
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = formatUtc(System.currentTimeMillis()),
                    senderShortId = localShortId.toInt(),
                    sequenceNumber = header.sequenceNum.toInt(),
                    ttl = header.currentTtl,
                    hops = 0,
                    rssi = null,
                    transportMode = transport.name,
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        scope.launch {
            runCatching { transport.send(bytes, Transport.Priority.SOS) }
                .onFailure { android.util.Log.e("MeshRelayEngine", "SOSControlTx error: ${it.message}") }
        }
        refreshStateFlows()
        return true
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
                    transportMode = transport?.name ?: "BLE_ADV",
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport?.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Emite una señal rápida de convoy al convoy por la malla BLE con prioridad alta
     * (`Transport.Priority.PEER_EVENT`) para latencia ultra-baja en carretera.
     */
    suspend fun emitConvoySignal(
        signalKind: ConvoySignalKind,
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): MeshPacket {
        val seq = sequenceCounter
        sequenceCounter = (sequenceCounter + 1) and MeshPacket.MAX_SEQUENCE_NUMBER

        val kindCode = CONVOY_SIGNAL_PREFIX or (signalKind.code.toInt() and 0x0F)
        val encodedLatMicro = ((CONVOY_SIGNAL_BROADCAST_TARGET.toInt() and 0xFFFF) shl 8) or (kindCode and 0xFF)
        val encodedLonMicro = seq

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
                    transportMode = transport?.name ?: "BLE_ADV",
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport?.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Emite una notificación de ciclo de rodada (Fase C) al convoy por la malla BLE con prioridad alta
     * (`Transport.Priority.PEER_EVENT`) para sincronización inmediata de fase off-grid.
     */
    suspend fun emitRideLifecycleEvent(
        event: RideLifecycleEvent,
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): MeshPacket {
        val seq = sequenceCounter
        sequenceCounter = (sequenceCounter + 1) and MeshPacket.MAX_SEQUENCE_NUMBER

        val kindCode = RIDE_LIFECYCLE_PREFIX or (event.code.toInt() and 0x0F)
        val encodedLatMicro = ((RIDE_LIFECYCLE_BROADCAST_TARGET.toInt() and 0xFFFF) shl 8) or (kindCode and 0xFF)
        val encodedLonMicro = seq

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
                    transportMode = transport?.name ?: "BLE_ADV",
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport?.send(packet.toByteArray(), packet.priority)
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
                    transportMode = transport?.name ?: "BLE_ADV",
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        transport?.send(packet.toByteArray(), packet.priority)
        refreshStateFlows()
        return packet
    }

    /**
     * Cifra y emite un mensaje de texto táctico usando la MISMA clave de rodada y el MISMO
     * transporte activo que los heartbeats/SOS (Hito 7.0.2 · fix funcional).
     *
     * No modifica la criptografía (ChaCha20-Poly1305 en [TextMessageCrypto]) ni el flooding:
     * solo construye el paquete y lo entrega al transporte, igual que [emitLocalPosition].
     */
    fun emitTextMessage(targetShortId: UShort, text: String): TextMessagePacket? {
        val key = customRideSecretKey ?: rideSecretKey
        if (key.size != TextMessageCrypto.KEY_SIZE_BYTES) {
            android.util.Log.w(
                "MeshRelayEngine",
                "TextMessageTx abortado: clave de ${key.size} B, se requieren ${TextMessageCrypto.KEY_SIZE_BYTES} B"
            )
            return null
        }
        val transport = this.transport ?: return null
        val seq = sequenceCounter
        sequenceCounter = (seq + 1) and MeshPacket.MAX_SEQUENCE_NUMBER
        val packet = TextMessageCrypto.encrypt(
            key = key,
            plaintext = text,
            header = MeshHeader(
                clubNetworkId = clubNetworkId,
                senderShortId = localShortId,
                packetType = MeshHeader.TYPE_TEXT_MESSAGE,
                initTtl = 5,
                currentTtl = 5,
                sequenceNum = seq.toUShort()
            ),
            targetShortId = targetShortId
        )

        val dedupKey = (localShortId.toLong() shl 16) or seq.toLong()
        synchronized(this) {
            seenKeys[dedupKey] = true
            packetsEmitted++
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = formatUtc(System.currentTimeMillis()),
                    senderShortId = localShortId.toInt(),
                    sequenceNumber = seq,
                    ttl = 5,
                    hops = 0,
                    rssi = null,
                    transportMode = transport.name,
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        val bytes = packet.toByteArray()
        relayScope?.launch { transport.send(bytes, Transport.Priority.STATUS) }
            ?: android.util.Log.w("MeshRelayEngine", "TextMessageTx sin relayScope: no se pudo emitir")
        refreshStateFlows()
        return packet
    }

    /**
     * Emite un [VoicePacket] PTT por el transporte activo del RideService.
     *
     * El paquete YA viene cifrado con ChaCha20-Poly1305 por `PttAudioEngine`
     * (AudioRecord → Opus → ChaCha20 → VoicePacket): aquí solo se serializa y se entrega
     * al transporte, que aplica el MISMO `BleFragmentationCodec` de 24 B usado por el
     * chat táctico. No se vuelve a cifrar nada: eso rompería el descifrado receptor y
     * cambiaría la criptografía, que está fuera de este fix.
     */
    fun emitVoicePacket(packet: VoicePacket): Boolean {
        val activeTransport = this.transport ?: run {
            android.util.Log.w("MeshRelayEngine", "VoicePacketTx abortado: no hay transport activo")
            return false
        }
        val bytes = packet.toBinary()
        val scope = relayScope ?: run {
            android.util.Log.w("MeshRelayEngine", "VoicePacketTx abortado: sin relayScope")
            return false
        }

        // Backpressure: en advertising el radio es mucho más lento que el micrófono (2 tramas
        // pendientes). Con GATT el enlace admite la cadencia completa de 50 fps, así que se
        // amplía la cola para no descartar audio innecesariamente. El JitterBuffer sigue
        // aplicando PLC si aun así falta algún frame.
        val gattStream = voiceStreamTransport
        val effectiveMaxPending = if (gattStream?.isVoiceReady == true) 32 else maxPendingVoiceSends
        val pending = voiceTxPending.incrementAndGet()
        if (pending > effectiveMaxPending) {
            voiceTxPending.decrementAndGet()
            voicePacketsDroppedBackpressure.incrementAndGet()
            if (voicePacketsDroppedBackpressure.get() % 20 == 1) {
                android.util.Log.w(
                    "MeshRelayEngine",
                    "VoicePacketTx descartado por backpressure pending=$pending frame=${packet.frameIndex} " +
                        "dropped=${voicePacketsDroppedBackpressure.get()}"
                )
            }
            return false
        }

        synchronized(this) {
            voicePacketsEmitted.incrementAndGet()
            packetsEmitted++
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = formatUtc(System.currentTimeMillis()),
                    senderShortId = localShortId.toInt(),
                    sequenceNumber = packet.header.sequenceNum.toInt(),
                    ttl = packet.header.currentTtl,
                    hops = 0,
                    rssi = null,
                    transportMode = activeTransport.name,
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = false,
                    direction = "TX"
                )
            )
        }
        val stream = gattStream
        val viaGatt = stream?.isVoiceReady == true
        scope.launch {
            try {
                if (stream != null && viaGatt) {
                    val accepted = stream.sendVoiceFrame(bytes)
                    if (!accepted) {
                        voicePacketsDroppedBackpressure.incrementAndGet()
                    }
                } else if (activeTransport is BleAdvTransport) {
                    activeTransport.sendVoice(bytes)
                } else {
                    activeTransport.send(bytes, Transport.Priority.VOICE)
                }
            } catch (e: Exception) {
                android.util.Log.w("MeshRelayEngine", "VoicePacketTx fallo: ${e.message}")
            } finally {
                voiceTxPending.decrementAndGet()
            }
        }
        try {
            android.util.Log.i(
                "MeshRelayEngine",
                "VoicePacketTx frame=${packet.frameIndex} last=${packet.isLastFrame} bytes=${bytes.size} " +
                    "via=${if (viaGatt) "GATT" else "ADV"}"
            )
        } catch (_: Throwable) {}
        refreshStateFlows()
        return true
    }

    /**
     * Procesa un paquete entrante crudo (20 bytes), actualiza métricas de entrega/saltos,
     * registra la entrada en el log CSV y reenvía por inundación controlada si corresponde.
     */
    suspend fun onRawPacketReceived(
        raw: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis(),
        rssi: Int? = null,
        senderAddressSuffix: String? = null,
        scanTimestampNanos: Long? = null
    ) {
        if (raw.size != MeshPacket.PACKET_SIZE_BYTES) return
        val packet = runCatching { MeshPacket.fromByteArray(raw) }.getOrNull() ?: return

        if (!packet.verifyAuthTag(rideSecretKey)) {
            android.util.Log.w("MeshRelayEngine", "AuthMismatch: packetShortId=${packet.shortId} prio=${packet.priority} invalidAuthDropped")
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
        var decodedConvoySignal: IncomingConvoySignal? = null
        var decodedRideLifecycleEvent: IncomingRideLifecycleEvent? = null
        val utcIso = formatUtc(nowEpochMs)
        synchronized(this) {
            if (!senderAddressSuffix.isNullOrBlank()) {
                peerAddresses.getOrPut(packet.shortId.toInt()) { mutableSetOf() }.add(senderAddressSuffix)
            }
            val isDuplicate = seenKeys.containsKey(packet.dedupKey)
            logPacketEntryLocked(
                PacketLogEntry(
                    timestampUtc = utcIso,
                    senderShortId = packet.shortId.toInt(),
                    sequenceNumber = packet.sequenceNumber,
                    ttl = packet.currentTtl,
                    hops = packet.hopsTaken,
                    rssi = rssi,
                    transportMode = transport?.name ?: "BLE_ADV",
                    advertisingMode = currentAdvertisingMode(),
                    isDuplicate = isDuplicate,
                    direction = "RX",
                    advAddressSuffix = senderAddressSuffix,
                    timestampNanos = scanTimestampNanos
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
                if (kindCode in (CONVOY_SIGNAL_PREFIX + 1)..(CONVOY_SIGNAL_PREFIX + 5)) {
                    val signalCode = (kindCode and 0x0F).toByte()
                    val signalKind = ConvoySignalKind.fromCode(signalCode)
                    if (signalKind != null) {
                        decodedConvoySignal = IncomingConvoySignal(
                            senderShortId = packet.shortId,
                            signalKind = signalKind,
                            sequenceNumber = packet.sequenceNumber,
                            hopsTaken = packet.hopsTaken
                        )
                    }
                } else if (kindCode in (RIDE_LIFECYCLE_PREFIX + 1)..(RIDE_LIFECYCLE_PREFIX + 5)) {
                    val eventCode = (kindCode and 0x0F).toByte()
                    val event = RideLifecycleEvent.fromCode(eventCode)
                    if (event != null) {
                        decodedRideLifecycleEvent = IncomingRideLifecycleEvent(
                            senderShortId = packet.shortId,
                            event = event,
                            sequenceNumber = packet.sequenceNumber,
                            hopsTaken = packet.hopsTaken
                        )
                    }
                } else {
                    decodedAnnouncedSignal = AnnouncedMeshSignal(
                        eventId = "EVT:$kindCode:${subjectId.toInt()}:$cycle",
                        announcerShortId = packet.shortId,
                        subjectShortId = subjectId,
                        eventKindCode = kindCode,
                        eventCycle = cycle
                    )
                }
            } else {
                updatePeerTrackLocked(packet, nowEpochMs, rssi)
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
        decodedConvoySignal?.let { signal ->
            android.util.Log.i(
                "MeshRelayEngine",
                "IncomingConvoySignal decoded & emitting: sender=${signal.senderShortId} kind=${signal.signalKind.name}"
            )
            _incomingConvoySignals.tryEmit(signal)
            onConvoySignalReceived?.invoke(signal)
        }
        decodedRideLifecycleEvent?.let { signal ->
            android.util.Log.i(
                "MeshRelayEngine",
                "IncomingRideLifecycleEvent decoded & emitting: sender=${signal.senderShortId} event=${signal.event.name}"
            )
            _incomingRideLifecycleEvents.tryEmit(signal)
            onRideLifecycleEventReceived?.invoke(signal)
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
                        transportMode = transport?.name ?: "BLE_ADV",
                        advertisingMode = currentAdvertisingMode(),
                        isDuplicate = false,
                        direction = "RELAY_TX"
                    )
                )
            }
            transport?.send(toRelay.toByteArray(), toRelay.priority)
        }
    }

    /**
     * Determina si existe al menos un peer activo en la malla cuyo último heartbeat
     * fue recibido dentro de la ventana de tiempo especificada [windowMs] (por defecto 10 s).
     *
     * Fundamental para el odómetro de malla ([com.rutabikersync.app.core.mesh.odometer.MeshOdometer]):
     * el convoy se considera activo solo si hay presencia de otros nodos en radio directo o multi-salto.
     */
    fun hasActivePeerWithin(
        windowMs: Long = 10_000L,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(this) {
            return peerMap.values.any { track ->
                track.shortId != localShortId &&
                track.receivedBeats > 0 &&
                (nowEpochMs - track.lastSeenEpochMs).coerceAtLeast(0L) <= windowMs
            }
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

    private fun updatePeerTrackLocked(packet: MeshPacket, nowEpochMs: Long, rssi: Int? = null) {
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

        // Fix A: el centinela (0,0) que publica el emisor cuando caduca su fix GPS (30 s)
        // no debe borrar la última coordenada real del peer. Si el paquete entrante no es
        // renderizable y ya teníamos un fix válido, se conservan coordenadas y marca de
        // tiempo. `lastSeenEpochMs` SÍ se refresca en cada heartbeat: la vida del peer
        // sigue dependiendo solo del timeout de 15 s, nunca de la calidad del GPS/RSSI.
        val incomingPositionValid = MeshTelemetry.isValidPosition(packet.latitude, packet.longitude)
        val previousValidPosition = existing?.takeIf {
            MeshTelemetry.isValidPosition(it.lastLatitude, it.lastLongitude)
        }
        val effectiveLatitude = if (incomingPositionValid) {
            packet.latitude
        } else {
            previousValidPosition?.lastLatitude ?: packet.latitude
        }
        val effectiveLongitude = if (incomingPositionValid) {
            packet.longitude
        } else {
            previousValidPosition?.lastLongitude ?: packet.longitude
        }
        val effectivePositionEpochMs = if (incomingPositionValid) {
            nowEpochMs
        } else {
            previousValidPosition?.lastPositionEpochMs ?: 0L
        }
        val retainedPreviousPosition = !incomingPositionValid && previousValidPosition != null

        peerMap[packet.shortId] = PeerTrack(
            shortId = packet.shortId,
            lastLatitude = effectiveLatitude,
            lastLongitude = effectiveLongitude,
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
            lastHops = packet.hopsTaken,
            lastRssiDbm = rssi ?: existing?.lastRssiDbm,
            lastPositionEpochMs = effectivePositionEpochMs,
            isPositionRetained = retainedPreviousPosition
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
        val distinctAddressesMap = peerAddresses.mapValues { it.value.size }
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
                isLost = track.isLost,
                distinctAdvAddressesCount = distinctAddressesMap[track.shortId.toInt()] ?: 0
            )
        }
        return MeshFieldReport(
            localNodeId = localShortId.toInt(),
            transportName = transport?.name ?: "BLE_ADV",
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
            peerReports = peerReports,
            distinctAdvAddressesPerPeer = distinctAddressesMap
        )
    }
}
