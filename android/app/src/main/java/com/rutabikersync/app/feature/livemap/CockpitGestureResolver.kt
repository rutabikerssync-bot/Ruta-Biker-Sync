package com.rutabikersync.app.feature.livemap

/**
 * Paso 4 del plan maestro: decisión pura del gesto vertical de la cabina.
 *
 * Se mantiene fuera del Composable para poder verificarlo en JVM sin Android.
 * `dragYPx < 0` significa arrastre hacia arriba (abrir); `> 0` hacia abajo (cerrar).
 */
object CockpitGestureResolver {

    /** Umbral en píxeles; la pantalla convierte 24 dp a px antes de llamar a estos métodos. */
    fun shouldExpand(dragYPx: Float, thresholdPx: Float): Boolean = dragYPx < -thresholdPx

    /** Un arrastre hacia abajo suficiente oculta la botonera y despeja el mapa. */
    fun shouldCollapse(dragYPx: Float, thresholdPx: Float): Boolean = dragYPx > thresholdPx
}
