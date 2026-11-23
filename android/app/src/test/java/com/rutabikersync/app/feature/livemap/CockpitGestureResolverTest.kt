package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paso 4: el gesto de la cabina debe ser deliberado y no dispararse con jitter. */
class CockpitGestureResolverTest {

    private val umbral = 48f

    @Test
    fun `arrastre arriba suficiente abre la cabina`() {
        assertTrue(CockpitGestureResolver.shouldExpand(-49f, umbral))
        assertFalse(CockpitGestureResolver.shouldCollapse(-49f, umbral))
    }

    @Test
    fun `arrastre abajo suficiente cierra la cabina`() {
        assertTrue(CockpitGestureResolver.shouldCollapse(49f, umbral))
        assertFalse(CockpitGestureResolver.shouldExpand(49f, umbral))
    }

    @Test
    fun `arrastre corto o en el umbral no cambia el estado`() {
        assertFalse(CockpitGestureResolver.shouldExpand(-48f, umbral))
        assertFalse(CockpitGestureResolver.shouldCollapse(48f, umbral))
        assertFalse(CockpitGestureResolver.shouldExpand(-12f, umbral))
        assertFalse(CockpitGestureResolver.shouldCollapse(12f, umbral))
        assertFalse(CockpitGestureResolver.shouldExpand(0f, umbral))
        assertFalse(CockpitGestureResolver.shouldCollapse(0f, umbral))
    }
}
