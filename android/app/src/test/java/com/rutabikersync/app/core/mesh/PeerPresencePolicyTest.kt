package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Máquina de estados única: ACTIVE -> STALE_GRACE -> LOST. No cambia umbrales; solo
 * separa pérdida sospechada (`isLost`) de pérdida confirmada (gracia agotada).
 */
class PeerPresencePolicyTest {

    private fun track(
        isLost: Boolean,
        lostSinceEpochMs: Long = 0L,
        effectiveTimeoutMs: Long = 0L
    ): MeshRelayEngine.PeerTrack = MeshRelayEngine.PeerTrack(
        shortId = 2u,
        lastLatitude = 19.0,
        lastLongitude = -99.0,
        lastSpeedKmh = 45,
        lastBatteryPercent = 80,
        isSos = false,
        lastSeenEpochMs = 1_000L,
        firstSequence = 1,
        lastSequence = 1,
        expectedBeats = 1,
        receivedBeats = 1,
        consecutiveAfterLossBeats = 0,
        isLost = isLost,
        lostSinceEpochMs = lostSinceEpochMs,
        effectiveTimeoutMs = effectiveTimeoutMs,
        lastHops = 1
    )

    @Test
    fun `peer no perdido es ACTIVE aunque arrastre marca de perdida antigua`() {
        assertEquals(
            PeerPresenceState.ACTIVE,
            PeerPresencePolicy.stateFor(track(isLost = false, lostSinceEpochMs = 5_000L), 9_000_000L)
        )
    }

    @Test
    fun `gracia de 81s separa STALE_GRACE de LOST sin cambiar el timeout`() {
        val lostAt = 1_000_000L
        val timeout = MeshRelayEngine.LOST_TIMEOUT_MS
        val grace = PeerGracePolicy.graceMsFor(timeout)
        assertEquals(81_000L, grace)

        val stale = track(isLost = true, lostSinceEpochMs = lostAt, effectiveTimeoutMs = timeout)
        assertEquals(PeerPresenceState.STALE_GRACE, PeerPresencePolicy.stateFor(stale, lostAt))
        assertEquals(PeerPresenceState.STALE_GRACE, PeerPresencePolicy.stateFor(stale, lostAt + grace - 1L))
        assertEquals(PeerPresenceState.LOST, PeerPresencePolicy.stateFor(stale, lostAt + grace))

        assertTrue(PeerPresencePolicy.countsAsConnected(PeerPresenceState.STALE_GRACE))
        assertTrue(PeerPresencePolicy.countsAsConnected(PeerPresenceState.ACTIVE))
        assertFalse(PeerPresencePolicy.countsAsConnected(PeerPresenceState.LOST))
        assertFalse(PeerPresencePolicy.isConfirmedLost(PeerPresenceState.STALE_GRACE))
        assertTrue(PeerPresencePolicy.isConfirmedLost(PeerPresenceState.LOST))
    }

    @Test
    fun `peer esperado del manifiesto nunca oido es LOST sin gracia`() {
        val neverHeard = track(isLost = true, lostSinceEpochMs = 0L, effectiveTimeoutMs = 0L)
        assertEquals(PeerPresenceState.LOST, PeerPresencePolicy.stateFor(neverHeard, 9_000_000L))
    }

    @Test
    fun `texto de notificacion distingue degradado de confirmado`() {
        assertEquals("", PeerPresencePolicy.notificationSuffix(0, 0))
        assertEquals(" · 1 sin señal temporal", PeerPresencePolicy.notificationSuffix(1, 0))
        assertEquals(" · 1 sin señal", PeerPresencePolicy.notificationSuffix(0, 1))
        assertEquals(
            " · 2 sin señal temporal · 1 sin señal",
            PeerPresencePolicy.notificationSuffix(2, 1)
        )
    }
}
