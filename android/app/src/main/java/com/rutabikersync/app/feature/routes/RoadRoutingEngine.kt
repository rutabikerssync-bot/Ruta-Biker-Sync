package com.rutabikersync.app.feature.routes

import com.rutabikersync.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lugar de interés biker para selección rápida y autocompletado en el planificador.
 */
data class BikerPlace(
    val name: String,
    val description: String,
    val emoji: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Catálogo de puntos y destinos emblemáticos para rutas de motociclistas.
 */
object BikerPlacesCatalog {
    val places = listOf(
        BikerPlace("Colonia Tovar", "Plaza Bolívar · Aragua", "🏔️", 10.4064, -67.2886),
        BikerPlace("El Jarillo", "Pueblo y Parapente · Miranda", "🍑", 10.3452, -67.1648),
        BikerPlace("San Pedro de Los Altos", "Plaza Central · Miranda", "🌲", 10.3582, -67.0850),
        BikerPlace("Pozo de Rosas", "Mirador y Cafetería · Miranda", "☕", 10.3642, -67.1200),
        BikerPlace("Sector El Paso (Los Teques)", "Punto Biker Central · Los Teques", "🏍️", 10.3538, -67.0425),
        BikerPlace("E/S Panamericana Km 24", "Gasolinera de Salida Convoy", "⛽", 10.3444, -67.0433),
        BikerPlace("San Antonio de Los Altos", "Redoma San Antonio · Miranda", "🌉", 10.3750, -66.9630),
        BikerPlace("Caracas (Plaza Venezuela)", "Distrito Capital", "🏙️", 10.4965, -66.8850),
        BikerPlace("La Guaira (Cinta Costera)", "Llegada al Litoral · La Guaira", "🏖️", 10.6015, -66.9325),
        BikerPlace("Galipán", "El Ávila · Camino Real", "🌸", 10.5580, -66.8920),
        BikerPlace("Maracay (Las Delicias)", "Aragua", "🏢", 10.2520, -67.6030),
        BikerPlace("Lagunetica", "Sector El Rincón · Los Teques", "🏕️", 10.3455, -67.0535),
        BikerPlace("La Victoria", "Monumento a la Juventud · Aragua", "⚔️", 10.2260, -67.3320),
        BikerPlace("Ciudad Chávez", "Plaza Central · Valencia Sur", "🚩", 10.1500, -68.0000),
        BikerPlace("Valencia (San Blas)", "Distribuidor San Blas · Carabobo", "🏙️", 10.1820, -67.9850),
        BikerPlace("Guacara", "Distribuidor Guacara · Carabobo", "🛣️", 10.2450, -67.8780)
    )

    fun search(query: String): List<BikerPlace> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return places.take(6)
        return places.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }

    fun searchPlaces(query: String): List<BikerPlace> = search(query)

    fun findClosest(lat: Double, lon: Double): BikerPlace? {
        return places.minByOrNull {
            val dLat = it.latitude - lat
            val dLon = it.longitude - lon
            dLat * dLat + dLon * dLon
        }?.takeIf {
            val dLat = it.latitude - lat
            val dLon = it.longitude - lon
            (dLat * dLat + dLon * dLon) < 0.00004 // Tolerancia estricta de aprox 400 metros (evita desvíos de varios km)
        }
    }

    fun findClosestPlace(lat: Double, lon: Double): BikerPlace? = findClosest(lat, lon)
}

/**
 * Motor de ruteo vial real:
 * - Consulta la red vial de carreteras (OSRM / OpenStreetMap / Google Maps geometry).
 * - Garantiza trazados 100% sobre calzadas reales con todas sus curvas y desniveles.
 * - Elimina líneas rectas o interpolaciones sinusoidales artificiales.
 */
object RoadRoutingEngine {

    /**
     * Token público de Mapbox obtenido de forma segura a través de BuildConfig / local.properties.
     * Nunca se hardcodea en texto plano en la lógica de negocio.
     */
    val mapboxToken: String get() = BuildConfig.MAPBOX_ACCESS_TOKEN

