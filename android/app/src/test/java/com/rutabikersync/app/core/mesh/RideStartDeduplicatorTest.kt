package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un solo RideStarted por arranque de rodada.
 *
 * Reproduce en JVM los dos callers que en hardware despacharon el mismo arranque
 * a 36/58/81/102 ms (botón de rodadas + LaunchedEffect de la cabina).
 */
class RideStartDeduplicatorTest {

    private val rideId = "43a2efba-8f15-46d7-9d88-4eb85655015d"

    @Test
    fun `dos solicitudes del mismo rideId en menos de 100 ms solo despachan una`() {
        val guard = RideStartDeduplicator()
        assertTrue(guard.shouldStart(rideId, nowMs = 1_000L))
        assertFalse("La segunda llamada en 36 ms debe suprimirse", guard.shouldStart(rideId, nowMs = 1_036L))
    }

    @Test
    fun `el duplicado observado a 102 ms tambien se suprime`() {
        val guard = RideStartDeduplicator()
        assertTrue(guard.shouldStart(rideId, nowMs = 10_000L))
        assertFalse(guard.shouldStart(rideId, nowMs = 10_102L))
    }

    @Test
    fun `solicitudes separadas por la ventana si despachan`() {
        val guard = RideStartDeduplicator()
        assertTrue(guard.shouldStart(rideId, nowMs = 1_000L))
        assertTrue(
            guard.shouldStart(rideId, nowMs = 1_000L + RideStartDeduplicator.DEFAULT_WINDOW_MS)
        )
    }

    @Test
    fun `un rideId distinto no se considera duplicado`() {
        val guard = RideStartDeduplicator()
        assertTrue(guard.shouldStart(rideId, nowMs = 1_000L))
        assertTrue(guard.shouldStart("otra-rodada", nowMs = 1_010L))
    }

    @Test
    fun `reset permite reiniciar la misma rodada tras una parada legitima`() {
        val guard = RideStartDeduplicator()
        assertTrue(guard.shouldStart(rideId, nowMs = 1_000L))
        assertFalse(guard.shouldStart(rideId, nowMs = 1_010L))
        guard.reset()
        assertTrue(guard.shouldStart(rideId, nowMs = 1_020L))
    }
}
