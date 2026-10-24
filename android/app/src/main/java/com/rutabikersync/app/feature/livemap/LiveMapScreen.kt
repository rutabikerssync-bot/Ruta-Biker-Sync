package com.rutabikersync.app.feature.livemap

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/**
 * Pantalla `C1. Mapa en vivo` (SCREENS.md C1, DESIGN.md §2, §4, §9 e ICONS.md §3–§5).
 *
 * - Regla de oro (SCREENS.md §3 C): nada en rodada activa depende de la red ni muestra skeleton.
 * - Tema oscuro de alto contraste bajo el sol (`#0B0E14`).
 * - Decisión 3.1 (A): PMTiles offline del corredor (`asset://tiles/sample_corridor.pmtiles`) + atribución OSM visible.
 * - Decisión 3.2 (C): Nombre abreviado siempre visible junto al avatar (`"J.M."`) + nombre completo al tocar.
 * - Decisión 3.3 (B): Pin del líder fijo + botón `"Seguir líder"` que centra por 10 s y vuelve a navegación libre.
 * - Modo guantes: controles ≥ 72 dp, vibración al tocar y máximo 3 acciones flotantes inferiores (`Hablar`, `SOS`, `Señales`).
 */
@Composable
fun LiveMapScreen(
    liveMapViewModel: LiveMapViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by liveMapViewModel.uiState.collectAsState()
    val rideServiceState by RideService.sharedState.collectAsState()
    var showOfflineTilesSheet by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
    ) {
        // 1. Lienzo de mapa MapLibre (con estilo oscuro PMTiles offline) + proyección del corredor
        MapLibreCorridorSurface(
            state = state,
            onSelectRider = { shortId -> liveMapViewModel.controller.selectRiderMarker(shortId) },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Barra superior: batería, nodos conectados, estado de mesh, GPS y selector Modo Guantes
        LiveMapTopStatusBar(
            state = state,
            onToggleGlovesMode = { enabled -> liveMapViewModel.controller.toggleGlovesMode(enabled) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
        )

        // 3. Controles laterales de cámara (≥ 72 dp)
        MapCameraControlsColumn(
            glovesMode = state.glovesMode,
            isFollowingLeader = state.cameraMode == CameraMode.FOLLOWING_LEADER,
            followLeaderRemainingSeconds = state.followLeaderRemainingSeconds,
            onCenterMyLocation = {
                liveMapViewModel.controller.centerOnMyLocation()
            },
            onFollowLeader = {
                liveMapViewModel.controller.followLeader(
                    nowEpochMs = System.currentTimeMillis(),
                    scheduleAutoRevert = true
                )
            },
            onToggleLayerInfo = {
                showOfflineTilesSheet = !showOfflineTilesSheet
            },
            onZoomIn = { liveMapViewModel.controller.zoomBy(+1.0) },
            onZoomOut = { liveMapViewModel.controller.zoomBy(-1.0) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        )

        // 4. Tarjeta informativa de PMTiles offline (cuando se toca "PMTiles" en modo normal)
        if (showOfflineTilesSheet) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .background(BikerColors.SurfaceDark, RoundedCornerShape(10.dp))
                    .border(1.dp, BikerColors.PrimaryAmber, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Corredor Offline PMTiles (A)",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Archivo: ${state.pmtilesAssetUri}",
                    color = BikerColors.TextPrimary,
                    fontSize = 11.sp
                )
                Text(
                    text = "Estilo: ${state.styleAssetUri}",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // 5. Bloque inferior: Pin del líder + Atribución OpenStreetMap + 3 acciones máximas en modo guantes
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Pin del líder fijo + estado de cámara + atribución OpenStreetMap siempre visible
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.LeaderPin,
                        contentDescription = "Pin del líder",
                        tint = BikerColors.PrimaryAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    val leaderText = state.leaderPin?.let {
                        "Pin Líder fijo: ${it.abbreviatedLabel} (${String.format("%.4f", it.latitude)}, ${String.format("%.4f", it.longitude)})"
                    } ?: "Pin Líder fijo"
                    Text(
                        text = leaderText,
                        color = BikerColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = state.osmAttribution,
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp
                )
            }

            actionFeedback?.let { feedback ->
                Text(
                    text = feedback,
                    color = BikerColors.PrimaryAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Máximo 3 acciones flotantes en modo guantes (Hablar, SOS, Señales) ≥ 72 dp
            GlovesPrimaryActionBar(
                isSosActive = rideServiceState.isSosActive,
                onTalkPttClick = {
                    actionFeedback = "PTT: listo para grabar clip de voz (C2)"
                },
                onSosClick = {
                    val sosIntent = Intent(context, RideService::class.java).apply {
                        action = RideService.ACTION_TRIGGER_SOS
                    }
                    runCatching { context.startService(sosIntent) }
                    actionFeedback = "SOS emitido por la mesh con prioridad máxima"
                },
                onQuickSignalsClick = {
                    actionFeedback = "Señales rápidas: Parada · Peligro · Gasolina · Reagrupar (C3)"
                }
            )
        }
    }
}

@Composable
private fun LiveMapTopStatusBar(
    state: LiveMapUiState,
    onToggleGlovesMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(BikerColors.SurfaceDark.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
            .border(1.dp, BikerColors.PrimaryAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Estado de la mesh + número de nodos
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val meshColor = when (state.connectivityState) {
                    LiveMapConnectivityState.MESH_CONNECTED -> BikerColors.StatusGreen
                    LiveMapConnectivityState.MESH_PARTIAL_ISLANDS -> BikerColors.StatusYellow
                    LiveMapConnectivityState.CONNECTING, LiveMapConnectivityState.ALONE -> BikerColors.TextSecondary
                }
                Icon(
                    imageVector = AppIcons.BleFieldTest,
                    contentDescription = null,
                    tint = meshColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${state.connectivityState.label} · ${state.connectedNodesCount} nodos",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Estado GPS + Batería
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (state.gpsFixed) AppIcons.GpsFixed else AppIcons.GpsNotFixed,
                    contentDescription = if (state.gpsFixed) "GPS fijado" else "GPS sin fijar",
                    tint = if (state.gpsFixed) BikerColors.StatusGreen else BikerColors.StatusYellow,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (state.gpsFixed) "GPS OK" else "Sin GPS",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp
                )
                Icon(
                    imageVector = AppIcons.BatteryStd,
                    contentDescription = "Batería",
                    tint = if (state.localBatteryPercent < 20) BikerColors.StatusRed else BikerColors.StatusGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${state.localBatteryPercent}%",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.cameraMode == CameraMode.FOLLOWING_LEADER) {
                    "Cámara: Siguiendo al líder (${state.followLeaderRemainingSeconds} s restantes)"
                } else {
                    "Cámara libre · Zoom ${state.cameraZoom.roundToInt()}x · PMTiles Offline"
                },
                color = BikerColors.TextSecondary,
                fontSize = 11.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Modo Guantes (≥72dp)",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = state.glovesMode,
                    onCheckedChange = onToggleGlovesMode
                )
            }
        }
    }
}

@Composable
private fun MapLibreCorridorSurface(
    state: LiveMapUiState,
    onSelectRider: (UShort) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Intentar inicializar MapLibre nativo cuando corre en dispositivo Android con GL disponible
        AndroidView(
            factory = { ctx ->
                runCatching {
                    MapLibre.getInstance(ctx)
                    MapView(ctx).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.setStyle(state.styleAssetUri)
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(state.cameraLatitude, state.cameraLongitude))
                                .zoom(state.cameraZoom)
                                .build()
                        }
                    }
                }.getOrElse {
                    android.view.View(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
                    }
                }
            },
            update = { view ->
                if (view is MapView) {
                    view.getMapAsync { map ->
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(state.cameraLatitude, state.cameraLongitude))
                            .zoom(state.cameraZoom)
                            .build()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Trazado del corredor offline ±10 km en alto contraste para visibilidad bajo el sol
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width * 0.45f
            drawLine(
                color = Color(0xFF1F2937),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 36f
            )
            drawLine(
                color = BikerColors.PrimaryAmber.copy(alpha = 0.65f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 4f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
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
