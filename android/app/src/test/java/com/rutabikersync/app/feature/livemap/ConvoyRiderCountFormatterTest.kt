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
    fun `sin fix ni peers validos informa cero sin inventar un rider`() {
        assertEquals("0 riders", ConvoyRiderCountFormatter.format(null))
        assertEquals("0 riders", ConvoyRiderCountFormatter.format(0))
        assertEquals("0 riders", ConvoyRiderCountFormatter.format(-4))
    }
}
