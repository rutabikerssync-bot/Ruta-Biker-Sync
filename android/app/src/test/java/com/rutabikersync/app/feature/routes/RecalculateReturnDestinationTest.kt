package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** M5 — en retorno el recálculo apunta al origen y la polilínea se orienta al leer. */
class RecalculateReturnDestinationTest {

    private val origin = WaypointLocation("Origen", 10.34000, -67.03000)
    private val destination = WaypointLocation("Destino", 10.36000, -67.05000)
    private val recalculatedWaypoints = listOf(
        LatLngPoint(10.34000, -67.03000),
        LatLngPoint(10.34500, -67.03500),
        LatLngPoint(10.35000, -67.04000)
    )

    private fun publishRide() {
        RideScheduleRepository.publishScheduledRide(
            ScheduledRide(
                id = "ride-return-recalc",
                title = "Rodada de prueba",
                origin = origin,
                destination = destination,
                selectedRoute = RouteAlternative(
                    id = "scheduled",
                    name = "Programada",
                    subtitle = "",
                    distanceKm = 1.0,
                    estimatedMinutes = 3,
                    roadCondition = "Asfalto",
                    difficulty = "Media",
                    waypoints = listOf(origin.asLatLng(), destination.asLatLng())
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
        )
    }

    private fun WaypointLocation.asLatLng() = LatLngPoint(latitude, longitude)

    @After
    fun tearDown() {
        RouteDeviationDetector.reset()
        RideScheduleRepository.clearCurrentRide()
    }

    @Test
    fun `en retorno el objetivo del recalculo es el origen no el destino de ida`() = runTest {
        publishRide()
        var captured: LatLngPoint? = null
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { _, target, _ ->
                captured = target
                RouteAlternative(
                    id = "recalculated",
                    name = "Recalculada",
                    subtitle = "",
                    distanceKm = 1.0,
                    estimatedMinutes = 3,
                    roadCondition = "Asfalto",
                    difficulty = "Media",
                    waypoints = recalculatedWaypoints
                )
            },
            isReturnPhase = { true }
        )

        val outcome = useCase(
            currentLocation = origin.asLatLng(),
            destination = destination
        )

        assertEquals(RecalculateRouteOutcome.SUCCESS, outcome)
        assertEquals(LatLngPoint(origin.latitude, origin.longitude), captured)
        val selected = RideScheduleRepository.currentRide.value?.selectedRoute
        assertEquals(
            recalculatedWaypoints.reversed(),
            RouteOrientation.effectiveWaypoints(selected!!.waypoints, true)
        )
    }

    @Test
    fun `en ida el objetivo sigue siendo el destino`() = runTest {
        publishRide()
        var captured: LatLngPoint? = null
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { _, target, _ ->
                captured = target
                null
            }
        )

        val outcome = useCase(origin.asLatLng(), destination)

        assertEquals(RecalculateRouteOutcome.NO_ROUTE, outcome)
        assertEquals(LatLngPoint(destination.latitude, destination.longitude), captured)
    }
}
