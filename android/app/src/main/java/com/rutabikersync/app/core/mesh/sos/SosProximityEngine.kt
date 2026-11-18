package com.rutabikersync.app.core.mesh.sos

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de Proximidad del Radar SOS en Radio de 5 km (Fase 7.1.5).
 * Realiza cálculos esféricos Haversine, cálculo azimutal de rumbo cardinal y generación háptica Morse SOS (... --- ...).
 */
object SosProximityEngine {

    const val DEFAULT_SOS_RADIUS_KM = 5.0

    /**
     * Patrón háptico oficial Morse SOS en milisegundos:
     * - 'S': tres pulsos cortos (150 ms encendido / 100 ms silencio)
     * - Espacio entre letras: 300 ms silencio
     * - 'O': tres pulsos largos (400 ms encendido / 100 ms silencio)
     * - Espacio entre letras: 300 ms silencio
     * - 'S': tres pulsos cortos (150 ms encendido / 100 ms silencio)
     */
    val SOS_MORSE_VIBRATION_PATTERN = longArrayOf(
        0,
        150, 100, 150, 100, 150, // S (... )
        300,                     // Pausa
        400, 100, 400, 100, 400, // O (--- )
        300,                     // Pausa
        150, 100, 150, 100, 150  // S (... )
    )

    /**
     * Calcula la distancia ortodrómica en kilómetros entre dos coordenadas usando la fórmula de Haversine.
     */
    fun calculateDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val rEarthKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return rEarthKm * c
    }

    /**
     * Calcula el rumbo cardinal (N, NE, E, SE, S, SO, O, NO) desde el punto de origen (rescatista) hacia el destino (SOS).
     */
    fun calculateBearing(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): String {
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        val normalized = ((deg % 360.0) + 360.0) % 360.0

        return when {
            normalized >= 337.5 || normalized < 22.5 -> "Norte ⬆"
            normalized >= 22.5 && normalized < 67.5 -> "Noreste ↗"
            normalized >= 67.5 && normalized < 112.5 -> "Este ➡"
            normalized >= 112.5 && normalized < 157.5 -> "Sureste ↘"
            normalized >= 157.5 && normalized < 202.5 -> "Sur ⬇"
            normalized >= 202.5 && normalized < 247.5 -> "Suroeste ↙"
            normalized >= 247.5 && normalized < 292.5 -> "Oeste ⬅"
            else -> "Noroeste ↖"
        }
    }

    /**
     * Determina si la distancia calculada se encuentra dentro del radio operativo de seguridad.
     */
    fun isWithinRadius(distanceKm: Double, radiusKm: Double = DEFAULT_SOS_RADIUS_KM): Boolean {
        return distanceKm in 0.0..radiusKm
    }

    /**
     * Formatea el texto canónico de alerta para el banner táctico de LiveMapScreen.
     * Cero exposición de coordenadas geográficas en texto plano para proteger la privacidad.
     */
    fun formatAlert(distanceKm: Double, bearing: String): String {
        val formattedDist = String.format(java.util.Locale.US, "%.1f", distanceKm)
        return "🚨 SOS BIKER CERCANO — A $formattedDist km al $bearing"
    }
}

/**
 * Modelo inmutable de Alerta SOS de Proximidad para la interfaz de cabina en LiveMapScreen.
 */
data class SosProximityAlert(
    val sosId: String,
    val userId: String,
    val emitterAlias: String,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double,
    val bearing: String,
    val hasMedicalSheet: Boolean,
    val notes: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