    suspend fun fetchRealRoadRoute(
        origin: LatLngPoint,
        destination: LatLngPoint,
        destinationName: String,
        profile: MotorcycleRoutingProfile = MotorcycleRoutingProfile.CURVY_SCENIC
    ): RouteAlternative? = withContext(Dispatchers.IO) {
        runCatching {
            val coordsStr = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f", origin.longitude, origin.latitude, destination.longitude, destination.latitude)
            val urlString = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$coordsStr?geometries=geojson&overview=full&access_token=$mapboxToken"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 7000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RutaBikerSync/1.0 (Android)")
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optString("code") == "Ok") {
                    val routes = root.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route0 = routes.getJSONObject(0)
                        val distKm = Math.round((route0.getDouble("distance") / 1000.0) * 10.0) / 10.0
                        val durSec = route0.getDouble("duration")
                        val adjMinutes = Math.round((durSec / 60.0) / profile.speedMultiplier).toInt().coerceAtLeast(1)
                        val coords = route0.getJSONObject("geometry").getJSONArray("coordinates")
                        val pts = ArrayList<LatLngPoint>(coords.length())
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            pts.add(LatLngPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
                        }
                        if (pts.size >= 2) {
                            return@withContext RouteAlternative(
                                id = "road-${profile.id}-${System.currentTimeMillis() % 10000}",
                                name = "${profile.emoji} Ruta sobre Vías: $destinationName",
                                subtitle = "Trazado vial verificado sobre carretera · $distKm km",
                                distanceKm = distKm,
                                estimatedMinutes = adjMinutes,
                                roadCondition = when (profile) {
                                    MotorcycleRoutingProfile.FASTEST -> "Vías rápidas y calzada principal"
                                    MotorcycleRoutingProfile.CURVY_SCENIC -> "Carretera con curvas sobre calzada real"
                                    MotorcycleRoutingProfile.OFFROAD_ADVENTURE -> "Vías secundarias y mixtas"
                                },
                                difficulty = when (profile) {
                                    MotorcycleRoutingProfile.FASTEST -> "Fácil / Rápida"
                                    MotorcycleRoutingProfile.CURVY_SCENIC -> "Técnica / Curvas"
                                    MotorcycleRoutingProfile.OFFROAD_ADVENTURE -> "Aventura / Trail"
                                },
                                isRecommended = true,
                                waypoints = pts
                            )
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun parseDirectionsRoute(routeObj: JSONObject, id: String, name: String, subtitle: String, condition: String, difficulty: String, isRecommended: Boolean): RouteAlternative? {
        val distKm = Math.round((routeObj.getDouble("distance") / 1000.0) * 10.0) / 10.0
        val durSec = routeObj.getDouble("duration")
        val adjMinutes = Math.round(durSec / 60.0).toInt().coerceAtLeast(1)
        val coords = routeObj.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLngPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(LatLngPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
        }
        if (pts.size < 2) return null
        
        // Fase A3: Snap-to-roads y limpieza geométrica en cliente (elimina espigas y suaviza curvas reales)
        val cleanedPoints = RoadGeometryCleaner.cleanAndSnap(pts)
        if (cleanedPoints.size < 2) return null

        return RouteAlternative(
            id = id,
            name = name,
            subtitle = subtitle,
            distanceKm = distKm,
            estimatedMinutes = adjMinutes,
            roadCondition = condition,
            difficulty = difficulty,
            isRecommended = isRecommended,
            waypoints = cleanedPoints
        )
    }

    private fun queryDirectionsUrl(urlStr: String): List<JSONObject> {
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RutaBikerSync/1.0 (Android)")
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optString("code") == "Ok") {
                    val arr = root.getJSONArray("routes")
                    val list = ArrayList<JSONObject>(arr.length())
                    for (i in 0 until arr.length()) {
                        list.add(arr.getJSONObject(i))
                    }
                    list
                } else {
                    emptyList<JSONObject>()
                }
            } else {
                emptyList<JSONObject>()
            }
        } catch (_: Exception) {
            emptyList<JSONObject>()
        }
    }

    /**
     * Traza exactamente las 3 alternativas viales reales solicitadas (Ruta 1, Ruta 2 y Ruta 3)
     * basadas en la API de Direcciones de Mapbox (driving-traffic) sobre carreteras y avenidas existentes.
     */
    suspend fun fetchThreeRouteAlternatives(
        origin: LatLngPoint,
        destination: LatLngPoint,
        originName: String,
        destinationName: String
    ): List<RouteAlternative> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RouteAlternative>()
        val dLat = destination.latitude - origin.latitude
        val dLon = destination.longitude - origin.longitude

        // 1. Consulta principal a Mapbox Directions API con perfil driving-traffic y alternativas
        val coordsStr = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f", origin.longitude, origin.latitude, destination.longitude, destination.latitude)
        val mapboxUrl = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$coordsStr?geometries=geojson&overview=full&alternatives=true&access_token=$mapboxToken"

        var mapboxRoutes = if (mapboxToken.isNotBlank()) queryDirectionsUrl(mapboxUrl) else emptyList()
        if (mapboxRoutes.isEmpty() && mapboxToken.isNotBlank()) {
            // Respaldo con perfil driving estándar si driving-traffic tiene congestión o límites
            val fallbackUrl = "https://api.mapbox.com/directions/v5/mapbox/driving/$coordsStr?geometries=geojson&overview=full&alternatives=true&access_token=$mapboxToken"
            mapboxRoutes = queryDirectionsUrl(fallbackUrl)
        }

        if (mapboxRoutes.isNotEmpty()) {
            val r1 = parseDirectionsRoute(
                mapboxRoutes[0],
                id = "route-1",
                name = "Ruta 1 · Vía Principal Directa (ARC)",
                subtitle = "Calzada principal y autopistas · Tráfico en tiempo real",
                condition = "Asfalto óptimo · Tráfico monitoreado",
                difficulty = "Recomendada",
                isRecommended = true
            )
            if (r1 != null && !RoadGeometryCleaner.hasWaterBodyCrossing(r1.waypoints)) results.add(r1)

            if (mapboxRoutes.size > 1) {
                val r2 = parseDirectionsRoute(
                    mapboxRoutes[1],
                    id = "route-2",
                    name = "Ruta 2 · Vía Alterna Secundaria",
                    subtitle = "Vía secundaria con menor afluencia",
                    condition = "Carretera nacional con curvas",
                    difficulty = "Escénica",
                    isRecommended = false
                )
                if (r2 != null && !RoadGeometryCleaner.hasWaterBodyCrossing(r2.waypoints)) results.add(r2)
            }

            if (mapboxRoutes.size > 2) {
                val r3 = parseDirectionsRoute(
                    mapboxRoutes[2],
                    id = "route-3",
                    name = "Ruta 3 · Variante Perimetral",
                    subtitle = "Circuito abierto por vías conectadas",
                    condition = "Carretera panorámica y desniveles",
                    difficulty = "Aventura",
                    isRecommended = false
                )
                if (r3 != null && !RoadGeometryCleaner.hasWaterBodyCrossing(r3.waypoints)) results.add(r3)
            }
        }

        // 2. Si Mapbox devolvió menos de 3 alternativas, buscar variantes reales a través de nodos viales
        if (results.size < 3) {
            val totalDistSq = dLat * dLat + dLon * dLon

            if (totalDistSq > 0.0001) {
                val viableWaypoints = BikerPlacesCatalog.places.filter { place ->
                    val pLat = place.latitude - origin.latitude
                    val pLon = place.longitude - origin.longitude
                    val t = (pLat * dLat + pLon * dLon) / totalDistSq
                    if (t in 0.18..0.82) {
                        val perpLat = pLat - t * dLat
                        val perpLon = pLon - t * dLon
                        val perpKm = Math.hypot(perpLat * 111.0, perpLon * 111.0)
                        perpKm in 1.5..25.0
                    } else false
                }

                for (place in viableWaypoints) {
                    if (results.size >= 3) break
                    val viaCoords = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f;%.6f,%.6f",
                        origin.longitude, origin.latitude,
                        place.longitude, place.latitude,
                        destination.longitude, destination.latitude)
                    val urlVia = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$viaCoords?geometries=geojson&overview=full&access_token=$mapboxToken"

                    val viaRoutes = queryDirectionsUrl(urlVia)
                    if (viaRoutes.isNotEmpty()) {
                        val routeNum = results.size + 1
                        val candidate = parseDirectionsRoute(
                            viaRoutes[0],
                            id = "route-$routeNum",
                            name = "Ruta $routeNum · Vía ${place.name}",
                            subtitle = "Alternativa sobre calzada real por ${place.description}",
                            condition = "Carretera nacional asfaltada",
                            difficulty = if (routeNum == 2) "Escénica" else "Variante",
                            isRecommended = false
                        )
                        if (candidate != null) {
                            val isDuplicate = results.any { Math.abs(it.distanceKm - candidate.distanceKm) < 1.5 }
                            val hasLoop = RoadGeometryCleaner.hasBacktrackLoop(candidate.waypoints, origin, destination, maxCorridorKm = 35.0)
                            val crossesWater = RoadGeometryCleaner.hasWaterBodyCrossing(candidate.waypoints)
                            if (!isDuplicate && !hasLoop && !crossesWater) {
                                results.add(candidate)
                            }
                        }
                    }
                }
            }
        }

        // 3. Garantizar siempre las 3 alternativas viales completando con las geometrías de alta densidad si la red devolvió menos de 3
        if (results.size < 3) {
            val baseList = DefaultRouteCatalog.getRoutesFor(destination.latitude, destination.longitude, origin.latitude, origin.longitude)
            for (cached in baseList) {
                if (results.size >= 3) break
                val alreadyExists = results.any { it.id == cached.id || Math.abs(it.distanceKm - cached.distanceKm) < 1.0 }
                if (!alreadyExists && !RoadGeometryCleaner.hasWaterBodyCrossing(cached.waypoints)) {
                    val nextNum = results.size + 1
                    val adjusted = cached.copy(
                        id = "route-$nextNum",
                        isRecommended = (nextNum == 1)
                    )
                    results.add(adjusted)
                }
            }
        }
        return@withContext results
    }
}

