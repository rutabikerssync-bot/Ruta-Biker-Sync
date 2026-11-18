package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.sos.Ed25519SosCrypto
import com.rutabikersync.app.core.mesh.sos.InMemorySosPublicKeyResolver
import com.rutabikersync.app.core.mesh.sos.SosAckPacket
import com.rutabikersync.app.core.mesh.sos.SosBloodType
import com.rutabikersync.app.core.mesh.sos.SosIncidentCode
import com.rutabikersync.app.core.mesh.sos.SosPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Suite de Pruebas Unitarias para Formatos Wire y Criptografía Ed25519 de SOS (Fase 6 · Hito 4 · Etapa 1).
 */
class SosPacketCryptoTest {

    private lateinit var crypto: Ed25519SosCrypto
    private lateinit var senderKeyPair: KeyPair
    private lateinit var ackerKeyPair: KeyPair
    private lateinit var otherKeyPair: KeyPair

    @Before
    fun setUp() {
        crypto = Ed25519SosCrypto(context = null) // JVM in-memory
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        senderKeyPair = kpg.generateKeyPair()
        ackerKeyPair = kpg.generateKeyPair()
        otherKeyPair = kpg.generateKeyPair()
    }

    @Test
    fun testSosPacketSerializationRoundTrip() {
        val dummySig = ByteArray(64) { it.toByte() }
        val original = SosPacket.create(
            clubNetworkId = 0xCAFEBABE_u,
            senderShortId = 42_u,
            sequenceNum = 105_u,
            latitude = 40.416775,
            longitude = -3.703790,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_POSITIVE,
            timestampEpochSec = 1731150000,
            signature = dummySig,
            currentTtl = 5,
            hopCount = 0
        )

        val bytes = original.toByteArray()
        assertEquals(88, bytes.size)
        assertEquals(88, SosPacket.PACKET_SIZE_BYTES)

        val parsed = SosPacket.fromByteArray(bytes)
        assertEquals(original, parsed)
        assertEquals(original.header, parsed.header)
        assertEquals(MeshHeader.TYPE_SOS, parsed.header.packetType)
        assertEquals(40.416775, parsed.latitude, 0.00001)
        assertEquals(-3.703790, parsed.longitude, 0.00001)
        assertEquals(SosIncidentCode.FALL_AUTOMATIC, parsed.incident)
        assertEquals(SosBloodType.O_POSITIVE, parsed.blood)
        assertEquals(1731150000, parsed.timestampEpochSec)
        assertEquals(0.toByte(), parsed.hopCount)
        assertArrayEquals(dummySig, parsed.signature)
    }

    @Test
    fun testSosAckPacketSerializationRoundTrip() {
        val dummySig = ByteArray(64) { (it + 5).toByte() }
        val originalAck = SosAckPacket.create(
            clubNetworkId = 0xCAFEBABE_u,
            ackerShortId = 1_u,
            sequenceNum = 200_u,
            originalSenderShortId = 42_u,
            originalSequenceNum = 105_u,
            ackTimestampEpochSec = 1731150005,
            signature = dummySig,
            currentTtl = 5
        )

        val bytes = originalAck.toByteArray()
        assertEquals(83, bytes.size)
        assertTrue(bytes.size <= 88)
        assertEquals(83, SosAckPacket.PACKET_SIZE_BYTES)

        val parsed = SosAckPacket.fromByteArray(bytes)
        assertEquals(originalAck, parsed)
        assertEquals(originalAck.header, parsed.header)
        assertEquals(MeshHeader.TYPE_SOS_ACK, parsed.header.packetType)
        assertEquals(42_u.toUShort(), parsed.originalSenderShortId)
        assertEquals(105_u.toUShort(), parsed.originalSequenceNum)
        assertEquals(1731150005, parsed.ackTimestampEpochSec)
        assertEquals(1_u.toUShort(), parsed.ackerShortId)
        assertArrayEquals(dummySig, parsed.signature)
    }

    @Test
    fun testEd25519SigningAndVerificationValidSos() {
        val unsigned = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.A_POSITIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )

        val signedSos = crypto.signSos(unsigned, senderKeyPair.private)
        assertEquals(64, signedSos.signature.size)

