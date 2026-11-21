package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderViewportLayoutTest {

    @Test
    fun `centro realmente proyectado cercano se conserva y un punto fuera no se pega al borde`() {
        val visible = RiderViewportLayout.isPointVisible(xPx = 360f, yPx = 400f, widthPx = 720f, heightPx = 1_000f)
        val outsideTwoKmAway = RiderViewportLayout.isPointVisible(xPx = 2_700f, yPx = -1_200f, widthPx = 720f, heightPx = 1_000f)

        assertTrue(visible)
        assertFalse(outsideTwoKmAway)
    }

    @Test
    fun `un píxel parcialmente visible sigue siendo accesible sin fabricar coordenadas`() {
        assertTrue(RiderViewportLayout.isPointVisible(xPx = -20f, yPx = 200f, widthPx = 720f, heightPx = 1_000f))
        assertFalse(RiderViewportLayout.isPointVisible(xPx = Float.NaN, yPx = 200f, widthPx = 720f, heightPx = 1_000f))
    }
}
