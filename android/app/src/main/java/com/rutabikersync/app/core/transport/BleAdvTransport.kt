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
    @Volatile var modeOverride: TransportModeOverride? = null
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
        val MESH_SERVICE_UUID: ParcelUuid = ParcelUuid(
            UUID.fromString("0000B1CE-0000-1000-8000-00805F9B34FB")
        )
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

    val effectiveMode: String
        get() = queryCapabilities().resolveEffectiveMode(modeOverride)

    override val name: String
        get() = "BleAdvTransport($effectiveMode)"

    private var activeLegacyCallback: AdvertiseCallback? = null
    private var activeExtendedCallback: AdvertisingSetCallback? = null

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

        runCatching {
            scanner?.startScan(listOf(filter), settingsBuilder.build(), scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        runCatching {
            scanner?.stopScan(scanCallback)
            activeLegacyCallback?.let { advertiser?.stopAdvertising(it) }
            activeExtendedCallback?.let { advertiser?.stopAdvertisingSet(it) }
        }
        activeLegacyCallback = null
        activeExtendedCallback = null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            caps.extendedAdvertisingSupported &&
            (mode == "EXTENDED_1M" || mode == "EXTENDED_CODED_PHY")
        ) {
            activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
            activeLegacyCallback = null
            activeExtendedCallback?.let { runCatching { adv.stopAdvertisingSet(it) } }
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
                ) = Unit
            }
            activeExtendedCallback = callback
            runCatching {
                adv.startAdvertisingSet(params, advertiseData, null, null, null, 30, 0, callback)
            }
        } else {
            activeExtendedCallback?.let { runCatching { adv.stopAdvertisingSet(it) } }
            activeExtendedCallback = null
            activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
            val advMode = when (priority) {
                Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(advMode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(1_500)
                .build()

            val callback = object : AdvertiseCallback() {}
            activeLegacyCallback = callback
            runCatching {
                adv.startAdvertising(settings, advertiseData, callback)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val bytes = result?.scanRecord?.getServiceData(MESH_SERVICE_UUID) ?: return
            _incoming.tryEmit(bytes)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { res ->
                res.scanRecord?.getServiceData(MESH_SERVICE_UUID)?.let { _incoming.tryEmit(it) }
            }
        }
    }
}
