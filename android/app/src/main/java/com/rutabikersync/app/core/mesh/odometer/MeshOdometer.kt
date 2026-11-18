package com.rutabikersync.app.core.mesh.odometer

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Odómetro de Malla Off-Grid (Fase 6 · Hito 5 · Etapa 3).
 *
 * Principios y Reglas de Negocio:
 * 1. **Condición de Convoy Activo:** Acumula distancia recorrida **únicamente** si se ha recibido
 *    un heartbeat de otro nodo del convoy dentro de los últimos [heartbeatTimeoutMs] (10 segundos por defecto).
 * 2. **Congelamiento por Aislamiento:** Si el piloto queda aislado (sin heartbeats de compañeros durante >10s),
 *    el odómetro se congela automáticamente ([isFrozen] = true) y no acumula kilometraje.
 * 3. **Reanudación sin Trampa (Anti-Cheat):** Al reencontrarse con el convoy, el odómetro se descongela
 *    y reanuda la acumulación a partir de la nueva posición. La distancia recorrida mientras estuvo aislado
 *    **nunca** se suma de forma retroactiva.
 * 4. **Parada en Convoy (Semáforos / Descansos):** Si el convoy se detiene (p. ej. en un semáforo durante 2 minutos),
 *    los heartbeats continúan llegando pero la distancia física recorrida es 0 m (o menor al umbral de ruido GPS
 *    [minMovementThresholdMeters]), por lo que se acumulan exactamente 0 km sin distorsión.
 * 5. **Privacidad Absoluta:** Este componente calcula exclusivamente la distancia escalar en metros/kilómetros.
 *    **Jamás** almacena ni transmite coordenadas geográficas (latitud/longitud) hacia el backend o servicios externos.
 */
class MeshOdometer(
    val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    val minMovementThresholdMeters: Double = DEFAULT_MIN_MOVEMENT_METERS
) {

    private val lock = Any()

    @Volatile
    private var lastGpsLat: Double? = null

    @Volatile
    private var lastGpsLon: Double? = null

    @Volatile
    private var lastPeerHeartbeatEpochMs: Long = 0L

    @Volatile
    private var _totalAccumulatedMeters: Double = 0.0

    @Volatile
    private var _isFrozen: Boolean = true

    @Volatile
    var isolatedCount: Int = 0
        private set

    @Volatile
    var reconnectCount: Int = 0
        private set

    /**
     * Proveedor externo opcional para verificar si la malla tiene peers activos
     * (p. ej. consultando directamente [com.rutabikersync.app.core.mesh.MeshRelayEngine.hasActivePeerWithin]).
     */
    var peerHeartbeatChecker: ((nowMs: Long) -> Boolean)? = null

    /**
     * Distancia total acumulada en metros con presencia de malla confirmada.
     */
    val totalAccumulatedMeters: Double
        get() = _totalAccumulatedMeters

    /**
     * Distancia total acumulada en kilómetros.
     */
    val totalAccumulatedKm: Double
        get() = _totalAccumulatedMeters / 1000.0

    /**
     * Indica si el odómetro se encuentra actualmente congelado debido a la ausencia de otros nodos en la malla.
     */
    val isFrozen: Boolean
        get() = _isFrozen

    /**
     * Notifica al odómetro la recepción de un heartbeat emitido por un compañero ([peerShortId]).
     * Actualiza la ventana de presencia y descongela el odómetro si estaba aislado.
     */
    fun onPeerHeartbeatReceived(
        peerShortId: UShort,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            lastPeerHeartbeatEpochMs = nowMs
            if (_isFrozen) {
                _isFrozen = false
                reconnectCount++
            }
        }
    }

    /**
     * Evalúa si el convoy se encuentra actualmente activo según la ventana de tiempo transcurrida
     * desde el último heartbeat recibido de un peer.
     */
    fun isConvoyActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val checker = peerHeartbeatChecker
        if (checker != null) {
            return checker.invoke(nowMs)
        }
        val elapsed = (nowMs - lastPeerHeartbeatEpochMs).coerceAtLeast(0L)
        return lastPeerHeartbeatEpochMs > 0L && elapsed <= heartbeatTimeoutMs
    }

    /**
     * Procesa una actualización de coordenadas GPS locales.
     *
     * @param latitude Latitud actual en grados decimales
     * @param longitude Longitud actual en grados decimales
     * @param nowMs Marca temporal actual en milisegundos
     * @return Metros acumulados en este paso (0.0 si el nodo está aislado, si está quieto o si es la primera lectura)
     */
    fun onLocationUpdate(
        latitude: Double,
        longitude: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        synchronized(lock) {
            val convoyActive = isConvoyActive(nowMs)

            // Caso A: Piloto aislado (sin presencia de otros nodos en los últimos 10 s)
            if (!convoyActive) {
                if (!_isFrozen) {
                    _isFrozen = true
                    isolatedCount++
                }
                // Actualizar la última posición conocida sin acumular distancia (evita salto artificial al reconectar)
                lastGpsLat = latitude
                lastGpsLon = longitude
                return 0.0
            }

            // Caso B: Convoy activo (presencia confirmada de malla)
            if (_isFrozen) {
                _isFrozen = false
                reconnectCount++
            }

            val prevLat = lastGpsLat
            val prevLon = lastGpsLon

            // Primer punto de lectura GPS dentro de la rodada
            if (prevLat == null || prevLon == null) {
                lastGpsLat = latitude
                lastGpsLon = longitude
                return 0.0
            }

            // Calcular distancia geodésica mediante fórmula del Semiverseno (Haversine)
            val distanceMeters = haversineDistanceMeters(prevLat, prevLon, latitude, longitude)

            // Caso C: Parada en semáforo / descanso en grupo o jitter de ruido GPS (< 3 metros)
            if (distanceMeters < minMovementThresholdMeters) {
                // No actualizamos prevLat/prevLon con ruido insignificante, se mantiene anclado
                return 0.0
            }

            // Caso D: Movimiento real verificado en convoy
            _totalAccumulatedMeters += distanceMeters
            lastGpsLat = latitude
            lastGpsLon = longitude
            return distanceMeters
        }
    }

    /**
     * Reinicia las métricas del odómetro a cero.
     */
    fun reset() {
        synchronized(lock) {
            lastGpsLat = null
            lastGpsLon = null
            lastPeerHeartbeatEpochMs = 0L
            _totalAccumulatedMeters = 0.0
            _isFrozen = true
            isolatedCount = 0
            reconnectCount = 0
        }
    }

    companion object {
        /** Ventana máxima de tolerancia sin heartbeats antes de congelar el odómetro (10 segundos) */
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS: Long = 10_000L

        /** Umbral mínimo de movimiento para descartar ruido GPS o paradas en semáforos (3 metros) */
        const val DEFAULT_MIN_MOVEMENT_METERS: Double = 3.0

        /**
         * Calcula la distancia sobre el elipsoide terrestre entre dos coordenadas geográficas
         * utilizando la fórmula de Haversine.
         */
        fun haversineDistanceMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val r = 6371000.0 // Radio medio de la Tierra en metros
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0).let { it * it } +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2.0).let { it * it }
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return r * c
        }
    }
}
