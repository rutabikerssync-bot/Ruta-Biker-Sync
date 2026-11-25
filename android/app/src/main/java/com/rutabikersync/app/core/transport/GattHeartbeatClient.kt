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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Cliente GATT de heartbeats (Opción B). Se conecta directamente (`autoConnect=false`) al nodo
 * con shortId MAYOR; el nodo con shortId menor actúa como servidor de ese par.
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

    private data class PeerLinkState(
        var peerShortId: UShort? = null,
        var connected: Boolean = false,
        var negotiatedMtu: Int = GattHeartbeatServer.DEFAULT_MTU,
        var pendingRxReady: Boolean = false,
        var readyToWrite: Boolean = false,
        val reassembler: HeartbeatFrameReassembler = HeartbeatFrameReassembler()
    )

    private val plan = planFor(localShortId, expectedPeerShortIds)
    private val peerLinks = ConcurrentHashMap<String, PeerLinkState>()
    private val writeMutex = Mutex()
    private val _incoming = MutableSharedFlow<Transport.IncomingPacket>(extraBufferCapacity = 128)

    val incomingPackets: Flow<Transport.IncomingPacket> = _incoming.asSharedFlow()

    @Volatile
    private var lastAddress: String? = null

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
        peerLinks.clear()
        lastAddress = null
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
        val existingForPeer = peerLinks.entries.firstOrNull {
            it.value.peerShortId == peerShortId && it.value.connected
        }
        if (existingForPeer != null) {
            return
        }
        val link = peerLinks.getOrPut(address) { PeerLinkState() }
        link.peerShortId = peerShortId
        val connected = runCatching { backend.connect(address) }.getOrDefault(false)
        log("GattHeartbeat CLIENT_CONNECT peer=$peerShortId address=$address ok=$connected")
    }

    fun onConnected(address: String?) {
        val target = address ?: return
        lastAddress = target
        val link = peerLinks.getOrPut(target) { PeerLinkState() }
        link.connected = true
        log("GattHeartbeat connected device=$target")
        val mtuRequested = runCatching { backend.requestMtu(target, REQUESTED_MTU) }.getOrDefault(false)
        if (!mtuRequested) {
            link.negotiatedMtu = GattHeartbeatServer.DEFAULT_MTU
        }
        runCatching { backend.discoverServices(target) }
        stopScanningIfAllTargetsEstablished()
    }

    private fun stopScanningIfAllTargetsEstablished() {
        if (plan.clientTargets.isEmpty()) return
        val connectedTargets = peerLinks.values
            .filter { it.connected }
            .mapNotNull { it.peerShortId }
            .toSet()
        if (connectedTargets.containsAll(plan.clientTargets)) {
            runCatching { backend.stopScanning() }
        }
    }

    fun onServicesDiscovered(
        hasTxCharacteristic: Boolean,
        hasRxCharacteristic: Boolean = false,
        address: String? = lastAddress
    ) {
        if (!hasTxCharacteristic) {
            log("GattHeartbeat CLIENT SERVICE_MISSING device=$address")
            return
        }
        val target = address ?: ""
        val link = peerLinks.getOrPut(target) { PeerLinkState(connected = true) }
        link.pendingRxReady = hasRxCharacteristic
        link.readyToWrite = false
        val subscribed = if (address != null) {
            runCatching { backend.subscribe(address) }.getOrDefault(false)
        } else {
            runCatching { backend.subscribe() }.getOrDefault(false)
        }
        log("GattHeartbeat SUBSCRIBE ok=$subscribed rxPending=$hasRxCharacteristic device=$address")
    }

    fun onDescriptorWrite(address: String?, success: Boolean) {
        val target = address ?: lastAddress ?: return
        val link = peerLinks[target] ?: return
        link.readyToWrite = success && link.pendingRxReady
        log("GattHeartbeat CCCD_WRITTEN device=$target success=$success readyToWrite=${link.readyToWrite}")
    }

    fun onMtuChanged(mtu: Int, address: String? = lastAddress) {
        val target = address ?: lastAddress ?: return
        val link = peerLinks.getOrPut(target) { PeerLinkState() }
        link.negotiatedMtu = mtu.coerceAtLeast(GattHeartbeatServer.DEFAULT_MTU)
        log("GattHeartbeat CLIENT_MTU device=$target mtu=${link.negotiatedMtu}")
    }

    /** Envía el heartbeat del cliente al servidor GATT por la characteristic RX (B1D4). */
    suspend fun writeHeartbeat(payload: ByteArray): Int = writeMutex.withLock {
        val readyEntries = peerLinks.entries.filter { it.value.readyToWrite }
        if (readyEntries.isEmpty()) {
            log("GattHeartbeat writeHeartbeat SKIP no conectado/sin RX")
            return@withLock 0
        }
        var totalSent = 0
        for ((address, link) in readyEntries) {
            val frames = GattHeartbeatServer.framesForPayload(payload, link.negotiatedMtu)
            var sent = 0
            for (frame in frames) {
                val ok = runCatching { backend.write(address, frame) }.getOrDefault(false)
                if (ok) sent++ else log("GattHeartbeat WRITE_FAILED device=$address bytes=${frame.size}")
            }
            totalSent += sent
            log("GattHeartbeat writeHeartbeat device=$address bytes=${payload.size} chunks=${frames.size} sent=$sent")
        }
        totalSent
    }

    fun onCharacteristicChanged(value: ByteArray, address: String? = lastAddress) {
        val target = address ?: lastAddress ?: ""
        val link = peerLinks.getOrPut(target) { PeerLinkState() }
        val payload = link.reassembler.feed(value) ?: return
        val suffix = addressSuffix(address ?: lastAddress)
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

    fun onDisconnected(address: String? = lastAddress) {
        val target = address ?: lastAddress
        if (target != null) {
            peerLinks[target]?.let { link ->
                link.reassembler.reset()
                link.connected = false
                link.pendingRxReady = false
                link.readyToWrite = false
                link.negotiatedMtu = GattHeartbeatServer.DEFAULT_MTU
            }
        }
        log("GattHeartbeat CLIENT_DISCONNECTED device=$target autoConnect=false")
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
    fun requestMtu(address: String, mtu: Int): Boolean = requestMtu(mtu)
    fun subscribe(): Boolean
    fun subscribe(address: String): Boolean = subscribe()
    fun write(value: ByteArray): Boolean = false
    fun write(address: String, value: ByteArray): Boolean = write(value)
    fun discoverServices(): Boolean
    fun discoverServices(address: String): Boolean = discoverServices()
    fun close()
}

/** Implementación Android real: scan B1D2, connectGatt(autoConnect=false), múltiples peers GATT. */
private class AndroidHeartbeatGattClientBackend(
    context: Context
) : HeartbeatGattClientBackend {
    private data class PeerGattLink(
        val gatt: BluetoothGatt,
        var txCharacteristic: BluetoothGattCharacteristic? = null,
        var rxCharacteristic: BluetoothGattCharacteristic? = null
    )

    private val context = context.applicationContext
    private val bluetoothManager: BluetoothManager? = runCatching {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }.getOrNull()
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var heartbeatClient: GattHeartbeatClient? = null
    private var scanCallback: ScanCallback? = null
    private val gattLinks = ConcurrentHashMap<String, PeerGattLink>()
    // El BluetoothDevice del ScanResult conserva el tipo de dirección (RPA); getRemoteDevice no.
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()

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
        val callback = scanCallback ?: object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val address = device.address
                discoveredDevices[address] = device
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

            override fun onScanFailed(errorCode: Int) {
                Log.e(GattHeartbeatClient.TAG, "GattHeartbeat CLIENT_SCAN_FAILED errorCode=$errorCode")
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
        if (gattLinks.containsKey(address)) return true
        val device: BluetoothDevice = discoveredDevices[address]
            ?: adapter?.getRemoteDevice(address)
            ?: return false
        // autoConnect=false: el stack no puede registrar una RPA como conexión de fondo.
        val newGatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull() ?: return false
        gattLinks[address] = PeerGattLink(gatt = newGatt)
        return true
    }

    override fun requestMtu(mtu: Int): Boolean =
        gattLinks.keys.firstOrNull()?.let { requestMtu(it, mtu) } ?: false

    @SuppressLint("MissingPermission")
    override fun requestMtu(address: String, mtu: Int): Boolean =
        runCatching { gattLinks[address]?.gatt?.requestMtu(mtu) == true }.getOrDefault(false)

    override fun discoverServices(): Boolean =
        gattLinks.keys.firstOrNull()?.let { discoverServices(it) } ?: false

    @SuppressLint("MissingPermission")
    override fun discoverServices(address: String): Boolean =
        runCatching { gattLinks[address]?.gatt?.discoverServices() == true }.getOrDefault(false)

    override fun subscribe(): Boolean =
        gattLinks.keys.firstOrNull()?.let { subscribe(it) } ?: false

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun subscribe(address: String): Boolean {
        val link = gattLinks[address] ?: return false
        val characteristic = link.txCharacteristic ?: return false
        val enabled = runCatching {
            link.gatt.setCharacteristicNotification(characteristic, true) == true
        }.getOrDefault(false)
        val descriptor = characteristic.getDescriptor(GattHeartbeatServer.CCCD_UUID) ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val written = runCatching { link.gatt.writeDescriptor(descriptor) == true }.getOrDefault(false)
        return enabled && written
    }

    override fun write(value: ByteArray): Boolean =
        gattLinks.keys.firstOrNull()?.let { write(it, value) } ?: false

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun write(address: String, value: ByteArray): Boolean {
        val link = gattLinks[address] ?: return false
        val characteristic = link.rxCharacteristic ?: return false
        val activeGatt = link.gatt
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = value
                activeGatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        stopScanning()
        gattLinks.values.forEach { link ->
            runCatching { link.gatt.disconnect() }
            runCatching { link.gatt.close() }
        }
        gattLinks.clear()
        discoveredDevices.clear()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val address = gatt?.device?.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                releaseLinkAndRescan(address)
                heartbeatClient?.onDisconnected(address)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    heartbeatClient?.onConnected(address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    releaseLinkAndRescan(address)
                    heartbeatClient?.onDisconnected(address)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = gatt?.device?.address ?: return
            val service = gatt.getService(GattHeartbeatServer.SERVICE_UUID)
            val tx = service?.getCharacteristic(GattHeartbeatServer.TX_CHAR_UUID)
            val rx = service?.getCharacteristic(GattHeartbeatServer.RX_CHAR_UUID)
            gattLinks[address]?.let { link ->
                link.txCharacteristic = tx
                link.rxCharacteristic = rx
            }
            heartbeatClient?.onServicesDiscovered(tx != null, rx != null, address)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (descriptor?.uuid == GattHeartbeatServer.CCCD_UUID) {
                heartbeatClient?.onDescriptorWrite(
                    address = gatt?.device?.address,
                    success = status == BluetoothGatt.GATT_SUCCESS
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                heartbeatClient?.onMtuChanged(mtu, gatt?.device?.address)
            }
        }

        @Suppress("DEPRECATION", "UNUSED_PARAMETER")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val value = characteristic?.value ?: return
            if (characteristic.uuid == GattHeartbeatServer.TX_CHAR_UUID) {
                heartbeatClient?.onCharacteristicChanged(value, gatt?.device?.address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseLinkAndRescan(address: String?) {
        if (address != null) {
            gattLinks.remove(address)?.let { link ->
                runCatching { link.gatt.close() }
            }
            discoveredDevices.remove(address)
        }
        startScanning()
    }
}
