package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.MeshRelayEngine.IncomingRideLifecycleEvent
import com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideLifecycleMeshPropagationTest {

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
    fun testEmitRideLifecycleEventConstructsValidSignedPacketWithPeerEventPriority() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val transport = CapturingTransport()

        val engine = MeshRelayEngine(
            localShortId = 101u,
            transport = transport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        )

        val packet = engine.emitRideLifecycleEvent(RideLifecycleEvent.START_OUTBOUND)

        assertEquals(101.toUShort(), packet.shortId)
        assertEquals(Transport.Priority.PEER_EVENT, packet.priority)
        assertEquals(1, transport.sentPackets.size)
        assertEquals(Transport.Priority.PEER_EVENT, transport.sentPackets.first().second)

        // Verificar codificación
        val latMicro = kotlin.math.round((packet.latitude * 1e7)).toInt()
        val kindCode = latMicro and 0xFF
        val expectedCode = MeshRelayEngine.RIDE_LIFECYCLE_PREFIX or RideLifecycleEvent.START_OUTBOUND.code.toInt()
        assertEquals(expectedCode, kindCode)
    }

    @Test
    fun testReceiveRideLifecycleEventDecodesAndEmitsToSharedFlowAndCallback() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val senderTransport = CapturingTransport()
        val senderEngine = MeshRelayEngine(
            localShortId = 201u,
            transport = senderTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        )

        val receiverTransport = CapturingTransport()
        val receiverEngine = MeshRelayEngine(
            localShortId = 301u,
            transport = receiverTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        )

        var callbackEvent: IncomingRideLifecycleEvent? = null
        receiverEngine.onRideLifecycleEventReceived = { incoming ->
            callbackEvent = incoming
        }

        val received = mutableListOf<IncomingRideLifecycleEvent>()
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            receiverEngine.incomingRideLifecycleEvents.collect { received.add(it) }
        }

        val packet = senderEngine.emitRideLifecycleEvent(RideLifecycleEvent.START_RETURN)
        val packetBytes = senderTransport.sentPackets.first().first

        receiverEngine.onRawPacketReceived(packetBytes, rssi = -65)

        assertEquals(1, received.size)
        val receivedEvent = received.first()
        assertEquals(201.toUShort(), receivedEvent.senderShortId)
        assertEquals(RideLifecycleEvent.START_RETURN, receivedEvent.event)
        assertEquals(packet.sequenceNumber, receivedEvent.sequenceNumber)

        assertNotNull(callbackEvent)
        assertEquals(RideLifecycleEvent.START_RETURN, callbackEvent?.event)
        collectJob.cancel()
    }

    @Test
    fun testSafeHomeLifecycleEventDelivery() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val senderTransport = CapturingTransport()
        val senderEngine = MeshRelayEngine(
            localShortId = 505u,
            transport = senderTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        )

        val receiverTransport = CapturingTransport()
        val receiverEngine = MeshRelayEngine(
            localShortId = 606u,
            transport = receiverTransport,
            relayScope = testScope,
            rideSecretKey = testRideSecretKey
        )

        val received = mutableListOf<IncomingRideLifecycleEvent>()
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            receiverEngine.incomingRideLifecycleEvents.collect { received.add(it) }
        }

        senderEngine.emitRideLifecycleEvent(RideLifecycleEvent.SAFE_HOME)
        val packetBytes = senderTransport.sentPackets.first().first

        receiverEngine.onRawPacketReceived(packetBytes, rssi = -70)

        assertEquals(1, received.size)
        assertEquals(RideLifecycleEvent.SAFE_HOME, received.first().event)
        assertEquals(505.toUShort(), received.first().senderShortId)
        collectJob.cancel()
    }
}
