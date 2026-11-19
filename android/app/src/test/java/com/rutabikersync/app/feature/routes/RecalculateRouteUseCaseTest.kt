package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloque C: verificaciones JVM de los tres desenlaces del botón "Recalcular ruta"
 * del banner de desvío. Sin Android, sin Mapbox y sin malla BLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecalculateRouteUseCaseTest {

    private val initialRoute = RouteAlternative(
        id = "route-original",
        name = "Ruta original",
        subtitle = "Ruta cacheada real",
        distanceKm = 2.3,
        estimatedMinutes = 5,
        roadCondition = "Asfalto",
        difficulty = "Media",
        waypoints = listOf(
            LatLngPoint(10.349246, -67.034904),
            LatLngPoint(10.351508, -67.036590)
        )
    )

    private val recalculatedRoute = RouteAlternative(
        id = "road-recalculated",
        name = "Ruta recalculada",
        subtitle = "Trazado vial verificado",
        distanceKm = 2.6,
        estimatedMinutes = 7,
        roadCondition = "Asfalto",
        difficulty = "Media",
        waypoints = listOf(
            LatLngPoint(10.349246, -67.034904),
            LatLngPoint(10.348500, -67.033900)
        )
    )

    private val currentLocation = LatLngPoint(10.349246, -67.034904)
    private val destination = WaypointLocation("Avenida Pedro Russo Ferrer", 10.360000, -67.050000)

    private fun publishRide() {
        RideScheduleRepository.publishScheduledRide(
            ScheduledRide(
                id = "ride-recalc-test",
                title = "Rodada de prueba",
                origin = WaypointLocation("8XX8+J24, Los Teques", 10.349246, -67.034904),
                destination = destination,
                selectedRoute = initialRoute,
                availableRoutes = listOf(initialRoute),
                returnPlan = ReturnPlan(),
                safeArrivalProtocol = SafeArrivalProtocol(enabled = true),
                dateSchedule = "2026-11-18",
                meetingTime = "07:30 AM",
                departureTime = "07:45 AM",
                distanceKm = initialRoute.distanceKm,
                estimatedMinutes = initialRoute.estimatedMinutes,
                confirmations = emptyList()
            )
        )
        RouteDeviationDetector.setActiveRoutePolyline(initialRoute.waypoints)
    }

    @After
    fun tearDown() {
        RouteDeviationDetector.reset()
        RideScheduleRepository.clearCurrentRide()
    }

    @Test
    fun `sin conexion devuelve OFFLINE y conserva la ruta cacheada sin llamar al motor vial`() = runTest {
        publishRide()
        var fetchCalls = 0
        val useCase = RecalculateRouteUseCase(
            isOnline = { false },
            fetchRoadRoute = { _, _, _ ->
                fetchCalls++
                recalculatedRoute
            }
        )

        val outcome = useCase(currentLocation, destination)

        assertEquals(RecalculateRouteOutcome.OFFLINE, outcome)
        assertEquals(0, fetchCalls)
        assertEquals("route-original", RideScheduleRepository.currentRide.value?.selectedRoute?.id)
    }

    @Test
    fun `con conexion y trazado real devuelve SUCCESS redibuja y reevalua el desvio`() = runTest {
        publishRide()
        var requestedDestinationName = ""
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { origin, target, destinationName ->
                assertEquals(currentLocation, origin)
                assertEquals(LatLngPoint(destination.latitude, destination.longitude), target)
                requestedDestinationName = destinationName
                recalculatedRoute
            }
        )

        val outcome = useCase(currentLocation, destination)

        assertEquals(RecalculateRouteOutcome.SUCCESS, outcome)
        assertEquals(destination.name, requestedDestinationName)
        val activeRide = RideScheduleRepository.currentRide.value!!
        assertEquals("road-recalculated", activeRide.selectedRoute.id)
        assertEquals(2.6, activeRide.distanceKm, 0.0)
        assertEquals(7, activeRide.estimatedMinutes)
        assertEquals(recalculatedRoute.waypoints, activeRide.selectedRoute.waypoints)

        // El detector ya evalúa la nueva polilínea: el punto de origen quedó dentro del corredor.
        val state = RouteDeviationDetector.deviationState.value
        assertFalse(state.isAlertActive)
        assertEquals(0, state.distanceToRouteMeters)
        assertTrue(state.distanceToRouteMeters <= RouteDeviationDetector.DEVIATION_THRESHOLD_METERS)
    }

    @Test
    fun `con conexion pero sin respuesta del motor devuelve NO_ROUTE y mantiene la ruta`() = runTest {
        publishRide()
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { _, _, _ -> null }
        )

        val outcome = useCase(currentLocation, destination)

        assertEquals(RecalculateRouteOutcome.NO_ROUTE, outcome)
        assertEquals("route-original", RideScheduleRepository.currentRide.value?.selectedRoute?.id)
        assertEquals(initialRoute.waypoints, RideScheduleRepository.currentRide.value?.selectedRoute?.waypoints)
    }

    @Test
    fun `sin posicion actual conocida devuelve NO_ROUTE sin fabricar coordenadas`() = runTest {
        publishRide()
        var fetchCalls = 0
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { _, _, _ ->
                fetchCalls++
                recalculatedRoute
            }
        )

        val outcome = useCase(null, destination)

        assertEquals(RecalculateRouteOutcome.NO_ROUTE, outcome)
        assertEquals(0, fetchCalls)
        assertEquals("route-original", RideScheduleRepository.currentRide.value?.selectedRoute?.id)
    }
}
