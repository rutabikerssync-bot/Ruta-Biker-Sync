package com.rutabikersync.app.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream

/**
 * Cliente GATT de heartbeats (Opción B). Se conecta con `autoConnect=true` al nodo con
 * shortId MAYOR; el nodo con shortId menor actúa como servidor de ese par.
 *
 * La lógica pura (plan de roles, suscripción, reensamblado y emisión) queda en esta clase y
 * Android se aísla en [HeartbeatGattClientBackend] para poder testearla en JVM con un fake.
 */
class GattHeartbeatClient(
    private val backend: HeartbeatGattClientBackend,
    private val localShortId: UShort,
    private val expectedPeerShortIds: Set<UShort> = emptySet(),
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) }
) {
    companion object {
        const val TAG = "GattHeartbeat"
        const val REQUESTED_MTU = 64

        fun create(
            context: Context,
            localShortId: UShort,
            expectedPeerShortIds: Set<UShort>
        ): GattHeartbeatClient {
            val backend = AndroidHeartbeatGattClientBackend(context.applicationContext)
            val client = GattHeartbeatClient(backend, localShortId, expectedPeerShortIds)
            backend.attach(client)
            return client
        }

        /** Regla del par: shortId menor = CLIENT, shortId mayor = SERVER. */
        fun roleFor(localShortId: UShort, peerShortId: UShort): HeartbeatGattRole =
            if (localShortId < peerShortId) HeartbeatGattRole.CLIENT else HeartbeatGattRole.SERVER

        /**
         * Un nodo puede ser cliente de varios peers mayores y servidor de varios menores
         * (malla completa de 3+ riders). Así cada par tiene exactamente un cliente y un servidor.
         */
        fun planFor(localShortId: UShort, peerShortIds: Collection<UShort>): HeartbeatGattPlan {
            val peers = peerShortIds.filter { it != localShortId }.toSet()
            return HeartbeatGattPlan(
                clientTargets = peers.filter { localShortId < it }.toSet(),
                serverForPeers = peers.filter { it < localShortId }.toSet()
            )
        }

        fun addressSuffix(address: String?): String? {
            if (address.isNullOrBlank()) return null
            return if (address.length >= 5 && address.contains(':')) {
                address.takeLast(5)
            } else {
                address.takeLast(4)
            }
        }
    }

    private val plan = planFor(localShortId, expectedPeerShortIds)
    private val reassembler = HeartbeatFrameReassembler()
    private val _incoming = MutableSharedFlow<Transport.IncomingPacket>(extraBufferCapacity = 128)

    val incomingPackets: Flow<Transport.IncomingPacket> = _incoming.asSharedFlow()

    @Volatile
    private var connectedAddress: String? = null

    fun startClient(): Boolean {
        if (!plan.isClient) {
            log("GattHeartbeat CLIENT SKIP rol=SERVER_ONLY node=$localShortId")
            return false
        }
        val started = runCatching { backend.startScanning() }.getOrDefault(false)
        log("GattHeartbeat CLIENT_STARTED node=$localShortId targets=${plan.clientTargets} scan=$started")
        return started
    }

    fun stopClient() {
        runCatching { backend.close() }
        reassembler.reset()
        connectedAddress = null
        log("GattHeartbeat CLIENT_STOPPED node=$localShortId")
    }

    fun onPeerDiscovered(address: String, peerShortId: UShort?) {
        if (peerShortId == null) {
            log("GattHeartbeat PEER_SKIP sin shortId en service data address=$address")
            return
        }
        if (peerShortId == localShortId) return
        if (peerShortId !in plan.clientTargets) {
            log("GattHeartbeat PEER_SKIP peer=$peerShortId fuera del plan de cliente")
            return
        }
        val connected = runCatching { backend.connect(address) }.getOrDefault(false)
        log("GattHeartbeat CLIENT_CONNECT peer=$peerShortId address=$address ok=$connected")
        if (connected) runCatching { backend.stopScanning() }
    }

    fun onConnected(address: String?) {
        connectedAddress = address
        log("GattHeartbeat connected device=$address")
        runCatching { backend.requestMtu(REQUESTED_MTU) }
    }

    fun onServicesDiscovered(hasTxCharacteristic: Boolean) {
        if (!hasTxCharacteristic) {
            log("GattHeartbeat CLIENT SERVICE_MISSING")
            return
        }
        val subscribed = runCatching { backend.subscribe() }.getOrDefault(false)
        log("GattHeartbeat SUBSCRIBE ok=$subscribed")
    }

    fun onMtuChanged(mtu: Int) {
        log("GattHeartbeat CLIENT_MTU mtu=$mtu")
    }

    fun onCharacteristicChanged(value: ByteArray) {
        val payload = reassembler.feed(value) ?: return
        val suffix = addressSuffix(connectedAddress)
        val emitted = _incoming.tryEmit(
            Transport.IncomingPacket(
                bytes = payload,
                rssi = null,
                timestampEpochMs = System.currentTimeMillis(),
                senderAddressSuffix = suffix
            )
        )
        log("GattHeartbeat onCharacteristicChanged bytes=${payload.size} suffix=$suffix emitted=$emitted")
    }

    fun onDisconnected() {
        reassembler.reset()
        log("GattHeartbeat CLIENT_DISCONNECTED autoConnect=activo")
    }
}

