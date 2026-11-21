package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideManifestParticipantPayload
import com.rutabikersync.app.core.crypto.RideManifestPayload
import com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosBloodType
import com.rutabikersync.app.core.mesh.sos.SosIncidentCode
import com.rutabikersync.app.core.mesh.sos.SosPacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * P0 auditoría 2026-11-21: Hallazgos H1–H5.
 * Todas las comprobaciones son JVM puras; no requieren dispositivo ni backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P0SecurityChecksTest {

    private val rideKey = "0123456789abcdef0123456789abcdef".toByteArray()
    private val keyHex = rideKey.joinToString("") { "%02x".format(it) }

    class CapturingTransport : Transport {
        val sent = mutableListOf<ByteArray>()
        override val name: String = "P0_CAPTURING"
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) { sent.add(payload) }
        override suspend fun start() {}
        override suspend fun stop() {}
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()
    }

    private fun pkOf(seed: String): String =
        Base64.getEncoder().encodeToString(seed.toByteArray(Charsets.UTF_8))

    private fun authorizedRide(leaderPk: String, memberPk: String): RideManifestKeyProvider.AuthorizedRide {
        val manifest = RideManifestPayload(
            rideId = "ride-p0",
            hostGroupId = "club-a",
            groupIds = listOf("club-a"),
            hostLeaderId = "u-leader",
            participants = listOf(
                RideManifestParticipantPayload(
                    uid = "u-leader", gid = "club-a", statusKind = "confirmed",
                    rideRole = "road_marshal", pk = leaderPk
                ),
                RideManifestParticipantPayload(
                    uid = "u-member", gid = "club-a", statusKind = "confirmed",
                    rideRole = null, pk = memberPk
                )
            ),
            rideSecretKeyHex = keyHex,
            iat = 0L,
            exp = Long.MAX_VALUE,
            kid = "p0-kid"
        )
        return RideManifestKeyProvider.AuthorizedRide(
            rideId = manifest.rideId,
            manifest = manifest,
            rideSecretKey = rideKey,
            participantUid = "u-leader",
            groupIds = manifest.groupIds.toSet(),
            authorizedShortIds = manifest.participants.mapNotNull { it.pk?.let(RideManifestKeyProvider::shortIdFromParticipantPublicKey) }.toSet(),
            localShortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(leaderPk)
        )
    }

    private fun engineWith(
        scope: TestScope,
        shortId: UShort,
        auth: RideManifestKeyProvider.AuthorizedRide? = null
    ): Pair<MeshRelayEngine, CapturingTransport> {
        val transport = CapturingTransport()
        val engine = MeshRelayEngine(
            localShortId = shortId,
            transport = transport,
            relayScope = scope,
            rideSecretKey = rideKey
        )
        if (auth != null) engine.rideAuthorization = RideMeshAuthorization(auth)
        return engine to transport
    }

    @Test
    fun `H3 otro club con la misma clave no puede alterar el ciclo de la rodada`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val leaderPk = pkOf("pk-leader")
        val memberPk = pkOf("pk-member")
        val auth = authorizedRide(leaderPk, memberPk)
        val (receiver, _) = engineWith(scope, 60_000u, auth)

        val received = mutableListOf<MeshRelayEngine.IncomingRideLifecycleEvent>()
        receiver.onRideLifecycleEventReceived = { received.add(it) }

        val (intruder, intruderTransport) = engineWith(scope, 61_111u)
        intruder.emitRideLifecycleEvent(RideLifecycleEvent.START_RETURN)
        receiver.onRawPacketReceived(intruderTransport.sent.first())

        assertEquals("Un shortId ajeno al roster debe ser ignorado", 0, received.size)

        val leaderShortId = auth.localShortId
        val (peer, peerTransport) = engineWith(scope, leaderShortId)
        peer.emitRideLifecycleEvent(RideLifecycleEvent.START_OUTBOUND)
        receiver.onRawPacketReceived(peerTransport.sent.first())

        assertEquals(1, received.size)
        assertEquals(leaderShortId, received.first().senderShortId)
    }

    @Test
    fun `H3 rol y frescura invalidan inversiones de ruta no autorizadas`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val leaderPk = pkOf("pk-leader")
        val memberPk = pkOf("pk-member")
        val auth = authorizedRide(leaderPk, memberPk)
        val (receiver, _) = engineWith(scope, 60_001u, auth)
        val received = mutableListOf<MeshRelayEngine.IncomingRideLifecycleEvent>()
        receiver.onRideLifecycleEventReceived = { received.add(it) }

        val memberShortId = RideManifestKeyProvider.shortIdFromParticipantPublicKey(memberPk)
        val (member, memberTransport) = engineWith(scope, memberShortId)
        member.emitRideLifecycleEvent(RideLifecycleEvent.START_RETURN)
        receiver.onRawPacketReceived(memberTransport.sent.first())
        assertEquals("Un miembro sin rol de líder no puede invertir la ruta", 0, received.size)

        val (leader, leaderTransport) = engineWith(scope, auth.localShortId)
        leader.emitRideLifecycleEvent(RideLifecycleEvent.START_RETURN)
        receiver.onRawPacketReceived(leaderTransport.sent.first())
        assertEquals(1, received.size)
    }

    @Test
    fun `H3 la secuencia repetida o retrocedida se rechaza por emisor`() {
        val leaderPk = pkOf("pk-leader")
        val memberPk = pkOf("pk-member")
        val auth = authorizedRide(leaderPk, memberPk)
        val authorization = RideMeshAuthorization(auth)
        val sid = auth.localShortId

        assertTrue(authorization.acceptSequence(sid, 10u))
        assertFalse("Replay del mismo sequenceNumber", authorization.acceptSequence(sid, 10u))
        assertFalse("SequenceNumber anterior", authorization.acceptSequence(sid, 4u))
        assertTrue("Avance válido", authorization.acceptSequence(sid, 11u))
    }

    @Test
    fun `H2 SOS con firma Ed25519 invalida se rechaza antes de avisar`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val legitimateKey = kpg.generateKeyPair()
        val attackerKey = kpg.generateKeyPair()

        val (receiver, _) = engineWith(scope, 1u)
        receiver.sosCrypto = Ed25519SosCrypto(context = null)
        receiver.sosPublicKeyResolver = InMemorySosPublicKeyResolver().apply {
            registerPeer(7u, legitimateKey.public)
        }
        var notified = false
        receiver.onSosReceived = { notified = true }

        val unsigned = SosPacket.create(
            clubNetworkId = 0x52425301u,
            senderShortId = 7u,
            sequenceNum = 100u,
            latitude = 19.432608,
            longitude = -99.133209,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1_733_000_000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val forged = Ed25519SosCrypto(context = null).signSos(unsigned, attackerKey.private)
        val decision = receiver.processIncomingPacket(forged.toByteArray())

        assertTrue("La firma forjada debe producir DroppedInvalidSignature: $decision",
            decision is MeshRelayEngine.RelayDecision.DroppedInvalidSignature)
        assertFalse("Un SOS forjado no debe llegar a la UI ni a la alarma", notified)

        val valid = Ed25519SosCrypto(context = null).signSos(
            unsigned.copy(header = unsigned.header.copy(sequenceNum = 101u)), legitimateKey.private
        )
        val validDecision = receiver.processIncomingPacket(valid.toByteArray())
        assertFalse(validDecision is MeshRelayEngine.RelayDecision.DroppedInvalidSignature)
        assertTrue("Un SOS legítimo sí debe notificarse", notified)
    }

    @Test
    fun `H1 REST autoriza antes que BLE y sin autorizacion no sale nada`() = runTest {
        val successLog = mutableListOf<String>()
        RestFirstLifecycleGate.runAfterRest<Unit>(
            restCall = { successLog.add("REST"); Result.success(Unit) },
            authorized = { true },
            onAuthorized = { successLog.add("STATE") },
            onDenied = { _, _ -> successLog.add("DENIED") },
            lifecycleEvent = RideLifecycleEvent.START_OUTBOUND,
            emit = { successLog.add("BLE") }
        )
        assertEquals(listOf("REST", "STATE", "BLE"), successLog)

        val rejectedLog = mutableListOf<String>()
        RestFirstLifecycleGate.runAfterRest<Int>(
            restCall = { rejectedLog.add("REST"); Result.success(403) },
            authorized = { false },
            onAuthorized = { rejectedLog.add("STATE") },
            onDenied = { _, _ -> rejectedLog.add("DENIED") },
            lifecycleEvent = RideLifecycleEvent.START_RETURN,
            emit = { rejectedLog.add("BLE") }
        )
        assertEquals(listOf("REST", "DENIED"), rejectedLog)

        val failureLog = mutableListOf<String>()
        RestFirstLifecycleGate.runAfterRest<Unit>(
            restCall = { failureLog.add("REST"); Result.failure(IllegalStateException("offline")) },
            authorized = { true },
            onAuthorized = { failureLog.add("STATE") },
            onDenied = { _, _ -> failureLog.add("DENIED") },
            lifecycleEvent = RideLifecycleEvent.SAFE_HOME,
            emit = { failureLog.add("BLE") }
        )
        assertEquals(listOf("REST", "DENIED"), failureLog)
    }

    @Test
    fun `H1 el evento queda encolado si todavia no existe el engine`() {
        val queue = PendingLifecycleEvents()
        assertTrue(queue.isEmpty)
        queue.enqueue(RideLifecycleEvent.START_OUTBOUND)
        assertFalse(queue.isEmpty)
        assertEquals(listOf(RideLifecycleEvent.START_OUTBOUND), queue.snapshot())
        queue.remove(RideLifecycleEvent.START_OUTBOUND)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `H5 la identidad derivada de la clave publica firmada es estable entre dispositivos`() {
        val pk = pkOf("pk-leader")
        val first = RideManifestKeyProvider.shortIdFromParticipantPublicKey(pk)
        val second = RideManifestKeyProvider.shortIdFromParticipantPublicKey(pk)
        assertEquals(first, second)
        val auth = authorizedRide(pk, pkOf("pk-member"))
        assertArrayEquals(rideKey, auth.rideSecretKey)
        assertTrue(first in auth.authorizedShortIds)
    }
}
