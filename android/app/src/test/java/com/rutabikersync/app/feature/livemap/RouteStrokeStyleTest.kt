package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests JVM del trazado reforzado de la ruta (halo + línea, extremos redondos). */
class RouteStrokeStyleTest {

    @Test
    fun `halo oscuro semitransparente mas ancho que la linea`() {
        assertEquals("#3A1E14", RouteStrokeStyle.HALO_COLOR)
        assertEquals(0.5f, RouteStrokeStyle.HALO_OPACITY)
        assertEquals(13f, RouteStrokeStyle.HALO_WIDTH)
        assertEquals("#F08A45", RouteStrokeStyle.LINE_COLOR)
        assertEquals(1.0f, RouteStrokeStyle.LINE_OPACITY)
        assertEquals(6f, RouteStrokeStyle.LINE_WIDTH)
        assert(RouteStrokeStyle.HALO_WIDTH > RouteStrokeStyle.LINE_WIDTH)
    }

    @Test
    fun `extremos y uniones redondos en ambas capas`() {
        assertEquals("round", RouteStrokeStyle.CAP_AND_JOIN)
    }
}
