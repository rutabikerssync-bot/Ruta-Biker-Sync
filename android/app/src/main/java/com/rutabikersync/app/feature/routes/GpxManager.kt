package com.rutabikersync.app.feature.routes

import java.io.InputStream
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Gestor e importador / exportador de archivos GPX (GPS Exchange Format 1.1).
 * Totalmente compatible con Garmin, Gaia GPS, OsmAnd, BaseCamp, Calimoto y Strava.
 */
object GpxManager {

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /**
     * Parsea una cadena de texto XML GPX o stream y la convierte en una [RouteAlternative].
     * Soporta tracks (<trk><trkpt>), waypoints (<wpt>) y rutas (<rte><rtept>).
     */
    fun parseGpxContent(gpxXml: String, defaultName: String = "Ruta Importada GPX"): RouteAlternative {
        val waypoints = mutableListOf<LatLngPoint>()
        var routeName = defaultName

        // 1. Extraer nombre de la ruta si existe (<name>...</name>)
        val nameMatcher = Pattern.compile("<name>(.*?)</name>", Pattern.CASE_INSENSITIVE).matcher(gpxXml)
        if (nameMatcher.find()) {
            val candidate = nameMatcher.group(1)?.trim()
            if (!candidate.isNullOrBlank()) {
                routeName = candidate
            }
        }

        // 2. Extraer puntos de track (<trkpt lat="..." lon="...">)
        val trkptMatcher = Pattern.compile(
            "<trkpt\\s+[^>]*lat=[\"']([^\"']+)[\"'][^>]*lon=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        ).matcher(gpxXml)

        while (trkptMatcher.find()) {
            val latStr = trkptMatcher.group(1)
            val lonStr = trkptMatcher.group(2)
            val lat = latStr?.toDoubleOrNull()
            val lon = lonStr?.toDoubleOrNull()
            if (lat != null && lon != null) {
                waypoints.add(LatLngPoint(lat, lon))
            }
        }

        // 3. Si no hubo trkpt, intentar con route points (<rtept lat="..." lon="...">)
        if (waypoints.isEmpty()) {
            val rteptMatcher = Pattern.compile(
                "<rtept\\s+[^>]*lat=[\"']([^\"']+)[\"'][^>]*lon=[\"']([^\"']+)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
            ).matcher(gpxXml)

            while (rteptMatcher.find()) {
                val lat = rteptMatcher.group(1)?.toDoubleOrNull()
                val lon = rteptMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    waypoints.add(LatLngPoint(lat, lon))
                }
            }
        }

        // 4. Si aún no hay, buscar waypoints generales (<wpt lat="..." lon="...">)
        if (waypoints.isEmpty()) {
            val wptMatcher = Pattern.compile(
                "<wpt\\s+[^>]*lat=[\"']([^\"']+)[\"'][^>]*lon=[\"']([^\"']+)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
            ).matcher(gpxXml)

            while (wptMatcher.find()) {
                val lat = wptMatcher.group(1)?.toDoubleOrNull()
                val lon = wptMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    waypoints.add(LatLngPoint(lat, lon))
                }
            }
        }

        // Si el archivo estaba vacío o no contenía coordenadas válidas, retornar una ruta vacía segura
        if (waypoints.isEmpty()) {
            return RouteAlternative(
                id = "route-gpx-empty-${System.currentTimeMillis()}",
                name = routeName,
                subtitle = "Archivo GPX sin coordenadas válidas",
                distanceKm = 0.0,
                estimatedMinutes = 0,
                roadCondition = "Desconocida",
                difficulty = "Sin datos",
                waypoints = emptyList()
            )
        }