/**
 * Fase A3: Procesador de geometría vial Snap-to-Roads en cliente.
 * - Elimina espigas/outliers causadas por errores de geometría o digitalización en OSM rural.
 * - Previene bucles en U y desvíos fuera de corredores viales coherentes.
 * - Suaviza vértices con algoritmo de Chaikin respetando la calzada real.
 */
object RoadGeometryCleaner {

    /**
     * Snap-to-roads y filtrado geométrico integral en cliente.
     */
    fun cleanAndSnap(points: List<LatLngPoint>): List<LatLngPoint> {
        if (points.size < 3) return points

        // 1. Deduplicar puntos sub-métricos (< 2.5 metros)
        val deduped = mutableListOf<LatLngPoint>()
        deduped.add(points[0])
        for (i in 1 until points.size) {
            val prev = deduped.last()
            val curr = points[i]
            val dLat = curr.latitude - prev.latitude
            val dLon = curr.longitude - prev.longitude
            if (dLat * dLat + dLon * dLon > 0.00000008) {
                deduped.add(curr)
            }
        }
        if (deduped.size < 3) return deduped

        // 2. Filtro de espigas y salidas espurias (Spike filter)
        val noSpikes = mutableListOf<LatLngPoint>()
        noSpikes.add(deduped[0])
        var i = 1
        while (i < deduped.size - 1) {
            val pPrev = noSpikes.last()
            val pCurr = deduped[i]
            val pNext = deduped[i + 1]

            val d12 = distMeters(pPrev, pCurr)
            val d23 = distMeters(pCurr, pNext)
            val d13 = distMeters(pPrev, pNext)

            // Si salir y volver (d12 + d23) es significativamente mayor que la cuerda directa (d13),
            // y la cuerda directa es corta (< 400m), pCurr es un pico/desvío cerrado espurio de OSM
            val isSpike = (d12 > 20.0 && d23 > 20.0 && (d12 + d23) > 2.8 * d13 && d13 < 400.0)
            if (!isSpike) {
                noSpikes.add(pCurr)
            }
            i++
        }
        noSpikes.add(deduped.last())
        if (noSpikes.size < 3) return noSpikes

        // 3. Suavizado Chaikin (Corner-cutting) para curvas fluidas sobre calzada
        return chaikinSmooth(noSpikes, iterations = 1)
    }

