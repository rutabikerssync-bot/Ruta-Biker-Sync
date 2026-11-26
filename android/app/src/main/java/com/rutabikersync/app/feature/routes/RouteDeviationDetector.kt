package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estado de seguimiento de la ruta activa del piloto.
 */
enum class DeviationStatus {
    ON_TRACK,        // Siguiendo la ruta dentro del umbral (< 150m)
    DEVIATED,        // Fuera de la ruta (> 150m) - Alerta activa
    VOLUNTARY_STOP,  // Parada voluntaria ("Voy a parar") - Alerta silenciada
    ARRIVED          // Llegó cerca del destino: detiene recálculo repetitivo
}

/**
 * Motivo de la parada voluntaria del piloto.
 */
enum class StopReason(val label: String, val emoji: String) {
    FUEL("Carga de Combustible", "⛽"),
    REST_PHOTOS("Descanso / Fotografías", "📸"),
    BIKE_CHECK("Ajuste de Equipaje o Moto", "🔧"),
    HYDRATION("Hidratación / Refrigerio", "☕"),
    PERSONAL("Llamada / Motivo Personal", "📞")
}

/**
 * Información instantánea del estado de desvío y distancia del piloto.
 */
data class RouteDeviationState(
    val status: DeviationStatus = DeviationStatus.ON_TRACK,
    val distanceToRouteMeters: Int = 0,
    val isAlertActive: Boolean = false,
    val stopReason: StopReason? = null,
    val stopStartedAtMillis: Long? = null,
    val lastKnownLocation: LatLngPoint? = null,
    val speedKmh: Float = 0f
)

/**
 * Detector y monitor en tiempo real de desvío de ruta.
 * Monitorea la distancia ortogonal del piloto a la polilínea activa y provee la función
 * "Voy a parar" para evitar falsas alarmas cuando el conductor se detiene a propósito (CHECKLIST.md F).
 */
object RouteDeviationDetector {

    const val DEVIATION_THRESHOLD_METERS = 150 // Margen de desvío para disparar alerta
    const val DEVIATION_SUSTAIN_DURATION_MS = 10_000L // Histéresis: evita alertas ruidosas por jitter GPS
    const val AUTO_RESUME_SPEED_THRESHOLD_KMH = 18f // Velocidad para reanudar marcha tras parada voluntaria
    const val DESTINATION_ARRIVAL_RADIUS_METERS = 120 // Radio de llegada al destino

    private val _deviationState = MutableStateFlow(RouteDeviationState())
    private var deviationStartedAtMs: Long? = null
    private var hasArrivedDestination: Boolean = false
    val deviationState: StateFlow<RouteDeviationState> = _deviationState.asStateFlow()

    private var activePolyline: List<LatLngPoint> = emptyList()

    /**
     * Establece la polilínea activa de la rodada para monitoreo.
     */
    fun setActiveRoutePolyline(points: List<LatLngPoint>) {
        activePolyline = points
        // Una ruta nueva inicia una ventana nueva de estudio del corredor.
        deviationStartedAtMs = null
        hasArrivedDestination = false
    }

    /**
     * Evalúa la posición actual del piloto frente a la polilínea activa.
     */
    fun updateRiderLocation(
        location: LatLngPoint,
        speedKmh: Float = 0f,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val currentState = _deviationState.value

        // Si está en parada voluntaria, verificar si ya reanudó la marcha a velocidad de crucero
        if (currentState.status == DeviationStatus.VOLUNTARY_STOP) {
            if (speedKmh >= AUTO_RESUME_SPEED_THRESHOLD_KMH) {
                // Auto-reanudación: el piloto arrancó la moto y reanudó la marcha
                resumeRouteTracking(nowEpochMs)
            } else {
                deviationStartedAtMs = null
                _deviationState.value = currentState.copy(
                    lastKnownLocation = location,
                    speedKmh = speedKmh
                )
                return
            }
        }

        if (activePolyline.size < 2) {
            deviationStartedAtMs = null
            _deviationState.value = RouteDeviationState(
                status = DeviationStatus.ON_TRACK,
                distanceToRouteMeters = 0,
                isAlertActive = false,
                lastKnownLocation = location,
                speedKmh = speedKmh
            )
            return
        }

        val destination = activePolyline.last()
        val distToDestMeters = distancePointToSegmentMeters(location, destination, destination).toInt()
        if (hasArrivedDestination || distToDestMeters <= DESTINATION_ARRIVAL_RADIUS_METERS) {
            hasArrivedDestination = true
            deviationStartedAtMs = null
            _deviationState.value = RouteDeviationState(
                status = DeviationStatus.ARRIVED,
                distanceToRouteMeters = 0,
                isAlertActive = false,
                stopReason = null,
                stopStartedAtMillis = null,
                lastKnownLocation = location,
                speedKmh = speedKmh
            )
            return
        }

        val minDistanceMeters = computeMinDistanceToPolylineMeters(location, activePolyline)
        val isDeviated = minDistanceMeters > DEVIATION_THRESHOLD_METERS
        if (!isDeviated) {
            deviationStartedAtMs = null
        } else if (deviationStartedAtMs == null) {
            deviationStartedAtMs = nowEpochMs
        }
        val sustainedDeviation = isDeviated &&
            nowEpochMs - (deviationStartedAtMs ?: nowEpochMs) >= DEVIATION_SUSTAIN_DURATION_MS

        _deviationState.value = RouteDeviationState(
            status = if (isDeviated) DeviationStatus.DEVIATED else DeviationStatus.ON_TRACK,
            distanceToRouteMeters = minDistanceMeters,
            isAlertActive = sustainedDeviation,
            stopReason = null,
            stopStartedAtMillis = null,
            lastKnownLocation = location,
            speedKmh = speedKmh
        )
    }

