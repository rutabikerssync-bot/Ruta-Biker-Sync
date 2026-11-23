package com.rutabikersync.app.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4 — los cinco eventos de ciclo no se re-ejecutan al recrear la cabina. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideServiceLifecycleReplayRegressionTest {

    private fun incoming(
        event: MeshRelayEngine.RideLifecycleEvent,
        sequence: Int
    ) = MeshRelayEngine.IncomingRideLifecycleEvent(
        senderShortId = 7u,
        event = event,
        sequenceNumber = sequence,
        hopsTaken = 0
    )

    @Test
    fun `recrear la cabina no reejecuta ningun evento de ciclo antiguo`() = runTest {
        MeshRelayEngine.RideLifecycleEvent.values().forEachIndexed { index, event ->
            val firstCabina = mutableListOf<MeshRelayEngine.IncomingRideLifecycleEvent>()
            val firstCollector = launch {
                RideService.incomingRideLifecycleEvents.take(1).toList(firstCabina)
            }
            advanceUntilIdle()

            RideService.notifyIncomingRideLifecycleEvent(incoming(event, index + 1))
            advanceUntilIdle()
            assertEquals(listOf(event), firstCabina.map { it.event })
            firstCollector.cancel()

            val recreatedCabina = mutableListOf<MeshRelayEngine.IncomingRideLifecycleEvent>()
            val secondCollector = launch {
                RideService.incomingRideLifecycleEvents.take(1).toList(recreatedCabina)
            }
            advanceUntilIdle()
            assertTrue("$event no debe re-ejecutarse al recrear la cabina", recreatedCabina.isEmpty())

            RideService.notifyIncomingRideLifecycleEvent(incoming(event, index + 101))
            advanceUntilIdle()
            assertEquals(listOf(event), recreatedCabina.map { it.event })
            secondCollector.cancel()
        }
    }
}
