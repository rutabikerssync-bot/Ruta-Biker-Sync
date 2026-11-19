package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que el mapa redacta nombre/avatar/rol de los peers cuyo groupId no
 * coincide con el del usuario local, sin tocar PeerTrack ni la malla BLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerProfileRedactionTest {

    private val secretKey = "rbs-phase3-livemap-secret-32b!!!".encodeToByteArray()

    @Test
    fun `mismo club muestra perfil real y club distinto o desconocido lo redacta`() =
        runTest(UnconfinedTestDispatcher()) {
            val medium = SimulatedBleMedium()
            val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u)
            medium.connectChain(nodeIds)
            val engines = nodeIds.associateWith { id ->
                MeshRelayEngine(
                    localShortId = id,
                    transport = medium.createNodeTransport(id),
                    rideSecretKey = secretKey
                ).also { it.start(backgroundScope) }
            }

            val controller = LiveMapController(
                scope = backgroundScope,
                localShortId = 1u,
                leaderShortId = 1u,
                initialDirectory = listOf(
                    RiderDirectoryEntry(
                        shortId = 1u,
                        fullName = "Ana Local",
                        roleLabel = "Punta",
                        groupId = "g-1"
                    ),
                    RiderDirectoryEntry(
                        shortId = 2u,
                        fullName = "Beto Mismo",
                        rideRole = RiderRideRole.SCOUT,
                        avatarBase64 = "AAA",
                        roleLabel = "Scout",
                        groupId = "g-1"
                    ),
                    RiderDirectoryEntry(
                        shortId = 3u,
                        fullName = "Carla Ajena",
                        rideRole = RiderRideRole.SWEEP,
                        avatarBase64 = "BBB",
                        roleLabel = "Sweep",
                        groupId = "g-2"
                    ),
                    RiderDirectoryEntry(
                        shortId = 4u,
                        fullName = "Diego Incognito",
                        rideRole = RiderRideRole.CAPTAIN,
                        avatarBase64 = "CCC",
                        roleLabel = "Capitán",
                        groupId = null
                    )
                )
            )
            controller.setLocalGroupId("g-1")
            controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)
            controller.bindToMeshEngine(engines.getValue(1u), enablePeriodicCadenceLoop = false)
            listOf<UShort>(2u, 3u, 4u).forEach { id ->
                engines.getValue(id).emitLocalPosition(
                    10.349300, -67.034904, 20, 180f, 80, ttl = 4
                )
            }

            val byId = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
                .markers.associateBy { it.shortId }

            val self = byId.getValue(1u)
            assertEquals("Ana Local", self.fullName)
            assertFalse(self.profileRedacted)

            val sameClub = byId.getValue(2u)
            assertEquals("Beto Mismo", sameClub.fullName)
            assertEquals("B.M.", sameClub.abbreviatedLabel)
            assertEquals("AAA", sameClub.avatarBase64)
            assertEquals("Scout", sameClub.roleLabel)
            assertEquals(RiderRideRole.SCOUT, sameClub.rideRole)
            assertEquals("g-1", sameClub.groupId)
            assertFalse(sameClub.profileRedacted)

            listOf<UShort>(3u, 4u).forEach { id ->
                val redacted = byId.getValue(id)
                assertEquals("Piloto #${id.toInt()}", redacted.fullName)
                assertEquals("Piloto #${id.toInt()}", redacted.abbreviatedLabel)
                assertEquals("Piloto #${id.toInt()}", redacted.expandedDisplayName)
                assertNull(redacted.avatarUrl)
                assertNull(redacted.avatarBase64)
                assertEquals("Desconocido", redacted.roleLabel)
                assertEquals(RiderRideRole.NONE, redacted.rideRole)
                assertEquals(RiderRelation.ALLIED_STRANGER, redacted.relation)
                assertTrue(redacted.profileRedacted)
            }
            assertEquals("g-2", byId.getValue(3u).groupId)
            assertNull(byId.getValue(4u).groupId)

            engines.values.forEach { it.stop() }
        }
}
