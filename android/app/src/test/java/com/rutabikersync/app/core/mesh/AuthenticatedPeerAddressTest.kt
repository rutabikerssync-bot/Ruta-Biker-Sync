package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.protocol.MeshPacket
import com.rutabikersync.app.core.transport.BleAdvTransport
import com.rutabikersync.app.core.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0.1: la MAC se propaga siempre; la identidad solo aparece tras validar el sobre. */
class AuthenticatedPeerAddressTest {

    private val key = ByteArray(32) { (it + 0x31).toByte() }

    private fun signedPacket(shortId: UShort, sequenceNumber: Int): MeshPacket = MeshPacket.createSigned(
        shortId = shortId,
        latitude = 19.4326077,
        longitude = -99.133208,
        speedKmh = 30,
        bearingDegrees = 90f,
        batteryPercent = 88,
        priority = Transport.Priority.PEER_EVENT,
        sequenceNumber = sequenceNumber,
        ttl = 2,
        rideSecretKey = key
    )

    @Test
    fun `scan propaga MAC de sobre cifrado sin derivar shortId`() {
        val raw = signedPacket(77u, 11).toByteArray()
        val visible = BleAdvTransport.peerAddressHint("AA:BB:CC:DD:EE:01", raw)!!
        assertEquals("AA:BB:CC:DD:EE:01", visible.first)
        assertEquals(77u.toUShort(), visible.second)

        val envelope = SensitiveFrameEnvelope.wrap(
            key,
            SensitiveFrameEnvelope.KIND_MESH_PACKET,
            raw
        )
        assertTrue(SensitiveFrameEnvelope.isEnvelope(envelope))
        val fromEnvelope = BleAdvTransport.peerAddressHint("AA:BB:CC:DD:EE:02", envelope)!!
        assertEquals("AA:BB:CC:DD:EE:02", fromEnvelope.first)
        assertNull("El cifrado no autentica identidad: shortId debe ser null", fromEnvelope.second)
    }

    @Test
    fun `engine notifica sufijo solo despues de validar HMAC`() = runBlocking {
        val engine = MeshRelayEngine(
            localShortId = 1u,
            rideSecretKey = key,
            requireAuthorization = false
        )
        val events = mutableListOf<Pair<UShort, String>>()
        val unsubscribe = engine.onAuthenticatedPeerAddress { shortId, suffix -> events += shortId to suffix }

        val valid = signedPacket(77u, 21).toByteArray()
        engine.onRawPacketReceived(valid, senderAddressSuffix = "EE:77")
        assertEquals(listOf(77u.toUShort() to "EE:77"), events)
        assertEquals(77u.toUShort(), engine.resolveShortIdForAddressSuffix("EE:77"))

        val tampered = signedPacket(99u, 22).toByteArray().also { it[3] = (it[3] + 1).toByte() }
        engine.onRawPacketReceived(tampered, senderAddressSuffix = "EE:99")
        assertEquals("Un HMAC inválido no debe autenticar MAC ni identidad", 1, events.size)
        assertNull(engine.resolveShortIdForAddressSuffix("EE:99"))

        unsubscribe()
        engine.onRawPacketReceived(signedPacket(88u, 23).toByteArray(), senderAddressSuffix = "EE:88")
        assertEquals(1, events.size)
    }
}
