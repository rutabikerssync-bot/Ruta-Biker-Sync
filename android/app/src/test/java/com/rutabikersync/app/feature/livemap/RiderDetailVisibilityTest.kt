package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests JVM de la regla «sólo avatar por defecto; detalle al tocar o hacer hover». */
class RiderDetailVisibilityTest {

    @Test
    fun `sin seleccion ni hover el detalle queda oculto`() {
        assertFalse(RiderDetailVisibility.shouldShow(isSelected = false, isHovered = false))
    }

    @Test
    fun `al tocar el avatar se muestra el detalle`() {
        assertTrue(RiderDetailVisibility.shouldShow(isSelected = true, isHovered = false))
    }

    @Test
    fun `al entrar con el raton se muestra el detalle y al salir se oculta`() {
        assertTrue(RiderDetailVisibility.shouldShow(isSelected = false, isHovered = true))
        assertFalse(RiderDetailVisibility.shouldShow(isSelected = false, isHovered = false))
    }

    @Test
    fun `seleccion y hover simultaneos mantienen el detalle visible`() {
        assertTrue(RiderDetailVisibility.shouldShow(isSelected = true, isHovered = true))
    }
}
