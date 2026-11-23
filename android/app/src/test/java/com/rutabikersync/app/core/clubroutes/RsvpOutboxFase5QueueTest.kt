package com.rutabikersync.app.core.clubroutes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 5 · el RSVP offline se conserva y se sincroniza solo al volver la red. */
class RsvpOutboxFase5QueueTest {

    private class FlakyTransport : RsvpTransport {
        var failNext = true
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun sendRsvp(
            routeId: String,
            userId: String,
            status: String
        ): RsvpSendResult {
            sent += routeId to status
            return if (failNext) {
                failNext = false
                RsvpSendResult.Error("sin red")
            } else {
                RsvpSendResult.Accepted
            }
        }
    }

    @Test
    fun `sin red se encola y al volver la red se sincroniza`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed"))
        assertEquals(1, dao.pendingCount())

        val transport = FlakyTransport()
        val offline = RsvpOutboxSyncEngine(dao, transport).flush()
        assertTrue(offline is RsvpOutboxSyncEngine.SyncResult.Failure)
        assertEquals(1, dao.pendingCount())

        val online = RsvpOutboxSyncEngine(dao, transport).flush()
        assertTrue(online is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(1, (online as RsvpOutboxSyncEngine.SyncResult.Success).syncedCount)
        assertEquals(0, dao.pendingCount())
        // Dos llamadas: el intento sin red y el reintento ya con conectividad.
        assertEquals(listOf("r1" to "confirmed", "r1" to "confirmed"), transport.sent)
    }
}
