package com.rutabikersync.app.core.clubroutes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 5 — Outbox de RSVP y coherencia con la idempotencia del backend (Fase 3).
 *
 * Casos críticos para conectividad intermitente:
 * 1. Coalescencia de estado (un solo RSVP pendiente por ruta+usuario).
 * 2. Reenvío idempotente: 201 y 200 son ambos éxito.
 * 3. Error de red conserva la cola para reintento con backoff.
 * 4. Rechazo terminal (409 `home -> confirmed`) se descarta sin bucle infinito.
 * 5. El contador del banner refleja la cola real.
 */
class RsvpOutboxFase5Test {

    private class FakeTransport(
        private val results: MutableList<RsvpSendResult>
    ) : RsvpTransport {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun sendRsvp(
            routeId: String,
            userId: String,
            status: String
        ): RsvpSendResult {
            sent += routeId to status
            return if (results.isEmpty()) RsvpSendResult.Accepted else results.removeAt(0)
        }

        override suspend fun sendArrival(routeId: String, userId: String): RsvpSendResult {
            sent += routeId to "arrive"
            return if (results.isEmpty()) RsvpSendResult.Accepted else results.removeAt(0)
        }
    }

    @Test
    fun `coalesce un solo rsVP pendiente por ruta y usuario`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed"))
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "home"))

        val pending = dao.getUnsynced()
        assertEquals(1, pending.size)
        assertEquals("home", pending[0].targetStatus) // la última intención gana
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun `flush idempotente marca sincronizado y purga la cola`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed"))
        val transport = FakeTransport(mutableListOf(RsvpSendResult.Accepted)) // 201/200

        val result = RsvpOutboxSyncEngine(dao, transport).flush()

        assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(1, (result as RsvpOutboxSyncEngine.SyncResult.Success).syncedCount)
        assertEquals(0, dao.pendingCount())
        assertEquals(listOf("r1" to "confirmed"), transport.sent)
    }

    @Test
    fun `error de red conserva la fila y reintenta después`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "home"))
        val transport = FakeTransport(
            mutableListOf(RsvpSendResult.Error("timeout"), RsvpSendResult.Accepted)
        )
        val engine = RsvpOutboxSyncEngine(dao, transport)

        val first = engine.flush()
        assertTrue(first is RsvpOutboxSyncEngine.SyncResult.Failure)
        assertEquals(1, dao.pendingCount())
        val preserved = dao.getUnsynced().single()
        assertEquals(1, preserved.attempts)
        assertEquals("timeout", preserved.lastError)

        val second = engine.flush()
        assertTrue(second is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun `transicion terminal 409 se descarta sin reintentos infinitos`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed")) // home -> confirmed
        val transport = FakeTransport(
            mutableListOf(RsvpSendResult.Rejected("INVALID_RSVP_TRANSITION"))
        )

        val result = RsvpOutboxSyncEngine(dao, transport).flush()

        assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(1, (result as RsvpOutboxSyncEngine.SyncResult.Success).droppedCount)
        assertEquals(0, dao.pendingCount())
        assertEquals(1, transport.sent.size) // un solo intento
    }

    @Test
    fun `segundo flush sin cola es NoOp`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed"))
        val engine = RsvpOutboxSyncEngine(
            dao,
            FakeTransport(mutableListOf(RsvpSendResult.Accepted, RsvpSendResult.Accepted))
        )

        engine.flush()
        val second = engine.flush()

        assertTrue(second is RsvpOutboxSyncEngine.SyncResult.NoOp)
        assertFalse(dao.getUnsynced().isNotEmpty())
    }

    @Test
    fun `arrive sincroniza confirmed antes de la presencia y purga una sola fila`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(
            RsvpOutboxEntity(
                routeId = "r1",
                userId = "u1",
                targetStatus = "confirmed",
                operation = RsvpOutboxEntity.OPERATION_ARRIVE
            )
        )
        val transport = FakeTransport(mutableListOf())

        val result = RsvpOutboxSyncEngine(dao, transport).flush()

        assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(listOf("r1" to "confirmed", "r1" to "arrive"), transport.sent)
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun `arrive no se envia si la confirmacion fue rechazada`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        dao.enqueue(
            RsvpOutboxEntity(
                routeId = "r1",
                userId = "u1",
                targetStatus = "confirmed",
                operation = RsvpOutboxEntity.OPERATION_ARRIVE
            )
        )
        val transport = FakeTransport(mutableListOf(RsvpSendResult.Rejected("RSVP_LOCKED")))

        val result = RsvpOutboxSyncEngine(dao, transport).flush()

        assertTrue(result is RsvpOutboxSyncEngine.SyncResult.Success)
        assertEquals(listOf("r1" to "confirmed"), transport.sent)
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun `contador del banner sigue a la cola`() = runTest {
        val dao = InMemoryRsvpOutboxDao()
        assertEquals(0, dao.pendingCount())

        dao.enqueue(RsvpOutboxEntity("r1", "u1", "confirmed"))
        dao.enqueue(RsvpOutboxEntity("r2", "u1", "no_go"))
        assertEquals(2, dao.pendingCount())

        RsvpOutboxSyncEngine(
            dao,
            FakeTransport(mutableListOf(RsvpSendResult.Accepted, RsvpSendResult.Accepted))
        ).flush()
        assertEquals(0, dao.pendingCount())
    }
}
