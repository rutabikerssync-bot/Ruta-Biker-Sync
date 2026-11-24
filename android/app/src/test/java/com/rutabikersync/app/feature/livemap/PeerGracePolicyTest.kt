package com.rutabikersync.app.feature.livemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIX B · gracia visual de 60 s antes de excluir al peer del conteo del HUD. */
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
}
