package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresiones JVM de la máquina de cámara de navegación (Fase 1).
 * No requiere Android ni MapLibre.
 */
class CameraModeManagerTest {

    @Test
    fun `gesto del usuario congela la camara desde cualquier modo`() {
        NavCameraMode.values().forEach { mode ->
            assertEquals(
                NavCameraMode.FREE_INSPECT,
                CameraModeManager.transition(mode, NavCameraEvent.MAP_GESTURE)
            )
        }
    }

    @Test
    fun `un tick de GPS no recupera el seguimiento despues de un gesto`() {
        val afterGesture = CameraModeManager.transition(
            NavCameraMode.FOLLOW_HEADING_3D,
            NavCameraEvent.MAP_GESTURE
        )
        assertEquals(
            NavCameraMode.FREE_INSPECT,
            CameraModeManager.transition(afterGesture, NavCameraEvent.GPS_UPDATED)
        )
    }

    @Test
    fun `recenter vuelve a seguir y overview encuadra la ruta`() {
        val inspecting = NavCameraMode.FREE_INSPECT
        assertEquals(
            NavCameraMode.FOLLOW_HEADING_3D,
            CameraModeManager.transition(inspecting, NavCameraEvent.RECENTER_REQUESTED)
        )
        assertEquals(
            NavCameraMode.ROUTE_OVERVIEW,
            CameraModeManager.transition(inspecting, NavCameraEvent.OVERVIEW_REQUESTED)
        )
        // El GPS tampoco saca al usuario del overview.
        val overview = CameraModeManager.transition(inspecting, NavCameraEvent.OVERVIEW_REQUESTED)
        assertEquals(
            NavCameraMode.ROUTE_OVERVIEW,
            CameraModeManager.transition(overview, NavCameraEvent.GPS_UPDATED)
        )
    }

    @Test
    fun `fin de rodada devuelve el control al usuario`() {
        assertEquals(
            NavCameraMode.FREE_INSPECT,
            CameraModeManager.transition(NavCameraMode.ROUTE_OVERVIEW, NavCameraEvent.RIDE_ENDED)
        )
    }

    @Test
    fun `auto zoom por velocidad usa las tres bandas`() {
        assertEquals(CameraModeManager.ZOOM_SLOW, CameraModeManager.zoomForSpeedKmh(12.0), 1e-6)
        assertEquals(CameraModeManager.ZOOM_SLOW, CameraModeManager.zoomForSpeedKmh(0.0), 1e-6)
        assertEquals(CameraModeManager.ZOOM_MID, CameraModeManager.zoomForSpeedKmh(50.0), 1e-6)
        assertEquals(CameraModeManager.ZOOM_FAST, CameraModeManager.zoomForSpeedKmh(90.0), 1e-6)
    }

    @Test
    fun `histeresis evita oscilar en los umbrales de 30 y 70`() {
        // En 29 km/h, quien venia de 15.0 se queda en 15.0; quien venia de 16.5 no salta.
        assertEquals(CameraModeManager.ZOOM_MID, CameraModeManager.zoomForSpeedKmh(29.0, CameraModeManager.ZOOM_MID), 1e-6)
        assertEquals(CameraModeManager.ZOOM_SLOW, CameraModeManager.zoomForSpeedKmh(29.0, CameraModeManager.ZOOM_SLOW), 1e-6)
        assertEquals(CameraModeManager.ZOOM_SLOW, CameraModeManager.zoomForSpeedKmh(26.0, CameraModeManager.ZOOM_MID), 1e-6)

        // En 68 km/h, quien venia de 13.8 se mantiene; al bajar de 65 vuelve a 15.0.
        assertEquals(CameraModeManager.ZOOM_FAST, CameraModeManager.zoomForSpeedKmh(68.0, CameraModeManager.ZOOM_FAST), 1e-6)
        assertEquals(CameraModeManager.ZOOM_MID, CameraModeManager.zoomForSpeedKmh(64.0, CameraModeManager.ZOOM_FAST), 1e-6)
    }

    @Test
    fun `solo seguir en incline y la camara se congela al inspeccionar`() {
        assertEquals(
            CameraModeManager.FOLLOW_TILT_DEGREES,
            CameraModeManager.tiltFor(NavCameraMode.FOLLOW_HEADING_3D)
        )
        assertEquals(
            CameraModeManager.FLAT_TILT_DEGREES,
            CameraModeManager.tiltFor(NavCameraMode.ROUTE_OVERVIEW)
        )
        assertEquals(
            CameraModeManager.FLAT_TILT_DEGREES,
            CameraModeManager.tiltFor(NavCameraMode.FREE_INSPECT)
        )
        assertTrue(CameraModeManager.shouldApplyCamera(NavCameraMode.FOLLOW_HEADING_3D))
        assertTrue(CameraModeManager.shouldApplyCamera(NavCameraMode.ROUTE_OVERVIEW))
        assertFalse(CameraModeManager.shouldApplyCamera(NavCameraMode.FREE_INSPECT))
    }

    @Test
    fun `bearing se congela por debajo de 5 km h`() {
        // En un semáforo el GPS puede decir 350° y después 40°: no se debe girar el mapa.
        assertEquals(
            90f,
            CameraModeManager.bearingForSpeedKmh(0.0, 350f, 90f),
            1e-6f
        )
        assertEquals(
            90f,
            CameraModeManager.bearingForSpeedKmh(4.9, 40f, 90f),
            1e-6f
        )
        assertEquals(
            40f,
            CameraModeManager.bearingForSpeedKmh(5.0, 40f, 90f),
            1e-6f
        )
    }

    @Test
    fun `bounds de overview usan solo coordenadas reales`() {
        val bounds = MapBounds(north = 19.5, south = 19.3, east = -99.0, west = -99.4)
        assertTrue(bounds.isValid())
        assertEquals(19.4, bounds.centerLat, 1e-6)
        assertEquals(-99.2, bounds.centerLon, 1e-6)
        assertFalse(MapBounds(north = 0.0, south = 1.0, east = 0.0, west = 0.0).isValid())
        assertFalse(MapBounds(north = 1.0, south = 0.0, east = Double.NaN, west = 0.0).isValid())
    }

    @Test
    fun `zoom de overview baja al crecer el vano`() {
        val small = MapBounds(north = 19.45, south = 19.40, east = -99.10, west = -99.15)
        val large = MapBounds(north = 20.50, south = 18.90, east = -98.00, west = -100.20)
        assertTrue(CameraModeManager.zoomForBounds(large) < CameraModeManager.zoomForBounds(small))
        assertEquals(
            CameraModeManager.ZOOM_MID,
            CameraModeManager.zoomForBounds(
                MapBounds(Double.NaN, 0.0, 1.0, 0.0)
            ),
            1e-6
        )
    }
}
