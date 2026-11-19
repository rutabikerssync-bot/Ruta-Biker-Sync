package com.rutabikersync.app.feature.livemap

/**
 * Decisión pura de visibilidad del detalle del marcador del mapa.
 *
 * Por defecto el marcador muestra únicamente el avatar, liberando el corredor
 * de la ruta. El nombre, el avatar ampliado y el rol se revelan al tocar el
 * avatar (selección) o al pasar el ratón (hover).
 */
object RiderDetailVisibility {
    fun shouldShow(isSelected: Boolean, isHovered: Boolean): Boolean = isSelected || isHovered
}
