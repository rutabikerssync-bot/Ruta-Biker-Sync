package com.rutabikersync.app.feature.livemap

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import com.rutabikersync.app.core.mesh.MeshTelemetry
import com.rutabikersync.app.feature.routes.LatLngPoint

/**
 * Estado local de renderizado de la cámara. Se guarda fuera del composable para que
 * `LiveMapScreen` no crezca: ese método ya supera el límite de instrucciones de ART.
 */
class NavCameraRenderState {
    var lastRequestedCamera: CameraPosition? = null
    var lastRequestedBoundsKey: String? = null
}

/** Bounds construidos solo con posiciones reales y recientes; nunca con (0,0). */
fun mapBoundsOf(points: Iterable<Pair<Double, Double>>): MapBounds? {
    val valid = points.filter { (lat, lon) -> MeshTelemetry.isValidPosition(lat, lon) }
    if (valid.isEmpty()) return null
    return MapBounds(
        north = valid.maxOf { it.first },
        south = valid.minOf { it.first },
        east = valid.maxOf { it.second },
        west = valid.minOf { it.second }
    )
}

/** Bounds de vista general: waypoints de la ruta + riders con posición fiable. */
fun navigationBounds(
    waypoints: List<LatLngPoint>,
    markers: List<RiderMarkerUiModel>
): MapBounds? {
    val points = buildList {
        waypoints.forEach { add(it.latitude to it.longitude) }
        markers.forEach { marker ->
            val tooOld = marker.positionAgeSeconds?.let { it > 60L } ?: false
            if (!tooOld) add(marker.targetLatitude to marker.targetLongitude)
        }
    }
    return mapBoundsOf(points)
}

/**
 * Aplica la cámara de navegación sobre MapLibre. Se llama desde el `update` del
 * `AndroidView`; toda la lógica vive aquí para no engordar `LiveMapScreen`.
 */
fun applyNavigationCamera(
    map: MapLibreMap,
    state: LiveMapUiState,
    density: Float,
    renderState: NavCameraRenderState
) {
    // El usuario tomó el control: ni un tick de GPS ni una recomposición lo recupera.
    if (state.navCameraMode == NavCameraMode.FREE_INSPECT) return

    val overview = state.overviewBounds
    if (state.navCameraMode == NavCameraMode.ROUTE_OVERVIEW &&
        overview != null && overview.isValid()
    ) {
        val boundsKey = "${overview.north}|${overview.south}|${overview.east}|${overview.west}"
        if (renderState.lastRequestedBoundsKey == boundsKey) return
        renderState.lastRequestedBoundsKey = boundsKey
        renderState.lastRequestedCamera = null

        val latSpan = overview.north - overview.south
        val lonSpan = overview.east - overview.west
        val update = if (latSpan < 0.002 && lonSpan < 0.002) {
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(overview.centerLat, overview.centerLon))
                    .zoom(15.5)
                    .tilt(0.0)
                    .build()
            )
        } else {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(overview.south, overview.west))
                .include(LatLng(overview.north, overview.east))
                .build()
            CameraUpdateFactory.newLatLngBounds(
                bounds,
                (64 * density).toInt(),
                (104 * density).toInt(),
                (64 * density).toInt(),
                (72 * density).toInt()
            )
        }
        map.animateCamera(update, 450)
        return
    }

    if (!MeshTelemetry.isValidPosition(state.cameraTargetLat, state.cameraTargetLon)) return
    val current = map.cameraPosition
    val target = CameraPosition.Builder()
        .target(LatLng(state.cameraTargetLat, state.cameraTargetLon))
        .zoom(state.cameraZoom)
        .bearing(
            when {
                state.isNorthLocked -> 0.0
                state.navCameraMode == NavCameraMode.FOLLOW_HEADING_3D ->
                    state.navigationBearingDegrees.toDouble()
                else -> current?.bearing ?: 0.0
            }
        )
        .tilt(state.navigationTiltDegrees.toDouble())
        .build()
    if (renderState.lastRequestedCamera == target) return
    renderState.lastRequestedCamera = target
    renderState.lastRequestedBoundsKey = null
    map.animateCamera(CameraUpdateFactory.newCameraPosition(target), 450)
}
