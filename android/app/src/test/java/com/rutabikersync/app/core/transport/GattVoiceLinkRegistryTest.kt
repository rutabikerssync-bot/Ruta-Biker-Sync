package com.rutabikersync.app.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0.2: política pura de enlaces GATT, allowlist, roles y fan-out con flag de rollback. */
class GattVoiceLinkRegistryTest {

    @Test
    fun `rol determinista por shortId sin depender del orden de descubrimiento`() {
        assertEquals(GattVoiceRole.SERVER, GattVoiceLinkRegistry(10u, true).roleFor(20u))
        assertEquals(GattVoiceRole.CLIENT, GattVoiceLinkRegistry(20u, true).roleFor(10u))
    }

    @Test
    fun `allowlist rechaza nodo local y peers fuera del manifiesto`() {
        val registry = GattVoiceLinkRegistry(1u, true)
        registry.setAllowedPeers(setOf(2u, 3u))

        assertFalse(registry.accept(1u, "AA:01"))
        assertFalse(registry.accept(9u, "AA:09"))
        assertTrue(registry.accept(2u, "AA:02"))
        assertEquals("AA:02", registry.addressFor(2u))
    }

    @Test
    fun `multipeer false conserva un solo enlace y elige el primero listo`() {
        val registry = GattVoiceLinkRegistry(1u, false)
        registry.setAllowedPeers(setOf(2u, 3u, 4u))

        assertTrue(registry.accept(3u, "AA:03"))
        assertFalse("El flag de rollback no debe abrir un segundo enlace", registry.accept(2u, "AA:02"))
        assertNull(registry.addressFor(2u))

        // Aunque el engine marque varios como listos, el rollback envía solo al primero.
        assertEquals(listOf<UShort>(3u), registry.sendTargets(setOf(2u, 3u)))
    }

    @Test
    fun `multipeer true acepta todos los peers y hace fan-out de destinos listos`() {
        val registry = GattVoiceLinkRegistry(1u, true)
        registry.setAllowedPeers(setOf(2u, 3u, 4u))

        assertTrue(registry.accept(3u, "AA:03"))
        assertTrue(registry.accept(2u, "AA:02"))
        assertTrue(registry.accept(4u, "AA:04"))

        assertEquals(setOf("AA:02", "AA:03", "AA:04"), registry.snapshot().values.toSet())
        // Orden determinista para que la prueba sea estable.
        assertEquals(listOf<UShort>(2u, 3u), registry.sendTargets(setOf(3u, 2u)))
    }

    @Test
    fun `cambio de manifiesto purga enlaces que ya no estan autorizados`() {
        val registry = GattVoiceLinkRegistry(1u, true)
        registry.setAllowedPeers(setOf(2u, 3u))
        assertTrue(registry.accept(2u, "AA:02"))
        assertTrue(registry.accept(3u, "AA:03"))

        registry.setAllowedPeers(setOf(3u, 4u))
        assertNull(registry.addressFor(2u))
        assertEquals("AA:03", registry.addressFor(3u))
        assertEquals(listOf<UShort>(3u), registry.sendTargets(setOf(2u, 3u)))
    }
}
