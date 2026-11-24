package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIX B + Fase 2 · gracia visual del HUD antes de excluir al peer del conteo. */
class PeerGracePolicyTest {

    @Test
    fun `peer con gap de 50 s sigue en gracia durante 60 s`() {
        val lostAt = 1_700_000_000_000L
        assertTrue(PeerGracePolicy.isInGrace(lostAt + 1L, lostAt))
        assertTrue(PeerGracePolicy.isInGrace(lostAt + 50_000L, lostAt))
        assertTrue(PeerGracePolicy.isInGrace(lostAt + 59_999L, lostAt))
        assertFalse(PeerGracePolicy.isInGrace(lostAt + 60_000L, lostAt))
        assertFalse(PeerGracePolicy.isInGrace(lostAt + 61_000L, lostAt))
    }

    @Test
    fun `peer nunca perdido o con marca invalida no tiene gracia`() {
        assertFalse(PeerGracePolicy.isInGrace(1_700_000_000_000L, 0L))
        assertFalse(PeerGracePolicy.isInGrace(1_700_000_000_000L, -1L))
    }

    @Test
    fun `gracia derivada del timeout con tope de 120 s`() {
        assertEquals(60_000L, PeerGracePolicy.graceMsFor(0L))
        assertEquals(81_000L, PeerGracePolicy.graceMsFor(MeshRelayEngine.LOST_TIMEOUT_MS))
        assertEquals(108_000L, PeerGracePolicy.graceMsFor(54_000L))
        assertEquals(120_000L, PeerGracePolicy.graceMsFor(180_000L))
    }

    @Test
    fun `isInGrace respeta una gracia explicita`() {
        val lostAt = 1_700_000_000_000L
        assertTrue(PeerGracePolicy.isInGrace(lostAt + 100_000L, lostAt, graceMs = 120_000L))
        assertFalse(PeerGracePolicy.isInGrace(lostAt + 120_000L, lostAt, graceMs = 120_000L))
    }
}
