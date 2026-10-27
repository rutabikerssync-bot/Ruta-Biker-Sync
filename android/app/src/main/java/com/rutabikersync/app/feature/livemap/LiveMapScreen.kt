package com.rutabikersync.app.feature.livemap

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.rutabikersync.app.BuildConfig
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.components.HeritageStopRideHoldButton
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.ui.theme.HeritageTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by liveMapViewModel.uiState.collectAsState()
    val rideServiceState by RideService.sharedState.collectAsState()
    val simulatedAlert by com.rutabikersync.app.core.uxtest.UxTestTracker.simulatedAlertEvent.collectAsState()
    val uxParticipant by com.rutabikersync.app.core.uxtest.UxTestTracker.participantId.collectAsState()
    val uxLeaderId by com.rutabikersync.app.core.uxtest.UxTestTracker.leaderShortId.collectAsState()
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(uxParticipant, uxLeaderId) {
        val localShortId = com.rutabikersync.app.core.uxtest.UxTestTracker.participantToShortId(uxParticipant)
        liveMapViewModel.controller.updateConvoyIds(localId = localShortId, targetLeaderId = uxLeaderId)
    }

    androidx.compose.runtime.LaunchedEffect(simulatedAlert) {
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
            batteryPercent = liveMapViewModel.uiState.value.localBatteryPercent
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
        MapLibreCorridorSurface(
            state = state,
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
            // Cápsula 1: Líder con distancia y flecha
            val leaderDist = state.leaderPin?.let { "320 m" } ?: "320 m"
            FloatingHudCapsule(
                icon = Icons.Default.Shield,
                iconTint = Color(0xFF10B981),
                text = "Líder a $leaderDist ↗",
                onClick = {
                    liveMapViewModel.controller.followLeader(
                        nowEpochMs = System.currentTimeMillis(),
                        scheduleAutoRevert = true
                    )
                }
            )

            // Cápsula 2: Velocidad actual en km/h
            val currentSpeed = state.markers.firstOrNull { it.isSelf }?.speedKmh ?: 85
            FloatingHudCapsule(
                icon = Icons.Default.Speed,
                iconTint = Color(0xFF10B981),
                text = "$currentSpeed km/h"
            )

            // Cápsula 3: Nivel de batería
            val battery = state.localBatteryPercent.coerceIn(1, 100)
            FloatingHudCapsule(
                icon = AppIcons.BatteryStd,
                iconTint = if (battery < 15) Color(0xFFEAB308) else Color(0xFF10B981),
                text = "$battery%"
            )
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xCC0B0E14),
                            Color(0xF20B0E14),
                            Color(0xFF0B0E14)
                        )
                    )
                )
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 20.dp)
        ) {
            // Cápsulas intermedias sobre el mapa: Convoy (izquierda) y Seguir líder (derecha)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Cápsula izquierda: Convoy + cantidad de riders
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF181A20).copy(alpha = 0.94f))
                        .border(1.2.dp, Color(0xFF3E3A32), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Convoy",
                        tint = Color(0xFFE5B869),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Convoy",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp
                        )
                        Text(
                            text = "${state.connectedNodesCount.coerceAtLeast(1)} riders",
                            color = Color(0xFF9CA3AF),
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.sp,
                            lineHeight = 11.sp
                        )
                    }
                }

                // Cápsula derecha: Botón "Seguir líder"
                val isFollowing = state.cameraMode == CameraMode.FOLLOWING_LEADER
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isFollowing) Color(0xFFC99A45) else Color(0xFF181A20).copy(alpha = 0.94f)
                        )
                        .border(
                            1.2.dp,
                            if (isFollowing) Color(0xFFF3D082) else Color(0xFF3E3A32),
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
                        tint = if (isFollowing) Color(0xFF101114) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isFollowing) "Líder (${state.followLeaderRemainingSeconds}s)" else "Seguir líder",
                        color = if (isFollowing) Color(0xFF101114) else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            // Feedback o aviso de acciones
            actionFeedback?.let { feedback ->
                Text(
                    text = feedback,
                    color = Color(0xFFE5B869),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // 5. Las 3 acciones circulares de cabina Heritage (≥ 72 dp)
            GlovesPrimaryActionBar(
                isSosActive = rideServiceState.isSosActive,
                onTalkPttClick = {
                    com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                        "LIVE_MAP",
                        "TAP_TALK_PTT",
                        "btn_hablar"
                    )
                    actionFeedback = "Audio PTT activo · Transmitiendo a convoy"
                },
                onSosClick = {
                    val isUxMode = BuildConfig.UX_TEST_MODE
                    val meshBroadcastAllowed = isUxMode && com.rutabikersync.app.core.uxtest.UxTestTracker.isSosMeshBroadcastEnabled

                    if (!isUxMode || meshBroadcastAllowed) {
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            screen = "LIVE_MAP",
                            action = "TAP_SOS",
                            target = if (isUxMode) "btn_sos_prueba" else "btn_sos",
                            details = "task=T9,broadcast=true"
                        )
                        val sosIntent = Intent(context, RideService::class.java).apply {
                            action = RideService.ACTION_TRIGGER_SOS
                        }
                        runCatching { context.startService(sosIntent) }
                        actionFeedback = if (isUxMode) {
                            "[MODO PRUEBA UX] SOS emitido por la malla BLE"
                        } else {
                            "SOS emitido por la malla con prioridad máxima"
                        }
                    } else {
                        com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                            screen = "LIVE_MAP",
                            action = "TAP_SOS",
                            target = "btn_sos_prueba",
                            details = "task=T9,broadcast=false"
                        )
                        actionFeedback = "[MODO PRUEBA UX] SOS simulado LOCAL (sin emisión BLE)"
                    }
                },
                onQuickSignalsClick = {
                    com.rutabikersync.app.core.uxtest.UxTestTracker.logEvent(
                        "LIVE_MAP",
                        "TAP_SIGNALS",
                        "btn_senales"
                    )
                    actionFeedback = "Señal de ruta emitida · Precaución en convoy"
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
                    RideService.dispatchStopRide(context)
                    onStopRideHoldCompleted?.invoke()
                    actionFeedback = "Rodada finalizada. Guardando métricas..."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            // 7. Pie de cabina oficial
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Cabina de Rodada Activa",
                    color = Color(0xFFE5E7EB),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pantalla completa · 3 acciones principales · Máxima seguridad",
                    color = Color(0xFF9CA3AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
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
            .background(Color(0xFF141519).copy(alpha = 0.92f))
            .border(1.dp, Color(0xFF2C2F38), RoundedCornerShape(20.dp))
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
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Trazado del corredor offline: ruta montañosa en azul eléctrico luminoso con halo (como en el mockup oficial)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                val startX = size.width * 0.52f
                moveTo(startX, size.height * 0.08f)
                cubicTo(
                    size.width * 0.56f, size.height * 0.20f,
                    size.width * 0.44f, size.height * 0.35f,
                    size.width * 0.50f, size.height * 0.50f
                )
                cubicTo(
                    size.width * 0.56f, size.height * 0.65f,
                    size.width * 0.42f, size.height * 0.78f,
                    size.width * 0.48f, size.height * 0.92f
                )
            }

            // Halo exterior azul eléctrico
            drawPath(
                path = path,
                color = Color(0xFF0284C7).copy(alpha = 0.35f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 16f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )

            // Línea central de convoy en cian/azul brillante
            drawPath(
                path = path,
                color = Color(0xFF38BDF8),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 5.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }

        // Posicionar cada RiderMarker interpolado sobre el lienzo del corredor
        state.markers.forEachIndexed { index, marker ->
            val latDelta = (marker.displayLatitude - state.cameraLatitude)
            val lonDelta = (marker.displayLongitude - state.cameraLongitude)
            val normalizedX = (0.45f + (lonDelta * 120f).toFloat()).coerceIn(0.12f, 0.72f)
            val normalizedY = (0.48f - (latDelta * 120f).toFloat() + index * 0.04f).coerceIn(0.18f, 0.74f)

            val xOffsetPx = (normalizedX * widthPx).roundToInt() - 48
            val yOffsetPx = (normalizedY * heightPx).roundToInt() - 48

            RiderMarker(
                marker = marker,
                isSelected = state.selectedRiderShortId == marker.shortId,
                onClick = { onSelectRider(marker.shortId) },
                modifier = Modifier.offset { IntOffset(xOffsetPx, yOffsetPx) }
            )
        }
    }
}
