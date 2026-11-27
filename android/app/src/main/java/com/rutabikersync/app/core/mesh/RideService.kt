package com.rutabikersync.app.core.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.MainActivity
import com.rutabikersync.app.feature.sos.EmergencyLockScreenActivity
import com.rutabikersync.app.feature.sos.FallDetector
import com.rutabikersync.app.feature.sos.SosCountdownManager
import com.rutabikersync.app.feature.sos.SosRepository
import com.rutabikersync.app.feature.sos.SosTriggerType
import com.rutabikersync.app.feature.vault.VaultRepository
import com.rutabikersync.app.core.announcer.UniqueAnnouncer
import com.rutabikersync.app.core.audio.SosToneHub
import com.rutabikersync.app.core.power.BatteryAwareness
import com.rutabikersync.app.core.power.ManufacturerBatteryGuide
import com.rutabikersync.app.core.power.PowerProfile
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.GattHeartbeatClient
import com.rutabikersync.app.core.transport.GattHeartbeatServer
import com.rutabikersync.app.core.transport.GattVoiceTransport
import com.rutabikersync.app.core.transport.HeartbeatGattRoutingTransport
import java.security.SecureRandom
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import com.rutabikersync.app.core.transport.SimulatedBleTransport
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (`foregroundServiceType="location|connectedDevice"`) para la Rodada Activa
 * (Fase 3 — Bloque 3.2, DESIGN.md §§2–3, RULES.md R6.1 y R6.5):
 *
 * Decisiones confirmadas:
 * 1. Usa `BleAdvTransport` real por defecto; incluye interruptor de desarrollo (`forceSimulatedTransport`)
 *    y fallback automático a `SimulatedBleTransport` cuando el hardware BLE no está disponible (laboratorio/JVM).
 * 2. La notificación persistente muestra en tiempo real el número de riders conectados en la mesh.
 * 3. La acción "Detener rodada" desde la notificación exige confirmación dentro de una ventana de 3 segundos
 *    (`STOP_CONFIRM_WINDOW_MS = 3_000L`) para evitar cierres accidentales con guantes.
 */
enum class RideLifecycleState {
    IDLE,
    RUNNING,
    CONFIRMING_STOP,
    STOPPED
}

data class RideNotificationSnapshot(
    val title: String,
    val contentText: String,
    val connectedRidersCount: Int,
    val totalParticipantsCount: Int,
    val transportModeLabel: String,
    val isUsingSimulatedTransport: Boolean,
    val isSosActive: Boolean,
    val isConfirmingStop: Boolean,
    val stopConfirmationRemainingMs: Long
)

data class BluetoothConnectedDevicesSnapshot(
    val connectedDevicesCount: Int = 0,
    val hasOtherConnectedDevices: Boolean = false,
    val isAudioDeviceConnected: Boolean = false,
    val deviceNames: List<String> = emptyList(),
    val summary: String = "Ninguno"
)

data class RideSessionState(
    val rideId: String? = null,
    val lifecycleState: RideLifecycleState = RideLifecycleState.IDLE,
    val startedAtEpochMs: Long = 0L,
    val localShortId: UShort = 1u,
    val expectedPeerShortIds: List<Int> = emptyList(),
    val transportName: String = "Idle",
    val forcedBleMode: String? = null,
    val f3AdvertisingBurstDurationMs: Int = BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS,
    val f3AdvertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST,
    val f3AdvertisingSetsStarted: Int = 0,
    val f3AdvertisingSetsStopped: Int = 0,
    val f3LastMeasuredTxPowerDbm: Int? = null,
    val f3AdvertisingBurstsStarted: Int = 0,
    val f3AdvertisingBurstsSuccess: Int = 0,
    val f3AdvertisingFailuresCount: Int = 0,
    val f3LastAdvertisingFailure: String? = null,
    val f3ScanStartsCount: Int = 0,
    val f3ScanFailuresCount: Int = 0,
    val f3LastScanFailure: String? = null,
    val f3LogicalHeartbeatsEmitted: Int = 0,
    val connectedBluetoothDevicesCount: Int = 0,
    val hasOtherConnectedBluetoothDevices: Boolean = false,
    val connectedBluetoothAudio: Boolean = false,
    val connectedBluetoothDeviceNames: List<String> = emptyList(),
    val isUsingSimulatedTransport: Boolean = false,
    val connectedRidersCount: Int = 0,
    val lostRidersCount: Int = 0,
    val isSosActive: Boolean = false,
    val stopRequestedAtEpochMs: Long? = null,
    val voiceClipsPurgedOnClose: Boolean = false,
    val emergencyAccessRevokedOnClose: Boolean = false,
    val ttsShutdownOnClose: Boolean = false,
    val powerProfile: PowerProfile = PowerProfile.NORMAL,
    val heartbeatIntervalMs: Long = PowerProfile.NORMAL.heartbeatIntervalMs,
    val mapUpdateCadenceMs: Long = PowerProfile.NORMAL.mapUpdateCadenceMs,
    val isMapFrozen: Boolean = false,
    val lastBatteryTtsAlert: String? = null,
    val lastStartError: String? = null,
    val relayEnabled: Boolean = true,
    val txRxRole: BleAdvTransport.TxRxRole = BleAdvTransport.TxRxRole.BOTH,
    val unfilteredScanDiagnostic: Boolean = false,
    val notification: RideNotificationSnapshot = RideNotificationSnapshot(
        title = "Ruta Biker Sync",
        contentText = "Sin rodada activa",
        connectedRidersCount = 0,
        totalParticipantsCount = 0,
        transportModeLabel = "Inactivo",
        isUsingSimulatedTransport = false,
        isSosActive = false,
        isConfirmingStop = false,
        stopConfirmationRemainingMs = 0L
    )
)

/**
 * Controlador desacoplado de Android Framework para que el ciclo de vida completo de `RideService`
 * (`startRide` -> `MeshRelayEngine` -> notificación en tiempo real -> confirmación de 3 s -> `stopAll`)
 * pueda verificarse de forma determinista tanto en JVM como dentro del `Service` real de Android.
 */
