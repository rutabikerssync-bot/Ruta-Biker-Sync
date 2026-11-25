package com.rutabikersync.app.core.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GattHeartbeatServerTest {

    private class FakeBackend(
        private val acceptService: Boolean = true,
        private val acceptAdvertising: Boolean = true,
        private val notifyResult: Boolean = true
    ) : HeartbeatGattServerBackend {
        val notifications = mutableListOf<Pair<String, ByteArray>>()
        var serviceAdded = 0
            private set
        var advertisingStarts = 0
            private set
        var advertisingStops = 0
            private set
        var closed = 0
            private set

        override fun addHeartbeatService(): Boolean {
            serviceAdded++
            return acceptService
        }

        override fun startConnectableAdvertising(): Boolean {
            advertisingStarts++
            return acceptAdvertising
        }

        override fun stopConnectableAdvertising() {
            advertisingStops++
        }

        override fun notify(address: String, value: ByteArray): Boolean {
            notifications += address to value.copyOf()
            return notifyResult
        }

        override fun close() {
            closed++
        }
    }

    private class FakeTransport : Transport {
        override val name: String = "fake-adv"
        val sends = mutableListOf<Pair<ByteArray, Transport.Priority>>()
        val heartbeats = mutableListOf<ByteArray>()

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            sends += payload.copyOf() to priority
        }

        override suspend fun sendHeartbeat(payload: ByteArray, priority: Transport.Priority) {
            heartbeats += payload.copyOf()
        }

        override val incoming: Flow<ByteArray> = emptyFlow()
    }

    /** Quita el header de transporte `[total, indice]` y concatena los chunks en orden. */
    private fun reassemble(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        frames.sortedBy { it[1].toInt() }.forEach { out.write(it, 2, it.size - 2) }
        return out.toByteArray()
    }

    @Test
    fun `start anade el servicio B1D2 y anuncia connectable`() {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val server = GattHeartbeatServer(backend, logs::add)

        assertTrue(server.start())
        assertEquals(1, backend.serviceAdded)
        assertEquals(1, backend.advertisingStarts)
        assertEquals("0000b1d2-0000-1000-8000-00805f9b34fb", GattHeartbeatServer.SERVICE_UUID.toString())
        assertEquals("0000b1d3-0000-1000-8000-00805f9b34fb", GattHeartbeatServer.TX_CHAR_UUID.toString())
        assertTrue(logs.any { it.contains("SERVER_STARTED") })
    }

    @Test
    fun `start falla si no se puede anadir el servicio`() {
        val backend = FakeBackend(acceptService = false)
        val logs = mutableListOf<String>()
        val server = GattHeartbeatServer(backend, logs::add)

        assertTrue(!server.start())
        assertEquals(0, backend.advertisingStarts)
        assertTrue(logs.any { it.contains("SERVICE_ADD_FAILED") })
    }

    @Test
    fun `con MTU negociado un heartbeat de 24 bytes cabe en una notificacion`() = runBlocking {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val server = GattHeartbeatServer(backend, logs::add)
        server.start()
        server.onClientConnected("AA:BB:CC:DD:EE:01")
        server.onSubscribed("AA:BB:CC:DD:EE:01", enabled = true)
        server.onMtuChanged(64)
        val payload = ByteArray(24) { it.toByte() }

        assertEquals(1, server.notifyHeartbeat(payload))
        assertEquals(1, backend.notifications.size)
        val frame = backend.notifications.single().second
        assertEquals(1, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertArrayEquals(payload, reassemble(listOf(frame)))
        assertTrue(logs.any { it.contains("notifyHeartbeat") && it.contains("chunks=1") })
    }

    @Test
    fun `con MTU por defecto fragmenta 24 bytes sin alterar la trama`() = runBlocking {
        val backend = FakeBackend()
        val server = GattHeartbeatServer(backend)
        server.start()
        server.onSubscribed("AA:BB:CC:DD:EE:01", enabled = true)
        val payload = ByteArray(24) { (it * 3).toByte() }

        val frames = server.framesFor(payload)
        assertEquals(2, frames.size)
        assertEquals(2, server.notifyHeartbeat(payload))
        val received = backend.notifications.map { it.second }
        assertEquals(listOf(0, 1), received.map { it[1].toInt() })
        received.forEach { assertTrue("Frame excede ATT payload", it.size <= 20) }
        assertArrayEquals(payload, reassemble(received))
    }

    @Test
    fun `sin suscriptores no notifica`() = runBlocking {
        val backend = FakeBackend()
        val logs = mutableListOf<String>()
        val server = GattHeartbeatServer(backend, logs::add)
        server.start()

        assertEquals(0, server.notifyHeartbeat(ByteArray(24) { 1 }))
        assertTrue(backend.notifications.isEmpty())
        assertTrue(logs.any { it.contains("SKIP") })
    }

    @Test
    fun `desconectar retira al cliente y stop cierra el backend`() = runBlocking {
        val backend = FakeBackend()
        val server = GattHeartbeatServer(backend)
        server.start()
        server.onSubscribed("AA:BB:CC:DD:EE:01", enabled = true)
        server.onClientDisconnected("AA:BB:CC:DD:EE:01")
        assertEquals(0, server.notifyHeartbeat(ByteArray(24) { 2 }))

        server.onSubscribed("AA:BB:CC:DD:EE:01", enabled = true)
        server.stop()
        assertEquals(1, backend.advertisingStops)
        assertEquals(1, backend.closed)
        assertEquals(0, server.notifyHeartbeat(ByteArray(24) { 2 }))
    }

    @Test
    fun `routing envia POSITION al GATT y SOS al advertising`() = runBlocking {
        val backend = FakeBackend()
        val server = GattHeartbeatServer(backend)
        server.start()
        server.onSubscribed("AA:BB:CC:DD:EE:01", enabled = true)
        server.onMtuChanged(64)
        val adv = FakeTransport()
        val routing = HeartbeatGattRoutingTransport(adv, server)
        val heartbeat = ByteArray(24) { 7 }
        val sos = ByteArray(12) { 9 }

        routing.sendHeartbeat(heartbeat, Transport.Priority.POSITION)
        routing.send(sos, Transport.Priority.SOS)

        assertEquals(1, backend.notifications.size)
        assertArrayEquals(heartbeat, reassemble(backend.notifications.map { it.second }))
        assertTrue(adv.heartbeats.isEmpty())
        assertEquals(1, adv.sends.size)
        assertEquals(Transport.Priority.SOS, adv.sends.single().second)
        assertTrue(routing.name.contains("gatt-hb"))
    }

    @Test
    fun `routing sin servidor hace fallback de POSITION al advertising cuando GATT envia 0`() = runBlocking {
        val adv = FakeTransport()
        val routing = HeartbeatGattRoutingTransport(adv, null)
        val payload = ByteArray(24) { 4 }

        routing.sendHeartbeat(payload, Transport.Priority.POSITION)
        routing.send(payload, Transport.Priority.POSITION)

        assertEquals(1, adv.heartbeats.size)
        assertEquals(1, adv.sends.size)
        assertTrue(!routing.name.contains("gatt-hb"))
    }

    @Test
    fun `routing con solo cliente no conectado hace fallback de POSITION y mantiene SOS`() = runBlocking {
        val clientBackend = object : HeartbeatGattClientBackend {
            override fun startScanning() = true
            override fun stopScanning() = Unit
            override fun connect(address: String) = true
            override fun requestMtu(mtu: Int) = true
            override fun subscribe() = true
            override fun discoverServices() = true
            override fun close() = Unit
        }
        val client = GattHeartbeatClient(clientBackend, 1u, setOf(2u))
        val adv = FakeTransport()
        val routing = HeartbeatGattRoutingTransport(adv, null, client)
        val payload = ByteArray(24) { 6 }

        routing.send(payload, Transport.Priority.POSITION)
        routing.send(payload, Transport.Priority.SOS)

        assertEquals(2, adv.sends.size)
        assertEquals(Transport.Priority.POSITION, adv.sends[0].second)
        assertEquals(Transport.Priority.SOS, adv.sends[1].second)
        assertTrue(routing.name.contains("gatt-hb"))
    }

    @Test
    fun `servidor recibe escritura RX del cliente y emite en incomingPackets`() = runBlocking {
        val backend = FakeBackend()
        val server = GattHeartbeatServer(backend)
        server.start()
        val payload = ByteArray(24) { (it + 3).toByte() }
        val frames = server.framesFor(payload, negotiatedMtu = 23) // 2 chunks

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            server.incomingPackets.first()
        }
        server.onCharacteristicWrite("AA:BB:CC:DD:EE:09", frames[0])
        server.onCharacteristicWrite("AA:BB:CC:DD:EE:09", frames[1])
        val pkt = received.await()

        assertArrayEquals(payload, pkt.bytes)
        assertEquals("EE:09", pkt.senderAddressSuffix)
    }

    @Test
    fun `routing con cliente conectado envia POSITION por writeHeartbeat`() = runBlocking {
        val writes = mutableListOf<ByteArray>()
        val clientBackend = object : HeartbeatGattClientBackend {
            override fun startScanning() = true
            override fun stopScanning() = Unit
            override fun connect(address: String) = true
            override fun requestMtu(mtu: Int) = true
            override fun subscribe() = true
            override fun write(value: ByteArray): Boolean {
                writes += value.copyOf()
                return true
            }
            override fun discoverServices() = true
            override fun close() = Unit
        }
        val client = GattHeartbeatClient(clientBackend, 1u, setOf(2u))
        client.onConnected("AA:BB:CC:DD:EE:02")
        client.onMtuChanged(64)
        client.onServicesDiscovered(hasTxCharacteristic = true, hasRxCharacteristic = true)
        client.onDescriptorWrite("AA:BB:CC:DD:EE:02", success = true)

        val adv = FakeTransport()
        val routing = HeartbeatGattRoutingTransport(adv, null, client)
        val payload = ByteArray(24) { 9 }

        routing.sendHeartbeat(payload, Transport.Priority.POSITION)

        assertEquals(1, writes.size)
        assertArrayEquals(payload, reassemble(writes))
        assertTrue(adv.heartbeats.isEmpty())
        assertTrue(adv.sends.isEmpty())
    }
}