    /**
     * Activa el modo "Voy a parar", silenciando de inmediato la alerta de desvío
     * y notificando el motivo de la parada.
     */
    fun activateVoluntaryStop(reason: StopReason = StopReason.REST_PHOTOS) {
        deviationStartedAtMs = null
        _deviationState.value = _deviationState.value.copy(
            status = DeviationStatus.VOLUNTARY_STOP,
            isAlertActive = false,
            stopReason = reason,
            stopStartedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * Reanuda el monitoreo normal de ruta tras haber estado en parada voluntaria.
     */
    fun resumeRouteTracking(nowEpochMs: Long = System.currentTimeMillis()) {
        val currentLoc = _deviationState.value.lastKnownLocation
        val speed = _deviationState.value.speedKmh

        if (currentLoc != null && activePolyline.size >= 2) {
            val dist = computeMinDistanceToPolylineMeters(currentLoc, activePolyline)
            val isDeviated = dist > DEVIATION_THRESHOLD_METERS
            // Al reanudar tras una parada voluntaria, una desviación se confirma
            // nuevamente durante 10 s; no se revive una alarma antigua de golpe.
            deviationStartedAtMs = if (isDeviated) nowEpochMs else null
            _deviationState.value = RouteDeviationState(
                status = if (isDeviated) DeviationStatus.DEVIATED else DeviationStatus.ON_TRACK,
                distanceToRouteMeters = dist,
                isAlertActive = false,
                stopReason = null,
                stopStartedAtMillis = null,
                lastKnownLocation = currentLoc,
                speedKmh = speed
            )
        } else {
            _deviationState.value = RouteDeviationState(
                status = DeviationStatus.ON_TRACK,
                distanceToRouteMeters = 0,
                isAlertActive = false,
                stopReason = null,
                stopStartedAtMillis = null,
                lastKnownLocation = currentLoc,
                speedKmh = speed
            )
        }
    }

    /**
     * Resetea el detector al finalizar la rodada activa.
     */
    fun reset() {
        activePolyline = emptyList()
        deviationStartedAtMs = null
        _deviationState.value = RouteDeviationState()
    }

    /**
     * Calcula la distancia ortogonal mínima en metros desde un punto a cualquier segmento de la polilínea.
     */
    fun computeMinDistanceToPolylineMeters(point: LatLngPoint, polyline: List<LatLngPoint>): Int {
        if (polyline.isEmpty()) return 0
        if (polyline.size == 1) {
            return (MotorcycleRoutingEngine.calculateHaversineKm(
                point.latitude, point.longitude,
                polyline[0].latitude, polyline[0].longitude
            ) * 1000.0).toInt()
        }

        var minDistance = Double.MAX_VALUE

        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i + 1]
            val dist = distancePointToSegmentMeters(point, p1, p2)
            if (dist < minDistance) {
                minDistance = dist
            }
        }

        return minDistance.toInt()
    }

    /**
     * Distancia euclidiana proyectada en metros desde un punto P a un segmento de línea [A, B].
     */
    private fun distancePointToSegmentMeters(
        p: LatLngPoint,
        a: LatLngPoint,
        b: LatLngPoint
    ): Double {
        // Conversión local a metros usando proyección cartesiana aproximada
        val midLat = (a.latitude + b.latitude) / 2.0
        val cosLat = cos(Math.toRadians(midLat))

        val px = p.longitude * 111320.0 * cosLat
        val py = p.latitude * 110540.0

        val ax = a.longitude * 111320.0 * cosLat
        val ay = a.latitude * 110540.0

        val bx = b.longitude * 111320.0 * cosLat
        val by = b.latitude * 110540.0

        val abx = bx - ax
        val aby = by - ay
        val abLenSq = abx * abx + aby * aby

        if (abLenSq == 0.0) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }

        val apx = px - ax
        val apy = py - ay
        val t = ((apx * abx + apy * aby) / abLenSq).coerceIn(0.0, 1.0)

        val projected = LatLngPoint(
            latitude = a.latitude + t * (b.latitude - a.latitude),
            longitude = a.longitude + t * (b.longitude - a.longitude)
        )
        // Distancia geodésica al punto realmente proyectado; no se inventa un corredor.
        return MotorcycleRoutingEngine.calculateHaversineKm(
            p.latitude,
            p.longitude,
            projected.latitude,
            projected.longitude
        ) * 1000.0
    }
}
