package com.rutabikersync.app.core.clubroutes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H3 — outbox de transiciones de ciclo: orden, reintento y rechazo terminal. */
class RideLifecycleOutboxTest {

    private class FakeTransport : RideLifecycleTransport {
        var failWithNetworkError = false
        var rejectionCode: Int? = null
        val sent = mutableListOf<RideLifecycleTransition>()

        override suspend fun send(
            routeId: String,
            event: RideLifecycleTransition
        ): RideLifecycleSendResult {
            sent += event
            return when {
                failWithNetworkError -> RideLifecycleSendResult.Error("offline")
                rejectionCode != null -> RideLifecycleHttpMapper.fromResponse(
                    rejectionCode!!,
                    "PHASE_CONFLICT"
                )
                else -> RideLifecycleSendResult.Accepted
            }
        }
    }

    private fun snapshot() = RideRoster(
        routeId = "route-1",
        startsAt = null,
        outboundStartedAt = null,
        destinationReachedAt = null,
        returnStartedAt = null,
        completedAt = null,
        participants = emptyList()
    )

    @Test
    fun `red caida encola y al volver la red sincroniza en orden`() = runTest {
        val dao = InMemoryRideLifecycleOutboxDao()
        val transport = FakeTransport().apply { failWithNetworkError = true }
        val coordinator = RideLifecycleOutboxCoordinator(dao, transport)

        val events = listOf(
            RideLifecycleTransition.START_OUTBOUND,
            RideLifecycleTransition.ARRIVE_DESTINATION,
            RideLifecycleTransition.START_RETURN,
            RideLifecycleTransition.COMPLETE_RIDE
        )
        events.forEach { event ->
            val result = coordinator.request("route-1", event)
            assertTrue(result is RideLifecycleRequestResult.Queued)
        }

        assertEquals(4, dao.pendingCount())
        assertEquals(0, dao.getUnsynced().map { it.transition }.count { it == null })

        // El primer intento en primer plano ya se registró; el worker arranca limpio.
        transport.sent.clear()
        transport.failWithNetworkError = false
        val flush = coordinator.flush()

        assertTrue(flush is RideLifecycleOutboxSyncEngine.SyncResult.Success)
        assertEquals(4, (flush as RideLifecycleOutboxSyncEngine.SyncResult.Success).syncedCount)
        assertEquals(0, dao.pendingCount())
        assertEquals(events, transport.sent)
    }

    @Test
    fun `403 y 409 no encolan ni mutan el estado`() = runTest {
        val dao = InMemoryRideLifecycleOutboxDao()
        val transport = FakeTransport()
        val coordinator = RideLifecycleOutboxCoordinator(dao, transport)
        val rosterBefore = snapshot()

        for (code in listOf(403, 409)) {
            transport.rejectionCode = code
            val result = coordinator.request("route-1", RideLifecycleTransition.START_RETURN)

            assertTrue(result is RideLifecycleRequestResult.Rejected)
            assertEquals(0, dao.pendingCount())
            assertEquals(rosterBefore, snapshot())
            assertFalse(
                RideLifecycleStatePolicy.shouldMutateRoster(result)
            )
            assertFalse(
                RideLifecycleStatePolicy.shouldOpenCockpit(
                    RideLifecycleTransition.START_RETURN,
                    result
                )
            )
            assertFalse(RideLifecycleStatePolicy.shouldBroadcastMesh(result))
        }
    }

    @Test
    fun `error transitorio conserva la cola y el reintento la vacia`() = runTest {
        val dao = InMemoryRideLifecycleOutboxDao()
        val transport = FakeTransport().apply { failWithNetworkError = true }
        val coordinator = RideLifecycleOutboxCoordinator(dao, transport)

        coordinator.request("route-1", RideLifecycleTransition.ARRIVE)
        val firstFlush = coordinator.flush()
        assertTrue(firstFlush is RideLifecycleOutboxSyncEngine.SyncResult.Failure)
        assertEquals(1, dao.pendingCount())
        assertEquals(1, dao.getUnsynced().single().attempts)

        transport.failWithNetworkError = false
        val secondFlush = coordinator.flush()
        assertTrue(secondFlush is RideLifecycleOutboxSyncEngine.SyncResult.Success)
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun `rechazo terminal durante el flush se descarta sin bucle`() = runTest {
        val dao = InMemoryRideLifecycleOutboxDao()
        val transport = FakeTransport().apply { failWithNetworkError = true }
        val coordinator = RideLifecycleOutboxCoordinator(dao, transport)

        coordinator.request("route-1", RideLifecycleTransition.COMPLETE_RIDE)
        transport.failWithNetworkError = false
        transport.rejectionCode = 409

        val flush = coordinator.flush()
        assertTrue(flush is RideLifecycleOutboxSyncEngine.SyncResult.Success)
        assertEquals(1, (flush as RideLifecycleOutboxSyncEngine.SyncResult.Success).droppedCount)
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun `mapper distingue exito idempotente rechazo de fase y error de red`() {
        assertTrue(
            RideLifecycleHttpMapper.fromResponse(201, null) is RideLifecycleSendResult.Accepted
        )
        assertTrue(
            RideLifecycleHttpMapper.fromResponse(200, null) is RideLifecycleSendResult.Accepted
        )
        assertTrue(
            RideLifecycleHttpMapper.fromResponse(403, "LEADER_REQUIRED")
                is RideLifecycleSendResult.Rejected
        )
        assertTrue(
            RideLifecycleHttpMapper.fromResponse(409, "RETURN_NOT_STARTED")
                is RideLifecycleSendResult.Rejected
        )
        assertTrue(
            RideLifecycleHttpMapper.fromResponse(503, "DB_UNAVAILABLE")
                is RideLifecycleSendResult.Error
        )
    }
}
