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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import kotlinx.coroutines.flow.map
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.rutabikersync.app.core.mesh.MeshTelemetry
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

    // Recepción reactiva de señales de convoy por la malla activa (Hito 2 · Propagación BLE)
    LaunchedEffect(Unit) {
        RideService.incomingConvoySignals.collect { incoming ->
            val selfMarker = state.markers.firstOrNull { it.isSelf }
            if (selfMarker == null || incoming.senderShortId != selfMarker.shortId) {
                // Banner de feedback visual en la cabina
                val senderName = state.markers.firstOrNull { it.shortId == incoming.senderShortId }?.let { it.nickname ?: it.fullName }
                    ?: "Rider #${incoming.senderShortId.toInt()}"
                actionFeedback = "📢 $senderName: ¡${incoming.signalKind.fullLabel}!"
                android.util.Log.i(
                    "LiveMapScreen",
                    "ConvoySignalRx recibido de $senderName por malla: ${incoming.signalKind.name}"
                )
            }
        }
    }

    // Recepción reactiva de ciclo de rodada (Fase C · Sincronización e Inversión de Retorno)
    LaunchedEffect(Unit) {
        RideService.incomingRideLifecycleEvents.collect { incoming ->
            val selfMarker = state.markers.firstOrNull { it.isSelf }
            if (selfMarker == null || incoming.senderShortId != selfMarker.shortId) {
                val senderName = state.markers.firstOrNull { it.shortId == incoming.senderShortId }?.let { it.nickname ?: it.fullName }
                    ?: "Rider #${incoming.senderShortId.toInt()}"
                actionFeedback = when (incoming.event) {
                    com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND ->
                        "🚀 $senderName: ¡Inicio de ruta del convoy!"
                    com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION ->
                        "🏁 $senderName: ¡Llegamos al punto de destino!"
                    com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_RETURN ->
                        "🔄 $senderName: ¡Inicio de retorno (Ruta invertida)!"
                    com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE ->
                        "🏁 $senderName: ¡Rodada finalizada!"
                    com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.SAFE_HOME ->
                        "🏠 $senderName: ¡Ha llegado seguro a casa!"
                }
                android.util.Log.i(
                    "LiveMapScreen",
                    "RideLifecycleEventRx recibido de $senderName por malla: ${incoming.event.name}"
                )
            }
        }
    }

    val pttState by pttEngine.pttState.collectAsState()
    val isRecording = pttState is PttState.Transmitting
    var recordingSeconds by remember { mutableIntStateOf(0) }

    fun startCabinaPtt() {
        // Silencio SOS síncrono antes de AudioRecord; PttAudioEngine permanece intacto.
        com.rutabikersync.app.core.audio.SosToneHub.setPttActive("cabina-ptt", true)
        RideService.beginPttVoiceStream()
        pttEngine.startTalking()
        coroutineScope.launch {
            kotlinx.coroutines.delay(1_500L)
            if (pttEngine.pttState.value is PttState.Idle) {
                com.rutabikersync.app.core.audio.SosToneHub.setPttActive("cabina-ptt", false)
            }
        }
    }

    fun stopCabinaPtt() {
        pttEngine.stopTalking()
        RideService.endPttVoiceStream()
        com.rutabikersync.app.core.audio.SosToneHub.setPttActive("cabina-ptt", false)
    }

    // Auto-parada de 15 s y contador de la cápsula de transmisión.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000L)
                recordingSeconds++
                if (recordingSeconds >= PttAudioEngine.MAX_TALK_SECONDS) {
                    stopCabinaPtt()
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
                startCabinaPtt()
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

    LaunchedEffect(Unit) {
        // Asegurar que la malla BLE y el servicio en primer plano estén activos al estar en la cabina
        if (RideService.sharedState.value.lifecycleState != RideLifecycleState.RUNNING) {
            val shortId = run {
                val androidId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).orEmpty()
                val uniqueSeed = "${android.os.Build.MODEL}:$androidId"
                ((uniqueSeed.hashCode() and 0x7FFF) % 65534) + 1
            }
            RideService.dispatchStartRide(
                context = context.applicationContext,
                rideId = "ride-live-01",
                localShortId = shortId,
                forceSimulated = false
            )
        }
        // El alcance real pertenece a App; cabina solo observa PTT y el estado SOS.
        com.rutabikersync.app.core.audio.SosToneHub.ensureInitialized(context.applicationContext)
        com.rutabikersync.app.core.audio.ConvoySignalAudioHub.ensureInitialized(
            context = context.applicationContext,
            externalPttStateProvider = { pttEngine.pttState.value !is PttState.Idle }
        )
    }
    LaunchedEffect(pttEngine) {
        com.rutabikersync.app.core.audio.SosToneHub.observePttSource(
            source = "cabina-ptt",
            active = pttEngine.pttState.map { it !is PttState.Idle },
            scope = this
        )
    }

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

    // Fuente principal en una rodada real: el MeshRelayEngine de RideService, no el servicio legacy.
    val activeMeshSosPacket by com.rutabikersync.app.core.audio.SosToneHub.latestRemoteSos.collectAsState()
    LaunchedEffect(activeMeshSosPacket) {
        activeMeshSosPacket?.let { sos ->
            showMeshSosDialog = BleMeshService.IncomingSosMeshEvent(
                senderShortId = sos.header.senderShortId,
                senderName = "Piloto #${sos.header.senderShortId.toInt()}",
                hopCount = sos.hopCount.toInt() and 0xFF,
                latitude = sos.latitude,
                longitude = sos.longitude,
                medicalData = bleMeshService?.medicalSheetStore?.getSheet(sos.header.senderShortId)?.data,
                clubNetworkId = sos.header.clubNetworkId,
                sequenceNum = sos.header.sequenceNum
            )
            actionFeedback = "🚨 SOS MESH recibido de Piloto #${sos.header.senderShortId.toInt()}"
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
            com.rutabikersync.app.core.audio.SosToneHub.onAck(
                ack.originalSenderShortId,
                ack.originalSequenceNum
            )
            if (ack.originalSenderShortId == selfShortId) {
                com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(false)
            }
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
                com.rutabikersync.app.core.audio.SosToneHub.start()
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
                // La emisión real SOS la comparte RideService con cabina/notificación/caída.
                actionFeedback = "🚨 ¡SOS ACTIVADO! Alarmando en todos los equipos enlazados"
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
    var isBottomCockpitExpanded by remember { mutableStateOf(false) }

    // Conectar resolver de perfil para que los mensajes del chat táctico muestren el nombre real del piloto
    LaunchedEffect(state.markers) {
        com.rutabikersync.app.core.mesh.text.TacticalChatStore.peerProfileResolver = { shortId ->
            state.markers.firstOrNull { it.shortId == shortId }?.let {
                Pair(it.fullName, it.avatarBase64)
            }
        }
    }

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

    // Histéresis integrada con la UI: el detector solo habilita la alerta tras
    // >150 m sostenidos 10 s. Aquella transición dispara UNA recalculación
    // automática, sin toque y sin reintentos por cada actualización de posición.
    LaunchedEffect(deviationState.isAlertActive) {
        if (!deviationState.isAlertActive || isRecalculatingRoute) return@LaunchedEffect
        isRecalculatingRoute = true
        val outcome = recalculateRouteUseCase(
            currentLocation = deviationState.lastKnownLocation,
            destination = scheduledRide?.destination
        )
        actionFeedback = when (outcome) {
            RecalculateRouteOutcome.SUCCESS -> "✓ Ruta recalculada automáticamente sobre vías reales"
            RecalculateRouteOutcome.OFFLINE -> "Sin conexión, usando ruta offline"
            RecalculateRouteOutcome.NO_ROUTE -> "No se pudo recalcular. Sigue la ruta actual"
        }
        isRecalculatingRoute = false
    }

    val isReturnPhase by RideScheduleRepository.isReturnPhase.collectAsState()
    LaunchedEffect(scheduledRide, isReturnPhase) {
        scheduledRide?.selectedRoute?.waypoints?.let { pts ->
            val effective = if (isReturnPhase) pts.reversed() else pts
            RouteDeviationDetector.setActiveRoutePolyline(effective)
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
            com.rutabikersync.app.core.audio.SosToneHub.silenceForCabinaExit()
            com.rutabikersync.app.core.audio.ConvoySignalAudioHub.stop()
            runCatching { context.unregisterReceiver(receiver) }
            stopCabinaPtt()
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
        when {
            showMeshMonitor -> showMeshMonitor = false
            showTacticalChat -> showTacticalChat = false
            showOfflineMapsScreen -> showOfflineMapsScreen = false
            rideServiceState.lifecycleState == RideLifecycleState.RUNNING -> {
                actionFeedback = "⚠ Para finalizar la rodada mantén presionado 3 s el botón inferior"
            }
            else -> onStopRideHoldCompleted?.invoke()
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
        // FIX privacidad: groupId del perfil local para comparar con el de cada peer.
        liveMapViewModel.controller.setLocalGroupId(localProfileRepository.getClubId())
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

    fun hasUsableFreshFix(loc: android.location.Location): Boolean =
        com.rutabikersync.app.core.mesh.LocalGpsPositionPolicy.isUsableFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 50f,
            fixTimeEpochMs = loc.time,
            nowEpochMs = System.currentTimeMillis()
        )

    fun applyLocation(loc: android.location.Location) {
        if (!hasUsableFreshFix(loc)) {
            android.util.Log.i(
                "LiveMapScreen",
                "Fix descartado por antigüedad/precisión: ageSec=" +
                    ((System.currentTimeMillis() - loc.time).coerceAtLeast(0L) / 1_000L) +
                    " accM=" + if (loc.hasAccuracy()) loc.accuracy.toInt() else "desconocida"
            )
            return
        }
        geoPrefs.edit()
            .putFloat("last_known_lat", loc.latitude.toFloat())
            .putFloat("last_known_lon", loc.longitude.toFloat())
            .putFloat("last_known_accuracy", loc.accuracy)
            .putLong("last_known_time_epoch_ms", loc.time)
            .apply()
        liveMapViewModel.controller.corridorReferenceLon = loc.longitude
        liveMapViewModel.controller.updateLocalRiderPose(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).roundToInt() else 0,
            bearingDegrees = if (loc.hasBearing()) loc.bearing else 180f,
            batteryPercent = realBatteryLevel,
            observedAtEpochMs = loc.time
        )
        // La malla necesita la misma posición real para que los peers se dibujen
        // sobre la vía. Antes los heartbeats emitían (0,0) y el remoto se veía
        // desviado a miles de km. No se inventa nada: viene del GPS/caché real.
        RideService.updateActiveLocalTelemetry(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).roundToInt() else 0,
            bearingDegrees = if (loc.hasBearing()) loc.bearing else 180f,
            batteryPercent = realBatteryLevel,
            observedAtEpochMs = loc.time
        )
        RouteDeviationDetector.updateRiderLocation(
            location = LatLngPoint(loc.latitude, loc.longitude),
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
        )
        // No recapturar el gesto manual del mapa en cada callback de GPS.
        if (!com.rutabikersync.app.core.mesh.MeshTelemetry.isValidPosition(
                state.cameraLatitude,
                state.cameraLongitude
            ) || state.cameraMode == CameraMode.CENTERED_ON_SELF
        ) {
            liveMapViewModel.controller.centerOnMyLocation()
        }
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
            val freshest = candidateProviders
                .mapNotNull { p -> runCatching { locManager?.getLastKnownLocation(p) }.getOrNull() }
                .filter { hasUsableFreshFix(it) }
                .maxByOrNull { it.time }
            freshest?.let { applyLocation(it) }
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
            val cachedTime = geoPrefs.getLong("last_known_time_epoch_ms", System.currentTimeMillis())
            if (com.rutabikersync.app.core.mesh.MeshTelemetry.isValidPosition(cachedLat, cachedLon)) {
                liveMapViewModel.controller.updateLocalRiderPose(
                    latitude = cachedLat,
                    longitude = cachedLon,
                    speedKmh = 0,
                    bearingDegrees = 180f,
                    batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent,
                    observedAtEpochMs = cachedTime
                )
                RideService.updateActiveLocalTelemetry(
                    latitude = cachedLat,
                    longitude = cachedLon,
                    speedKmh = 0,
                    bearingDegrees = 180f,
                    batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent,
                    observedAtEpochMs = cachedTime
                )
                liveMapViewModel.controller.centerOnMyLocation()
                liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
            }
        } else {
            // Posición inicial segura en caso de primera instalación sin fix previo
            val activeRoute = RideScheduleRepository.currentRide.value?.selectedRoute
            val initLat = activeRoute?.waypoints?.firstOrNull()?.latitude ?: 10.3444
            val initLon = activeRoute?.waypoints?.firstOrNull()?.longitude ?: -67.0422
            liveMapViewModel.controller.updateLocalRiderPose(
                latitude = initLat,
                longitude = initLon,
                speedKmh = 0,
                bearingDegrees = 180f,
                batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent,
                observedAtEpochMs = System.currentTimeMillis()
            )
            liveMapViewModel.controller.centerOnMyLocation()
            liveMapViewModel.controller.flushMapFrame(System.currentTimeMillis(), force = true)
        }

        if (fineGranted || coarseGranted) {
            // 2. Consultar todos los proveedores de geolocalización activos
            runCatching {
                val candidateProviders = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                candidateProviders
                    .mapNotNull { p -> runCatching { locManager?.getLastKnownLocation(p) }.getOrNull() }
                    .filter { hasUsableFreshFix(it) }
                    .maxByOrNull { it.time }
                    ?.let { applyLocation(it) }
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
            onMapClick = {
                isBottomCockpitExpanded = !isBottomCockpitExpanded
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. HUD Superior Flotante: 3 cápsulas redondeadas independientes
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    top = 8.dp,
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
        val effectiveCockpitBg = if (isBottomCockpitExpanded) {
            Brush.verticalGradient(cockpitBgColors)
        } else {
            Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(effectiveCockpitBg)
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
                            text = ConvoyRiderCountFormatter.format(state.ridersCount),
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
                        com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(false)
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

            // Control visual interactivo: Toca para subir/ocultar la botonera
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCardElevated.copy(alpha = 0.92f))
                    .border(
                        1.dp,
                        if (isBottomCockpitExpanded) HeritageCopper else DarkBorderSubtle,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { isBottomCockpitExpanded = !isBottomCockpitExpanded }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isBottomCockpitExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = HeritageBrassGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isBottomCockpitExpanded) "Ocultar Controles (Toca el mapa)" else "👆 Controles de Cabina (PTT · Señales · SOS)",
                        color = if (isBottomCockpitExpanded) HeritageCopper else TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            // Sección auto-ocultable de acciones de cabina (despeja 100% el mapa)
            AnimatedVisibility(
                visible = isBottomCockpitExpanded,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                                stopCabinaPtt()
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
                                com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(false)
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
            }

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

                        val signalColors = mapOf(
                            com.rutabikersync.app.core.audio.ConvoySignalKind.OBSTACLE to HeritageAmber,
                            com.rutabikersync.app.core.audio.ConvoySignalKind.REDUCE_SPEED to HeritageRustRed,
                            com.rutabikersync.app.core.audio.ConvoySignalKind.REGROUP to HeritageCopper,
                            com.rutabikersync.app.core.audio.ConvoySignalKind.GAS_STOP to HeritageGreen,
                            com.rutabikersync.app.core.audio.ConvoySignalKind.POLICE_CHECKPOINT to HeritageAmber
                        )
                        com.rutabikersync.app.core.audio.ConvoySignalKind.entries.forEach { signal ->
                            val accentColor = signalColors[signal] ?: HeritageCopper
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
                                            signal.name
                                        )
                                        com.rutabikersync.app.core.audio.ConvoySignalAudioHub.playSignal(signal)
                                        coroutineScope.launch {
                                            RideService.dispatchSendConvoySignal(signal)
                                        }
                                        actionFeedback = "📢 Señal emitida: ¡${signal.fullLabel}!"
                                        showSignalsDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = signal.fullLabel, color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(text = signal.subtitle, color = HeritageLeatherMuted, fontSize = 10.sp)
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
                    com.rutabikersync.app.core.audio.SosToneHub.setLocalActive(false)
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
                    val ackerShortId = selfShortId
                    val ack = try {
                        com.rutabikersync.app.core.mesh.sos.SosAckPacket.create(
                            clubNetworkId = event.clubNetworkId,
                            ackerShortId = ackerShortId,
                            sequenceNum = (System.currentTimeMillis() and 0xFFFF).toUShort(),
                            originalSenderShortId = event.senderShortId,
                            originalSequenceNum = event.sequenceNum,
                            ackTimestampEpochSec = (System.currentTimeMillis() / 1000L).toInt(),
                            signature = ByteArray(64)
                        )
                    } catch (e: Exception) {
                        null
                    }
                    val accepted = if (ack == null) {
                        false
                    } else if (RideService.getActiveRelayEngine() != null) {
                        RideService.dispatchSosAckPacket(ack)
                    } else {
                        bleMeshService?.sendSosAckPacket(ack) == true
                    }
                    if (accepted) {
                        bleMeshService?.clearIncomingSosMeshEvent()
                        showMeshSosDialog = null
                        activeMeshSosPacket?.let {
                            com.rutabikersync.app.core.audio.SosToneHub.dismissRemoteForCabina(it)
                        }
                        actionFeedback = "✅ ACK en emisión a Piloto #${event.senderShortId.toInt()}"
                    } else {
                        actionFeedback = "⚠ No se pudo emitir el ACK. La alerta sigue activa; inténtalo de nuevo"
                    }
                },
                onDismiss = {
                    bleMeshService?.clearIncomingSosMeshEvent()
                    showMeshSosDialog = null
                    activeMeshSosPacket?.let {
                        com.rutabikersync.app.core.audio.SosToneHub.dismissRemoteForCabina(it)
                    }
                    // Ocultar UI no equivale a ACK/resolución: otros equipos siguen alertando.
                    actionFeedback = "Alerta visual ocultada; el SOS sigue activo hasta ACK o resolución"
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
                val peerAvatars = remember(state.markers) {
                    state.markers.associate { it.shortId to it.avatarBase64 }
                }
                val peerFullNames = remember(state.markers) {
                    state.markers.associate { it.shortId to it.fullName }
                }
                TacticalChatScreen(
                    localShortId = localShortId,
                    peers = state.markers.filter { !it.isSelf }.map { Pair(it.shortId, it.fullName) },
                    peerAvatars = peerAvatars,
                    peerFullNames = peerFullNames,
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

/**
 * Doble trazo 100% local (sin llamadas a API): halo oscuro semitransparente y
 * línea brillante sobre la misma geometría. `round` en cap/join evita picos en
 * las curvas y extremos. No modifica el JSON de estilo base.
 */
private fun ensureRouteLayers(style: Style) {
    if (style.getLayer(RouteStrokeStyle.LINE_LAYER_ID) != null) return
    if (style.getSource(RouteStrokeStyle.SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                RouteStrokeStyle.SOURCE_ID,
                FeatureCollection.fromFeatures(emptyList<Feature>())
            )
        )
    }
    val halo = LineLayer(RouteStrokeStyle.HALO_LAYER_ID, RouteStrokeStyle.SOURCE_ID).withProperties(
        PropertyFactory.lineCap(RouteStrokeStyle.CAP_AND_JOIN),
        PropertyFactory.lineJoin(RouteStrokeStyle.CAP_AND_JOIN),
        PropertyFactory.lineColor(RouteStrokeStyle.HALO_COLOR),
        PropertyFactory.lineOpacity(RouteStrokeStyle.HALO_OPACITY),
        PropertyFactory.lineWidth(RouteStrokeStyle.HALO_WIDTH)
    )
    val line = LineLayer(RouteStrokeStyle.LINE_LAYER_ID, RouteStrokeStyle.SOURCE_ID).withProperties(
        PropertyFactory.lineCap(RouteStrokeStyle.CAP_AND_JOIN),
        PropertyFactory.lineJoin(RouteStrokeStyle.CAP_AND_JOIN),
        PropertyFactory.lineColor(RouteStrokeStyle.LINE_COLOR),
        PropertyFactory.lineOpacity(RouteStrokeStyle.LINE_OPACITY),
        PropertyFactory.lineWidth(RouteStrokeStyle.LINE_WIDTH)
    )
    style.addLayer(halo)
    style.addLayer(line)
}

/** Actualiza (o limpia) la geometría de la ruta en las dos capas locales. */
private fun drawRouteOnMap(map: MapLibreMap, waypoints: List<LatLngPoint>) {
    val style = map.style ?: return
    ensureRouteLayers(style)
    val source = style.getSourceAs<GeoJsonSource>(RouteStrokeStyle.SOURCE_ID) ?: return
    if (waypoints.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        return
    }
    val coordinates = waypoints.map { Point.fromLngLat(it.longitude, it.latitude) }
    val feature = Feature.fromGeometry(LineString.fromLngLats(coordinates))
    source.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
}

@Composable
private fun MapLibreCorridorSurface(
    state: LiveMapUiState,
    selfShortId: UShort,
    onSelectRider: (UShort?) -> Unit,
    onMapClick: () -> Unit = {},
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
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraProjectionRevision by remember { mutableIntStateOf(0) }
    var lastRequestedCamera by remember { mutableStateOf<CameraPosition?>(null) }

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
        val isReturnPhase by RideScheduleRepository.isReturnPhase.collectAsState()
        val activeRoute = scheduledRide?.selectedRoute
        val activeWaypoints = remember(activeRoute?.waypoints, isReturnPhase) {
            val pts = activeRoute?.waypoints.orEmpty()
            if (isReturnPhase) pts.reversed() else pts
        }

        // MapLibre nativo conectado a capas cartográficas
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        // Tocar el mapa vacío cierra el detalle; los listeners se registran una vez.
                        if (mapLibreMap !== map) {
                            mapLibreMap = map
                            map.addOnMapClickListener {
                                onSelectRider(null)
                                onMapClick()
                                true
                            }
                            map.addOnCameraIdleListener { cameraProjectionRevision++ }
                        }
                        map.setStyle(state.styleAssetUri) {
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.setAttributionMargins(32, 0, 0, 140)
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isCompassEnabled = false
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isScrollGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true

                            // Centrado inicial seguro: jamás apuntar a (0.0, 0.0) en el océano Atlántico
                            val effectiveLat = when {
                                MeshTelemetry.isValidPosition(state.cameraLatitude, state.cameraLongitude) -> state.cameraLatitude
                                activeRoute?.waypoints?.isNotEmpty() == true -> activeRoute.waypoints.first().latitude
                                else -> 10.3444 // Los Teques, Venezuela
                            }
                            val effectiveLon = when {
                                MeshTelemetry.isValidPosition(state.cameraLatitude, state.cameraLongitude) -> state.cameraLongitude
                                activeRoute?.waypoints?.isNotEmpty() == true -> activeRoute.waypoints.first().longitude
                                else -> -67.0422 // Los Teques, Venezuela
                            }

                            val validWaypoints = activeRoute?.waypoints.orEmpty().filter {
                                MeshTelemetry.isValidPosition(it.latitude, it.longitude) &&
                                    it.latitude in 0.5..13.0 && it.longitude in -74.0..-59.0
                            }

                            if (validWaypoints.size >= 2) {
                                try {
                                    val boundsBuilder = LatLngBounds.Builder()
                                    for (pt in validWaypoints) {
                                        boundsBuilder.include(LatLng(pt.latitude, pt.longitude))
                                    }
                                    val density = context.resources.displayMetrics.density
                                    map.moveCamera(
                                        CameraUpdateFactory.newLatLngBounds(
                                            boundsBuilder.build(),
                                            (40 * density).toInt(),
                                            (90 * density).toInt(),
                                            (40 * density).toInt(),
                                            (160 * density).toInt()
                                        )
                                    )
                                } catch (_: Exception) {
                                    map.moveCamera(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder()
                                                .target(LatLng(effectiveLat, effectiveLon))
                                                .zoom(state.cameraZoom.coerceAtLeast(12.0))
                                                .build()
                                        )
                                    )
                                }
                            } else {
                                map.moveCamera(
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder()
                                            .target(LatLng(effectiveLat, effectiveLon))
                                            .zoom(state.cameraZoom.coerceAtLeast(12.0))
                                            .build()
                                    )
                                )
                            }
                            drawRouteOnMap(map, activeWaypoints)
                            lastDrawnRouteId.value = "${activeRoute?.id}:${if (isReturnPhase) "return" else "outbound"}"
                        }
                    }
                }
            },
            update = { view ->
                view.getMapAsync { map ->
                    val currentCamera = map.cameraPosition
                    // Solo mover cámara dinámicamente si la telemetría reporta una posición real fija
                    if (MeshTelemetry.isValidPosition(state.cameraLatitude, state.cameraLongitude) &&
                        state.cameraLatitude in 0.5..13.0 && state.cameraLongitude in -74.0..-59.0
                    ) {
                        val targetCamera = CameraPosition.Builder()
                            .target(LatLng(state.cameraLatitude, state.cameraLongitude))
                            .zoom(state.cameraZoom)
                            .bearing(currentCamera?.bearing ?: 0.0)
                            .tilt(currentCamera?.tilt ?: 0.0)
                            .build()
                        val cameraHasSameTarget = currentCamera?.target?.let {
                            kotlin.math.abs(it.latitude - state.cameraLatitude) < 1e-10 &&
                                kotlin.math.abs(it.longitude - state.cameraLongitude) < 1e-10 &&
                                kotlin.math.abs(currentCamera.zoom - state.cameraZoom) < 1e-6
                        } == true
                        if (!cameraHasSameTarget && lastRequestedCamera != targetCamera) {
                            lastRequestedCamera = targetCamera
                            map.moveCamera(CameraUpdateFactory.newCameraPosition(targetCamera))
                        }
                    }
                    val desiredRouteKey = "${activeRoute?.id}:${if (isReturnPhase) "return" else "outbound"}"
                    if (desiredRouteKey != lastDrawnRouteId.value) {
                        drawRouteOnMap(map, activeWaypoints)
                        lastDrawnRouteId.value = desiredRouteKey
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Convoy sobre la vía: se proyectan los peers reales sobre la polilínea activa.
        // Sin ruta, ConvoyRouteProjection devuelve vacío y se usa la posición exacta.
        val convoyPlacements = remember(state.markers, activeWaypoints) {
            ConvoyRouteProjection.layout(state.markers, activeWaypoints, selfShortId)
        }
        val orderedConvoyMarkers = remember(state.markers, convoyPlacements) {
            state.markers.sortedWith(
                compareByDescending<RiderMarkerUiModel> { it.shortId == selfShortId }
                    .thenBy { convoyPlacements[it.shortId]?.cumulativeMeters ?: Int.MAX_VALUE }
            )
        }

        // La proyección nativa considera zoom, tilt, rotación y viewport reales. Leer
        // cameraProjectionRevision fuerza el cálculo de píxeles al terminar gestos de cámara.
        // La separación anti-solapamiento es solo visual y nunca modifica lat/lon del modelo.
        @Suppress("UNUSED_VARIABLE")
        val projectionRevision = cameraProjectionRevision
        var lastScreenOffset: IntOffset? = null
        orderedConvoyMarkers.forEach { marker ->
            val placement = convoyPlacements[marker.shortId]
            val displayLat = placement?.latitude ?: marker.displayLatitude
            val displayLon = placement?.longitude ?: marker.displayLongitude
            val point = mapLibreMap?.projection?.toScreenLocation(LatLng(displayLat, displayLon))
            if (point == null || !RiderViewportLayout.isPointVisible(point.x, point.y, widthPx, heightPx)) {
                return@forEach
            }

            var xOffsetPx = point.x.roundToInt() - 36
            var yOffsetPx = point.y.roundToInt() - 36
            val previous = lastScreenOffset
            if (previous != null &&
                kotlin.math.abs(xOffsetPx - previous.x) < 44 &&
                kotlin.math.abs(yOffsetPx - previous.y) < 44
            ) {
                val headingRad = Math.toRadians((placement?.headingDegrees ?: marker.bearingDegrees).toDouble())
                xOffsetPx += (kotlin.math.sin(headingRad) * 32.0).roundToInt()
                yOffsetPx -= (kotlin.math.cos(headingRad) * 32.0).roundToInt()
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
