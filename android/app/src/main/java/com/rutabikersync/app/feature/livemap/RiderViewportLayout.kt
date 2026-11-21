package com.rutabikersync.app.feature.livemap

/** Reglas puras de visibilidad: un píxel fuera de cámara nunca se fija artificialmente a un borde. */
object RiderViewportLayout {
    private const val PARTIAL_VISIBILITY_MARGIN_PX = 48f

    fun isPointVisible(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean {
        if (!xPx.isFinite() || !yPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return false
        return xPx in -PARTIAL_VISIBILITY_MARGIN_PX..(widthPx + PARTIAL_VISIBILITY_MARGIN_PX) &&
            yPx in -PARTIAL_VISIBILITY_MARGIN_PX..(heightPx + PARTIAL_VISIBILITY_MARGIN_PX)
    }
}
