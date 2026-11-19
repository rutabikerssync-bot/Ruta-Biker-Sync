package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.feature.routes.LatLngPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyRouteProjectionTest {

    private val route = listOf(
        LatLngPoint(10.0, -67.0),
        LatLngPoint(10.0, -67.0001),
        LatLngPoint(10.0, -67.0002)
    )

    private fun marker(
        shortId: UShort,
        lat: Double = 10.0,
        lon: Double = -67.00005,
        speed: Int = 0,
        bearing: Float = 0f,
        isSelf: Boolean = false
    ) = RiderMarkerUiModel(
        shortId = shortId,
        fullName = "Piloto $shortId",
        nickname = null,
        relation = RiderRelation.SAME_GROUP,
        rideRole = RiderRideRole.NONE,
        routeStatus = RiderRouteStatus.ON_ROUTE,
        markerColorHex = 0xFFFFFFFFL,
        prevLatitude = lat,
        prevLongitude = lon,
        targetLatitude = lat,
        targetLongitude = lon,
        displayLatitude = lat,
        displayLongitude = lon,
        speedKmh = speed,
        bearingDegrees = bearing,
        batteryPercent = 90,
        distanceToLeaderMeters = 0,
        lastHops = 0,
        isSelf = isSelf
    )

    @Test
    fun `sin ruta activa no se proyecta nada`() {
        assertTrue(ConvoyRouteProjection.layout(listOf(marker(1u.toUShort())), emptyList()).isEmpty())
        assertTrue(ConvoyRouteProjection.layout(listOf(marker(1u.toUShort())), listOf(route[0])).isEmpty())
    }

    @Test
    fun `peer cercano se proyecta sobre la polilinea real`() {
        val placement = ConvoyRouteProjection.layout(listOf(marker(1u.toUShort())), route).getValue(1u.toUShort())
        assertTrue(placement.isSnapped)
        assertEquals(10.0, placement.latitude, 0.000001)
        assertTrue(placement.longitude in -67.0001..-67.0)
        assertTrue(placement.cumulativeMeters in 0..11)
    }

    @Test
    fun `peer lejano conserva su posicion exacta`() {
        val placement = ConvoyRouteProjection.layout(
            listOf(marker(1u.toUShort(), lat = 10.01, lon = -67.00005)),
            route
        ).getValue(1u.toUShort())
        assertFalse(placement.isSnapped)
        assertEquals(10.01, placement.latitude, 0.0000001)
        assertEquals(-67.00005, placement.longitude, 0.0000001)
        assertTrue(placement.distanceToRouteMeters > ConvoyRouteProjection.SNAP_THRESHOLD_METERS)
    }

    @Test
    fun `peers solapados se separan al menos diez metros sobre la ruta`() {
        val placements = ConvoyRouteProjection.layout(
            listOf(marker(1u.toUShort()), marker(2u.toUShort())),
            route
        )
        val first = placements.getValue(1u.toUShort())
        val second = placements.getValue(2u.toUShort())
        assertTrue(second.cumulativeMeters - first.cumulativeMeters in ConvoyRouteProjection.MIN_SPACING_METERS..20)
        assertTrue(kotlin.math.abs(second.cumulativeMeters - second.visualOffsetAppliedMeters - first.cumulativeMeters) <= 1)
    }

    @Test
    fun `el peer local se entrega primero sin alterar su posicion`() {
        val placements = ConvoyRouteProjection.layout(
            listOf(marker(1u.toUShort()), marker(2u.toUShort(), isSelf = true)),
            route,
            localShortId = 2u.toUShort()
        )
        assertEquals(2u.toUShort(), placements.keys.first())
        assertTrue(placements.getValue(2u.toUShort()).isSnapped)
    }

    @Test
    fun `sin velocidad se usa el rumbo del tramo de ruta`() {
        val placement = ConvoyRouteProjection.layout(listOf(marker(1u.toUShort())), route).getValue(1u.toUShort())
        assertTrue(placement.headingDegrees in 260f..280f)
    }

    @Test
    fun `con velocidad se conserva el rumbo reportado por el peer`() {
        val placement = ConvoyRouteProjection.layout(
            listOf(marker(1u.toUShort(), speed = 42, bearing = 235f)),
            route
        ).getValue(1u.toUShort())
        assertEquals(235f, placement.headingDegrees, 0.001f)
    }
}
