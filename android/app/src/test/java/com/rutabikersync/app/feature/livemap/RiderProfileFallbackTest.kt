package com.rutabikersync.app.feature.livemap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RiderProfileFallbackTest {
    @Test
    fun `perfil ausente usa Piloto numero y Desconocido sin inventar datos`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveMapController(
            scope = backgroundScope,
            localShortId = 12243u,
            leaderShortId = 1u,
            initialDirectory = emptyList()
        )
        controller.applyCachedLocalProfile(null, null, null, null, null)
        val frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        val self = frame.markers.first { it.isSelf }

        assertEquals("Piloto #12243", self.fullName)
        assertEquals("Desconocido", self.roleLabel)
    }

    @Test
    fun `perfil cacheado y roster backend se aplican al peer local`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveMapController(
            scope = backgroundScope,
            localShortId = 12243u,
            leaderShortId = 1u,
            initialDirectory = emptyList()
        )
        controller.applyCachedLocalProfile("Oscar Rider", null, null, "#00E676", "u-local")
        controller.applyBackendRoster(
            listOf(
                RideRosterUiEntry("u-local", "Óscar Real", null, "Scout", 0xFFFFB300L),
                RideRosterUiEntry("u-remoto", "Otro", null, "Rider", null)
            ),
            localUserId = "u-local"
        )

        val self = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
            .markers.first { it.isSelf }
        assertEquals("Óscar Real", self.fullName)
        assertEquals("Scout", self.roleLabel)
        assertEquals(RiderRideRole.SCOUT, self.rideRole)
    }
}
