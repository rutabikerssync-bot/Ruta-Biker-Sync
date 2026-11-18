package com.rutabikersync.app.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stream GATT dedicado a voz PTT.
 *
 * El advertising legacy queda intacto para chat, SOS y heartbeats: este transporte abre
 * su propio servicio GATT con dos características y negocia MTU grande (hasta 517 B),
 * de modo que un `VoicePacket` cifrado cabe en una escritura/notificación y no hay
 * backpressure de radio. Un extremo actúa como servidor (notifica) y el otro como
 * cliente (escribe + suscribe), sobre la MISMA conexión: el tráfico es bidireccional.
 *
 * El rol se elige de forma determinista comparando direcciones Bluetooth: el nodo con
 * la dirección mayor monta el servidor. Ambos se enteran de la dirección del par por
 * los heartbeats que ya llegan por `BleAdvTransport` (no se añade escaneo nuevo).
 */
@SuppressLint("MissingPermission")
class GattVoiceTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localShortId: UShort
) : VoiceStreamTransport {

    companion object {
        private const val TAG = "GattVoice"

        /** UUID propio, distinto del servicio de malla legacy 0xB1CE. */
        val SERVICE_UUID: UUID = UUID.fromString("0000b1cf-0000-1000-8000-00805f9b34fb")
        /** Cliente -> servidor (VoicePacket cifrado fragmentado según MTU). */
        val TX_CHAR_UUID: UUID = UUID.fromString("0000b1d0-0000-1000-8000-00805f9b34fb")
        /** Servidor -> cliente (notificaciones del VoicePacket cifrado). */
        val RX_CHAR_UUID: UUID = UUID.fromString("0000b1d1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 517
        private const val MAX_PENDING_FRAMES = 16
    }

    private enum class Role { SERVER, CLIENT }

    override val name: String = "GattVoiceTransport(node=$localShortId)"

    @Volatile
    override var isVoiceReady: Boolean = false
        private set

    @Volatile
    var isSessionActive: Boolean = false
        private set

    @Volatile
    private var role: Role? = null
    @Volatile
    private var peerAddress: String? = null
    @Volatile
    private var peerShortId: UShort? = null
    @Volatile
    private var allowedPeerShortIds: Set<UShort> = emptySet()
    @Volatile
    private var localAddress: String? = null
    @Volatile
    private var serverMtu: Int = GattVoiceFramer.DEFAULT_MTU
    @Volatile
    private var clientMtu: Int = GattVoiceFramer.DEFAULT_MTU

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    private var serverDevice: BluetoothDevice? = null
    private var serverRxChar: BluetoothGattCharacteristic? = null
    private var clientTxChar: BluetoothGattCharacteristic? = null
    private var clientRxChar: BluetoothGattCharacteristic? = null
    private var serverAdvCallback: AdvertisingSetCallback? = null
    private var gattScanCallback: ScanCallback? = null
    private var gattScanRetryJob: Job? = null
    private var senderJob: Job? = null
    private var readyFallbackJob: Job? = null

    private val serverInbound = GattVoiceFramer.Reassembler()
    private val clientInbound = GattVoiceFramer.Reassembler()
    private val streamId = (localShortId.toInt() and 0xFFFF)
    private val sequence = AtomicInteger(1)
    private val pendingSends = AtomicInteger(0)

    val voiceFramesSent = AtomicInteger(0)
    val voiceFramesReceived = AtomicInteger(0)
    val voiceFramesDroppedBackpressure = AtomicInteger(0)
    val lastError = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private var sendChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var pendingWriteAck: CompletableDeferred<Int>? = null
    private var pendingNotifyAck: CompletableDeferred<Int>? = null

    private val _incomingPackets = MutableSharedFlow<Transport.IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val incomingPackets: Flow<Transport.IncomingPacket> = _incomingPackets.asSharedFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    override suspend fun start() {
        localAddress = runCatching { adapter?.address }.getOrNull()
        sendChannel = Channel<ByteArray>(Channel.BUFFERED)
        senderJob?.cancel()
        senderJob = scope.launch(Dispatchers.IO) {
            for (payload in sendChannel) {
                try {
                    writeChunked(payload)
                } catch (e: Exception) {
                    lastError.set("TX: ${e.message}")
                    Log.w(TAG, "GattVoice TX fallo: ${e.message}")
                } finally {
                    pendingSends.decrementAndGet()
                }
            }
        }
        Log.i(
            TAG,
            "GattVoice start node=$localShortId local=$localAddress " +
                "extendedAdv=${runCatching { adapter?.isLeExtendedAdvertisingSupported }.getOrDefault(false)}"
        )
    }

    override suspend fun stop() {
        isVoiceReady = false
        isSessionActive = false
        readyFallbackJob?.cancel()
        readyFallbackJob = null
        senderJob?.cancel()
        senderJob = null
        pendingWriteAck?.complete(BluetoothGatt.GATT_FAILURE)
        pendingWriteAck = null
        pendingNotifyAck?.complete(BluetoothGatt.GATT_FAILURE)
        pendingNotifyAck = null
        sendChannel.close()
        serverInbound.reset()
        clientInbound.reset()

        gattClient?.let { runCatching { it.disconnect(); it.close() } }
        gattClient = null
        clientTxChar = null
        clientRxChar = null

        serverDevice?.let { dev -> runCatching { gattServer?.cancelConnection(dev) } }
        serverDevice = null
        serverAdvCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        }
        serverAdvCallback = null
        gattScanRetryJob?.cancel()
        gattScanRetryJob = null
        gattScanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        gattScanCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
        serverRxChar = null
        role = null
        peerAddress = null
        peerShortId = null
        Log.i(TAG, "GattVoice stop")
    }

    /**
     * Llamado por RideService cuando un heartbeat autenticado de malla revela la dirección
     * del peer. El rol se decide por shortId (NO por MAC: Android devuelve 02:00:00:00:00:00).
     * El nodo con shortId menor monta el servidor; el mayor conecta como cliente.
     */
    fun setAllowedPeerShortIds(ids: Set<UShort>) {
        allowedPeerShortIds = ids.filter { it != localShortId }.toSet()
        Log.i(TAG, "GattVoice allowedPeers=${allowedPeerShortIds.sorted()}")
    }

    fun onMeshPeerAddress(address: String, peerShortId: UShort) {
        if (peerShortId == localShortId) return
        if (allowedPeerShortIds.isNotEmpty() && peerShortId !in allowedPeerShortIds) return
        if (peerAddress.equals(address, ignoreCase = true) && this.peerShortId == peerShortId) return
        if (this.peerShortId != null && this.peerShortId != peerShortId) {
            Log.w(TAG, "GattVoice se ignora peer=$peerShortId: ya hay enlace con ${this.peerShortId}")
            return
        }
        if (isVoiceReady && peerAddress.equals(address, ignoreCase = true)) return

        val nextRole = if (localShortId < peerShortId) Role.SERVER else Role.CLIENT
        peerAddress = address
        this.peerShortId = peerShortId
        role = nextRole
        Log.i(
            TAG,
            "GattVoice role=$nextRole localNode=$localShortId peerNode=$peerShortId " +
                "peer=$address advertiser=$localAddress"
        )
        try {
            if (nextRole == Role.SERVER) startGattServer() else connectAsClient()
        } catch (e: Exception) {
            val msg = "ROLE_SETUP_THREW: ${e.javaClass.simpleName}: ${e.message}"
            lastError.set(msg)
            Log.e(TAG, "GattVoice $msg")
            runCatching { gattServer?.close() }
            gattServer = null
            serverRxChar = null
        }
    }

    fun beginVoiceSession() {
        isSessionActive = true
        Log.i(TAG, "GattVoice session START ready=$isVoiceReady mtu=${currentMtu()}")
    }

    fun endVoiceSession() {
        isSessionActive = false
        Log.i(TAG, "GattVoice session END (enlace GATT se mantiene para la rodada)")
    }

    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        if (priority == Transport.Priority.VOICE) {
            sendVoiceFrame(payload)
        }
    }

    override suspend fun sendVoiceFrame(payload: ByteArray): Boolean {
        if (!isVoiceReady) {
            voiceFramesDroppedBackpressure.incrementAndGet()
            return false
        }
        val pending = pendingSends.incrementAndGet()
        if (pending > MAX_PENDING_FRAMES) {
            pendingSends.decrementAndGet()
            voiceFramesDroppedBackpressure.incrementAndGet()
            if (voiceFramesDroppedBackpressure.get() % 20 == 1) {
                Log.w(TAG, "GattVoice TX backpressure pending=$pending dropped=${voiceFramesDroppedBackpressure.get()}")
            }
            return false
        }
        val result = sendChannel.trySend(payload.copyOf())
        if (!result.isSuccess) {
            pendingSends.decrementAndGet()
            voiceFramesDroppedBackpressure.incrementAndGet()
            return false
        }
        return true
    }

    private fun currentMtu(): Int = if (role == Role.SERVER) serverMtu else clientMtu

    /** Serializa el payload en chunks del MTU negociado y los escribe/notifica en orden. */
    private suspend fun writeChunked(payload: ByteArray) {
        val activeRole = role ?: return
        val mtu = currentMtu()
        val frames = GattVoiceFramer.frame(
            payload = payload,
            mtu = mtu,
            streamId = streamId,
            sequence = sequence.getAndIncrement() and 0xFFFF
        )
        if (activeRole == Role.CLIENT) {
            val gatt = gattClient
            val characteristic = clientTxChar
            if (gatt == null || characteristic == null) return
            frames.forEach { chunk ->
                val ack = CompletableDeferred<Int>()
                pendingWriteAck = ack
                characteristic.value = chunk
                if (!gatt.writeCharacteristic(characteristic)) {
                    pendingWriteAck = null
                    throw IllegalStateException("writeCharacteristic devolvió false")
                }
                val status = withTimeout(1_000L) { ack.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("writeCharacteristic status=$status")
                }
            }
        } else {
            val server = gattServer
            val device = serverDevice
            val characteristic = serverRxChar
            if (server == null || device == null || characteristic == null) return
            frames.forEach { chunk ->
                val ack = CompletableDeferred<Int>()
                pendingNotifyAck = ack
                characteristic.value = chunk
                if (!server.notifyCharacteristicChanged(device, characteristic, false)) {
                    pendingNotifyAck = null
                    throw IllegalStateException("notifyCharacteristicChanged devolvió false")
                }
                val status = withTimeout(1_000L) { ack.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("notifyCharacteristicChanged status=$status")
                }
            }
        }
        voiceFramesSent.incrementAndGet()
        if (voiceFramesSent.get() % 25 == 1) {
            Log.i(TAG, "GattVoice Tx role=$activeRole bytes=${payload.size} chunks=${frames.size} mtu=$mtu")
        }
    }

    private fun deliverPayload(payload: ByteArray, direction: String) {
        voiceFramesReceived.incrementAndGet()
        serverInbound.purgeExpired()
        clientInbound.purgeExpired()
        _incomingPackets.tryEmit(
            Transport.IncomingPacket(
                bytes = payload.copyOf(),
                rssi = null,
                senderAddressSuffix = peerAddress?.takeLast(5)
            )
        )
        _incoming.tryEmit(payload.copyOf())
        if (voiceFramesReceived.get() % 25 == 1) {
            Log.i(TAG, "GattVoice Rx dir=$direction bytes=${payload.size} total=${voiceFramesReceived.get()}")
        }
    }

    private fun markReady(label: String, mtu: Int) {
        if (isVoiceReady) return
        isVoiceReady = true
        lastError.set(null)
        Log.i(TAG, "GattVoice READY role=$label mtu=$mtu node=$localShortId")
    }

    // ---------------------------------------------------------------------
    // Servidor GATT (notificaciones hacia el cliente)
    // ---------------------------------------------------------------------

    private fun startGattServer() {
        Log.i(TAG, "GattVoice startGattServer (gattServer=${gattServer != null})")
        if (gattServer != null) return
        gattServer = bluetoothManager?.openGattServer(context.applicationContext, serverCallback)
        val server = gattServer
        if (server == null) {
            lastError.set("GATT_SERVER_OPEN_FAILED")
            Log.e(TAG, "GattVoice no se pudo abrir GattServer")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val rx = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        rx.addDescriptor(cccd)
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        serverRxChar = rx
        if (!server.addService(service)) {
            lastError.set("GATT_SERVICE_ADD_FAILED")
            Log.e(TAG, "GattVoice no se pudo añadir el servicio GATT")
        }

        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            lastError.set("GATT_ADVERTISER_UNAVAILABLE")
            return
        }
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            // LEGACY exige connectable => scannable en Android; si no, lanza IllegalStateException.
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.i(TAG, "GattVoice connectable advertise started txPower=$txPower")
                } else {
                    val desc = "GATT_ADV_START_FAILED_STATUS_$status"
                    lastError.set(desc)
                    Log.e(TAG, "GattVoice $desc (no se toca el advertising de malla)")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.i(TAG, "GattVoice connectable advertise stopped")
            }
        }
        serverAdvCallback = callback
        try {
            advertiser.startAdvertisingSet(params, advData, null, null, null, 0, 0, callback)
        } catch (e: Exception) {
            lastError.set("GATT_ADV_START_THREW: ${e.message}")
            Log.e(TAG, "GattVoice startAdvertisingSet threw: ${e.message}")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.set("GATT_SERVICE_ADD_STATUS_$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevice = device
                Log.i(TAG, "GattVoice SERVER connected device=${device?.address}")
                readyFallbackJob?.cancel()
                readyFallbackJob = scope.launch {
                    delay(1_500L)
                    if (serverDevice != null && !isVoiceReady) markReady("SERVER", serverMtu)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GattVoice SERVER disconnected device=${device?.address}")
                if (serverDevice?.address == device?.address) serverDevice = null
                isVoiceReady = false
                pendingNotifyAck?.complete(BluetoothGatt.GATT_FAILURE)
                pendingNotifyAck = null
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            serverMtu = mtu
            Log.i(TAG, "GattVoice MTU SERVER=$mtu")
            if (serverDevice != null) markReady("SERVER", mtu)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
            if (value == null || characteristic?.uuid != TX_CHAR_UUID) return
            serverInbound.feed(value)?.let { deliverPayload(it, "CLIENT->SERVER") }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            pendingNotifyAck?.complete(status)
            pendingNotifyAck = null
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            descriptor?.value = value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cliente GATT (escrituras + suscripción a notificaciones)
    // ---------------------------------------------------------------------

    /**
     * El cliente NO usa la dirección del heartbeat de malla: esa dirección es un advertiser
     * no conectable y rota. Descubre el servidor por el UUID del servicio GATT de voz,
     * que sí anuncia el nodo servidor como connectable.
     */
    private fun connectAsClient() {
        if (gattClient != null || gattScanCallback != null) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            lastError.set("GATT_SCANNER_UNAVAILABLE")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                runCatching { scanner.stopScan(this) }
                if (gattScanCallback == this) gattScanCallback = null
                connectDevice(device)
            }

            override fun onScanFailed(errorCode: Int) {
                lastError.set("GATT_SCAN_FAILED_$errorCode")
                Log.e(TAG, "GattVoice CLIENT scan failed errorCode=$errorCode")
                if (gattScanCallback == this) gattScanCallback = null
                scheduleClientRetry()
            }
        }
        gattScanCallback = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
            Log.i(TAG, "GattVoice CLIENT scanning service=$SERVICE_UUID")
        } catch (e: Exception) {
            lastError.set("GATT_SCAN_THREW: ${e.message}")
            Log.e(TAG, "GattVoice scan threw: ${e.message}")
            gattScanCallback = null
            scheduleClientRetry()
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        try {
            gattClient = device.connectGatt(
                context.applicationContext,
                false,
                clientCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            Log.i(TAG, "GattVoice CLIENT connectGatt address=${device.address}")
        } catch (e: Exception) {
            lastError.set("GATT_CONNECT_THREW: ${e.message}")
            Log.e(TAG, "GattVoice connectGatt threw: ${e.message}")
            scheduleClientRetry()
        }
    }

    private fun scheduleClientRetry() {
        gattScanRetryJob?.cancel()
        gattScanRetryJob = scope.launch {
            delay(3_000L)
            if (role == Role.CLIENT && gattClient == null && gattScanCallback == null) {
                connectAsClient()
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.set("GATT_CLIENT_STATUS_$status")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GattVoice CLIENT connected")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GattVoice CLIENT disconnected (status=$status)")
                    isVoiceReady = false
                    pendingWriteAck?.complete(BluetoothGatt.GATT_FAILURE)
                    pendingWriteAck = null
                    clientTxChar = null
                    clientRxChar = null
                    gattClient = null
                    if (peerShortId != null && role == Role.CLIENT) scheduleClientRetry()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            pendingWriteAck?.complete(status)
            pendingWriteAck = null
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.set("GATT_SERVICES_STATUS_$status")
                return
            }
            val service = gatt?.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_CHAR_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            if (tx == null || rx == null) {
                lastError.set("GATT_CHARS_MISSING")
                Log.e(TAG, "GattVoice CLIENT características no encontradas")
                return
            }
            clientTxChar = tx
            clientRxChar = rx
            gatt?.requestMtu(REQUESTED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GattVoice MTU request falló status=$status, se usa $clientMtu")
                return
            }
            clientMtu = mtu
            Log.i(TAG, "GattVoice MTU CLIENT=$mtu")
            val rx = clientRxChar ?: return
            val ok = gatt?.setCharacteristicNotification(rx, true) == true
            val cccd = rx.getDescriptor(CCCD_UUID)
            if (ok && cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt?.writeDescriptor(cccd)
            } else {
                lastError.set("GATT_CCCD_ENABLE_FAILED")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == CCCD_UUID) {
                markReady("CLIENT", clientMtu)
            } else {
                lastError.set("GATT_CCCD_STATUS_$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid != RX_CHAR_UUID) return
            val value = characteristic.value ?: return
            clientInbound.feed(value)?.let { deliverPayload(it, "SERVER->CLIENT") }
        }
    }
}
