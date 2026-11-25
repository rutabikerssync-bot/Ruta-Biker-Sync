package com.rutabikersync.app.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Transporte GATT exclusivo para heartbeats de posición (Opción B).
 *
 * La lógica pura (roles de suscripción, framing y envío) vive en [GattHeartbeatServer] y el
 * acceso a Android queda detrás de [HeartbeatGattServerBackend]. Así los tests JVM pueden
 * sustituir el backend por un fake sin cargar clases Bluetooth.
 *
 * El formato de HeartbeatFrame NO se toca: los bytes que entrega el engine se reenvían
 * tal cual tras el framing de transporte de 2 bytes (total de chunks + índice).
 */
class GattHeartbeatServer(
    private val backend: HeartbeatGattServerBackend,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) }
) {
    companion object {
        const val TAG = "GattHeartbeat"
        val SERVICE_UUID: UUID = UUID.fromString("0000b1d2-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000b1d3-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000b1d4-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEFAULT_MTU = 23
        const val ATT_HEADER_BYTES = 3
        const val TRANSPORT_HEADER_BYTES = 2
        const val MAX_CHUNKS = 16

        fun create(context: Context, localShortId: UShort): GattHeartbeatServer {
            val backend = AndroidHeartbeatGattServerBackend(context.applicationContext, localShortId)
            val server = GattHeartbeatServer(backend)
            backend.attach(server)
            return server
        }

        /** Service data del advertising connectable: shortId real del nodo servidor (big endian). */
        fun shortIdServiceData(localShortId: UShort): ByteArray = byteArrayOf(
            (localShortId.toInt() shr 8).toByte(),
            (localShortId.toInt() and 0xFF).toByte()
        )

        /**
         * Framing compartido Server↔Client: `[totalChunks, chunkIndex] + chunk`.
         */
        fun framesForPayload(payload: ByteArray, negotiatedMtu: Int = DEFAULT_MTU): List<ByteArray> {
            require(payload.isNotEmpty()) { "GattHeartbeat payload vacío" }
            val usable = (negotiatedMtu - ATT_HEADER_BYTES - TRANSPORT_HEADER_BYTES).coerceAtLeast(1)
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + usable, payload.size)
                chunks += payload.copyOfRange(offset, end)
                offset = end
            }
            require(chunks.size <= MAX_CHUNKS) { "GattHeartbeat payload demasiado grande: ${payload.size}B" }
            return chunks.mapIndexed { index, chunk ->
                byteArrayOf(chunks.size.toByte(), index.toByte()) + chunk
            }
        }
    }

    private val notifyMutex = Mutex()
    private val subscribers = ConcurrentHashMap.newKeySet<String>()
    private val rxReassemblers = ConcurrentHashMap<String, HeartbeatFrameReassembler>()
    private val _incoming = MutableSharedFlow<Transport.IncomingPacket>(extraBufferCapacity = 128)
    val incomingPackets: Flow<Transport.IncomingPacket> = _incoming.asSharedFlow()

    @Volatile
    private var mtu: Int = DEFAULT_MTU

    fun start(): Boolean {
        val added = runCatching { backend.addHeartbeatService() }.getOrDefault(false)
        if (!added) {
            log("GattHeartbeat SERVICE_ADD_FAILED")
            return false
        }
        val advertised = runCatching { backend.startConnectableAdvertising() }.getOrDefault(false)
        log("GattHeartbeat SERVER_STARTED service=$SERVICE_UUID tx=$TX_CHAR_UUID advertising=$advertised")
        return true
    }

    fun stop() {
        runCatching { backend.stopConnectableAdvertising() }
        runCatching { backend.close() }
        subscribers.clear()
        log("GattHeartbeat SERVER_STOPPED")
    }

    fun onClientConnected(address: String) {
        log("GattHeartbeat connected device=$address")
    }

    fun onClientDisconnected(address: String) {
        subscribers.remove(address)
        rxReassemblers.remove(address)
        log("GattHeartbeat disconnected device=$address")
    }

    fun onCharacteristicWrite(address: String, value: ByteArray) {
        val reassembler = rxReassemblers.getOrPut(address) { HeartbeatFrameReassembler() }
        val payload = reassembler.feed(value) ?: return
        val suffix = GattHeartbeatClient.addressSuffix(address)
        val emitted = _incoming.tryEmit(
            Transport.IncomingPacket(
                bytes = payload,
                rssi = null,
                timestampEpochMs = System.currentTimeMillis(),
                senderAddressSuffix = suffix
            )
        )
        log("GattHeartbeat onCharacteristicWrite bytes=${payload.size} suffix=$suffix emitted=$emitted")
    }

    fun onMtuChanged(newMtu: Int) {
        mtu = newMtu.coerceAtLeast(DEFAULT_MTU)
        log("GattHeartbeat MTU=$mtu")
    }

    fun onSubscribed(address: String, enabled: Boolean) {
        if (enabled) subscribers.add(address) else subscribers.remove(address)
        log("GattHeartbeat SUBSCRIBED=$enabled device=$address")
    }

    /**
     * Framing de transporte: `[totalChunks, chunkIndex] + chunk`. El receptor concatena los
     * chunks en orden y obtiene exactamente los bytes originales de la trama.
     */
    fun framesFor(payload: ByteArray, negotiatedMtu: Int = mtu): List<ByteArray> =
        framesForPayload(payload, negotiatedMtu)

    /** Envía el heartbeat a todos los clientes suscritos. Devuelve el número de frames entregados. */
    suspend fun notifyHeartbeat(payload: ByteArray): Int = notifyMutex.withLock {
        val targets = subscribers.toList().sorted()
        if (targets.isEmpty()) {
            log("GattHeartbeat notifyHeartbeat SKIP sin suscriptores")
            0
        } else {
            val frames = framesFor(payload)
            var sent = 0
            for (address in targets) {
                for (frame in frames) {
                    val ok = runCatching { backend.notify(address, frame) }.getOrDefault(false)
                    if (ok) sent++ else log("GattHeartbeat NOTIFY_FAILED device=$address")
                }
            }
            log(
                "GattHeartbeat notifyHeartbeat bytes=${payload.size} chunks=${frames.size} " +
                    "targets=${targets.size} sent=$sent"
            )
            sent
        }
    }
}

