package com.rutabikersync.app.feature.livemap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import com.rutabikersync.app.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.rutabikersync.app.core.audio.AudioDuckingManager
import com.rutabikersync.app.core.audio.VoiceClipPlayerQueue
import com.rutabikersync.app.core.mesh.audio.PttAudioEngine
import com.rutabikersync.app.core.mesh.audio.PttState
import com.rutabikersync.app.ui.components.AppIcons
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.rutabikersync.app.feature.mesh.TacticalChatScreen
import com.rutabikersync.app.feature.mesh.MeshConvoyStatusScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.core.mesh.BleMeshService
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.feature.sos.MeshSosReceptionDialog
import com.rutabikersync.app.ui.components.HeritageStopRideHoldButton
import com.rutabikersync.app.ui.theme.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.annotations.PolylineOptions
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.feature.auth.ProfileRepository
import com.rutabikersync.app.feature.routes.DeviationStatus
import com.rutabikersync.app.feature.routes.LatLngPoint
import com.rutabikersync.app.feature.routes.RecalculateRouteOutcome
import com.rutabikersync.app.feature.routes.RecalculateRouteUseCase
import com.rutabikersync.app.feature.routes.RideScheduleRepository
import com.rutabikersync.app.feature.routes.RoadRoutingEngine
import com.rutabikersync.app.feature.routes.RouteDeviationDetector
import com.rutabikersync.app.feature.routes.RoutePolylineMeshRelay
import com.rutabikersync.app.feature.routes.StopReason
import com.rutabikersync.app.feature.sos.EmergencyRescuePacketDialog
import com.rutabikersync.app.feature.sos.FallDetector
import com.rutabikersync.app.feature.sos.SosActiveBanner
import com.rutabikersync.app.feature.sos.SosCountdownManager
import com.rutabikersync.app.feature.sos.SosCountdownOverlay
import com.rutabikersync.app.feature.sos.SosRepository
import com.rutabikersync.app.feature.sos.SosTriggerType
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.BorderStroke
import android.os.Build
import com.rutabikersync.app.core.mesh.sos.SosProximityEngine
import com.rutabikersync.app.core.mesh.sos.SosProximityAlert
import kotlin.math.roundToInt

/**
 * Pantalla `C1. Cabina de Rodada Activa` (SCREENS.md C1, DESIGN.md §2).
 * Fiel al diseño oficial Heritage Premium:
 * - Mapa satelital/topográfico dominante con trazo de convoy en azul eléctrico.
 * - Top HUD: 3 cápsulas flotantes (Líder a X m, Velocidad km/h, Batería %).
 * - Brújula circular norte a la derecha.
 * - Cápsulas flotantes sobre el mapa: Convoy (riders) y Seguir líder.
 * - Botonera inferior: 3 grandes botones circulares metálicos (Hablar PTT, Señal de ruta, SOS Emergencia).
 * - Control de parada: Cápsula con botón circular rojo '>' y parada protegida de 3 segundos.
 * - Pie de cabina: "Cabina de Rodada Activa - Pantalla completa · 3 acciones principales · Máxima seguridad".
 */
