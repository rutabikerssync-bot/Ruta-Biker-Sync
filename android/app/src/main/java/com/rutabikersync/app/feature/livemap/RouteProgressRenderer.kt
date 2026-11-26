package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshTelemetry
import com.rutabikersync.app.feature.routes.LatLngPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos

data class RouteProgressSplit(
    val completedPoints: List<LatLngPoint>,
    val remainingPoints: List<LatLngPoint>,
    val progressFraction: Float
)

/**
 * Divide la polilínea activa en tramo recorrido (gris pizarra) y tramo pendiente
 * (azul vibrante) usando proyección ortogonal sobre el segmento más cercano al piloto.
 * En fase de retorno mantiene visible el tramo de ida ya completado.
 */
object RouteProgressRenderer {
    const val COMPLETED_SOURCE_ID = "rbs-route-completed-source"
    const val COMPLETED_LAYER_ID = "rbs-route-completed-line"
    const val COMPLETED_COLOR = "#64748B"
    const val COMPLETED_WIDTH = 5.5f
    const val COMPLETED_OPACITY = 0.72f

    fun splitRouteProgress(
        waypoints: List<LatLngPoint>,
        riderLat: Double,
        riderLon: Double
    ): RouteProgressSplit {
        if (waypoints.size < 2 || !MeshTelemetry.isValidPosition(riderLat, riderLon)) {
            return RouteProgressSplit(
                completedPoints = emptyList(),
                remainingPoints = waypoints,
                progressFraction = 0f
            )
        }

        var bestSegIdx = 0
        var bestT = 0.0
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestSplitPoint = waypoints.first()

        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val cosLat = cos(Math.toRadians((a.latitude + b.latitude) * 0.5)).coerceAtLeast(0.1)
            val dx = (b.longitude - a.longitude) * cosLat
            val dy = b.latitude - a.latitude
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq > 1e-12) {
                val px = (riderLon - a.longitude) * cosLat
                val py = riderLat - a.latitude
                ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val projLat = a.latitude + t * (b.latitude - a.latitude)
            val projLon = a.longitude + t * (b.longitude - a.longitude)
            val errX = (riderLon - projLon) * cosLat
            val errY = riderLat - projLat
            val distSq = errX * errX + errY * errY
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestSegIdx = i
                bestT = t
                bestSplitPoint = LatLngPoint(projLat, projLon)
            }
        }

        val completed = buildList {
            for (i in 0..bestSegIdx) add(waypoints[i])
            if (bestT > 0.0 || bestSegIdx > 0) add(bestSplitPoint)
        }
        val remaining = buildList {
            add(bestSplitPoint)
            for (i in (bestSegIdx + 1) until waypoints.size) add(waypoints[i])
        }
        val fraction = ((bestSegIdx + bestT) / (waypoints.size - 1).coerceAtLeast(1))
            .toFloat()
            .coerceIn(0f, 1f)

        return RouteProgressSplit(
            completedPoints = if (completed.size >= 2) completed else emptyList(),
            remainingPoints = if (remaining.size >= 2) remaining else emptyList(),
            progressFraction = fraction
        )
    }

    fun drawProgressiveRoute(
        map: MapLibreMap,
        activeWaypoints: List<LatLngPoint>,
        outboundWaypointsIfReturn: List<LatLngPoint>,
        riderLat: Double,
        riderLon: Double,
        ensureActiveLayers: (org.maplibre.android.maps.Style) -> Unit
    ) {
        val style = map.style ?: return
        ensureActiveLayers(style)
        if (style.getSource(COMPLETED_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(COMPLETED_SOURCE_ID, FeatureCollection.fromFeatures(emptyList<Feature>())))
        }
        if (style.getLayer(COMPLETED_LAYER_ID) == null) {
            val completedLayer = LineLayer(COMPLETED_LAYER_ID, COMPLETED_SOURCE_ID).withProperties(
                PropertyFactory.lineCap(RouteStrokeStyle.CAP_AND_JOIN),
                PropertyFactory.lineJoin(RouteStrokeStyle.CAP_AND_JOIN),
                PropertyFactory.lineColor(COMPLETED_COLOR),
                PropertyFactory.lineOpacity(COMPLETED_OPACITY),
                PropertyFactory.lineWidth(COMPLETED_WIDTH)
            )
            style.addLayerBelow(completedLayer, RouteStrokeStyle.HALO_LAYER_ID)
        }

        val completedSource = style.getSourceAs<GeoJsonSource>(COMPLETED_SOURCE_ID) ?: return
        val activeSource = style.getSourceAs<GeoJsonSource>(RouteStrokeStyle.SOURCE_ID) ?: return

        if (activeWaypoints.size < 2) {
            completedSource.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            activeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }

        val split = splitRouteProgress(activeWaypoints, riderLat, riderLon)
        val completedFeatures = buildList {
            if (outboundWaypointsIfReturn.size >= 2) {
                add(Feature.fromGeometry(LineString.fromLngLats(outboundWaypointsIfReturn.map { Point.fromLngLat(it.longitude, it.latitude) })))
            }
            if (split.completedPoints.size >= 2) {
                add(Feature.fromGeometry(LineString.fromLngLats(split.completedPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
            }
        }
        completedSource.setGeoJson(FeatureCollection.fromFeatures(completedFeatures))

        val remainingFeatures = if (split.remainingPoints.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(split.remainingPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            emptyList()
        }
        activeSource.setGeoJson(FeatureCollection.fromFeatures(remainingFeatures))
    }
}
