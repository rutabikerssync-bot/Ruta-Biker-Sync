package com.rutabikersync.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Auditoría integral 2026-11-27 · anti-replay persistente cifrado. */
class RideReplayStateStoreTest {

    @Before
    fun setUp() {
        RideReplayStateStore().clearAll()
    }

    private fun state(
        rideId: String = "ride-replay-1",
        shortId: Int = 1234,
        sequence: Int = 77,
        nonces: List<String> = listOf("ab".repeat(12))
    ) = RideReplayStateStore.SenderState(
        rideId = rideId,
        senderShortId = shortId,
        lastSequence = sequence,
        hasSequence = true,
        recentNonces = nonces,
        updatedAtEpochSec = 1_700_000_000L
    )

    @Test
    fun `guarda y lee el estado sin exponer rideId ni nonces en claro`() {
        val store = RideReplayStateStore()
        val entry = state()

        assertTrue(store.save(entry))
        assertEquals(entry, store.load("ride-replay-1", 1234))

        val blob = store.rawStoredCiphertextForAudit("ride-replay-1", 1234)
        assertNotNull(blob)
        assertFalse("El rideId no debe aparecer en claro", blob!!.contains("ride-replay-1"))
        assertFalse("El nonce no debe aparecer en claro", blob.contains("ab".repeat(12)))
    }

    @Test
    fun `el estado sobrevive a una nueva instancia que simula reinicio del proceso`() {
        val writer = RideReplayStateStore()
        val entry = state(sequence = 91)
        assertTrue(writer.save(entry))

        val afterRestart = RideReplayStateStore()
        assertEquals(entry, afterRestart.load("ride-replay-1", 1234))
    }

    @Test
    fun `un blob manipulado se descarta y se borra`() {
        val store = RideReplayStateStore()
        assertTrue(store.save(state()))
        val blob = store.rawStoredCiphertextForAudit("ride-replay-1", 1234)!!
        val corrupted = (if (blob[0] == 'A') 'B' else 'A') + blob.substring(1)
        store.replaceStoredCiphertextForAudit("ride-replay-1", 1234, corrupted)

        assertNull("Un tag AEAD inválido no debe devolver estado", store.load("ride-replay-1", 1234))
        assertNull("La entrada corrupta debe purgarse", store.rawStoredCiphertextForAudit("ride-replay-1", 1234))
    }

    @Test
    fun `limpiar una rodada no afecta a otras ni a otros emisores`() {
        val store = RideReplayStateStore()
        val otherRide = state(rideId = "ride-replay-2", shortId = 1234)
        val otherSender = state(rideId = "ride-replay-1", shortId = 5678)
        assertTrue(store.save(state()))
        assertTrue(store.save(otherRide))
        assertTrue(store.save(otherSender))

        store.clear("ride-replay-1")

        assertNull(store.load("ride-replay-1", 1234))
        assertNull(store.load("ride-replay-1", 5678))
        assertEquals(otherRide, store.load("ride-replay-2", 1234))
    }

    @Test
    fun `la ventana de nonces queda acotada al guardar`() {
        val store = RideReplayStateStore()
        val nonces = (0 until 600).map { "%024x".format(it) }
        assertTrue(store.save(state(nonces = nonces)))

        val loaded = store.load("ride-replay-1", 1234)!!
        assertEquals(RideReplayStateStore.MAX_NONCES_PER_SENDER, loaded.recentNonces.size)
        assertEquals(nonces.last(), loaded.recentNonces.last())
    }
}
