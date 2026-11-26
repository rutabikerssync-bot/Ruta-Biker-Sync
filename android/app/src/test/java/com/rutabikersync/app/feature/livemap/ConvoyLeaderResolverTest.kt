package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideManifestParticipantPayload
import com.rutabikersync.app.core.crypto.RideManifestPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvoyLeaderResolverTest {

    @Test
    fun `solo el creador de la ruta es lider y el otro dispositivo es rutero`() = runTest {
        val leaderPk = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val ruteroPk = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        val manifest = RideManifestPayload(
            rideId = "ride-01",
            hostGroupId = "grp-1",
            groupIds = listOf("grp-1"),
            hostLeaderId = "uid-tecno",
            participants = listOf(
                RideManifestParticipantPayload(
                    uid = "uid-tecno",
                    gid = "grp-1",
                    statusKind = "leader",
                    pk = leaderPk
                ),
                RideManifestParticipantPayload(
                    uid = "uid-redmi",
                    gid = "grp-1",
                    statusKind = "full",
                    pk = ruteroPk
                )
            ),
            iat = 1000L,
            exp = 2000L,
            kid = "k1"
        )

        val expectedLeaderShortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(leaderPk)
        val expectedRuteroShortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(ruteroPk)

        val resolvedLeader = ConvoyLeaderResolver.resolveLeaderShortId(manifest)
        assertEquals(expectedLeaderShortId, resolvedLeader)

        val tecnoController = LiveMapController(scope = backgroundScope)
        tecnoController.updateConvoyIds(expectedLeaderShortId, resolvedLeader!!)
        tecnoController.updateLocalRiderPose(10.34, -67.04, 0, 0f, 90)
        val tecnoSelf = tecnoController.flushMapFrame(1000L, force = true).markers.first { it.isSelf }
        assertEquals(RiderRideRole.LEADER, tecnoSelf.rideRole)

        val redmiController = LiveMapController(scope = backgroundScope)
        redmiController.updateConvoyIds(expectedRuteroShortId, resolvedLeader)
        redmiController.updateLocalRiderPose(10.34, -67.04, 0, 0f, 90)
        val redmiSelf = redmiController.flushMapFrame(1000L, force = true).markers.first { it.isSelf }
        assertEquals(RiderRideRole.NONE, redmiSelf.rideRole)
    }
}
