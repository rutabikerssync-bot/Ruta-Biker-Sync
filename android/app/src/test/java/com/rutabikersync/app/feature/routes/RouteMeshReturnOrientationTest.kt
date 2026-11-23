package com.rutabikersync.app.feature.routes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 — la malla transporta en orden de emisión; la fase se aplica en el consumidor. */
class RouteMeshReturnOrientationTest {

    private val meshWaypoints = listOf(
        LatLngPoint(10.34000, -67.03000),
        LatLngPoint(10.35000, -67.04000),
        LatLngPoint(10.36000, -67.05000)
    )

    private fun route(id: String, points: List<LatLngPoint>) = RouteAlternative(
        id = id,
        name = id,
        subtitle = "",
        distanceKm = 1.0,
        estimatedMinutes = 3,
        roadCondition = "Asfalto",
        difficulty = "Media",
        waypoints = points
    )

    private fun publishScheduledRoute() {
        RideScheduleRepository.publishScheduledRide(
            ScheduledRide(
                id = "ride-mesh-orientation",
                title = "Rodada de prueba",
                origin = WaypointLocation("Origen", 10.34000, -67.03000),
                destination = WaypointLocation("Destino", 10.36000, -67.05000),
                selectedRoute = route("scheduled", listOf(LatLngPoint(10.31111, -67.01111))),
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
        )
    }

    @Before
    fun setUp() {
        RoutePolylineMeshRelay.clearOnRideFinished()
        RideScheduleRepository.clearCurrentRide()
    }

    @After
    fun tearDown() {
        RoutePolylineMeshRelay.clearOnRideFinished()
        RideScheduleRepository.clearCurrentRide()
    }

    @Test
    fun `relay conserva orden y hash y el resolver invierte la ruta de malla en retorno`() {
        val original = route("mesh-route", meshWaypoints)
        val chunks = RoutePolylineMeshRelay.fragmentRouteForMesh(original)
        var event: RouteMeshReceivedEvent? = null
        chunks.forEach { chunk ->
            RoutePolylineMeshRelay.processIncomingChunk(chunk)?.let { event = it }
        }

        assertNotNull(event)
        assertTrue(event!!.isHashVerified)
        assertEquals(meshWaypoints, event!!.route.waypoints)

        publishScheduledRoute()
        val resolverResult = RouteOrientation.resolveActiveRoute(
            scheduled = RideScheduleRepository.currentRide.value?.selectedRoute,
            mesh = event,
            meshIsActive = true,
            isReturnPhase = true
        )
        assertEquals(meshWaypoints.reversed(), resolverResult)
    }

    @Test
    fun `sin malla activa se usa la ruta programada y activates la recibida solo si el hash es valido`() {
        val original = route("mesh-route-valid", meshWaypoints)
        val chunks = RoutePolylineMeshRelay.fragmentRouteForMesh(original)
        var event: RouteMeshReceivedEvent? = null
        chunks.forEach { chunk ->
            RoutePolylineMeshRelay.processIncomingChunk(chunk)?.let { event = it }
        }
        assertNotNull(event)

        publishScheduledRoute()
        val scheduled = RideScheduleRepository.currentRide.value?.selectedRoute
        assertEquals(
            scheduled?.waypoints?.reversed(),
            RouteOrientation.resolveActiveRoute(scheduled, event, false, true)
        )

        assertTrue(RideScheduleRepository.activateMeshRoute(event!!))
        assertEquals(
            "mesh-route-valid",
            RideScheduleRepository.currentRide.value?.selectedRoute?.id
        )

        val tampered = event!!.copy(isHashVerified = false)
        assertFalse(RideScheduleRepository.activateMeshRoute(tampered))
        assertEquals(
            "mesh-route-valid",
            RideScheduleRepository.currentRide.value?.selectedRoute?.id
        )
    }
}
