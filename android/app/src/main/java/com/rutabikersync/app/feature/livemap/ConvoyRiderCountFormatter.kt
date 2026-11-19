package com.rutabikersync.app.feature.livemap

/**
 * Formateo único del contador de riders del HUD de cabina, puro y verificable en JVM.
 *
 * El conteo ya viene validado desde los marcadores del mapa (uno por rider activo,
 * incluido el local). Esta función sólo evita el plural incorrecto cuando hay uno
 * y garantiza el fallback honesto de "solo local" si no llega ningún dato.
 */
object ConvoyRiderCountFormatter {

    fun format(ridersCount: Int?): String {
        val count = (ridersCount ?: 0).coerceAtLeast(1)
        return if (count == 1) "1 rider" else "$count riders"
    }
}
