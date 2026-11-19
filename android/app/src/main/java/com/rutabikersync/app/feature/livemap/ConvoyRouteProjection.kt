package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.feature.routes.LatLngPoint
import com.rutabikersync.app.feature.routes.MotorcycleRoutingEngine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Posición visual de un rider dentro del convoy.
 *
 * [latitude]/[longitude] son el punto de la polilínea real más cercano al peer
 * cuando éste está dentro de [ConvoyRouteProjection.SNAP_THRESHOLD_METERS].
 * Si está fuera, son exactamente las coordenadas recibidas del peer (no se inventa
 * una posición). [visualOffsetAppliedMeters] documenta el único ajuste artificial:
 * la separación mínima para evitar que dos marcadores se solapen.
 */
data class ConvoyPlacement(
    val latitude: Double,
    val longitude: Double,
    val headingDegrees: Float,
    val isSnapped: Boolean,
    val distanceToRouteMeters: Int,
    val cumulativeMeters: Int,
    val visualOffsetAppliedMeters: Int = 0
)

/**
 * Proyección de peers sobre la ruta activa para que el convoy se vea alineado
 * sobre la vía. Toda la geometría de entrada es real (polilínea cacheada o
 * calculada); si no hay ruta, la función devuelve un mapa vacío y la UI usa la
 * posición exacta de cada peer.
 */
object ConvoyRouteProjection {

    /** Distancia máxima para considerar que un peer está sobre la vía. */
    const val SNAP_THRESHOLD_METERS = 50

    /** Separación recomendada entre marcadores consecutivos sobre la polilínea. */
    const val RECOMMENDED_SPACING_METERS = 18

    /** Separación mínima visual entre dos marcadores sobre la polilínea. */
    const val MIN_SPACING_METERS = 10

    private const val METERS_PER_DEG_LAT = 110540.0
    private const val METERS_PER_DEG_LON = 111320.0

    /**
     * Calcula la colocación de cada marcador.
     *
     * @param markers marcadores ya interpolados por [LiveMapViewModel]
     * @param polyline waypoints de la ruta activa (puede estar vacía)
     * @param localShortId shortId del peer local, que se devuelve primero en el mapa
     */
    fun layout(
        markers: List<RiderMarkerUiModel>,
        polyline: List<LatLngPoint>,
        localShortId: UShort? = null
    ): Map<UShort, ConvoyPlacement> {
        if (polyline.size < 2 || markers.isEmpty()) return emptyMap()

        val segments = buildSegments(polyline)
        val totalLength = segments.sumOf { it.lengthMeters }
        val placements = LinkedHashMap<UShort, ConvoyPlacement>()
        val onRoute = ArrayList<Pair<RiderMarkerUiModel, Projection>>()

        markers.forEach { marker ->
            val point = LatLngPoint(marker.displayLatitude, marker.displayLongitude)
            val projection = project(point, segments)
            if (projection.distanceToRouteMeters > SNAP_THRESHOLD_METERS) {
                // Fuera de la vía: se conserva la posición real del peer.
                placements[marker.shortId] = ConvoyPlacement(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    headingDegrees = marker.bearingDegrees,
                    isSnapped = false,
                    distanceToRouteMeters = projection.distanceToRouteMeters.toInt(),
                    cumulativeMeters = projection.cumulativeMeters.toInt()
                )
            } else {
                onRoute += marker to projection
            }
        }

        var previousCumulative: Double? = null
        onRoute
            .sortedBy { it.second.cumulativeMeters }
            .forEach { (marker, projection) ->
                val desired = previousCumulative?.let { it + RECOMMENDED_SPACING_METERS }
                    ?: projection.cumulativeMeters
                val minimum = previousCumulative?.let { it + MIN_SPACING_METERS }
                    ?: Double.NEGATIVE_INFINITY
                var target = maxOf(projection.cumulativeMeters, desired, minimum)
                    .coerceAtMost(totalLength)
                previousCumulative = target
                val pointOnRoute = pointAtDistance(segments, target)
                placements[marker.shortId] = ConvoyPlacement(
                    latitude = pointOnRoute.point.latitude,
                    longitude = pointOnRoute.point.longitude,
                    headingDegrees = if (marker.speedKmh > 0) marker.bearingDegrees else pointOnRoute.bearingDegrees,
                    isSnapped = true,
                    distanceToRouteMeters = projection.distanceToRouteMeters.toInt(),
                    cumulativeMeters = target.toInt(),
                    visualOffsetAppliedMeters = (target - projection.cumulativeMeters).toInt().coerceAtLeast(0)
                )
            }

        // El peer local se entrega primero para que la UI lo dibuje en primer lugar
        // (y con zIndex superior). Su posición sobre la ruta NO se altera.
        if (localShortId == null) return placements
        val local = placements.remove(localShortId) ?: return placements
        val ordered = LinkedHashMap<UShort, ConvoyPlacement>()
        ordered[localShortId] = local
        ordered.putAll(placements)
        return ordered
    }

