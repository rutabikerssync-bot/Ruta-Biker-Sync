package com.rutabikersync.app.feature.routes

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Fases del flujo guiado de creación de ruta libre:
 * 1. SET_ORIGIN: El usuario define el lugar de salida y lo guarda.
 * 2. SET_DESTINATION: El usuario define el lugar de llegada y lo guarda.
 * 3. ROUTES_SELECTION: El mapa traza Ruta 1, Ruta 2 y Ruta 3; el usuario escoge una, la diferencia y finalmente guarda y comparte.
 */
enum class RoutePlanningStep {
    SET_ORIGIN,
    SET_DESTINATION,
    ROUTES_SELECTION
}

enum class RouteSelectionPhase {
    CHOOSE_TRACK,   // 3A: Escoger Ruta 1, 2 o 3 (Mapa 75% libre)
    INPUT_DETAILS   // 3B: Convocatoria y Guardar (Nombre, fecha, hora, descripción)
}

data class SearchPlaceResult(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

@Composable
fun ScheduleRideMapScreen(
    onDismiss: () -> Unit,
    onRideSaved: () -> Unit,
    onPublishClubRoute: ((title: String, startsAt: String, description: String?) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val geoPrefs = remember { context.getSharedPreferences("rbs_geo_cache", Context.MODE_PRIVATE) }
    val cachedLat = remember { geoPrefs.getFloat("last_known_lat", 0f).toDouble() }
    val cachedLon = remember { geoPrefs.getFloat("last_known_lon", 0f).toDouble() }
    val initialLat = if (cachedLat != 0.0) cachedLat else 10.349293
    val initialLon = if (cachedLon != 0.0) cachedLon else -67.034980

    val activityIntent = (context as? Activity)?.intent
    val startValenciaRoute = activityIntent?.getBooleanExtra("START_VALENCIA_ROUTE", false) ?: false
    val startJarilloRoute = activityIntent?.getBooleanExtra("START_JARILLO_ROUTE", false) ?: false
    val isAutoRoute = startValenciaRoute || startJarilloRoute

    // Paso actual del flujo
    var currentStep by remember {
        mutableStateOf(if (isAutoRoute) RoutePlanningStep.ROUTES_SELECTION else RoutePlanningStep.SET_ORIGIN)
    }

    // Origen (Punto de Salida)
    var originName by remember {
        mutableStateOf(if (isAutoRoute) "8XX8+J24, Los Teques" else "Mi ubicación actual")
    }
    var originSearchText by remember {
        mutableStateOf(if (isAutoRoute) "8XX8+J24, Los Teques" else "Mi ubicación actual")
    }
    var originLat by remember { mutableDoubleStateOf(if (isAutoRoute) 10.3538 else initialLat) }
    var originLon by remember { mutableDoubleStateOf(if (isAutoRoute) -67.0425 else initialLon) }
    var isOriginSaved by remember { mutableStateOf(isAutoRoute) }

    // Destino (Punto de Llegada)
    var destinationName by remember {
        mutableStateOf(
            when {
                startValenciaRoute -> "Ciudad Chávez"
                startJarilloRoute -> "El Jarillo"
                else -> ""
            }
        )
    }
    var destinationSearchText by remember {
        mutableStateOf(
            when {
                startValenciaRoute -> "Ciudad Chávez"
                startJarilloRoute -> "El Jarillo"
                else -> ""
            }
        )
    }
    var destinationLat by remember {
        mutableDoubleStateOf(
            when {
                startValenciaRoute -> 10.1500
                startJarilloRoute -> 10.3452
                else -> 0.0
            }
        )
    }
    var destinationLon by remember {
        mutableDoubleStateOf(
            when {
                startValenciaRoute -> -68.0000
                startJarilloRoute -> -67.1648
                else -> 0.0
            }
        )
    }
    var isDestinationSaved by remember { mutableStateOf(isAutoRoute) }

    // Las 3 alternativas viales (Ruta 1, Ruta 2, Ruta 3)
    var availableRoutes by remember { mutableStateOf<List<RouteAlternative>>(emptyList()) }
    var selectedRouteId by remember { mutableStateOf("route-1") }
    var currentPolylineMap by remember { mutableStateOf<Map<Polyline, String>>(emptyMap()) }
    var isCalculatingRoutes by remember { mutableStateOf(false) }
    val collapseRoutes = activityIntent?.getBooleanExtra("COLLAPSE_ROUTES", false) ?: false
    var isRoutesBoxExpanded by remember { mutableStateOf(!collapseRoutes) }
    var routeSelectionPhase by remember { mutableStateOf(RouteSelectionPhase.CHOOSE_TRACK) }

    // Foco de búsqueda activa para autocompletado ("origin", "destination", o null)
    var activeSearchTarget by remember { mutableStateOf<String?>(null) }

    // Modal de opciones de retorno
    var showAdditionalDetailsModal by remember { mutableStateOf(false) }
    var showCorridorDownloadDialog by remember { mutableStateOf(false) }
    var returnStrategy by remember { mutableStateOf(ReturnStrategy.SAME_ROUTE) }
    var dispersionPointName by remember { mutableStateOf("") }
    var dispersionLat by remember { mutableDoubleStateOf(0.0) }
    var dispersionLon by remember { mutableDoubleStateOf(0.0) }
    var rideTitle by remember(originName, destinationName) {
        mutableStateOf(if (destinationName.isNotBlank()) "Rodada Oficial: $originName ➔ $destinationName" else "Nueva Rodada")
    }
    var rideDescription by remember { mutableStateOf("") }
    var rideStartsAt by remember {
        mutableStateOf(LocalDateTime.now().plusDays(1).withSecond(0).withNano(0))
    }
    var rideMetadataError by remember { mutableStateOf<String?>(null) }
    val rideZoneId = remember { ZoneId.systemDefault() }
    val dateSchedule = rideStartsAt.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val meetingTime = rideStartsAt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    val departureTime = meetingTime
    var safeArrivalCheckInEnabled by remember { mutableStateOf(true) }

    fun validatedRideMetadata(): RideMetadataInput? {
        return RideMetadataRules.validate(
            title = rideTitle,
            description = rideDescription,
            localDateTime = rideStartsAt,
            zoneId = rideZoneId
        ).fold(
            onSuccess = {
                rideMetadataError = null
                it
            },
            onFailure = {
                rideMetadataError = it.message ?: "Revisa los datos de la rodada"
                null
            }
        )
    }

    val currentSelectedRoute = availableRoutes.find { it.id == selectedRouteId }
        ?: availableRoutes.firstOrNull()

    // Importación GPX opcional
    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val imported = GpxManager.parseGpxStream(inputStream, "Ruta GPX Importada")
                    if (imported.waypoints.isNotEmpty()) {
                        destinationName = imported.name
                        destinationSearchText = imported.name
                        destinationLat = imported.waypoints.last().latitude
                        destinationLon = imported.waypoints.last().longitude
                        isOriginSaved = true
                        isDestinationSaved = true
                        availableRoutes = listOf(imported)
                        selectedRouteId = imported.id
                        currentStep = RoutePlanningStep.ROUTES_SELECTION
                        Toast.makeText(context, "✓ Ruta GPX cargada: ${imported.name} (${imported.distanceKm} km)", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                Toast.makeText(context, "Error al leer archivo GPX", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    // Actualiza marcadores y traza las 3 rutas viales diferenciadas en MapLibre
    fun refreshMapLayers(map: MapLibreMap) {
        map.clear()

        // 1. Marcador Origen (siempre visible)
        map.addMarker(
            MarkerOptions()
                .position(LatLng(originLat, originLon))
                .title("🟢 Salida: $originName")
        )

        // 2. Marcador Destino (visible a partir de Paso 2 si las coordenadas son válidas)
        if ((currentStep != RoutePlanningStep.SET_ORIGIN || isDestinationSaved) && destinationLat != 0.0 && destinationLon != 0.0) {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(destinationLat, destinationLon))
                    .title("🏁 Llegada: $destinationName")
            )
        }

        // 3. Trazado de las 3 rutas en modo selección con Snap-to-Roads aplicado a todas las polilíneas
        if (currentStep == RoutePlanningStep.ROUTES_SELECTION && availableRoutes.isNotEmpty()) {
            val polyMap = mutableMapOf<Polyline, String>()

            // Dibujar primero las rutas no seleccionadas (Gris / Púrpura suave)
            val nonSelected = availableRoutes.filter { it.id != selectedRouteId }
            nonSelected.forEachIndexed { idx, altRoute ->
                val points = altRoute.waypoints
                    .filter { it.latitude in 0.5..13.0 && it.longitude in -74.0..-59.0 && (it.latitude != 0.0 || it.longitude != 0.0) }
                    .map { LatLng(it.latitude, it.longitude) }
                val altColor = if (idx == 0) "#94A3B8" else "#A78BFA"
                val p = map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(android.graphics.Color.parseColor(altColor))
                        .width(4.5f)
                )
                polyMap[p] = altRoute.id
            }

            // Dibujar encima la ruta seleccionada (Azul vibrante Google Maps)
            val selected = currentSelectedRoute ?: availableRoutes.first()
            val selPoints = selected.waypoints
                .filter { it.latitude in 0.5..13.0 && it.longitude in -74.0..-59.0 && (it.latitude != 0.0 || it.longitude != 0.0) }
                .map { LatLng(it.latitude, it.longitude) }
            val selPolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(selPoints)
                    .color(android.graphics.Color.parseColor("#2563EB"))
                    .width(6.5f)
            )
            polyMap[selPolyline] = selected.id
            currentPolylineMap = polyMap

            // Fase B1: Renderizar chips de tiempo flotantes para TODAS las rutas viales (estilo Google Maps)
            val iconFactory = IconFactory.getInstance(context)

            availableRoutes.forEachIndexed { idx, route ->
                val isSelectedRoute = (route.id == selected.id)
                // Usar fracciones diferenciadas a lo largo de cada ruta para que los chips no choquen
                val fraction = when (idx) {
                    0 -> 0.42 // Eje ARC
                    1 -> 0.60 // Eje Carretera Nacional
                    else -> 0.78 // Eje Variante Bárbula
                }
                val chipPoint = RouteMapOverlayHelper.calculateRoutePointAtFraction(route.waypoints, fraction)
                val durationText = RouteMapOverlayHelper.formatRouteDuration(route.estimatedMinutes)
                val subtitle = if (isSelectedRoute) "${route.distanceKm.toInt()} km" else null
                val chipBitmap = RouteMapOverlayHelper.createTimeChipBitmap(
                    context = context,
                    timeText = durationText,
                    subtitleText = subtitle,
                    isSelected = isSelectedRoute
                )
                val icon = iconFactory.fromBitmap(chipBitmap)
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(chipPoint.latitude, chipPoint.longitude))
                        .icon(icon)
                        .title("ROUTE_CHIP:${route.id}")
                )
            }

            // Centrar y encuadrar cámara sobre la rodada completa usando LatLngBounds con padding asimétrico (Fase A1)
            val allPts = selPoints
            val minLat = allPts.minOf { it.latitude }
            val maxLat = allPts.maxOf { it.latitude }
            val minLon = allPts.minOf { it.longitude }
            val maxLon = allPts.maxOf { it.longitude }

            val focusVictoria = activityIntent?.getBooleanExtra("FOCUS_VICTORIA", false) ?: false
            val focusValencia = activityIntent?.getBooleanExtra("FOCUS_VALENCIA", false) ?: false
            val focusJarillo = activityIntent?.getBooleanExtra("FOCUS_JARILLO", false) ?: false

            if (focusVictoria) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(10.2260, -67.3320))
                            .zoom(14.8)
                            .build()
                    ),
                    500
                )
            } else if (focusValencia) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(10.1820, -67.9850))
                            .zoom(14.8)
                            .build()
                    ),
                    500
                )
            } else if (focusJarillo) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(10.3500, -67.1350))
                            .zoom(14.5)
                            .build()
                    ),
                    500
                )
            } else {
                try {
                    val boundsBuilder = LatLngBounds.Builder()
                    // Incluir solo puntos válidos dentro del territorio venezolano para evitar encuadres oceánicos
                    val validRoutesPoints = availableRoutes.flatMap { it.waypoints }
                        .filter { pt ->
                            pt.latitude in 0.5..13.0 && pt.longitude in -74.0..-59.0 &&
                                (pt.latitude != 0.0 || pt.longitude != 0.0)
                        }
                    if (validRoutesPoints.isNotEmpty()) {
                        for (pt in validRoutesPoints) {
                            boundsBuilder.include(LatLng(pt.latitude, pt.longitude))
                        }
                    } else {
                        val validSel = selPoints.filter { pt ->
                            pt.latitude in 0.5..13.0 && pt.longitude in -74.0..-59.0 &&
                                (pt.latitude != 0.0 || pt.longitude != 0.0)
                        }
                        for (pt in validSel) {
                            boundsBuilder.include(pt)
                        }
                    }
                    val bounds = boundsBuilder.build()

                    val density = context.resources.displayMetrics.density
                    val padLeft = (45 * density).toInt()
                    val padRight = (45 * density).toInt()
                    val padTop = (140 * density).toInt()     // Libra panel superior "Paso 3"
                    val padBottom = (290 * density).toInt()  // Libra panel inferior de selección de rutas

                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(
                            bounds,
                            padLeft,
                            padTop,
                            padRight,
                            padBottom
                        ),
                        600
                    )
                } catch (_: Exception) {
                    // Fallback proporcional si la vista MapView aún no reporta dimensiones en layout pass inicial
                    val centerLat = (minLat + maxLat) / 2.0
                    val centerLon = (minLon + maxLon) / 2.0
                    val latSpan = Math.abs(maxLat - minLat)
                    val lonSpan = Math.abs(maxLon - minLon)
                    val maxSpan = Math.max(latSpan, lonSpan)
                    val approxZoom = when {
                        maxSpan > 2.0 -> 7.8
                        maxSpan > 1.0 -> 8.8
                        maxSpan > 0.5 -> 9.8
                        maxSpan > 0.2 -> 10.8
                        maxSpan > 0.08 -> 11.8
                        else -> 12.8
                    }
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(centerLat, centerLon))
                                .zoom(approxZoom)
                                .build()
                        ),
                        450
                    )
                }
            }
        } else {
            // Encuadrar sobre el punto activo (nunca volar a 0.0, 0.0 en el océano)
            val focusLat = when {
                currentStep == RoutePlanningStep.SET_DESTINATION && destinationLat != 0.0 -> destinationLat
                originLat != 0.0 -> originLat
                else -> 10.3444
            }
            val focusLon = when {
                currentStep == RoutePlanningStep.SET_DESTINATION && destinationLon != 0.0 -> destinationLon
                originLon != 0.0 -> originLon
                else -> -67.0422
            }
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(focusLat, focusLon))
                        .zoom(13.5)
                        .build()
                ),
                300
            )
        }
    }

    // Función para calcular las 3 alternativas viales
    fun triggerCalculateThreeRoutes() {
        val isOriginValid = originLat in 0.5..13.0 && originLon in -74.0..-59.0
        val isDestValid = destinationLat in 0.5..13.0 && destinationLon in -74.0..-59.0

        if (!isOriginValid || !isDestValid) {
            // Bloqueo total: Jamás calcular hacia (0.0, 0.0) ni fuera del territorio nacional
            return
        }

        coroutineScope.launch {
            isCalculatingRoutes = true
            val routes = withContext(Dispatchers.IO) {
                RoadRoutingEngine.fetchThreeRouteAlternatives(
                    origin = LatLngPoint(originLat, originLon),
                    destination = LatLngPoint(destinationLat, destinationLon),
                    originName = originName,
                    destinationName = destinationName
                )
            }

            // Filtrar waypoints válidos en tierra firme sobre calzada real
            val sanitized = routes.map { route ->
                route.copy(
                    waypoints = route.waypoints.filter { pt ->
                        pt.latitude in 0.5..13.0 && pt.longitude in -74.0..-59.0 &&
                        !RoadGeometryCleaner.isInsideWaterBody(pt)
                    }
                )
            }.filter { it.waypoints.size >= 2 }

            availableRoutes = sanitized
            val selectRuta2 = activityIntent?.getBooleanExtra("SELECT_RUTA_2", false) ?: false
            val selectRuta3 = activityIntent?.getBooleanExtra("SELECT_RUTA_3", false) ?: false
            selectedRouteId = when {
                selectRuta2 && sanitized.size >= 2 -> sanitized[1].id
                selectRuta3 && sanitized.size >= 3 -> sanitized[2].id
                else -> sanitized.firstOrNull()?.id ?: "route-1"
            }
            isCalculatingRoutes = false

            if (sanitized.isEmpty()) {
                // Fallback explícito: Si Mapbox falla y no hay asset para este destino, mostrar error sin dibujar líneas rectas
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "⚠️ No se pudo calcular la ruta sobre vías reales. Verifica tu conexión a internet o descarga los mapas offline en PMTiles.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                currentStep = RoutePlanningStep.ROUTES_SELECTION
            }
            mapInstance?.let { refreshMapLayers(it) }
        }
    }

    LaunchedEffect(startValenciaRoute, startJarilloRoute) {
        if (isAutoRoute) {
            triggerCalculateThreeRoutes()
        }
    }

    // Función para compartir por WhatsApp / Telegram / Redes
    fun shareRouteSummary(route: RouteAlternative) {
        val shareText = """
            🏍️ *RODADA CONVOCADA — RUTA BIKER SYNC*
            ━━━━━━━━━━━━━━━━━━━━━
            🟢 *Salida:* $originName
            🏁 *Llegada:* $destinationName
            🛣️ *Ruta Elegida:* ${route.name}
            📏 *Distancia:* ${route.distanceKm} km
            ⏱️ *Tiempo Estimado:* ${route.estimatedMinutes} min
            🏕️ *Condición:* ${route.roadCondition}
            
            📲 ¡Ruta lista y sincronizada offline en la red mesh para todo el club!
        """.trimIndent()

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        val shareChooser = Intent.createChooser(sendIntent, "Compartir ruta con el club:")
        context.startActivity(shareChooser)
    }

    var isSearchingPlace by remember { mutableStateOf(false) }

    // Función que busca el destino o salida escrito en el mapa usando catálogo local, Geocoder y Nominatim OSM
    fun performSearchOnMap(target: String) {
        val query = (if (target == "origin") originSearchText else destinationSearchText).trim()
        if (query.isBlank()) {
            Toast.makeText(context, "Escribe un lugar o dirección para buscar", Toast.LENGTH_SHORT).show()
            return
        }

        isSearchingPlace = true
        focusManager.clearFocus()
        activeSearchTarget = null

        coroutineScope.launch {
            var foundResult: SearchPlaceResult? = null

            // 1. Catálogo local para motociclistas (instantáneo y offline)
            val catalogMatches = BikerPlacesCatalog.searchPlaces(query)
            val catalogMatch = catalogMatches.find { it.name.equals(query, ignoreCase = true) }
                ?: catalogMatches.firstOrNull()

            if (catalogMatch != null) {
                foundResult = SearchPlaceResult(
                    name = catalogMatch.name,
                    latitude = catalogMatch.latitude,
                    longitude = catalogMatch.longitude
                )
            }

            // 2. Geocodificador del sistema Android
            if (foundResult == null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val list = runCatching { geocoder.getFromLocationName(query, 3) }.getOrNull()
                            ?: runCatching { geocoder.getFromLocationName("$query, Venezuela", 3) }.getOrNull()
                        val first = list?.firstOrNull()
                        if (first != null && first.hasLatitude() && first.hasLongitude()) {
                            val nameStr = first.featureName ?: first.thoroughfare ?: first.locality ?: query
                            foundResult = SearchPlaceResult(nameStr, first.latitude, first.longitude)
                        }
                    }
                }
            }

            // 3. Fallback online OpenStreetMap Nominatim
            if (foundResult == null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val urlStr = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&countrycodes=ve"
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3000
                            setRequestProperty("User-Agent", "RutaBikerSync/1.0 (Android)")
                        }
                        if (conn.responseCode == 200) {
                            val resp = conn.inputStream.bufferedReader().use { it.readText() }
                            val arr = JSONArray(resp)
                            if (arr.length() > 0) {
                                val obj = arr.getJSONObject(0)
                                val lat = obj.getDouble("lat")
                                val lon = obj.getDouble("lon")
                                val disp = obj.optString("name").ifBlank {
                                    obj.optString("display_name").substringBefore(",")
                                }.ifBlank { query }
                                foundResult = SearchPlaceResult(disp, lat, lon)
                            }
                        }
                    }
                }
            }

            isSearchingPlace = false

            if (foundResult != null) {
                val res = foundResult!!
                if (target == "origin") {
                    originLat = res.latitude
                    originLon = res.longitude
                    originName = res.name
                    originSearchText = res.name
                } else {
                    destinationLat = res.latitude
                    destinationLon = res.longitude
                    destinationName = res.name
                    destinationSearchText = res.name
                }

                mapInstance?.let { map ->
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(res.latitude, res.longitude))
                                .zoom(14.5)
                                .build()
                        ),
                        650
                    )
                    refreshMapLayers(map)
                }
                Toast.makeText(context, "📍 Ubicado en el mapa: ${res.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No se encontró \"$query\". Prueba con otra referencia o toca el mapa.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Aplica la ubicación GPS real del sensor y actualiza el mapa
    fun applyGpsLocation(loc: Location, showToast: Boolean) {
        geoPrefs.edit()
            .putFloat("last_known_lat", loc.latitude.toFloat())
            .putFloat("last_known_lon", loc.longitude.toFloat())
            .apply()

        originLat = loc.latitude
        originLon = loc.longitude

        coroutineScope.launch {
            val resolved = resolveLocationNameWithGeocoder(context, loc.latitude, loc.longitude)
            originName = resolved
            originSearchText = resolved
            mapInstance?.let { map ->
                refreshMapLayers(map)
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(loc.latitude, loc.longitude))
                            .zoom(14.8)
                            .build()
                    ),
                    350
                )
            }
            if (showToast) {
                val accText = if (loc.hasAccuracy()) " (±${loc.accuracy.toInt()}m)" else ""
                Toast.makeText(context, "📍 GPS satelital fijado$accText: $resolved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var requestLocationPermissions: (() -> Unit)? by remember { mutableStateOf(null) }

    fun fetchRealGpsLocation(forceHighAccuracy: Boolean = false, showToast: Boolean = false) {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            requestLocationPermissions?.invoke()
            return
        }

        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val candidateProviders = listOf(
            LocationManager.GPS_PROVIDER,
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var best: Location? = null
        for (provider in candidateProviders) {
            runCatching {
                val l = locManager.getLastKnownLocation(provider)
                if (l != null) {
                    if (best == null) {
                        best = l
                    } else {
                        val isMoreAccurate = l.hasAccuracy() && (!best!!.hasAccuracy() || l.accuracy < best!!.accuracy)
                        val isMuchNewer = (l.time - best!!.time) > 60_000
                        if (isMoreAccurate || isMuchNewer) {
                            best = l
                        }
                    }
                }
            }
        }

        best?.let { applyGpsLocation(it, showToast = !forceHighAccuracy && showToast) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && fineGranted) {
            runCatching {
                locManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor
                ) { freshLoc ->
                    if (freshLoc != null) {
                        applyGpsLocation(freshLoc, showToast = showToast)
                    }
                }
            }
        }

        runCatching {
            var updates = 0
            val singleListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    updates++
                    applyGpsLocation(location, showToast = showToast && updates == 1)
                    if (updates >= 2 || (location.hasAccuracy() && location.accuracy <= 15f)) {
                        runCatching { locManager.removeUpdates(this) }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    singleListener
                )
            } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    singleListener
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchRealGpsLocation(forceHighAccuracy = true, showToast = true)
        }
    }

    requestLocationPermissions = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    LaunchedEffect(Unit) {
        fetchRealGpsLocation(forceHighAccuracy = false, showToast = false)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
        // 1. MAPA INTERACTIVO FULL SCREEN
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.setStyle("asset://tiles/dark_corridor_style.json") {
                            map.uiSettings.isAttributionEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isScrollGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true
                            mapInstance = map

                            val initialLat = if (originLat != 0.0) originLat else 10.3444
                            val initialLon = if (originLon != 0.0) originLon else -67.0422
                            map.moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(initialLat, initialLon))
                                        .zoom(13.5)
                                        .build()
                                )
                            )

                            // Fase B2: Selección de ruta al tocar directamente la polilínea en el mapa
                            map.setOnPolylineClickListener { polyline ->
                                val targetRouteId = currentPolylineMap[polyline]
                                if (targetRouteId != null && selectedRouteId != targetRouteId) {
                                    selectedRouteId = targetRouteId
                                    val picked = availableRoutes.find { it.id == targetRouteId }
                                    picked?.let {
                                        Toast.makeText(context, "✓ ${it.name} seleccionada", Toast.LENGTH_SHORT).show()
                                    }
                                    refreshMapLayers(map)
                                }
                            }

                            // Fase B1: Selección de ruta al tocar el chip flotante en el mapa
                            map.setOnMarkerClickListener { marker ->
                                val title = marker.title
                                if (title != null && title.startsWith("ROUTE_CHIP:")) {
                                    val targetRouteId = title.removePrefix("ROUTE_CHIP:")
                                    if (selectedRouteId != targetRouteId) {
                                        selectedRouteId = targetRouteId
                                        val picked = availableRoutes.find { it.id == targetRouteId }
                                        picked?.let {
                                            Toast.makeText(context, "✓ ${it.name} (${RouteMapOverlayHelper.formatRouteDuration(it.estimatedMinutes)})", Toast.LENGTH_SHORT).show()
                                        }
                                        refreshMapLayers(map)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }

                            // Al tocar el mapa se fija la salida o la llegada según el paso activo
                            map.addOnMapClickListener { latLng ->
                                focusManager.clearFocus()
                                when (currentStep) {
                                    RoutePlanningStep.SET_ORIGIN -> {
                                        originLat = latLng.latitude
                                        originLon = latLng.longitude
                                        activeSearchTarget = null
                                        coroutineScope.launch {
                                            val name = resolveLocationNameWithGeocoder(context, latLng.latitude, latLng.longitude)
                                            originName = name
                                            originSearchText = name
                                            Toast.makeText(context, "🟢 Salida ubicada: $name", Toast.LENGTH_SHORT).show()
                                            refreshMapLayers(map)
                                        }
                                    }
                                    RoutePlanningStep.SET_DESTINATION -> {
                                        destinationLat = latLng.latitude
                                        destinationLon = latLng.longitude
                                        activeSearchTarget = null
                                        coroutineScope.launch {
                                            val name = resolveLocationNameWithGeocoder(context, latLng.latitude, latLng.longitude)
                                            destinationName = name
                                            destinationSearchText = name
                                            Toast.makeText(context, "🏁 Llegada ubicada: $name", Toast.LENGTH_SHORT).show()
                                            refreshMapLayers(map)
                                        }
                                    }
                                    RoutePlanningStep.ROUTES_SELECTION -> {
                                        // Fase B2: Al tocar en el mapa sobre o cerca de una ruta alternativa, seleccionarla directamente
                                        val touchPt = LatLngPoint(latLng.latitude, latLng.longitude)
                                        val closest = RouteMapOverlayHelper.findClosestRoute(touchPt, availableRoutes)
                                        if (closest != null && closest.second <= 5000.0) { // dentro de 5 km de la calzada
                                            if (selectedRouteId != closest.first.id) {
                                                selectedRouteId = closest.first.id
                                                Toast.makeText(context, "✓ ${closest.first.name} seleccionada", Toast.LENGTH_SHORT).show()
                                                refreshMapLayers(map)
                                            }
                                        }
                                    }
                                }
                                true
                            }

                            refreshMapLayers(map)
                        }
                    }
                }
            },
            update = {
                mapInstance?.let { refreshMapLayers(it) }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. PANEL SUPERIOR GUIADO SEGÚN EL PASO
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 12.dp, start = 10.dp, end = 10.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = HeritageCard.copy(alpha = 0.88f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(1.dp, HeritageBorder.copy(alpha = 0.70f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cabecera: Botón volver, título del paso y botón Swap
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    if (currentStep == RoutePlanningStep.ROUTES_SELECTION) {
                                        currentStep = RoutePlanningStep.SET_DESTINATION
                                    } else if (currentStep == RoutePlanningStep.SET_DESTINATION) {
                                        currentStep = RoutePlanningStep.SET_ORIGIN
                                    } else {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Volver",
                                    tint = HeritageLeatherBrown
                                )
                            }
                            Column {
                                val stepTitle = when (currentStep) {
                                    RoutePlanningStep.SET_ORIGIN -> "Paso 1: Lugar de Salida 🟢"
                                    RoutePlanningStep.SET_DESTINATION -> "Paso 2: Lugar de Llegada 🏁"
                                    RoutePlanningStep.ROUTES_SELECTION -> if (routeSelectionPhase == RouteSelectionPhase.CHOOSE_TRACK)
                                        "Paso 3A: Escoge tu Ruta (1, 2 o 3)"
                                    else
                                        "Paso 3B: Convocatoria y Salida"
                                }
                                Text(
                                    text = stepTitle,
                                    color = HeritageLeatherBrown,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (currentStep == RoutePlanningStep.ROUTES_SELECTION) {
                                        if (routeSelectionPhase == RouteSelectionPhase.CHOOSE_TRACK)
                                            "3 rutas trazadas sobre vías reales"
                                        else
                                            "Define los datos oficiales para compartir con el club"
                                    } else "Toca el mapa o escribe para ubicar el punto",
                                    color = HeritageLeatherMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Botón de Invertir (disponible cuando ambos están fijados)
                        if (isOriginSaved && (isDestinationSaved || currentStep == RoutePlanningStep.ROUTES_SELECTION)) {
                            IconButton(
                                onClick = {
                                    val tempName = originName
                                    val tempLat = originLat
                                    val tempLon = originLon
                                    originName = destinationName
                                    originLat = destinationLat
                                    originLon = destinationLon
                                    originSearchText = destinationName

                                    destinationName = tempName
                                    destinationLat = tempLat
                                    destinationLon = tempLon
                                    destinationSearchText = tempName
                                    triggerCalculateThreeRoutes()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Invertir",
                                    tint = HeritageCopper
                                )
                            }
                        }
                    }

                    // PASO 1: FIJAR SALIDA
                    if (currentStep == RoutePlanningStep.SET_ORIGIN) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16A34A))
                            )
                            OutlinedTextField(
                                value = originSearchText,
                                onValueChange = {
                                    originSearchText = it
                                    activeSearchTarget = "origin"
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .onFocusChanged { if (it.isFocused) activeSearchTarget = "origin" },
                                leadingIcon = {
                                    if (isSearchingPlace && activeSearchTarget == "origin") {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF16A34A), strokeWidth = 2.dp)
                                    } else {
                                        IconButton(
                                            onClick = { performSearchOnMap("origin") },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Buscar Salida en el mapa",
                                                tint = Color(0xFF16A34A),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { performSearchOnMap("origin") }),
                                placeholder = { Text("Buscar lugar de salida...", fontSize = 12.sp, color = HeritageLeatherMuted) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF16A34A),
                                    unfocusedBorderColor = HeritageBorder,
                                    focusedTextColor = HeritageLeatherBrown,
                                    unfocusedTextColor = HeritageLeatherBrown,
                                    focusedContainerColor = HeritageCardWarm.copy(alpha = 0.5f),
                                    unfocusedContainerColor = HeritageCardWarm.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            // Botón GPS mi ubicación precisa
                            IconButton(
                                onClick = {
                                    fetchRealGpsLocation(forceHighAccuracy = true, showToast = true)
                                    focusManager.clearFocus()
                                    activeSearchTarget = null
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = "Mi ubicación",
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Botón de confirmación para guardar Salida
                        Button(
                            onClick = {
                                originName = originSearchText.ifBlank { "Salida Ubicada" }
                                isOriginSaved = true
                                currentStep = RoutePlanningStep.SET_DESTINATION
                                focusManager.clearFocus()
                                activeSearchTarget = null
                                mapInstance?.let { map ->
                                    val targetLat = if (originLat != 0.0) originLat else 10.3444
                                    val targetLon = if (originLon != 0.0) originLon else -67.0422
                                    map.animateCamera(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder()
                                                .target(LatLng(targetLat, targetLon))
                                                .zoom(13.5)
                                                .build()
                                        ),
                                        500
                                    )
                                    refreshMapLayers(map)
                                }
                                Toast.makeText(context, "✓ Salida guardada: $originName", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("✓ Guardar Lugar de Salida ➔ Siguiente", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // PASO 2: FIJAR LLEGADA (con resumen de Salida visible)
                    if (currentStep == RoutePlanningStep.SET_DESTINATION) {
                        // Tarjeta resumen de Salida ya guardada
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(HeritageCardWarm.copy(alpha = 0.6f))
                                .clickable {
                                    currentStep = RoutePlanningStep.SET_ORIGIN
                                    activeSearchTarget = "origin"
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF16A34A)))
                                Text("Salida fijada: $originName", fontSize = 11.sp, color = HeritageLeatherBrown, fontWeight = FontWeight.SemiBold)
                            }
                            Icon(Icons.Default.Edit, contentDescription = "Editar", tint = HeritageCopper, modifier = Modifier.size(14.dp))
                        }

                        // Campo Destino
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(HeritageCopper)
                            )
                            OutlinedTextField(
                                value = destinationSearchText,
                                onValueChange = {
                                    destinationSearchText = it
                                    activeSearchTarget = "destination"
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .onFocusChanged { if (it.isFocused) activeSearchTarget = "destination" },
                                leadingIcon = {
                                    if (isSearchingPlace && activeSearchTarget == "destination") {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = HeritageCopper, strokeWidth = 2.dp)
                                    } else {
                                        IconButton(
                                            onClick = { performSearchOnMap("destination") },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Buscar Llegada en el mapa",
                                                tint = HeritageCopper,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { performSearchOnMap("destination") }),
                                placeholder = { Text("Buscar lugar de llegada...", fontSize = 12.sp, color = HeritageLeatherMuted) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HeritageCopper,
                                    unfocusedBorderColor = HeritageBorder,
                                    focusedTextColor = HeritageLeatherBrown,
                                    unfocusedTextColor = HeritageLeatherBrown,
                                    focusedContainerColor = HeritageCardWarm.copy(alpha = 0.5f),
                                    unfocusedContainerColor = HeritageCardWarm.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            IconButton(
                                onClick = {
                                    destinationSearchText = ""
                                    activeSearchTarget = "destination"
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = HeritageLeatherMuted, modifier = Modifier.size(18.dp))
                            }
                        }

                        // Botón de confirmación para guardar Llegada y trazar vías
                        Button(
                            onClick = {
                                if (destinationLat == 0.0 && destinationLon == 0.0) {
                                    if (destinationSearchText.isNotBlank()) {
                                        performSearchOnMap("destination")
                                    } else {
                                        Toast.makeText(context, "⚠️ Toca el mapa o escribe un lugar de llegada antes de trazar", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    destinationName = destinationSearchText.ifBlank { "Llegada Ubicada" }
                                    isDestinationSaved = true
                                    focusManager.clearFocus()
                                    activeSearchTarget = null
                                    triggerCalculateThreeRoutes()
                                    Toast.makeText(context, "✓ Llegada guardada. Trazando vías...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("✓ Guardar Llegada y Trazar Vías 🏍", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // PASO 3: RESUMEN DE RECORRIDO EN MODO SELECCIÓN
                    if (currentStep == RoutePlanningStep.ROUTES_SELECTION) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(HeritageCardWarm.copy(alpha = 0.7f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF16A34A)))
                                    Text(originName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HeritageLeatherBrown, maxLines = 1)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(HeritageCopper))
                                    Text(destinationName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HeritageCopper, maxLines = 1)
                                }
                            }
                            OutlinedButton(
                                onClick = { currentStep = RoutePlanningStep.SET_DESTINATION },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Cambiar", fontSize = 10.sp, color = HeritageLeatherBrown)
                            }
                        }
                    }

                    // Autocompletado mientras el usuario escribe en búsqueda
                    val query = if (activeSearchTarget == "origin") originSearchText else destinationSearchText
                    val matchingPlaces: List<BikerPlace> = remember(query) {
                        if (query.isNotBlank() && query.length >= 2) {
                            BikerPlacesCatalog.searchPlaces(query)
                        } else emptyList()
                    }
                    val showDropdown = activeSearchTarget != null && matchingPlaces.isNotEmpty() &&
                            !matchingPlaces.any { it.name.equals(query.trim(), ignoreCase = true) }

                    if (showDropdown) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(HeritageCardWarm)
                                .border(1.dp, HeritageCopper.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp)
                        ) {
                            matchingPlaces.take(3).forEach { place ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (activeSearchTarget == "origin") {
                                                originLat = place.latitude
                                                originLon = place.longitude
                                                originName = place.name
                                                originSearchText = place.name
                                            } else {
                                                destinationLat = place.latitude
                                                destinationLon = place.longitude
                                                destinationName = place.name
                                                destinationSearchText = place.name
                                            }
                                            activeSearchTarget = null
                                            focusManager.clearFocus()
                                            mapInstance?.let { map ->
                                                map.animateCamera(
                                                    CameraUpdateFactory.newCameraPosition(
                                                        CameraPosition.Builder()
                                                            .target(LatLng(place.latitude, place.longitude))
                                                            .zoom(14.5)
                                                            .build()
                                                    ),
                                                    600
                                                )
                                                refreshMapLayers(map)
                                            }
                                            Toast.makeText(context, "📍 Ubicado en el mapa: ${place.name}", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = HeritageCopper, modifier = Modifier.size(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(place.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HeritageLeatherBrown)
                                        Text(place.description, fontSize = 10.sp, color = HeritageLeatherMuted, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. BARRA INFERIOR DE SELECCIÓN DE RUTAS (1, 2 Y 3) Y GUARDAR + COMPARTIR
        if (currentStep == RoutePlanningStep.ROUTES_SELECTION) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HeritageCard.copy(alpha = 0.92f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(1.2.dp, HeritageBorder.copy(alpha = 0.85f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (routeSelectionPhase == RouteSelectionPhase.CHOOSE_TRACK) {
                        // === SUBFASE 3A: SELECCIÓN DE TRAZADO (MAPA 75% LIBRE) ===
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Carreteras y Avenidas Disponibles:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HeritageLeatherBrown
                                )
                                Text(
                                    text = "Toca para resaltar el trazado en el mapa",
                                    fontSize = 10.sp,
                                    color = HeritageLeatherMuted
                                )
                            }
                            Text(
                                text = "📍 ${currentSelectedRoute?.name?.substringBefore("·")?.trim() ?: "Ruta"} (${currentSelectedRoute?.distanceKm ?: 0.0} km)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HeritageCopper
                            )
                        }

                        // Selector horizontal compacto de las 3 Rutas
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableRoutes.forEachIndexed { index, route ->
                                val isSelected = route.id == selectedRouteId
                                val routeBadgeColor = when (index) {
                                    0 -> Color(0xFF2563EB) // Azul Principal
                                    1 -> Color(0xFF64748B) // Gris Alterna
                                    else -> Color(0xFF7C3AED) // Púrpura Variante
                                }

                                Box(
                                    modifier = Modifier
                                        .width(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) HeritageCardWarm.copy(alpha = 0.95f) else HeritageCard.copy(alpha = 0.72f))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) routeBadgeColor else HeritageBorder.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            selectedRouteId = route.id
                                            mapInstance?.let { refreshMapLayers(it) }
                                        }
                                        .padding(10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(routeBadgeColor)
                                            )
                                            Text(
                                                text = route.name.substringBefore("·").trim(),
                                                color = if (isSelected) routeBadgeColor else HeritageLeatherBrown,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 12.sp
                                            )
                                            if (isSelected) {
                                                Text("✓ Activa", color = routeBadgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Text(
                                            text = "${route.estimatedMinutes} min (${route.distanceKm} km)",
                                            color = HeritageCopper,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = route.subtitle,
                                            color = HeritageLeatherMuted,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Resumen y botón de confirmación de trazado
                        val activeRoute = currentSelectedRoute
                        if (activeRoute != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = activeRoute.roadCondition,
                                    fontSize = 10.sp,
                                    color = Color(0xFF1B4D3E),
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedButton(
                                    onClick = { showAdditionalDetailsModal = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("⚙ Retorno", fontSize = 10.sp, color = HeritageLeatherBrown)
                                }
                            }
                        }

                        Button(
                            onClick = { routeSelectionPhase = RouteSelectionPhase.INPUT_DETAILS },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDF6E32)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Aceptar Trazado y Definir Salida ➔",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        // === SUBFASE 3B: FORMULARIO DE CONVOCATORIA (ALTO CONTRASTE) ===
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = { routeSelectionPhase = RouteSelectionPhase.CHOOSE_TRACK },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Volver",
                                    tint = HeritageCopper,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "← Cambiar Trazado",
                                    color = HeritageCopper,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            currentSelectedRoute?.let { activeRoute ->
                                Text(
                                    text = "${activeRoute.name.substringBefore("·").trim()} (${activeRoute.distanceKm} km)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HeritageLeatherBrown
                                )
                            }
                        }

                        // Metadatos de la rodada (Nombre, Fecha, Hora, Descripción)
                        RideMetadataEditor(
                            title = rideTitle,
                            onTitleChange = { rideTitle = it },
                            startsAt = rideStartsAt,
                            onStartsAtChange = { rideStartsAt = it },
                            description = rideDescription,
                            onDescriptionChange = { rideDescription = it },
                            errorMessage = rideMetadataError,
                            onClearError = { rideMetadataError = null }
                        )

                        // Botón Secundario: Descarga explícita de mapa offline (PMTiles)
                        OutlinedButton(
                            onClick = {
                                if (currentSelectedRoute != null) {
                                    showCorridorDownloadDialog = true
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = HeritageCardWarm),
                            border = BorderStroke(1.2.dp, HeritageCopper),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = HeritageCopperDark, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "📥 Guardar Mapa Offline (PMTiles)",
                                    color = HeritageCopperDark,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Botón Principal: GUARDAR RUTA Y COMPARTIR CON EL CLUB
                        Button(
                            onClick = {
                                val finalRoute = currentSelectedRoute ?: return@Button
                                val metadata = validatedRideMetadata() ?: return@Button

                                // 1. Registrar y preparar paquete offline dinámico para el creador
                                val customRegion = CorridorOfflineManager.registerCustomRoute(
                                    routeId = finalRoute.id,
                                    routeName = finalRoute.name,
                                    distanceKm = finalRoute.distanceKm,
                                    waypoints = finalRoute.waypoints,
                                    bufferKm = 10,
                                    context = context
                                )
                                CorridorOfflineManager.startDownloadRegion(context, customRegion)

                                // 2. Guardar en memoria de desvío y fragmentar en malla BLE
                                RouteDeviationDetector.setActiveRoutePolyline(finalRoute.waypoints)
                                val chunks = RoutePolylineMeshRelay.fragmentRouteForMesh(finalRoute)
                                chunks.forEach { RoutePolylineMeshRelay.processIncomingChunk(it) }

                                // 3. Guardar en repositorio offline
                                RideScheduleRepository.scheduleNewRide(
                                    title = metadata.title,
                                    originName = originName,
                                    originLat = originLat,
                                    originLon = originLon,
                                    destinationName = destinationName,
                                    destinationLat = destinationLat,
                                    destinationLon = destinationLon,
                                    selectedRoute = finalRoute,
                                    availableRoutes = availableRoutes,
                                    returnStrategy = returnStrategy,
                                    dispersionPointName = dispersionPointName,
                                    dispersionLat = dispersionLat,
                                    dispersionLon = dispersionLon,
                                    meetingTime = meetingTime,
                                    departureTime = departureTime,
                                    dateSchedule = dateSchedule,
                                    distanceKm = finalRoute.distanceKm,
                                    estimatedMinutes = finalRoute.estimatedMinutes,
                                    description = metadata.description,
                                    startsAt = metadata.startsAt
                                )

                                // 4. Publicar con UUID real solo cuando hay un club/líder asociado.
                                onPublishClubRoute?.invoke(metadata.title, metadata.startsAt.toString(), metadata.description)

                                // 5. Compartir con el club vía Intent nativo
                                shareRouteSummary(finalRoute)

                                Toast.makeText(
                                    context,
                                    "✓ Ruta guardada offline y compartida (${chunks.size} paquetes mesh).",
                                    Toast.LENGTH_LONG
                                ).show()
                                onRideSaved()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "✓ Guardar Ruta y Compartir 🏍",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Modal de Retorno y Seguridad
        if (showAdditionalDetailsModal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showAdditionalDetailsModal = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HeritageSand)
                        .border(1.dp, HeritageBorder, RoundedCornerShape(16.dp))
                        .clickable(enabled = false) {}
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Plan de Retorno y Seguridad",
                            color = HeritageLeatherBrown,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        ReturnStrategyChoice(
                            title = "Retorno en Convoy (Misma Ruta)",
                            isSelected = returnStrategy == ReturnStrategy.SAME_ROUTE,
                            onClick = { returnStrategy = ReturnStrategy.SAME_ROUTE }
                        )
                        ReturnStrategyChoice(
                            title = "Circuito Cerrado (Ruta Alterna)",
                            isSelected = returnStrategy == ReturnStrategy.CIRCUIT_LOOP,
                            onClick = { returnStrategy = ReturnStrategy.CIRCUIT_LOOP }
                        )
                        ReturnStrategyChoice(
                            title = "Desconcentración Libre",
                            isSelected = returnStrategy == ReturnStrategy.OPEN_RETURN,
                            onClick = { returnStrategy = ReturnStrategy.OPEN_RETURN }
                        )

                        OutlinedTextField(
                            value = dispersionPointName,
                            onValueChange = { dispersionPointName = it },
                            label = { Text("Punto de Desconcentración") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HeritageCopper,
                                unfocusedBorderColor = HeritageBorder,
                                focusedTextColor = HeritageLeatherBrown,
                                unfocusedTextColor = HeritageLeatherBrown,
                                focusedLabelColor = HeritageCopper,
                                unfocusedLabelColor = HeritageLeatherMuted
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Protocolo 'Llegada Segura a Casa'", color = HeritageLeatherBrown, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Check-in de seguridad individual", color = HeritageLeatherMuted, fontSize = 10.sp)
                            }
                            Switch(
                                checked = safeArrivalCheckInEnabled,
                                onCheckedChange = { safeArrivalCheckInEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = HeritageCopper
                                )
                            )
                        }

                        HeritageBrassButton(
                            text = "Listo · Aplicar",
                            onClick = { showAdditionalDetailsModal = false },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Modal de Descarga de Corredor Offline (PMTiles)
        if (showCorridorDownloadDialog && currentSelectedRoute != null) {
            CorridorDownloadDialog(
                route = currentSelectedRoute!!,
                onDismiss = { showCorridorDownloadDialog = false }
            )
        }
    }
}

/**
 * Resuelve el nombre del lugar según las coordenadas seleccionadas en el mapa usando Geocoder real y catálogo biker.
 */
private suspend fun resolveLocationNameWithGeocoder(context: Context, lat: Double, lon: Double): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // 1. Si está muy cerca (< 400m) de un waypoint conocido del catálogo
    val matched = BikerPlacesCatalog.findClosestPlace(lat, lon)
    if (matched != null) return@withContext matched.name

    // 2. Intentar Geocoder nativo para obtener nombre real de la calle o avenida
    try {
        if (Geocoder.isPresent()) {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val road = addr.thoroughfare ?: addr.featureName
                val sector = addr.subLocality ?: addr.locality ?: addr.subAdminArea
                if (!road.isNullOrBlank() && !sector.isNullOrBlank()) {
                    return@withContext "$road, $sector"
                } else if (!road.isNullOrBlank()) {
                    return@withContext road
                } else if (!sector.isNullOrBlank()) {
                    return@withContext sector
                }
            }
        }
    } catch (_: Exception) {}

    resolveLocationNameFast(lat, lon)
}

private fun resolveLocationNameFast(lat: Double, lon: Double): String {
    val matched = BikerPlacesCatalog.findClosestPlace(lat, lon)
    if (matched != null) return matched.name

    return when {
        lon in -67.24..-67.12 && lat in 10.30..10.38 -> "Sector El Jarillo"
        lon < -67.24 && lat > 10.38 -> "Sector Colonia Tovar"
        lon in -67.09..-67.06 && lat in 10.34..10.38 -> "San Pedro de Los Altos"
        lon in -67.07..-67.04 && lat in 10.33..10.36 -> "Lagunetica (Los Teques)"
        lon in -67.05..-67.02 && lat in 10.33..10.37 -> "Los Teques Centro"
        lon in -67.14..-67.10 && lat in 10.35..10.38 -> "Pozo de Rosas"
        lon in -66.97..-66.91 && lat in 10.58..10.63 -> "La Guaira"
        lon in -66.98..-66.94 && lat in 10.36..10.39 -> "San Antonio de Los Altos"
        else -> String.format(Locale.US, "Vía (%.4f, %.4f)", lat, lon)
    }
}

@Composable
private fun ReturnStrategyChoice(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) HeritageCardWarm else HeritageCard)
            .border(1.dp, if (isSelected) HeritageCopper else HeritageBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = if (isSelected) HeritageCopper else HeritageLeatherBrown,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = HeritageCopper, modifier = Modifier.size(16.dp))
        }
    }
}
