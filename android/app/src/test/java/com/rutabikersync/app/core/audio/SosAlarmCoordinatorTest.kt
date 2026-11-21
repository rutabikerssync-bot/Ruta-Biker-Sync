package com.rutabikersync.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAlarmCoordinatorTest {
    private class FakePlayer : SosTonePlayer {
        override var isPlaying = false
            private set
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
            isPlaying = true
        }

        override fun stop() {
            stops++
            isPlaying = false
        }
    }

    private val incidentA = SosIncidentKey(7u, 101u)
    private val incidentB = SosIncidentKey(8u, 201u)

    @Test
    fun `SOS remoto e incidente local arrancan una sola alarma sin duplicar por flooding`() {
        val player = FakePlayer()
        val coordinator = SosAlarmCoordinator(player)

        assertTrue(coordinator.onRemoteSos(incidentA))
        assertFalse(coordinator.onRemoteSos(incidentA))
        coordinator.setLocalActive(true)
        assertEquals(1, player.starts)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `ACK solo cierra su incidente y un SOS viejo confirmado no vuelve a sonar`() {
        val player = FakePlayer()
        val coordinator = SosAlarmCoordinator(player)
        coordinator.onRemoteSos(incidentA)
        coordinator.onRemoteSos(incidentB)

        coordinator.onAck(incidentA)
        assertTrue("El segundo incidente sigue activo", player.isPlaying)
        assertFalse(coordinator.onRemoteSos(incidentA))

        coordinator.onAck(incidentB)
        assertFalse(player.isPlaying)
        assertEquals(1, player.starts)
    }

    @Test
    fun `PTT silencia durante pulso largo o pausa y retoma solo un SOS vigente`() {
        val player = FakePlayer()
        val coordinator = SosAlarmCoordinator(player)
        coordinator.onRemoteSos(incidentA)
        assertEquals(1, player.starts)

        coordinator.setPttActive("hardware-headset", true)
        assertFalse(player.isPlaying)
        assertTrue("PTT no cancela la emergencia", coordinator.hasActiveIncident)

        coordinator.setPttActive("hardware-headset", false)
        assertTrue(player.isPlaying)
        assertEquals(2, player.starts)

        coordinator.onAck(incidentA)
        assertFalse(coordinator.hasActiveIncident)
        coordinator.setPttActive("software-cabina", true)
        coordinator.setPttActive("software-cabina", false)
        assertFalse("ACK no se reabre al terminar PTT", player.isPlaying)
    }

    @Test
    fun `foco perdido se silencia y si se recupera sigue sonando la alarma vigente`() {
        val player = FakePlayer()
        val coordinator = SosAlarmCoordinator(player)
        coordinator.onRemoteSos(incidentA)

        coordinator.setFocusAvailable(false)
        assertFalse(player.isPlaying)
        coordinator.setFocusAvailable(true)
        assertTrue(player.isPlaying)
        assertEquals(2, player.starts)
    }

    @Test
    fun `primer toque de parada no equivale a STOPPED`() {
        val player = FakePlayer()
        val coordinator = SosAlarmCoordinator(player)
        coordinator.setLocalActive(true)

        // RideService conserva isSosActive durante CONFIRMING_STOP: no debe apagarse aquí.
        coordinator.setLocalActive(true)
        assertTrue(player.isPlaying)

        coordinator.stopAll()
        assertFalse(player.isPlaying)
        assertFalse(coordinator.hasActiveIncident)
    }
}