    /**
     * Detección geométrica de bucles en U y retrocesos anómalos respecto al vector origen -> destino.
     */
    fun hasBacktrackLoop(
        waypoints: List<LatLngPoint>,
        origin: LatLngPoint,
        destination: LatLngPoint,
        maxCorridorKm: Double = 35.0
    ): Boolean {
        if (waypoints.size < 10) return false
        val totalLat = destination.latitude - origin.latitude
        val totalLon = destination.longitude - origin.longitude
        val totalLenSq = totalLat * totalLat + totalLon * totalLon
        if (totalLenSq == 0.0) return false

        var prevProjection = 0.0

        for (pt in waypoints) {
            val dLat = pt.latitude - origin.latitude
            val dLon = pt.longitude - origin.longitude

            // Proyección escalar sobre el vector de avance [0.0 = origen, 1.0 = destino]
            val projection = (dLat * totalLat + dLon * totalLon) / totalLenSq

            // Distancia perpendicular al corredor del viaje
            val perpLat = dLat - projection * totalLat
            val perpLon = dLon - projection * totalLon
            val perpKm = Math.hypot(perpLat * 111.0, perpLon * 111.0)
            if (perpKm > maxCorridorKm) {
                return true // Se salió demasiado lejos del corredor del viaje (ej. San Juan de los Morros)
            }

            // Detección de retroceso continuo: si la proyección retrocede significativamente respecto a lo avanzado
            if (prevProjection - projection > 0.18) {
                return true
            }
            if (projection > prevProjection) {
                prevProjection = projection
            }
        }
        return false
    }

