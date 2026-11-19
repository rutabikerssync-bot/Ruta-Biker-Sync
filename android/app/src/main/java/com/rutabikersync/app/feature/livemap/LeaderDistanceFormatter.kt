package com.rutabikersync.app.feature.livemap

import java.util.Locale

/**
 * Formateo único de la distancia al líder, puro y verificable en JVM.
 *
 * Reglas:
 * - distancia desconocida, sin fix GPS, líder local o distancia no positiva → `null`
 *   (la UI oculta el campo en lugar de mostrar un valor engañoso);
 * - 1..999 m → `"320 m"`;
 * - >= 1000 m → `"3.5 km"` con `Locale.US` para evitar comas decimales dependientes
 *   del locale del dispositivo.
 *
 * No inventa datos: sólo presenta la distancia ya validada por `LiveMapController`.
 */
object LeaderDistanceFormatter {

    fun format(meters: Int?, isLeader: Boolean = false, hasGpsFix: Boolean = true): String? {
        val distance = meters ?: return null
        if (isLeader || !hasGpsFix || !isPlausible(distance)) return null
        return when {
            distance < 1_000 -> "$distance m"
            else -> String.format(Locale.US, "%.1f km", distance / 1_000.0)
        }
    }

    /** Distancia máxima considerada plausible para un convoy (1000 km). */
    const val MAX_PLAUSIBLE_DISTANCE_METERS: Int = 1_000_000

    fun isPlausible(meters: Int?): Boolean {
        if (meters == null) return false
        return meters > 0 && meters <= MAX_PLAUSIBLE_DISTANCE_METERS
    }
}