/** Operaciones GATT que necesita el servidor, sin tipos Android en la firma (testeable en JVM). */
interface HeartbeatGattServerBackend {
    fun addHeartbeatService(): Boolean
    fun startConnectableAdvertising(): Boolean
    fun stopConnectableAdvertising()
    fun notify(address: String, value: ByteArray): Boolean
    fun close()
}

/**
 * Redirige los heartbeats al servidor GATT y deja SOS/eventos/discovery en el transporte BLE
 * original. Se usa como `Transport` del `MeshRelayEngine`, no como reemplazo del advertising.
 */
class HeartbeatGattRoutingTransport(
    private val delegate: Transport,
    private val heartbeatServer: GattHeartbeatServer?,
    private val heartbeatClient: GattHeartbeatClient? = null
) : Transport {
    override val name: String get() =
        if (heartbeatServer != null || heartbeatClient != null) "${delegate.name}+gatt-hb" else delegate.name

    override suspend fun start() = delegate.start()
    override suspend fun stop() = delegate.stop()

    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        if (priority == Transport.Priority.POSITION) {
            val serverSent = heartbeatServer?.notifyHeartbeat(payload) ?: 0
            val clientSent = heartbeatClient?.writeHeartbeat(payload) ?: 0
            if (serverSent + clientSent == 0) {
                delegate.send(payload, priority)
            }
        } else {
            delegate.send(payload, priority)
        }
    }

    override suspend fun sendHeartbeat(payload: ByteArray, priority: Transport.Priority) {
        val serverSent = heartbeatServer?.notifyHeartbeat(payload) ?: 0
        val clientSent = heartbeatClient?.writeHeartbeat(payload) ?: 0
        if (serverSent + clientSent == 0) {
            delegate.sendHeartbeat(payload, priority)
        }
    }

    override val incoming: Flow<ByteArray> get() = delegate.incoming

    /** El engine recibe los heartbeats GATT (Server y Client) por el mismo flow que los de advertising. */
    override val incomingPackets: Flow<Transport.IncomingPacket>? get() {
        val flows = buildList {
            delegate.incomingPackets?.let { add(it) }
            heartbeatClient?.incomingPackets?.let { add(it) }
            heartbeatServer?.incomingPackets?.let { add(it) }
        }
        return when (flows.size) {
            0 -> null
            1 -> flows.first()
            else -> merge(*flows.toTypedArray())
        }
    }
}

