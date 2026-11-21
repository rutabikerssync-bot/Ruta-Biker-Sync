package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.crypto.RideManifestKeyProvider
import com.rutabikersync.app.core.crypto.RideManifestParticipantPayload
import com.rutabikersync.app.core.crypto.RideManifestPayload
import com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent
import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosBloodType
import com.rutabikersync.app.core.mesh.sos.SosIncidentCode
import com.rutabikersync.app.core.mesh.sos.SosPacket
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64

/**
 * P0 auditoría 2026-11-21: Hallazgos H1–H5.
 * Todas las comprobaciones son JVM puras; no requieren dispositivo ni backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P0SecurityChecksTest {

    private val rideKey = "0123456789abcdef0123456789abcdef".toByteArray()
    private val keyHex = rideKey.joinToString("") { "%02x".format(it) }
    /** Claves privadas reales del fixture, indexadas por el shortId derivado del manifiesto. */
    private val sosPrivateKeys = mutableMapOf<UShort, PrivateKey>()

    class CapturingTransport : Transport {
        val sent = mutableListOf<ByteArray>()
        private val incomingFlow = MutableSharedFlow<ByteArray>()
        override val name: String = "P0_CAPTURING"
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) { sent.add(payload) }
        override suspend fun start() {}
        override suspend fun stop() {}
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> = incomingFlow.asSharedFlow()
    }

    private fun pkOf(seed: String): String =
        Base64.getEncoder().encodeToString(seed.toByteArray(Charsets.UTF_8))

    private fun authorizedRide(leaderPk: String, memberPk: String): RideManifestKeyProvider.AuthorizedRide {
        sosPrivateKeys.clear()
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val leaderKeys = kpg.generateKeyPair()
        val memberKeys = kpg.generateKeyPair()
        val leaderSid = RideManifestKeyProvider.shortIdFromParticipantPublicKey(leaderPk)
        val memberSid = RideManifestKeyProvider.shortIdFromParticipantPublicKey(memberPk)
        sosPrivateKeys[leaderSid] = leaderKeys.private
        sosPrivateKeys[memberSid] = memberKeys.private
        val sosPublicKeys = mapOf(
            "u-leader" to Base64.getEncoder().encodeToString(
                Ed25519SosCrypto.exportRawPublicKey(leaderKeys.public)
            ),
            "u-member" to Base64.getEncoder().encodeToString(
                Ed25519SosCrypto.exportRawPublicKey(memberKeys.public)
            )
        )
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
            sosPublicKeys = sosPublicKeys,
            rideState = "active",
            iat = 0L,
            exp = Long.MAX_VALUE,
            kid = "p0-kid"
        )
        val resolver = RideManifestKeyProvider.buildSosPublicKeyResolver(manifest)
            ?: error("El fixture del manifiesto debe incluir claves SOS válidas")
        return RideManifestKeyProvider.AuthorizedRide(
            rideId = manifest.rideId,
            manifest = manifest,
            rideSecretKey = rideKey,
            participantUid = "u-leader",
            groupIds = manifest.groupIds.toSet(),
            authorizedShortIds = manifest.participants.mapNotNull { it.pk?.let(RideManifestKeyProvider::shortIdFromParticipantPublicKey) }.toSet(),
            localShortId = leaderSid,
            sosPublicKeyResolver = resolver
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
        if (auth != null) {
            engine.rideAuthorization = RideMeshAuthorization(auth)
            engine.sosPublicKeyResolver = auth.sosPublicKeyResolver
            engine.sosCrypto = Ed25519SosCrypto(context = null)
        }
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
    fun `H2 SOS con clave publica desconocida en el manifiesto se rechaza`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val auth = authorizedRide(pkOf("pk-leader"), pkOf("pk-member"))
        val (receiver, _) = engineWith(scope, 1u, auth)
        var notified = false
        receiver.onSosReceived = { notified = true }

        // Firma real, pero de una clave que NO está en el mapa firmado del manifiesto.
        val outsider = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val unsigned = SosPacket.create(
            clubNetworkId = 0x52425301u,
            senderShortId = 999u,
            sequenceNum = 102u,
            latitude = 19.432608,
            longitude = -99.133209,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1_733_000_000,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val signed = Ed25519SosCrypto(context = null).signSos(unsigned, outsider.private)
        val decision = receiver.processIncomingPacket(signed.toByteArray())

        assertTrue("Un shortId sin clave en el manifiesto debe caer en fail-closed: $decision",
            decision is MeshRelayEngine.RelayDecision.DroppedInvalidSignature)
        assertFalse("El SOS de clave desconocida no debe llegar a la alarma", notified)
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

    @Test
    fun `P0 PEER_EVENT sensible hace round trip cifrado y entrega la señal`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val auth = authorizedRide(pkOf("pk-leader"), pkOf("pk-member"))
        val (receiver, _) = engineWith(scope, 60_100u, auth)
        val received = mutableListOf<MeshRelayEngine.IncomingConvoySignal>()
        receiver.onConvoySignalReceived = { received.add(it) }

        val (sender, senderTransport) = engineWith(scope, auth.localShortId)
        sender.emitConvoySignal(ConvoySignalKind.OBSTACLE)
        val envelope = senderTransport.sent.single()

        assertTrue("PEER_EVENT debe viajar en sobre sensible", SensitiveFrameEnvelope.isEnvelope(envelope))
        assertEquals(52, envelope.size)
        assertEquals(SensitiveFrameEnvelope.KIND_MESH_PACKET, SensitiveFrameEnvelope.kindOf(envelope))

        receiver.onRawPacketReceived(envelope)
        assertEquals(1, received.size)
        assertEquals(auth.localShortId, received.first().senderShortId)
        assertEquals(ConvoySignalKind.OBSTACLE, received.first().signalKind)
    }

    @Test
    fun `P0 tag AEAD manipulado se rechaza sin entregar la señal`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val auth = authorizedRide(pkOf("pk-leader"), pkOf("pk-member"))
        val (receiver, _) = engineWith(scope, 60_101u, auth)
        val received = mutableListOf<MeshRelayEngine.IncomingConvoySignal>()
        receiver.onConvoySignalReceived = { received.add(it) }

        val (sender, senderTransport) = engineWith(scope, auth.localShortId)
        sender.emitConvoySignal(ConvoySignalKind.REDUCE_SPEED)
        val tampered = senderTransport.sent.single().copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()

        receiver.onRawPacketReceived(tampered)
        assertTrue(received.isEmpty())
        assertEquals(1, receiver.metrics.value.sensitiveTagRejected)
    }

    @Test
    fun `P0 nonce repetido se descarta aunque el tag sea valido`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val auth = authorizedRide(pkOf("pk-leader"), pkOf("pk-member"))
        val (receiver, _) = engineWith(scope, 60_102u, auth)
        val received = mutableListOf<MeshRelayEngine.IncomingConvoySignal>()
        receiver.onConvoySignalReceived = { received.add(it) }

        val (sender, senderTransport) = engineWith(scope, auth.localShortId)
        sender.emitConvoySignal(ConvoySignalKind.POLICE_CHECKPOINT)
        val envelope = senderTransport.sent.single()

        receiver.onRawPacketReceived(envelope)
        receiver.onRawPacketReceived(envelope.copyOf())

        assertEquals("La segunda entrega debe caer por replay", 1, received.size)
        assertEquals(1, receiver.metrics.value.sensitiveReplayRejected)
    }

    @Test
    fun `P0 heartbeats viajan cifrados y conservan su HMAC de 20 bytes`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (sender, senderTransport) = engineWith(scope, 7u)
        sender.emitLocalPosition(19.432608, -99.133209, 42, 90f, 88)
        val envelope = senderTransport.sent.single()

        assertEquals(52, envelope.size)
        assertTrue("El heartbeat debe viajar en sobre AEAD", SensitiveFrameEnvelope.isEnvelope(envelope))
        assertEquals(SensitiveFrameEnvelope.KIND_HEARTBEAT, SensitiveFrameEnvelope.kindOf(envelope))
        val heartbeat = SensitiveFrameEnvelope.unwrap(rideKey, envelope)
        assertEquals(MeshPacket.PACKET_SIZE_BYTES, heartbeat.size)
        assertTrue(MeshPacket.fromByteArray(heartbeat).verifyAuthTag(rideKey))

        val (receiver, _) = engineWith(scope, 8u)
        receiver.onRawPacketReceived(envelope)
        assertEquals(1, receiver.metrics.value.packetsReceivedValid)
    }

    @Test
    fun `P0 tag manipulado de heartbeat se rechaza sin entregar posicion`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (sender, senderTransport) = engineWith(scope, 17u)
        sender.emitLocalPosition(19.43, -99.13, 40, 90f, 80)
        val tampered = senderTransport.sent.single().copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()

        val (receiver, _) = engineWith(scope, 18u)
        receiver.onRawPacketReceived(tampered)
        assertEquals(0, receiver.metrics.value.packetsReceivedValid)
        assertEquals(1, receiver.metrics.value.sensitiveTagRejected)
    }

    @Test
    fun `P0 nonce repetido de heartbeat se descarta aunque el tag sea valido`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (sender, senderTransport) = engineWith(scope, 27u)
        sender.emitLocalPosition(19.44, -99.14, 41, 95f, 79)
        val envelope = senderTransport.sent.single()

        val (receiver, _) = engineWith(scope, 28u)
        receiver.onRawPacketReceived(envelope)
        receiver.onRawPacketReceived(envelope.copyOf())
        assertEquals(1, receiver.metrics.value.packetsReceivedValid)
        assertEquals(1, receiver.metrics.value.sensitiveReplayRejected)
    }

    @Test
    fun `P0 SOS y ACK se emiten cifrados y se aceptan tras descifrar`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val auth = authorizedRide(pkOf("pk-leader"), pkOf("pk-member"))
        val senderSid = auth.localShortId
        val privateKey = sosPrivateKeys.getValue(senderSid)
        val verifier = Ed25519SosCrypto(context = null)
        val (emitter, transport) = engineWith(scope, senderSid, auth)
        emitter.start(scope)
        testScheduler.advanceUntilIdle()

        val unsignedSos = SosPacket.create(
            clubNetworkId = 0x52425301u,
            senderShortId = senderSid,
            sequenceNum = 300u,
            latitude = 10.349070,
            longitude = -67.034950,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1_733_000_000,
            signature = ByteArray(64),
            currentTtl = 3
        )
        val sos = verifier.signSos(unsignedSos, privateKey)
        assertTrue(emitter.emitSosPacket(sos))
        testScheduler.advanceUntilIdle()
        val sosWire = transport.sent.last()
        assertEquals(SosPacket.PACKET_SIZE_BYTES + 32, sosWire.size)
        assertEquals(SensitiveFrameEnvelope.KIND_SOS, SensitiveFrameEnvelope.kindOf(sosWire))

        val unsignedAck = SosAckPacket.create(
            clubNetworkId = 0x52425301u,
            ackerShortId = senderSid,
            sequenceNum = 301u,
            originalSenderShortId = 99u,
            originalSequenceNum = 300u,
            ackTimestampEpochSec = 1_733_000_001,
            signature = ByteArray(64),
            currentTtl = 3
        )
        val ack = verifier.signSosAck(unsignedAck, privateKey)
        assertTrue(emitter.emitSosAckPacket(ack))
        testScheduler.advanceUntilIdle()
        val ackWire = transport.sent.last()
        assertEquals(SosAckPacket.PACKET_SIZE_BYTES + 32, ackWire.size)
        assertEquals(SensitiveFrameEnvelope.KIND_SOS_ACK, SensitiveFrameEnvelope.kindOf(ackWire))

        val (receiver, _) = engineWith(scope, 13u, auth)
        var sosCount = 0
        var ackCount = 0
        receiver.onSosReceived = { sosCount++ }
        receiver.onSosAckObserved = { ackCount++ }
        receiver.processIncomingPacket(sosWire)
        receiver.processIncomingPacket(ackWire)

        assertEquals(1, sosCount)
        assertEquals(1, ackCount)
        emitter.stop()
    }

    @Test
    fun `P0 SOS legacy sin transport sale cifrado por el callback de relay`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val emitted = mutableListOf<ByteArray>()
        val engine = MeshRelayEngine(
            localShortId = 15u,
            clubNetworkId = 0x52425301u,
            scope = scope,
            onTransmitRelay = { emitted.add(it) }
        )
        val sos = SosPacket.create(
            clubNetworkId = 0x52425301u,
            senderShortId = 15u,
            sequenceNum = 400u,
            latitude = 10.349070,
            longitude = -67.034950,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1_733_000_002,
            signature = ByteArray(64),
            currentTtl = 5
        )

        assertTrue(engine.emitSosPacket(sos))
        testScheduler.advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertEquals(SensitiveFrameEnvelope.KIND_SOS, SensitiveFrameEnvelope.kindOf(emitted.single()))
    }
}