@Composable
fun LiveMapScreen(
    liveMapViewModel: LiveMapViewModel = viewModel(),
    onStopRideHoldCompleted: (() -> Unit)? = null,
    initialShowTacticalChat: Boolean = false,
    initialShowMeshMonitor: Boolean = false,
    initialFocusKeyboard: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by liveMapViewModel.uiState.collectAsState()
    val rideServiceState by RideService.sharedState.collectAsState()
    val simulatedAlert by com.rutabikersync.app.core.uxtest.UxTestTracker.simulatedAlertEvent.collectAsState()
    val uxParticipant by com.rutabikersync.app.core.uxtest.UxTestTracker.participantId.collectAsState()
    val uxLeaderId by com.rutabikersync.app.core.uxtest.UxTestTracker.leaderShortId.collectAsState()
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var showSignalsDialog by remember { mutableStateOf(false) }
    var showConvoyDialog by remember { mutableStateOf(false) }
    var showTacticalChat by remember { mutableStateOf(initialShowTacticalChat) }
    var showMeshMonitor by remember { mutableStateOf(initialShowMeshMonitor) }
    var reportingMarker by remember { mutableStateOf<RiderMarkerUiModel?>(null) }
    var reportReason by remember { mutableStateOf("dangerous") }

    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(rideServiceState.lifecycleState) {
        view.keepScreenOn = rideServiceState.lifecycleState == RideLifecycleState.RUNNING
        onDispose { view.keepScreenOn = false }
    }
    val duckingManager = remember(context) { AudioDuckingManager(context) }
    val voicePlayerQueue = remember(context) { VoiceClipPlayerQueue(context, duckingManager, coroutineScope) }
    val currentlyPlayingClip by voicePlayerQueue.currentlyPlaying.collectAsState()
    val playbackProgress by voicePlayerQueue.playbackProgress.collectAsState()
    val mutedSenders by voicePlayerQueue.mutedSenders.collectAsState()

    // Fix funcional PTT (Hito 7.0.3): el botón usa el MISMO MeshRelayEngine activo del
    // RideService. PttAudioEngine ya implementa AudioRecord → Opus → ChaCha20 → VoicePacket
    // y la reproducción con JitterBuffer + AudioTrack; aquí solo se cablean ambos extremos.
    val activeRelayEngine = RideService.getActiveRelayEngine()
    // El ID del engine activo es la fuente de verdad del nodo en la malla: los markers
    // pueden no tener aún el flag isSelf al entrar en la pantalla y caeríamos en el #1
    // en ambos teléfonos (evidencia de campo: VoicePacketRx sender=1 en TECNO y Redmi).
    val selfShortId = activeRelayEngine?.localShortId
        ?: state.markers.firstOrNull { it.isSelf }?.shortId
        ?: 1u
    val pttEngine = remember(selfShortId, activeRelayEngine?.clubNetworkId) {
        PttAudioEngine(
            scope = coroutineScope,
            clubNetworkId = activeRelayEngine?.clubNetworkId ?: 0x52425301u,
            localShortId = selfShortId,
            audioFocusController = null,
            onSendVoicePacket = { packet ->
                val engine = RideService.getActiveRelayEngine()
                if (engine?.emitVoicePacket(packet) != true) {
                    android.util.Log.w("LiveMapScreen", "VoicePacketTx abortado: no hay relayEngine activo")
                }
            }
        )
    }
    DisposableEffect(pttEngine) {
        onDispose { pttEngine.release() }
    }
    // El engine puede sustituirse al reiniciar BLE: re-suscribir la recepción de voz.
    DisposableEffect(rideServiceState.lifecycleState, rideServiceState.isUsingSimulatedTransport, pttEngine) {
        val unsubscribe = RideService.getActiveRelayEngine()?.onVoiceReceived { packet ->
            pttEngine.onVoicePacketReceived(packet)
            android.util.Log.i(
                "LiveMapScreen",
                "VoicePacketRx entregado al reproductor PTT frame=${packet.frameIndex}"
            )
        }
        onDispose { unsubscribe?.invoke() }
    }

    val pttState by pttEngine.pttState.collectAsState()
    val isRecording = pttState is PttState.Transmitting
    var recordingSeconds by remember { mutableIntStateOf(0) }

    // Auto-parada de 15 s y contador de la cápsula de transmisión.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000L)
                recordingSeconds++
                if (recordingSeconds >= PttAudioEngine.MAX_TALK_SECONDS) {
                    pttEngine.stopTalking()
                    RideService.endPttVoiceStream()
                    break
                }
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (RideService.getActiveRelayEngine() == null) {
                actionFeedback = "⚠ PTT no disponible: inicia la rodada para activar la malla"
            } else {
                RideService.beginPttVoiceStream()
                pttEngine.startTalking()
                com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent("LIVE_MAP", "PTT_TRANSMIT_START", "btn_hablar")
                actionFeedback = "🎙 TRANSMITIENDO PTT AL CONVOY (Opus 20 kbps · malla activa)"
            }
        } else {
            actionFeedback = "Se requiere permiso de micrófono para transmitir por PTT"
        }
    }

    val batteryManager = remember(context) { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    var realBatteryLevel by remember {
        mutableIntStateOf(
            runCatching {
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
            }.getOrNull() ?: 88
        )
    }

    // Sistema de Emergencias y Ficha de Rescate Médico (Fase 5 - R10.2, R10.3)
    val sosRepository = remember { SosRepository.getInstance(context) }
    val activeSosAlerts by sosRepository.activeAlerts.collectAsState()
    var viewingRescuePacket by remember { mutableStateOf<com.rutabikersync.app.feature.sos.EmergencyRescuePacket?>(null) }

    // Recepción de SOS por Malla BLE (Hito 4 · Etapa 4)
    val bleMeshService: BleMeshService? = remember { BleMeshService.getActiveInstance() }
    val incomingSosMeshEvent by (bleMeshService?.incomingSosMeshEvent
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    var showMeshSosDialog by remember { mutableStateOf<BleMeshService.IncomingSosMeshEvent?>(null) }

    LaunchedEffect(incomingSosMeshEvent) {
        incomingSosMeshEvent?.let { event ->
            showMeshSosDialog = event
            actionFeedback = "🚨 SOS MESH recibido de Piloto #${event.senderShortId.toInt()} (${event.hopCount} saltos)"
        }
    }

    // Radar SOS de Proximidad 5 km (Fase 7.1.5)
    var activeRadarSosAlert by remember { mutableStateOf<SosProximityAlert?>(null) }

    fun triggerMorseSosVibration(ctx: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        SosProximityEngine.SOS_MORSE_VIBRATION_PATTERN,
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(
                    SosProximityEngine.SOS_MORSE_VIBRATION_PATTERN,
                    -1
                )
            }
        } catch (_: Exception) {}
    }

    // Monitoreo del Radar SOS de proximidad (telemetría + radar 5 km)
    LaunchedEffect(Unit) {
        val client = com.rutabikersync.app.core.network.ClubApiClient.getInstance()
        while (true) {
            try {
                val currentLat = liveMapViewModel.uiState.value.cameraLatitude
                val currentLng = liveMapViewModel.uiState.value.cameraLongitude

                // 1. Emitir telemetría efímera si el usuario está autenticado
                if (currentLat != 0.0 && currentLng != 0.0 && (client.accessToken != null || client.currentUserId != null)) {
                    client.sendTelemetryHeartbeat(currentLat, currentLng)
                }

                // 2. Consultar alertas SOS cercanas activas (< 5 km)
                if (client.accessToken != null || client.currentUserId != null) {
                    val res = client.getNearbySosAlerts(currentLat, currentLng, 5.0)
                    res.getOrNull()?.body?.let { jsonBody ->
                        val alertsArray = jsonBody["sosAlerts"] as? kotlinx.serialization.json.JsonArray
                        if (alertsArray != null && alertsArray.isNotEmpty()) {
                            val first = alertsArray[0] as? kotlinx.serialization.json.JsonObject
                            if (first != null) {
                                val sId = (first["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                                val uId = (first["userId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                                val alias = (first["emitterAlias"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Compañero Motero"
                                val sLat = (first["latitude"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: currentLat
                                val sLng = (first["longitude"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: currentLng
                                val dKm = (first["distanceKm"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.8
                                val brg = (first["bearing"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Noreste ↗"
                                val hasMed = (first["hasMedicalSheet"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

                                if (activeRadarSosAlert == null || activeRadarSosAlert?.sosId != sId) {
                                    activeRadarSosAlert = SosProximityAlert(
                                        sosId = sId,
                                        userId = uId,
                                        emitterAlias = alias,
                                        latitude = sLat,
                                        longitude = sLng,
                                        distanceKm = dKm,
                                        bearing = brg,
                                        hasMedicalSheet = hasMed
                                    )
                                    triggerMorseSosVibration(context)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(10_000L)
        }
    }

    val incomingSosAckEvent by (bleMeshService?.incomingSosAckEvent
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()

    LaunchedEffect(incomingSosAckEvent) {
        incomingSosAckEvent?.let { ack ->
            bleMeshService?.stopSosBroadcaster()
            actionFeedback = "✅ SOS confirmado por Líder #${ack.ackerShortId.toInt()}"
        }
    }

    val sosCountdownManager = remember(context) {
        SosCountdownManager(
            context = context,
            coroutineScope = coroutineScope,
            onSosConfirmed = { trigger ->
                val selfMarker = state.markers.firstOrNull { it.isSelf }
                RideService.dispatchTriggerSos(context, true)
                sosRepository.emitLocalSos(
                    senderShortId = selfMarker?.shortId ?: 1u,
                    senderName = selfMarker?.fullName ?: "Mi Piloto",
                    senderRole = selfMarker?.rideRole?.name ?: "LÍDER",
                    lat = liveMapViewModel.uiState.value.cameraLatitude,
                    lon = liveMapViewModel.uiState.value.cameraLongitude,
                    triggerType = trigger
                )
                liveMapViewModel.controller.updateLocalRiderPose(
                    latitude = liveMapViewModel.uiState.value.cameraLatitude,
                    longitude = liveMapViewModel.uiState.value.cameraLongitude,
                    speedKmh = 0,
                    batteryPercent = realBatteryLevel,
                    isSos = true
                )
                liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
                bleMeshService?.emitLocalSosPacket(
                    latitude = liveMapViewModel.uiState.value.cameraLatitude,
                    longitude = liveMapViewModel.uiState.value.cameraLongitude
                )
                actionFeedback = "🚨 ¡SOS ACTIVADO! Posición y ficha médica transmitidos al convoy"
            }
        )
    }
    val sosCountdownState by sosCountdownManager.countdownState.collectAsState()

    // Sistema de Navegación Offline y Detección de Desvíos (Fase 4 - P1)
    val scheduledRide by RideScheduleRepository.currentRide.collectAsState()
    val deviationState by RouteDeviationDetector.deviationState.collectAsState()
    val lastReceivedMeshRoute by RoutePolylineMeshRelay.lastReceivedRoute.collectAsState()
    var showStopReasonDialog by remember { mutableStateOf(false) }
    var showOfflineMapsScreen by remember { mutableStateOf(false) }
    var isRecalculatingRoute by remember { mutableStateOf(false) }

    // Recalcular ruta desde el banner de desvío: la red se consulta al pulsar para no
    // confiar en un estado cacheado, y el motor vial se inyecta sin tocar malla/GATT.
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val isOnline = remember(context) {
        {
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    }
    val recalculateRouteUseCase = remember(context) {
        RecalculateRouteUseCase(
            isOnline = isOnline,
            fetchRoadRoute = { origin, destination, destinationName ->
                RoadRoutingEngine.fetchRealRoadRoute(origin, destination, destinationName)
            }
        )
    }

    LaunchedEffect(scheduledRide) {
        scheduledRide?.selectedRoute?.waypoints?.let {
            RouteDeviationDetector.setActiveRoutePolyline(it)
        }
    }

    val fallDetector = remember(context) {
        FallDetector(context) { trigger ->
            sosCountdownManager.startCountdown(trigger)
            actionFeedback = "⚠ ¡IMPACTO O CAÍDA DETECTADA! Iniciando cuenta regresiva de 20 s"
        }
    }

    DisposableEffect(context) {
        fallDetector.start()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    realBatteryLevel = (level * 100 / scale.toFloat()).roundToInt()
                } else {
                    val cap = runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull() ?: -1
                    if (cap in 1..100) realBatteryLevel = cap
                }
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            fallDetector.stop()
            sosCountdownManager.resetToIdle()
            runCatching { context.unregisterReceiver(receiver) }
            pttEngine.stopTalking()
            RideService.endPttVoiceStream()
            voicePlayerQueue.discardAll()
        }
    }

    LaunchedEffect(rideServiceState.lifecycleState) {
        if (rideServiceState.lifecycleState == RideLifecycleState.RUNNING) {
            val engine = RideService.getActiveRelayEngine()
            if (engine != null) {
                liveMapViewModel.controller.bindToMeshEngine(engine, enablePeriodicCadenceLoop = true)
            }
            val selfMarker = state.markers.firstOrNull { it.isSelf }
            val isLeader = (selfMarker?.rideRole == com.rutabikersync.app.feature.livemap.RiderRideRole.LEADER) ||
                (uxLeaderId == selfMarker?.shortId)
            if (isLeader) {
                bleMeshService?.medicalSheetStore?.setRescueContext(true, BleMeshService.DEFAULT_DEMO_RESCUE_KEY)
            } else {
                bleMeshService?.medicalSheetStore?.setRescueContext(false, null)
                val clinicalData = com.rutabikersync.app.core.mesh.sos.MedicalRescueData(
                    bloodType = "O+",
                    allergies = "Penicilina",
                    medication = "Ninguna",
                    conditions = "Ninguna",
                    insurancePolicy = "GMX-98231-M",
                    iceName = "Laura Esposa",
                    icePhone = "+525512345678"
                )
                bleMeshService?.emitLocalMedicalSheetViaMesh(
                    rescueKey = BleMeshService.DEFAULT_DEMO_RESCUE_KEY,
                    rideId = java.util.UUID.randomUUID(),
                    clinicalData = clinicalData,
                    ownerShortId = selfMarker?.shortId ?: 2u,
                    seqNum = 1u
                )
            }
        }
    }

    BackHandler(enabled = true) {
        if (rideServiceState.lifecycleState == RideLifecycleState.RUNNING) {
            actionFeedback = "⚠ Para finalizar la rodada mantén presionado 3 s el botón inferior"
        } else {
            onStopRideHoldCompleted?.invoke()
        }
    }

    LaunchedEffect(uxParticipant, uxLeaderId) {
        val localShortId = com.rutabikersync.app.core.uxtest.UxTestTracker.participantToShortId(uxParticipant)
        liveMapViewModel.controller.updateConvoyIds(localId = localShortId, targetLeaderId = uxLeaderId)
    }

    // Bloque B: perfil cacheado (offline) y roster backend (cuando hay UUID de rodada).
    val localProfileRepository = remember { ProfileRepository(context.applicationContext) }
    val localProfile = remember { localProfileRepository.getUserProfile() }
    LaunchedEffect(localProfile) {
        localProfile?.let { profile ->
            liveMapViewModel.controller.applyCachedLocalProfile(
                fullName = profile.aliasBiker,
                avatarBase64 = profile.photoBase64,
                avatarUrl = profile.photoUrl,
                pinColorHex = profile.pinColorHex,
                userId = profile.uid
            )
        }
    }
    LaunchedEffect(rideServiceState.rideId, localProfile?.uid) {
        val rideId = rideServiceState.rideId
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        // La rodada de prueba usa "ride-live-01": el backend exige UUID, así que se omite
        // la petición y se conservan los fallbacks locales sin lanzar errores.
        if (rideId.isNullOrBlank() || !uuidPattern.matches(rideId)) return@LaunchedEffect
        val api = ClubApiClient.getInstance().apply { initContext(context.applicationContext) }
        val response = api.fetchRideRoster(rideId).getOrNull() ?: return@LaunchedEffect
        if (!response.isSuccess) return@LaunchedEffect
        val entries = response.body?.let { parseRideRosterUi(it) }.orEmpty()
        if (entries.isNotEmpty()) {
            liveMapViewModel.controller.applyBackendRoster(entries, localProfile?.uid)
        }
    }

    LaunchedEffect(simulatedAlert) {
        simulatedAlert?.let {
            actionFeedback = it
        }
    }

    val geoPrefs = remember { context.getSharedPreferences("rbs_geo_cache", Context.MODE_PRIVATE) }

    fun applyLocation(loc: android.location.Location) {
        geoPrefs.edit()
            .putFloat("last_known_lat", loc.latitude.toFloat())
            .putFloat("last_known_lon", loc.longitude.toFloat())
            .apply()
        liveMapViewModel.controller.corridorReferenceLon = loc.longitude
        liveMapViewModel.controller.updateLocalRiderPose(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).roundToInt() else 0,
            bearingDegrees = if (loc.hasBearing()) loc.bearing else 180f,
            batteryPercent = realBatteryLevel
        )
        // La malla necesita la misma posición real para que los peers se dibujen
        // sobre la vía. Antes los heartbeats emitían (0,0) y el remoto se veía
        // desviado a miles de km. No se inventa nada: viene del GPS/caché real.
        RideService.updateActiveLocalTelemetry(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).roundToInt() else 0,
            bearingDegrees = if (loc.hasBearing()) loc.bearing else 180f,
            batteryPercent = realBatteryLevel
        )
        RouteDeviationDetector.updateRiderLocation(
            location = LatLngPoint(loc.latitude, loc.longitude),
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
        )
        liveMapViewModel.controller.centerOnMyLocation()
        liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val candidateProviders = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (p in candidateProviders) {
                val l = runCatching { locManager?.getLastKnownLocation(p) }.getOrNull()
                if (l != null) {
                    applyLocation(l)
                    break
                }
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // 1. Cargar de inmediato la última ubicación real conocida guardada en caché (cualquier país del mundo)
        if (geoPrefs.contains("last_known_lat") && geoPrefs.contains("last_known_lon")) {
            val cachedLat = geoPrefs.getFloat("last_known_lat", 0f).toDouble()
            val cachedLon = geoPrefs.getFloat("last_known_lon", 0f).toDouble()
            if (cachedLat != 0.0 && cachedLon != 0.0) {
                liveMapViewModel.controller.updateLocalRiderPose(
                    latitude = cachedLat,
                    longitude = cachedLon,
                    speedKmh = 0,
                    bearingDegrees = 180f,
                    batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent
                )
                RideService.updateActiveLocalTelemetry(
                    latitude = cachedLat,
                    longitude = cachedLon,
                    speedKmh = 0,
                    bearingDegrees = 180f,
                    batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent
                )
                liveMapViewModel.controller.centerOnMyLocation()
                liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
            }
        }

        if (fineGranted || coarseGranted) {
            // 2. Consultar todos los proveedores de geolocalización activos
            runCatching {
                val candidateProviders = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                var best: android.location.Location? = null
                for (p in candidateProviders) {
                    val l = runCatching { locManager?.getLastKnownLocation(p) }.getOrNull()
                    if (l != null && (best == null || l.time > best.time)) {
                        best = l
                    }
                }
                best?.let { applyLocation(it) }
            }

            // 3. Suscribirse a actualizaciones en tiempo real del GPS
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    applyLocation(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { providerName ->
                runCatching {
                    locManager?.requestLocationUpdates(providerName, 1000L, 1f, listener)
                }
            }

            onDispose {
                runCatching { locManager?.removeUpdates(listener) }
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            onDispose {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // 1. Lienzo de mapa MapLibre (estilo oscuro PMTiles offline + trazo de convoy)
        // M8: Variables de rol elevadas al scope del Box para acceso desde HUD y cápsulas
        val selfMarker = state.markers.firstOrNull { it.isSelf }
        val currentLeaderPin = state.leaderPin
        val isLeader = (selfMarker?.rideRole == com.rutabikersync.app.feature.livemap.RiderRideRole.LEADER) ||
            (uxLeaderId == selfMarker?.shortId) ||
            (selfMarker != null && currentLeaderPin != null && selfMarker.shortId == currentLeaderPin.leaderShortId)

        MapLibreCorridorSurface(
            state = state,
            selfShortId = selfShortId,
            onSelectRider = { shortId -> liveMapViewModel.controller.selectRiderMarker(shortId) },
            modifier = Modifier.fillMaxSize()
        )

        // 2. HUD Superior Flotante: 3 cápsulas redondeadas independientes
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(
                    top = if (BuildConfig.UX_TEST_MODE) 46.dp else 16.dp,
                    start = 12.dp,
                    end = 12.dp
                )
        ) {
            // Cápsula 1: Líder con rol inteligente y distancia real

            val leaderDistText = LeaderDistanceFormatter.format(
                meters = selfMarker?.distanceToLeaderMeters,
                isLeader = isLeader,
                hasGpsFix = state.gpsFixed
            )
            val leaderCapsuleText = when {
                isLeader -> "👑 Eres Líder"
                leaderDistText == null -> "Sin líder en rango"
                else -> "Líder a $leaderDistText ↗"
            }

            HudStatusChip(
                icon = Icons.Default.Shield,
                iconTint = if (isLeader) Color(0xFFF59E0B) else Color(0xFF10B981),
                text = leaderCapsuleText,
                onClick = {
                    if (isLeader) {
                        actionFeedback = "👑 Eres el Líder y Guía del Convoy"
                    } else {
                        liveMapViewModel.controller.followLeader(
                            nowEpochMs = System.currentTimeMillis(),
                            scheduleAutoRevert = true
                        )
                        actionFeedback = if (leaderDistText == null) {
                            "Siguiendo posición del líder"
                        } else {
                            "Siguiendo posición del líder ($leaderDistText)"
                        }
                    }
                }
            )

            // Cápsula 2: Velocidad actual en km/h (GPS o crucero en simulación activa)
            val selfSpeed = selfMarker?.speedKmh ?: 0
            val isSimulatedRide = rideServiceState.isUsingSimulatedTransport
            val effectiveSpeed = if (selfSpeed > 0) {
                selfSpeed
            } else if (isSimulatedRide && rideServiceState.lifecycleState == RideLifecycleState.RUNNING) {
                48
            } else {
                0
            }

            HudStatusChip(
                icon = Icons.Default.Speed,
                iconTint = if (effectiveSpeed > 0) Color(0xFF10B981) else Color(0xFF9CA3AF),
                text = "$effectiveSpeed km/h",
                onClick = {
                    actionFeedback = if (effectiveSpeed > 0) {
                        "Velocidad de marcha: $effectiveSpeed km/h"
                    } else {
                        "Velocidad: 0 km/h · Detenido (GPS activo)"
                    }
                }
            )

            // Cápsula 3: Nivel de batería real del dispositivo
            val battery = realBatteryLevel.coerceIn(1, 100)
            HudStatusChip(
                icon = AppIcons.BatteryStd,
                iconTint = when {
                    battery <= 15 -> Color(0xFFEF4444)
                    battery <= 30 -> Color(0xFFEAB308)
                    else -> Color(0xFF10B981)
                },
                text = "$battery%",
                onClick = {
                    actionFeedback = "Batería del dispositivo: $battery% disponible"
                }
            )
        }

        // 2.5 Badge de Corredor Offline PMTiles a la izquierda
        val nearestOfflineCorridor = remember(state.cameraLatitude, state.cameraLongitude) {
            com.rutabikersync.app.feature.routes.CorridorOfflineManager.findNearestOfflineCorridor(
                state.cameraLatitude,
                state.cameraLongitude,
                context
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = if (BuildConfig.UX_TEST_MODE) 102.dp else 74.dp, start = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF16181F).copy(alpha = 0.92f))
                .border(
                    1.2.dp,
                    if (nearestOfflineCorridor != null) EmeraldMesh else Color(0xFF353945),
                    RoundedCornerShape(20.dp)
                )
                .clickable { showOfflineMapsScreen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (nearestOfflineCorridor != null) EmeraldMesh else Color(0xFF9CA3AF))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (nearestOfflineCorridor != null) "OFFLINE: ${nearestOfflineCorridor.name}" else "MAPAS OFFLINE",
                    color = if (nearestOfflineCorridor != null) EmeraldMesh else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 3. Brújula circular norte flotante a la derecha
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (BuildConfig.UX_TEST_MODE) 102.dp else 74.dp, end = 16.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF16181F).copy(alpha = 0.92f))
                .border(1.2.dp, Color(0xFF353945), CircleShape)
                .clickable { liveMapViewModel.controller.centerOnMyLocation() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Norte",
                    tint = Color(0xFFD92D35),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "N",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    lineHeight = 11.sp
                )
            }
        }

        // 4. Contenedor inferior de Cabina de Rodada Activa (con gradiente oscuro ascendente)
        val isDarkTheme = HeritageTheme.colors.isDark
        val cockpitBgColors = if (isDarkTheme) {
            listOf(
                Color.Transparent,
                DarkSlate950.copy(alpha = 0.85f),
                DarkSlate950.copy(alpha = 0.98f),
                DarkSlate950
            )
        } else {
            listOf(
                Color.Transparent,
                HeritageSand.copy(alpha = 0.85f),
                HeritageSand.copy(alpha = 0.98f),
                HeritageSand
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(cockpitBgColors))
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 20.dp)
        ) {
            // Cápsulas intermedias sobre el mapa: Convoy (izquierda) y Seguir líder (derecha)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Cápsula izquierda: Convoy + cantidad de riders (Clickable para silenciar y reportar riders R13)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkCardElevated.copy(alpha = 0.95f))
                        .border(1.2.dp, DarkBorderProminent, RoundedCornerShape(14.dp))
                        .clickable { showConvoyDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Convoy",
                        tint = HeritageCopper,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Convoy",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp
                        )
                        Text(
                            text = "${state.connectedNodesCount.coerceAtLeast(1)} riders",
                            color = HeritageLeatherMuted,
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.sp,
                            lineHeight = 11.sp
                        )
                    }
                }

                if (!isLeader && currentLeaderPin != null) {
                    // Cápsula derecha: Botón "Seguir líder"
                    val isFollowing = state.cameraMode == CameraMode.FOLLOWING_LEADER
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isFollowing) HeritageCopper else HeritageCardWarm.copy(alpha = 0.95f)
                            )
                            .border(
                                1.2.dp,
                                if (isFollowing) HeritageCopperDark else HeritageBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                                    "LIVE_MAP",
                                    "TAP_FOLLOW_LEADER",
                                    "btn_seguir_lider"
                                )
                                liveMapViewModel.controller.followLeader(
                                    nowEpochMs = System.currentTimeMillis(),
                                    scheduleAutoRevert = true
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = "Seguir líder",
                            tint = if (isFollowing) Color.White else HeritageLeatherBrown,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isFollowing) "Líder (${state.followLeaderRemainingSeconds}s)" else "Seguir líder",
                            color = if (isFollowing) Color.White else HeritageLeatherBrown,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Feedback o aviso de acciones tácticas (Ticker dinámico de alta visibilidad)
            actionFeedback?.let { feedback ->
                var isMarqueeActive by remember(feedback) { mutableStateOf(true) }

                LaunchedEffect(feedback) {
                    isMarqueeActive = true
                    kotlinx.coroutines.delay(60_000L) // Se desplaza repetidamente durante 1 minuto
                    isMarqueeActive = false // Al cumplirse el minuto, queda estático hasta nuevo texto
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    DarkSlate900.copy(alpha = 0.96f),
                                    DarkCardElevated.copy(alpha = 0.96f),
                                    DarkSlate900.copy(alpha = 0.96f)
                                )
                            )
                        )
                        .border(1.5.dp, Color(0xFFE5B869), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = feedback,
                        color = Color(0xFFFFD580),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isMarqueeActive) {
                                    Modifier.basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        repeatDelayMillis = 1000,
                                        velocity = 45.dp
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }

            // Banner dinámico: Emergencia SOS activa en el convoy
            val topSosAlert = activeSosAlerts.firstOrNull()
            if (topSosAlert != null) {
                val selfId = state.markers.firstOrNull { it.isSelf }?.shortId ?: 1u
                SosActiveBanner(
                    alertEvent = topSosAlert,
                    isSelf = topSosAlert.senderShortId == selfId,
                    onViewPacketClick = {
                        topSosAlert.rescuePacket?.let { packet ->
                            sosRepository.logAccessToEmergencyPacket(
                                sosId = topSosAlert.sosId,
                                viewerUserId = "u-self",
                                viewerUserName = state.markers.firstOrNull { it.isSelf }?.fullName ?: "Mi Piloto",
                                targetRiderName = topSosAlert.senderName
                            )
                            viewingRescuePacket = packet
                        }
                    },
                    onResolveClick = {
                        sosRepository.resolveSos(topSosAlert.sosId)
                        RideService.dispatchTriggerSos(context, false)
                        bleMeshService?.stopSosBroadcaster()
                        liveMapViewModel.controller.updateLocalRiderPose(
                            latitude = liveMapViewModel.uiState.value.cameraLatitude,
                            longitude = liveMapViewModel.uiState.value.cameraLongitude,
                            speedKmh = 45,
                            batteryPercent = realBatteryLevel,
                            isSos = false
                        )
                        actionFeedback = "✓ Emergencia SOS resuelta"
                    }
                )
            } else if (rideServiceState.isSosActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFB91C1C))
                        .border(1.dp, Color(0xFFFF4D4D), RoundedCornerShape(8.dp))
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🚨 ALERTA SOS ACTIVA · TRANSMITIENDO A TODOS",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            }

            // Banner dinámico: Transmisión PTT en curso
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF064E3B))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(10.dp))
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎙 TRANSMITIENDO PTT AL CONVOY (${recordingSeconds}s / 15s · Opus)",
                        color = Color(0xFFA7F3D0),
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                }
            }

            // Indicador visual de recepción PTT (evidencia en hardware del par RX).
            (pttState as? PttState.Receiving)?.let { incoming ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0C4A6E))
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔊 Recibiendo voz de Piloto #${incoming.senderShortId} · ${incoming.packetsReceived} tramas",
                        color = Color(0xFFBAE6FD),
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                }
            }

            // Banner dinámico: Clip de voz entrante con ducking activo
            currentlyPlayingClip?.let { incoming ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141926))
                        .border(1.2.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF242732))
                                .border(1.2.dp, Color(0xFFE5B869), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = incoming.senderName.take(2).uppercase(),
                                color = Color(0xFFE5B869),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${incoming.senderName} (${incoming.senderRole})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "🔊 Escuchando en casco · Atenuando música (${incoming.durationSeconds}s)",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${(playbackProgress * 100).toInt()}%",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF7F1D1D))
                                    .clickable {
                                        voicePlayerQueue.toggleMuteSender(incoming.senderShortId)
                                        actionFeedback = "🔇 Silenciado localmente a ${incoming.senderName} (RULES R13.3)"
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Silenciar",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Banner de Emergencia: Radar SOS de Proximidad 5 km (Fase 7.1.5)
            activeRadarSosAlert?.let { alert ->
                val infiniteTransition = rememberInfiniteTransition(label = "SosPulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "SosPulseAlpha"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            1.5.dp,
                            Color(0xFFEF4444).copy(alpha = pulseAlpha),
                            RoundedCornerShape(12.dp)
                        ),
                    color = Color(0xFF1E1418),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "🚨",
                                    fontSize = 20.sp,
                                    modifier = Modifier.graphicsLayer { alpha = pulseAlpha }
                                )
                                Column {
                                    Text(
                                        text = SosProximityEngine.formatAlert(alert.distanceKm, alert.bearing),
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Piloto: ${alert.emitterAlias}${if (alert.hasMedicalSheet) " · 🩺 Ficha disponible" else ""}",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            IconButton(
                                onClick = { activeRadarSosAlert = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("✕", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    liveMapViewModel.controller.updateLocalRiderPose(
                                        latitude = alert.latitude,
                                        longitude = alert.longitude,
                                        speedKmh = 0,
                                        batteryPercent = realBatteryLevel
                                    )
                                    liveMapViewModel.controller.centerOnMyLocation()
                                    liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
                                    actionFeedback = "🧭 Rumbo de auxilio trazado hacia ${alert.emitterAlias} (${alert.distanceKm} km)"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "🧭 ACUDIR AL RESCATE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            if (alert.hasMedicalSheet) {
                                OutlinedButton(
                                    onClick = {
                                        actionFeedback = "🩺 Ficha Médica de emergencia de ${alert.emitterAlias}"
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "🩺 FICHA",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Banner dinámico: Alerta de desvío de ruta (> 150m) o parada voluntaria (MD3 High Contrast)
            if (deviationState.isAlertActive) {
                OffRouteBanner(
                    distanceMeters = deviationState.distanceToRouteMeters,
                    isRecalculating = isRecalculatingRoute,
                    onRecalculate = {
                        if (isRecalculatingRoute) return@OffRouteBanner
                        isRecalculatingRoute = true
                        coroutineScope.launch {
                            val outcome = recalculateRouteUseCase(
                                currentLocation = deviationState.lastKnownLocation,
                                destination = scheduledRide?.destination
                            )
                            actionFeedback = when (outcome) {
                                RecalculateRouteOutcome.SUCCESS -> "✓ Ruta recalculada sobre vías reales"
                                RecalculateRouteOutcome.OFFLINE -> "Sin conexión, usando ruta offline"
                                RecalculateRouteOutcome.NO_ROUTE -> "No se pudo recalcular. Sigue la ruta actual"
                            }
                            isRecalculatingRoute = false
                        }
                    },
                    onStop = { showStopReasonDialog = true }
                )
            } else if (deviationState.status == DeviationStatus.VOLUNTARY_STOP) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF064E3B))
                        .border(1.2.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(deviationState.stopReason?.emoji ?: "☕", fontSize = 18.sp)
                            Column {
                                Text(
                                    text = "PARADA: ${deviationState.stopReason?.label ?: "Voluntaria"}",
                                    color = Color(0xFFA7F3D0),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Alerta silenciada · Auto-reanuda al avanzar",
                                    color = Color(0xFF6EE7B7),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        androidx.compose.material3.Button(
                            onClick = {
                                RouteDeviationDetector.resumeRouteTracking()
                                actionFeedback = "✓ Monitoreo de ruta reanudado"
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Reanudar", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Banner dinámico: Nueva ruta compartida por la malla (Hash verificado)
            lastReceivedMeshRoute?.let { meshRoute ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2838))
                        .border(1.2.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📡", fontSize = 18.sp)
                            Column {
                                Text(
                                    text = "Ruta por Malla (Hash verificado)",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = meshRoute.route.name,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        androidx.compose.material3.Button(
                            onClick = {
                                RouteDeviationDetector.setActiveRoutePolyline(meshRoute.route.waypoints)
                                actionFeedback = "✓ Ruta de malla cargada en cabina: ${meshRoute.route.name}"
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cargar", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 4.5. Botón flotante Monitor de Malla (esquina inferior derecha, encima del botón SOS)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(HeritageCardWarm.copy(alpha = 0.95f))
                        .border(1.2.dp, EmeraldMesh, RoundedCornerShape(14.dp))
                        .clickable { showMeshMonitor = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("📡", fontSize = 14.sp)
                    Text(
                        text = "MONITOR MALLA",
                        color = EmeraldMesh,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // 5. Las 3 acciones circulares de cabina Heritage (≥ 72 dp)
            GlovesPrimaryActionBar(
                isSosActive = rideServiceState.isSosActive,
                isPttActive = isRecording,
                pttSecondaryText = if (isRecording) "${recordingSeconds}s/15s" else null,
                onTalkPttClick = {
                    if (isRecording) {
                        pttEngine.stopTalking()
                        RideService.endPttVoiceStream()
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            "LIVE_MAP",
                            "PTT_TRANSMIT_STOP",
                            "btn_hablar"
                        )
                        val frames = (pttState as? PttState.Transmitting)?.packetsSent
                            ?: pttEngine.voicePacketsSent.get()
                        actionFeedback = "✅ PTT enviado al convoy por la malla activa ($frames tramas Opus cifradas)"
                    } else if (RideService.getActiveRelayEngine() == null) {
                        actionFeedback = "⚠ PTT no disponible: inicia la rodada para activar la malla"
                    } else {
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMicPermission) {
                            RideService.beginPttVoiceStream()
                            pttEngine.startTalking()
                            com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                                "LIVE_MAP",
                                "PTT_TRANSMIT_START",
                                "btn_hablar"
                            )
                            actionFeedback = "🎙 TRANSMITIENDO PTT AL CONVOY (Opus 20 kbps · malla activa)"
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onSosClick = {
                    val selfId = state.markers.firstOrNull { it.isSelf }?.shortId ?: 1u
                    val isCurrentlyActive = rideServiceState.isSosActive || activeSosAlerts.any { it.senderShortId == selfId }

                    if (isCurrentlyActive) {
                        activeSosAlerts.firstOrNull { it.senderShortId == selfId }?.let {
                            sosRepository.resolveSos(it.sosId)
                        }
                        RideService.dispatchTriggerSos(context, false)
                        bleMeshService?.stopSosBroadcaster()
                        liveMapViewModel.controller.updateLocalRiderPose(
                            latitude = liveMapViewModel.uiState.value.cameraLatitude,
                            longitude = liveMapViewModel.uiState.value.cameraLongitude,
                            speedKmh = 48,
                            batteryPercent = realBatteryLevel,
                            isSos = false
                        )
                        liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
                        actionFeedback = "✓ Emergencia SOS desactivada"
                    } else {
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            screen = "LIVE_MAP",
                            action = "START_SOS_COUNTDOWN",
                            target = "btn_sos"
                        )
                        sosCountdownManager.startCountdown(SosTriggerType.MANUAL)
                    }
                },
                onQuickSignalsClick = {
                    com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                        "LIVE_MAP",
                        "TAP_SIGNALS",
                        "btn_senales"
                    )
                    showSignalsDialog = true
                }
            )

            // 6. Control de parada protegida de 3 segundos (cápsula con botón circular rojo y chevron '>')
            HeritageStopRideHoldButton(
                onHoldCompleted = {
                    com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                        "LIVE_MAP",
                        "STOP_RIDE_HOLD_3S",
                        "btn_detener_rodada_3s"
                    )
                    RideService.dispatchImmediateStopRide(context)
                    onStopRideHoldCompleted?.invoke()
                    actionFeedback = "Rodada finalizada. Guardando métricas..."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

        }

        if (showSignalsDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSignalsDialog = false },
                containerColor = HeritageSand,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📢", fontSize = 18.sp)
                        Text(
                            text = "SEÑALES RÁPIDAS DE CONVOY",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Acceso Directo al Chat Táctico Off-Grid
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.horizontalGradient(listOf(DarkCardElevated, DarkCardBg)))
                                .border(1.dp, HeritageCopper, RoundedCornerShape(12.dp))
                                .clickable {
                                    showSignalsDialog = false
                                    showTacticalChat = true
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💬", fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "CHAT TÁCTICO OFF-GRID",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = "ChaCha20-Poly1305 · Modo Guantes",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(HeritageCopper.copy(alpha = 0.15f))
                                    .border(1.dp, HeritageCopper.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "ABRIR ➔",
                                    color = HeritageCopper,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }

                        val signals = listOf(
                            Triple("⚠ Obstáculo en la vía", "Bache, grava o escombros", HeritageAmber),
                            Triple("🛑 Reducir velocidad", "Frenado fuerte / Peligro", HeritageRustRed),
                            Triple("🔁 Reagrupar convoy", "Cerrar filas de convoy", HeritageCopper),
                            Triple("⛽ Parada de gasolina", "Estación de servicio próxima", HeritageGreen),
                            Triple("👮 Retén / Precaución", "Control o alerta en la vía", HeritageAmber)
                        )
                        signals.forEach { (title, subtitle, accentColor) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(HeritageCardWarm)
                                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                                            "LIVE_MAP",
                                            "BROADCAST_SIGNAL",
                                            title
                                        )
                                        actionFeedback = "📢 Señal emitida: ¡$title!"
                                        showSignalsDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = title, color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(text = subtitle, color = HeritageLeatherMuted, fontSize = 10.sp)
                                }
                                Text("➔", color = accentColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showSignalsDialog = false }) {
                        Text("Cerrar", color = HeritageCopper, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Diálogo para Guantes: "Voy a parar" (Pausa de alerta de desvío por parada voluntaria)
        if (showStopReasonDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showStopReasonDialog = false },
                containerColor = HeritageSand,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🛑", fontSize = 18.sp)
                        Text(
                            text = "VOY A PARAR (PARADA VOLUNTARIA)",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Selecciona el motivo. Esto silenciará la alarma de desvío y avisará al convoy que tu detención es intencional:",
                            color = HeritageLeatherMuted,
                            fontSize = 11.sp
                        )
                        StopReason.values().forEach { reason ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(HeritageCardWarm)
                                    .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                                    .clickable {
                                        RouteDeviationDetector.activateVoluntaryStop(reason)
                                        actionFeedback = "✓ Parada registrada: ${reason.label}. Alerta silenciada."
                                        showStopReasonDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(reason.emoji, fontSize = 20.sp)
                                Text(reason.label, color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showStopReasonDialog = false }) {
                        Text("Cancelar", color = HeritageCopper, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // 8. Superposición de cuenta regresiva de 20 s para guantes (Fase 5 - C4)
        val countdown = sosCountdownState
        if (countdown is SosCountdownManager.State.Counting) {
            SosCountdownOverlay(
                remainingSeconds = countdown.remainingSeconds,
                triggerType = countdown.triggerType,
                onCancelFalseAlarm = {
                    sosCountdownManager.cancelCountdown("Cancelado por el usuario (Estoy bien)")
                    actionFeedback = "✓ Alerta SOS cancelada (falsa alarma registrada)"
                },
                onConfirmImmediately = {
                    sosCountdownManager.confirmImmediately()
                }
            )
        }

        // 9. Diálogo de Ficha Médica de Rescate de 60 min (Fase 5 - R10.2, R10.3, C5)
        viewingRescuePacket?.let { packet ->
            EmergencyRescuePacketDialog(
                packet = packet,
                remainingMinutes = 60,
                onDismiss = { viewingRescuePacket = null }
            )
        }

        // 9b. Diálogo de Recepción SOS por Malla BLE (Hito 4 · Etapa 4) — FLAG_SECURE activo
        showMeshSosDialog?.let { event ->
            MeshSosReceptionDialog(
                senderName = event.senderName,
                senderShortId = event.senderShortId,
                hopCount = event.hopCount,
                latitude = event.latitude,
                longitude = event.longitude,
                medicalData = event.medicalData,
                onConfirmReception = {
                    // Crear y emitir SosAckPacket usando el servicio BLE activo
                    val selfMarker = state.markers.firstOrNull { it.isSelf }
                    val ackerShortId = selfMarker?.shortId ?: 1u
                    val ack = try {
                        com.rutabikersync.app.core.mesh.sos.SosAckPacket.create(
                            clubNetworkId = 0x52425301u,
                            ackerShortId = ackerShortId,
                            sequenceNum = (System.currentTimeMillis() and 0xFFFF).toUShort(),
                            originalSenderShortId = event.senderShortId,
                            originalSequenceNum = 0u,
                            ackTimestampEpochSec = (System.currentTimeMillis() / 1000L).toInt(),
                            signature = ByteArray(64)
                        )
                    } catch (e: Exception) {
                        null
                    }
                    if (ack != null) {
                        bleMeshService?.sendSosAckPacket(ack)
                    }
                    bleMeshService?.clearIncomingSosMeshEvent()
                    showMeshSosDialog = null
                    actionFeedback = "✅ ACK enviado a Piloto #${event.senderShortId.toInt()}"
                },
                onDismiss = {
                    bleMeshService?.clearIncomingSosMeshEvent()
                    showMeshSosDialog = null
                }
            )
        }

        // 10. Diálogo de Convoy: Lista de Riders en Malla, Silenciamiento Local y Reporte (RULES R13)
        if (showConvoyDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showConvoyDialog = false },
                containerColor = HeritageSand,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = HeritageCopper)
                        Text(
                            text = "Riders en Convoy (${state.markers.size})",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Acceso Directo al Monitor de Malla y Enlaces RF
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.horizontalGradient(listOf(DarkCardElevated, DarkCardBg)))
                                .border(1.dp, EmeraldMesh, RoundedCornerShape(12.dp))
                                .clickable {
                                    showConvoyDialog = false
                                    showMeshMonitor = true
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📡", fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MONITOR DE MALLA Y RF",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Topología en vivo · RSSI · LPN",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EmeraldMesh.copy(alpha = 0.15f))
                                    .border(1.dp, EmeraldMesh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "ABRIR ➔",
                                    color = EmeraldMesh,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }

                        Text(
                            text = "Gestiona el audio y reporte de participantes en la rodada activa (RULES R13).",
                            color = HeritageLeatherMuted,
                            fontSize = 11.sp
                        )
                        state.markers.forEach { marker ->
                            val isMuted = mutedSenders.contains(marker.shortId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(HeritageCardWarm)
                                    .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${marker.fullName}${if (marker.isSelf) " (Tú)" else ""}",
                                        color = if (marker.isSelf) HeritageCopper else HeritageLeatherBrown,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "${marker.rideRole.name} · ${marker.batteryPercent}% bat",
                                        color = HeritageLeatherMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                if (!marker.isSelf) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isMuted) HeritageGreenLight else HeritageRustRed.copy(alpha = 0.15f))
                                                .clickable {
                                                    voicePlayerQueue.toggleMuteSender(marker.shortId)
                                                    actionFeedback = if (!isMuted) {
                                                        "🔇 Silenciado localmente a ${marker.fullName} (R13.3)"
                                                    } else {
                                                        "🔊 Audio reactivado para ${marker.fullName}"
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (isMuted) "Activar" else "Silenciar",
                                                color = if (isMuted) HeritageGreen else HeritageRustRed,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(HeritageCard)
                                                .border(1.dp, HeritageBorder, RoundedCornerShape(6.dp))
                                                .clickable {
                                                    reportingMarker = marker
                                                    showConvoyDialog = false
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Reportar",
                                                color = HeritageCopper,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showConvoyDialog = false }) {
                        Text("Cerrar", color = HeritageCopper, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // 11. Diálogo de Reportar Rider (RULES R13.1, R13.2 - Sin Audio)
        val reported = reportingMarker
        if (reported != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { reportingMarker = null },
                containerColor = HeritageSand,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = AppIcons.Report, contentDescription = null, tint = HeritageRustRed)
                        Text(
                            text = "Reportar a ${reported.fullName}",
                            color = HeritageRustRed,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "El reporte llega a los líderes del grupo y queda en auditoría sin audio ni ubicación (RULES R13.2).",
                            color = HeritageLeatherMuted,
                            fontSize = 11.sp
                        )
                        val reasons = listOf(
                            "dangerous" to "Conducta peligrosa en ruta",
                            "abuse" to "Insultos o agresividad por voz",
                            "harassment" to "Acoso a otros participantes",
                            "other" to "Otro motivo"
                        )
                        reasons.forEach { (code, label) ->
                            val isSelected = reportReason == code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) HeritageCardWarm else HeritageCard)
                                    .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) HeritageRustRed else HeritageBorder, RoundedCornerShape(8.dp))
                                    .clickable { reportReason = code }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) HeritageRustRed else HeritageLeatherBrown,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Text("✓", color = HeritageRustRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.Button(
                        onClick = {
                            val targetName = reported.fullName
                            reportingMarker = null
                            actionFeedback = "🛡️ Reporte enviado a la Mesa Directiva contra $targetName (R13.1)"
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = HeritageRustRed)
                    ) {
                        Text("Enviar Reporte", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { reportingMarker = null }) {
                        Text("Cancelar", color = HeritageLeatherMuted)
                    }
                }
            )
        }

        // 12. Overlay Pantalla Completa: Chat Táctico Off-Grid
        if (showTacticalChat) {
            val localShortId = rideServiceState.localShortId
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkSlate950)
            ) {
                TacticalChatScreen(
                    localShortId = localShortId,
                    peers = state.markers.filter { !it.isSelf }.map { Pair(it.shortId, it.fullName) },
                    initialFocusKeyboard = initialFocusKeyboard,
                    onSendMessage = { targetId, text ->
                        RideService.dispatchSendTextMessage(context, targetId, text)
                        actionFeedback = "💬 Mensaje enviado: $text"
                    },
                    onNavigateBack = { showTacticalChat = false }
                )
            }
        }

        // 13. Overlay Pantalla Completa: Monitor de Malla y Enlaces RF
        if (showMeshMonitor) {
            val localShortId = rideServiceState.localShortId
            val relayEngine = remember(
                rideServiceState.lifecycleState,
                rideServiceState.rideId,
                rideServiceState.isUsingSimulatedTransport,
                rideServiceState.forcedBleMode
            ) {
                RideService.getActiveRelayEngine()
            }
            val meshPeersMap by (relayEngine?.peers
                ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<UShort, com.rutabikersync.app.core.mesh.MeshRelayEngine.PeerTrack>()))
                .collectAsState()
            val meshPeers = com.rutabikersync.app.feature.mesh.mapPeerTracksToMeshPeerLiveStates(
                peers = meshPeersMap,
                nowEpochMs = System.currentTimeMillis()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkSlate950)
            ) {
                MeshConvoyStatusScreen(
                    localShortId = localShortId,
                    localBatteryPercent = realBatteryLevel.coerceIn(0, 100),
                    isLocalLpnActive = false,
                    peers = meshPeers,
                    isLpnToggleEnabled = false,
                    lpnToggleDisabledReason = "No implementado aún",
                    modulationLabel = rideServiceState.forcedBleMode ?: "AUTO",
                    onBleModeChange = { mode ->
                        coroutineScope.launch {
                            RideService.restartWithBleMode(mode)
                        }
                    },
                    onOpenChatWithPeer = { _ ->
                        showMeshMonitor = false
                        showTacticalChat = true
                    },
                    onNavigateBack = { showMeshMonitor = false }
                )
            }
        }

        // 14. Overlay Pantalla Completa: Gestión de Mapas Offline (PMTiles v3)
        if (showOfflineMapsScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F141C))
            ) {
                com.rutabikersync.app.feature.routes.OfflineMapsScreen(
                    onNavigateBack = { showOfflineMapsScreen = false }
                )
            }
        }
    }
}

/**
 * Cápsula flotante redondeada para el HUD superior de la cabina.
 */
@Composable
private fun FloatingHudCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkCardElevated.copy(alpha = 0.94f))
            .border(1.2.dp, DarkBorderProminent, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MapLibreCorridorSurface(
    state: LiveMapUiState,
    selfShortId: UShort,
    onSelectRider: (UShort) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView.onStop()
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Controla el redibujo cuando "Recalcular ruta" sustituye la polilínea activa.
        val lastDrawnRouteId = remember { mutableStateOf<String?>(null) }

        val scheduledRide by RideScheduleRepository.currentRide.collectAsState()
        val activeRoute = scheduledRide?.selectedRoute

        // MapLibre nativo conectado a capas cartográficas
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.setStyle(state.styleAssetUri) {
                            map.uiSettings.isAttributionEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isCompassEnabled = false
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isScrollGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true
                            map.moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(state.cameraLatitude, state.cameraLongitude))
                                        .zoom(state.cameraZoom)
                                        .build()
                                )
                            )
                            if (activeRoute != null && activeRoute.waypoints.size >= 2) {
                                val latLngs = activeRoute.waypoints.map { LatLng(it.latitude, it.longitude) }
                                map.addPolyline(
                                    PolylineOptions()
                                        .addAll(latLngs)
                                        .color(android.graphics.Color.parseColor("#C05A2A"))
                                        .width(8.0f)
                                )
                            }
                            lastDrawnRouteId.value = activeRoute?.id
                        }
                    }
                }
            },
            update = { view ->
                view.getMapAsync { map ->
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(state.cameraLatitude, state.cameraLongitude))
                                .zoom(state.cameraZoom)
                                .build()
                        )
                    )
                    val desiredRouteId = activeRoute?.id
                    if (desiredRouteId != lastDrawnRouteId.value) {
                        map.polylines.forEach { it.remove() }
                        if (activeRoute != null && activeRoute.waypoints.size >= 2) {
                            val latLngs = activeRoute.waypoints.map { LatLng(it.latitude, it.longitude) }
                            map.addPolyline(
                                PolylineOptions()
                                    .addAll(latLngs)
                                    .color(android.graphics.Color.parseColor("#C05A2A"))
                                    .width(8.0f)
                            )
                        }
                        lastDrawnRouteId.value = desiredRouteId
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Convoy sobre la vía: se proyectan los peers reales sobre la polilínea activa.
        // Sin ruta, ConvoyRouteProjection devuelve vacío y se usa la posición exacta.
        val convoyPlacements = remember(state.markers, activeRoute?.waypoints) {
            ConvoyRouteProjection.layout(state.markers, activeRoute?.waypoints.orEmpty(), selfShortId)
        }
        val orderedConvoyMarkers = remember(state.markers, convoyPlacements) {
            state.markers.sortedWith(
                compareByDescending<RiderMarkerUiModel> { it.shortId == selfShortId }
                    .thenBy { convoyPlacements[it.shortId]?.cumulativeMeters ?: Int.MAX_VALUE }
            )
        }

        // Posicionar cada RiderMarker sobre el corredor (proyectado si hay ruta).
        // El overlay Compose usa una escala propia distinta del zoom de MapLibre: cuando
        // dos peers reales quedan a pocos metros, sus marcadores se solapan. Se aplica
        // un desplazamiento visual en pantalla (nunca en lat/lon) para separarlos.
        var lastScreenOffset: IntOffset? = null
        orderedConvoyMarkers.forEach { marker ->
            val placement = convoyPlacements[marker.shortId]
            val displayLat = placement?.latitude ?: marker.displayLatitude
            val displayLon = placement?.longitude ?: marker.displayLongitude
            val latDelta = (displayLat - state.cameraLatitude)
            val lonDelta = (displayLon - state.cameraLongitude)
            val normalizedX = (0.45f + (lonDelta * 120f).toFloat()).coerceIn(0.12f, 0.72f)
            val normalizedY = (0.48f - (latDelta * 120f).toFloat()).coerceIn(0.18f, 0.74f)

            var xOffsetPx = (normalizedX * widthPx).roundToInt() - 48
            var yOffsetPx = (normalizedY * heightPx).roundToInt() - 48
            val previous = lastScreenOffset
            if (previous != null &&
                kotlin.math.abs(xOffsetPx - previous.x) < 56 &&
                kotlin.math.abs(yOffsetPx - previous.y) < 56
            ) {
                val headingRad = Math.toRadians((placement?.headingDegrees ?: marker.bearingDegrees).toDouble())
                xOffsetPx += (kotlin.math.sin(headingRad) * 44.0).roundToInt()
                yOffsetPx -= (kotlin.math.cos(headingRad) * 44.0).roundToInt()
            }
            lastScreenOffset = IntOffset(xOffsetPx, yOffsetPx)

            val markerLeaderText = LeaderDistanceFormatter.format(
                meters = marker.distanceToLeaderMeters,
                isLeader = marker.rideRole == com.rutabikersync.app.feature.livemap.RiderRideRole.LEADER ||
                    marker.shortId == state.leaderPin?.leaderShortId,
                hasGpsFix = state.gpsFixed
            )
            RiderMarker(
                marker = marker,
                isSelected = state.selectedRiderShortId == marker.shortId,
                onClick = { onSelectRider(marker.shortId) },
                modifier = Modifier
                    .offset { IntOffset(xOffsetPx, yOffsetPx) }
                    .zIndex(if (marker.shortId == selfShortId) 2f else 1f),
                headingDegrees = placement?.headingDegrees ?: marker.bearingDegrees,
                leaderDistanceText = markerLeaderText
            )
        }
    }
}
