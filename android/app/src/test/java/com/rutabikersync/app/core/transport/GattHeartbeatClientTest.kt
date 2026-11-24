package com.rutabikersync.app.core.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattHeartbeatClientTest {

    private class FakeBackend : HeartbeatGattClientBackend {
        var scanStarts = 0
            private set
        var scanStops = 0
            private set
        var connectCalls = 0
            private set
        var mtuRequests = mutableListOf<Int>()
            private set
        var subscribeCalls = 0
            private set
        var discoverCalls = 0
            private set
        var closes = 0
            private set
        var connectedAddress: String? = null

        override fun startScanning(): Boolean {
            scanStarts++
            return true
        }

        override fun stopScanning() {
            scanStops++
        }

        override fun connect(address: String): Boolean {
            connectCalls++
            connectedAddress = address
            return true
        }

        override fun requestMtu(mtu: Int): Boolean {
            mtuRequests += mtu
            return true
        }

        override fun subscribe(): Boolean {
            subscribeCalls++
            return true
        }

        override fun discoverServices(): Boolean {
            discoverCalls++
            return true
        }

        override fun close() {
            closes++
        }
    }

    @Test
    fun `rol por shortId menor es cliente y mayor es servidor`() {
        assertEquals(
            HeartbeatGattRole.CLIENT,
            GattHeartbeatClient.roleFor(1u, 2u)
        )
        assertEquals(
            HeartbeatGattRole.SERVER,
            GattHeartbeatClient.roleFor(2u, 1u)
        )
    }

    @Test
    fun `plan por pares da clientes a mayores y servidores a menores`() {
        val plan = GattHeartbeatClient.planFor(2u, listOf(2u, 1u, 3u))

        assertEquals(setOf<UShort>(3u), plan.clientTargets)
        assertEquals(setOf<UShort>(1u), plan.serverForPeers)
        assertTrue(plan.isClient)
        assertTrue(plan.isServer)
    }

    @Test
    fun `startClient escanea solo si hay peers mayores`() {
        val clientBackend = FakeBackend()
        val client = GattHeartbeatClient(clientBackend, 1u, setOf(2u))
        assertTrue(client.startClient())
        assertEquals(1, clientBackend.scanStarts)

        val serverOnlyBackend = FakeBackend()
        val serverOnly = GattHeartbeatClient(serverOnlyBackend, 2u, setOf(1u))
        assertTrue(!serverOnly.startClient())
        assertEquals(0, serverOnlyBackend.scanStarts)
    }

    @Test
    fun `onPeerDiscovered conecta solo al par con shortId mayor`() {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val client = GattHeartbeatClient(backend, 1u, setOf(2u), logs::add)
        client.startClient()

        client.onPeerDiscovered("AA:BB:CC:DD:EE:02", 2u)
        client.onPeerDiscovered("AA:BB:CC:DD:EE:03", 0u)
        client.onPeerDiscovered("AA:BB:CC:DD:EE:01", 1u)
        client.onPeerDiscovered("AA:BB:CC:DD:EE:04", null)

        assertEquals(1, backend.connectCalls)
        assertEquals("AA:BB:CC:DD:EE:02", backend.connectedAddress)
        assertEquals(1, backend.scanStops)
        assertTrue(logs.any { it.contains("CLIENT_CONNECT") })
        assertTrue(logs.any { it.contains("PEER_SKIP") })
    }

    @Test
    fun `conectado negocia MTU y suscribe la characteristic TX`() {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val client = GattHeartbeatClient(backend, 1u, setOf(2u), logs::add)

        client.onConnected("AA:BB:CC:DD:EE:02")
        client.onServicesDiscovered(hasTxCharacteristic = true)

        assertEquals(listOf(GattHeartbeatClient.REQUESTED_MTU), backend.mtuRequests)
        assertEquals(1, backend.subscribeCalls)
        assertEquals(0, backend.discoverCalls)
        assertTrue(logs.any { it.contains("GattHeartbeat connected") })
    }

    @Test
    fun `sin characteristic TX no se suscribe`() {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val client = GattHeartbeatClient(backend, 1u, setOf(2u), logs::add)

        client.onServicesDiscovered(hasTxCharacteristic = false)

        assertEquals(0, backend.subscribeCalls)
        assertTrue(logs.any { it.contains("SERVICE_MISSING") })
    }

    @Test
    fun `onCharacteristicChanged reensambla y emite con suffix de direccion real`() = runBlocking {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val client = GattHeartbeatClient(backend, 1u, setOf(2u), logs::add)
        client.onConnected("AA:BB:CC:DD:EE:02")
        val payload = ByteArray(24) { (it + 5).toByte() }
        val frames = listOf(
            byteArrayOf(2, 0) + payload.copyOfRange(0, 18),
            byteArrayOf(2, 1) + payload.copyOfRange(18, 24)
        )

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            client.incomingPackets.first()
        }
        client.onCharacteristicChanged(frames[1])
        client.onCharacteristicChanged(frames[0])
        val packet = received.await()

        assertArrayEquals(payload, packet.bytes)
        assertEquals("EE:02", packet.senderAddressSuffix)
        assertTrue(logs.any { it.contains("onCharacteristicChanged") })
    }

    @Test
    fun `reassembler descarta frames malformados y se reinicia al desconectar`() {
        val payload = ByteArray(24) { it.toByte() }
        val single = byteArrayOf(1, 0) + payload
        val first = byteArrayOf(2, 0) + payload.copyOfRange(0, 12)
        val second = byteArrayOf(2, 1) + payload.copyOfRange(12, 24)
        val reassembler = HeartbeatFrameReassembler()

        assertTrue(reassembler.feed(byteArrayOf(1, 0)) == null)
        assertTrue(reassembler.feed(byteArrayOf(5, 9, 1, 2)) == null)
        assertArrayEquals(payload, reassembler.feed(single))

        assertTrue(reassembler.feed(first) == null)
        reassembler.reset()
        assertTrue(reassembler.feed(first) == null)
        assertArrayEquals(payload, reassembler.feed(second))
    }

    @Test
    fun `stopClient cierra el backend`() {
        val backend = FakeBackend()
        val client = GattHeartbeatClient(backend, 1u, setOf(2u))

        client.stopClient()

        assertEquals(1, backend.closes)
    }
}