class RideSessionController(
    private val scope: CoroutineScope,
    private val bleTransportFactory: (() -> BleAdvTransport?)? = null,
    private val gattVoiceTransportFactory: ((UShort) -> GattVoiceTransport?)? = null,
    private val gattHeartbeatServerFactory: ((UShort) -> GattHeartbeatServer?)? = null,
    private val gattHeartbeatClientFactory: ((UShort, Set<UShort>) -> GattHeartbeatClient?)? = null,
    private val simulatedMedium: SimulatedBleMedium = SimulatedBleMedium.defaultMedium,
    private val onNotificationUpdated: ((RideNotificationSnapshot) -> Unit)? = null,
    private val bluetoothDevicesProvider: (() -> BluetoothConnectedDevicesSnapshot)? = null,
    /**
     * En producción (RideService) se activa para no publicar jamás la coordenada por
     * defecto 19.4326,-99.1332 antes de tener GPS/caché real. Los tests JVM conservan
     * el valor por defecto `false` para no alterar sus fixtures de telemetry.
     */
    private val requireValidPositionForHeartbeat: Boolean = false
) {
    companion object {
        const val STOP_CONFIRM_WINDOW_MS: Long = 3_000L
        const val HEARTBEAT_INTERVAL_MS: Long = MeshRelayEngine.HEARTBEAT_INTERVAL_MS
        val DEFAULT_TEST_RIDE_KEY: ByteArray = "rbs-phase3-default-ride-key-32b!".toByteArray(Charsets.UTF_8)
    }

    private val _state = MutableStateFlow(RideSessionState())
    val state: StateFlow<RideSessionState> = _state.asStateFlow()

    var relayEngine: MeshRelayEngine? = null
        private set

    /** Stream GATT de voz: convive con el advertising de malla, no lo sustituye. */
    var gattVoiceTransport: GattVoiceTransport? = null
        private set

    /** Servidor GATT de heartbeats (Opción B): sustituye la ráfaga de posición por advertising. */
    var gattHeartbeatServer: GattHeartbeatServer? = null
        private set

    /** Cliente GATT de heartbeats: se conecta a los peers con shortId mayor. */
    var gattHeartbeatClient: GattHeartbeatClient? = null
        private set

    var lastPacketsCsv: String? = null
        private set

    var uniqueAnnouncer: UniqueAnnouncer? = null
        private set

    var peerHealthMonitor: PeerHealthMonitor? = null
        private set

    var heartbeatScheduler: HeartbeatScheduler? = null
        private set

    var meshOdometer: com.rutabikersync.app.core.mesh.odometer.MeshOdometer? = null
        private set

    var gamificationDao: com.rutabikersync.app.core.gamification.PendingGamificationEventDao? = null

    /** Fase 2 Garage: suma automáticamente los km recorridos en rodada real al odómetro. */
    var onRideDistanceKmRecorded: ((Int) -> Unit)? = null
    /** Fase 6 Gamificación: registra kilometraje real acumulado de la rodada en el perfil local. */
    var onRideGamificationKmRecorded: ((Double) -> Unit)? = null

    val batteryAwareness: BatteryAwareness = BatteryAwareness()
    val manufacturerBatteryGuide: ManufacturerBatteryGuide = ManufacturerBatteryGuide()

    private var activeTransport: Transport? = null
    private var voiceTransportObserveJob: Job? = null
    private var advPeerAddressCallback: ((address: String, senderShortId: UShort?) -> Unit)? = null
    private var heartbeatJob: Job? = null
    private var peerObserveJob: Job? = null
    private var autoFallbackJob: Job? = null
    private var metricsObserveJob: Job? = null
    private var stopConfirmTimeoutJob: Job? = null

    private val _metrics = MutableStateFlow<MeshRelayEngine.MeshFieldReport?>(null)
    val metrics: StateFlow<MeshRelayEngine.MeshFieldReport?> = _metrics.asStateFlow()

    @Volatile
    private var localLatitude: Double = 19.4326
    @Volatile
    private var localLongitude: Double = -99.1332
    @Volatile
    private var hasValidLocalPosition: Boolean = false
    @Volatile
    private var localPositionObservedAtEpochMs: Long = 0L
    @Volatile
    private var localSpeedKmh: Int = 45
    @Volatile
    private var localBearingDegrees: Float = 90f
    @Volatile
    private var localBatteryPercent: Int = 85

    suspend fun startRide(
        rideId: String,
        localShortId: UShort = 1u,
        rideSecretKey: ByteArray = DEFAULT_TEST_RIDE_KEY,
        forceSimulatedTransport: Boolean = false,
        bleModeOverride: BleAdvTransport.TransportModeOverride? = null,
        advertisingBurstDurationMs: Int = BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS,
        expectedPeerShortIds: Set<UShort> = emptySet(),
        enablePeriodicHeartbeatLoop: Boolean = true,
        relayEnabled: Boolean = true,
        txRxRole: BleAdvTransport.TxRxRole = BleAdvTransport.TxRxRole.BOTH,
        unfilteredScanDiagnostic: Boolean = false,
        advertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST,
        nowEpochMs: Long = System.currentTimeMillis()
    ): RideSessionState {
        stopAllInternal(resetToIdle = false)

        // P0: fail-closed. standardRelease nunca debe caer en una clave fija ni en
        // una clave aleatoria local (ninguna de las dos permite autenticar al club).
        require(rideSecretKey.size == 32) { "SecurityViolation: rideSecretKey debe tener 32 bytes" }
        if (!BuildConfig.ALLOW_DEFAULT_TEST_RIDE_KEY && rideSecretKey.contentEquals(DEFAULT_TEST_RIDE_KEY)) {
            error(
                "SecurityViolation: rideSecretKey fija por defecto prohibida en standardRelease. " +
                    "La clave debe provenir del manifiesto firmado por el backend."
            )
        }
        val effectiveRideSecretKey = rideSecretKey

        val (selectedTransport, isSimulated) = resolveTransport(
            localShortId = localShortId,
            forceSimulated = forceSimulatedTransport,
            bleModeOverride = bleModeOverride,
            advertisingBurstDurationMs = advertisingBurstDurationMs,
            txRxRole = txRxRole,
            unfilteredScanDiagnostic = unfilteredScanDiagnostic,
            advertisingDeliveryMode = advertisingDeliveryMode
        )
        activeTransport = selectedTransport

        val manifestPeers = com.rutabikersync.app.core.crypto.RideManifestKeyProvider.cached(rideId)
            ?.authorizedShortIds
            .orEmpty()
        val effectiveExpectedPeers = (if (expectedPeerShortIds.isNotEmpty()) expectedPeerShortIds else manifestPeers)
            .filter { it != localShortId }
            .toSet()
        val heartbeatPlan = GattHeartbeatClient.planFor(localShortId, effectiveExpectedPeers)

        // Opción B: el heartbeat sale por GATT; SOS/eventos/discovery siguen en BleAdvTransport.
        // Rol por shortId: el menor es Cliente y el mayor Servidor en cada par.
        val heartbeatServer = if (!isSimulated && heartbeatPlan.isServer) {
            gattHeartbeatServerFactory?.invoke(localShortId)
        } else {
            null
        }
        if (heartbeatServer != null) {
            heartbeatServer.start()
            gattHeartbeatServer = heartbeatServer
        }
        val heartbeatClient = if (!isSimulated && heartbeatPlan.isClient) {
            gattHeartbeatClientFactory?.invoke(localShortId, effectiveExpectedPeers)
        } else {
            null
        }
        val engineTransport = if (heartbeatServer != null || heartbeatClient != null) {
            HeartbeatGattRoutingTransport(selectedTransport, heartbeatServer, heartbeatClient)
        } else {
            selectedTransport
        }

        val engine = MeshRelayEngine(
            localShortId = localShortId,
            transport = engineTransport,
            rideSecretKey = effectiveRideSecretKey,
            relayEnabled = relayEnabled,
            relayScope = scope,
            // Fase 2: el suelo del timeout sigue al intervalo real del scheduler, que se
            // crea más abajo. El lambda se evalúa tarde, así que no hay orden de init frágil.
            heartbeatIntervalProvider = {
                heartbeatScheduler?.currentIntervalMs ?: HEARTBEAT_INTERVAL_MS
            },
            requireAuthorization = !isSimulated
        )
        // P0 cierre final: el manifiesto verificado es la única fuente de pertenencia
        // al convoy Y de las claves públicas SOS. Sin resolver, SOS/ACK se descartan.
        com.rutabikersync.app.core.crypto.RideManifestKeyProvider.cached(rideId)?.let { manifest ->
            engine.rideAuthorization = RideMeshAuthorization(manifest)
            engine.sosPublicKeyResolver = manifest.sosPublicKeyResolver
            engine.sosCrypto = com.rutabikersync.app.core.mesh.sos.SosSigningHub.verifier
        }
        // Hito 7.0.2: conectar la recepción de texto al store del chat táctico.
        engine.onTextMessageReceived = { textPacket, decryptedText ->
            if (decryptedText != null) {
                com.rutabikersync.app.core.mesh.text.TacticalChatStore.addMessage(
                    com.rutabikersync.app.core.mesh.text.TacticalChatMessage(
                        senderShortId = textPacket.header.senderShortId,
                        senderName = "Piloto #${textPacket.header.senderShortId}",
                        senderRole = "PILOTO",
                        targetShortId = textPacket.targetShortId,
                        text = decryptedText,
                        isOutgoing = false,
                        hopCount = textPacket.hopCount
                    )
                )
            }
        }
        // Todo SOS recibido por este engine activo alimenta su alarma local, no un servicio legacy.
        engine.onSosReceived = { sos ->
            com.rutabikersync.app.core.audio.SosToneHub.onRemoteSos(sos)
        }
        engine.onSosAckObserved = { ack ->
            com.rutabikersync.app.core.audio.SosToneHub.onAck(ack)
            if (ack.originalSenderShortId == localShortId) {
                scope.launch { triggerSos(active = false) }
            }
        }
        engine.onConvoySignalReceived = { incoming ->
            android.util.Log.i(
                "RideService",
                "ConvoySignalRx recibido por BLE Mesh: from=${incoming.senderShortId} kind=${incoming.signalKind.name}"
            )
            // 1. Tono audible en el casco inmediatamente en Foreground Service
            com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playSignal(incoming.signalKind)
            // 2. Notificar al canal estático de RideService para la UI
            RideService.notifyIncomingConvoySignal(incoming)
        }
        engine.onRideLifecycleEventReceived = { incoming ->
            android.util.Log.i(
                "RideService",
                "RideLifecycleEventRx recibido por BLE Mesh: from=${incoming.senderShortId} event=${incoming.event.name}"
            )
            // 1. Tono audible diferenciado en el casco inmediatamente
            when (incoming.event) {
                MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(false)
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_BEEP,
                        pulseCount = 2,
                        pulseDurationMs = 150L,
                        gapMs = 80L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_ACK,
                        pulseCount = 1,
                        pulseDurationMs = 400L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.START_RETURN -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(true)
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_BEEP2,
                        pulseCount = 3,
                        pulseDurationMs = 120L,
                        gapMs = 80L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_PROMPT,
                        pulseCount = 2,
                        pulseDurationMs = 200L,
                        gapMs = 100L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.SAFE_HOME -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_ACK,
                        pulseCount = 2,
                        pulseDurationMs = 250L,
                        gapMs = 100L
                    )
                }
            }
            // 2. Notificar al canal estático de RideService para la UI
            RideService.notifyIncomingRideLifecycleEvent(incoming)
        }
        relayEngine = engine
        // Reenvío autenticado: reutiliza el Transport activo de la rodada. Los bytes ya vienen
        // validados y con TTL decrementado por el engine; aquí no se fabrican ni alteran tramas.
        engine.onAuthenticatedRelayTransmit = { bytes ->
            // Los SOS/ACK ya llegan dentro del sobre sensible: priorizar por KIND sin
            // volver a abrir el ciphertext ni interpretar el magic como MeshHeader.
            val priority = when (SensitiveFrameEnvelope.kindOf(bytes)) {
                SensitiveFrameEnvelope.KIND_SOS,
                SensitiveFrameEnvelope.KIND_SOS_ACK -> Transport.Priority.SOS
                SensitiveFrameEnvelope.KIND_MESH_PACKET -> Transport.Priority.PEER_EVENT
                SensitiveFrameEnvelope.KIND_HEARTBEAT -> Transport.Priority.POSITION
                else -> runCatching {
                    when (MeshHeader.fromByteArray(bytes).packetType) {
                        MeshHeader.TYPE_SOS,
                        MeshHeader.TYPE_SOS_ACK,
                        MeshHeader.TYPE_MEDICAL_SHEET -> Transport.Priority.SOS
                        MeshHeader.TYPE_PTT_VOICE -> Transport.Priority.VOICE
                        MeshHeader.TYPE_HEARTBEAT -> Transport.Priority.POSITION
                        else -> Transport.Priority.STATUS
                    }
                }.getOrDefault(Transport.Priority.STATUS)
            }
            scope.launch {
                runCatching { engineTransport.send(bytes, priority) }
                    .onFailure { Log.w("RideService", "RelayTx falló: ${it.message}") }
            }
        }
        for (peerId in effectiveExpectedPeers) {
            engine.registerExpectedPeer(shortId = peerId, nowEpochMs = nowEpochMs, initialExpectedBeats = 0)
        }

        // Voz por GATT: canal paralelo al advertising. Se configura ANTES de engine.start()
        // para que la dirección del primer heartbeat discovered ya pueda abrir el enlace.
        if (!isSimulated) {
            val gatt = gattVoiceTransportFactory?.invoke(localShortId)
            if (gatt != null) {
                gattVoiceTransport = gatt
                gatt.setAllowedPeerShortIds(effectiveExpectedPeers)
                engine.setVoiceStreamTransport(gatt)
                (selectedTransport as? BleAdvTransport)?.let { adv ->
                    val addressCallback: (address: String, senderShortId: UShort?) -> Unit = { address, shortId ->
                        val suffix = if (address.length >= 5 && address.contains(':')) {
                            address.takeLast(5)
                        } else {
                            address.takeLast(4)
                        }
                        val peerShortId = shortId ?: engine.resolveShortIdForAddressSuffix(suffix)
                        if (peerShortId != null) {
                            gatt.onMeshPeerAddress(address, peerShortId)
                        }
                    }
                    adv.onPeerAddressDiscovered = addressCallback
                    advPeerAddressCallback = addressCallback
                }
                voiceTransportObserveJob = scope.launch {
                    gatt.incomingPackets?.collect { incoming ->
                        engine.processIncomingPacket(incoming.bytes, nowMs = incoming.timestampEpochMs)
                    }
                }
                gatt.start()
            } else {
                engine.setVoiceStreamTransport(null)
            }
        } else {
            engine.setVoiceStreamTransport(null)
        }

        engine.start(scope)

        if (heartbeatClient != null) {
            heartbeatClient.startClient()
            gattHeartbeatClient = heartbeatClient
        }

        // Bloque 3.4: Activar UniqueAnnouncer + TtsAnnouncer mientras la rodada está RUNNING
        val announcer = UniqueAnnouncer(
            scope = scope,
            localShortId = localShortId,
            meshEngine = engine
        )
        uniqueAnnouncer = announcer
        announcer.start()

        // Bloque 3.5: Activar PeerHealthMonitor (histéresis 2 latidos + islas > 60 s) y HeartbeatScheduler adaptativo
        val healthMonitor = PeerHealthMonitor(engine = engine)
        peerHealthMonitor = healthMonitor
        val scheduler = HeartbeatScheduler(
            scope = scope,
            engine = engine,
            peerHealthMonitor = healthMonitor
        )
        scheduler.applyPowerProfile(batteryAwareness.state.value.profile)
        heartbeatScheduler = scheduler

        // Fase 6 · Hito 5 · Etapa 3: Inicializar odómetro de malla
        val odo = com.rutabikersync.app.core.mesh.odometer.MeshOdometer()
        odo.peerHeartbeatChecker = { nowMs ->
            relayEngine?.hasActivePeerWithin(windowMs = 10_000L, nowEpochMs = nowMs) == true
        }
        meshOdometer = odo

        val initialSnapshot = buildNotificationSnapshot(
            lifecycleState = RideLifecycleState.RUNNING,
            connectedRiders = 0,
            lostRiders = 0,
            transportName = selectedTransport.name,
            isSimulated = isSimulated,
            isSos = false,
            stopRemainingMs = 0L
        )
        val btSnapshot = bluetoothDevicesProvider?.invoke()
        val bleAdv = selectedTransport as? BleAdvTransport
        _state.value = RideSessionState(
            rideId = rideId,
            lifecycleState = RideLifecycleState.RUNNING,
            startedAtEpochMs = nowEpochMs,
            localShortId = localShortId,
            expectedPeerShortIds = effectiveExpectedPeers.map { it.toInt() }.sorted(),
            transportName = selectedTransport.name,
            forcedBleMode = bleModeOverride?.code,
            f3AdvertisingBurstDurationMs = advertisingBurstDurationMs,
            f3AdvertisingDeliveryMode = advertisingDeliveryMode,
            f3AdvertisingSetsStarted = bleAdv?.advertisingSetsStartedCount ?: 0,
            f3AdvertisingSetsStopped = bleAdv?.advertisingSetsStoppedCount ?: 0,
            f3LastMeasuredTxPowerDbm = bleAdv?.lastMeasuredTxPowerDbm,
            f3AdvertisingBurstsStarted = bleAdv?.advertisingBurstsStartedCount ?: 0,
            f3AdvertisingBurstsSuccess = bleAdv?.advertisingBurstsSuccessCount ?: 0,
            f3AdvertisingFailuresCount = bleAdv?.advertisingFailuresCount ?: 0,
            f3LastAdvertisingFailure = bleAdv?.lastAdvertisingFailure,
            f3ScanStartsCount = bleAdv?.scanStartsCount ?: 0,
            f3ScanFailuresCount = bleAdv?.scanFailuresCount ?: 0,
            f3LastScanFailure = bleAdv?.lastScanFailure,
            f3LogicalHeartbeatsEmitted = 0,
            connectedBluetoothDevicesCount = btSnapshot?.connectedDevicesCount ?: 0,
            hasOtherConnectedBluetoothDevices = btSnapshot?.hasOtherConnectedDevices ?: false,
            connectedBluetoothAudio = btSnapshot?.isAudioDeviceConnected ?: false,
            connectedBluetoothDeviceNames = btSnapshot?.deviceNames ?: emptyList(),
            isUsingSimulatedTransport = isSimulated,
            connectedRidersCount = 0,
            lostRidersCount = 0,
            isSosActive = false,
            stopRequestedAtEpochMs = null,
            voiceClipsPurgedOnClose = false,
            emergencyAccessRevokedOnClose = false,
            relayEnabled = relayEnabled,
            txRxRole = txRxRole,
            unfilteredScanDiagnostic = unfilteredScanDiagnostic,
            notification = initialSnapshot
        )
        runCatching {
            Log.i(
                "RideService",
                "RideStarted rideId=$rideId startedAtEpochMs=$nowEpochMs transport=${selectedTransport.name} forcedMode=${bleModeOverride?.code ?: "AUTO"}"
            )
        }
        onNotificationUpdated?.invoke(initialSnapshot)

        // Observar cambios en los peers de la mesh para actualizar en tiempo real la notificación persistente
        peerObserveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val previousPeerSos = mutableMapOf<UShort, Boolean>()
            engine.peers.collect { peerMap ->
                peerMap.forEach { (peerId, track) ->
                    val wasActive = previousPeerSos[peerId] == true
                    com.rutabikersync.app.core.audio.SosToneHub.onPeerSosStateChanged(
                        senderShortId = peerId,
                        wasActive = wasActive,
                        isActive = track.isSos
                    )
                    previousPeerSos[peerId] = track.isSos
                }
                updateRealtimePeerCounts(peerMap)
            }
        }

        metricsObserveJob?.cancel()
        metricsObserveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.metrics.collect { report ->
                _metrics.value = report
                RideService.updateSharedMetrics(report)
            }
        }

        if (enablePeriodicHeartbeatLoop) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    emitHeartbeatNow()
                    delay(heartbeatScheduler?.currentIntervalMs ?: HEARTBEAT_INTERVAL_MS)
                }
            }
        }

        return _state.value
    }

    /**
     * Bloque 3.5: Actualiza el estado de batería/cargador (`BatteryAwareness`), ajusta automáticamente
     * el intervalo de `HeartbeatScheduler` (3 s -> 5 s -> 10 s), congela el mapa en modo supervivencia (<= 10% / 5%)
     * y actualiza la notificación persistente.
     */
    fun onBatteryStateChanged(
        batteryPercent: Int,
        isCharging: Boolean
    ): RideSessionState {
        localBatteryPercent = batteryPercent.coerceIn(0, 100)
        val powerState = batteryAwareness.evaluateBatteryState(localBatteryPercent, isCharging)
        return applyPowerStateToSession(powerState.profile, powerState.lastTtsAlertMessage)
    }

    fun setAutoBatterySaverEnabled(enabled: Boolean): RideSessionState {
        val powerState = batteryAwareness.setAutoBatterySaverEnabled(enabled)
        return applyPowerStateToSession(powerState.profile, powerState.lastTtsAlertMessage)
    }

    fun setManualPowerSaverOverride(override: Boolean?): RideSessionState {
        val powerState = batteryAwareness.setManualPowerSaverOverride(override)
        return applyPowerStateToSession(powerState.profile, powerState.lastTtsAlertMessage)
    }

    private fun applyPowerStateToSession(
        profile: PowerProfile,
        ttsAlert: String?
    ): RideSessionState {
        heartbeatScheduler?.applyPowerProfile(profile)
        val current = _state.value
        val updatedSnapshot = buildNotificationSnapshot(
            lifecycleState = current.lifecycleState,
            connectedRiders = current.connectedRidersCount,
            lostRiders = current.lostRidersCount,
            transportName = "${current.transportName} · ${profile.displayLabel}",
            isSimulated = current.isUsingSimulatedTransport,
            isSos = current.isSosActive,
            stopRemainingMs = if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP) STOP_CONFIRM_WINDOW_MS else 0L
        )
        _state.value = current.copy(
            powerProfile = profile,
            heartbeatIntervalMs = profile.heartbeatIntervalMs,
            mapUpdateCadenceMs = profile.mapUpdateCadenceMs,
            isMapFrozen = profile.isMapFrozen,
            lastBatteryTtsAlert = ttsAlert,
            notification = updatedSnapshot
        )
        onNotificationUpdated?.invoke(updatedSnapshot)
        return _state.value
    }

    fun updateLocalTelemetry(
        latitude: Double,
        longitude: Double,
        speedKmh: Int,
        bearingDegrees: Float = 0f,
        batteryPercent: Int = 85,
        observedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        // Nunca aceptar un centinela como posición propia: se publicaría por BLE y el
        // receptor calcularía distancias de miles de km contra una coordenada falsa.
        if (!MeshTelemetry.isValidPosition(latitude, longitude)) {
            hasValidLocalPosition = false
            return
        }
        localLatitude = latitude
        localLongitude = longitude
        localPositionObservedAtEpochMs = observedAtEpochMs
        hasValidLocalPosition = true
        localSpeedKmh = speedKmh
        localBearingDegrees = bearingDegrees
        localBatteryPercent = batteryPercent
        meshOdometer?.onLocationUpdate(latitude, longitude)
    }

    suspend fun emitHeartbeatNow(
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val engine = relayEngine ?: return
        val current = _state.value
        if (current.lifecycleState != RideLifecycleState.RUNNING &&
            current.lifecycleState != RideLifecycleState.CONFIRMING_STOP
        ) {
            return
        }
        val hasFreshLocalPosition = hasValidLocalPosition &&
            LocalGpsPositionPolicy.isFresh(localPositionObservedAtEpochMs, nowEpochMs)
        val publishRealPosition = hasFreshLocalPosition || !requireValidPositionForHeartbeat
        // La identidad y la vida de la malla se publican siempre. Sin fix fresco se envía
        // el centinela (0,0), que MeshTelemetry.isValidPosition excluye del mapa; nunca
        // se reemite una coordenada antigua como si fuese actual.
        engine.emitLocalPosition(
            latitude = if (publishRealPosition) localLatitude else 0.0,
            longitude = if (publishRealPosition) localLongitude else 0.0,
            speedKmh = localSpeedKmh,
            bearingDegrees = localBearingDegrees,
            batteryPercent = localBatteryPercent,
            isSos = current.isSosActive
        )
        runCatching {
            val elapsedSec = ((nowEpochMs - current.startedAtEpochMs) / 1000L).coerceAtLeast(0L)
            Log.i(
                "HeartbeatTx",
                "rideId=${current.rideId} startedAtEpochMs=${current.startedAtEpochMs} " +
                    "elapsedSec=${elapsedSec}s packetsEmitted=${engine.metrics.value.packetsEmitted}"
            )
        }
        peerHealthMonitor?.evaluateHealth(nowEpochMs) ?: engine.evaluatePeerTimeouts(nowEpochMs)
        updateRealtimePeerCounts(engine.peers.value)
    }

    suspend fun triggerSos(active: Boolean = true) {
        val current = _state.value
        if (current.lifecycleState == RideLifecycleState.IDLE ||
            current.lifecycleState == RideLifecycleState.STOPPED
        ) {
            return
        }
        if (active) {
            SosToneHub.setLocalActive(true)
        } else {
            relayEngine?.stopSosBroadcaster()
            SosToneHub.setLocalActive(false)
        }
        if (active && current.isSosActive) return

        val nextSnapshot = buildNotificationSnapshot(
            lifecycleState = current.lifecycleState,
            connectedRiders = current.connectedRidersCount,
            lostRiders = current.lostRidersCount,
            transportName = current.transportName,
            isSimulated = current.isUsingSimulatedTransport,
            isSos = active,
            stopRemainingMs = if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP) STOP_CONFIRM_WINDOW_MS else 0L
        )
        _state.value = current.copy(
            isSosActive = active,
            notification = nextSnapshot
        )
        onNotificationUpdated?.invoke(nextSnapshot)
        if (active) {
            val now = System.currentTimeMillis()
            val hasFreshPosition = hasValidLocalPosition &&
                LocalGpsPositionPolicy.isFresh(localPositionObservedAtEpochMs, now)
            val transmitted = relayEngine?.let { relay ->
                com.rutabikersync.app.core.mesh.sos.SosMeshEmitter.startLocalBroadcast(
                    relay = relay,
                    scope = scope,
                    latitude = if (hasFreshPosition) localLatitude else 0.0,
                    longitude = if (hasFreshPosition) localLongitude else 0.0
                )
            } == true
            if (!transmitted) {
                android.util.Log.w("RideService", "SOS local activo, sin transporte disponible para emitir aún")
            }
            emitHeartbeatNow()
        } else {
            // Cancelar SOS es un estado prioritario y debe llegar incluso sin fix GPS fresco.
            emitHeartbeatNow()
        }
    }

    /** ACK real creado desde el paquete recibido y enviado por el motor activo. */
    fun sendSosAck(packet: com.rutabikersync.app.core.mesh.sos.SosAckPacket): Boolean =
        relayEngine?.emitSosAckPacket(packet) == true

    /**
     * Acción "Detener rodada" desde la notificación persistente:
     * - Primer toque: entra en `CONFIRMING_STOP` durante 3 segundos (`STOP_CONFIRM_WINDOW_MS`).
     * - Segundo toque dentro de los 3 segundos: confirma y detiene la rodada (`stopAll`).
     * - Si pasan los 3 segundos sin segundo toque: cancela la confirmación y sigue en `RUNNING`.
     */
    suspend fun onStopActionFromNotification(
        nowEpochMs: Long = System.currentTimeMillis(),
        scheduleAutoRevert: Boolean = true
    ): RideSessionState {
        val current = _state.value
        if (current.lifecycleState == RideLifecycleState.RUNNING) {
            val confirmSnapshot = buildNotificationSnapshot(
                lifecycleState = RideLifecycleState.CONFIRMING_STOP,
                connectedRiders = current.connectedRidersCount,
                lostRiders = current.lostRidersCount,
                transportName = current.transportName,
                isSimulated = current.isUsingSimulatedTransport,
                isSos = current.isSosActive,
                stopRemainingMs = STOP_CONFIRM_WINDOW_MS
            )
            _state.value = current.copy(
                lifecycleState = RideLifecycleState.CONFIRMING_STOP,
                stopRequestedAtEpochMs = nowEpochMs,
                notification = confirmSnapshot
            )
            onNotificationUpdated?.invoke(confirmSnapshot)

            if (scheduleAutoRevert) {
                stopConfirmTimeoutJob?.cancel()
                stopConfirmTimeoutJob = scope.launch {
                    delay(STOP_CONFIRM_WINDOW_MS)
                    expireStopConfirmationIfNeeded(nowEpochMs + STOP_CONFIRM_WINDOW_MS)
                }
            }
            return _state.value
        }

        if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP) {
            val requestedAt = current.stopRequestedAtEpochMs ?: nowEpochMs
            if (nowEpochMs - requestedAt <= STOP_CONFIRM_WINDOW_MS) {
                return stopAll()
            } else {
                expireStopConfirmationIfNeeded(nowEpochMs)
                return _state.value
            }
        }

        return current
    }

    fun expireStopConfirmationIfNeeded(nowEpochMs: Long = System.currentTimeMillis()): RideSessionState {
        val current = _state.value
        val requestedAt = current.stopRequestedAtEpochMs ?: return current
        if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP &&
            nowEpochMs - requestedAt >= STOP_CONFIRM_WINDOW_MS
        ) {
            val revertedSnapshot = buildNotificationSnapshot(
                lifecycleState = RideLifecycleState.RUNNING,
                connectedRiders = current.connectedRidersCount,
                lostRiders = current.lostRidersCount,
                transportName = current.transportName,
                isSimulated = current.isUsingSimulatedTransport,
                isSos = current.isSosActive,
                stopRemainingMs = 0L
            )
            _state.value = current.copy(
                lifecycleState = RideLifecycleState.RUNNING,
                stopRequestedAtEpochMs = null,
                notification = revertedSnapshot
            )
            onNotificationUpdated?.invoke(revertedSnapshot)
        }
        return _state.value
    }

    suspend fun stopAll(): RideSessionState {
        stopAllInternal(resetToIdle = false)
        val stoppedSnapshot = buildNotificationSnapshot(
            lifecycleState = RideLifecycleState.STOPPED,
            connectedRiders = 0,
            lostRiders = 0,
            transportName = "Stopped",
            isSimulated = _state.value.isUsingSimulatedTransport,
            isSos = false,
            stopRemainingMs = 0L
        )
        _state.value = _state.value.copy(
            lifecycleState = RideLifecycleState.STOPPED,
            startedAtEpochMs = 0L,
            connectedRidersCount = 0,
            lostRidersCount = 0,
            isSosActive = false,
            stopRequestedAtEpochMs = null,
            voiceClipsPurgedOnClose = true,
            emergencyAccessRevokedOnClose = true,
            ttsShutdownOnClose = true,
            notification = stoppedSnapshot
        )
        onNotificationUpdated?.invoke(stoppedSnapshot)
        return _state.value
    }

    private suspend fun stopAllInternal(resetToIdle: Boolean) {
        com.rutabikersync.app.core.audio.SosToneHub.stopAll()
        stopConfirmTimeoutJob?.cancel()
        stopConfirmTimeoutJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatScheduler?.stop()
        heartbeatScheduler = null
        peerHealthMonitor?.reset()
        peerHealthMonitor = null
        batteryAwareness.resetToNormal()
        peerObserveJob?.cancel()
        peerObserveJob = null
        autoFallbackJob?.cancel()
        autoFallbackJob = null
        metricsObserveJob?.cancel()
        metricsObserveJob?.cancel()
        metricsObserveJob = null
        uniqueAnnouncer?.cancelAllAndStop()
        uniqueAnnouncer = null

        // Fase 6 · Hito 5 · Etapa 3: Persistir kilometraje de malla en Room si se acumuló distancia
        val currentRideId = _state.value.rideId
        val odo = meshOdometer
        if (!currentRideId.isNullOrBlank() && odo != null && odo.totalAccumulatedKm > 0.0) {
            val meters = odo.totalAccumulatedMeters.toLong()
            val event = com.rutabikersync.app.core.gamification.PendingGamificationEvent(
                rideId = currentRideId,
                eventType = com.rutabikersync.app.core.gamification.GamificationEventType.MESH_KM.name,
                value = meters
            )
            gamificationDao?.let { dao ->
                scope.launch {
                    runCatching { dao.insert(event) }
                }
            }
            if (!_state.value.isUsingSimulatedTransport) {
                if (odo.totalAccumulatedKm >= 0.05) {
                    runCatching { onRideGamificationKmRecorded?.invoke(odo.totalAccumulatedKm) }
                }
                val rideKm = kotlin.math.round(odo.totalAccumulatedKm).toInt()
                if (rideKm > 0) {
                    runCatching { onRideDistanceKmRecorded?.invoke(rideKm) }
                }
            }
        }
        meshOdometer = null
        val currentCsv = relayEngine?.exportPacketsCsv()
        if (!currentCsv.isNullOrBlank() && currentCsv.lines().count { it.isNotBlank() } > 1) {
            lastPacketsCsv = currentCsv
            RideService.globalLastPacketsCsv = currentCsv
        }
        voiceTransportObserveJob?.cancel()
        voiceTransportObserveJob = null
        advPeerAddressCallback?.let { (activeTransport as? BleAdvTransport)?.onPeerAddressDiscovered = null }
        advPeerAddressCallback = null
        relayEngine?.setVoiceStreamTransport(null)
        gattVoiceTransport?.stop()
        gattVoiceTransport = null
        gattHeartbeatServer?.stop()
        gattHeartbeatServer = null
        gattHeartbeatClient?.stopClient()
        gattHeartbeatClient = null

        relayEngine?.stop()
        relayEngine = null
        activeTransport?.stop()
        activeTransport = null
        if (resetToIdle) {
            _state.value = RideSessionState()
        }
    }

    private fun resolveTransport(
        localShortId: UShort,
        forceSimulated: Boolean,
        bleModeOverride: BleAdvTransport.TransportModeOverride? = null,
        advertisingBurstDurationMs: Int = BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS,
        txRxRole: BleAdvTransport.TxRxRole = BleAdvTransport.TxRxRole.BOTH,
        unfilteredScanDiagnostic: Boolean = false,
        advertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST
    ): Pair<Transport, Boolean> {
        if (!forceSimulated) {
            val realBle = runCatching { bleTransportFactory?.invoke() }.getOrNull()
            if (realBle != null) {
                realBle.modeOverride = bleModeOverride
                realBle.advertisingBurstDurationMs = advertisingBurstDurationMs
                realBle.txRxRole = txRxRole
                realBle.unfilteredScanDiagnostic = unfilteredScanDiagnostic
                realBle.advertisingDeliveryMode = advertisingDeliveryMode
                val caps = runCatching { realBle.queryCapabilities() }.getOrNull()
                if (caps != null && caps.bluetoothSupported && caps.bluetoothEnabled) {
                    return realBle to false
                }
            }
        }
        val simulated: SimulatedBleTransport = simulatedMedium.createNodeTransport(localShortId)
        return simulated to true
    }

    private fun updateRealtimePeerCounts(peerMap: Map<UShort, MeshRelayEngine.PeerTrack>) {
        val current = _state.value
        if (current.lifecycleState != RideLifecycleState.RUNNING &&
            current.lifecycleState != RideLifecycleState.CONFIRMING_STOP
        ) {
            return
        }
        val nowEpochMs = System.currentTimeMillis()
        val presenceByShortId = peerMap.mapValues { (_, track) ->
            PeerPresencePolicy.stateFor(track, nowEpochMs)
        }
        // La notificación usa el mismo criterio que HUD y mapa: degradado cuenta como
        // presente, pero se distingue del confirmado en el texto.
        val activeConnected = peerMap.values.count {
            it.receivedBeats > 0 &&
                presenceByShortId[it.shortId] != PeerPresenceState.LOST
        }
        val staleCount = presenceByShortId.values.count { it == PeerPresenceState.STALE_GRACE }
        val lostCount = presenceByShortId.values.count(PeerPresencePolicy::isConfirmedLost)
        val bleAdv = activeTransport as? BleAdvTransport
        val btSnapshot = bluetoothDevicesProvider?.invoke()
        val snapshot = buildNotificationSnapshot(
            lifecycleState = current.lifecycleState,
            connectedRiders = activeConnected,
            staleRiders = staleCount,
            lostRiders = lostCount,
            transportName = current.transportName,
            isSimulated = current.isUsingSimulatedTransport,
            isSos = current.isSosActive,
            stopRemainingMs = if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP) STOP_CONFIRM_WINDOW_MS else 0L
        )
        _state.value = current.copy(
            connectedRidersCount = activeConnected,
            lostRidersCount = lostCount,
            f3AdvertisingSetsStarted = bleAdv?.advertisingSetsStartedCount ?: 0,
            f3AdvertisingSetsStopped = bleAdv?.advertisingSetsStoppedCount ?: 0,
            f3LastMeasuredTxPowerDbm = bleAdv?.lastMeasuredTxPowerDbm,
            f3AdvertisingBurstsStarted = bleAdv?.advertisingBurstsStartedCount ?: 0,
            f3AdvertisingBurstsSuccess = bleAdv?.advertisingBurstsSuccessCount ?: 0,
            f3AdvertisingFailuresCount = bleAdv?.advertisingFailuresCount ?: 0,
            f3LastAdvertisingFailure = bleAdv?.lastAdvertisingFailure,
            f3ScanStartsCount = bleAdv?.scanStartsCount ?: 0,
            f3ScanFailuresCount = bleAdv?.scanFailuresCount ?: 0,
            f3LastScanFailure = bleAdv?.lastScanFailure,
            f3LogicalHeartbeatsEmitted = relayEngine?.metrics?.value?.packetsEmitted ?: 0,
            connectedBluetoothDevicesCount = btSnapshot?.connectedDevicesCount ?: current.connectedBluetoothDevicesCount,
            hasOtherConnectedBluetoothDevices = btSnapshot?.hasOtherConnectedDevices ?: current.hasOtherConnectedBluetoothDevices,
            connectedBluetoothAudio = btSnapshot?.isAudioDeviceConnected ?: current.connectedBluetoothAudio,
            connectedBluetoothDeviceNames = btSnapshot?.deviceNames ?: current.connectedBluetoothDeviceNames,
            notification = snapshot
        )
        onNotificationUpdated?.invoke(snapshot)
    }

    private fun buildNotificationSnapshot(
        lifecycleState: RideLifecycleState,
        connectedRiders: Int,
        staleRiders: Int = 0,
        lostRiders: Int,
        transportName: String,
        isSimulated: Boolean,
        isSos: Boolean,
        stopRemainingMs: Long
    ): RideNotificationSnapshot {
        val modeLabel = if (isSimulated) "BLE Simulado (Dev)" else "BLE Real"
        val title = when {
            lifecycleState == RideLifecycleState.STOPPED -> "Rodada finalizada"
            isSos -> "🚨 SOS ACTIVO · Rodada en curso"
            lifecycleState == RideLifecycleState.CONFIRMING_STOP -> "⚠️ Confirmar cierre de rodada (3 s)"
            else -> "Rodada activa · $connectedRiders riders conectados"
        }
        val content = when (lifecycleState) {
            RideLifecycleState.CONFIRMING_STOP ->
                "Pulsa 'Detener' otra vez en 3 s para cerrar ($connectedRiders riders en mesh · $modeLabel)"
            RideLifecycleState.RUNNING -> {
                val presenceSuffix = PeerPresencePolicy.notificationSuffix(staleRiders, lostRiders)
                "$connectedRiders riders conectados en la mesh$presenceSuffix · $modeLabel"
            }
            RideLifecycleState.STOPPED ->
                "Mesh detenida · Notas de voz y accesos de emergencia borrados (R6.5)"
            RideLifecycleState.IDLE -> "Sin rodada activa"
        }
        return RideNotificationSnapshot(
            title = title,
            contentText = content,
            connectedRidersCount = connectedRiders,
            totalParticipantsCount = connectedRiders + 1,
            transportModeLabel = transportName,
            isUsingSimulatedTransport = isSimulated,
            isSosActive = isSos,
            isConfirmingStop = lifecycleState == RideLifecycleState.CONFIRMING_STOP,
            stopConfirmationRemainingMs = stopRemainingMs
        )
    }

    fun recordStartError(errorMessage: String?): RideSessionState {
        _state.value = _state.value.copy(lastStartError = errorMessage)
        return _state.value
    }
}