enum class HeartbeatGattRole { CLIENT, SERVER }

data class HeartbeatGattPlan(
    val clientTargets: Set<UShort>,
    val serverForPeers: Set<UShort>
) {
    val isClient: Boolean get() = clientTargets.isNotEmpty()
    val isServer: Boolean get() = serverForPeers.isNotEmpty()
}

/**
 * Reensambla los frames `[total, indice] + chunk` del servidor. El payload resultante es
 * exactamente el mismo ByteArray que entregó el engine (no se reinterpreta HeartbeatFrame).
 */
class HeartbeatFrameReassembler {
    private val chunks = mutableMapOf<Int, ByteArray>()

    fun feed(frame: ByteArray): ByteArray? {
        if (frame.size < 3) return null
        val total = frame[0].toInt() and 0xFF
        val index = frame[1].toInt() and 0xFF
        if (total !in 1..16 || index !in 0 until total) return null
        chunks[index] = frame.copyOfRange(2, frame.size)
        if (chunks.size != total) return null
        val out = ByteArrayOutputStream()
        for (i in 0 until total) {
            val chunk = chunks[i] ?: return null
            out.write(chunk, 0, chunk.size)
        }
        chunks.clear()
        return out.toByteArray()
    }

    fun reset() {
        chunks.clear()
    }
}

/** Operaciones GATT del cliente sin tipos Android en la firma (testeable en JVM). */
interface HeartbeatGattClientBackend {
    fun startScanning(): Boolean
    fun stopScanning()
    fun connect(address: String): Boolean
    fun requestMtu(mtu: Int): Boolean
    fun subscribe(): Boolean
    fun discoverServices(): Boolean
    fun close()
}

/** Implementación Android real: scan B1D2, connectGatt(autoConnect=true), notify TX. */
private class AndroidHeartbeatGattClientBackend(
    context: Context
) : HeartbeatGattClientBackend {
    private val context = context.applicationContext
    private val bluetoothManager: BluetoothManager? = runCatching {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }.getOrNull()
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var heartbeatClient: GattHeartbeatClient? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    fun attach(client: GattHeartbeatClient) {
        heartbeatClient = client
    }

    @SuppressLint("MissingPermission")
    override fun startScanning(): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GattHeartbeatServer.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val address = result?.device?.address ?: return
                val data = result.scanRecord?.getServiceData(
                    ParcelUuid(GattHeartbeatServer.SERVICE_UUID)
                )
                val peerShortId = if (data != null && data.size >= 2) {
                    ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)).toUShort()
                } else {
                    null
                }
                heartbeatClient?.onPeerDiscovered(address, peerShortId)
            }
        }
        scanCallback = callback
        return runCatching {
            scanner.startScan(listOf(filter), settings, callback)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun stopScanning() {
        val callback = scanCallback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String): Boolean {
        if (gatt != null) return true
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return false
        gatt = runCatching { device.connectGatt(context, true, gattCallback) }.getOrNull()
        return gatt != null
    }

    override fun requestMtu(mtu: Int): Boolean =
        runCatching { gatt?.requestMtu(mtu) != null }.getOrDefault(false)

    override fun discoverServices(): Boolean =
        runCatching { gatt?.discoverServices() != null }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun subscribe(): Boolean {
        val characteristic = txCharacteristic ?: return false
        val enabled = runCatching {
            gatt?.setCharacteristicNotification(characteristic, true) == true
        }.getOrDefault(false)
        val descriptor = characteristic.getDescriptor(GattHeartbeatServer.CCCD_UUID) ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val written = runCatching { gatt?.writeDescriptor(descriptor) != null }.getOrDefault(false)
        return enabled && written
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        stopScanning()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        txCharacteristic = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                heartbeatClient?.onDisconnected()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    heartbeatClient?.onConnected(gatt?.device?.address)
                    runCatching { gatt?.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txCharacteristic = null
                    heartbeatClient?.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            txCharacteristic = gatt?.getService(GattHeartbeatServer.SERVICE_UUID)
                ?.getCharacteristic(GattHeartbeatServer.TX_CHAR_UUID)
            heartbeatClient?.onServicesDiscovered(txCharacteristic != null)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) heartbeatClient?.onMtuChanged(mtu)
        }

        @Suppress("DEPRECATION", "UNUSED_PARAMETER")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val value = characteristic?.value ?: return
            if (characteristic.uuid == GattHeartbeatServer.TX_CHAR_UUID) {
                heartbeatClient?.onCharacteristicChanged(value)
            }
        }
    }
}
