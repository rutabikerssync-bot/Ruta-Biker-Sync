package com.rutabikersync.app.feature.routes

import com.rutabikersync.app.feature.groups.ClubRole
import java.time.Instant

/**
 * Coordenada geográfica serializable e independiente de frameworks externos.
 */
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * Alternativa de ruta trazada sobre la vía con perfil de manejo y coordenadas reales de carretera.
 */
data class RouteAlternative(
    val id: String,
    val name: String,
    val subtitle: String,
    val distanceKm: Double,
    val estimatedMinutes: Int,
    val roadCondition: String, // e.g. "Asfalto óptimo · Curvas medias"
    val difficulty: String,    // e.g. "Media", "Escénica / Técnica", "Fácil"
    val isRecommended: Boolean = false,
    val waypoints: List<LatLngPoint>
)

object DefaultRouteCatalog {
    var customAssetLoader: ((String) -> String?)? = null

    val tovarDirecta: RouteAlternative
        get() = loadCachedRoutes("tovar_routes.json").firstOrNull() ?: RouteAlternative(
            id = "route-tovar-directa",
            name = "Ruta 1: Colonia Tovar Directa",
            subtitle = "Ruta principal más rápida · Asfalto verificado",
            distanceKm = 48.5,
            estimatedMinutes = 65,
            roadCondition = "Asfalto óptimo · Curvas medias",
            difficulty = "Recomendada",
            isRecommended = true,
            waypoints = emptyList() // Jamás cuerdas rectas inventadas
        )

    val tovarElJarillo: RouteAlternative
        get() = loadCachedRoutes("tovar_routes.json").getOrNull(1) ?: RouteAlternative(
            id = "route-tovar-jarillo",
            name = "Ruta 2: Colonia Tovar Variante",
            subtitle = "Variante escénica por El Jarillo",
            distanceKm = 54.2,
            estimatedMinutes = 85,
            roadCondition = "Curvas cerradas de montaña",
            difficulty = "Técnica / Escénica",
            isRecommended = false,
            waypoints = emptyList()
        )

    val tovarElJunquito: RouteAlternative
        get() = loadCachedRoutes("tovar_routes.json").getOrNull(2) ?: tovarElJarillo

