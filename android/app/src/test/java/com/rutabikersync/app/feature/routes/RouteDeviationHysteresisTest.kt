package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteDeviationHysteresisTest {
    private val route = listOf(
        LatLngPoint(0.0, 0.0),
        LatLngPoint(0.01, 0.0)
    )
    private val smallDeviation = LatLngPoint(0.005, 0.0005) // ~55 m al este del corredor norte/sur.
    private val sustainedDeviation = LatLngPoint(0.005, 0.003) // ~334 m al este del corredor norte/sur (>150 m).
    private val actualStartMs = 1_000L

    @Before
    fun setUp() {
        RideScheduleRepository.clearCurrentRide()
        RideScheduleRepository.scheduleNewRide(
            title = "Ruta activa",
            originName = "Origen",
            originLat = 0.0,
            originLon = 0.0,
            destinationName = "Destino real",
            destinationLat = 0.009,
            destinationLon = 0.0,
            selectedRoute = RouteAlternative(
                id = "original-route",
                name = "Ruta original",
                subtitle = "Corredor base",
                distanceKm = 1.0,
                estimatedMinutes = 10,
                roadCondition = "Asfalto",
                difficulty = "Media",
                waypoints = route
            ),
            availableRoutes = emptyList(),
            returnStrategy = ReturnStrategy.SAME_ROUTE,
            dispersionPointName = "Destino real",
            dispersionLat = 0.009,
            dispersionLon = 0.0,
            meetingTime = "08:00",
            departureTime = "08:00",
            dateSchedule = "2026-11-21",
            distanceKm = 1.0,
            estimatedMinutes = 10
        )
        RouteDeviationDetector.reset()
        RouteDeviationDetector.setActiveRoutePolyline(route)
    }

    @Test
    fun `desvio de 50m o jitter corto no dispara recalculo`() {
        // 50 m reales, incluso durante mucho más de 10 s: no es motivo de alerta.
        RouteDeviationDetector.updateRiderLocation(smallDeviation, 45f, actualStartMs)
        RouteDeviationDetector.updateRiderLocation(smallDeviation, 45f, actualStartMs + 15_000L)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)

        // Alternancia jitter/corredor: el tiempo no se acumula sin desvío sostenido.
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, actualStartMs)
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(0.0, 0.0), 0f, actualStartMs + 3_000L)
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, actualStartMs + 6_000L)
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, actualStartMs + 11_000L)
        assertTrue(RouteDeviationDetector.deviationState.value.isAlertActive.not())
    }

    @Test
    fun `desvio de 200m durante 15s ejecuta recalculo automatico con posicion real`() = runTest {
        val firstSeen = actualStartMs
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, firstSeen)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, firstSeen + 9_000L)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, firstSeen + 15_000L)
        assertTrue(RouteDeviationDetector.deviationState.value.isAlertActive)

        // Misma acción que LaunchedEffect(isAlertActive) de LiveMapScreen: se invoca
        // automáticamente el caso de uso, sin pulsar ningún botón.
        var fetchCalls = 0
        val destination = WaypointLocation("Destino real", 0.009, 0.0)
        val replacement = RouteAlternative(
            id = "auto-road-route",
            name = "Ruta vial automática",
            subtitle = "Corredor actual real",
            distanceKm = 0.7,
            estimatedMinutes = 3,
            roadCondition = "Asfalto",
            difficulty = "Media",
            waypoints = listOf(sustainedDeviation, LatLngPoint(destination.latitude, destination.longitude))
        )
        val useCase = RecalculateRouteUseCase(
            isOnline = { true },
            fetchRoadRoute = { _, _, _ ->
                fetchCalls++
                replacement
            }
        )
        val outcome = useCase(RouteDeviationDetector.deviationState.value.lastKnownLocation, destination)

        assertEquals(RecalculateRouteOutcome.SUCCESS, outcome)
        assertEquals(1, fetchCalls)
        assertEquals("auto-road-route", RideScheduleRepository.currentRide.value?.selectedRoute?.id)
        assertNotNull(RouteDeviationDetector.deviationState.value.lastKnownLocation)
        // Tras adoptar la nueva ruta desde la posición actual vuelve a estar en corredor.
        assertEquals(0, RouteDeviationDetector.deviationState.value.distanceToRouteMeters)
        assertFalse(RouteDeviationDetector.deviationState.value.isAlertActive)

        RideScheduleRepository.clearCurrentRide()
        RouteDeviationDetector.reset()
    }

    @Test
    fun `offline conserva ruta real y no inventa recalculo`() = runTest {
        RideScheduleRepository.scheduleNewRide(
            title = "Ruta offline",
            originName = "Origen",
            originLat = 0.0,
            originLon = 0.0,
            destinationName = "Destino",
            destinationLat = 0.009,
            destinationLon = 0.0,
            selectedRoute = RouteAlternative(
                id = "offline-original",
                name = "Ruta original offline",
                subtitle = "Cache real",
                distanceKm = 1.0,
                estimatedMinutes = 10,
                roadCondition = "Cache",
                difficulty = "Media",
                waypoints = route
            ),
            availableRoutes = emptyList(),
            returnStrategy = ReturnStrategy.SAME_ROUTE,
            dispersionPointName = "Destino",
            dispersionLat = 0.009,
            dispersionLon = 0.0,
            meetingTime = "08:00",
            departureTime = "08:00",
            dateSchedule = "2026-11-21",
            distanceKm = 1.0,
            estimatedMinutes = 10
        )
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, actualStartMs)
        RouteDeviationDetector.updateRiderLocation(sustainedDeviation, 45f, actualStartMs + 10_000L)
        val debugState = RouteDeviationDetector.deviationState.value
        assertTrue(
            "DEBUG state=$debugState computed=${
                RouteDeviationDetector.computeMinDistanceToPolylineMeters(sustainedDeviation, route)
            }",
            debugState.isAlertActive
        )

        var fetchCalls = 0
        val offlineUseCase = RecalculateRouteUseCase(
            isOnline = { false },
            fetchRoadRoute = { _, _, _ ->
                fetchCalls++
                null
            }
        )
        val outcome = offlineUseCase(
            RouteDeviationDetector.deviationState.value.lastKnownLocation,
            RideScheduleRepository.currentRide.value?.destination
        )
        assertEquals(RecalculateRouteOutcome.OFFLINE, outcome)
        assertEquals(0, fetchCalls)
        assertEquals("offline-original", RideScheduleRepository.currentRide.value?.selectedRoute?.id)

        RideScheduleRepository.clearCurrentRide()
        RouteDeviationDetector.reset()
    }
}
