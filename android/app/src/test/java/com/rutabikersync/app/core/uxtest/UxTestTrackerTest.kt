package com.rutabikersync.app.core.uxtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UxTestTrackerTest {

    @Before
    fun setUp() {
        UxTestTracker.setParticipant("P1")
        UxTestTracker.setLeaderShortId(1u)
        UxTestTracker.setSosMeshBroadcastEnabled(false)
    }

    @Test
    fun `participantToShortId asigna IDs unicos sin colisiones entre P1 a P6 y P0`() {
        val participants = listOf("P1", "P2", "P3", "P4", "P5", "P6", "P0")
        val ids = participants.map { UxTestTracker.participantToShortId(it) }

        // Verificación de asignación exacta
        assertEquals(1u.toUShort(), UxTestTracker.participantToShortId("P1"))
        assertEquals(2u.toUShort(), UxTestTracker.participantToShortId("P2"))
        assertEquals(3u.toUShort(), UxTestTracker.participantToShortId("P3"))
        assertEquals(4u.toUShort(), UxTestTracker.participantToShortId("P4"))
        assertEquals(5u.toUShort(), UxTestTracker.participantToShortId("P5"))
        assertEquals(6u.toUShort(), UxTestTracker.participantToShortId("P6"))
        assertEquals(99u.toUShort(), UxTestTracker.participantToShortId("P0"))

        // Unicidad matemática: sin duplicados
        assertEquals("Cada teléfono debe tener un ID único en la malla", ids.size, ids.toSet().size)
    }

    @Test
    fun `leaderShortId por defecto es 1 y el moderador puede reasignarlo`() {
        assertEquals(1u.toUShort(), UxTestTracker.leaderShortId.value)

        UxTestTracker.setLeaderShortId(2u)
        assertEquals(2u.toUShort(), UxTestTracker.leaderShortId.value)

        val lastEvent = UxTestTracker.getRecordedEvents().lastOrNull { it.action == "SET_CONVOY_LEADER" }
        assertTrue("Debe registrarse evento SET_CONVOY_LEADER", lastEvent != null)
        assertEquals("shortId_2", lastEvent?.target)
    }

    @Test
    fun `isConvoyLeader es verdadero solo cuando localShortId coincide con leaderShortId`() {
        UxTestTracker.setLeaderShortId(1u)

        // P1 es líder si leaderShortId es 1
        UxTestTracker.setParticipant("P1")
        assertTrue("P1 debe ser líder cuando leaderShortId es 1", UxTestTracker.isConvoyLeader)

        // P2 es miembro si leaderShortId es 1
        UxTestTracker.setParticipant("P2")
        assertFalse("P2 debe ser miembro cuando leaderShortId es 1", UxTestTracker.isConvoyLeader)

        // Si el moderador cambia el líder a 2, P2 se convierte en líder
        UxTestTracker.setLeaderShortId(2u)
        assertTrue("P2 debe ser líder cuando leaderShortId es 2", UxTestTracker.isConvoyLeader)

        // P1 pasa a ser miembro
        UxTestTracker.setParticipant("P1")
        assertFalse("P1 debe ser miembro cuando leaderShortId es 2", UxTestTracker.isConvoyLeader)
    }

    @Test
    fun `logEvent registra TAP_SOS asociado a T9 y respeta detalle de broadcast`() {
        UxTestTracker.logEvent(
            screen = "LIVE_MAP",
            action = "TAP_SOS",
            target = "btn_sos_prueba",
            details = "task=T9,broadcast=false"
        )

        val lastSos = UxTestTracker.getRecordedEvents().lastOrNull { it.action == "TAP_SOS" }
        assertTrue("Debe existir evento TAP_SOS registrado", lastSos != null)
        assertEquals("LIVE_MAP", lastSos?.screen)
        assertEquals("btn_sos_prueba", lastSos?.target)
        assertTrue("El detalle debe indicar asociación con T9", lastSos?.details?.contains("task=T9") == true)
        assertTrue("El detalle debe indicar broadcast=false", lastSos?.details?.contains("broadcast=false") == true)
    }

    @Test
    fun `verifyPin valida PIN correcto 1984 y rechaza otros`() {
        assertTrue("PIN 1984 debe ser válido", UxTestTracker.verifyPin("1984"))
        assertFalse("PIN 0000 debe ser rechazado", UxTestTracker.verifyPin("0000"))
        assertFalse("PIN 1234 debe ser rechazado", UxTestTracker.verifyPin("1234"))
        assertFalse("PIN vacío debe ser rechazado", UxTestTracker.verifyPin(""))
    }

    @Test
    fun `isModeratorSupport es verdadero unicamente para P0`() {
        UxTestTracker.setParticipant("P0")
        assertTrue("P0 debe identificarse como teléfono de apoyo", UxTestTracker.isModeratorSupport)

        listOf("P1", "P2", "P3", "P4", "P5", "P6").forEach { p ->
            UxTestTracker.setParticipant(p)
            assertFalse("$p NO debe ser teléfono de moderador", UxTestTracker.isModeratorSupport)
        }
    }
}
