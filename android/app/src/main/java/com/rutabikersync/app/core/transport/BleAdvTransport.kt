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
import androidx.annotation.RequiresApi
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
    @Volatile var advertisingBurstDurationMs: Int = DEFAULT_ADVERTISING_BURST_DURATION_MS,
    @Volatile var txRxRole: TxRxRole = TxRxRole.BOTH,
    @Volatile var unfilteredScanDiagnostic: Boolean = false,
    @Volatile var advertisingDeliveryMode: AdvertisingDeliveryMode = AdvertisingDeliveryMode.BURST
) : Transport {

    enum class AdvertisingDeliveryMode(val code: String, val displayLabel: String) {
        BURST("BURST_1S", "RÁFAGA 1 s"),
        CONTINUOUS("CONTINUOUS", "CONTINUO");

        companion object {
            fun fromCode(code: String?): AdvertisingDeliveryMode =
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: BURST
        }
    }

    enum class TxRxRole(val code: String, val displayLabel: String) {
        BOTH("BOTH", "Tx y Rx (Normal)"),
        TX_ONLY("TX_ONLY", "Solo Emitir (Tx)"),
        RX_ONLY("RX_ONLY", "Solo Escuchar (Rx)");

        companion object {
            fun fromCode(code: String?): TxRxRole =
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: BOTH
        }
    }

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
    private var activeAdvertisingSet: AdvertisingSet? = null
    private var activeAdvertisingSetCallback: AdvertisingSetCallback? = null
    private var activeAdvertisingSetMode: String? = null
    private var currentAdvertisingPriority: Transport.Priority? = null
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

        if (txRxRole != TxRxRole.TX_ONLY) {
            val filter = if (unfilteredScanDiagnostic) {
                // Modo diagnóstico: filtro amplio en hardware para aislar comportamiento de HALs
                ScanFilter.Builder().build()
            } else {
                // Modo producción: filtro estándar por ServiceData UUID (requerido para escaneo continuo con pantalla apagada)
                ScanFilter.Builder()
                    .setServiceData(MESH_SERVICE_UUID, ByteArray(0), ByteArray(0))
                    .build()
            }

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)

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
            Log.i(TAG, "startScan (invocation #$scanStartsCount) mode=$mode diagnostic=$unfilteredScanDiagnostic")
            runCatching {
                scanner?.startScan(listOf(filter), settingsBuilder.build(), scanCallback)
            }.onFailure { ex ->
                scanFailuresCount++
                val desc = "START_SCAN_THREW: ${ex.message}"
                lastScanFailure = desc
                Log.e(TAG, "scanner.startScan threw exception", ex)
            }
        } else {
            Log.i(TAG, "startScan omitido: rol TX_ONLY activo")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        runCatching {
            scanner?.stopScan(scanCallback)
            activeLegacyCallback?.let { advertiser?.stopAdvertising(it) }
            stopAllActiveExtendedAdvertisingSets()
        }
        activeAdvertisingSet = null
        activeLegacyCallback = null
        activeAdvertisingSetCallback = null
        activeAdvertisingSetMode = null
        currentAdvertisingPriority = null
    }

    @SuppressLint("MissingPermission")
    private fun stopAllActiveExtendedAdvertisingSets() {
        val adv = advertiser
        val cb = activeAdvertisingSetCallback
        if (adv != null && cb != null) {
            if (activeExtendedCallbacks.remove(cb)) {
                runCatching { adv.stopAdvertisingSet(cb) }
                advertisingSetsStoppedCount++
            }
        }
        activeAdvertisingSet = null
        activeAdvertisingSetCallback = null
        val snapshot = activeExtendedCallbacks.toList()
        for (extraCb in snapshot) {
            if (activeExtendedCallbacks.remove(extraCb)) {
                runCatching { adv?.stopAdvertisingSet(extraCb) }
                advertisingSetsStoppedCount++
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildAdvertisingSetParameters(
        mode: String,
        caps: BleCapabilities,
        priority: Transport.Priority,
        isExtendedPhy: Boolean
    ): AdvertisingSetParameters {
        val interval = when (priority) {
            Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertisingSetParameters.INTERVAL_LOW
            else -> AdvertisingSetParameters.INTERVAL_MEDIUM
        }
        val builder = AdvertisingSetParameters.Builder()
            .setConnectable(false)
            .setScannable(false)
            .setInterval(interval)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)

        if (isExtendedPhy) {
            val useCodedPhy = mode == "EXTENDED_CODED_PHY" && caps.codedPhySupported
            val phy = if (useCodedPhy) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
            builder.setLegacyMode(false)
                .setPrimaryPhy(phy)
                .setSecondaryPhy(phy)
        } else {
            builder.setLegacyMode(true)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyContinuousFallback(
        adv: BluetoothLeAdvertiser,
        advertiseData: AdvertiseData,
        priority: Transport.Priority
    ) {
        activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
        val advMode = when (priority) {
            Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0) // 0 = Continuo indefinido (sin ráfagas con timeout)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                advertisingBurstsSuccessCount++
                Log.i(TAG, "Legacy continuous advertise started successfully burst#=$advertisingBurstsStartedCount")
            }

            override fun onStartFailure(errorCode: Int) {
                advertisingFailuresCount++
                val desc = describeAdvertiseFailure(errorCode)
                lastAdvertisingFailure = desc
                Log.e(
                    TAG,
                    "Legacy continuous onStartFailure errorCode=$errorCode ($desc) totalFailures=$advertisingFailuresCount"
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

    @SuppressLint("MissingPermission")
    private fun startLegacyBurstFallback(
        adv: BluetoothLeAdvertiser,
        advertiseData: AdvertiseData,
        priority: Transport.Priority,
        burstMs: Int
    ) {
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
                Log.i(TAG, "Legacy burst advertise started successfully burst#=$advertisingBurstsStartedCount")
            }

            override fun onStartFailure(errorCode: Int) {
                advertisingFailuresCount++
                val desc = describeAdvertiseFailure(errorCode)
                lastAdvertisingFailure = desc
                Log.e(
                    TAG,
                    "Legacy burst onStartFailure errorCode=$errorCode ($desc) totalFailures=$advertisingFailuresCount burst#=$advertisingBurstsStartedCount"
                )
            }
        }
        activeLegacyCallback = callback
        runCatching {
            advertisingSetsStartedCount++
            adv.startAdvertising(settings, advertiseData, callback)
        }.onFailure { ex ->
            advertisingFailuresCount++
            val desc = "START_LEGACY_BURST_ADV_THREW: ${ex.message}"
            lastAdvertisingFailure = desc
            Log.e(TAG, "startAdvertising threw exception", ex)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        if (txRxRole == TxRxRole.RX_ONLY) {
            Log.d(TAG, "send omitido: rol RX_ONLY activo")
            return
        }
        val adv = advertiser ?: return
        val caps = queryCapabilities()
        if (!caps.bluetoothEnabled || !caps.multipleAdvertisementSupported) return

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(MESH_SERVICE_UUID, payload)
            .build()

        val mode = caps.resolveEffectiveMode(modeOverride)
        val isExtendedPhy = mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY"
        val supportsAdvertisingSet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && caps.extendedAdvertisingSupported

        advertisingBurstsStartedCount++
        Log.i(TAG, "BleAdvTransport.send burst#=$advertisingBurstsStartedCount mode=$mode delivery=$advertisingDeliveryMode priority=$priority")

        if (advertisingDeliveryMode == AdvertisingDeliveryMode.CONTINUOUS) {
            // MODO CONTINUO (Opción A): Mantener AdvertisingSet encendido indefinidamente y actualizar datos in-place
            if (supportsAdvertisingSet) {
                val currentSet = activeAdvertisingSet
                if (currentSet != null && activeAdvertisingSetMode == mode) {
                    val updateResult = runCatching {
                        currentSet.setAdvertisingData(advertiseData)
                        if (currentAdvertisingPriority != priority) {
                            currentAdvertisingPriority = priority
                            val newParams = buildAdvertisingSetParameters(mode, caps, priority, isExtendedPhy)
                            currentSet.setAdvertisingParameters(newParams)
                        }
                    }
                    if (updateResult.isSuccess) {
                        advertisingBurstsSuccessCount++
                        Log.d(TAG, "Continuous AdvertisingSet data updated in-place burst#=$advertisingBurstsStartedCount")
                        return
                    } else {
                        Log.w(TAG, "setAdvertisingData failed (${updateResult.exceptionOrNull()?.message}), restarting set")
                        stopAllActiveExtendedAdvertisingSets()
                    }
                }

                // Iniciar conjunto continuo si aún no está activo
                stopAllActiveExtendedAdvertisingSets()
                activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
                activeLegacyCallback = null

                val params = buildAdvertisingSetParameters(mode, caps, priority, isExtendedPhy)
                currentAdvertisingPriority = priority
                activeAdvertisingSetMode = mode

                val callback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(
                        advertisingSet: AdvertisingSet?,
                        txPower: Int,
                        status: Int
                    ) {
                        if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                            activeAdvertisingSet = advertisingSet
                            lastMeasuredTxPowerDbm = txPower
                            advertisingBurstsSuccessCount++
                            Log.i(TAG, "Continuous AdvertisingSet started successfully txPower=$txPower mode=$mode")
                        } else {
                            advertisingFailuresCount++
                            val desc = "ADV_SET_FAILED_STATUS_$status"
                            lastAdvertisingFailure = desc
                            Log.e(
                                TAG,
                                "onAdvertisingSetStarted failed status=$status ($desc) totalFailures=$advertisingFailuresCount"
                            )
                            activeAdvertisingSet = null
                            activeExtendedCallbacks.remove(this)
                        }
                    }

                    override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                        if (status == ADVERTISE_SUCCESS) {
                            Log.d(TAG, "onAdvertisingDataSet confirmed burst#=$advertisingBurstsStartedCount")
                        } else {
                            Log.w(TAG, "onAdvertisingDataSet failed status=$status")
                        }
                    }

                    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                        Log.i(TAG, "Continuous AdvertisingSet stopped")
                        if (activeAdvertisingSet == advertisingSet) {
                            activeAdvertisingSet = null
                        }
                        activeExtendedCallbacks.remove(this)
                    }
                }
                activeExtendedCallbacks.add(callback)
                activeAdvertisingSetCallback = callback

                runCatching {
                    advertisingSetsStartedCount++
                    // duration = 0: emisión continua indefinida (sin ráfagas con timeout que apaguen la radio)
                    adv.startAdvertisingSet(params, advertiseData, null, null, null, 0, 0, callback)
                }.onFailure { ex ->
                    advertisingFailuresCount++
                    val desc = "START_ADV_SET_THREW: ${ex.message}"
                    lastAdvertisingFailure = desc
                    Log.e(TAG, "startAdvertisingSet threw exception, falling back to legacy", ex)
                    activeExtendedCallbacks.remove(callback)
                    activeAdvertisingSet = null

                    startLegacyContinuousFallback(adv, advertiseData, priority)
                }
            } else {
                stopAllActiveExtendedAdvertisingSets()
                startLegacyContinuousFallback(adv, advertiseData, priority)
            }
        } else {
            // MODO RÁFAGA (BURST 1s): Ráfaga acotada en el tiempo (advertisingBurstDurationMs) para ahorro de energía
            val burstMs = advertisingBurstDurationMs
            if (supportsAdvertisingSet) {
                stopAllActiveExtendedAdvertisingSets()
                activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
                activeLegacyCallback = null

                val params = buildAdvertisingSetParameters(mode, caps, priority, isExtendedPhy)
                currentAdvertisingPriority = priority
                activeAdvertisingSetMode = mode

                val callback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(
                        advertisingSet: AdvertisingSet?,
                        txPower: Int,
                        status: Int
                    ) {
                        if (status == ADVERTISE_SUCCESS) {
                            lastMeasuredTxPowerDbm = txPower
                            advertisingBurstsSuccessCount++
                            Log.i(TAG, "Burst AdvertisingSet started successfully txPower=$txPower burst#=$advertisingBurstsStartedCount")
                        } else {
                            advertisingFailuresCount++
                            val desc = "BURST_ADV_SET_FAILED_STATUS_$status"
                            lastAdvertisingFailure = desc
                            Log.e(
                                TAG,
                                "Burst onAdvertisingSetStarted failed status=$status ($desc) totalFailures=$advertisingFailuresCount burst#=$advertisingBurstsStartedCount"
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
                        // Al expirar la ráfaga (durationUnits10Ms), el chip deshabilita el anuncio (enable = false).
                        // Liberamos el slot en hardware invocando stopAdvertisingSet(callback).
                        if (!enable && activeExtendedCallbacks.remove(this)) {
                            runCatching { adv.stopAdvertisingSet(this) }
                            advertisingSetsStoppedCount++
                            Log.d(TAG, "Burst AdvertisingSet slot freed after expiration")
                        }
                    }

                    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                        activeExtendedCallbacks.remove(this)
                    }
                }
                activeExtendedCallbacks.add(callback)
                activeAdvertisingSetCallback = callback

                val durationUnits10Ms = (burstMs / 10).coerceIn(10, 65_535)
                runCatching {
                    advertisingSetsStartedCount++
                    adv.startAdvertisingSet(params, advertiseData, null, null, null, durationUnits10Ms, 0, callback)
                }.onFailure { ex ->
                    advertisingFailuresCount++
                    val desc = "START_BURST_ADV_SET_THREW: ${ex.message}"
                    lastAdvertisingFailure = desc
                    Log.e(TAG, "Burst startAdvertisingSet threw exception, falling back to legacy", ex)
                    if (activeExtendedCallbacks.remove(callback)) {
                        advertisingSetsStoppedCount++
                    }
                    startLegacyBurstFallback(adv, advertiseData, priority, burstMs)
                }
            } else {
                stopAllActiveExtendedAdvertisingSets()
                startLegacyBurstFallback(adv, advertiseData, priority, burstMs)
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
