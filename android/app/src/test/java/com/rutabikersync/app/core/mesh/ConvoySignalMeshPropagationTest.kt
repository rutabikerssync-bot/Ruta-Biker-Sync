package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.mesh.MeshRelayEngine.IncomingConvoySignal
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvoySignalMeshPropagationTest {

    private val testRideSecretKey = "0123456789abcdef0123456789abcdef".toByteArray()

    class CapturingTransport(override val name: String = "CAPTURING_TRANSPORT") : Transport {
        val sentPackets = mutableListOf<Pair<ByteArray, Transport.Priority>>()
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sentPackets.add(payload to priority)
        }
        override suspend fun start() {}
        override suspend fun stop() {}
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()
    }

    @Test
    fun testEmitConvoySignalConstructsValidSignedPacketWithPeerEventPriority() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val transport = CapturingTransport()

        val engine = MeshRelayEngine(
            localShortId = 100u,
            transport = transport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        ,
        requireAuthorization = false
        )

        val packet = engine.emitConvoySignal(ConvoySignalKind.REDUCE_SPEED)

        assertEquals(100.toUShort(), packet.shortId)
        assertEquals(Transport.Priority.PEER_EVENT, packet.priority)
        assertEquals(1, transport.sentPackets.size)
        assertEquals(Transport.Priority.PEER_EVENT, transport.sentPackets.first().second)

        // Verificar codificación microgrados
        val latMicro = kotlin.math.round((packet.latitude * 1e7)).toInt()
        val kindCode = latMicro and 0xFF
        val expectedCode = MeshRelayEngine.CONVOY_SIGNAL_PREFIX or ConvoySignalKind.REDUCE_SPEED.code.toInt()
        assertEquals(expectedCode, kindCode)
    }

    @Test
    fun testReceiveConvoySignalDecodesAndEmitsToSharedFlow() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val senderTransport = CapturingTransport()
        val senderEngine = MeshRelayEngine(
            localShortId = 200u,
            transport = senderTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        ,
        requireAuthorization = false
        )

        val receiverTransport = CapturingTransport()
        val receiverEngine = MeshRelayEngine(
            localShortId = 300u,
            transport = receiverTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        ,
        requireAuthorization = false
        )

        // 1. Suscribirse a señales antes de emitir (replay=0)
        val received = mutableListOf<IncomingConvoySignal>()
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            receiverEngine.incomingConvoySignals.collect { received.add(it) }
        }

        // 2. Emisor genera señal de OBSTACLE
        val packet = senderEngine.emitConvoySignal(ConvoySignalKind.OBSTACLE)
        val packetBytes = senderTransport.sentPackets.first().first

        // 3. Receptor recibe los bytes por RF
        receiverEngine.onRawPacketReceived(packetBytes, rssi = -60)

        // 4. Verificar que se emitió el evento IncomingConvoySignal decodificado
        assertEquals(1, received.size)
        val receivedSignal = received.first()
        assertEquals(200.toUShort(), receivedSignal.senderShortId)
        assertEquals(ConvoySignalKind.OBSTACLE, receivedSignal.signalKind)
        assertEquals(packet.sequenceNumber, receivedSignal.sequenceNumber)
        collectJob.cancel()
    }

    @Test
    fun testDuplicateConvoySignalDroppedBySeenKeys() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val senderTransport = CapturingTransport()
        val senderEngine = MeshRelayEngine(
            localShortId = 500u,
            transport = senderTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        ,
        requireAuthorization = false
        )

        val receiverTransport = CapturingTransport()
        val receiverEngine = MeshRelayEngine(
            localShortId = 600u,
            transport = receiverTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        ,
        requireAuthorization = false
        )

        senderEngine.emitConvoySignal(ConvoySignalKind.POLICE_CHECKPOINT)
        val packetBytes = senderTransport.sentPackets.first().first

        // Primera entrega válida
        receiverEngine.onRawPacketReceived(packetBytes, rssi = -55)
        val validCount1 = receiverEngine.metrics.value.packetsReceivedValid

        // Segunda entrega (eco / duplicado)
        receiverEngine.onRawPacketReceived(packetBytes, rssi = -55)
        val duplicateCount = receiverEngine.metrics.value.duplicatesDropped
        val validCount2 = receiverEngine.metrics.value.packetsReceivedValid

        assertEquals(1, validCount1)
        assertEquals(1, duplicateCount)
        assertEquals(1, validCount2)
    }
}
