package com.rutabikersync.app.feature.routes

import org.junit.Assert.assertEquals
import org.junit.Test

/** M5 — resolución pura de orientación; sin Android, Compose ni malla BLE. */
class RouteOrientationTest {

    private val a = LatLngPoint(10.34000, -67.03000)
    private val b = LatLngPoint(10.35000, -67.04000)
    private val c = LatLngPoint(10.36000, -67.05000)

    private fun ride() = ScheduledRide(
        id = "ride-orientation-test",
        title = "Rodada de prueba",
        origin = WaypointLocation("Origen", 10.34000, -67.03000),
        destination = WaypointLocation("Destino", 10.36000, -67.05000),
        selectedRoute = RouteAlternative(
            id = "scheduled",
            name = "Programada",
            subtitle = "",
            distanceKm = 1.0,
            estimatedMinutes = 3,
            roadCondition = "Asfalto",
            difficulty = "Media",
            waypoints = listOf(a, b, c)
        ),
        availableRoutes = emptyList(),
        returnPlan = ReturnPlan(),
        safeArrivalProtocol = SafeArrivalProtocol(enabled = true),
        dateSchedule = "2026-11-22",
        meetingTime = "07:30 AM",
        departureTime = "07:45 AM",
        distanceKm = 1.0,
        estimatedMinutes = 3,
        confirmations = emptyList()
    )

    @Test
    fun `ida conserva el orden original`() {
        val points = listOf(a, b, c)
        assertEquals(points, RouteOrientation.effectiveWaypoints(points, false))
    }

    @Test
    fun `retorno invierte sin mutar la lista original`() {
        val points = listOf(a, b, c)
        assertEquals(listOf(c, b, a), RouteOrientation.effectiveWaypoints(points, true))
        assertEquals(listOf(a, b, c), points)
    }

    @Test
    fun `listas vacias y de un punto son estables`() {
        assertEquals(emptyList<LatLngPoint>(), RouteOrientation.effectiveWaypoints(emptyList(), true))
        assertEquals(listOf(a), RouteOrientation.effectiveWaypoints(listOf(a), true))
    }

    @Test
    fun `doble inversion recupera el orden`() {
        val once = RouteOrientation.effectiveWaypoints(listOf(a, b, c), true)
        assertEquals(listOf(a, b, c), RouteOrientation.effectiveWaypoints(once, true))
    }

    @Test
    fun `destino efectivo es destino en ida y origen en retorno`() {
        val current = ride()
        assertEquals(current.destination, RouteOrientation.effectiveDestination(current, false))
        assertEquals(current.origin, RouteOrientation.effectiveDestination(current, true))
    }
}
