package com.rutabikersync.app.feature.routes

import com.rutabikersync.app.feature.groups.ClubRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repositorio central de programación de rodadas, confirmación de asistencia y protocolo de llegada segura a casa.
 */
object RideScheduleRepository {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    private val _currentRide = MutableStateFlow<ScheduledRide?>(null)
    val currentRide: StateFlow<ScheduledRide?> = _currentRide.asStateFlow()

    fun publishScheduledRide(ride: ScheduledRide) {
        _currentRide.value = ride
    }

    /**
     * Actualiza la confirmación de asistencia de un integrante específico.
     */
    fun updateAttendance(userId: String, newStatus: AttendanceStatus) {
        val current = _currentRide.value ?: return
        val nowFormatted = timeFormat.format(Date())
        val updated = current.confirmations.map { member ->
            if (member.userId == userId) {
                member.copy(
                    attendanceStatus = newStatus,
                    confirmedAtFormatted = if (newStatus == AttendanceStatus.CONFIRMED) "Hoy · $nowFormatted" else null
                )
            } else {
                member
            }
        }
        _currentRide.value = current.copy(confirmations = updated)
    }

    /**
     * Check-in de seguridad: El participante confirma que ha llegado sano y salvo a su casa.
     */
    fun confirmSafeHomeArrival(userId: String) {
        val current = _currentRide.value ?: return
        val nowFormatted = timeFormat.format(Date())
        val updated = current.confirmations.map { member ->
            if (member.userId == userId) {
                member.copy(
                    safeHomeStatus = SafeHomeStatus.SAFE_AT_HOME,
                    safeHomeAtFormatted = nowFormatted
                )
            } else {
                member
            }
        }
        _currentRide.value = current.copy(confirmations = updated)
    }

    /**
     * Crea una nueva rodada programada a partir de los puntos seleccionados en el mapa.
     */
    fun scheduleNewRide(
        title: String,
        originName: String,
        originLat: Double,
        originLon: Double,
        destinationName: String,
        destinationLat: Double,
        destinationLon: Double,
        selectedRoute: RouteAlternative = DefaultRouteCatalog.tovarDirecta,
        availableRoutes: List<RouteAlternative> = listOf(DefaultRouteCatalog.tovarDirecta, DefaultRouteCatalog.tovarElJarillo, DefaultRouteCatalog.tovarElJunquito),
        returnStrategy: ReturnStrategy,
        dispersionPointName: String,
        dispersionLat: Double,
        dispersionLon: Double,
        meetingTime: String,
        departureTime: String,
        dateSchedule: String,
        distanceKm: Double,
        estimatedMinutes: Int
    ) {
        val newRide = ScheduledRide(
            id = "ride-custom-${System.currentTimeMillis()}",
            title = title,
            origin = WaypointLocation(
                name = originName,
                latitude = originLat,
                longitude = originLon,
                timeLabel = meetingTime
            ),
            destination = WaypointLocation(
                name = destinationName,
                latitude = destinationLat,
                longitude = destinationLon,
                timeLabel = departureTime
            ),
            selectedRoute = selectedRoute,
            availableRoutes = availableRoutes,
            returnPlan = ReturnPlan(
                strategy = returnStrategy,
                dispersionPoint = WaypointLocation(
                    name = dispersionPointName,
                    latitude = dispersionLat,
                    longitude = dispersionLon
                )
            ),
            safeArrivalProtocol = SafeArrivalProtocol(enabled = true),
            dateSchedule = dateSchedule,
            meetingTime = meetingTime,
            departureTime = departureTime,
            distanceKm = distanceKm,
            estimatedMinutes = estimatedMinutes,
            confirmations = emptyList(),
            isPublished = true
        )
        _currentRide.value = newRide
    }

    // Historial oficial de rodadas completadas (inicia en cero para registros reales)
    private val _rideHistory = MutableStateFlow<List<RideHistoryItem>>(emptyList())
    val rideHistory: StateFlow<List<RideHistoryItem>> = _rideHistory.asStateFlow()

    /**
     * Sustituye la ruta activa por una recalculada sobre vías reales, manteniendo
     * sincronizadas la distancia y la duración que se muestran en la cabina.
     * Si no hay rodada publicada no se hace nada (no se inventa una ruta).
     */
    fun updateSelectedRoute(route: RouteAlternative) {
        val current = _currentRide.value ?: return
        _currentRide.value = current.copy(
            selectedRoute = route,
            distanceKm = route.distanceKm,
            estimatedMinutes = route.estimatedMinutes
        )
    }

    /**
     * Limpia completamente el historial de rodadas.
     */
    fun clearHistory() {
        _rideHistory.value = emptyList()
    }

    /**
     * Cancela o finaliza la rodada activa programada.
     */
    fun clearCurrentRide() {
        _currentRide.value = null
    }

