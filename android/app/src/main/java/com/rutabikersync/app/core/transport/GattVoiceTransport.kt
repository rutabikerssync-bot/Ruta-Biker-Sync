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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * Stream GATT dedicado a voz PTT, con un enlace independiente por peer de la rodada (P0.2).
 *
 * El advertising legacy sigue intacto para chat, SOS y heartbeats. Cada peer del manifiesto
 * tiene su propio [VoiceLink], con rol determinista por shortId, MTU, reensamblador y cola
 * propios. El nodo con shortId menor monta el servidor; el mayor conecta como cliente.
 *
 * La dirección del peer llega desde el scan de malla sin inferir identidad del sobre cifrado:
 * `RideService` la empareja con el shortId ya autenticado por el manifiesto. En el enlace
 * GATT, el servidor identifica al cliente por el shortId de la cabecera del frame y el cliente
 * identifica al servidor por el shortId anunciado en los datos del fabricante GATT.
 */
@SuppressLint("MissingPermission")
class GattVoiceTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localShortId: UShort,
    private val multipeerEnabled: Boolean = false
) : VoiceStreamTransport {

    companion object {
        private const val TAG = "GattVoice"

        /** UUID propio, distinto del servicio de malla legacy 0xB1CE. */
        val SERVICE_UUID: UUID = UUID.fromString("0000b1cf-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000b1d0-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000b1d1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Mismo fabricante del manifiesto mesh; aquí solo transporta el shortId del servidor GATT. */
        private const val MANUFACTURER_ID = 0x5242

        private const val REQUESTED_MTU = 517
        private const val MAX_PENDING_FRAMES = 16

        /** Flag de rollback de P0.2: se activa solo tras validar 3 riders en campo. */
        const val PREFS_FLAGS = "rbs_flags"
        const val FLAG_MULTIPEER = "rbs.gatt.multipeer"
    }

    private inner class VoiceLink(val shortId: UShort, address: String) {
        var address: String = address
        var role: GattVoiceRole? = null
        var mtu: Int = GattVoiceFramer.DEFAULT_MTU
        var gatt: BluetoothGatt? = null
        var serverDevice: BluetoothDevice? = null
        var txChar: BluetoothGattCharacteristic? = null
        var rxChar: BluetoothGattCharacteristic? = null
        var ready: Boolean = false
        var writeAck: CompletableDeferred<Int>? = null
        var notifyAck: CompletableDeferred<Int>? = null
        val inbound = GattVoiceFramer.Reassembler()
        val pendingSends = AtomicInteger(0)
        var channel: Channel<ByteArray>? = null
        var senderJob: Job? = null
        var retryJob: Job? = null
        var readyFallbackJob: Job? = null
        var connectWatchdogJob: Job? = null
        /** Solo para logging/backoff: todos los intentos son directos sobre una MAC recién escaneada. */
        var connectAttempts: Int = 0

        fun resetRuntime() {
            ready = false
            gatt = null
            serverDevice = null
            txChar = null
            rxChar = null
            writeAck?.complete(BluetoothGatt.GATT_FAILURE)
            writeAck = null
            notifyAck?.complete(BluetoothGatt.GATT_FAILURE)
            notifyAck = null
            inbound.reset()
            pendingSends.set(0)
            channel?.close()
            channel = null
            senderJob?.cancel()
            senderJob = null
            retryJob?.cancel()
            retryJob = null
            readyFallbackJob?.cancel()
            readyFallbackJob = null
            connectWatchdogJob?.cancel()
            connectWatchdogJob = null
        }
    }

    override val name: String = "GattVoiceTransport(node=$localShortId)"

    @Volatile
    override var isVoiceReady: Boolean = false
        private set

    @Volatile
    var isSessionActive: Boolean = false
        private set

    private val links = mutableMapOf<UShort, VoiceLink>()
    private val serverLinksByAddress = mutableMapOf<String, VoiceLink>()
    /** Conexiones entrantes con MAC aún no mapeada (rotación RPA): se resuelven con el primer frame. */
    private val pendingServerDevices = mutableMapOf<String, BluetoothDevice>()
    /** MTU/CCCD de esas conexiones pendientes: el PTT puede empezar minutos después del connect. */
    private val pendingServerMeta = GattVoicePendingConnections()
    private val clientLinksByAddress = mutableMapOf<String, VoiceLink>()
    private val gattLinks = mutableMapOf<BluetoothGatt, VoiceLink>()
    private val linkRegistry = GattVoiceLinkRegistry(localShortId, multipeerEnabled)

    private var gattServer: BluetoothGattServer? = null
    private var serverAdvCallback: AdvertisingSetCallback? = null
    @Volatile
    private var clientScanCallback: ScanCallback? = null

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val sequence = AtomicInteger(1)

    val voiceFramesSent = AtomicInteger(0)
    val voiceFramesReceived = AtomicInteger(0)
    val voiceFramesDroppedBackpressure = AtomicInteger(0)
    val lastError = java.util.concurrent.atomic.AtomicReference<String?>(null)

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
        Log.i(TAG, "GattVoice start node=$localShortId local=${runCatching { adapter?.address }.getOrNull()}")
        refreshReady()
    }

    override suspend fun stop() {
        isVoiceReady = false
        isSessionActive = false
        stopClientScan()
        val allLinks = synchronized(links) { links.values.toList() }
        allLinks.forEach { link ->
            link.gatt?.let { gatt -> runCatching { gatt.disconnect(); gatt.close() } }
            link.serverDevice?.let { dev -> runCatching { gattServer?.cancelConnection(dev) } }
            link.resetRuntime()
        }
        synchronized(links) { links.clear() }
        synchronized(serverLinksByAddress) { serverLinksByAddress.clear() }
        synchronized(pendingServerDevices) { pendingServerDevices.clear() }
        pendingServerMeta.clear()
        synchronized(clientLinksByAddress) { clientLinksByAddress.clear() }
        synchronized(gattLinks) { gattLinks.clear() }
        linkRegistry.clear()
        serverAdvCallback?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) } }
        serverAdvCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
        Log.i(TAG, "GattVoice stop")
    }

    fun setAllowedPeerShortIds(ids: Set<UShort>) {
        val allowed = ids.filter { it != localShortId }.toSet()
        linkRegistry.setAllowedPeers(allowed)
        Log.i(TAG, "GattVoice allowedPeers=${allowed.sorted()} multipeer=$multipeerEnabled")
        synchronized(links) {
            links.keys.toList().filterNot { it in allowed }.forEach { shortId ->
                val link = links.remove(shortId)
                link?.resetRuntime()
            }
        }
        // Los shortId vienen del manifiesto firmado, así que ya están autenticados. Crear los
        // enlaces aquí evita depender de que el heartbeat llegue completo por advertising:
        // en campo #3 el cliente recibió solo 2 de 4 fragmentos y nunca abrió el scan GATT.
        // Con multipeer=false se conserva el presupuesto de un solo enlace (rollback P0.2).
        val selected = allowed.sortedBy { it.toInt() }.let { if (multipeerEnabled) it else it.take(1) }
        selected.forEach { peerShortId ->
            val role = linkRegistry.roleFor(peerShortId)
            val created = synchronized(links) {
                if (links.containsKey(peerShortId)) {
                    false
                } else {
                    links[peerShortId] = VoiceLink(peerShortId, "").also { it.role = role }
                    true
                }
            }
            // Registrar también en la política: sendTargets() filtra por peers registrados.
            if (linkRegistry.addressFor(peerShortId).isNullOrBlank()) {
                linkRegistry.accept(peerShortId, "")
            }
            if (role == GattVoiceRole.SERVER) {
                runCatching { startGattServerIfNeeded() }
                    .onFailure { lastError.set("ROLE_SETUP_THREW: ${it.message}") }
            } else {
                startClientScanIfNeeded()
            }
            if (created) Log.i(TAG, "GattVoice enlace desde manifiesto role=$role peer=$peerShortId")
        }
    }

    /**
     * Añade/actualiza el enlace de un peer autenticado. Con `multipeerEnabled=true`, cada
     * shortId del manifiesto tiene su propio enlace. Con `false` se conserva el camino de
     * un solo peer como flag de rollback (P0.2).
     */
    fun onMeshPeerAddress(address: String, peerShortId: UShort) {
        if (!linkRegistry.accept(peerShortId, address)) {
            if (multipeerEnabled || peerShortId == localShortId) return
            Log.w(TAG, "GattVoice peer ignorado node=$peerShortId: no autorizado o ya hay un enlace (flag multipeer=false)")
            return
        }

        val link = synchronized(links) {
            links.getOrPut(peerShortId) { VoiceLink(peerShortId, address) }.also { it.address = address }
        }
        if (link.ready || link.gatt != null || link.serverDevice != null) {
            if (link.address.equals(address, ignoreCase = true)) return
        }

        val role = linkRegistry.roleOf(peerShortId) ?: linkRegistry.roleFor(peerShortId)
        link.role = role
        if (role == GattVoiceRole.SERVER) {
            synchronized(serverLinksByAddress) { serverLinksByAddress[address] = link }
            try {
                startGattServerIfNeeded()
            } catch (e: Exception) {
                lastError.set("ROLE_SETUP_THREW: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "GattVoice ${lastError.get()}")
            }
        } else {
            synchronized(clientLinksByAddress) { clientLinksByAddress[address] = link }
            startClientScanIfNeeded()
        }
        Log.i(TAG, "GattVoice role=$role localNode=$localShortId peerNode=$peerShortId peer=$address")
    }

    fun beginVoiceSession() {
        isSessionActive = true
        Log.i(TAG, "GattVoice session START ready=$isVoiceReady")
    }

    fun endVoiceSession() {
        isSessionActive = false
        Log.i(TAG, "GattVoice session END (los enlaces GATT se mantienen para la rodada)")
    }

    override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
        if (priority == Transport.Priority.VOICE) sendVoiceFrame(payload)
    }

    override suspend fun sendVoiceFrame(payload: ByteArray): Boolean {
        val readyByShortId = synchronized(links) {
            links.values.filter { it.ready }.associateBy { it.shortId }
        }
        val targets = linkRegistry.sendTargets(readyByShortId.keys)
        if (targets.isEmpty()) {
            voiceFramesDroppedBackpressure.incrementAndGet()
            return false
        }
        var accepted = 0
        targets.forEach { shortId ->
            val link = readyByShortId[shortId] ?: return@forEach
            val pending = link.pendingSends.incrementAndGet()
            if (pending > MAX_PENDING_FRAMES) {
                link.pendingSends.decrementAndGet()
                voiceFramesDroppedBackpressure.incrementAndGet()
                return@forEach
            }
            ensureSender(link)
            val channel = link.channel
            if (channel == null || !channel.trySend(payload.copyOf()).isSuccess) {
                link.pendingSends.decrementAndGet()
                voiceFramesDroppedBackpressure.incrementAndGet()
                return@forEach
            }
            accepted++
        }
        if (accepted == 0 && voiceFramesDroppedBackpressure.get() % 20 == 1) {
            Log.w(TAG, "GattVoice TX backpressure drops=${voiceFramesDroppedBackpressure.get()}")
        }
        return accepted > 0
    }

    private fun ensureSender(link: VoiceLink) {
        if (link.senderJob?.isActive == true && link.channel != null) return
        val channel = Channel<ByteArray>(Channel.BUFFERED)
        link.channel = channel
        link.senderJob = scope.launch(Dispatchers.IO) {
            for (payload in channel) {
                try {
                    writeChunked(link, payload)
                } catch (e: Exception) {
                    lastError.set("TX: ${e.message}")
                    Log.w(TAG, "GattVoice TX fallo peer=${link.shortId}: ${e.message}")
                } finally {
                    link.pendingSends.decrementAndGet()
                }
            }
        }
    }

    private suspend fun writeChunked(link: VoiceLink, payload: ByteArray) {
        val role = link.role ?: return
        val frames = GattVoiceFramer.frame(
            payload = payload,
            mtu = link.mtu,
            streamId = localShortId.toInt(),
            sequence = sequence.getAndIncrement() and 0xFFFF
        )
        if (role == GattVoiceRole.CLIENT) {
            val gatt = link.gatt ?: return
            val characteristic = link.txChar ?: return
            frames.forEach { chunk ->
                val ack = CompletableDeferred<Int>()
                link.writeAck = ack
                characteristic.value = chunk
                if (!gatt.writeCharacteristic(characteristic)) {
                    link.writeAck = null
                    throw IllegalStateException("writeCharacteristic devolvió false")
                }
                val status = withTimeout(1_000L) { ack.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("writeCharacteristic status=$status")
                }
            }
        } else {
            val server = gattServer ?: return
            val device = link.serverDevice ?: return
            val characteristic = serverRxCharacteristic() ?: return
            frames.forEach { chunk ->
                val ack = CompletableDeferred<Int>()
                link.notifyAck = ack
                characteristic.value = chunk
                if (!server.notifyCharacteristicChanged(device, characteristic, false)) {
                    link.notifyAck = null
                    throw IllegalStateException("notifyCharacteristicChanged devolvió false")
                }
                val status = withTimeout(1_000L) { ack.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("notifyCharacteristicChanged status=$status")
                }
            }
        }
        voiceFramesSent.incrementAndGet()
    }

    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null
    private fun serverRxCharacteristic(): BluetoothGattCharacteristic? = serverRxCharacteristic

    private fun deliverPayload(link: VoiceLink, payload: ByteArray, direction: String) {
        voiceFramesReceived.incrementAndGet()
        _incomingPackets.tryEmit(
            Transport.IncomingPacket(
                bytes = payload.copyOf(),
                rssi = null,
                senderAddressSuffix = link.address.takeLast(5)
            )
        )
        _incoming.tryEmit(payload.copyOf())
        if (voiceFramesReceived.get() % 25 == 1) {
            Log.i(TAG, "GattVoice Rx dir=$direction peer=${link.shortId} bytes=${payload.size}")
        }
    }

    private fun markReady(link: VoiceLink, label: String, mtu: Int) {
        if (link.ready) return
        link.ready = true
        link.mtu = mtu
        link.connectAttempts = 0
        lastError.set(null)
        refreshReady()
        Log.i(TAG, "GattVoice READY role=$label mtu=$mtu peer=${link.shortId}")
        val waitingClient = synchronized(links) {
            links.values.any { it.role == GattVoiceRole.CLIENT && !it.ready }
        }
        if (!waitingClient) stopClientScan()
    }

    private fun refreshReady() {
        isVoiceReady = synchronized(links) { links.values.any { it.ready } }
    }

    private fun resolveServerLink(device: BluetoothDevice?): VoiceLink? {
        val address = device?.address ?: return null
        return synchronized(serverLinksByAddress) { serverLinksByAddress[address] }
    }

    private fun resolveClientLink(gatt: BluetoothGatt?): VoiceLink? {
        if (gatt == null) return null
        synchronized(gattLinks) { gattLinks[gatt]?.let { return it } }
        val address = runCatching { gatt.device?.address }.getOrNull() ?: return null
        return synchronized(clientLinksByAddress) { clientLinksByAddress[address] }
    }

    // ---------------------------------------------------------------------
    // Servidor GATT (notificaciones hacia los clientes de la rodada)
    // ---------------------------------------------------------------------

    private fun startGattServerIfNeeded() {
        if (gattServer != null) return
        gattServer = bluetoothManager?.openGattServer(context.applicationContext, serverCallback)
        val server = gattServer
        if (server == null) {
            lastError.set("GATT_SERVER_OPEN_FAILED")
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
        serverRxCharacteristic = rx
        if (!server.addService(service)) lastError.set("GATT_SERVICE_ADD_FAILED")

        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            lastError.set("GATT_ADVERTISER_UNAVAILABLE")
            return
        }
        // P0.2: el servidor anuncia su shortId para que el cliente sepa a qué enlace conecta.
        val shortIdBytes = byteArrayOf(
            (localShortId.toInt() ushr 8).toByte(),
            localShortId.toInt().toByte()
        )
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, shortIdBytes)
            .build()
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    lastError.set("GATT_ADV_START_FAILED_STATUS_$status")
                    Log.e(TAG, "GattVoice $lastError.get()")
                } else {
                    Log.i(TAG, "GattVoice SERVER advertising started shortId=$localShortId")
                }
            }
        }
        serverAdvCallback = callback
        try {
            advertiser.startAdvertisingSet(params, advData, null, null, null, 0, 0, callback)
        } catch (e: Exception) {
            lastError.set("GATT_ADV_START_THREW: ${e.message}")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.set("GATT_SERVICE_ADD_STATUS_$status")
                Log.e(TAG, "GattVoice SERVER serviceAdded status=$status")
            } else {
                Log.i(TAG, "GattVoice SERVER serviceAdded status=$status uuid=${service?.uuid}")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.i(TAG, "GattVoice SERVER connectionState device=${device?.address} status=$status state=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = device?.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val link = resolveServerLink(device)
                if (link == null) {
                    // No cancelar la conexión: en campo #4 el primer frame de voz llegó ~106 s
                    // después del connect y el watchdog de 8 s dejó al servidor sin subscriptor,
                    // haciendo fallar todas las notificaciones posteriores.
                    Log.w(TAG, "GattVoice SERVER conexión sin enlace mapeado device=$address; se resolverá por frame")
                    if (device != null && address != null) {
                        synchronized(pendingServerDevices) { pendingServerDevices[address] = device }
                        pendingServerMeta.record(address)
                    }
                } else {
                    link.serverDevice = device
                    if (address != null) {
                        pendingServerMeta.take(address)?.let { meta ->
                            link.mtu = meta.mtu
                            Log.i(TAG, "GattVoice SERVER MTU/CCCD aplicados peer=${link.shortId} mtu=${meta.mtu} cccd=${meta.notificationEnabled}")
                        }
                    }
                    link.readyFallbackJob?.cancel()
                    link.readyFallbackJob = scope.launch {
                        delay(1_500L)
                        if (link.serverDevice != null && !link.ready) markReady(link, "SERVER", link.mtu)
                    }
                }
                Log.i(TAG, "GattVoice SERVER connected device=$address")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val link = resolveServerLink(device)
                if (link != null) {
                    link.ready = false
                    link.serverDevice = null
                    link.notifyAck?.complete(BluetoothGatt.GATT_FAILURE)
                    link.notifyAck = null
                }
                if (address != null) {
                    synchronized(pendingServerDevices) { pendingServerDevices.remove(address) }
                    pendingServerMeta.remove(address)
                }
                refreshReady()
                Log.i(TAG, "GattVoice SERVER disconnected device=$address")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            val link = resolveServerLink(device)
            if (link == null) {
                // El cliente negocia MTU antes de enviar el primer frame con shortId; guardarlo
                // evita que el servidor caiga a 23 B cuando la resolución llega después.
                pendingServerMeta.onMtuChanged(address, mtu)
                Log.i(TAG, "GattVoice SERVER mtu pendiente device=$address mtu=$mtu")
                return
            }
            pendingServerMeta.remove(address)
            link.mtu = mtu
            if (link.serverDevice != null) markReady(link, "SERVER", mtu)
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
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            }
            if (value == null || characteristic?.uuid != TX_CHAR_UUID) return
            // Asociar la conexión con el peer de la rodada usando SOLO la cabecera del framing.
            if (resolveServerLink(device) == null) {
                val frameShortId = GattVoiceFramer.senderShortId(value)
                val address = device?.address
                if (frameShortId != null && address != null) {
                    val link = synchronized(links) { links[frameShortId] }
                    if (link != null && link.role == GattVoiceRole.SERVER) {
                        synchronized(serverLinksByAddress) { serverLinksByAddress[address] = link }
                        link.serverDevice = device
                        synchronized(pendingServerDevices) { pendingServerDevices.remove(address) }
                        val meta = pendingServerMeta.take(address)
                        if (meta != null) {
                            link.mtu = meta.mtu
                            Log.i(
                                TAG,
                                "GattVoice SERVER enlace resuelto por frame peer=${link.shortId} address=$address " +
                                    "mtu=${meta.mtu} cccd=${meta.notificationEnabled}"
                            )
                        } else {
                            Log.i(
                                TAG,
                                "GattVoice SERVER enlace resuelto por frame peer=${link.shortId} address=$address"
                            )
                        }
                        markReady(link, "SERVER", link.mtu)
                    }
                }
            }
            val link = resolveServerLink(device) ?: return
            link.inbound.purgeExpired()
            link.inbound.feed(value)?.let { deliverPayload(link, it, "CLIENT->SERVER") }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            resolveServerLink(device)?.notifyAck?.complete(status)
            resolveServerLink(device)?.notifyAck = null
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
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            }
            if (descriptor?.uuid == CCCD_UUID && value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
                val address = device?.address
                val link = resolveServerLink(device)
                if (link == null) {
                    if (address != null) {
                        pendingServerMeta.onNotificationEnabled(address)
                        Log.i(TAG, "GattVoice SERVER CCCD pendiente device=$address")
                    }
                } else {
                    link.serverDevice = device
                    if (address != null) pendingServerMeta.remove(address)
                    markReady(link, "SERVER", link.mtu)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cliente GATT (escrituras + suscripción a notificaciones, un enlace por peer servidor)
    // ---------------------------------------------------------------------

    private fun stopClientScan() {
        clientScanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        clientScanCallback = null
        synchronized(links) {
            links.values.forEach { it.retryJob?.cancel(); it.retryJob = null }
        }
    }

    private fun startClientScanIfNeeded() {
        val pending = synchronized(links) { links.values.filter { it.role == GattVoiceRole.CLIENT && it.gatt == null } }
        if (pending.isEmpty() || clientScanCallback != null) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            lastError.set("GATT_SCANNER_UNAVAILABLE")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val manufacturer = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                val serverShortId = if (manufacturer != null && manufacturer.size >= 2) {
                    // El servidor escribe el shortId en big-endian (ver startGattServerIfNeeded
                    // y GattVoiceFramer); parsearlo al revés hacía fallar el emparejamiento
                    // por shortId y dejaba solo el fallback por MAC.
                    ((((manufacturer[0].toInt() and 0xFF) shl 8) or (manufacturer[1].toInt() and 0xFF))).toUShort()
                } else null
                Log.i(
                    TAG,
                    "GattVoice CLIENT scanResult address=${device.address} serverShortId=$serverShortId"
                )
                val candidates = synchronized(links) {
                    links.values.filter { it.role == GattVoiceRole.CLIENT && it.gatt == null }
                }
                val link = if (serverShortId != null) {
                    candidates.firstOrNull { it.shortId == serverShortId }
                } else {
                    candidates.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                } ?: return
                synchronized(clientLinksByAddress) { clientLinksByAddress[device.address] = link }
                // No detener el scan aquí: si este intento falla con status=133 necesitamos
                // una dirección fresca en el siguiente callback, no reutilizar esta RPA.
                connectDevice(link, device)
            }

            override fun onScanFailed(errorCode: Int) {
                lastError.set("GATT_SCAN_FAILED_$errorCode")
                Log.e(TAG, "GattVoice CLIENT scan failed errorCode=$errorCode")
                stopClientScan()
                synchronized(links) {
                    links.values.filter { it.role == GattVoiceRole.CLIENT && it.gatt == null }.forEach { scheduleClientRetry(it) }
                }
            }
        }
        clientScanCallback = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
            Log.i(TAG, "GattVoice CLIENT scanning service=$SERVICE_UUID")
        } catch (e: Exception) {
            lastError.set("GATT_SCAN_THREW: ${e.message}")
            clientScanCallback = null
            synchronized(links) {
                links.values.filter { it.role == GattVoiceRole.CLIENT && it.gatt == null }.forEach { scheduleClientRetry(it) }
            }
        }
    }

    private fun releaseClientConnection(link: VoiceLink, gatt: BluetoothGatt?) {
        link.connectWatchdogJob?.cancel()
        link.connectWatchdogJob = null
        link.ready = false
        link.txChar = null
        link.rxChar = null
        link.writeAck?.complete(BluetoothGatt.GATT_FAILURE)
        link.writeAck = null
        if (gatt != null) {
            synchronized(gattLinks) { gattLinks.remove(gatt) }
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
            runCatching { gatt.device?.address }.getOrNull()?.let { addr ->
                synchronized(clientLinksByAddress) { clientLinksByAddress.remove(addr) }
            }
        }
        if (gatt == null || link.gatt === gatt) link.gatt = null
        refreshReady()
    }

    private fun connectDevice(link: VoiceLink, device: BluetoothDevice) {
        link.gatt?.let { previous -> releaseClientConnection(link, previous) }
        try {
            // Siempre conexión directa a una MAC recién escaneada. `autoConnect=true` deja el
            // GATT pendiente sobre una RPA vieja y no emite callback: fue la causa del timeout
            // observado en campo (status=133 → 93 s sin actividad).
            link.connectAttempts++
            val gatt = device.connectGatt(
                context.applicationContext,
                false,
                clientCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            link.gatt = gatt
            if (gatt != null) synchronized(gattLinks) { gattLinks[gatt] = link }
            Log.i(
                TAG,
                "GattVoice CLIENT connectGatt peer=${link.shortId} address=${device.address} " +
                    "attempt=${link.connectAttempts} autoConnect=false"
            )
            if (gatt != null) {
                link.connectWatchdogJob = scope.launch {
                    delay(8_000L)
                    if (link.role == GattVoiceRole.CLIENT && !link.ready && link.gatt === gatt) {
                        Log.w(
                            TAG,
                            "GattVoice CLIENT connection timeout peer=${link.shortId} address=${device.address}"
                        )
                        releaseClientConnection(link, gatt)
                        scheduleClientRetry(link)
                    }
                }
            }
        } catch (e: Exception) {
            lastError.set("GATT_CONNECT_THREW: ${e.message}")
            scheduleClientRetry(link)
        }
    }

    private fun scheduleClientRetry(link: VoiceLink) {
        link.retryJob?.cancel()
        val backoff = minOf(12_000L, 3_000L + (link.connectAttempts - 1).coerceAtLeast(0) * 2_000L)
        link.retryJob = scope.launch {
            delay(backoff)
            if (link.role == GattVoiceRole.CLIENT && link.gatt == null) startClientScanIfNeeded()
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val link = resolveClientLink(gatt) ?: return
            Log.i(
                TAG,
                "GattVoice CLIENT connectionState peer=${link.shortId} status=$status state=$newState"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) lastError.set("GATT_CLIENT_STATUS_$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GattVoice CLIENT connected peer=${link.shortId}")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GattVoice CLIENT disconnected peer=${link.shortId} status=$status")
                    releaseClientConnection(link, gatt)
                    scheduleClientRetry(link)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            resolveClientLink(gatt)?.writeAck?.complete(status)
            resolveClientLink(gatt)?.writeAck = null
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val link = resolveClientLink(gatt) ?: return
            Log.i(TAG, "GattVoice CLIENT servicesDiscovered peer=${link.shortId} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.set("GATT_SERVICES_STATUS_$status")
                releaseClientConnection(link, gatt)
                scheduleClientRetry(link)
                return
            }
            val service = gatt?.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_CHAR_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            if (tx == null || rx == null) {
                lastError.set("GATT_CHARS_MISSING")
                Log.e(TAG, "GattVoice CLIENT chars missing service=${service?.uuid} tx=$tx rx=$rx")
                releaseClientConnection(link, gatt)
                scheduleClientRetry(link)
                return
            }
            link.txChar = tx
            link.rxChar = rx
            gatt?.requestMtu(REQUESTED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            val link = resolveClientLink(gatt) ?: return
            Log.i(TAG, "GattVoice CLIENT mtuChanged peer=${link.shortId} mtu=$mtu status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GattVoice MTU request falló peer=${link.shortId}, se usa ${link.mtu}")
            } else {
                link.mtu = mtu
            }
            val rx = link.rxChar ?: return
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
            val link = resolveClientLink(gatt) ?: return
            Log.i(
                TAG,
                "GattVoice CLIENT descriptorWrite peer=${link.shortId} uuid=${descriptor?.uuid} status=$status"
            )
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == CCCD_UUID) {
                markReady(link, "CLIENT", link.mtu)
            } else {
                lastError.set("GATT_CCCD_STATUS_$status")
                releaseClientConnection(link, gatt)
                scheduleClientRetry(link)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid != RX_CHAR_UUID) return
            val value = characteristic.value ?: return
            val link = resolveClientLink(gatt) ?: return
            link.inbound.purgeExpired()
            link.inbound.feed(value)?.let { deliverPayload(link, it, "SERVER->CLIENT") }
        }
    }
}