    /**
     * Carga geometrías vectoriales de alta precisión (cientos de puntos sobre calzada real)
     * generadas por el motor de routing para fallback offline sin internet.
     */
    fun loadCachedRoutes(assetFileName: String): List<RouteAlternative> {
        return try {
            val jsonStr = customAssetLoader?.invoke(assetFileName)
                ?: com.rutabikersync.app.App.instance.assets.open("routes/$assetFileName").bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<RouteAlternative>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val coords = obj.getJSONArray("coordinates")
                val pts = ArrayList<LatLngPoint>(coords.length())
                for (j in 0 until coords.length()) {
                    val c = coords.getJSONArray(j)
                    pts.add(LatLngPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
                }
                list.add(
                    RouteAlternative(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        subtitle = obj.getString("subtitle"),
                        distanceKm = obj.getDouble("distanceKm"),
                        estimatedMinutes = obj.getInt("estimatedMinutes"),
                        roadCondition = obj.getString("roadCondition"),
                        difficulty = obj.getString("difficulty"),
                        isRecommended = obj.getBoolean("isRecommended"),
                        waypoints = pts
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getRoutesFor(destLat: Double, destLon: Double, originLat: Double, originLon: Double): List<RouteAlternative> {
        // Caso específico: Valencia / Ciudad Chávez (Carabobo) - Eje Norte 100% sobre asfalto con miles de puntos reales
        if (destLon in -68.25..-67.75 && destLat in 10.05..10.30) {
            val cached = loadCachedRoutes("valencia_routes.json")
            if (cached.isNotEmpty()) return cached
        }

        // Caso específico: El Jarillo (coordenadas precisas sobre asfalto de montaña)
        if (destLon in -67.24..-67.12 && destLat in 10.30..10.38) {
            val cached = loadCachedRoutes("jarillo_routes.json")
            if (cached.isNotEmpty()) return cached
        }

        // Caso específico: Colonia Tovar
        if (destLon in -67.35..-67.24 && destLat in 10.38..10.45) {
            val cached = loadCachedRoutes("tovar_routes.json")
            if (cached.isNotEmpty()) return cached
        }

        // Si no hay geometría vectorial real disponible en los assets ni en línea, se devuelve lista vacía
        // NUNCA se inventan cuerdas rectas ni trazados artificiales sobre casas o agua.
        return emptyList()
    }
}

/**
 * Representa una ubicación geográfica o waypoint de la ruta (Origen, Destino, Desconcentración).
 */
data class WaypointLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val description: String = "",
    val timeLabel: String = ""
)

/**
 * Estrategia de retorno del convoy al finalizar el destino.
 */
enum class ReturnStrategy(val title: String, val description: String) {
    SAME_ROUTE("Retorno en Convoy (Misma Ruta)", "El grupo regresa en formación cerrada por el mismo corredor"),
    CIRCUIT_LOOP("Circuito de Vuelta (Ruta Circular)", "Retorno por una carretera alterna completando un circuito"),
    OPEN_RETURN("Retorno Libre / Desconcentración", "El convoy rompe filas en el punto acordado y cada piloto regresa a su ritmo")
}

/**
 * Plan de retorno y punto de desconcentración.
 */
data class ReturnPlan(
    val strategy: ReturnStrategy = ReturnStrategy.SAME_ROUTE,
    val dispersionPoint: WaypointLocation = WaypointLocation(
        name = "Redoma San Antonio de Los Altos",
        latitude = 10.3750,
        longitude = -66.9630,
        description = "Punto de desconcentración y despedida del convoy",
        timeLabel = "04:30 PM"
    ),
    val notes: String = "Briefing final de seguridad antes de dispersión"
)

/**
 * Protocolo de seguridad para garantizar que todos los participantes lleguen a salvo a sus casas.
 */
data class SafeArrivalProtocol(
    val enabled: Boolean = true,
    val targetMaxReturnMinutes: Int = 120,
    val notes: String = "Al romper filas, cada integrante debe pulsar 'Llegué a salvo a casa' para cerrar la bitácora."
)

/**
 * Estado de confirmación de asistencia a la rodada convocada.
 */
enum class AttendanceStatus(val label: String) {
    CONFIRMED("✓ Confirmado"),
    PENDING("⏳ Pendiente"),
    DECLINED("✕ No asiste")
}

/**
 * Estado del retorno individual a casa de cada participante.
 */
enum class SafeHomeStatus(val label: String) {
    PENDING("En convoy / En ruta"),
    TRAVELLING_HOME("🏍 En ruta a casa"),
    SAFE_AT_HOME("🏠 En casa seguro")
}

/**
 * Registro de asistencia y llegada segura de un participante.
 */
data class ParticipantConfirmation(
    val userId: String,
    val displayName: String,
    val bikeModel: String,
    val clubRole: ClubRole?,
    val attendanceStatus: AttendanceStatus = AttendanceStatus.PENDING,
    val safeHomeStatus: SafeHomeStatus = SafeHomeStatus.PENDING,
    val confirmedAtFormatted: String? = null,
    val safeHomeAtFormatted: String? = null
)

/**
 * Rodada programada completa con ruta en mapa, retorno, seguridad y confirmaciones de miembros.
 */
data class ScheduledRide(
    val id: String,
    val title: String,
    val origin: WaypointLocation,
    val destination: WaypointLocation,
    val selectedRoute: RouteAlternative = DefaultRouteCatalog.tovarDirecta,
    val availableRoutes: List<RouteAlternative> = listOf(DefaultRouteCatalog.tovarDirecta, DefaultRouteCatalog.tovarElJarillo, DefaultRouteCatalog.tovarElJunquito),
    val returnPlan: ReturnPlan,
    val safeArrivalProtocol: SafeArrivalProtocol,
    val dateSchedule: String,
    val meetingTime: String,
    val departureTime: String,
    val distanceKm: Double,
    val estimatedMinutes: Int,
    val confirmations: List<ParticipantConfirmation>,
    val isPublished: Boolean = true,
    val description: String? = null,
    val startsAt: Instant? = null,
    /** UUID real en club_routes; null para una ruta libre solo local. */
    val clubRouteId: String? = null
)

/**
 * Registro histórico de una rodada realizada con métricas y resumen de participantes.
 * Enriquecido para Fase 7.0.2 (Opción A): Telemetría de malla, XP de gamificación y auditoría de convoy.
 */
data class RideHistoryItem(
    val id: String,
    val title: String,
    val dateFormatted: String,
    val originName: String,
    val destinationName: String,
    val distanceKm: Double,
    val durationMinutes: Int,
    val avgSpeedKmh: Int,
    val confirmedParticipantsCount: Int,
    val safeHomeCount: Int,
    val roadType: String,
    val badgeStatus: String = "✓ Completada",
    val leadRider: String = "Oscar (Capitán)",
    val notes: String = "",
    val waypoints: List<LatLngPoint> = emptyList(),
    // Campos analíticos Fase 7.0.2 (Opción A con 100% retrocompatibilidad)
    val meshKm: Double = 0.0,
    val xpEarned: Int = 0,
    val badgesUnlocked: List<String> = emptyList(),
    val clubName: String = "",
    val roleInRide: String = "Punta",
    val relaysCount: Int = 0,
    val sosAssistsCount: Int = 0,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