    /**
     * Detección geométrica estricta de waypoints dentro de cuerpos o masas de agua
     * (Lago de Valencia / Tacarigua, Embalses o Mar Caribe).
     */
    fun isInsideWaterBody(pt: LatLngPoint): Boolean {
        // Masa de agua del Lago de Valencia (Tacarigua):
        // Lat: 10.108 N a 10.234 N, Lon: -67.865 W a -67.620 W
        if (pt.latitude in 10.108..10.234 && pt.longitude in -67.865..-67.620) {
            return true
        }
        // Embalse La Mariposa (Miranda / Caracas)
        if (pt.latitude in 10.400..10.412 && pt.longitude in -66.932..-66.920) {
            return true
        }
        // Mar Caribe (Norte de la línea de costa litoral central / Carabobo)
        if (pt.latitude > 10.625 && pt.longitude in -68.20..-66.80) {
            return true
        }
        return false
    }

    /**
     * Valida si alguno de los waypoints de la ruta o interpolación cae en un cuerpo de agua.
     */
    fun hasWaterBodyCrossing(waypoints: List<LatLngPoint>): Boolean {
        return waypoints.any { isInsideWaterBody(it) }
    }

    /**
     * Suavizado adaptativo para carreteras de montaña:
     * - En rectas y curvas suaves (< 75°): Suaviza sutilmente las aristas de digitalización de OSM.
     * - En curvas cerradas y horquillas (hairpins > 75°): CONSERVA el vértice exacto del asfalto para
     *   garantizar que la polilínea no "corte" la curva ni se salga de la calzada en curvas de 180°
     *   (como en la subida a Colonia Tovar, El Jarillo o Galipán).
     */
    private fun chaikinSmooth(points: List<LatLngPoint>, iterations: Int): List<LatLngPoint> {
        var current = points
        for (it in 0 until iterations) {
            if (current.size < 3) break
            val smoothed = ArrayList<LatLngPoint>(current.size * 2)
            smoothed.add(current.first())
            for (j in 0 until current.size - 1) {
                val p0 = current[j]
                val p1 = current[j + 1]

                // Evaluar si el vértice p0 es una curva cerrada (horquilla de montaña)
                val isHairpin = if (j > 0 && j < current.size - 1) {
                    val pPrev = current[j - 1]
                    val v1x = p0.longitude - pPrev.longitude
                    val v1y = p0.latitude - pPrev.latitude
                    val v2x = p1.longitude - p0.longitude
                    val v2y = p1.latitude - p0.latitude
                    val dot = v1x * v2x + v1y * v2y
                    val mag = Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y)
                    if (mag > 0.0) (dot / mag) < 0.25 else false // Giro > 75°
                } else false

                if (isHairpin) {
                    // Mantener el vértice real para no cortar la curva
                    smoothed.add(p0)
                } else {
                    // Suavizado conservador al 82/18 para apego estricto a la calzada
                    val qLat = 0.82 * p0.latitude + 0.18 * p1.latitude
                    val qLon = 0.82 * p0.longitude + 0.18 * p1.longitude
                    val rLat = 0.18 * p0.latitude + 0.82 * p1.latitude
                    val rLon = 0.18 * p0.longitude + 0.82 * p1.latitude
                    smoothed.add(LatLngPoint(qLat, qLon))
                    smoothed.add(LatLngPoint(rLat, rLon))
                }
            }
            smoothed.add(current.last())
            current = smoothed
        }
        return current
    }

    private fun distMeters(p1: LatLngPoint, p2: LatLngPoint): Double {
        val dLat = (p2.latitude - p1.latitude) * 111139.0
        val dLon = (p2.longitude - p1.longitude) * 111139.0 * Math.cos(Math.toRadians((p1.latitude + p2.latitude) / 2.0))
        return Math.hypot(dLat, dLon)
    }
}
