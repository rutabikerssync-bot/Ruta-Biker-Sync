package com.rutabikersync.app.feature.routes

import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perfiles de ruteo para motocicleta inspirados en BRouter.
 * Permiten optimizar el trazado según el estilo de conducción del club o del piloto.
 */
enum class MotorcycleRoutingProfile(
    val id: String,
    val title: String,
    val shortLabel: String,
    val description: String,
    val emoji: String,
    val speedMultiplier: Double,
    val curvatureWeight: Double,
    val avoidHighways: Boolean,
    val avoidTolls: Boolean,
    val allowUnpaved: Boolean
) {
    FASTEST(
        id = "moto-fastest",
        title = "Rápido / Autopista",
        shortLabel = "Rápida",
        description = "Prioriza autopistas, vías rápidas y pavimento óptimo para trayectos directos.",
        emoji = "⚡",
        speedMultiplier = 1.25,
        curvatureWeight = 0.2,
        avoidHighways = false,
        avoidTolls = false,
        allowUnpaved = false
    ),
    CURVY_SCENIC(
        id = "moto-curvy",
        title = "Curvas de Montaña / Escénica",
        shortLabel = "Curvas",
        description = "Maximiza carreteras secundarias sinuosas, desniveles de montaña y paisajes panorámicos.",
        emoji = "⛰️",
        speedMultiplier = 0.90,
        curvatureWeight = 1.9,
        avoidHighways = true,
        avoidTolls = true,
        allowUnpaved = false
    ),
    OFFROAD_ADVENTURE(
        id = "moto-trail",
        title = "Aventura / Trail Mixto",
        shortLabel = "Trail",
        description = "Habilita pistas de tierra compacta, caminos rurales y tramos de terracería técnica.",
        emoji = "🏕️",
        speedMultiplier = 0.75,
        curvatureWeight = 1.4,
        avoidHighways = true,
        avoidTolls = true,
        allowUnpaved = true
    )
}

/**
 * Punto de elevación y distancia en el perfil altimétrico de la ruta.
 */
data class ElevationPoint(
    val distanceKm: Double,
    val altitudeMeters: Double,
    val coordinate: LatLngPoint
)

/**
 * Punto de interés o parada técnica en la ruta (gasolinera, mirador, peaje, taller).
 */
data class RoutePoi(
    val id: String,
    val name: String,
    val category: PoiCategory,
    val location: LatLngPoint,
    val distanceAlongRouteKm: Double,
    val notes: String = ""
)

enum class PoiCategory(val label: String, val iconEmoji: String) {
    GAS_STATION("Gasolinera / Combustible", "⛽"),
    VIEWPOINT("Mirador Panorámico", "📸"),
    FOOD_REST("Comida / Descanso", "☕"),
    TOLL_BOOTH("Peaje", "💳"),
    WORKSHOP("Taller Mecánico", "🔧"),
    MEETING_POINT("Punto de Encuentro", "🚩")
}

/**
 * Motor de ruteo offline para motocicletas.
 * Genera alternativas de ruta, perfiles de elevación, POIs y hashes de integridad.
 */
object MotorcycleRoutingEngine {

    /**
     * Calcula la distancia Haversine en kilómetros entre dos coordenadas.
     */
    fun calculateHaversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Radio terrestre en km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Calcula la distancia total acumulada de una lista de waypoints.
     */
    fun calculateTotalDistanceKm(points: List<LatLngPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += calculateHaversineKm(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
        }
        return Math.round(total * 10.0) / 10.0
    }

    /**
     * Genera un perfil altimétrico simulado realista de montaña para la polilínea dada.
     */
    fun generateElevationProfile(
        points: List<LatLngPoint>,
        baseAltitudeMeters: Double = 980.0,
        peakAltitudeMeters: Double = 2150.0
    ): List<ElevationPoint> {
        if (points.isEmpty()) return emptyList()

        val totalDist = calculateTotalDistanceKm(points).coerceAtLeast(1.0)
        var currentDist = 0.0
        val elevationList = mutableListOf<ElevationPoint>()

        for (i in points.indices) {
            if (i > 0) {
                currentDist += calculateHaversineKm(
                    points[i - 1].latitude, points[i - 1].longitude,
                    points[i].latitude, points[i].longitude
                )
            }
            val progress = (currentDist / totalDist).coerceIn(0.0, 1.0)
            // Curva parabólica con ruido topográfico sinusoidal para emular cadenas montañosas
            val mountainCurve = sin(progress * Math.PI)
            val subRidges = sin(progress * Math.PI * 4.0) * 85.0
            val altitude = baseAltitudeMeters + (peakAltitudeMeters - baseAltitudeMeters) * mountainCurve + subRidges
            elevationList.add(
                ElevationPoint(
                    distanceKm = Math.round(currentDist * 10.0) / 10.0,
                    altitudeMeters = Math.round(altitude).toDouble(),
                    coordinate = points[i]
                )
            )
        }
        return elevationList
    }

    /**
     * Calcula el desnivel positivo acumulado (ascenso total) en metros.
     */
    fun calculateTotalAscentMeters(elevationProfile: List<ElevationPoint>): Int {
        if (elevationProfile.size < 2) return 0
        var ascent = 0.0
        for (i in 0 until elevationProfile.size - 1) {
            val diff = elevationProfile[i + 1].altitudeMeters - elevationProfile[i].altitudeMeters
            if (diff > 0) {
                ascent += diff
            }
        }
        return ascent.toInt()
    }

