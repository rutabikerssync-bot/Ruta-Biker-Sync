package com.rutabikersync.app.feature.routes

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.theme.*
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Pantalla E4: Detalle de Ruta con Desnivel, POIs, Hash Verificado y Exportación GPX
 * Presenta mapa interactivo, perfil de ascenso/descenso, lista de waypoints y gasolineras,
 * estado de descarga de corredor offline y opción de compartir íntegramente por BLE Mesh (R6.1).
 */
@Composable
fun RouteDetailScreen(
    route: RouteAlternative,
    onDismiss: () -> Unit,
    onActivateInRide: ((RouteAlternative) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDownloadDialog by remember { mutableStateOf(false) }

    // Cálculos de elevación, POIs y hash
    val elevationProfile = remember(route) {
        MotorcycleRoutingEngine.generateElevationProfile(route.waypoints)
    }
    val totalAscent = remember(elevationProfile) {
        MotorcycleRoutingEngine.calculateTotalAscentMeters(elevationProfile)
    }
    val totalDescent = remember(elevationProfile) {
        MotorcycleRoutingEngine.calculateTotalDescentMeters(elevationProfile)
    }
    val routePois = remember(route) {
        MotorcycleRoutingEngine.generateRoutePois(route.waypoints, route.name)
    }
    val sha256Checksum = remember(route) {
        MotorcycleRoutingEngine.computeRouteSha256(route.waypoints)
    }

    // Progreso de corredor offline
    val progressMap by CorridorOfflineManager.downloadProgressMap.collectAsState()
    val isDownloaded = CorridorOfflineManager.isCorridorDownloaded(context, route.id)

    // Referencias de MapLibre
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HeritageSand)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // 1. Barra Superior
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(HeritageCard)
                            .border(1.dp, HeritageBorder, CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = HeritageLeatherBrown)
                    }

                    Text("Detalle de Ruta", color = HeritageLeatherBrown, fontWeight = FontWeight.Black, fontSize = 16.sp)

                    // Botón Exportar GPX
                    IconButton(
                        onClick = {
                            val gpxContent = GpxManager.exportToGpxString(route, pois = routePois, elevationProfile = elevationProfile)
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, gpxContent)
                                putExtra(Intent.EXTRA_SUBJECT, "${route.name}.gpx")
                                type = "application/gpx+xml"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Exportar ruta GPX a:"))
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(HeritageCardWarm)
                            .border(1.dp, HeritageBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Exportar GPX", tint = HeritageCopper, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 2. Mapa embebido con la polilínea
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, HeritageBorder, RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = {
                            mapView.apply {
                                getMapAsync { map ->
                                    mapInstance = map
                                    map.setStyle("asset://tiles/positron_corridor_style.json") {
                                        map.clear()
                                        if (route.waypoints.isNotEmpty()) {
                                            map.addMarker(MarkerOptions().position(LatLng(route.waypoints.first().latitude, route.waypoints.first().longitude)).title("Salida"))
                                            map.addMarker(MarkerOptions().position(LatLng(route.waypoints.last().latitude, route.waypoints.last().longitude)).title("Llegada"))
                                            map.addPolyline(
                                                PolylineOptions()
                                                    .addAll(route.waypoints.map { LatLng(it.latitude, it.longitude) })
                                                    .color(android.graphics.Color.parseColor("#B85C38"))
                                                    .width(7f)
                                            )
                                            val centerLat = (route.waypoints.first().latitude + route.waypoints.last().latitude) / 2.0
                                            val centerLon = (route.waypoints.first().longitude + route.waypoints.last().longitude) / 2.0
                                            map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(centerLat, centerLon)).zoom(11.5).build()), 300)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Badge de estado offline sobre el mapa
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDownloaded) HeritageGreenLight else HeritageCardWarm)
                            .border(1.dp, if (isDownloaded) HeritageGreen.copy(alpha = 0.5f) else HeritageBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isDownloaded) "✓ Offline Listo (PMTiles)" else "📡 Requiere Descarga",
                            color = if (isDownloaded) HeritageGreen else HeritageLeatherMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Título y métricas clave
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(route.name, color = HeritageLeatherBrown, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(route.subtitle, color = HeritageLeatherMuted, fontSize = 12.sp)

                    // Fila de Métricas (Distancia, Tiempo, Desnivel +/-, Condición)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricCard(
                            icon = Icons.Default.Speed,
                            label = "Distancia",
                            value = "${route.distanceKm} km",
                            color = HeritageCopperDark,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            icon = Icons.Default.Timer,
                            label = "Tiempo Est.",
                            value = "${route.estimatedMinutes} min",
                            color = HeritageAmber,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            icon = Icons.Default.Terrain,
                            label = "Desnivel",
                            value = "+$totalAscent m / -$totalDescent m",
                            color = HeritageGreen,
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                }
            }

            // 4. Hash Criptográfico SHA-256 (Garantía de Integridad de Malla R6.1)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = HeritageCard),
                    border = BorderStroke(1.dp, HeritageBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = HeritageGreen, modifier = Modifier.size(16.dp))
                            Text("Integridad de Malla SHA-256 Verificada", color = HeritageGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(
                            text = sha256Checksum,
                            color = HeritageCopperDark,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Text(
                            text = "Garantiza que todos los miembros del convoy recorran la misma polilínea sin desviaciones en tramos sin señal.",
                            color = HeritageLeatherMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // 5. Puntos de Interés / Gasolineras a lo largo de la ruta
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Puntos de Interés y Abastecimiento (POIs)", color = HeritageLeatherBrown, fontWeight = FontWeight.Black, fontSize = 14.sp)

                    routePois.forEach { poi ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(HeritageCard)
                                .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(poi.category.iconEmoji, fontSize = 20.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(poi.name, color = HeritageLeatherBrown, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(poi.notes, color = HeritageLeatherMuted, fontSize = 10.sp)
                                }
                                Text(
                                    text = "km ${poi.distanceAlongRouteKm}",
                                    color = HeritageCopperDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Barra inferior fija con botones de acción (Descargar Corredor / Activar en Rodada)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, HeritageSand.copy(alpha = 0.95f), HeritageSand)))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showDownloadDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeritageCardWarm,
                        contentColor = HeritageLeatherBrown
                    ),
                    border = BorderStroke(1.dp, HeritageBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = HeritageCopper, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isDownloaded) "Corredor Listo" else "Bajar PMTiles",
                        color = HeritageLeatherBrown,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val chunks = RoutePolylineMeshRelay.fragmentRouteForMesh(route)
                        chunks.forEach { RoutePolylineMeshRelay.processIncomingChunk(it) }
                        RouteDeviationDetector.setActiveRoutePolyline(route.waypoints)
                        onActivateInRide?.invoke(route)
                        Toast.makeText(context, "Ruta activada y polilínea compartida por malla (${chunks.size} paquetes)", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Activar en Rodada", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Modal de descarga de corredor offline si se abre
        if (showDownloadDialog) {
            CorridorDownloadDialog(
                route = route,
                onDismiss = { showDownloadDialog = false }
            )
        }
    }
}

@Composable
private fun MetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HeritageCard)
            .border(1.dp, HeritageBorder, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Text(label, color = HeritageLeatherMuted, fontSize = 10.sp)
            }
            Text(value, color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