        // Verificación con la clave pública legítima debe ser TRUE
        val isValid = crypto.verifySos(signedSos, senderKeyPair.public)
        assertTrue("La firma Ed25519 de un SosPacket válido debe verificarse exitosamente", isValid)
    }

    @Test
    fun testEd25519TamperDetectionInCoordinatesFailsVerification() {
        val unsigned = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.A_POSITIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64)
        )
        val signedSos = crypto.signSos(unsigned, senderKeyPair.private)

        // Alterar 1 bit en latitud
        val tamperedLat = signedSos.copy(latitudeRaw = signedSos.latitudeRaw xor 1)
        assertFalse("Modificación de latitud debe invalidar la firma Ed25519", crypto.verifySos(tamperedLat, senderKeyPair.public))

        // Alterar 1 bit en longitud
        val tamperedLon = signedSos.copy(longitudeRaw = signedSos.longitudeRaw xor 1)
        assertFalse("Modificación de longitud debe invalidar la firma Ed25519", crypto.verifySos(tamperedLon, senderKeyPair.public))
    }

    @Test
    fun testEd25519TamperDetectionInIncidentOrBloodTypeFailsVerification() {
        val unsigned = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.MANUAL_BUTTON,
            bloodType = SosBloodType.A_POSITIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64)
        )
        val signedSos = crypto.signSos(unsigned, senderKeyPair.private)

        // Alterar tipo de incidente
        val tamperedIncident = signedSos.copy(incidentCode = SosIncidentCode.VEHICLE_COLLISION.code)
        assertFalse("Modificación de incidentCode debe invalidar la firma Ed25519", crypto.verifySos(tamperedIncident, senderKeyPair.public))

        // Alterar tipo de sangre
        val tamperedBlood = signedSos.copy(bloodType = SosBloodType.AB_NEGATIVE.code)
        assertFalse("Modificación de bloodType debe invalidar la firma Ed25519", crypto.verifySos(tamperedBlood, senderKeyPair.public))
    }

    @Test
    fun testEd25519TamperDetectionInSignatureFailsVerification() {
        val unsigned = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_NEGATIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64)
        )
        val signedSos = crypto.signSos(unsigned, senderKeyPair.private)

        val corruptedSig = signedSos.signature.copyOf()
        corruptedSig[0] = (corruptedSig[0].toInt() xor 0xFF).toByte()
        val corruptedSos = signedSos.copy(signature = corruptedSig)

        assertFalse("Firma corrupta en 1 bit debe ser rechazada", crypto.verifySos(corruptedSos, senderKeyPair.public))
    }

    @Test
    fun testEd25519WrongPublicKeyFailsVerification() {
        val unsigned = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.O_NEGATIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64)
        )
        val signedSos = crypto.signSos(unsigned, senderKeyPair.private)

        // Verificar con clave pública distinta a la del emisor
        assertFalse("Verificación con clave pública de otro nodo debe fallar", crypto.verifySos(signedSos, otherKeyPair.public))
    }

    @Test
    fun testHopCountIncrementPreservesSignatureValidityOptionB() {
        // Opción B: hopCount es metadato de transporte fuera de los 14 bytes firmados
        val original = SosPacket.create(
            clubNetworkId = 0x12345678_u,
            senderShortId = 7_u,
            sequenceNum = 10_u,
            latitude = 4.609710,
            longitude = -74.081750,
            incident = SosIncidentCode.FALL_AUTOMATIC,
            bloodType = SosBloodType.B_POSITIVE,
            timestampEpochSec = 1731151200,
            signature = ByteArray(64),
            currentTtl = 5,
            hopCount = 0
        )
        val signedByOrigin = crypto.signSos(original, senderKeyPair.private)
        assertEquals(0.toByte(), signedByOrigin.hopCount)
        assertTrue(crypto.verifySos(signedByOrigin, senderKeyPair.public))

        // Simulación Salto 1 (Relay N1 -> N2): decrementa TTL a 4, incrementa hopCount a 1
        val relayHop1 = signedByOrigin.copyWithIncrementedHop(newTtl = 4)
        assertEquals(1.toByte(), relayHop1.hopCount)
        assertEquals(4, relayHop1.header.currentTtl)
        assertTrue("Firma debe seguir siendo válida tras Salto 1", crypto.verifySos(relayHop1, senderKeyPair.public))

        // Simulación Salto 2 (Relay N2 -> N3): decrementa TTL a 3, incrementa hopCount a 2
        val relayHop2 = relayHop1.copyWithIncrementedHop(newTtl = 3)
        assertEquals(2.toByte(), relayHop2.hopCount)
        assertEquals(3, relayHop2.header.currentTtl)
        assertTrue("Firma debe seguir siendo válida tras Salto 2", crypto.verifySos(relayHop2, senderKeyPair.public))

        // Simulación Salto 3 (Relay N3 -> N4 Líder): decrementa TTL a 2, incrementa hopCount a 3
        val relayHop3 = relayHop2.copyWithIncrementedHop(newTtl = 2)
        assertEquals(3.toByte(), relayHop3.hopCount)
        assertEquals(2, relayHop3.header.currentTtl)
        assertTrue("Firma debe seguir siendo válida al llegar al Líder tras 3 saltos", crypto.verifySos(relayHop3, senderKeyPair.public))

        // Los bytes serializados preservan exactamente la firma y el hopCount en el byte 87
        val wireBytes = relayHop3.toByteArray()
        assertEquals(88, wireBytes.size)
        assertEquals(3.toByte(), wireBytes[87])

        val deserializedAtLeader = SosPacket.fromByteArray(wireBytes)
        assertEquals(3.toByte(), deserializedAtLeader.hopCount)
        assertTrue("Firma deserializada en el Líder debe ser 100% válida", crypto.verifySos(deserializedAtLeader, senderKeyPair.public))
    }

    @Test
    fun testSosAckSigningAndVerification() {
        val unsignedAck = SosAckPacket.create(
            clubNetworkId = 0x12345678_u,
            ackerShortId = 1_u,
            sequenceNum = 50_u,
            originalSenderShortId = 7_u,
            originalSequenceNum = 10_u,
            ackTimestampEpochSec = 1731151203,
            signature = ByteArray(64)
        )

        val signedAck = crypto.signSosAck(unsignedAck, ackerKeyPair.private)
        assertEquals(64, signedAck.signature.size)
        assertTrue("Firma de SosAckPacket debe verificarse exitosamente", crypto.verifySosAck(signedAck, ackerKeyPair.public))

        // Tamper en shortId original
        val tamperedAck = signedAck.copy(originalSenderShortId = 99_u)
        assertFalse("Modificación en originalSenderShortId del ACK debe invalidar la firma", crypto.verifySosAck(tamperedAck, ackerKeyPair.public))

        // Clave incorrecta
        assertFalse("Verificación con clave ajena debe fallar", crypto.verifySosAck(signedAck, otherKeyPair.public))
    }

    @Test
    fun testRawPublicKeyConversionToAndFromSpki() {
        val rawBytes = Ed25519SosCrypto.exportRawPublicKey(senderKeyPair.public)
        assertEquals(32, rawBytes.size)

        val reimportedKey = Ed25519SosCrypto.importRawPublicKey(rawBytes)
        assertEquals(senderKeyPair.public, reimportedKey)

        // Verificar que la clave reimportada valida la firma
        val data = "SOS_EMERGENCY_DATA".toByteArray(Charsets.UTF_8)
        val sig = crypto.sign(data, senderKeyPair.private)
        assertTrue(crypto.verify(data, sig, reimportedKey))
    }

    @Test
    fun testInMemorySosPublicKeyResolver() {
        val resolver = InMemorySosPublicKeyResolver()
        assertNull(resolver.resolvePublicKey(7_u))

        resolver.registerPeer(7_u, senderKeyPair.public)
        resolver.registerPeer(1_u, ackerKeyPair.public)

        assertEquals(senderKeyPair.public, resolver.resolvePublicKey(7_u))
        assertEquals(ackerKeyPair.public, resolver.resolvePublicKey(1_u))
        assertNull(resolver.resolvePublicKey(99_u))

        resolver.removePeer(7_u)
        assertNull(resolver.resolvePublicKey(7_u))
        assertNotNull(resolver.resolvePublicKey(1_u))

        resolver.clear()
        assertNull(resolver.resolvePublicKey(1_u))
    }
}