    /**
     * Registra una rodada completada al inicio de la lista de historial con métricas analíticas.
     */
    fun recordCompletedRide(
        item: RideHistoryItem
    ) {
        val currentList = _rideHistory.value.toMutableList()
        currentList.add(0, item)
        _rideHistory.value = currentList
    }

    /**
     * Consolida y registra una rodada completada a partir de los submódulos de telemetría y gamificación.
     */
    fun recordCompletedRideWithMetrics(
        title: String,
        originName: String,
        destinationName: String,
        distanceKm: Double,
        meshKm: Double,
        durationMinutes: Int,
        xpEarned: Int,
        badgesUnlocked: List<String> = emptyList(),
        clubName: String = "Águilas del Camino MC",
        roleInRide: String = "Punta",
        relaysCount: Int = 0,
        sosAssistsCount: Int = 0,
        confirmedParticipantsCount: Int = 1
    ) {
        val item = RideHistoryItem(
            id = "ride_${System.currentTimeMillis()}",
            title = title,
            dateFormatted = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
            originName = originName,
            destinationName = destinationName,
            distanceKm = distanceKm,
            meshKm = meshKm,
            durationMinutes = durationMinutes,
            avgSpeedKmh = if (durationMinutes > 0) ((distanceKm / durationMinutes) * 60).toInt() else 0,
            confirmedParticipantsCount = confirmedParticipantsCount,
            safeHomeCount = confirmedParticipantsCount,
            roadType = "Carretera nacional / Convoy",
            badgeStatus = "✅ Completada",
            leadRider = "Capitán de Ruta",
            notes = "Rodada concluida con telemetría de malla y retorno seguro.",
            xpEarned = xpEarned,
            badgesUnlocked = badgesUnlocked,
            clubName = clubName,
            roleInRide = roleInRide,
            relaysCount = relaysCount,
            sosAssistsCount = sosAssistsCount,
            timestampEpochMs = System.currentTimeMillis()
        )
        recordCompletedRide(item)
    }

    /**
     * Genera datos de bitácora de muestra para verificación visual e inspección analítica.
     */
    fun loadDemoHistory() {
        val now = System.currentTimeMillis()
        val dayMs = 86400000L
        _rideHistory.value = listOf(
            RideHistoryItem(
                id = "ride_hist_01",
                title = "Travesía Sierra Nevada · Ruta de la Cumbre",
                dateFormatted = "Ayer, 08:30 AM",
                originName = "Mérida (Plaza Bolívar)",
                destinationName = "Páramo La Culata",
                distanceKm = 48.5,
                meshKm = 46.2,
                durationMinutes = 115,
                avgSpeedKmh = 38,
                confirmedParticipantsCount = 6,
                safeHomeCount = 6,
                roadType = "Pavimento de montaña / Curvas",
                badgeStatus = "✅ Completada",
                leadRider = "Oscar (Capitán)",
                roleInRide = "Punta",
                clubName = "Águilas del Camino MC",
                xpEarned = 145,
                badgesUnlocked = listOf("convoy_seguro", "sin_cobertura"),
                relaysCount = 38,
                sosAssistsCount = 0,
                timestampEpochMs = now - dayMs
            ),
            RideHistoryItem(
                id = "ride_hist_02",
                title = "Circuito Valles del Tuy · Enlace Off-Grid",
                dateFormatted = "Hace 3 días, 06:45 AM",
                originName = "Caracas (Hoyo de la Puerta)",
                destinationName = "Charallave Sur",
                distanceKm = 64.0,
                meshKm = 58.7,
                durationMinutes = 95,
                avgSpeedKmh = 52,
                confirmedParticipantsCount = 8,
                safeHomeCount = 8,
                roadType = "Autopista / Vía Rápida",
                badgeStatus = "✅ Completada",
                leadRider = "Carlos (Sargento)",
                roleInRide = "Scout",
                clubName = "Centauros de Acero MC",
                xpEarned = 180,
                badgesUnlocked = listOf("viajero", "racha_3"),
                relaysCount = 52,
                sosAssistsCount = 1,
                timestampEpochMs = now - (3 * dayMs)
            ),
            RideHistoryItem(
                id = "ride_hist_03",
                title = "Ruta Nocturna El Jarillo · Neblina Táctica",
                dateFormatted = "Hace 1 semana, 07:15 PM",
                originName = "San Antonio de los Altos",
                destinationName = "El Jarillo",
                distanceKm = 36.2,
                meshKm = 35.0,
                durationMinutes = 80,
                avgSpeedKmh = 29,
                confirmedParticipantsCount = 4,
                safeHomeCount = 4,
                roadType = "Asfalto quebrado / Neblina",
                badgeStatus = "✅ Completada",
                leadRider = "Oscar (Capitán)",
                roleInRide = "Punta",
                clubName = "Águilas del Camino MC",
                xpEarned = 110,
                badgesUnlocked = listOf("piloto_protegido"),
                relaysCount = 29,
                sosAssistsCount = 0,
                timestampEpochMs = now - (7 * dayMs)
            )
        )
    }
}