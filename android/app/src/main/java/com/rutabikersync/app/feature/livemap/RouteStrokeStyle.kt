package com.rutabikersync.app.feature.livemap

/**
 * Configuración pura del trazado de ruta compartido entre la creación de ruta
 * (`ScheduleRideMapScreen`) y la cabina activa (`LiveMapScreen`).
 *
 * Se aplica como capas locales de MapLibre (sin tocar el JSON de estilo base):
 * halo azul marino semitransparente + línea azul vibrante (#2563EB) de 6.5 px.
 */
object RouteStrokeStyle {
    const val SOURCE_ID = "rbs-live-route-source"
    const val HALO_LAYER_ID = "rbs-live-route-halo"
    const val LINE_LAYER_ID = "rbs-live-route-line"

    const val HALO_COLOR = "#1E3A8A"
    const val LINE_COLOR = "#2563EB"
    const val HALO_WIDTH = 12f
    const val LINE_WIDTH = 6.5f
    const val HALO_OPACITY = 0.45f
    const val LINE_OPACITY = 1.0f
    const val CAP_AND_JOIN = "round"

    const val ALT_LINE_COLOR_PRIMARY = "#94A3B8"
    const val ALT_LINE_COLOR_SECONDARY = "#A78BFA"
    const val ALT_LINE_WIDTH = 4.5f
}