/**
 * Resultado puro de elegibilidad del servicio y de la mesh BLE según la versión de Android (`sdkInt`)
 * y el conjunto de permisos concedidos (`grantedPermissions`).
 *
 * Reglas verificadas contra la documentación oficial de Android:
 * 1. `meshViable`:
 *    - API 31+ (`Build.VERSION_CODES.S`, Android 12+): exige AMBOS `BLUETOOTH_SCAN` y `BLUETOOTH_ADVERTISE`
 *      porque la mesh necesita escuchar y emitir paquetes simultáneamente.
 *    - API 26–30 (`Build.VERSION_CODES.O..R`, Android 8–11): exige permisos legacy (`BLUETOOTH` y `BLUETOOTH_ADMIN`)
 *      más ubicación (`ACCESS_FINE_LOCATION` o `ACCESS_COARSE_LOCATION`), requerida por Android 6–11 para escanear BLE.
 * 2. `effectiveForceSimulated`: `forceSimulated || !meshViable`.
 * 3. `foregroundServiceTypes`:
 *    - En API 34+ (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`), `startForeground()` con `type = 0` lanza
 *      `MissingForegroundServiceTypeException`. Por tanto, en modo simulado (`effectiveForceSimulated == true`)
 *      **NO se usa un Foreground Service** (`shouldPromoteToForeground = false`, `foregroundServiceTypes = 0`):
 *      la simulación corre dentro de la app mientras está visible.
 *    - En modo real (`effectiveForceSimulated == false`):
 *      - `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (`0x10`) se incluye solo si hay al menos un permiso BLE concedido.
 *      - `FOREGROUND_SERVICE_TYPE_LOCATION` (`0x08`) se incluye solo si hay permiso de ubicación concedido.
 * 4. `postNotificationsGranted`: en API 33+ (`Build.VERSION_CODES.TIRAMISU`), `POST_NOTIFICATIONS` en `grantedPermissions`
 *    (si se rechaza, el servicio sigue corriendo pero la notificación no se muestra y la UI avisa al usuario).
 */