    private data class Segment(
        val start: LatLngPoint,
        val end: LatLngPoint,
        val startCumulativeMeters: Double,
        val lengthMeters: Double,
        val cosLat: Double,
        val bearingDegrees: Float
    )

    private data class Projection(
        val point: LatLngPoint,
        val cumulativeMeters: Double,
        val distanceToRouteMeters: Double,
        val bearingDegrees: Float
    )

    private data class PointOnRoute(val point: LatLngPoint, val bearingDegrees: Float)

    private fun buildSegments(polyline: List<LatLngPoint>): List<Segment> {
        val segments = ArrayList<Segment>(polyline.size - 1)
        var cumulative = 0.0
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val length = MotorcycleRoutingEngine.calculateHaversineKm(
                a.latitude, a.longitude, b.latitude, b.longitude
            ) * 1000.0
            val midLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
            val dx = (b.longitude - a.longitude) * METERS_PER_DEG_LON * cos(midLat)
            val dy = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
            val bearing = if (dx == 0.0 && dy == 0.0) {
                0f
            } else {
                ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
            }
            segments += Segment(a, b, cumulative, length, cos(midLat), bearing)
            cumulative += length
        }
        return segments
    }

    private fun project(point: LatLngPoint, segments: List<Segment>): Projection {
        var bestDistance = Double.MAX_VALUE
        var best = Projection(point, 0.0, bestDistance, 0f)

        segments.forEach { segment ->
            val px = point.longitude * METERS_PER_DEG_LON * segment.cosLat
            val py = point.latitude * METERS_PER_DEG_LAT
            val ax = segment.start.longitude * METERS_PER_DEG_LON * segment.cosLat
            val ay = segment.start.latitude * METERS_PER_DEG_LAT
            val bx = segment.end.longitude * METERS_PER_DEG_LON * segment.cosLat
            val by = segment.end.latitude * METERS_PER_DEG_LAT

            val abx = bx - ax
            val aby = by - ay
            val lengthSq = abx * abx + aby * aby
            val t = if (lengthSq == 0.0) {
                0.0
            } else {
                (((px - ax) * abx + (py - ay) * aby) / lengthSq).coerceIn(0.0, 1.0)
            }

            val projectionX = ax + t * abx
            val projectionY = ay + t * aby
            val distance = sqrt((px - projectionX) * (px - projectionX) + (py - projectionY) * (py - projectionY))
            if (distance < bestDistance) {
                bestDistance = distance
                best = Projection(
                    point = LatLngPoint(
                        latitude = segment.start.latitude + t * (segment.end.latitude - segment.start.latitude),
                        longitude = segment.start.longitude + t * (segment.end.longitude - segment.start.longitude)
                    ),
                    cumulativeMeters = segment.startCumulativeMeters + t * segment.lengthMeters,
                    distanceToRouteMeters = distance,
                    bearingDegrees = segment.bearingDegrees
                )
            }
        }
        return best
    }

    private fun pointAtDistance(segments: List<Segment>, targetMeters: Double): PointOnRoute {
        var remaining = targetMeters.coerceAtLeast(0.0)
        var last = segments.last()
        for (segment in segments) {
            if (remaining <= segment.lengthMeters || segment === last) {
                val t = if (segment.lengthMeters <= 0.0) 0.0 else (remaining / segment.lengthMeters).coerceIn(0.0, 1.0)
                return PointOnRoute(
                    point = LatLngPoint(
                        latitude = segment.start.latitude + t * (segment.end.latitude - segment.start.latitude),
                        longitude = segment.start.longitude + t * (segment.end.longitude - segment.start.longitude)
                    ),
                    bearingDegrees = segment.bearingDegrees
                )
            }
            remaining -= segment.lengthMeters
            last = segment
        }
        return PointOnRoute(last.end, last.bearingDegrees)
    }
}
