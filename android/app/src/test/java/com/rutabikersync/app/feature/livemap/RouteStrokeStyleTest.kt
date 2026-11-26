package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStrokeStyleTest {

    @Test
    fun `trazo activo usa el estilo azul unificado con halo y extremos redondeados`() {
        assertEquals("#1E3A8A", RouteStrokeStyle.HALO_COLOR)
        assertEquals(0.45f, RouteStrokeStyle.HALO_OPACITY)
        assertEquals(12f, RouteStrokeStyle.HALO_WIDTH)
        assertEquals("#2563EB", RouteStrokeStyle.LINE_COLOR)
        assertEquals(1.0f, RouteStrokeStyle.LINE_OPACITY)
        assertEquals(6.5f, RouteStrokeStyle.LINE_WIDTH)
        assertTrue(RouteStrokeStyle.HALO_WIDTH > RouteStrokeStyle.LINE_WIDTH)
        assertEquals("round", RouteStrokeStyle.CAP_AND_JOIN)
        assertEquals("#94A3B8", RouteStrokeStyle.ALT_LINE_COLOR_PRIMARY)
        assertEquals("#A78BFA", RouteStrokeStyle.ALT_LINE_COLOR_SECONDARY)
        assertEquals(4.5f, RouteStrokeStyle.ALT_LINE_WIDTH)
    }
}
