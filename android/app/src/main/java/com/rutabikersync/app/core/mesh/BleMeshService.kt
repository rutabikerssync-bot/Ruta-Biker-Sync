package com.rutabikersync.app.core.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rutabikersync.app.MainActivity
import com.rutabikersync.app.core.mesh.audio.AndroidAudioFocusController
import com.rutabikersync.app.core.mesh.audio.AudioFocusController
import com.rutabikersync.app.core.mesh.audio.PttAudioEngine
import com.rutabikersync.app.core.mesh.audio.PttState
import com.rutabikersync.app.core.mesh.audio.VoicePacket
import com.rutabikersync.app.core.mesh.sos.MedicalRescueData
import com.rutabikersync.app.core.mesh.sos.MedicalSheetCrypto
import com.rutabikersync.app.core.mesh.sos.MedicalSheetPacket
import com.rutabikersync.app.core.mesh.sos.MedicalSheetStore
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground Service de Malla BLE y Audio PTT en Radiofrecuencia.
 * Conforme al Pliego de Arquitectura (Hito 1 y Hito 2):
 * - Ejecuta como connectedDevice | location
 * - PARTIAL_WAKE_LOCK con tag RBS:MeshRadioEngineLock
 * - Transmisión y escaneo de telemetría (HeartbeatFrame, 24B) y voz (VoicePacket, 80-100B)
 * - Soporte para Extended Advertising sin fragmentación SAR
 * - Contador bidireccional y métricas de Packet Loss Rate (PLR)
 * - PttAudioEngine integrado para captura, codificación Opus, cifrado ChaCha20 y reproducción
 */
@SuppressLint("MissingPermission")
class BleMeshService : Service() {

