package com.rutabikersync.app.feature.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadRoutingFallbackTest {

    private val origin = LatLngPoint(10.3538, -67.0425)
    private val destination = LatLngPoint(10.3452, -67.1648)

    @Test
    fun `fallback aproximado genera tres alternativas etiquetadas como no navegables`() {
        val routes = RoadRoutingEngine.buildApproximateAlternatives(
            origin = origin,
            destination = destination,
            destinationName = "Punto campo",
            limit = 3
        )

        assertEquals(3, routes.size)
        assertEquals(listOf("route-1", "route-2", "route-3"), routes.map { it.id })

        routes.forEach { route ->
            assertTrue("Debe tener al menos 6 waypoints", route.waypoints.size >= 6)
            assertEquals(origin, route.waypoints.first())
            assertEquals(destination, route.waypoints.last())
            assertTrue("El nombre debe indicar que es aproximada", route.name.contains("aproximada"))
            assertTrue("El subtítulo debe indicar que no es navegable", route.subtitle.contains("no navegable"))
        }

        assertTrue(routes.first().isRecommended)
        assertFalse(routes[1].isRecommended)
        assertFalse(routes[2].isRecommended)
    }

    @Test
    fun `fallback aproximado respeta el limite solicitado`() {
        val routes = RoadRoutingEngine.buildApproximateAlternatives(
            origin = origin,
            destination = destination,
            destinationName = "Punto campo",
            limit = 2
        )

        assertEquals(2, routes.size)
        assertEquals(listOf("route-1", "route-2"), routes.map { it.id })
    }
}
