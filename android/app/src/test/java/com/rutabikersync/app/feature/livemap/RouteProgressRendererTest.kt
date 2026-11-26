package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.feature.routes.DeviationStatus
import com.rutabikersync.app.feature.routes.LatLngPoint
import com.rutabikersync.app.feature.routes.RouteDeviationDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressRendererTest {

    @Test
    fun `divide la ruta en tramo recorrido y tramo pendiente segun la posicion del piloto`() {
        val route = listOf(
            LatLngPoint(10.000, -67.000),
            LatLngPoint(10.010, -67.000),
            LatLngPoint(10.020, -67.000)
        )

        val split = RouteProgressRenderer.splitRouteProgress(
            waypoints = route,
            riderLat = 10.015,
            riderLon = -67.000
        )

        assertTrue(split.completedPoints.size >= 2)
        assertTrue(split.remainingPoints.size >= 2)
        assertEquals(0.75f, split.progressFraction, 0.05f)
        assertEquals(10.015, split.completedPoints.last().latitude, 1e-4)
        assertEquals(10.015, split.remainingPoints.first().latitude, 1e-4)
    }

    @Test
    fun `al llegar cerca del destino marca ARRIVED y no vuelve a recalcular por desvio`() {
        val route = listOf(
            LatLngPoint(10.3400, -67.0400),
            LatLngPoint(10.3450, -67.0400),
            LatLngPoint(10.3500, -67.0400)
        )
        RouteDeviationDetector.setActiveRoutePolyline(route)

        // Llega a ~30 m del destino (dentro de 120 m)
        RouteDeviationDetector.updateRiderLocation(
            location = LatLngPoint(10.3502, -67.0400),
            speedKmh = 5f,
            nowEpochMs = 1_000L
        )
        assertEquals(DeviationStatus.ARRIVED, RouteDeviationDetector.deviationState.value.status)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)

        // Aunque se aleje 300 m después de llegar (estacionamiento/desvío), no recalcula ni dispara alerta
        RouteDeviationDetector.updateRiderLocation(
            location = LatLngPoint(10.3530, -67.0400),
            speedKmh = 10f,
            nowEpochMs = 20_000L
        )
        assertEquals(DeviationStatus.ARRIVED, RouteDeviationDetector.deviationState.value.status)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)
    }
}
