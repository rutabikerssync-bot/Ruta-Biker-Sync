package com.rutabikersync.app.core.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.rutabikersync.app.MainActivity
import com.rutabikersync.app.core.transport.BleAdvTransport
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

data class RideSessionState(
    val rideId: String? = null,
    val lifecycleState: RideLifecycleState = RideLifecycleState.IDLE,
    val localShortId: UShort = 1u,
    val transportName: String = "Idle",
    val isUsingSimulatedTransport: Boolean = false,
    val connectedRidersCount: Int = 0,
    val lostRidersCount: Int = 0,
    val isSosActive: Boolean = false,
    val stopRequestedAtEpochMs: Long? = null,
    val voiceClipsPurgedOnClose: Boolean = false,
    val emergencyAccessRevokedOnClose: Boolean = false,
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
    private val simulatedMedium: SimulatedBleMedium = SimulatedBleMedium.defaultMedium,
    private val onNotificationUpdated: ((RideNotificationSnapshot) -> Unit)? = null
) {
    companion object {
        const val STOP_CONFIRM_WINDOW_MS: Long = 3_000L
        const val HEARTBEAT_INTERVAL_MS: Long = MeshRelayEngine.HEARTBEAT_INTERVAL_MS
    }

    private val _state = MutableStateFlow(RideSessionState())
    val state: StateFlow<RideSessionState> = _state.asStateFlow()

    var relayEngine: MeshRelayEngine? = null
        private set

    private var activeTransport: Transport? = null
    private var heartbeatJob: Job? = null
    private var peerObserveJob: Job? = null
    private var stopConfirmTimeoutJob: Job? = null

    @Volatile
    private var localLatitude: Double = 19.4326
    @Volatile
    private var localLongitude: Double = -99.1332
    @Volatile
    private var localSpeedKmh: Int = 45
    @Volatile
    private var localBearingDegrees: Float = 90f
    @Volatile
    private var localBatteryPercent: Int = 85

    suspend fun startRide(
        rideId: String,
        localShortId: UShort = 1u,
        rideSecretKey: ByteArray = "rbs-phase3-default-ride-key-32b!".toByteArray(Charsets.UTF_8),
        forceSimulatedTransport: Boolean = false,
        enablePeriodicHeartbeatLoop: Boolean = true
    ): RideSessionState {
        stopAllInternal(resetToIdle = false)

        val (selectedTransport, isSimulated) = resolveTransport(localShortId, forceSimulatedTransport)
        activeTransport = selectedTransport

        val engine = MeshRelayEngine(
            localShortId = localShortId,
            transport = selectedTransport,
            rideSecretKey = rideSecretKey
        )
        relayEngine = engine
        engine.start(scope)

        val initialSnapshot = buildNotificationSnapshot(
            lifecycleState = RideLifecycleState.RUNNING,
            connectedRiders = 0,
            lostRiders = 0,
            transportName = selectedTransport.name,
            isSimulated = isSimulated,
            isSos = false,
            stopRemainingMs = 0L
        )
        _state.value = RideSessionState(
            rideId = rideId,
            lifecycleState = RideLifecycleState.RUNNING,
            localShortId = localShortId,
            transportName = selectedTransport.name,
            isUsingSimulatedTransport = isSimulated,
            connectedRidersCount = 0,
            lostRidersCount = 0,
            isSosActive = false,
            stopRequestedAtEpochMs = null,
            voiceClipsPurgedOnClose = false,
            emergencyAccessRevokedOnClose = false,
            notification = initialSnapshot
        )
        onNotificationUpdated?.invoke(initialSnapshot)

        // Observar cambios en los peers de la mesh para actualizar en tiempo real la notificación persistente
        peerObserveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.peers.collect { peerMap ->
                updateRealtimePeerCounts(peerMap)
            }
        }

        if (enablePeriodicHeartbeatLoop) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    emitHeartbeatNow()
                    engine.evaluatePeerTimeouts(System.currentTimeMillis())
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
        }

        return _state.value
    }

    fun updateLocalTelemetry(
        latitude: Double,
        longitude: Double,
        speedKmh: Int,
        bearingDegrees: Float = 0f,
        batteryPercent: Int = 85
    ) {
        localLatitude = latitude
        localLongitude = longitude
        localSpeedKmh = speedKmh
        localBearingDegrees = bearingDegrees
        localBatteryPercent = batteryPercent
    }

    suspend fun emitHeartbeatNow(nowEpochMs: Long = System.currentTimeMillis()) {
        val engine = relayEngine ?: return
        val current = _state.value
        if (current.lifecycleState != RideLifecycleState.RUNNING &&
            current.lifecycleState != RideLifecycleState.CONFIRMING_STOP
        ) {
            return
        }
        engine.emitLocalPosition(
            latitude = localLatitude,
            longitude = localLongitude,
            speedKmh = localSpeedKmh,
            bearingDegrees = localBearingDegrees,
            batteryPercent = localBatteryPercent,
            isSos = current.isSosActive
        )
        engine.evaluatePeerTimeouts(nowEpochMs)
        updateRealtimePeerCounts(engine.peers.value)
    }

    suspend fun triggerSos(active: Boolean = true) {
        val current = _state.value
        if (current.lifecycleState == RideLifecycleState.IDLE ||
            current.lifecycleState == RideLifecycleState.STOPPED
        ) {
            return
        }
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
            emitHeartbeatNow()
        }
    }

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
            connectedRidersCount = 0,
            lostRidersCount = 0,
            isSosActive = false,
            stopRequestedAtEpochMs = null,
            voiceClipsPurgedOnClose = true,
            emergencyAccessRevokedOnClose = true,
            notification = stoppedSnapshot
        )
        onNotificationUpdated?.invoke(stoppedSnapshot)
        return _state.value
    }

    private suspend fun stopAllInternal(resetToIdle: Boolean) {
        stopConfirmTimeoutJob?.cancel()
        stopConfirmTimeoutJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        peerObserveJob?.cancel()
        peerObserveJob = null
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
        forceSimulated: Boolean
    ): Pair<Transport, Boolean> {
        if (!forceSimulated) {
            val realBle = runCatching { bleTransportFactory?.invoke() }.getOrNull()
            if (realBle != null) {
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
        val activeConnected = peerMap.values.count { !it.isLost }
        val lostCount = peerMap.values.count { it.isLost }
        val snapshot = buildNotificationSnapshot(
            lifecycleState = current.lifecycleState,
            connectedRiders = activeConnected,
            lostRiders = lostCount,
            transportName = current.transportName,
            isSimulated = current.isUsingSimulatedTransport,
            isSos = current.isSosActive,
            stopRemainingMs = if (current.lifecycleState == RideLifecycleState.CONFIRMING_STOP) STOP_CONFIRM_WINDOW_MS else 0L
        )
        _state.value = current.copy(
            connectedRidersCount = activeConnected,
            lostRidersCount = lostCount,
            notification = snapshot
        )
        onNotificationUpdated?.invoke(snapshot)
    }

    private fun buildNotificationSnapshot(
        lifecycleState: RideLifecycleState,
        connectedRiders: Int,
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
                val lostSuffix = if (lostRiders > 0) " · $lostRiders sin señal" else ""
                "$connectedRiders riders conectados en la mesh$lostSuffix · $modeLabel"
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
}

class RideService : Service() {

    companion object {
        const val CHANNEL_ID = "rbs_active_ride_mesh_channel"
        const val NOTIFICATION_ID = 3201

        const val ACTION_START_RIDE = "com.rutabikersync.app.action.START_RIDE"
        const val ACTION_STOP_RIDE = "com.rutabikersync.app.action.STOP_RIDE"
        const val ACTION_TRIGGER_SOS = "com.rutabikersync.app.action.TRIGGER_SOS"

        const val EXTRA_RIDE_ID = "extra_ride_id"
        const val EXTRA_LOCAL_SHORT_ID = "extra_local_short_id"
        const val EXTRA_FORCE_SIMULATED = "extra_force_simulated"

        private val _sharedState = MutableStateFlow(RideSessionState())
        val sharedState: StateFlow<RideSessionState> = _sharedState.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): RideService = this@RideService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var controller: RideSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        controller = RideSessionController(
            scope = serviceScope,
            bleTransportFactory = { BleAdvTransport(applicationContext) },
            onNotificationUpdated = { snapshot ->
                _sharedState.value = controller.state.value
                updateForegroundNotification(snapshot)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RIDE -> {
                val rideId = intent.getStringExtra(EXTRA_RIDE_ID) ?: "ride-local-default"
                val shortId = intent.getIntExtra(EXTRA_LOCAL_SHORT_ID, 1).toUShort()
                val forceSim = intent.getBooleanExtra(EXTRA_FORCE_SIMULATED, false)
                acquireWakeLockIfNeeded()
                serviceScope.launch {
                    val state = controller.startRide(
                        rideId = rideId,
                        localShortId = shortId,
                        forceSimulatedTransport = forceSim
                    )
                    _sharedState.value = state
                    promoteToForeground(state.notification)
                }
            }
            ACTION_TRIGGER_SOS -> {
                serviceScope.launch {
                    controller.triggerSos(active = true)
                    _sharedState.value = controller.state.value
                }
            }
            ACTION_STOP_RIDE -> {
                serviceScope.launch {
                    val state = controller.onStopActionFromNotification()
                    _sharedState.value = state
                    if (state.lifecycleState == RideLifecycleState.STOPPED) {
                        releaseWakeLockIfHeld()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            controller.stopAll()
            _sharedState.value = controller.state.value
        }
        releaseWakeLockIfHeld()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(snapshot: RideNotificationSnapshot) {
        val notification = buildAndroidNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

        return builder
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
            .build()
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