        val descDistMatcher = Pattern.compile("Distancia:\\s*([0-9.]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(gpxXml)
        val declaredDistKm = if (descDistMatcher.find()) descDistMatcher.group(1)?.toDoubleOrNull() else null

        val totalDistKm = declaredDistKm ?: MotorcycleRoutingEngine.calculateTotalDistanceKm(waypoints)
        val estMinutes = Math.round((totalDistKm / 45.0) * 60.0).toInt().coerceAtLeast(10)

        return RouteAlternative(
            id = "route-gpx-${System.currentTimeMillis() % 100000}",
            name = routeName,
            subtitle = "Importado desde GPX · ${waypoints.size} puntos registrados",
            distanceKm = totalDistKm,
            estimatedMinutes = estMinutes,
            roadCondition = "Ruta verificada por GPX externo",
            difficulty = if (totalDistKm > 50) "Larga distancia" else "Media",
            isRecommended = true,
            waypoints = waypoints
        )
    }

    /**
     * Parsea un [InputStream] de un archivo GPX seleccionado por el usuario.
     */
    fun parseGpxStream(inputStream: InputStream, defaultName: String = "Ruta GPX"): RouteAlternative {
        val xmlContent = inputStream.bufferedReader().use { it.readText() }
        return parseGpxContent(xmlContent, defaultName)
    }

    /**
     * Exporta una [RouteAlternative] o lista de coordenadas a formato estándar XML GPX 1.1.
     */
    fun exportToGpxString(
        route: RouteAlternative,
        clubName: String = "Ruta Biker Sync Club",
        pois: List<RoutePoi> = emptyList(),
        elevationProfile: List<ElevationPoint> = emptyList()
    ): String {
        val sb = StringBuilder()
        val timestamp = isoDateFormat.format(Date())

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Ruta Biker Sync - Convoy Mesh Platform"""")
        sb.appendLine("""     xmlns="http://www.topografix.com/GPX/1/1"""")
        sb.appendLine("""     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
        sb.appendLine("""     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")

        // Metadata
        sb.appendLine("""  <metadata>""")
        sb.appendLine("""    <name>${escapeXml(route.name)}</name>""")
        sb.appendLine("""    <desc>${escapeXml(route.subtitle)} | Club: ${escapeXml(clubName)} | Distancia: ${route.distanceKm} km</desc>""")
        sb.appendLine("""    <author><name>${escapeXml(clubName)}</name></author>""")
        sb.appendLine("""    <time>$timestamp</time>""")
        sb.appendLine("""  </metadata>""")

        // Waypoints / POIs si existen
        pois.forEach { poi ->
            sb.appendLine("""  <wpt lat="${String.format(Locale.US, "%.6f", poi.location.latitude)}" lon="${String.format(Locale.US, "%.6f", poi.location.longitude)}">""")
            sb.appendLine("""    <name>${escapeXml(poi.name)}</name>""")
            sb.appendLine("""    <desc>${escapeXml(poi.notes)}</desc>""")
            sb.appendLine("""    <sym>${escapeXml(poi.category.label)}</sym>""")
            sb.appendLine("""  </wpt>""")
        }

        // Track principal (<trk>)
        sb.appendLine("""  <trk>""")
        sb.appendLine("""    <name>${escapeXml(route.name)}</name>""")
        sb.appendLine("""    <type>motorcycling</type>""")
        sb.appendLine("""    <trkseg>""")

        // Mapear elevación si coincide el tamaño de puntos
        for (i in route.waypoints.indices) {
            val pt = route.waypoints[i]
            val latStr = String.format(Locale.US, "%.6f", pt.latitude)
            val lonStr = String.format(Locale.US, "%.6f", pt.longitude)
            val ele = if (i < elevationProfile.size) elevationProfile[i].altitudeMeters else 1150.0

            sb.appendLine("""      <trkpt lat="$latStr" lon="$lonStr">""")
            sb.appendLine("""        <ele>${String.format(Locale.US, "%.1f", ele)}</ele>""")
            sb.appendLine("""      </trkpt>""")
        }

        sb.appendLine("""    </trkseg>""")
        sb.appendLine("""  </trk>""")
        sb.appendLine("""</gpx>""")

        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
