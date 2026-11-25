package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportEdgeMarkerTest {

    @Test
    fun `marcador fuera del viewport se proyecta en el borde con direccion`() {
        val width = 1080f
        val height = 2000f

        // Muy a la derecha y ligeramente arriba del centro
        val rightEdge = ViewportEdgeProjector.project(
            xPx = 2200f,
            yPx = 1000f,
            widthPx = width,
            heightPx = height,
            edgePaddingPx = 56f
        )
        assertNotNull(rightEdge)
        assertTrue(rightEdge!!.isOutsideViewport)
        assertEquals(width - 56f, rightEdge.xPx, 0.5f)
        assertEquals(1000f, rightEdge.yPx, 0.5f)
        assertEquals(90f, rightEdge.directionDegrees, 1f)

        // Muy arriba del viewport
        val topEdge = ViewportEdgeProjector.project(
            xPx = 540f,
            yPx = -500f,
            widthPx = width,
            heightPx = height,
            edgePaddingPx = 56f
        )
        assertNotNull(topEdge)
        assertTrue(topEdge!!.isOutsideViewport)
        assertEquals(540f, topEdge.xPx, 0.5f)
        assertEquals(56f, topEdge.yPx, 0.5f)
        assertEquals(0f, topEdge.directionDegrees, 1f)
    }

    @Test
    fun `marcador dentro del viewport conserva su posicion sin fijarse al borde`() {
        val inside = ViewportEdgeProjector.project(
            xPx = 400f,
            yPx = 800f,
            widthPx = 1080f,
            heightPx = 2000f
        )
        assertNotNull(inside)
        assertFalse(inside!!.isOutsideViewport)
        assertEquals(400f, inside.xPx, 0.01f)
        assertEquals(800f, inside.yPx, 0.01f)
    }
}
