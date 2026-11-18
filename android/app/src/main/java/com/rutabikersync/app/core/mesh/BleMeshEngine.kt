package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Motor Lógico de Malla BLE (Fase 6 · Hito 1 · BLE_MESH_ARCHITECTURE_HITO_0.md).
 * Desacoplado del framework de Android para ser 100% verificable en JVM Unit Tests.
 *
 * Responsabilidades:
 * - Emisión periódica de Heartbeats de 24 bytes (3s normal, 10s supervivencia).
 * - Deduplicación estricta de tramas entrantes (DeduplicationTable LRU 1024 / 30s).
 * - Conmutación adaptativa de PHY (CodedPhySwitcher con Ajuste 1: timeout de 60s en zona gris).
 * - Monitoreo de salud y pérdida de peers (15s sin señal = descolgado).
 * - Ajuste 2: Modo Aislado tras 30 min sin peers conectados.
 * - Ajuste 3: Mitigación de Batería Baja (<20% aviso; <=10% Modo Supervivencia).
 */
class BleMeshEngine(
    private val externalScope: CoroutineScope? = null,
    private val onTransmitWireBytes: ((ByteArray) -> Unit)? = null,
    private val onPeerCountChanged: ((Int) -> Unit)? = null,
    private val onIsolatedModeChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        const val HEARTBEAT_INTERVAL_NORMAL_MS = 3_000L
        const val HEARTBEAT_INTERVAL_SURVIVAL_MS = 10_000L
        const val ISOLATED_TIMEOUT_MS = 30 * 60 * 1_000L // 30 minutos
        const val PEER_EXPIRATION_MS = 15_000L // 15 segundos sin latido = descolgado
    }

    data class PeerMeshState(
        val shortId: UShort,
        val lastHeartbeat: HeartbeatFrame,
        val lastRssi: Int,
        val lastSeenEpochMs: Long,
        val isLost: Boolean = false
    )

    enum class LifecycleState {
        STOPPED,
        STARTING,
        RUNNING
    }

    private val engineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Componentes de Protocolo Hito 1
    val deduplicationTable = DeduplicationTable()
    val codedPhySwitcher = CodedPhySwitcher { _, newPhy, _ ->
        _currentPhy.value = newPhy
    }

    // Identidad Mesh
    var clubNetworkId: UInt = 0x52425301u // "RBS1"
        private set
    var localShortId: UShort = 0x0001u
        private set
    private var sequenceCounter: UShort = 0u

    // Estados reactivos
    private val _lifecycleState = MutableStateFlow(LifecycleState.STOPPED)
    val lifecycleState: StateFlow<LifecycleState> = _lifecycleState.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _isIsolatedMode = MutableStateFlow(false)
    val isIsolatedMode: StateFlow<Boolean> = _isIsolatedMode.asStateFlow()

    private val _isLowBatterySurvivalMode = MutableStateFlow(false)
    val isLowBatterySurvivalMode: StateFlow<Boolean> = _isLowBatterySurvivalMode.asStateFlow()

    private val _batteryPercent = MutableStateFlow(100)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _currentPhy = MutableStateFlow(CodedPhySwitcher.PhyMode.PHY_1M)
    val currentPhy: StateFlow<CodedPhySwitcher.PhyMode> = _currentPhy.asStateFlow()

    private val _sentHeartbeatsCount = MutableStateFlow(0)
    val sentHeartbeatsCount: StateFlow<Int> = _sentHeartbeatsCount.asStateFlow()

    private val _receivedHeartbeatsCount = MutableStateFlow(0)
    val receivedHeartbeatsCount: StateFlow<Int> = _receivedHeartbeatsCount.asStateFlow()

    // Directorio de peers
    private val peersMap = ConcurrentHashMap<UShort, PeerMeshState>()
    private var lastPeerSeenEpochMs: Long = 0L

    // Trabajos en corrutinas
    private var heartbeatLoopJob: Job? = null
    private var healthCheckLoopJob: Job? = null

    // Telemetría local
    @Volatile var currentLatitude: Double = 10.4806
    @Volatile var currentLongitude: Double = -66.9036
    @Volatile var currentSpeedKmh: Int = 0
    @Volatile var currentHeadingDeg: Float = 0f
    @Volatile var currentFlags: Int = HeartbeatFrame.FLAG_NONE
    var localBootEpoch: Int = 0
        private set

    fun startMeshEngine(clubId: UInt, shortId: UShort, bootEpoch: Int = 0) {
        if (_lifecycleState.value == LifecycleState.RUNNING) return

        clubNetworkId = clubId
        localShortId = shortId
        localBootEpoch = bootEpoch
        _lifecycleState.value = LifecycleState.STARTING
        lastPeerSeenEpochMs = System.currentTimeMillis()

        startPeriodicLoops()
        _lifecycleState.value = LifecycleState.RUNNING
    }

    fun stopMeshEngine() {
        _lifecycleState.value = LifecycleState.STOPPED
        stopPeriodicLoops()
        deduplicationTable.clear()
        codedPhySwitcher.reset()
        peersMap.clear()
        _connectedPeersCount.value = 0
        _isIsolatedMode.value = false
    }

    private fun startPeriodicLoops() {
        heartbeatLoopJob?.cancel()
        heartbeatLoopJob = engineScope.launch {
            while (isActive) {
                emitLocalHeartbeat()
                val interval = if (_isLowBatterySurvivalMode.value) HEARTBEAT_INTERVAL_SURVIVAL_MS else HEARTBEAT_INTERVAL_NORMAL_MS
                delay(interval)
            }
        }

        healthCheckLoopJob?.cancel()
        healthCheckLoopJob = engineScope.launch {
            while (isActive) {
                evaluatePeersHealthAndIsolation(System.currentTimeMillis())
                delay(1000L)
            }
        }
    }

    private fun stopPeriodicLoops() {
        heartbeatLoopJob?.cancel()
        heartbeatLoopJob = null
        healthCheckLoopJob?.cancel()
        healthCheckLoopJob = null
    }

    /**
     * Emite un Heartbeat de 24 bytes empaquetado.
     */
    fun emitLocalHeartbeat(initTtl: Int = 0, currentTtl: Int = 0): HeartbeatFrame {
        sequenceCounter = (sequenceCounter + 1u).toUShort()
        val header = MeshHeader(
            clubNetworkId = clubNetworkId,
            senderShortId = localShortId,
            packetType = MeshHeader.TYPE_HEARTBEAT,
            initTtl = initTtl,
            currentTtl = currentTtl,
            sequenceNum = sequenceCounter
        )

        val frame = HeartbeatFrame(
            header = header,
            latitude = currentLatitude,
            longitude = currentLongitude,
            speedKmh = currentSpeedKmh,
            headingDeg = currentHeadingDeg,
            batteryPercent = _batteryPercent.value,
            flags = currentFlags,
            bootEpoch = localBootEpoch
        )

        val wireBytes = frame.toByteArray()
        _sentHeartbeatsCount.value = _sentHeartbeatsCount.value + 1
        onTransmitWireBytes?.invoke(wireBytes)
        return frame
    }

    /**
     * Procesa una trama recibida (por BLE o simulador de test).
     */
    fun onRawPacketReceived(bytes: ByteArray, rssi: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (bytes.size < MeshHeader.HEADER_SIZE_BYTES) return false

        val header = try {
            MeshHeader.fromByteArray(bytes)
        } catch (e: Exception) {
            return false
        }

        if (header.clubNetworkId != clubNetworkId) return false
        if (header.senderShortId == localShortId) return false

        // Deduplicación LRU
        val isNew = deduplicationTable.shouldProcessAndMark(header.senderShortId, header.sequenceNum, nowMs)
        if (!isNew) return false

        // Conmutador PHY
        codedPhySwitcher.onRssiSample(rssi, nowMs)

        // Payload Heartbeat
        if (header.packetType == MeshHeader.TYPE_HEARTBEAT) {
            if (bytes.size < HeartbeatFrame.FRAME_SIZE_BYTES) return false
            val frame = try {
                HeartbeatFrame.fromByteArray(bytes)
            } catch (e: Exception) {
                return false
            }

            processReceivedHeartbeat(frame, rssi, nowMs)
            return true
        }
        return false
    }

    private fun processReceivedHeartbeat(frame: HeartbeatFrame, rssi: Int, nowMs: Long) {
        val senderId = frame.header.senderShortId
        android.util.Log.i("BleMeshService", "Heartbeat received from shortId=$senderId, rssi=$rssi, speed=${frame.speedKmh} km/h, bat=${frame.batteryPercent}%")
        val state = PeerMeshState(
            shortId = senderId,
            lastHeartbeat = frame,
            lastRssi = rssi,
            lastSeenEpochMs = nowMs,
            isLost = false
        )
        peersMap[senderId] = state
        lastPeerSeenEpochMs = nowMs
        _receivedHeartbeatsCount.value = _receivedHeartbeatsCount.value + 1

        evaluatePeersHealthAndIsolation(nowMs)
    }

    /**
     * Evalúa expiración de peers y condición de Modo Aislado (Ajuste 2).
     */
    fun evaluatePeersHealthAndIsolation(nowMs: Long = System.currentTimeMillis()) {
        var activeCount = 0
        val iterator = peersMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val peer = entry.value
            if (nowMs - peer.lastSeenEpochMs > PEER_EXPIRATION_MS) {
                peersMap[entry.key] = peer.copy(isLost = true)
            } else {
                activeCount++
            }
        }

        val previousCount = _connectedPeersCount.value
        _connectedPeersCount.value = activeCount

        if (previousCount != activeCount) {
            onPeerCountChanged?.invoke(activeCount)
        }

        // Ajuste 2: Modo Aislado tras 30 min sin peers
        if (activeCount == 0) {
            val timeSinceLastPeer = nowMs - lastPeerSeenEpochMs
            if (timeSinceLastPeer >= ISOLATED_TIMEOUT_MS) {
                val wasIsolated = _isIsolatedMode.value
                _isIsolatedMode.value = true
                if (!wasIsolated) {
                    onIsolatedModeChanged?.invoke(true)
                }
            }
        } else {
            lastPeerSeenEpochMs = nowMs
            if (_isIsolatedMode.value) {
                _isIsolatedMode.value = false
                onIsolatedModeChanged?.invoke(false)
            }
        }
    }

    /**
     * Ajuste 3: Mitigación de Batería Baja.
     */
    fun onBatteryLevelChanged(percent: Int) {
        _batteryPercent.value = percent
        val isSurvival = percent <= 10
        _isLowBatterySurvivalMode.value = isSurvival
    }

    fun getPeer(shortId: UShort): PeerMeshState? = peersMap[shortId]

    val allPeers: Map<UShort, PeerMeshState>
        get() = peersMap.toMap()

    fun close() {
        stopMeshEngine()
        if (externalScope == null) {
            engineScope.cancel()
        }
    }
}

