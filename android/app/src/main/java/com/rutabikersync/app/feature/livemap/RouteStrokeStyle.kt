package com.rutabikersync.app.feature.livemap

/**
 * Configuración pura del trazado reforzado de la ruta en el mapa.
 *
 * Se aplica como capas locales de MapLibre (sin tocar el JSON de estilo base):
 * un halo oscuro semitransparente y una línea brillante sobre la misma geometría.
 */
object RouteStrokeStyle {
    const val SOURCE_ID = "rbs-live-route-source"
    const val HALO_LAYER_ID = "rbs-live-route-halo"
    const val LINE_LAYER_ID = "rbs-live-route-line"

    const val HALO_COLOR = "#3A1E14"
    const val LINE_COLOR = "#F08A45"
    const val HALO_WIDTH = 13f
    const val LINE_WIDTH = 6f
    const val HALO_OPACITY = 0.5f
    const val LINE_OPACITY = 1.0f
    const val CAP_AND_JOIN = "round"
}
