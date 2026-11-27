package com.rutabikersync.app.feature.livemap

import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max

/**
 * Máquina de estados pura (JVM, sin dependencias de Android/MapLibre) para la cámara
 * de navegación del mapa en vivo.
 *
 * No sustituye a [CameraMode]: ese enum conserva el contrato histórico de
 * `FOLLOWING_LEADER` (10 s) y es la fuente de las coordenadas usadas por SOS.
 * La cámara de navegación decide **qué mira el mapa**, con tres estados:
 *  - [NavCameraMode.FOLLOW_HEADING_3D]: sigue al rider, inclinada y rotada por el bearing.
 *  - [NavCameraMode.ROUTE_OVERVIEW]: encuadra la ruta/resto de convoy.
 *  - [NavCameraMode.FREE_INSPECT]: el usuario tocó/arrastró el mapa; se congela hasta
 *    que pulse "Re-centrar". Un tick de GPS **no** debe recuperar el seguimiento.
 */
enum class NavCameraMode {
    FOLLOW_HEADING_3D,
    ROUTE_OVERVIEW,
    FREE_INSPECT
}

/** Eventos que pueden cambiar el modo de cámara de navegación. */
enum class NavCameraEvent {
    /** Nueva posición GPS: actualiza objetivo/zoom/bearing, pero no cambia el modo. */
    GPS_UPDATED,
    /** Gesto del usuario sobre el mapa: pasa a inspección libre. */
    MAP_GESTURE,
    /** El usuario pidió encuadrar la ruta completa. */
    OVERVIEW_REQUESTED,
    /** El usuario pidió volver a seguir su posición. */
    RECENTER_REQUESTED,
    /** La rodada terminó: se devuelve el control al usuario. */
    RIDE_ENDED
}

/**
 * Bounds en coordenadas geográficas, sin tipos de MapLibre para que sea verificable en JVM.
 * Se construyen solo con posiciones reales (riders con posición válida y waypoints de ruta).
 */
data class MapBounds(val north: Double, val south: Double, val east: Double, val west: Double) {
    val centerLat: Double get() = (north + south) / 2.0
    val centerLon: Double get() = (east + west) / 2.0

    fun isValid(): Boolean =
        north.isFinite() && south.isFinite() && east.isFinite() && west.isFinite() &&
            north >= south && east >= west
}

/**
 * Transiciones y curvas de cámara. Toda función es pura y determinista.
 */
object CameraModeManager {
    const val FOLLOW_TILT_DEGREES: Float = 45f
    const val FLAT_TILT_DEGREES: Float = 0f

    const val ZOOM_SLOW: Double = 16.5
    const val ZOOM_MID: Double = 15.0
    const val ZOOM_FAST: Double = 13.8

    /** Por debajo de esta velocidad el rumbo del GPS es ruido: se conserva el último válido. */
    const val MIN_BEARING_SPEED_KMH: Double = 5.0

    /** Umbrales con histeresis: evita oscilar cuando la velocidad ronda 30 o 70 km/h. */
    private const val MID_ENTER_KMH: Double = 30.0
    private const val MID_EXIT_KMH: Double = 27.0
    private const val FAST_ENTER_KMH: Double = 70.0
    private const val FAST_EXIT_KMH: Double = 65.0

    fun transition(current: NavCameraMode, event: NavCameraEvent): NavCameraMode = when (event) {
        NavCameraEvent.MAP_GESTURE, NavCameraEvent.RIDE_ENDED -> NavCameraMode.FREE_INSPECT
        NavCameraEvent.OVERVIEW_REQUESTED -> NavCameraMode.ROUTE_OVERVIEW
        NavCameraEvent.RECENTER_REQUESTED -> NavCameraMode.FOLLOW_HEADING_3D
        NavCameraEvent.GPS_UPDATED -> current
    }

    /**
     * Auto-zoom por velocidad. [currentZoom] permite aplicar histeresis con el estado previo;
     * si es null se parte de la banda lenta.
     */
    fun zoomForSpeedKmh(speedKmh: Double, currentZoom: Double? = null): Double {
        if (!speedKmh.isFinite() || speedKmh <= 0.0) return ZOOM_SLOW
        var band = bandForZoom(currentZoom)
        when {
            speedKmh >= FAST_ENTER_KMH -> band = 2
            band == 2 && speedKmh > FAST_EXIT_KMH -> Unit
            speedKmh >= MID_ENTER_KMH -> band = 1
            band >= 1 && speedKmh > MID_EXIT_KMH -> Unit
            else -> band = 0
        }
        return when (band) {
            2 -> ZOOM_FAST
            1 -> ZOOM_MID
            else -> ZOOM_SLOW
        }
    }

    /**
     * En parado o a velocidad muy baja el GPS entrega bearings aleatorios. Congelar el
     * último rumbo válido evita que el mapa gire solo en un semáforo.
     */
    fun bearingForSpeedKmh(
        speedKmh: Double,
        rawBearing: Float,
        previousBearing: Float
    ): Float = if (speedKmh.isFinite() && speedKmh >= MIN_BEARING_SPEED_KMH) {
        rawBearing
    } else {
        previousBearing
    }

    /**
     * Zoom aproximado para encuadrar [bounds]: cada duplicado del vano geográfico baja
     * un nivel de zoom. Se usa solo como semilla; MapLibre ajusta con `newLatLngBounds`
     * y el padding real de la pantalla.
     */
    fun zoomForBounds(bounds: MapBounds): Double {
        if (!bounds.isValid()) return ZOOM_MID
        val latSpan = bounds.north - bounds.south
        val lonSpan = (bounds.east - bounds.west) *
            cos(Math.toRadians(bounds.centerLat)).coerceAtLeast(0.01)
        val span = max(latSpan, lonSpan).coerceAtLeast(0.0005)
        return (15.0 - log2(span / 0.02)).coerceIn(9.0, 16.0)
    }

    private fun bandForZoom(zoom: Double?): Int = when {
        zoom == null -> 0
        // Orden ascendente de zoom: 13.8 (rápido) < 15.0 (medio) < 16.5 (lento).
        zoom <= ZOOM_FAST + 0.01 -> 2
        zoom <= ZOOM_MID + 0.01 -> 1
        else -> 0
    }

    fun tiltFor(mode: NavCameraMode): Float = when (mode) {
        NavCameraMode.FOLLOW_HEADING_3D -> FOLLOW_TILT_DEGREES
        NavCameraMode.ROUTE_OVERVIEW, NavCameraMode.FREE_INSPECT -> FLAT_TILT_DEGREES
    }

    /**
     * En inspección libre la cámara no debe ser movida por ticks de GPS ni por el bucle
     * de cadencia; en los otros dos modos sí.
     */
    fun shouldApplyCamera(mode: NavCameraMode): Boolean = mode != NavCameraMode.FREE_INSPECT
}