data class RideServiceEligibility(
    val sdkInt: Int,
    val meshViable: Boolean,
    val effectiveForceSimulated: Boolean,
    val foregroundServiceTypes: Int,
    val shouldPromoteToForeground: Boolean,
    val postNotificationsGranted: Boolean,
    /** P0: false cuando no se pudo obtener una clave de rodada firmada por el backend. */
    val securityKeyReady: Boolean = true,
    /** true cuando el guard de arranque suprimió una segunda solicitud idéntica. */
    val duplicateStartSuppressed: Boolean = false
)

class RideService : Service() {

    companion object {
        private const val TAG = "RideService"
        const val CHANNEL_ID = "rbs_active_ride_mesh_channel"
        const val NOTIFICATION_ID = 3201

        @Volatile
        var instance: RideService? = null
            private set

        /** Un único arranque por rodada: cubre los dos callers de UI que llegaban a <100 ms. */
        private val rideStartDeduplicator = RideStartDeduplicator()

        suspend fun restartWithBleMode(mode: BleAdvTransport.TransportModeOverride): RideSessionState? {
            return instance?.restartWithBleMode(mode)
        }

        const val ACTION_START_RIDE = "com.rutabikersync.app.action.START_RIDE"
        const val ACTION_STOP_RIDE = "com.rutabikersync.app.action.STOP_RIDE"
        const val ACTION_FORCE_STOP_RIDE = "com.rutabikersync.app.action.FORCE_STOP_RIDE"
        const val ACTION_TRIGGER_SOS = "com.rutabikersync.app.action.TRIGGER_SOS"
        const val ACTION_CANCEL_SOS = "com.rutabikersync.app.action.CANCEL_SOS"
        const val ACTION_RIDE_START_ERROR = "com.rutabikersync.app.action.RIDE_START_ERROR"

        const val EXTRA_RIDE_ID = "extra_ride_id"
        const val EXTRA_LOCAL_SHORT_ID = "extra_local_short_id"
        const val EXTRA_FORCE_SIMULATED = "extra_force_simulated"
        const val EXTRA_BLE_MODE_OVERRIDE = "extra_ble_mode_override"
        const val EXTRA_ADVERTISING_BURST_DURATION_MS = "extra_advertising_burst_duration_ms"
        const val EXTRA_EXPECTED_PEER_SHORT_IDS = "extra_expected_peer_short_ids"
        const val EXTRA_RIDE_START_ERROR_MESSAGE = "extra_ride_start_error_message"
        const val EXTRA_RELAY_ENABLED = "extra_relay_enabled"
        const val EXTRA_TX_RX_ROLE = "extra_tx_rx_role"
        const val EXTRA_UNFILTERED_SCAN_DIAGNOSTIC = "extra_unfiltered_scan_diagnostic"
        const val EXTRA_ADVERTISING_DELIVERY_MODE = "extra_advertising_delivery_mode"
        /** P0: clave de 32 bytes del manifiesto firmado, en hexadecimal. */
        const val EXTRA_RIDE_SECRET_KEY_HEX = "extra_ride_secret_key_hex"

        /** Decodifica exactamente 32 bytes hexadecimales; null si el valor es inválido. */
        fun decodeManifestKey(hex: String?): ByteArray? {
            val value = hex?.trim().takeUnless { it.isNullOrBlank() } ?: return null
            if (value.length != 64) return null
            return runCatching {
                ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }

        /**
         * Función pura que devuelve los pares esperados del convoy dado el nº de teléfonos en la prueba (2..5)
         * y el ID de este teléfono (1..totalPhonesInTest), evitando marcar como perdidos nodos inexistentes
         * (ej. con 2 teléfonos e ID #1, solo espera al nodo #2 y no al #3).
         */
        fun computeExpectedPeerIds(totalPhonesInTest: Int, localNodeShortId: Int): Set<UShort> {
            val clampedTotal = totalPhonesInTest.coerceIn(2, 5)
            val clampedLocal = localNodeShortId.coerceIn(1, clampedTotal)
            return (1..clampedTotal)
                .filter { it != clampedLocal }
                .map { it.toUShort() }
                .toSet()
        }

        private val _sharedState = MutableStateFlow(RideSessionState())
        val sharedState: StateFlow<RideSessionState> = _sharedState.asStateFlow()

        private val _sharedMetrics = MutableStateFlow<MeshRelayEngine.MeshFieldReport?>(null)
        val sharedMetrics: StateFlow<MeshRelayEngine.MeshFieldReport?> = _sharedMetrics.asStateFlow()

        private val inAppSimScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val inAppSimController = RideSessionController(
            scope = inAppSimScope,
            bleTransportFactory = { null },
            onNotificationUpdated = { _ ->
                _sharedState.value = inAppSimControllerState()
            }
        )

        private fun inAppSimControllerState(): RideSessionState = inAppSimController.state.value

        fun updateSharedMetrics(report: MeshRelayEngine.MeshFieldReport?) {
            _sharedMetrics.value = report
        }

        /**
         * Función pura determinista (probada en JVM sin MockContext) que calcula:
         * - `meshViable` según `sdkInt` y permisos reales de la mesh
         * - `effectiveForceSimulated = forceSimulated || !meshViable`
         * - `foregroundServiceTypes` (`CONNECTED_DEVICE = 0x10`, `LOCATION = 0x08`)
         * - `shouldPromoteToForeground` (`false` en modo simulado para no invocar FGS sin tipo en API 34+)
         * - `postNotificationsGranted` (detección de `POST_NOTIFICATIONS` en API 33+)
         */
        fun computeEligibility(
            sdkInt: Int,
            grantedPermissions: Set<String>,
            forceSimulated: Boolean = false
        ): RideServiceEligibility {
            val hasLocation = Manifest.permission.ACCESS_FINE_LOCATION in grantedPermissions ||
                Manifest.permission.ACCESS_COARSE_LOCATION in grantedPermissions

            val meshViable = if (sdkInt >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN in grantedPermissions &&
                    Manifest.permission.BLUETOOTH_ADVERTISE in grantedPermissions
            } else {
                val hasLegacyBle = Manifest.permission.BLUETOOTH in grantedPermissions &&
                    Manifest.permission.BLUETOOTH_ADMIN in grantedPermissions
                hasLegacyBle && hasLocation
            }

            val hasAnyBleForFgsType = if (sdkInt >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN in grantedPermissions ||
                    Manifest.permission.BLUETOOTH_ADVERTISE in grantedPermissions ||
                    Manifest.permission.BLUETOOTH_CONNECT in grantedPermissions
            } else {
                Manifest.permission.BLUETOOTH in grantedPermissions ||
                    Manifest.permission.BLUETOOTH_ADMIN in grantedPermissions
            }

            val effectiveForceSim = forceSimulated || !meshViable

            val fgsTypes = if (effectiveForceSim || sdkInt < Build.VERSION_CODES.Q) {
                0
            } else {
                var types = 0
                if (hasAnyBleForFgsType) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (hasLocation) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                types
            }

            val shouldPromote = !effectiveForceSim && (
                sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || fgsTypes != 0
            )

            val notificationsGranted = sdkInt < Build.VERSION_CODES.TIRAMISU ||
                Manifest.permission.POST_NOTIFICATIONS in grantedPermissions

            return RideServiceEligibility(
                sdkInt = sdkInt,
                meshViable = meshViable,
                effectiveForceSimulated = effectiveForceSim,
                foregroundServiceTypes = fgsTypes,
                shouldPromoteToForeground = shouldPromote,
                postNotificationsGranted = notificationsGranted
            )
        }

        fun collectGrantedPermissions(context: Context): Set<String> {
            val candidates = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            return candidates.filterTo(mutableSetOf()) { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun evaluateContextEligibility(
            context: Context,
            forceSimulated: Boolean = false
        ): RideServiceEligibility = computeEligibility(
            sdkInt = Build.VERSION.SDK_INT,
            grantedPermissions = collectGrantedPermissions(context),
            forceSimulated = forceSimulated
        )

        fun hasLocationPermissions(context: Context): Boolean {
            val granted = collectGrantedPermissions(context)
            return Manifest.permission.ACCESS_FINE_LOCATION in granted ||
                Manifest.permission.ACCESS_COARSE_LOCATION in granted
        }

        fun isMeshViable(context: Context): Boolean =
            evaluateContextEligibility(context, forceSimulated = false).meshViable

        fun isPostNotificationsGranted(context: Context): Boolean =
            evaluateContextEligibility(context, forceSimulated = false).postNotificationsGranted

        fun isBluetoothAdapterEnabled(context: Context): Boolean =
            runCatching { BleAdvTransport(context.applicationContext).queryCapabilities().bluetoothEnabled }
                .getOrDefault(false)

        /**
         * Inicia la rodada o simulación según `computeEligibility`:
         * - Si `shouldPromoteToForeground == true` (modo BLE real con permisos viables), usa `startForegroundService`.
         * - Si `effectiveForceSimulated == true` (modo simulado), NO inicia ningún Service de Android:
         *   la simulación corre dentro del proceso de la app visible (`inAppSimController`).
         */
        fun dispatchStartRide(
            context: Context,
            rideId: String,
            localShortId: Int = 1,
            forceSimulated: Boolean = false,
            bleModeOverride: BleAdvTransport.TransportModeOverride? = null,
            advertisingBurstDurationMs: Int = BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS,
            expectedPeerShortIds: Set<UShort> = emptySet(),
            relayEnabled: Boolean = true,
            txRxRole: BleAdvTransport.TxRxRole = BleAdvTransport.TxRxRole.BOTH,
            unfilteredScanDiagnostic: Boolean = false,
            advertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST,
            /** P0: obligatoria en producción; nunca se usa la clave fija de tests. */
            rideSecretKey: ByteArray? = null
        ): RideServiceEligibility {
            val eligibility = evaluateContextEligibility(context, forceSimulated)
            if (!rideStartDeduplicator.shouldStart(rideId)) {
                Log.i(
                    TAG,
                    "RideStart duplicado ignorado rideId=$rideId (ventana ${RideStartDeduplicator.DEFAULT_WINDOW_MS} ms)"
                )
                return eligibility.copy(duplicateStartSuppressed = true)
            }
            val safeKey = rideSecretKey?.takeIf { it.size == 32 }
            if (safeKey == null) {
                Log.e(
                    TAG,
                    "RideStart bloqueado: falta la clave de rodada del manifiesto firmado. " +
                        "No se inicia malla con clave fija ni aleatoria local."
                )
                return eligibility.copy(securityKeyReady = false, shouldPromoteToForeground = false)
            }
            if (eligibility.shouldPromoteToForeground) {
                val intent = Intent(context, RideService::class.java).apply {
                    action = ACTION_START_RIDE
                    putExtra(EXTRA_RIDE_ID, rideId)
                    putExtra(EXTRA_LOCAL_SHORT_ID, localShortId)
                    putExtra(EXTRA_FORCE_SIMULATED, false)
                    putExtra(EXTRA_BLE_MODE_OVERRIDE, bleModeOverride?.code)
                    putExtra(EXTRA_ADVERTISING_BURST_DURATION_MS, advertisingBurstDurationMs)
                    putExtra(
                        EXTRA_EXPECTED_PEER_SHORT_IDS,
                        expectedPeerShortIds.map { it.toInt() }.toIntArray()
                    )
                    putExtra(EXTRA_RELAY_ENABLED, relayEnabled)
                    putExtra(EXTRA_TX_RX_ROLE, txRxRole.code)
                    putExtra(EXTRA_UNFILTERED_SCAN_DIAGNOSTIC, unfilteredScanDiagnostic)
                    putExtra(EXTRA_ADVERTISING_DELIVERY_MODE, advertisingDeliveryMode.code)
                    putExtra(EXTRA_RIDE_SECRET_KEY_HEX, safeKey.joinToString("") { "%02x".format(it) })
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                Log.i(TAG, "Starting in-app simulated mesh (no Android Service / no FGS)")
                inAppSimScope.launch {
                    val state = inAppSimController.startRide(
                        rideId = rideId,
                        localShortId = localShortId.toUShort(),
                        rideSecretKey = safeKey,
                        forceSimulatedTransport = true,
                        bleModeOverride = bleModeOverride,
                        advertisingBurstDurationMs = advertisingBurstDurationMs,
                        expectedPeerShortIds = expectedPeerShortIds,
                        relayEnabled = relayEnabled,
                        txRxRole = txRxRole,
                        unfilteredScanDiagnostic = unfilteredScanDiagnostic,
                        advertisingDeliveryMode = advertisingDeliveryMode
                    )
                    _sharedState.value = state
                    drainPendingLifecycleEvents()
                }
            }
            return eligibility
        }

        fun dispatchStopRide(context: Context) {
            val current = _sharedState.value
            if (current.isUsingSimulatedTransport) {
                inAppSimScope.launch {
                    val state = inAppSimController.onStopActionFromNotification()
                    _sharedState.value = state
                }
            } else {
                val stopIntent = Intent(context, RideService::class.java).apply {
                    action = ACTION_STOP_RIDE
                }
                context.startService(stopIntent)
            }
        }

        fun dispatchImmediateStopRide(context: Context) {
            val csv = activeController?.relayEngine?.exportPacketsCsv() ?: activeController?.lastPacketsCsv
            if (!csv.isNullOrBlank() && csv.lines().count { it.isNotBlank() } > 1) {
                globalLastPacketsCsv = csv
            }
            val current = _sharedState.value
            if (current.isUsingSimulatedTransport) {
                inAppSimScope.launch {
                    val state = inAppSimController.stopAll()
                    _sharedState.value = state
                }
            } else {
                val stopIntent = Intent(context, RideService::class.java).apply {
                    action = ACTION_FORCE_STOP_RIDE
                }
                context.startService(stopIntent)
            }
        }

        fun dispatchTriggerSos(context: Context, active: Boolean? = null) {
            val current = _sharedState.value
            val targetActive = active ?: !current.isSosActive
            if (current.isUsingSimulatedTransport || current.lifecycleState != RideLifecycleState.RUNNING) {
                inAppSimScope.launch {
                    inAppSimController.triggerSos(targetActive)
                    _sharedState.value = inAppSimController.state.value.copy(isSosActive = targetActive)
                }
            } else {
                val sosIntent = Intent(context, RideService::class.java).apply {
                    action = if (targetActive) ACTION_TRIGGER_SOS else ACTION_CANCEL_SOS
                }
                runCatching { context.startService(sosIntent) }
                _sharedState.value = current.copy(isSosActive = targetActive)
            }
        }

        @Volatile
        var globalLastPacketsCsv: String? = null

        private val incomingConvoySignalBus = NonReplayingEventBus<MeshRelayEngine.IncomingConvoySignal>()
        val incomingConvoySignals: SharedFlow<MeshRelayEngine.IncomingConvoySignal> = incomingConvoySignalBus.events

        internal fun notifyIncomingConvoySignal(incoming: MeshRelayEngine.IncomingConvoySignal) {
            incomingConvoySignalBus.publish(incoming)
        }

        private val incomingRideLifecycleEventBus = NonReplayingEventBus<MeshRelayEngine.IncomingRideLifecycleEvent>()
        val incomingRideLifecycleEvents: SharedFlow<MeshRelayEngine.IncomingRideLifecycleEvent> = incomingRideLifecycleEventBus.events

        internal fun notifyIncomingRideLifecycleEvent(incoming: MeshRelayEngine.IncomingRideLifecycleEvent) {
            incomingRideLifecycleEventBus.publish(incoming)
        }

        @Volatile
        var activeController: RideSessionController? = null

        /**
         * P0/H1: eventos autorizados por REST que llegan antes de que exista el
         * MeshRelayEngine. Se conservan en memoria y se drenan en cuanto el
         * servicio publica `RUNNING`. Conjunto para que un reintento de UI no
         * duplique el evento.
         */
        private val pendingLifecycleEvents = PendingLifecycleEvents()

        fun getActiveRelayEngine(): MeshRelayEngine? {
            return activeController?.relayEngine ?: if (_sharedState.value.isUsingSimulatedTransport) inAppSimController.relayEngine else null
        }

        /**
         * Publica la posición real del usuario en el controlador de malla activo para
         * que los heartbeats lleven coordenadas válidas. No modifica GATT ni crypto.
         */
        fun updateActiveLocalTelemetry(
            latitude: Double,
            longitude: Double,
            speedKmh: Int,
            bearingDegrees: Float = 0f,
            batteryPercent: Int = 85,
            observedAtEpochMs: Long = System.currentTimeMillis()
        ) {
            val controller = activeController
                ?: if (_sharedState.value.isUsingSimulatedTransport) inAppSimController else null
            controller?.updateLocalTelemetry(
                latitude = latitude,
                longitude = longitude,
                speedKmh = speedKmh,
                bearingDegrees = bearingDegrees,
                batteryPercent = batteryPercent,
                observedAtEpochMs = observedAtEpochMs
            )
        }

        /** ACK emitido por el motor activo: false si no existe transporte para aceptar el envío. */
        fun dispatchSosAckPacket(ack: com.rutabikersync.app.core.mesh.sos.SosAckPacket): Boolean {
            val controller = activeController
                ?: if (_sharedState.value.isUsingSimulatedTransport) inAppSimController else null
            val accepted = controller?.sendSosAck(ack) == true
            if (accepted) {
                com.rutabikersync.app.core.audio.SosToneHub.onAck(ack)
            } else {
                android.util.Log.w("RideService", "SosAckPacket rechazado: no hay transporte activo")
            }
            return accepted
        }

        /** Abre la sesión lógica de voz GATT justo antes de pulsar PTT. */
        fun beginPttVoiceStream() {
            activeController?.gattVoiceTransport?.beginVoiceSession()
        }

        /** Cierra la sesión lógica de voz GATT al soltar PTT (el enlace se mantiene en rodada). */
        fun endPttVoiceStream() {
            activeController?.gattVoiceTransport?.endVoiceSession()
        }

        /** true cuando GATT tiene conexión, MTU negociado y notificaciones activas. */
        fun isPttVoiceReady(): Boolean = activeController?.gattVoiceTransport?.isVoiceReady == true

        /** Retorna la instancia activa de [BleMeshService], o null si no está en ejecución. */
        fun getActiveMeshService(): com.rutabikersync.app.core.mesh.BleMeshService? =
            com.rutabikersync.app.core.mesh.BleMeshService.getActiveInstance()

        /**
         * Despacha un mensaje de texto táctico cifrado por el MeshRelayEngine activo del RideService
         * (Hito 7.0.2 · fix funcional). BleMeshService NO se usa: nunca se inicia durante la rodada
         * y por eso los mensajes antes se marcaban "Enviado" pero nunca salían por el aire.
         */
        fun dispatchSendTextMessage(context: Context, targetShortId: UShort, text: String): Boolean {
            val engine = getActiveRelayEngine() ?: run {
                android.util.Log.w("RideService", "TextMessageTx abortado: no hay relayEngine activo")
                return false
            }
            val sent = engine.emitTextMessage(targetShortId, text)
            if (sent == null) {
                android.util.Log.w("RideService", "TextMessageTx abortado: emitTextMessage devolvió null")
            }
            return sent != null
        }

        /**
         * Emite una señal rápida de convoy por el MeshRelayEngine activo del RideService
         * con prioridad Transport.Priority.PEER_EVENT.
         */
        suspend fun dispatchSendConvoySignal(signal: com.rutabikersync.app.core.audio.ConvoySignalKind): Boolean {
            val engine = getActiveRelayEngine() ?: run {
                android.util.Log.w("RideService", "ConvoySignalTx abortado: no hay relayEngine activo")
                return false
            }
            val packet = engine.emitConvoySignal(signal)
            android.util.Log.i("RideService", "ConvoySignalTx emitido por malla: kind=${signal.name} code=${signal.code}")
            return true
        }

        /**
         * Emite una notificación de ciclo de rodada (Fase C) por el MeshRelayEngine activo
         * con prioridad Transport.Priority.PEER_EVENT para sincronización off-grid.
         */
        suspend fun dispatchSendRideLifecycleEvent(event: MeshRelayEngine.RideLifecycleEvent): Boolean {
            val engine = getActiveRelayEngine() ?: run {
                // No se descarta: queda pendiente hasta que el motor esté listo.
                pendingLifecycleEvents.enqueue(event)
                android.util.Log.w(
                    "RideService",
                    "RideLifecycleEventTx encolado hasta engine-ready: event=${event.name}"
                )
                return false
            }
            when (event) {
                MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(false)
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_BEEP,
                        pulseCount = 2,
                        pulseDurationMs = 150L,
                        gapMs = 80L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_ACK,
                        pulseCount = 1,
                        pulseDurationMs = 400L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.START_RETURN -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(true)
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_BEEP2,
                        pulseCount = 3,
                        pulseDurationMs = 120L,
                        gapMs = 80L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_PROMPT,
                        pulseCount = 2,
                        pulseDurationMs = 200L,
                        gapMs = 100L
                    )
                }
                MeshRelayEngine.RideLifecycleEvent.SAFE_HOME -> {
                    com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playPattern(
                        android.media.ToneGenerator.TONE_PROP_ACK,
                        pulseCount = 2,
                        pulseDurationMs = 250L,
                        gapMs = 100L
                    )
                }
            }
            val packet = engine.emitRideLifecycleEvent(event)
            pendingLifecycleEvents.remove(event)
            android.util.Log.i("RideService", "RideLifecycleEventTx emitido por malla: event=${event.name} code=${event.code}")
            return true
        }

        /** Drena los eventos encolados. Llamar cuando `sharedState` pasa a RUNNING. */
        suspend fun drainPendingLifecycleEvents() {
            if (getActiveRelayEngine() == null) return
            val queued = pendingLifecycleEvents.snapshot()
            for (event in queued) {
                runCatching { dispatchSendRideLifecycleEvent(event) }
            }
        }

        fun clearPendingLifecycleEvents() = pendingLifecycleEvents.clear()

        fun exportPacketsCsv(): String {
            val live = activeController?.relayEngine?.exportPacketsCsv()
            if (!live.isNullOrBlank() && live.lines().count { it.isNotBlank() } > 1) {
                globalLastPacketsCsv = live
                return live
            }
            val saved = activeController?.lastPacketsCsv
            if (!saved.isNullOrBlank() && saved.lines().count { it.isNotBlank() } > 1) {
                globalLastPacketsCsv = saved
                return saved
            }
            val globalSaved = globalLastPacketsCsv
            if (!globalSaved.isNullOrBlank() && globalSaved.lines().count { it.isNotBlank() } > 1) {
                return globalSaved
            }

            val simLive = inAppSimController.relayEngine?.exportPacketsCsv()
            if (!simLive.isNullOrBlank() && simLive.lines().count { it.isNotBlank() } > 1) return simLive
            val simSaved = inAppSimController.lastPacketsCsv
            if (!simSaved.isNullOrBlank() && simSaved.lines().count { it.isNotBlank() } > 1) return simSaved

            return globalLastPacketsCsv ?: "timestamp_utc,sender_short_id,sequence_number,ttl,hops,rssi_dbm,transport_mode,is_duplicate,direction\n"
        }

        fun queryConnectedBluetoothDevices(context: Context): BluetoothConnectedDevicesSnapshot {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return BluetoothConnectedDevicesSnapshot(summary = "Sin permiso BLUETOOTH_CONNECT")
                    }
                }
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
                    ?: return BluetoothConnectedDevicesSnapshot(summary = "Sin adaptador Bluetooth")

                val gattDevices = runCatching { bm?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList() }.getOrDefault(emptyList())
                val isA2dp = runCatching { adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
                val isHeadset = runCatching { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
                val isAudio = isA2dp || isHeadset

                val names = mutableListOf<String>()
                for (dev in gattDevices) {
                    val name = runCatching { dev.name }.getOrNull() ?: dev.address
                    if (!name.isNullOrBlank()) names.add(name)
                }
                if (isAudio && names.none { it.contains("Audio", ignoreCase = true) || it.contains("Headset", ignoreCase = true) }) {
                    names.add("Audio/Intercom BT (A2DP/HFP)")
                }
                val totalCount = maxOf(gattDevices.size, if (isAudio) 1 else 0)
                val summary = if (totalCount == 0) {
                    "Ninguno"
                } else {
                    names.joinToString(", ").ifBlank { "$totalCount conectado(s)" }
                }
                BluetoothConnectedDevicesSnapshot(
                    connectedDevicesCount = totalCount,
                    hasOtherConnectedDevices = totalCount > 0,
                    isAudioDeviceConnected = isAudio,
                    deviceNames = names,
                    summary = summary
                )
            }.getOrElse { err ->
                BluetoothConnectedDevicesSnapshot(summary = "Error al consultar: ${err.message}")
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RideService = this@RideService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var isPromotedToForeground: Boolean = false
    private var fallDetector: FallDetector? = null
    @Volatile
    private var activeManifestKey: ByteArray? = null

    private var autoFallbackJob: Job? = null

    lateinit var controller: RideSessionController
        private set
    private lateinit var rideStateStore: RideServiceStateStore

    override fun onCreate() {
        instance = this
        super.onCreate()
        rideStateStore = RideServiceStateStore(applicationContext)
        // P0 auditoría 2026-11-25: disponible también cuando el proceso nace solo
        // para atender un START_STICKY.
        com.rutabikersync.app.core.crypto.RideManifestKeyProvider.initialize(applicationContext)
        createNotificationChannelIfNeeded()
        controller = RideSessionController(
            scope = serviceScope,
            bleTransportFactory = { BleAdvTransport(applicationContext) },
            gattVoiceTransportFactory = { shortId ->
                GattVoiceTransport(applicationContext, serviceScope, shortId)
            },
            gattHeartbeatServerFactory = { shortId ->
                GattHeartbeatServer.create(applicationContext, shortId)
            },
            gattHeartbeatClientFactory = { shortId, expectedPeers ->
                GattHeartbeatClient.create(applicationContext, shortId, expectedPeers)
            },
            onNotificationUpdated = { snapshot ->
                _sharedState.value = controller.state.value
                if (isPromotedToForeground) {
                    updateForegroundNotification(snapshot)
                }
            },
            bluetoothDevicesProvider = { queryConnectedBluetoothDevices(applicationContext) },
            requireValidPositionForHeartbeat = true
        )
        controller.onRideGamificationKmRecorded = { distanceKm ->
            com.rutabikersync.app.feature.badges.GamificationRepository(applicationContext)
                .recordCompletedRide(distanceKm = distanceKm)
        }
        controller.onRideDistanceKmRecorded = { rideKm ->
            val garageRepo = com.rutabikersync.app.feature.profile.garage.BikeGarageRepository(applicationContext)
            val updated = garageRepo.addRideDistanceKm(garageRepo.loadState(), rideKm)
            serviceScope.launch {
                runCatching {
                    garageRepo.pushToBackend(
                        com.rutabikersync.app.core.network.ClubApiClient.getInstance(),
                        updated
                    )
                }
            }
        }
        activeController = controller
    }

    /**
     * Siembra la telemetry con la última posición real guardada por la app antes del
     * primer heartbeat. Es la misma caché `rbs_geo_cache` que usa la cabina; no se
     * inventa ninguna coordenada. Si no hay caché, el controlador se abstiene de emitir
     * posición hasta que llegue un fix GPS real.
     */
    private fun seedLocalTelemetryFromCache() {
        val prefs = getSharedPreferences("rbs_geo_cache", Context.MODE_PRIVATE)
        val latitude = prefs.getFloat("last_known_lat", 0f).toDouble()
        val longitude = prefs.getFloat("last_known_lon", 0f).toDouble()
        val accuracy = prefs.getFloat("last_known_accuracy", Float.NaN)
        val observedAtEpochMs = prefs.getLong("last_known_time_epoch_ms", 0L)
        if (
            LocalGpsPositionPolicy.isUsableFix(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                fixTimeEpochMs = observedAtEpochMs,
                nowEpochMs = System.currentTimeMillis()
            )
        ) {
            controller.updateLocalTelemetry(
                latitude = latitude,
                longitude = longitude,
                speedKmh = 0,
                bearingDegrees = 180f,
                batteryPercent = 85,
                observedAtEpochMs = observedAtEpochMs
            )
            Log.i(TAG, "Telemetry sembrada desde caché real reciente al iniciar rodada")
        } else {
            Log.w(TAG, "Sin caché GPS reciente y precisa: no se publica una coordenada obsoleta")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RIDE -> {
                val manifestKeyHex = intent.getStringExtra(EXTRA_RIDE_SECRET_KEY_HEX)
                val manifestKey = decodeManifestKey(manifestKeyHex)
                    ?: run {
                        Log.e(TAG, "ACTION_START_RIDE rechazado: clave del manifiesto ausente o inválida")
                        return START_NOT_STICKY
                    }
                val params = PersistedRideParams(
                    rideId = intent.getStringExtra(EXTRA_RIDE_ID) ?: "ride-local-default",
                    localShortId = intent.getIntExtra(EXTRA_LOCAL_SHORT_ID, 1).toUShort(),
                    manifestKeyHex = manifestKeyHex!!,
                    expectedPeerShortIds = intent.getIntArrayExtra(EXTRA_EXPECTED_PEER_SHORT_IDS)
                        ?.map { it.toUShort() }
                        ?.toSet()
                        ?: emptySet(),
                    forceSimulated = intent.getBooleanExtra(EXTRA_FORCE_SIMULATED, false),
                    bleModeOverrideCode = intent.getStringExtra(EXTRA_BLE_MODE_OVERRIDE),
                    advertisingBurstDurationMs = intent.getIntExtra(
                        EXTRA_ADVERTISING_BURST_DURATION_MS,
                        BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS
                    ),
                    relayEnabled = intent.getBooleanExtra(EXTRA_RELAY_ENABLED, true),
                    txRxRoleCode = intent.getStringExtra(EXTRA_TX_RX_ROLE),
                    unfilteredScanDiagnostic = intent.getBooleanExtra(EXTRA_UNFILTERED_SCAN_DIAGNOSTIC, false),
                    advertisingDeliveryModeCode = intent.getStringExtra(EXTRA_ADVERTISING_DELIVERY_MODE)
                )
                rideStateStore.save(params)
                return startOrRestoreRide(params, manifestKey)
            }
            null -> {
                var restoredResult = START_NOT_STICKY
                val handler = StickyRideRecoveryHandler(
                    store = rideStateStore,
                    startForegroundFirst = { params ->
                        val eligibility = evaluateContextEligibility(this, params.forceSimulated)
                        if (eligibility.shouldPromoteToForeground) {
                            val snapshot = controller.state.value.notification.copy(
                                title = "Restaurando rodada · Mesh activa",
                                contentText = "Reconstruyendo mesh BLE tras reinicio del sistema...",
                                isUsingSimulatedTransport = eligibility.effectiveForceSimulated
                            )
                            val promoted = promoteToForeground(snapshot, eligibility.foregroundServiceTypes)
                            if (promoted) {
                                isPromotedToForeground = true
                                acquireWakeLockIfNeeded()
                            }
                            promoted
                        } else {
                            true
                        }
                    },
                    restoreRide = { params ->
                        val manifestKey = decodeManifestKey(params.manifestKeyHex)
                        if (manifestKey != null) {
                            Log.w(TAG, "START_STICKY intent==null: restaurando rodada=${params.rideId}")
                            restoredResult = launchRestoredRideSession(params, manifestKey)
                        } else {
                            rideStateStore.clear()
                            stopSelf()
                        }
                    },
                    stopWithoutRide = {
                        Log.i(TAG, "START_STICKY intent==null sin rodada activa persistida: deteniendo servicio")
                        stopSelf()
                    }
                )
                handler.handleNullIntent()
                return restoredResult
            }
            ACTION_TRIGGER_SOS -> {
                serviceScope.launch {
                    controller.triggerSos(active = true)
                    _sharedState.value = controller.state.value
                }
            }
            ACTION_CANCEL_SOS -> {
                serviceScope.launch {
                    controller.triggerSos(active = false)
                    _sharedState.value = controller.state.value
                }
            }
            ACTION_STOP_RIDE -> {
                rideStartDeduplicator.reset()
                serviceScope.launch {
                    val state = controller.onStopActionFromNotification()
                    _sharedState.value = state
                    if (state.lifecycleState == RideLifecycleState.STOPPED) {
                        rideStateStore.clear()
                        fallDetector?.stop()
                        fallDetector = null
                        SosRepository.getInstance(applicationContext).clearAllOnRideFinished()
                        // R12: purga de fichas médicas en RAM al cerrar rodada
                        com.rutabikersync.app.core.mesh.BleMeshService.getActiveInstance()
                            ?.medicalSheetStore?.purgeAllOnRideClosed()
                        releaseWakeLockIfHeld()
                        if (isPromotedToForeground) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            isPromotedToForeground = false
                        }
                        stopSelf()
                    }
                }
            }
            ACTION_FORCE_STOP_RIDE -> {
                rideStartDeduplicator.reset()
                rideStateStore.clear()
                serviceScope.launch {
                    val state = controller.stopAll()
                    _sharedState.value = state
                    fallDetector?.stop()
                    fallDetector = null
                    SosRepository.getInstance(applicationContext).clearAllOnRideFinished()
                    // R12: purga de fichas médicas en RAM al forzar cierre
                    com.rutabikersync.app.core.mesh.BleMeshService.getActiveInstance()
                        ?.medicalSheetStore?.purgeAllOnRideClosed()
                    releaseWakeLockIfHeld()
                    if (isPromotedToForeground) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        isPromotedToForeground = false
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startOrRestoreRide(params: PersistedRideParams, manifestKey: ByteArray): Int {
        val eligibility = evaluateContextEligibility(this, params.forceSimulated)
        val effectiveForceSim = eligibility.effectiveForceSimulated
        val initialSnapshot = controller.state.value.notification.copy(
            title = "Iniciando rodada · Mesh activa",
            contentText = if (effectiveForceSim) {
                "Simulación en app activa (sin Foreground Service)..."
            } else {
                "Preparando mesh BLE real..."
            },
            isUsingSimulatedTransport = effectiveForceSim
        )
        if (eligibility.shouldPromoteToForeground) {
            val promoted = promoteToForeground(
                snapshot = initialSnapshot,
                fgsTypes = eligibility.foregroundServiceTypes
            )
            if (!promoted) {
                return START_NOT_STICKY
            }
            isPromotedToForeground = true
            acquireWakeLockIfNeeded()
        } else {
            isPromotedToForeground = false
            Log.i(TAG, "Starting RideService in simulated in-app mode (no Foreground Service)")
        }
        return launchRestoredRideSession(params, manifestKey)
    }

    private fun launchRestoredRideSession(params: PersistedRideParams, manifestKey: ByteArray): Int {
        activeManifestKey = manifestKey
        val eligibility = evaluateContextEligibility(this, params.forceSimulated)
        val effectiveForceSim = eligibility.effectiveForceSimulated
        val bleModeOverride = BleAdvTransport.TransportModeOverride.fromCode(params.bleModeOverrideCode)
        val txRxRole = BleAdvTransport.TxRxRole.fromCode(params.txRxRoleCode)
        val advertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.fromCode(params.advertisingDeliveryModeCode)

        SosRepository.getInstance(applicationContext).initRideCrypto()
        try {
            fallDetector?.stop()
            fallDetector = FallDetector(applicationContext) { triggerType ->
                Log.w(TAG, "Caída detectada por sensor inercial en rodada activa ($triggerType)")
                serviceScope.launch(Dispatchers.Main) {
                    SosCountdownManager.getInstance(applicationContext) {
                        serviceScope.launch {
                            controller.triggerSos(active = true)
                            _sharedState.value = controller.state.value
                        }
                    }.startCountdown(triggerType)
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar FallDetector: ${e.message}")
        }

        controller.recordStartError(null)
        seedLocalTelemetryFromCache()
        serviceScope.launch {
            // P0 auditoría 2026-11-25: la restauración ya no confía en la clave
            // persistida. Exige manifiesto verificado (memoria o disco) y que el
            // shortId local siga en el roster firmado; si no, fail-closed.
            val restoredAuth = com.rutabikersync.app.core.crypto.RideManifestKeyProvider
                .resolveOffline(params.rideId)
            if (restoredAuth == null || params.localShortId !in restoredAuth.authorizedShortIds) {
                Log.e(
                    TAG,
                    "Restauración rechazada: manifiesto no verificable sin red (ride=${params.rideId})"
                )
                controller.recordStartError("Manifiesto no verificable sin red")
                _sharedState.value = controller.state.value
                updateForegroundNotification(controller.state.value.notification)
                return@launch
            }
            val state = controller.startRide(
                rideId = params.rideId,
                localShortId = params.localShortId,
                rideSecretKey = manifestKey,
                forceSimulatedTransport = effectiveForceSim,
                bleModeOverride = bleModeOverride,
                advertisingBurstDurationMs = params.advertisingBurstDurationMs,
                expectedPeerShortIds = params.expectedPeerShortIds,
                relayEnabled = params.relayEnabled,
                txRxRole = txRxRole,
                unfilteredScanDiagnostic = params.unfilteredScanDiagnostic,
                advertisingDeliveryMode = advertisingDeliveryMode
            )
            _sharedState.value = state
            drainPendingLifecycleEvents()
            if (isPromotedToForeground) {
                updateForegroundNotification(state.notification)
            }
            val isExtended = bleModeOverride == null ||
                bleModeOverride == BleAdvTransport.TransportModeOverride.EXTENDED_1M ||
                bleModeOverride == BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY
            if (isExtended) {
                autoFallbackJob?.cancel()
                autoFallbackJob = serviceScope.launch {
                    delay(30_000)
                    val eng = controller.relayEngine
                    if (eng != null && eng.peers.value.isEmpty()) {
                        Log.w("RideService", "Auto-fallback BLE: sin peers en 30s, cambiando a LEGACY_1M")
                        runCatching { restartWithBleMode(BleAdvTransport.TransportModeOverride.LEGACY_1M) }
                    }
                }
            }
        }
        return START_STICKY
    }

    suspend fun restartWithBleMode(mode: BleAdvTransport.TransportModeOverride): RideSessionState {
        val current = _sharedState.value
        if (current.lifecycleState != RideLifecycleState.RUNNING) return current
        val rideId = current.rideId ?: PilotRideConfig.rideId
        val shortId = current.localShortId
        val expected = current.expectedPeerShortIds.map { it.toUShort() }.toSet()
        val isSim = current.isUsingSimulatedTransport
        val txRx = current.txRxRole
        val relay = current.relayEnabled
        val retainedKey = activeManifestKey ?: run {
            Log.e(TAG, "restartWithBleMode abortado: no hay clave del manifiesto en memoria")
            return current
        }
        controller.stopAll()
        delay(400)
        val state = controller.startRide(
            rideId = rideId,
            localShortId = shortId.toUShort(),
            rideSecretKey = retainedKey,
            forceSimulatedTransport = isSim,
            bleModeOverride = mode,
            expectedPeerShortIds = expected,
            relayEnabled = relay,
            txRxRole = txRx
        )
        _sharedState.value = state
        if (isPromotedToForeground) {
            updateForegroundNotification(state.notification)
        }
        return state
    }

    override fun onDestroy() {
        instance = null
        com.rutabikersync.app.core.audio.SosToneHub.stopAll()
        autoFallbackJob?.cancel()
        autoFallbackJob = null
        runCatching {
            val csv = controller.lastPacketsCsv ?: controller.relayEngine?.exportPacketsCsv()
            if (!csv.isNullOrBlank() && csv.lines().count { it.isNotBlank() } > 1) {
                globalLastPacketsCsv = csv
            }
        }
        if (activeController === controller) {
            activeController = null
        }
        fallDetector?.stop()
        fallDetector = null
        SosRepository.getInstance(applicationContext).clearAllOnRideFinished()
        serviceScope.launch {
            controller.stopAll()
            _sharedState.value = controller.state.value
        }
        isPromotedToForeground = false
        releaseWakeLockIfHeld()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(
        snapshot: RideNotificationSnapshot,
        fgsTypes: Int
    ): Boolean {
        val notification = buildAndroidNotification(snapshot)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "Promoting RideService to foreground with fgsType=0x${fgsTypes.toString(16)}")
                startForeground(NOTIFICATION_ID, notification, fgsTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse { error ->
            val msg = "No se pudo iniciar el servicio de rodada: ${error.javaClass.simpleName}: ${error.message}"
            Log.e(TAG, msg, error)
            val errState = controller.recordStartError(msg)
            _sharedState.value = errState
            val errorIntent = Intent(ACTION_RIDE_START_ERROR).apply {
                setPackage(packageName)
                putExtra(EXTRA_RIDE_START_ERROR_MESSAGE, msg)
            }
            sendBroadcast(errorIntent)
            stopSelf()
            false
        }
    }

    private fun updateForegroundNotification(snapshot: RideNotificationSnapshot) {
        if (snapshot.title.isBlank()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.notify(NOTIFICATION_ID, buildAndroidNotification(snapshot))
        }
    }

    private fun buildAndroidNotification(snapshot: RideNotificationSnapshot): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RideService::class.java).apply {
            action = ACTION_STOP_RIDE
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sosIntent = Intent(this, RideService::class.java).apply {
            action = ACTION_TRIGGER_SOS
        }
        val sosPending = PendingIntent.getService(
            this,
            102,
            sosIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopButtonLabel = if (snapshot.isConfirmingStop) {
            "Confirmar (3s)"
        } else {
            "Detener rodada"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    stopButtonLabel,
                    stopPending
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_dialog_alert),
                    "SOS",
                    sosPending
                ).build()
            )

        val showLockAction = runCatching {
            VaultRepository.getInstance(this).medicalSheetFlow.value.showOnLockScreen
        }.getOrDefault(false)

        if (showLockAction) {
            val lockIntent = EmergencyLockScreenActivity.createIntent(this)
            val lockPending = PendingIntent.getActivity(
                this,
                103,
                lockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_myplaces),
                    "Ficha Médica",
                    lockPending
                ).build()
            )
        }

        return builder.build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rodada Activa y Mesh BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene viva la mesh BLE y muestra riders conectados en tiempo real"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RutaBikerSync::ActiveRideMeshWakeLock"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) } // máx. 6 h por rodada
        }
    }

    private fun releaseWakeLockIfHeld() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }
}