/** Implementación Android real del backend GATT (servicio B1D2 + characteristic TX B1D3). */
private class AndroidHeartbeatGattServerBackend(
    private val context: Context,
    private val localShortId: UShort
) : HeartbeatGattServerBackend {
    private val bluetoothManager: BluetoothManager? = runCatching {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }.getOrNull()
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()

    private var heartbeatServer: GattHeartbeatServer? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var advertisingCallback: AdvertisingSetCallback? = null

    fun attach(server: GattHeartbeatServer) {
        heartbeatServer = server
    }

    @SuppressLint("MissingPermission")
    override fun addHeartbeatService(): Boolean {
        val manager = bluetoothManager ?: return false
        val gatt = runCatching { manager.openGattServer(context.applicationContext, serverCallback) }
            .getOrNull()
            ?: return false
        gattServer = gatt

        val service = BluetoothGattService(
            GattHeartbeatServer.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val tx = BluetoothGattCharacteristic(
            GattHeartbeatServer.TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            GattHeartbeatServer.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        tx.addDescriptor(cccd)
        service.addCharacteristic(tx)
        txCharacteristic = tx

        val rx = BluetoothGattCharacteristic(
            GattHeartbeatServer.RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(rx)
        rxCharacteristic = rx

        val added = runCatching { gatt.addService(service) }.getOrDefault(false)
        if (!added) Log.e(GattHeartbeatServer.TAG, "GattHeartbeat no se pudo añadir el servicio")
        return added
    }

    @SuppressLint("MissingPermission")
    override fun startConnectableAdvertising(): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
        // BLE Legacy límite = 31 B: UUID 128-bit en advData (21 B con Flags) y ServiceData en scanResponse (20 B).
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(GattHeartbeatServer.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(GattHeartbeatServer.SERVICE_UUID),
                GattHeartbeatServer.shortIdServiceData(localShortId)
            )
            .build()
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            // Legacy connectable exige scannable en Android.
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                Log.i(GattHeartbeatServer.TAG, "GattHeartbeat ADV_STARTED status=$status txPower=$txPower")
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                Log.i(GattHeartbeatServer.TAG, "GattHeartbeat ADV_STOPPED")
            }
        }
        advertisingCallback = callback
        return runCatching {
            advertiser.startAdvertisingSet(params, advData, scanResponse, null, null, 0, 0, callback)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun stopConnectableAdvertising() {
        advertisingCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
        }
        advertisingCallback = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun notify(address: String, value: ByteArray): Boolean {
        val device = devices[address] ?: return false
        val characteristic = txCharacteristic ?: return false
        val gatt = gattServer ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.notifyCharacteristicChanged(device, characteristic, false, value) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = value
                gatt.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { gattServer?.close() }
        gattServer = null
        devices.clear()
        txCharacteristic = null
        rxCharacteristic = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    devices[address] = device
                    heartbeatServer?.onClientConnected(address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    devices.remove(address)
                    heartbeatServer?.onClientDisconnected(address)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            heartbeatServer?.onMtuChanged(mtu)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val address = device?.address
            if (address != null && descriptor?.uuid == GattHeartbeatServer.CCCD_UUID) {
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                heartbeatServer?.onSubscribed(address, enabled)
            }
            descriptor?.value = value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val address = device?.address
            if (address != null && value != null && characteristic?.uuid == GattHeartbeatServer.RX_CHAR_UUID) {
                heartbeatServer?.onCharacteristicWrite(address, value)
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(GattHeartbeatServer.TAG, "GattHeartbeat NOTIFY_SENT_FAILED status=$status")
            }
        }
    }
}
