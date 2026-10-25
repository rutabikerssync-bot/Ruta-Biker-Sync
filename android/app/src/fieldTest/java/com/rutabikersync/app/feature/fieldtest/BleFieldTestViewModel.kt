package com.rutabikersync.app.feature.fieldtest

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel de la app mínima de validación BLE de Fase 0 (ROADMAP.md Fase 0).
 *
 * Permite alternar entre:
 * 1. Hardware BLE Real (BleAdvTransport: Extended Advertising / Coded PHY o Legacy 1M).
 * 2. Simulador de convoy de 5 riders (SimulatedBleMedium en cadena 1-2-3-4-5) para verificar
 *    el reenvío con TTL, % de heartbeats entregados, saltos promedio e islas en un solo dispositivo.
 */
class BleFieldTestViewModel(application: Application) : AndroidViewModel(application) {

    data class FieldTestUiState(
        val isRunning: Boolean = false,
        val useRealBle: Boolean = false,
        val transportModeOverride: BleAdvTransport.TransportModeOverride =
            BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY,
        val initialTtl: Int = MeshPacket.DEFAULT_TTL,
        val islandSimulated: Boolean = false,
        val bleCapabilitiesSummary: String = "",
        val report: MeshRelayEngine.MeshFieldReport? = null,
        val exportedJson: String? = null
    )

    private val rideSecretKey = "rbs-phase0-field-test-secret-key".encodeToByteArray()
    private val realBleTransport = BleAdvTransport(application)
    private val simulatedMedium = SimulatedBleMedium()

    private var localEngine: MeshRelayEngine? = null
    private val virtualPeerEngines = mutableListOf<MeshRelayEngine>()
    private var heartbeatLoopJob: Job? = null
    private var metricsObserverJob: Job? = null

    private val _uiState = MutableStateFlow(
        FieldTestUiState(
            bleCapabilitiesSummary = formatCapabilities(realBleTransport.queryCapabilities())
        )
    )
    val uiState: StateFlow<FieldTestUiState> = _uiState.asStateFlow()

    fun setMode(useRealBle: Boolean) {
        if (_uiState.value.isRunning) return
        _uiState.value = _uiState.value.copy(
            useRealBle = useRealBle,
            bleCapabilitiesSummary = formatCapabilities(realBleTransport.queryCapabilities())
        )
    }

    fun setTransportModeOverride(override: BleAdvTransport.TransportModeOverride) {
        if (_uiState.value.isRunning) return
        realBleTransport.modeOverride = override
        _uiState.value = _uiState.value.copy(
            transportModeOverride = override,
            bleCapabilitiesSummary = formatCapabilities(realBleTransport.queryCapabilities())
        )
    }

    fun setInitialTtl(ttl: Int) {
        _uiState.value = _uiState.value.copy(initialTtl = ttl.coerceIn(1, 7))
    }

