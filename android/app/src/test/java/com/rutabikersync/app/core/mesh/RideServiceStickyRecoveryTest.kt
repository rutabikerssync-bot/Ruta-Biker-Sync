package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideServiceStickyRecoveryTest {

    private class InMemoryRideRecoveryStore : RideRecoveryStore {
        private var current: PersistedRideParams? = null
        override fun save(params: PersistedRideParams) {
            current = params
        }

        override fun load(): PersistedRideParams? = current

        override fun clear() {
            current = null
        }
    }

    @Test
    fun `onStartCommand null restaura la rodada y llama a startForeground antes de reconstruir`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryRideRecoveryStore()
        val secretKey = "rbs-sticky-recovery-secret-key32".toByteArray(Charsets.UTF_8)
        val hexKey = secretKey.joinToString("") { "%02x".format(it) }
        store.save(
            PersistedRideParams(
                rideId = "ride-sticky-01",
                localShortId = 2u,
                manifestKeyHex = hexKey,
                expectedPeerShortIds = setOf(1u, 3u),
                forceSimulated = true,
                bleModeOverrideCode = null,
                advertisingBurstDurationMs = 500,
                relayEnabled = true,
                txRxRoleCode = null,
                unfilteredScanDiagnostic = false,
                advertisingDeliveryModeCode = null
            )
        )

        val controller = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = SimulatedBleMedium()
        )
        val events = mutableListOf<String>()

        val handler = StickyRideRecoveryHandler(
            store = store,
            startForegroundFirst = {
                events += "startForeground"
                true
            },
            restoreRide = { params ->
                events += "restoreRide:${params.rideId}"
                val keyBytes = params.manifestKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                kotlinx.coroutines.runBlocking {
                    controller.startRide(
                        rideId = params.rideId,
                        localShortId = params.localShortId,
                        rideSecretKey = keyBytes,
                        forceSimulatedTransport = params.forceSimulated,
                        expectedPeerShortIds = params.expectedPeerShortIds,
                        enablePeriodicHeartbeatLoop = false
                    )
                }
            },
            stopWithoutRide = {
                events += "stopSelf"
            }
        )

        val handled = handler.handleNullIntent()

        assertTrue(handled)
        assertEquals(listOf("startForeground", "restoreRide:ride-sticky-01"), events)
        assertEquals(RideLifecycleState.RUNNING, controller.state.value.lifecycleState)
        assertEquals("ride-sticky-01", controller.state.value.rideId)
        assertNotNull(controller.relayEngine)
        controller.stopAll()
    }

    @Test
    fun `onStartCommand null sin estado persistido detiene el servicio`() {
        val store = InMemoryRideRecoveryStore()
        var stopped = false
        var foregroundCalled = false

        val handler = StickyRideRecoveryHandler(
            store = store,
            startForegroundFirst = {
                foregroundCalled = true
                true
            },
            restoreRide = {},
            stopWithoutRide = { stopped = true }
        )

        val handled = handler.handleNullIntent()

        assertFalse(handled)
        assertFalse(foregroundCalled)
        assertTrue(stopped)
    }
}
