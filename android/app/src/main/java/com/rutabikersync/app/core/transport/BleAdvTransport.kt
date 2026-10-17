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
    context: Context
) : Transport {

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
        val codedPhySupported: Boolean
    ) {
        val recommendedMode: String
            get() = when {
                !bluetoothSupported -> "UNSUPPORTED"
                extendedAdvertisingSupported && codedPhySupported -> "EXTENDED_CODED_PHY"
                extendedAdvertisingSupported -> "EXTENDED_1M"
                multipleAdvertisementSupported -> "LEGACY_1M"
                else -> "SCAN_ONLY"
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

    override val name: String
        get() = "BleAdvTransport(${queryCapabilities().recommendedMode})"

    private var activeLegacyCallback: AdvertiseCallback? = null
    private var activeExtendedCallback: AdvertisingSetCallback? = null

    fun queryCapabilities(): BleCapabilities {
        val adapter = bluetoothAdapter ?: return BleCapabilities(
            bluetoothSupported = false,
            bluetoothEnabled = false,
            multipleAdvertisementSupported = false,
            extendedAdvertisingSupported = false,
            codedPhySupported = false
        )
        return BleCapabilities(
            bluetoothSupported = true,
            bluetoothEnabled = adapter.isEnabled,
            multipleAdvertisementSupported = adapter.isMultipleAdvertisementSupported,
            extendedAdvertisingSupported = adapter.isLeExtendedAdvertisingSupported,
            codedPhySupported = adapter.isLeCodedPhySupported
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported) {
            settingsBuilder.setLegacy(false)
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && caps.extendedAdvertisingSupported) {
            activeExtendedCallback?.let { runCatching { adv.stopAdvertisingSet(it) } }
            val primaryPhy = if (caps.codedPhySupported) {
                BluetoothDevice.PHY_LE_CODED
            } else {
                BluetoothDevice.PHY_LE_1M
            }
            val secondaryPhy = if (caps.codedPhySupported) {
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
            activeLegacyCallback?.let { runCatching { adv.stopAdvertising(it) } }
            val mode = when (priority) {
                Transport.Priority.SOS, Transport.Priority.PEER_EVENT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(mode)
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
