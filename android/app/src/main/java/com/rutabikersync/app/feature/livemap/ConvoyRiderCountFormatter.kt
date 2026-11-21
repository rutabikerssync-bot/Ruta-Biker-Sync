package com.rutabikersync.app.feature.livemap

/**
 * Formateo único del contador de riders del HUD de cabina, puro y verificable en JVM.
 *
 * El conteo ya viene validado desde los marcadores del mapa (uno por rider activo,
 * incluido el local). Esta función sólo evita el plural incorrecto cuando hay uno.
 * Sin fix local ni peers válidos informa 0 riders; no inventa un piloto local ausente.
 */
object ConvoyRiderCountFormatter {

    fun format(ridersCount: Int?): String {
        val count = (ridersCount ?: 0).coerceAtLeast(0)
        return if (count == 1) "1 rider" else "$count riders"
    }
}
