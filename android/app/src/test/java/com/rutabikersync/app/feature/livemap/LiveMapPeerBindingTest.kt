package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapPeerBindingTest {

    @Test
    fun `bindToMeshEngine re-subscribes to new MeshRelayEngine after restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = LiveMapController(TestScope(dispatcher))
        controller.updateConvoyIds(localId = 10u, targetLeaderId = 10u)
        controller.updateLocalRiderPose(10.35, -67.04, speedKmh = 0, observedAtEpochMs = 1_000L)

        val engine1 = MeshRelayEngine(
            localShortId = 10u,
            rideSecretKey = ByteArray(32) { 1 },
            relayScope = backgroundScope
        )
        controller.bindToMeshEngine(engine1, enablePeriodicCadenceLoop = false)
        advanceUntilIdle()

        val engine2 = MeshRelayEngine(
            localShortId = 10u,
            rideSecretKey = ByteArray(32) { 1 },
            relayScope = backgroundScope
        )
        controller.bindToMeshEngine(engine2, enablePeriodicCadenceLoop = false)
        advanceUntilIdle()

        val state = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertEquals(1, state.markers.size)
        assertEquals(10u.toUShort(), state.markers.first().shortId)
    }

    @Test
    fun `manifest localShortId is preserved when uxParticipant is not active`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = LiveMapController(TestScope(dispatcher))
        controller.updateConvoyIds(localId = 42u, targetLeaderId = 42u)
        controller.updateLocalRiderPose(10.35, -67.04, speedKmh = 12, observedAtEpochMs = 1_000L)

        val uxParticipant: String? = null
        if (!uxParticipant.isNullOrBlank()) {
            controller.updateConvoyIds(localId = 1u, targetLeaderId = 1u)
        }

        val state = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertEquals(42u.toUShort(), state.markers.first { it.isSelf }.shortId)
    }
}