    /**
     * Calcula el desnivel negativo acumulado (descenso total) en metros.
     */
    fun calculateTotalDescentMeters(elevationProfile: List<ElevationPoint>): Int {
        if (elevationProfile.size < 2) return 0
        var descent = 0.0
        for (i in 0 until elevationProfile.size - 1) {
            val diff = elevationProfile[i].altitudeMeters - elevationProfile[i + 1].altitudeMeters
            if (diff > 0) {
                descent += diff
            }
        }
        return descent.toInt()
    }

    /**
     * Genera POIs típicos de ruta (gasolineras, miradores) a lo largo del recorrido.
     */
    fun generateRoutePois(points: List<LatLngPoint>, destinationName: String): List<RoutePoi> {
        val totalKm = calculateTotalDistanceKm(points)
        val pois = mutableListOf<RoutePoi>()

        if (points.isNotEmpty()) {
            // POI 1: Salida / Estación de combustible
            pois.add(
                RoutePoi(
                    id = "poi-gas-start",
                    name = "E/S Combustible Salida (Tanque Lleno)",
                    category = PoiCategory.GAS_STATION,
                    location = points.first(),
                    distanceAlongRouteKm = 0.5,
                    notes = "Punto de abastecimiento obligatorio para el convoy antes de partir"
                )
            )
        }

        // POI 2: Mirador a mitad de camino
        if (points.size >= 4) {
            val midIndex = points.size / 2
            pois.add(
                RoutePoi(
                    id = "poi-viewpoint-mid",
                    name = "Mirador Panorámico de Montaña",
                    category = PoiCategory.VIEWPOINT,
                    location = points[midIndex],
                    distanceAlongRouteKm = Math.round(totalKm * 0.5 * 10.0) / 10.0,
                    notes = "Parada de 10 min para reagrupamiento y toma de fotografías"
                )
            )
        }

        // POI 3: Destino
        if (points.isNotEmpty()) {
            pois.add(
                RoutePoi(
                    id = "poi-destination",
                    name = "Llegada: $destinationName",
                    category = PoiCategory.MEETING_POINT,
                    location = points.last(),
                    distanceAlongRouteKm = totalKm,
                    notes = "Estacionamiento principal y punto de desconcentración / refrigerio"
                )
            )
        }

        return pois
    }

    /**
     * Calcula el hash criptográfico SHA-256 de una polilínea para verificar integridad en la malla (R6.1).
     */
    fun computeRouteSha256(points: List<LatLngPoint>): String {
        val rawString = points.joinToString(";") { "${String.format(java.util.Locale.US, "%.5f", it.latitude)},${String.format(java.util.Locale.US, "%.5f", it.longitude)}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Traza una ruta completa aplicando el perfil de moto especificado.
     */
    fun planMotorcycleRoute(
        origin: LatLngPoint,
        destination: LatLngPoint,
        destinationName: String,
        profile: MotorcycleRoutingProfile,
        customWaypoints: List<LatLngPoint> = emptyList()
    ): RouteAlternative {
        val straightDist = calculateHaversineKm(origin.latitude, origin.longitude, destination.latitude, destination.longitude)
        val distanceMultiplier = when (profile) {
            MotorcycleRoutingProfile.FASTEST -> 1.25
            MotorcycleRoutingProfile.CURVY_SCENIC -> 1.45
            MotorcycleRoutingProfile.OFFROAD_ADVENTURE -> 1.55
        }
        val roadDistKm = Math.round(straightDist * distanceMultiplier * 10.0) / 10.0
        val baseSpeedKmh = 45.0 * profile.speedMultiplier
        val estimatedMinutes = Math.round((roadDistKm / baseSpeedKmh) * 60.0).toInt().coerceAtLeast(15)

        // Generación de coordenadas base de ruta sin distorsiones sinusoidales arbitrarias
        val points = mutableListOf<LatLngPoint>()
        points.add(origin)

        val numSteps = (roadDistKm * 1.5).toInt().coerceIn(6, 20)
        for (i in 1 until numSteps) {
            val t = i.toDouble() / numSteps.toDouble()
            val lat = origin.latitude + (destination.latitude - origin.latitude) * t
            val lon = origin.longitude + (destination.longitude - origin.longitude) * t
            points.add(LatLngPoint(lat, lon))
        }

        // Agregar waypoints intermedios si existen
        customWaypoints.forEach { wp ->
            points.add(points.size / 2, wp)
        }
        points.add(destination)

        return RouteAlternative(
            id = "route-${profile.id}-${System.currentTimeMillis() % 10000}",
            name = "${profile.emoji} Ruta ${profile.shortLabel}: Hacia $destinationName",
            subtitle = "${profile.title} · ${profile.description}",
            distanceKm = roadDistKm,
            estimatedMinutes = estimatedMinutes,
            roadCondition = when (profile) {
                MotorcycleRoutingProfile.FASTEST -> "Asfalto óptimo · Rectas y autopista fluida"
                MotorcycleRoutingProfile.CURVY_SCENIC -> "Curvas pronunciadas · Desnivel de montaña"
                MotorcycleRoutingProfile.OFFROAD_ADVENTURE -> "Terreno mixto · Asfalto y terracería compacta"
            },
            difficulty = when (profile) {
                MotorcycleRoutingProfile.FASTEST -> "Fácil / Directa"
                MotorcycleRoutingProfile.CURVY_SCENIC -> "Técnica / Escénica"
                MotorcycleRoutingProfile.OFFROAD_ADVENTURE -> "Exigente / Trail"
            },
            isRecommended = profile == MotorcycleRoutingProfile.CURVY_SCENIC,
            waypoints = points
        )
    }
}
