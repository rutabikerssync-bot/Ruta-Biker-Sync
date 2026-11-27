package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.power.PowerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * FASE 3.1: política JVM pura de tope de enlaces GATT cliente (8/4/2). Sin Android, sin
 * Robolectric y sin peers inventados: todas las entradas son shortIds del manifiesto autorizado.
 */
class GattPeerBudgetPolicyTest {

    private val ninePeers = (1..9).map { it.toUShort() }.toSet()

    @Test
    fun `los perfiles definen 8 4 2 objetivos Gatt y no alteran intervalos`() {
        assertEquals(8, PowerProfile.NORMAL.maxGattPeerLinks)
        assertEquals(4, PowerProfile.POWER_SAVER.maxGattPeerLinks)
        assertEquals(2, PowerProfile.SURVIVAL.maxGattPeerLinks)

        assertEquals(3_000L, PowerProfile.NORMAL.heartbeatIntervalMs)
        assertEquals(5_000L, PowerProfile.POWER_SAVER.heartbeatIntervalMs)
        assertEquals(10_000L, PowerProfile.SURVIVAL.heartbeatIntervalMs)
    }

    @Test
    fun `NORMAL recorta nueve peers autorizados a los ocho menores`() {
        val capped = GattPeerBudgetPolicy.cap(ninePeers, PowerProfile.NORMAL)

        assertEquals((1..8).map { it.toUShort() }.toSet(), capped)
        assertEquals(8, capped.size)
    }

    @Test
    fun `POWER_SAVER recorta nueve peers autorizados a los cuatro menores`() {
        val capped = GattPeerBudgetPolicy.cap(ninePeers, PowerProfile.POWER_SAVER)

        assertEquals((1..4).map { it.toUShort() }.toSet(), capped)
        assertEquals(4, capped.size)
    }

    @Test
    fun `SURVIVAL recorta nueve peers autorizados a los dos menores`() {
        val capped = GattPeerBudgetPolicy.cap(ninePeers, PowerProfile.SURVIVAL)

        assertEquals(setOf<UShort>(1u, 2u), capped)
        assertEquals(2, capped.size)
    }

    @Test
    fun `excluye el shortId local aun cuando cae dentro del tope`() {
        val withSelf = (1..9).map { it.toUShort() }.toSet() + 100.toUShort()

        val cappedWithHighSelf = GattPeerBudgetPolicy.cap(withSelf, PowerProfile.NORMAL, localShortId = 100u)
        assertEquals((1..8).map { it.toUShort() }.toSet(), cappedWithHighSelf)
        assertFalse(cappedWithHighSelf.contains(100u))

        // Si el propio nodo es el menor, el tope se cubre con los 8 siguientes: no se inventa un peer.
        val cappedWithLowSelf = GattPeerBudgetPolicy.cap(ninePeers, PowerProfile.NORMAL, localShortId = 1u)
        assertEquals((2..9).map { it.toUShort() }.toSet(), cappedWithLowSelf)
        assertFalse(cappedWithLowSelf.contains(1u))
    }

    @Test
    fun `deduplica shortIds repetidos del manifiesto`() {
        val duplicated = listOf<UShort>(9u, 2u, 5u, 2u, 9u, 5u, 7u)

        val capped = GattPeerBudgetPolicy.cap(duplicated, PowerProfile.NORMAL)

        assertEquals(setOf<UShort>(2u, 5u, 7u, 9u), capped)
        assertEquals(4, capped.size)
    }

    @Test
    fun `el orden ascendente es estable y no depende del orden de entrada`() {
        val shuffled = listOf<UShort>(9u, 1u, 7u, 3u, 5u)

        val capped = GattPeerBudgetPolicy.cap(shuffled, PowerProfile.NORMAL)
        val cappedReversed = GattPeerBudgetPolicy.cap(shuffled.asReversed(), PowerProfile.NORMAL)

        // Criterio determinista: ascending por shortId; NO cercanía, GPS ni RSSI.
        assertEquals(listOf<UShort>(1u, 3u, 5u, 7u, 9u), capped.toList())
        assertEquals(capped, cappedReversed)
        assertEquals(capped.toList(), cappedReversed.toList())
    }

    @Test
    fun `un conjunto dentro del limite no cambia`() {
        val withinLimit = setOf<UShort>(6u, 2u, 4u)

        val capped = GattPeerBudgetPolicy.cap(withinLimit, PowerProfile.NORMAL)

        assertEquals(withinLimit, capped)
        assertEquals(listOf<UShort>(2u, 4u, 6u), capped.toList())
    }
}
