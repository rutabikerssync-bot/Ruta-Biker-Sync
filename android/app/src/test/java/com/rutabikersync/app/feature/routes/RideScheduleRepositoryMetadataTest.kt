package com.rutabikersync.app.feature.routes

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideScheduleRepositoryMetadataTest {
    @Test
    fun `scheduleNewRide conserva titulo descripcion e Instant de salida`() {
        val startsAt = Instant.parse("2026-11-21T12:30:00Z")
        val route = RouteAlternative(
            id = "route-test",
            name = "Ruta de prueba",
            subtitle = "Vía existente",
            distanceKm = 22.7,
            estimatedMinutes = 52,
            roadCondition = "Asfalto",
            difficulty = "Media",
            waypoints = emptyList()
        )
        try {
            RideScheduleRepository.scheduleNewRide(
                title = "Rodada El Jarillo",
                originName = "Panamericana",
                originLat = 10.35,
                originLon = -67.04,
                destinationName = "El Jarillo",
                destinationLat = 10.34,
                destinationLon = -67.16,
                selectedRoute = route,
                availableRoutes = listOf(route),
                returnStrategy = ReturnStrategy.SAME_ROUTE,
                dispersionPointName = "El Jarillo",
                dispersionLat = 10.34,
                dispersionLon = -67.16,
                meetingTime = "08:30",
                departureTime = "08:30",
                dateSchedule = "2026-11-21",
                distanceKm = route.distanceKm,
                estimatedMinutes = route.estimatedMinutes,
                description = "Salida en convoy",
                startsAt = startsAt
            )

            val saved = RideScheduleRepository.currentRide.value!!
            assertEquals("Rodada El Jarillo", saved.title)
            assertEquals("Salida en convoy", saved.description)
            assertEquals(startsAt, saved.startsAt)
            assertEquals(route.id, saved.selectedRoute.id)
        } finally {
            RideScheduleRepository.clearCurrentRide()
        }
        assertNull(RideScheduleRepository.currentRide.value)
    }
}
