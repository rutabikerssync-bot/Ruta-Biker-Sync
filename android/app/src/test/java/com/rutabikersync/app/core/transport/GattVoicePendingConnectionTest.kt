package com.rutabikersync.app.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattVoicePendingConnectionTest {

    @Test
    fun `conexion pendiente usa MTU por defecto y sin suscripcion`() {
        val pending = GattVoicePendingConnections()
        pending.record("AA:BB")

        val captured = pending.take("AA:BB")

        assertEquals(GattVoiceFramer.DEFAULT_MTU, captured?.mtu)
        assertFalse(captured?.notificationEnabled == true)
        assertNull(pending.take("AA:BB"))
    }

    @Test
    fun `MTU y CCCD se capturan antes de resolver el enlace`() {
        val pending = GattVoicePendingConnections()
        pending.onMtuChanged("AA:BB", 517)
        pending.onNotificationEnabled("AA:BB")

        val captured = pending.take("AA:BB")

        assertEquals(517, captured?.mtu)
        assertTrue(captured?.notificationEnabled == true)
    }

    @Test
    fun `snapshot no expone el estado mutable y remove limpia la entrada`() {
        val pending = GattVoicePendingConnections()
        pending.onMtuChanged("AA:BB", 517)
        val snapshot = pending.snapshot().getValue("AA:BB")

        assertEquals(517, snapshot.mtu)
        pending.remove("AA:BB")
        assertEquals(517, snapshot.mtu)
        assertNull(pending.take("AA:BB"))
    }
}
