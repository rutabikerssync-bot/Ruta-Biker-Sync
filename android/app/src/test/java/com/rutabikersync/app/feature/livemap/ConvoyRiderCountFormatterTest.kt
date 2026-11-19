package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests JVM del formato del contador de convoy del HUD (fallback y singular). */
class ConvoyRiderCountFormatterTest {

    @Test
    fun `un rider usa singular`() {
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(1))
    }

    @Test
    fun `varios riders usan plural`() {
        assertEquals("2 riders", ConvoyRiderCountFormatter.format(2))
        assertEquals("5 riders", ConvoyRiderCountFormatter.format(5))
    }

    @Test
    fun `sin dato o valor invalido cae al fallback de un rider`() {
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(null))
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(0))
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(-4))
    }
}
