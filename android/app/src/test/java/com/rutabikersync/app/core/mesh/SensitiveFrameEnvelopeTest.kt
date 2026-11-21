package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosPacket
import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/** P0 cierre: sobre ChaCha20-Poly1305 de frames BLE sensibles y anti-replay de nonces. */
class SensitiveFrameEnvelopeTest {

    private val key = ByteArray(32) { (it * 3).toByte() }

    private fun meshPacket(): MeshPacket = MeshPacket.createSigned(
        shortId = 41u,
        latitude = 19.4326077,
        longitude = -99.133208,
        speedKmh = 0,
        bearingDegrees = 0f,
        batteryPercent = 100,
        priority = Transport.Priority.PEER_EVENT,
        sequenceNumber = 77,
        ttl = 4,
        rideSecretKey = key
    )

    @Test
    fun `round trip de signal PEER_EVENT conserva el HMAC de los 20 bytes`() {
        val packet = meshPacket()
        val envelope = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_MESH_PACKET,
            packet.toByteArray()
        )

        assertEquals(20 + SensitiveFrameEnvelope.HEADER_BYTES + SensitiveFrameEnvelope.TAG_SIZE_BYTES, envelope.size)
        assertTrue(SensitiveFrameEnvelope.isEnvelope(envelope))
        assertEquals(SensitiveFrameEnvelope.KIND_MESH_PACKET, SensitiveFrameEnvelope.kindOf(envelope))

        val decoded = SensitiveFrameEnvelope.unwrap(key, envelope)
        assertArrayEquals(packet.toByteArray(), decoded)
        assertTrue("El HMAC original debe sobrevivir dentro del ciphertext", MeshPacket.fromByteArray(decoded).verifyAuthTag(key))
    }

    @Test
    fun `round trip de SOS y ACK usa el tamano nuevo esperado`() {
        val sos = ByteArray(SosPacket.PACKET_SIZE_BYTES) { (it * 5).toByte() }
        val ack = ByteArray(SosAckPacket.PACKET_SIZE_BYTES) { (it * 7).toByte() }

        val sosEnvelope = SensitiveFrameEnvelope.wrap(key, SensitiveFrameEnvelope.KIND_SOS, sos)
        val ackEnvelope = SensitiveFrameEnvelope.wrap(key, SensitiveFrameEnvelope.KIND_SOS_ACK, ack)

        assertEquals(SosPacket.PACKET_SIZE_BYTES + 32, sosEnvelope.size)
        assertEquals(SosAckPacket.PACKET_SIZE_BYTES + 32, ackEnvelope.size)
        assertArrayEquals(sos, SensitiveFrameEnvelope.unwrap(key, sosEnvelope))
        assertArrayEquals(ack, SensitiveFrameEnvelope.unwrap(key, ackEnvelope))
    }

    @Test
    fun `tag Poly1305 manipulado se rechaza`() {
        val envelope = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_MESH_PACKET,
            meshPacket().toByteArray()
        ).copyOf()
        envelope[envelope.lastIndex] = (envelope[envelope.lastIndex].toInt() xor 0xFF).toByte()

        assertThrows(AEADBadTagException::class.java) {
            SensitiveFrameEnvelope.unwrap(key, envelope)
        }
    }

    @Test
    fun `nonce repetido se rechaza y el valido se registra una sola vez`() {
        val nonce = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_MESH_PACKET,
            meshPacket().toByteArray()
        ).let(SensitiveFrameEnvelope::nonceOf)!!
        val guard = SensitiveFrameEnvelope.ReplayGuard()

        assertFalse(guard.isReplay(nonce))
        assertTrue(guard.registerIfNew(nonce))
        assertTrue(guard.isReplay(nonce))
        assertFalse(guard.registerIfNew(nonce))
    }

    @Test
    fun `nonces distintos no colisionan en la guardia anti replay`() {
        val guard = SensitiveFrameEnvelope.ReplayGuard()
        val first = byteArrayOf(1, 23)
        val second = byteArrayOf(12, 3)

        assertTrue(guard.registerIfNew(first))
        assertFalse("Claves decimales distintas no deben colisionar", guard.isReplay(second))
        assertTrue(guard.registerIfNew(second))
    }

    @Test
    fun `cabecera del sobre se autentica como AAD`() {
        val envelope = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_MESH_PACKET,
            meshPacket().toByteArray()
        ).copyOf()
        envelope[3] = SensitiveFrameEnvelope.KIND_SOS

        assertThrows(AEADBadTagException::class.java) {
            SensitiveFrameEnvelope.unwrap(key, envelope)
        }
    }
}