    companion object {
        private const val TAG = "BleMeshService"
        private const val NOTIFICATION_CHANNEL_ID = "rbs_mesh_channel"
        private const val NOTIFICATION_ID = 2026
        private const val WAKELOCK_TAG = "RBS:MeshRadioEngineLock"

        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000fe6b-0000-1000-8000-00805f9b34fb")

        const val ACTION_START = "com.rutabikersync.app.mesh.ACTION_START"
        const val ACTION_STOP = "com.rutabikersync.app.mesh.ACTION_STOP"
        const val ACTION_PTT_START = "com.rutabikersync.app.mesh.ACTION_PTT_START"
        const val ACTION_PTT_STOP = "com.rutabikersync.app.mesh.ACTION_PTT_STOP"
        const val ACTION_EMIT_MULTIHOP = "com.rutabikersync.app.mesh.ACTION_EMIT_MULTIHOP"
        const val ACTION_RESET_METRICS = "com.rutabikersync.app.mesh.ACTION_RESET_METRICS"
        const val ACTION_EMIT_MEDICAL_SHEET = "com.rutabikersync.app.mesh.ACTION_EMIT_MEDICAL_SHEET"
        const val ACTION_EMIT_SOS = "com.rutabikersync.app.mesh.ACTION_EMIT_SOS"
        const val ACTION_EMIT_ACK = "com.rutabikersync.app.mesh.ACTION_EMIT_ACK"
        const val ACTION_SET_RESCUE_CONTEXT = "com.rutabikersync.app.mesh.ACTION_SET_RESCUE_CONTEXT"
        const val ACTION_PURGE_R12 = "com.rutabikersync.app.mesh.ACTION_PURGE_R12"

        private val ALLOWED_ACTIONS = setOf(
            ACTION_START,
            ACTION_STOP,
            ACTION_PTT_START,
            ACTION_PTT_STOP,
            ACTION_EMIT_MULTIHOP,
            ACTION_RESET_METRICS,
            ACTION_EMIT_MEDICAL_SHEET,
            ACTION_EMIT_SOS,
            ACTION_EMIT_ACK,
            ACTION_SET_RESCUE_CONTEXT,
            ACTION_PURGE_R12
        )

        const val EXTRA_CLUB_NETWORK_ID = "extra_club_network_id"
        const val EXTRA_LOCAL_SHORT_ID = "extra_local_short_id"
        const val EXTRA_TTL = "extra_ttl"

        /** Clave de rescate simétrica por defecto para pruebas de convoy (256 bits). */
        val DEFAULT_DEMO_RESCUE_KEY = byteArrayOf(
            0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
            0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(),
            0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(), 0x00
        )

        /** Referencia a la instancia activa del servicio para acceso desde la UI. */
        @Volatile
        private var activeInstance: BleMeshService? = null

        /** Retorna la instancia activa del servicio BLE, o null si no está en ejecución. */
        fun getActiveInstance(): BleMeshService? = activeInstance
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val engine = BleMeshEngine(
        onTransmitWireBytes = { wireBytes ->
            heartbeatsSentCount.incrementAndGet()
            transmitBleFrame(wireBytes)
        },
        onPeerCountChanged = { count ->
            updateNotification(count)
        },
        onIsolatedModeChanged = { isIsolated ->
            Log.i(TAG, "Notificación de modo aislado: $isIsolated")
        }
    )

    var pttAudioEngine: PttAudioEngine? = null
        private set

    @Volatile
    private var foregroundStarted = false
    private var voiceRxUnsubscribe: (() -> Unit)? = null

    var relayEngine: MeshRelayEngine? = null
        private set

    /** Almacén volátil de fichas médicas descifradas (solo Líder/Sargento). */
    val medicalSheetStore = MedicalSheetStore()

    /**
     * Evento emitido cuando el nodo local recibe un [SosPacket] por la malla BLE.
     * La UI observa este StateFlow para mostrar [MeshSosReceptionDialog].
     */
    private val _incomingSosMeshEvent = MutableStateFlow<IncomingSosMeshEvent?>(null)
    val incomingSosMeshEvent: StateFlow<IncomingSosMeshEvent?> = _incomingSosMeshEvent.asStateFlow()

    /**
     * Datos del SOS entrante por malla: identidad del emisor, saltos y datos clínicos
     * (si el nodo local es Líder/Sargento y posee la clave de rescate).
     */
    data class IncomingSosMeshEvent(
        val senderShortId: UShort,
        val senderName: String,
        val hopCount: Int,
        val latitude: Double,
        val longitude: Double,
        val medicalData: MedicalRescueData?,
        val clubNetworkId: UInt,
        val sequenceNum: UShort
    )

    /** Evento emitido cuando el nodo recibe un [SosAckPacket] confirmando su SOS. */
    data class IncomingSosAckEvent(
        val ackerShortId: UShort,
        val originalSenderShortId: UShort,
        val originalSequenceNum: UShort,
        val ackTimestampEpochSec: Int
    )
    private val _incomingSosAckEvent = MutableStateFlow<IncomingSosAckEvent?>(null)
    val incomingSosAckEvent: StateFlow<IncomingSosAckEvent?> = _incomingSosAckEvent.asStateFlow()

    // Métricas direccionales por Peer (Hito 3 · Etapa 1)
    val directionalTracker = PeerDirectionalTracker()

    fun receivedFromPeer(id: UShort): Int = directionalTracker.getReceivedCount(id)
    fun expectedFromPeer(id: UShort): Int = directionalTracker.getExpectedCount(id)
    fun lostFromPeer(id: UShort): Int = directionalTracker.getLostCount(id)
    fun calculatePlr(id: UShort): Float = directionalTracker.calculatePlr(id)

    // Contadores bidireccionales y telemetría de radio BLE
    val heartbeatsSentCount = AtomicInteger(0)
    val heartbeatsReceivedCount = AtomicInteger(0)
    val voicePacketsSentCount = AtomicInteger(0)
    val voicePacketsReceivedCount = AtomicInteger(0)

    private val peerHeartbeatSequences = ConcurrentHashMap<Int, Int>()
    val totalHeartbeatsExpected = AtomicInteger(0)
    val totalHeartbeatsLost = AtomicInteger(0)

    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeAdvertisingSet: AdvertisingSet? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    engine.onBatteryLevelChanged(pct)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.i(TAG, "BleMeshService onCreate")
        acquireWakeLock()
        createNotificationChannel()
        initBluetooth()
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "Error registrando batteryReceiver: ${e.message}")
        }
        startMetricsReporter()
    }

    /**
     * Llama a startForeground() ANTES de cualquier operación larga. El crash
     * ForegroundServiceDidNotStartInTimeException ocurría porque ACTION_PTT_START/STOP
     * llegaban sin ACTION_START y nunca se promovía el servicio a foreground.
     */
    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = buildPersistentNotification(connectedCount = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        Log.i(TAG, "startForeground garantizado antes de procesar la acción PTT")
    }

    /**
     * Inicializa el motor PTT aunque el servicio no haya recibido ACTION_START, cableando
     * su salida al MeshRelayEngine activo del RideService (mismo transporte fragmentado
     * que el chat) y su entrada a las tramas recibidas por ese engine.
     */
    private fun ensurePttEngineInitialized() {
        if (pttAudioEngine != null) return
        val active = RideService.getActiveRelayEngine()
        val clubId = active?.clubNetworkId ?: 0x52425301u
        val shortId = active?.localShortId ?: 1u
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        pttAudioEngine = PttAudioEngine(
            scope = serviceScope,
            clubNetworkId = clubId,
            localShortId = shortId,
            audioFocusController = AndroidAudioFocusController(audioManager),
            onSendVoicePacket = { packet ->
                val engine = RideService.getActiveRelayEngine()
                val sent = engine?.emitVoicePacket(packet) == true
                voicePacketsSentCount.incrementAndGet()
                if (!sent) {
                    Log.w(TAG, "VoicePacketTx descartado: no hay relayEngine activo en RideService")
                }
            }
        )
        voiceRxUnsubscribe?.invoke()
        voiceRxUnsubscribe = active?.onVoiceReceived { packet ->
            pttAudioEngine?.onVoicePacketReceived(packet)
        }
        pttAudioEngine?.let { ptt ->
            com.rutabikersync.app.core.audio.SosToneHub.observePttSource(
                source = "hardware-ptt",
                active = ptt.pttState.map { it !is PttState.Idle },
                scope = serviceScope
            )
        }
        Log.i(TAG, "Foco de silencio SOS compartido con hardware PTT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        // Defensa en profundidad (T-04/B2-4): el servicio es no exportado, pero se validan
        // acciones conocidas para evitar comportamientos inesperados ante intents internos mal formados.
        if (action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Acción ignorada (no permitida): $action")
            return START_STICKY
        }
        if (action != ACTION_STOP) ensureForeground()
        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP recibido en BleMeshService")
                stopMeshService()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val clubId = intent?.getIntExtra(EXTRA_CLUB_NETWORK_ID, 0x52425301.toInt())?.toUInt() ?: 0x52425301u
                val shortIdInt = intent?.getIntExtra(EXTRA_LOCAL_SHORT_ID, -1) ?: -1
                val shortId = if (shortIdInt != -1) shortIdInt.toUShort() else (intent?.getShortExtra(EXTRA_LOCAL_SHORT_ID, 1)?.toUShort() ?: 1u)
                startMeshService(clubId, shortId)
            }
            ACTION_PTT_START -> {
                Log.i(TAG, "ACTION_PTT_START recibido")
                // Silenciar antes de iniciar la captura; ningún cambio en PttAudioEngine.
                com.rutabikersync.app.core.audio.SosToneHub.setPttActive("hardware-ptt", true)
                RideService.beginPttVoiceStream()
                ensurePttEngineInitialized()
                pttAudioEngine?.startTalking()
                serviceScope.launch {
                    delay(1_500L)
                    if (pttAudioEngine?.pttState?.value is PttState.Idle) {
                        com.rutabikersync.app.core.audio.SosToneHub.setPttActive("hardware-ptt", false)
                    }
                }
            }
            ACTION_PTT_STOP -> {
                Log.i(TAG, "ACTION_PTT_STOP recibido")
                pttAudioEngine?.stopTalking()
                com.rutabikersync.app.core.audio.SosToneHub.setPttActive("hardware-ptt", false)
                RideService.endPttVoiceStream()
                activeAdvertisingSet?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            bleAdvertiser?.stopAdvertisingSet(object : AdvertisingSetCallback() {})
                        } catch (_: Exception) {}
                    }
                    activeAdvertisingSet = null
                }
                // Emitir inmediatamente un Heartbeat para restaurar el anuncio normal de telemetría
                val hb = engine.emitLocalHeartbeat()
                heartbeatsSentCount.incrementAndGet()
                transmitBleFrame(hb.toByteArray())
            }
            ACTION_EMIT_MULTIHOP -> {
                val ttl = intent?.getIntExtra(EXTRA_TTL, 5) ?: 5
                Log.i(TAG, "ACTION_EMIT_MULTIHOP recibido (ttl=$ttl)")
                emitMultiHopPacket(ttl)
            }
            ACTION_RESET_METRICS -> {
                Log.i(TAG, "ACTION_RESET_METRICS recibido: reseteando contadores y tracker")
                resetAllMetrics()
            }
            ACTION_SET_RESCUE_CONTEXT -> {
                val auth = intent?.getBooleanExtra("extra_is_authorized", true) ?: true
                Log.i(TAG, "ACTION_SET_RESCUE_CONTEXT: auth=$auth")
                medicalSheetStore.setRescueContext(auth, if (auth) DEFAULT_DEMO_RESCUE_KEY else null)
            }
            ACTION_EMIT_MEDICAL_SHEET -> {
                val owner = relayEngine?.localShortId ?: 2u
                Log.i(TAG, "ACTION_EMIT_MEDICAL_SHEET para ownerShortId=$owner")
                val clinicalData = MedicalRescueData(
                    bloodType = "O+",
                    allergies = "Penicilina",
                    medication = "Ninguna",
                    conditions = "Ninguna",
                    insurancePolicy = "GMX-98231-M",
                    iceName = "Laura Esposa",
                    icePhone = "+525512345678"
                )
                emitLocalMedicalSheetViaMesh(
                    rescueKey = DEFAULT_DEMO_RESCUE_KEY,
                    rideId = UUID.fromString("12345678-1234-5678-1234-567812345678"),
                    clinicalData = clinicalData,
                    ownerShortId = owner,
                    seqNum = 1u
                )
            }
            ACTION_EMIT_SOS -> {
                Log.i(TAG, "ACTION_EMIT_SOS recibido: activando broadcaster SOS")
                emitLocalSosPacket(
                    latitude = 10.4064,
                    longitude = -67.2886,
                    incidentCode = 0x02,
                    bloodType = 1
                )
            }
            ACTION_EMIT_ACK -> {
                val targetShortId = intent?.getIntExtra("extra_target_short_id", 2)?.toUShort() ?: 2u
                Log.i(TAG, "ACTION_EMIT_ACK recibido para target=$targetShortId")
                val unsignedAck = SosAckPacket.create(
                    clubNetworkId = relayEngine?.clubNetworkId ?: 0x52425301u,
                    ackerShortId = relayEngine?.localShortId ?: 1u,
                    sequenceNum = 1u,
                    originalSenderShortId = targetShortId,
                    originalSequenceNum = 0u,
                    ackTimestampEpochSec = (System.currentTimeMillis() / 1000L).toInt(),
                    signature = ByteArray(64)
                )
                sendSosAckPacket(
                    com.rutabikersync.app.core.mesh.sos.SosSigningHub.signSosAck(unsignedAck)
                )
            }
            ACTION_PURGE_R12 -> {
                Log.i(TAG, "ACTION_PURGE_R12 recibido: purgando fichas médicas en RAM")
                medicalSheetStore.purgeAllOnRideClosed()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "BleMeshService onDestroy")
        activeInstance = null
        stopMeshService()
        releaseWakeLock()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMetricsReporter() {
        serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(10000L) // Reporte cada 10 segundos
                val peers = directionalTracker.getAllTrackedPeers()
                if (peers.isEmpty()) {
                    val hbLoss = calculateHeartbeatPlr()
                    val voicePlr = pttAudioEngine?.calculateVoicePlr() ?: 0f
                    Log.i(
                        TAG,
                        "METRICS: No peers tracked yet | HB Sent=${heartbeatsSentCount.get()}, Recv=${heartbeatsReceivedCount.get()}, LossRate=${"%.2f".format(hbLoss)}% | Voice Sent=${voicePacketsSentCount.get()}, Recv=${voicePacketsReceivedCount.get()}, PLR=${"%.2f".format(voicePlr)}%"
                    )
                } else {
                    for (peerId in peers) {
                        val recv = directionalTracker.getReceivedCount(peerId)
                        val exp = directionalTracker.getExpectedCount(peerId)
                        val lost = directionalTracker.getLostCount(peerId)
                        val plr = directionalTracker.calculatePlr(peerId)
                        Log.i(
                            TAG,
                            "METRICS: Peer#$peerId -> Local: Recv=$recv, Expected=$exp, Lost=$lost, PLR=${"%.2f".format(plr)}%"
                        )
                    }
                }
            }
        }
    }

    fun startMeshService(clubId: UInt, shortId: UShort) {
        ensureForeground()

        initBluetooth()
        val bootEpoch = getNextBootEpoch()
        engine.startMeshEngine(clubId, shortId, bootEpoch)

        // Inicializar motor de audio PTT con gestión de AudioFocus
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val audioFocusController = AndroidAudioFocusController(audioManager)

        pttAudioEngine?.release()
        pttAudioEngine = PttAudioEngine(
            scope = serviceScope,
            clubNetworkId = clubId,
            localShortId = shortId,
            audioFocusController = audioFocusController,
            onSendVoicePacket = { voicePacket ->
                val active = RideService.getActiveRelayEngine()
                val sent = active?.emitVoicePacket(voicePacket) == true
                voicePacketsSentCount.incrementAndGet()
                if (!sent) {
                    Log.w(TAG, "VoicePacketTx sin engine activo: fallback alAdvertising legado")
                    transmitBleFrame(voicePacket.toBinary())
                }
            }
        )
        voiceRxUnsubscribe?.invoke()
        voiceRxUnsubscribe = RideService.getActiveRelayEngine()?.onVoiceReceived { packet ->
            pttAudioEngine?.onVoicePacketReceived(packet)
        }
        pttAudioEngine?.let { ptt ->
            com.rutabikersync.app.core.audio.SosToneHub.observePttSource(
                source = "hardware-ptt",
                active = ptt.pttState.map { it !is PttState.Idle },
                scope = serviceScope
            )
        }

        // Monitorear estado de PTT para actualizar la notificación
        serviceScope.launch {
            pttAudioEngine?.pttState?.collect { state ->
                when (state) {
                    is PttState.Transmitting -> {
                        updateNotificationForPtt("🎙️ Transmitiendo voz PTT (Frame #${state.frameIndex})")
                    }
                    is PttState.Receiving -> {
                        updateNotificationForPtt("🔊 Recibiendo voz de Piloto #${state.senderShortId}")
                    }
                    is PttState.Idle -> {
                        updateNotification(engine.connectedPeersCount.value)
                    }
                }
            }
        }

        // Inicializar motor de flooding multi-salto (Hito 3) + integración Hito 4
        relayEngine = MeshRelayEngine(
            localShortId = shortId,
            clubNetworkId = clubId,
            deduplicationTable = engine.deduplicationTable,
            onTransmitRelay = { bytes -> transmitBleFrame(bytes) }
        ).also { relay ->
            // Conectar almacén de fichas médicas (Hito 4 · Etapa 3)
            relay.medicalSheetStore = medicalSheetStore
            relay.onMedicalSheetReceived = { _, _ ->
                // El MedicalSheetStore ya gestiona el almacenamiento internamente.
                // No se registra nada en logcat para evitar fuga de datos clínicos.
            }
            // Notificar a la UI cuando llega un SosPacket por malla (Hito 4 · Etapa 4)
            relay.onSosReceived = { sos ->
                com.rutabikersync.app.core.audio.SosToneHub.onRemoteSos(sos)
                val decrypted = medicalSheetStore.getSheet(sos.header.senderShortId)
                serviceScope.launch(Dispatchers.Main) {
                    _incomingSosMeshEvent.value = IncomingSosMeshEvent(
                        senderShortId = sos.header.senderShortId,
                        senderName = "Piloto #${sos.header.senderShortId.toInt()}",
                        hopCount = sos.hopCount.toInt() and 0xFF,
                        latitude = sos.latitude,
                        longitude = sos.longitude,
                        medicalData = decrypted?.data,
                        clubNetworkId = sos.header.clubNetworkId,
                        sequenceNum = sos.header.sequenceNum
                    )
                }
                // Puente BLE Mesh hacia Radar SOS HTTP (Fase 7.1.5.5)
                com.rutabikersync.app.core.mesh.sos.BleMeshSosBridge.onMeshSosReceived(
                    context = applicationContext,
                    sos = sos,
                    scope = serviceScope
                )
            }
            // Notificar a la UI cuando llega un SosAckPacket de vuelta (Hito 4 · Etapa 4)
            relay.onSosAckReceived = { ack ->
                com.rutabikersync.app.core.audio.SosToneHub.onAck(ack)
                serviceScope.launch(Dispatchers.Main) {
                    _incomingSosAckEvent.value = IncomingSosAckEvent(
                        ackerShortId = ack.ackerShortId,
                        originalSenderShortId = ack.originalSenderShortId,
                        originalSequenceNum = ack.originalSequenceNum,
                        ackTimestampEpochSec = ack.ackTimestampEpochSec
                    )
                }
            }
            // Notificar a la UI cuando llega un TextMessagePacket por malla (Hito 5 · Fase 7.0.2)
            relay.onTextMessageReceived = { textPacket, decryptedText ->
                if (decryptedText != null) {
                    serviceScope.launch(Dispatchers.Main) {
                        val senderName = "Piloto #${textPacket.header.senderShortId}"
                        com.rutabikersync.app.core.mesh.text.TacticalChatStore.addMessage(
                            com.rutabikersync.app.core.mesh.text.TacticalChatMessage(
                                senderShortId = textPacket.header.senderShortId,
                                senderName = senderName,
                                senderRole = "PILOTO",
                                targetShortId = textPacket.targetShortId,
                                text = decryptedText,
                                isOutgoing = false,
                                hopCount = textPacket.hopCount
                            )
                        )
                    }
                }
            }
        }

        startBleRadio()
        Log.i(TAG, "BleMeshService en ejecución: Club=0x${clubId.toString(16)}, ShortId=$shortId, BootEpoch=$bootEpoch")
    }

    private fun getNextBootEpoch(): Int {
        val prefs = getSharedPreferences("rbs_mesh_prefs", Context.MODE_PRIVATE)
        val currentEpoch = prefs.getInt("mesh_boot_epoch", 0)
        val nextEpoch = (currentEpoch + 1) and 0xFF
        prefs.edit().putInt("mesh_boot_epoch", nextEpoch).commit()
        Log.i(TAG, "Nuevo bootEpoch generado y persistido: $nextEpoch (anterior: $currentEpoch)")
        return nextEpoch
    }

    fun stopMeshService() {
        stopBleRadio()
        engine.stopMeshEngine()
        relayEngine?.resetMetrics()
        relayEngine = null
        voiceRxUnsubscribe?.invoke()
        voiceRxUnsubscribe = null
        pttAudioEngine?.release()
        pttAudioEngine = null
        foregroundStarted = false
        // R12: purgar fichas médicas y mensajes tácticos en RAM al detener el servicio
        medicalSheetStore.purgeAllOnRideClosed()
        com.rutabikersync.app.core.mesh.text.TacticalChatStore.purgeR12()
        _incomingSosMeshEvent.value = null
    }

    /**
     * Emite un mensaje de texto cifrado con ChaCha20-Poly1305 por la malla BLE (Hito 5 · Fase 7.0.2).
     */
    fun sendTextMessage(targetShortId: UShort, text: String, rideKey: ByteArray? = null): Boolean {
        val relay = relayEngine ?: return false
        val key = rideKey ?: relay.customRideSecretKey ?: DEFAULT_DEMO_RESCUE_KEY
        return try {
            val header = MeshHeader(
                clubNetworkId = relay.clubNetworkId,
                senderShortId = relay.localShortId,
                packetType = MeshHeader.TYPE_TEXT_MESSAGE,
                initTtl = 5,
                currentTtl = 5,
                sequenceNum = (System.currentTimeMillis() and 0xFFFF).toUShort()
            )
            val packet = com.rutabikersync.app.core.mesh.text.TextMessageCrypto.encrypt(
                key = key,
                plaintext = text,
                header = header,
                targetShortId = targetShortId
            )
            transmitBleFrame(packet.toByteArray())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al cifrar/emitir mensaje táctico: ${e.message}")
            false
        }
    }

    /**
     * Emite la ficha médica local cifrada por la malla BLE.
     *
     * Solo debe llamarse cuando el piloto se une a una rodada activa y ha autorizado
     * la compartición en la Fase 5. El ciphertext viaja cifrado con AES-256-GCM;
     * los nodos intermedios actúan como relays ciegos.
     *
     * @param rescueKey  Clave de rescate de 256 bits de la rodada (jamás se loguea).
     * @param rideId     UUID de la rodada activa.
     * @param clinicalData Datos clínicos del piloto local.
     * @param ownerShortId ShortId local (uint16).
     * @param seqNum     Número de secuencia para el MeshHeader.
     */
    fun emitLocalMedicalSheetViaMesh(
        rescueKey: ByteArray,
        rideId: UUID,
        clinicalData: MedicalRescueData,
        ownerShortId: UShort,
        seqNum: UShort
    ) {
        val relay = relayEngine ?: run {
            Log.w(TAG, "emitLocalMedicalSheetViaMesh: relayEngine no inicializado")
            return
        }
        val encrypted = try {
            MedicalSheetCrypto.encrypt(clinicalData, rescueKey)
        } catch (e: Exception) {
            Log.e(TAG, "Error cifrando ficha médica: ${e.javaClass.simpleName}")
            return
        }
        val packet = MedicalSheetPacket.create(
            clubNetworkId = relay.clubNetworkId,
            ownerShortId = ownerShortId,
            sequenceNum = seqNum,
            rideId = rideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext
        )
        serviceScope.launch(Dispatchers.Default) {
            transmitBleFrame(packet.toByteArray())
            Log.i(TAG, "MedicalSheetPacket emitido (${packet.toByteArray().size}B, ownerShortId=$ownerShortId)")
        }
    }

    /**
     * Emite una alerta SOS propia por la malla BLE de manera periódica (prioritaria).
     */
    fun emitLocalSosPacket(
        latitude: Double,
        longitude: Double,
        incidentCode: Byte = 0x02,
        bloodType: Byte = 1
    ) {
        val active = RideService.getActiveRelayEngine()
        if (active != null) {
            val accepted = com.rutabikersync.app.core.mesh.sos.SosMeshEmitter.startLocalBroadcast(
                relay = active,
                scope = serviceScope,
                latitude = latitude,
                longitude = longitude,
                incidentCode = incidentCode,
                bloodType = bloodType
            )
            if (accepted) com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(true)
            return
        }
        val relay = relayEngine ?: return
        val packet = com.rutabikersync.app.core.mesh.sos.SosMeshEmitter.createLocalPacket(
            relay = relay,
            latitude = latitude,
            longitude = longitude,
            incidentCode = incidentCode,
            bloodType = bloodType
        )
        relay.startSosBroadcaster(packet, serviceScope, intervalMs = 2000L) { bytes ->
            transmitBleFrame(bytes)
        }
        Log.i(TAG, "SosBroadcaster legacy activado para shortId=${relay.localShortId}")
    }

    /**
     * Detiene el broadcaster continuo de SOS (al recibir el ACK o resolver manualmente).
     */
    fun stopSosBroadcaster() {
        RideService.getActiveRelayEngine()?.stopSosBroadcaster()
        relayEngine?.stopSosBroadcaster()
        com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(false)
        Log.i(TAG, "SosBroadcaster detenido")
    }

    /**
     * Emite un [SosAckPacket] por la malla BLE.
     * El Líder/Sargento llama a este método al tocar "CONFIRMAR RECEPCIÓN" en el diálogo.
     */
    fun sendSosAckPacket(ack: SosAckPacket): Boolean {
        if (RideService.getActiveRelayEngine() != null) {
            return RideService.dispatchSosAckPacket(ack)
        }
        if (relayEngine == null) return false
        serviceScope.launch(Dispatchers.Default) {
            transmitBleFrame(ack.toByteArray())
            Log.i(TAG, "SosAckPacket en emisión para originalSender=${ack.originalSenderShortId}")
        }
        com.rutabikersync.app.core.audio.SosToneHub.onAck(ack)
        return true
    }

    /**
     * Descarta el evento SOS activo en el StateFlow tras confirmarlo en la UI.
     * Permite que el diálogo vuelva a aparecer si llega un nuevo SOS.
     */
    fun clearIncomingSosMeshEvent() {
        _incomingSosMeshEvent.value = null
    }

    fun resetAllMetrics() {
        directionalTracker.reset()
        totalHeartbeatsExpected.set(0)
        totalHeartbeatsLost.set(0)
        heartbeatsReceivedCount.set(0)
        heartbeatsSentCount.set(0)
        voicePacketsReceivedCount.set(0)
        voicePacketsSentCount.set(0)
        peerHeartbeatSequences.clear()
        relayEngine?.resetMetrics()
        Log.i(TAG, "Todas las métricas de radio BLE y PLR han sido reseteadas a 0")
    }

    fun emitMultiHopPacket(ttl: Int = 5) {
        serviceScope.launch(Dispatchers.Default) {
            val hb = engine.emitLocalHeartbeat(initTtl = ttl, currentTtl = ttl)
            Log.i("MeshRelayEngine", "Emitted packet with currentTtl=$ttl, seq=${hb.header.sequenceNum}, epoch=${hb.bootEpoch}")
            heartbeatsSentCount.incrementAndGet()
            relayEngine?.deduplicationTable?.shouldProcessAndMark(hb.header.senderShortId, hb.header.sequenceNum)
            transmitBleFrame(hb.toByteArray())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "PARTIAL_WAKE_LOCK adquirido ($WAKELOCK_TAG)")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "PARTIAL_WAKE_LOCK liberado")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando wakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun initBluetooth() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothAdapter != null) {
            val extAdv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) bluetoothAdapter?.isLeExtendedAdvertisingSupported else false
            Log.i(TAG, "Bluetooth inicializado. ExtendedAdv=$extAdv, BLE5.0=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}")
        }
    }

    private fun transmitBleFrame(payload: ByteArray) {
        if (!hasBlePermissions()) {
            Log.w(TAG, "transmitBleFrame abortado: faltan permisos BLE")
            return
        }
        if (bleAdvertiser == null) {
            initBluetooth()
            if (bleAdvertiser == null) {
                Log.w(TAG, "transmitBleFrame abortado: BluetoothLeAdvertiser es null (¿Bluetooth apagado?)")
                return
            }
        }

        try {
            val pUuid = ParcelUuid(MESH_SERVICE_UUID)

            // Si el hardware soporta Extended Advertising (BLE 5.0) y el paquete es > 24B, usar Extended Advertising
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bluetoothAdapter?.isLeExtendedAdvertisingSupported == true && payload.size > 24) {
                val data = AdvertiseData.Builder()
                    .addServiceData(pUuid, payload)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()

                val currentSet = activeAdvertisingSet
                if (currentSet != null) {
                    try {
                        currentSet.setAdvertisingData(data)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Recreando AdvertisingSet tras error: ${e.message}")
                    }
                }

                val parameters = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .setConnectable(false)
                    .build()

                bleAdvertiser?.startAdvertisingSet(parameters, data, null, null, null, object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            activeAdvertisingSet = advertisingSet
                            Log.i(TAG, "Extended Advertising started (payload=${payload.size}B, status=$status)")
                        } else {
                            Log.w(TAG, "Extended Advertising start error: status=$status")
                        }
                    }
                })
                return
            }

            // Anuncio Legacy Standard (Heartbeats de 24B)
            activeAdvertiseCallback?.let { old ->
                runCatching { bleAdvertiser?.stopAdvertising(old) }
            }

            val isSurvival = engine.isLowBatterySurvivalMode.value
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(if (isSurvival) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .addServiceData(pUuid, payload)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.i(TAG, "Advertising started with serviceUUID=$MESH_SERVICE_UUID (payload=${payload.size}B)")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "Advertise startFailure: $errorCode")
                }
            }
            activeAdvertiseCallback = callback
            bleAdvertiser?.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException al anunciar: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Error transmitiendo anuncio BLE: ${e.message}")
        }
    }

    private fun startBleRadio() {
        if (!hasBlePermissions()) {
            Log.w(TAG, "startBleRadio abortado: faltan permisos BLE")
            return
        }
        if (bleScanner == null) {
            initBluetooth()
            if (bleScanner == null) {
                Log.w(TAG, "startBleRadio abortado: BluetoothLeScanner es null (¿Bluetooth apagado?)")
                return
            }
        }

        try {
            val filter = ScanFilter.Builder()
                .setServiceData(ParcelUuid(MESH_SERVICE_UUID), null)
                .build()

            val scanMode = if (engine.isLowBatterySurvivalMode.value) {
                ScanSettings.SCAN_MODE_LOW_POWER
            } else {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }

            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(scanMode)
                .setReportDelay(0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(false)
            }
            val settings = settingsBuilder.build()

            Log.i(TAG, "Scan started with filter=serviceDataUuid:$MESH_SERVICE_UUID")
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException al iniciar scan: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Error iniciando scan: ${e.message}")
        }
    }

    private fun stopBleRadio() {
        try {
            activeAdvertiseCallback?.let {
                bleAdvertiser?.stopAdvertising(it)
                activeAdvertiseCallback = null
            }
            activeAdvertisingSet?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bleAdvertiser?.stopAdvertisingSet(object : AdvertisingSetCallback() {})
                }
                activeAdvertisingSet = null
            }
            if (isScanning && bleScanner != null && hasBlePermissions()) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo radio BLE: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Fallo ScanCallback BLE: $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(MESH_SERVICE_UUID)) ?: return
        if (serviceData.size < MeshHeader.HEADER_SIZE_BYTES) return

        val header = try {
            MeshHeader.fromByteArray(serviceData, 0)
        } catch (e: Exception) {
            Log.w("MeshRelayEngine", "DroppedMalformed: cabecera corrupta (${e.message})")
            return
        }

        val relay = relayEngine
        if (relay != null) {
            when (val decision = relay.processIncomingPacket(serviceData, header.senderShortId)) {
                is MeshRelayEngine.RelayDecision.Relayed -> {
                    Log.i(
                        "MeshRelayEngine",
                        "Received packet from shortId=${header.senderShortId} with currentTtl=${header.currentTtl}, Relayed with newTtl=${decision.newTtl}, jitterMs=${decision.jitterMs}, highPriority=${decision.isHighPriority}"
                    )
                    serviceScope.launch(Dispatchers.Default) {
                        if (decision.jitterMs > 0) delay(decision.jitterMs)
                        transmitBleFrame(decision.relayedBytes)
                    }
                    processLocalPacket(header, serviceData, result.rssi)
                }
                is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted -> {
                    Log.i(
                        "MeshRelayEngine",
                        "Received packet from shortId=${header.senderShortId} with currentTtl=${header.currentTtl}, ConsumedLocallyTtlExhausted(highPriority=${decision.isHighPriority})"
                    )
                    processLocalPacket(header, serviceData, result.rssi)
                }
                is MeshRelayEngine.RelayDecision.DroppedDuplicate -> {
                    Log.i(
                        "MeshRelayEngine",
                        "Received packet from shortId=${header.senderShortId} with currentTtl=${header.currentTtl}, DroppedDuplicate"
                    )
                }
                is MeshRelayEngine.RelayDecision.DroppedOwnPacket -> {
                    Log.d("MeshRelayEngine", "DroppedOwnPacket from shortId=${header.senderShortId}")
                }
                is MeshRelayEngine.RelayDecision.DroppedWrongNetwork -> {
                    Log.d("MeshRelayEngine", "DroppedWrongNetwork: 0x${header.clubNetworkId.toString(16)}")
                }
                is MeshRelayEngine.RelayDecision.DroppedMalformed -> {
                    Log.w("MeshRelayEngine", "DroppedMalformed: ${decision.reason}")
                }
                is MeshRelayEngine.RelayDecision.DroppedInvalidSignature -> {
                    Log.w("MeshRelayEngine", "DroppedInvalidSignature: ${decision.reason}")
                }
                is MeshRelayEngine.RelayDecision.DroppedHopLimitExceeded -> {
                    Log.w("MeshRelayEngine", "DroppedHopLimitExceeded: hops=${decision.hopCount} >= 5")
                }
            }
        } else {
            processLocalPacket(header, serviceData, result.rssi)
        }
    }

    private fun processLocalPacket(header: MeshHeader, serviceData: ByteArray, rssi: Int) {
        when (header.packetType) {
            MeshHeader.TYPE_HEARTBEAT -> {
                heartbeatsReceivedCount.incrementAndGet()
                val hbFrame = try {
                    HeartbeatFrame.fromByteArray(serviceData)
                } catch (_: Exception) {
                    null
                }
                val epoch = hbFrame?.bootEpoch ?: 0
                directionalTracker.recordPacket(header.senderShortId, header.sequenceNum.toInt(), epoch)
                trackHeartbeatSequence(header.senderShortId.toInt(), header.sequenceNum.toInt())
                Log.i(TAG, "onScanResult peer discovered: rawBytes=${serviceData.size}, rssi=$rssi, epoch=$epoch")
                engine.onRawPacketReceived(serviceData, rssi)
            }
            MeshHeader.TYPE_PTT_VOICE -> {
                voicePacketsReceivedCount.incrementAndGet()
                try {
                    val voicePacket = VoicePacket.fromBinary(serviceData)
                    Log.i(
                        TAG,
                        "VoicePacket received from shortId=${header.senderShortId}, frameIndex=${voicePacket.frameIndex}, last=${voicePacket.isLastFrame}, bytes=${serviceData.size}"
                    )
                    pttAudioEngine?.onVoicePacketReceived(voicePacket)
                } catch (e: Exception) {
                    Log.w(TAG, "Error deserializando VoicePacket: ${e.message}")
                }
            }
            MeshHeader.TYPE_MEDICAL_SHEET -> {
                // El relay ciego ya fue procesado por MeshRelayEngine.
                // processLocalPacket solo registra la recepción a nivel informativo (sin datos clínicos).
                Log.i(TAG, "MedicalSheetPacket recibido desde shortId=${header.senderShortId} (${serviceData.size}B)")
            }
            MeshHeader.TYPE_SOS -> {
                // El SosPacket fue procesado por MeshRelayEngine (que llama onSosReceived).
                // processLocalPacket registra sin revelar datos sensibles.
                Log.i(TAG, "SosPacket procesado desde shortId=${header.senderShortId} (${serviceData.size}B)")
            }
            MeshHeader.TYPE_SOS_ACK -> {
                // SosAckPacket: el relay ya lo encaminó; aquí solo registramos.
                Log.i(TAG, "SosAckPacket procesado desde shortId=${header.senderShortId} (${serviceData.size}B)")
            }
            else -> {
                Log.d(TAG, "Paquete con packetType=${header.packetType} recibido (${serviceData.size}B)")
            }
        }
    }

    private fun trackHeartbeatSequence(senderId: Int, seqNum: Int) {
        val lastSeq = peerHeartbeatSequences.put(senderId, seqNum)
        if (lastSeq != null) {
            val diff = (seqNum - lastSeq) and 0xFFFF
            if (diff in 2..50) {
                val lost = diff - 1
                totalHeartbeatsLost.addAndGet(lost)
                totalHeartbeatsExpected.addAndGet(diff)
            } else if (diff == 1) {
                totalHeartbeatsExpected.incrementAndGet()
            } else if (diff == 0) {
                // Mismo anuncio recibido por escaneo continuo sin pérdida
            } else {
                // diff > 50 indica reinicio de secuencia del peer
                totalHeartbeatsExpected.incrementAndGet()
            }
        } else {
            totalHeartbeatsExpected.incrementAndGet()
        }
    }

    fun calculateHeartbeatPlr(): Float {
        val exp = totalHeartbeatsExpected.get()
        if (exp == 0) return 0f
        val lost = totalHeartbeatsLost.get()
        return (lost.toFloat() / exp.toFloat()) * 100f
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ruta Biker Sync Convoy Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Telemetría y canal de radio mesh BLE off-grid"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(connectedCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalRiders = if (connectedCount == 0) 1 else connectedCount + 1
        val titleText = if (totalRiders == 1) "🏍️ Convoy Mesh: 1 piloto enlazado" else "🏍️ Convoy Mesh: $totalRiders pilotos enlazados"

        val contentText = if (connectedCount == 0) {
            "Escaneando convoy cercano en radiofrecuencia..."
        } else {
            "$connectedCount compañero(s) en radiofrecuencia (sin internet)"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(connectedCount: Int) {
        val notification = buildPersistentNotification(connectedCount)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationForPtt(pttStatusText: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalRiders = if (engine.connectedPeersCount.value == 0) 1 else engine.connectedPeersCount.value + 1
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("🏍️ Convoy Mesh: $totalRiders pilotos enlazados")
            .setContentText(pttStatusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            return scan && adv
        }
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleMeshService = this@BleMeshService
    }
}
