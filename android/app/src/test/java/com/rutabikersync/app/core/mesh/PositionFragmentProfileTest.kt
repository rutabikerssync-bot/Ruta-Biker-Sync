package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.audio.ConvoySignalKind
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.BleFragmentationCodec
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión de cadencia: el sobre AEAD de los heartbeats POSITION mide 52 B y se fragmenta
 * en 4. El perfil estándar (1000+120 ms × 2 repeticiones) serializa ~9 s en sendMutex y
 * baja el bucle de 3 s a ~18 s. Solo POSITION debe usar el perfil corto (300+20 ms × 1);
 * PEER_EVENT/SOS y VOICE/PTT conservan el suyo.
 */
class PositionFragmentProfileTest {

    private class ProfiledTransport : Transport {
        override val name: String = "TEST_PROFILE"
        val standard = mutableListOf<Pair<ByteArray, Transport.Priority>>()
        val heartbeat = mutableListOf<Pair<ByteArray, Transport.Priority>>()
        private val _incoming = MutableSharedFlow<ByteArray>()
        override val incoming = _incoming.asSharedFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun send(payload: ByteArray, priority: Transport.Priority) {
            standard.add(payload.copyOf() to priority)
        }

        override suspend fun sendHeartbeat(payload: ByteArray, priority: Transport.Priority) {
            heartbeat.add(payload.copyOf() to priority)
        }
    }

    private fun testKey() = ByteArray(32) { (it + 0x11).toByte() }

    @Test
    fun `heartbeat POSITION usa sendHeartbeat y sigue yendo cifrado`() = runBlocking {
        val transport = ProfiledTransport()
        val engine = MeshRelayEngine(
            localShortId = 100u,
            transport = transport,
            relayScope = this,
            rideSecretKey = testKey()
        )

        engine.emitLocalPosition(19.4326, -99.1332, 48, 90f, 80)

        assertEquals(1, transport.heartbeat.size)
        assertEquals(Transport.Priority.POSITION, transport.heartbeat.single().second)
        val wire = transport.heartbeat.single().first
        assertTrue(SensitiveFrameEnvelope.isEnvelope(wire))
        assertEquals(52, wire.size)
    }

    @Test
    fun `PEER_EVENT conserva el envio estandar`() = runBlocking {
        val transport = ProfiledTransport()
        val engine = MeshRelayEngine(
            localShortId = 100u,
            transport = transport,
            relayScope = this,
            rideSecretKey = testKey()
        )

        engine.emitConvoySignal(ConvoySignalKind.OBSTACLE)

        assertTrue(transport.heartbeat.isEmpty())
        assertTrue(transport.standard.any { it.second == Transport.Priority.PEER_EVENT })
    }

    @Test
    fun `perfil POSITION reduce el airtime de 8960 a 1280 ms sin cambiar el frame`() {
        val key = testKey()
        val packet = MeshPacket.createSigned(
            shortId = 100u,
            latitude = 19.4326,
            longitude = -99.1332,
            speedKmh = 48,
            bearingDegrees = 90f,
            batteryPercent = 80,
            priority = Transport.Priority.POSITION,
            sequenceNumber = 1,
            rideSecretKey = key
        )
        val envelope = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_HEARTBEAT,
            packet.toByteArray()
        )
        assertEquals(20, packet.toByteArray().size)
        assertEquals(52, envelope.size)
        val frames = BleFragmentationCodec.fragment(envelope, 1)
        assertEquals(4, frames.size)

        val estandarMs = frames.size.toLong() * 2 * (1_000L + 120L)
        val positionMs = frames.size.toLong() *
            BleAdvTransport.POSITION_FRAGMENT_REPEATS *
            (BleAdvTransport.POSITION_FRAGMENT_BURST_MS + BleAdvTransport.POSITION_FRAGMENT_GAP_MS)
        assertEquals(8_960L, estandarMs)
        assertEquals(1_280L, positionMs)
    }
}