    fun startFieldTest() {
        if (_uiState.value.isRunning) return
        val useReal = _uiState.value.useRealBle
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        viewModelScope.launch {
            virtualPeerEngines.clear()
            val engine: MeshRelayEngine = if (useReal) {
                realBleTransport.modeOverride = _uiState.value.transportModeOverride
                val nodeId = ((Build.MODEL.hashCode() and 0x7FFF).coerceAtLeast(1)).toUShort()
                MeshRelayEngine(
                    localShortId = nodeId,
                    transport = realBleTransport,
                    rideSecretKey = rideSecretKey,
                    localDeviceModel = deviceName
                ).also { it.start(viewModelScope) }
            } else {
                val chainIds = listOf<UShort>(1u, 2u, 3u, 4u, 5u)
                simulatedMedium.connectChain(chainIds)
                simulatedMedium.islandSplitActive = _uiState.value.islandSimulated

                val node1Transport = simulatedMedium.createNodeTransport(1u)
                val primary = MeshRelayEngine(
                    localShortId = 1u,
                    transport = node1Transport,
                    rideSecretKey = rideSecretKey,
                    localDeviceModel = "$deviceName (Líder #1)"
                )
                primary.registerPeerDeviceModel("Samsung S23 (#2)")
                primary.registerPeerDeviceModel("Xiaomi Redmi Note (#3)")
                primary.registerPeerDeviceModel("Motorola Edge (#4)")
                primary.registerPeerDeviceModel("Pixel 8a (#5)")
                primary.start(viewModelScope)

                for (id in listOf<UShort>(2u, 3u, 4u, 5u)) {
                    val t = simulatedMedium.createNodeTransport(id)
                    val peerEngine = MeshRelayEngine(
                        localShortId = id,
                        transport = t,
                        rideSecretKey = rideSecretKey,
                        localDeviceModel = "SimRider-$id"
                    )
                    peerEngine.start(viewModelScope)
                    virtualPeerEngines.add(peerEngine)
                }
                primary
            }

            localEngine = engine
            _uiState.value = _uiState.value.copy(isRunning = true, exportedJson = null)

            metricsObserverJob?.cancel()
            metricsObserverJob = viewModelScope.launch {
                engine.metrics.collect { latestReport ->
                    _uiState.value = _uiState.value.copy(report = latestReport)
                }
            }

            heartbeatLoopJob?.cancel()
            heartbeatLoopJob = viewModelScope.launch {
                var tick = 0
                while (isActive) {
                    val ttl = _uiState.value.initialTtl
                    val ext = realBleTransport.queryCapabilities().extendedAdvertisingSupported
                    engine.emitLocalPosition(
                        latitude = 19.432608 + (tick * 0.0001),
                        longitude = -99.133209 + (tick * 0.0001),
                        speedKmh = 75,
                        bearingDegrees = 45f,
                        batteryPercent = 85,
                        isSos = false,
                        isExtendedBle = ext,
                        ttl = ttl
                    )
                    for ((idx, peer) in virtualPeerEngines.withIndex()) {
                        peer.emitLocalPosition(
                            latitude = 19.430000 - (idx * 0.001) + (tick * 0.0001),
                            longitude = -99.130000 - (idx * 0.001) + (tick * 0.0001),
                            speedKmh = 72 + idx,
                            bearingDegrees = 45f,
                            batteryPercent = 80 - (idx * 10),
                            isSos = false,
                            isExtendedBle = true,
                            ttl = ttl
                        )
                    }
                    engine.evaluatePeerTimeouts(System.currentTimeMillis())
                    tick++
                    delay(MeshRelayEngine.HEARTBEAT_INTERVAL_MS)
                }
            }
        }
    }

    fun stopFieldTest() {
        viewModelScope.launch {
            heartbeatLoopJob?.cancel()
            heartbeatLoopJob = null
            metricsObserverJob?.cancel()
            metricsObserverJob = null
            localEngine?.stop()
            virtualPeerEngines.forEach { it.stop() }
            virtualPeerEngines.clear()
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                report = localEngine?.metrics?.value,
                exportedJson = localEngine?.exportReportJson()
            )
        }
    }

    fun emitPrioritySos() {
        val engine = localEngine ?: return
        viewModelScope.launch {
            engine.emitLocalPosition(
                latitude = 19.432608,
                longitude = -99.133209,
                speedKmh = 0,
                bearingDegrees = 0f,
                batteryPercent = 80,
                isSos = true,
                isExtendedBle = realBleTransport.queryCapabilities().extendedAdvertisingSupported,
                ttl = _uiState.value.initialTtl
            )
        }
    }

    fun toggleSimulatedIsland() {
        val next = !_uiState.value.islandSimulated
        simulatedMedium.islandSplitActive = next
        _uiState.value = _uiState.value.copy(islandSimulated = next)
    }

    fun exportReportJson() {
        val json = localEngine?.exportReportJson() ?: return
        _uiState.value = _uiState.value.copy(exportedJson = json)
    }

    private fun formatCapabilities(caps: BleAdvTransport.BleCapabilities): String {
        return "Modo: ${caps.recommendedMode} | BLE: ${caps.bluetoothSupported} | Activo: ${caps.bluetoothEnabled} | " +
            "ExtAdv: ${caps.extendedAdvertisingSupported} | CodedPHY: ${caps.codedPhySupported} | " +
            "PHY 2M: ${caps.le2MPhySupported} | MaxAdvLen: ${caps.maxAdvertisingDataLength} B"
    }
}
