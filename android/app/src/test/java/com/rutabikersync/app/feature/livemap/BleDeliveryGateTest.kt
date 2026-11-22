package com.rutabikersync.app.feature.livemap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corte 2A: con el radio BLE apagado, el despacho BLE no debe ni intentarse;
 * la compuerta fuerza así el fallback HTTP sin depender del RelayEngine.
 */
class BleDeliveryGateTest {

    @Test
    fun `radio apagado no invoca BLE y fuerza fallback`() {
        var dispatchInvoked = false
        val delivered = tryBleDelivery(false) {
            dispatchInvoked = true
            true
        }

        assertFalse(delivered)
        assertFalse(dispatchInvoked)
    }

    @Test
    fun `radio encendido respeta el resultado del despacho BLE`() {
        assertTrue(tryBleDelivery(true) { true })
        assertFalse(tryBleDelivery(true) { false })
    }

    @Test
    fun `variante suspendida tambien corta el despacho con radio apagado`() = runBlocking {
        var dispatchInvoked = false
        val delivered = tryBleSuspendDelivery(false) {
            dispatchInvoked = true
            true
        }

        assertFalse(delivered)
        assertFalse(dispatchInvoked)
    }
}
