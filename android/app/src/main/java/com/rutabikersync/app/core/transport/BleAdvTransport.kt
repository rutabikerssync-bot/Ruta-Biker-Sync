package com.rutabikersync.app.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Implementación real de Transport sobre BLE Advertising ("emitir y escuchar").
 *
 * Cumple DESIGN.md §3 y MEMORY.md D2 / D27:
 * - Usa BLE Extended Advertising + Coded PHY si el teléfono lo soporta (mayor alcance en carretera).
 * - Cae automáticamente a BLE Legacy Advertising (paquete de 20 bytes en Service Data) si no lo soporta.
 */
class BleAdvTransport(
    context: Context,
    @Volatile var modeOverride: TransportModeOverride? = null,
    @Volatile var advertisingBurstDurationMs: Int = DEFAULT_ADVERTISING_BURST_DURATION_MS
) : Transport {

    enum class TransportModeOverride(val code: String, val displayLabel: String) {
        LEGACY_1M("LEGACY_1M", "Legacy (1M)"),
        EXTENDED_1M("EXTENDED_1M", "Extendido (1M)"),
        EXTENDED_CODED_PHY("EXTENDED_CODED_PHY", "Extendido + Coded PHY");

        companion object {
            fun fromCode(code: String?): TransportModeOverride? =
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }

    companion object {
        const val DEFAULT_ADVERTISING_BURST_DURATION_MS: Int = 1_000
        val SUPPORTED_BURST_DURATIONS_MS: List<Int> = listOf(300, 600, 1_000, 1_500)

        val MESH_SERVICE_UUID: ParcelUuid = ParcelUuid(
            UUID.fromString("0000B1CE-0000-1000-8000-00805F9B34FB")
        )

        private const val TAG = "BleAdvTransport"

        fun describeScanSettings(effectiveMode: String): String {
            val isExtended = effectiveMode == "EXTENDED_1M" || effectiveMode == "EXTENDED_CODED_PHY"
            return if (isExtended) {
                "SCAN_MODE_LOW_LATENCY (2) | legacy=false (Legacy+Extended) | phy=PHY_LE_ALL_SUPPORTED (255: 1M+Coded) | reportDelay=0ms"
            } else {
                "SCAN_MODE_LOW_LATENCY (2) | legacy=true (Legacy only) | phy=PHY_LE_1M (1) | reportDelay=0ms"
            }
        }

        fun describeAdvertiseFailure(errorCode: Int): String = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE (1)"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS (2)"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED (3)"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR (4)"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED (5)"
            else -> "ADVERTISE_FAILED_UNKNOWN ($errorCode)"
        }

        fun describeScanFailure(errorCode: Int): String = when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED (1)"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2)"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR (3)"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED (4)"
            5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (5)"
            6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY (6)"
            else -> "SCAN_FAILED_UNKNOWN ($errorCode)"
        }
    }

    data class BleCapabilities(
        val bluetoothSupported: Boolean,
        val bluetoothEnabled: Boolean,
        val multipleAdvertisementSupported: Boolean,
        val extendedAdvertisingSupported: Boolean,
        val le2MPhySupported: Boolean = false,
        val codedPhySupported: Boolean,
        val maxAdvertisingDataLength: Int = 0
    ) {
        val recommendedMode: String
            get() = when {
                !bluetoothSupported -> "UNSUPPORTED"
                extendedAdvertisingSupported && codedPhySupported -> "EXTENDED_CODED_PHY"
                extendedAdvertisingSupported -> "EXTENDED_1M"
                multipleAdvertisementSupported -> "LEGACY_1M"
                else -> "SCAN_ONLY"
            }

        fun resolveEffectiveMode(override: TransportModeOverride? = null): String {
            if (!bluetoothSupported) return "UNSUPPORTED"
            return when (override) {
                TransportModeOverride.LEGACY_1M ->
                    if (multipleAdvertisementSupported) "LEGACY_1M" else "SCAN_ONLY"
                TransportModeOverride.EXTENDED_1M -> when {
                    extendedAdvertisingSupported -> "EXTENDED_1M"
                    multipleAdvertisementSupported -> "LEGACY_1M"
                    else -> "SCAN_ONLY"
                }
                TransportModeOverride.EXTENDED_CODED_PHY -> when {
                    extendedAdvertisingSupported && codedPhySupported -> "EXTENDED_CODED_PHY"
                    extendedAdvertisingSupported -> "EXTENDED_1M"
                    multipleAdvertisementSupported -> "LEGACY_1M"
                    else -> "SCAN_ONLY"
                }
                null -> recommendedMode
            }
        }
    }

    private val bluetoothManager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _incomingPackets = MutableSharedFlow<Transport.IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingPackets: Flow<Transport.IncomingPacket> = _incomingPackets.asSharedFlow()

    val effectiveMode: String
        get() = queryCapabilities().resolveEffectiveMode(modeOverride)

    override val name: String
        get() = "BleAdvTransport($effectiveMode)"

    private var activeLegacyCallback: AdvertiseCallback? = null
    private var activeExtendedCallback: AdvertisingSetCallback? = null
    private val activeExtendedCallbacks = java.util.concurrent.ConcurrentHashMap.newKeySet<AdvertisingSetCallback>()

    @Volatile
    var advertisingSetsStartedCount: Int = 0
        private set

    @Volatile
    var advertisingSetsStoppedCount: Int = 0
        private set

    @Volatile
    var advertisingBurstsStartedCount: Int = 0
        private set

    @Volatile
    var advertisingBurstsSuccessCount: Int = 0
        private set

    @Volatile
    var advertisingFailuresCount: Int = 0
        private set

    @Volatile
    var lastAdvertisingFailure: String? = null
        private set

    @Volatile
    var scanStartsCount: Int = 0
        private set

    @Volatile
    var scanFailuresCount: Int = 0
        private set

    @Volatile
    var lastScanFailure: String? = null
        private set

    @Volatile
    var lastMeasuredTxPowerDbm: Int? = null
        private set

    fun currentAdvertisingIntervalDescription(mode: String = effectiveMode): String =
        if (mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY") {
            "INTERVAL_MEDIUM (400 slots = 250ms) / SOS: INTERVAL_LOW (160 slots = 100ms)"
        } else {
            "ADVERTISE_MODE_BALANCED (~250ms) / SOS: ADVERTISE_MODE_LOW_LATENCY (~100ms)"
        }

    fun currentTxPowerDescription(mode: String = effectiveMode): String =
        if (mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY") {
            "TX_POWER_HIGH (+1 dBm nominal)"
        } else {
            "ADVERTISE_TX_POWER_HIGH (+1 dBm nominal)"
        }

    fun queryCapabilities(): BleCapabilities {
        val adapter = bluetoothAdapter ?: return BleCapabilities(
            bluetoothSupported = false,
            bluetoothEnabled = false,
            multipleAdvertisementSupported = false,
            extendedAdvertisingSupported = false,
            le2MPhySupported = false,
            codedPhySupported = false,
            maxAdvertisingDataLength = 0
        )
        return BleCapabilities(
            bluetoothSupported = true,
            bluetoothEnabled = adapter.isEnabled,
            multipleAdvertisementSupported = adapter.isMultipleAdvertisementSupported,
            extendedAdvertisingSupported = adapter.isLeExtendedAdvertisingSupported,
            le2MPhySupported = runCatching { adapter.isLe2MPhySupported }.getOrDefault(false),
            codedPhySupported = adapter.isLeCodedPhySupported,
            maxAdvertisingDataLength = runCatching { adapter.leMaximumAdvertisingDataLength }.getOrDefault(0)
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        val filter = ScanFilter.Builder()
            .setServiceData(MESH_SERVICE_UUID, ByteArray(0), ByteArray(0))
            .build()

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)

        val mode = effectiveMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            adapter.isLeExtendedAdvertisingSupported &&
            (mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY")
        ) {
            settingsBuilder.setLegacy(false)
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsBuilder.setLegacy(true)
            settingsBuilder.setPhy(BluetoothDevice.PHY_LE_1M)
        }

        scanStartsCount++
        Log.i(TAG, "startScan (invocation #$scanStartsCount) mode=$mode")
        runCatching {
            scanner?.startScan(listOf(filter), settingsBuilder.build(), scanCallback)
        }.onFailure { ex ->
            scanFailuresCount++
            val desc = "START_SCAN_THREW: ${ex.message}"
            lastScanFailure = desc
            Log.e(TAG, "scanner.startScan threw exception", ex)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        runCatching {
            scanner?.stopScan(scanCallback)
            activeLegacyCallback?.let { advertiser?.stopAdvertising(it) }
            stopAllActiveExtendedAdvertisingSets()
        }
        activeLegacyCallback = null
        activeExtendedCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun stopAllActiveExtendedAdvertisingSets() {
        val adv = advertiser
        activeExtendedCallback?.let { cb ->
            if (activeExtendedCallbacks.remove(cb)) {
                runCatching { adv?.stopAdvertisingSet(cb) }
                advertisingSetsStoppedCount++
            }
        }
        activeExtendedCallback = null
        val snapshot = activeExtendedCallbacks.toList()
        for (cb in snapshot) {
            if (activeExtendedCallbacks.remove(cb)) {
                runCatching { adv?.stopAdvertisingSet(cb) }
                advertisingSetsStoppedCount++
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        val adv = advertiser ?: return
        val caps = queryCapabilities()
        if (!caps.bluetoothEnabled || !caps.multipleAdvertisementSupported) return

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(MESH_SERVICE_UUID, payload)
            .build()

        val mode = caps.resolveEffectiveMode(modeOverride)
        val burstMs = advertisingBurstDurationMs.coerceIn(100, 2_500)
        advertisingBurstsStartedCount++
        Log.i(TAG, "BleAdvTransport.send burst#=$advertisingBurstsStartedCount mode=$mode priority=$priority burstMs=$burstMs")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            caps.extendedAdvertisingSupported &&
            (mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY")
        ) {
            activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
            activeLegacyCallback = null
            // F3: Detener explícitamente cualquier AdvertisingSet previo antes de abrir uno nuevo
            // para no agotar el límite de conjuntos simultáneos del controlador BLE (ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
            stopAllActiveExtendedAdvertisingSets()

            val useCodedPhy = mode == "EXTENDED_CODED_PHY" && caps.codedPhySupported
            val primaryPhy = if (useCodedPhy) {
                BluetoothDevice.PHY_LE_CODED
            } else {
                BluetoothDevice.PHY_LE_1M
            }
            val secondaryPhy = if (useCodedPhy) {
                BluetoothDevice.PHY_LE_CODED
            } else {
                BluetoothDevice.PHY_LE_1M
            }
            val interval = when (priority) {
                Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertisingSetParameters.INTERVAL_LOW
                else -> AdvertisingSetParameters.INTERVAL_MEDIUM
            }
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(interval)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .setPrimaryPhy(primaryPhy)
                .setSecondaryPhy(secondaryPhy)
                .build()

            val callback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int
                ) {
                    if (status == ADVERTISE_SUCCESS) {
                        lastMeasuredTxPowerDbm = txPower
                        advertisingBurstsSuccessCount++
                        Log.i(TAG, "Extended advertise started successfully txPower=$txPower burst#=$advertisingBurstsStartedCount")
                    } else {
                        advertisingFailuresCount++
                        val desc = "EXT_ADV_FAILED_STATUS_$status"
                        lastAdvertisingFailure = desc
                        Log.e(
                            TAG,
                            "Extended onAdvertisingSetStarted failed status=$status ($desc) totalFailures=$advertisingFailuresCount burst#=$advertisingBurstsStartedCount"
                        )
                        if (activeExtendedCallbacks.remove(this)) {
                            runCatching { adv.stopAdvertisingSet(this) }
                            advertisingSetsStoppedCount++
                        }
                    }
                }

                override fun onAdvertisingEnabled(
                    advertisingSet: AdvertisingSet?,
                    enable: Boolean,
                    status: Int
                ) {
                    // Cuando expira la ráfaga configurada (durationUnits10Ms), el chip deshabilita el anuncio (enable = false)
                    // pero mantiene ocupado el slot de AdvertisingSet hasta invocar stopAdvertisingSet(callback).
                    if (!enable && activeExtendedCallbacks.remove(this)) {
                        runCatching { adv.stopAdvertisingSet(this) }
                        advertisingSetsStoppedCount++
                    }
                }

                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                    activeExtendedCallbacks.remove(this)
                }
            }
            activeExtendedCallbacks.add(callback)
            activeExtendedCallback = callback
            val durationUnits10Ms = (burstMs / 10).coerceIn(10, 65_535)
            runCatching {
                advertisingSetsStartedCount++
                adv.startAdvertisingSet(params, advertiseData, null, null, null, durationUnits10Ms, 0, callback)
            }.onFailure { ex ->
                advertisingFailuresCount++
                val desc = "START_EXT_ADV_THREW: ${ex.message}"
                lastAdvertisingFailure = desc
                Log.e(TAG, "startAdvertisingSet threw exception", ex)
                if (activeExtendedCallbacks.remove(callback)) {
                    advertisingSetsStoppedCount++
                }
            }
        } else {
            stopAllActiveExtendedAdvertisingSets()
            activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
            val advMode = when (priority) {
                Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(advMode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(burstMs)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    advertisingBurstsSuccessCount++
                    Log.i(TAG, "Legacy advertise started successfully burst#=$advertisingBurstsStartedCount")
                }

                override fun onStartFailure(errorCode: Int) {
                    advertisingFailuresCount++
                    val desc = describeAdvertiseFailure(errorCode)
                    lastAdvertisingFailure = desc
                    Log.e(
                        TAG,
                        "Legacy onStartFailure errorCode=$errorCode ($desc) totalFailures=$advertisingFailuresCount burst#=$advertisingBurstsStartedCount"
                    )
                }
            }
            activeLegacyCallback = callback
            runCatching {
                advertisingSetsStartedCount++
                adv.startAdvertising(settings, advertiseData, callback)
            }.onFailure { ex ->
                advertisingFailuresCount++
                val desc = "START_LEGACY_ADV_THREW: ${ex.message}"
                lastAdvertisingFailure = desc
                Log.e(TAG, "startAdvertising threw exception", ex)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val bytes = result?.scanRecord?.getServiceData(MESH_SERVICE_UUID) ?: return
            val rssi = result.rssi
            val pkt = Transport.IncomingPacket(bytes = bytes, rssi = rssi)
            _incomingPackets.tryEmit(pkt)
            _incoming.tryEmit(bytes)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { res ->
                val bytes = res.scanRecord?.getServiceData(MESH_SERVICE_UUID) ?: return@forEach
                val rssi = res.rssi
                val pkt = Transport.IncomingPacket(bytes = bytes, rssi = rssi)
                _incomingPackets.tryEmit(pkt)
                _incoming.tryEmit(bytes)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanFailuresCount++
            val desc = describeScanFailure(errorCode)
            lastScanFailure = desc
            Log.e(
                TAG,
                "ScanCallback.onScanFailed errorCode=$errorCode ($desc) totalScanFailures=$scanFailuresCount"
            )
        }
    }
}
