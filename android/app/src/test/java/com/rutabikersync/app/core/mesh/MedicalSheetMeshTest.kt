package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.mesh.sos.DecryptedMedicalSheet
import com.rutabikersync.app.core.mesh.sos.MedicalRescueData
import com.rutabikersync.app.core.mesh.sos.MedicalSheetCrypto
import com.rutabikersync.app.core.mesh.sos.MedicalSheetPacket
import com.rutabikersync.app.core.mesh.sos.MedicalSheetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.AEADBadTagException

/**
 * Suite de Pruebas Unitarias para Compartición de Ficha Médica por Mesh y Purga R12 (Hito 4 · Etapa 3).
 *
 * Valida:
 * 1. Formato Wire y Serialización binaria de [MedicalSheetPacket] (<= 254 bytes, cero fragmentación SAR).
 * 2. Cifrado simétrico AES-256-GCM y descifrado de [MedicalRescueData].
 * 3. Integridad criptográfica: detección de manipulación de ciphertext o tag mediante [AEADBadTagException].
 * 4. Aislamiento Zero-Knowledge: Nodos intermedios actúan como repetidores ciegos (Blind Relays).
 * 5. Simulación de convoy de 5 nodos con entrega garantizada al Líder.
 * 6. Purga criptográfica estricta en memoria según la Regla R12 al cierre de rodada.
 */
class MedicalSheetMeshTest {

    private val clubId: UInt = 0x52425301u
    private val testRideId = UUID.fromString("12345678-1234-5678-1234-567812345678")
    private lateinit var validRescueKey: ByteArray

    @Before
    fun setUp() {
        validRescueKey = MedicalSheetCrypto.generateRescueKey()
    }

    /**
     * 1. Valida round-trip de serialización binaria y límite estricto de 254 bytes.
     */
    @Test
    fun testMedicalSheetPacketSerializationRoundTrip() {
        val sampleData = MedicalRescueData(
            bloodType = "O+",
            allergies = "Penicilina",
            medication = "Ninguna",
            conditions = "Hipertensión controlada",
            insurancePolicy = "POL-GN-2026-99",
            iceName = "María Esposa",
            icePhone = "+52 55 9876 5432"
        )

        val encrypted = MedicalSheetCrypto.encrypt(sampleData, validRescueKey)
        val packet = MedicalSheetPacket.create(
            clubNetworkId = clubId,
            ownerShortId = 42u,
            sequenceNum = 1u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            currentTtl = 5
        )

        val wireBytes = packet.toByteArray()

        // Regla R10.2: CERO fragmentación SAR (paquete <= 254 bytes)
        assertTrue(
            "El tamaño del paquete (${wireBytes.size} B) debe ser <= 254 B para evitar fragmentación SAR",
            wireBytes.size <= 254
        )

        val deserialized = MedicalSheetPacket.fromByteArray(wireBytes)
        assertEquals(packet.header.clubNetworkId, deserialized.header.clubNetworkId)
        assertEquals(packet.ownerShortId, deserialized.ownerShortId)
        assertEquals(packet.rideIdUuid, deserialized.rideIdUuid)
        assertArrayEquals(packet.iv, deserialized.iv)
        assertArrayEquals(packet.authTag, deserialized.authTag)
        assertArrayEquals(packet.ciphertext, deserialized.ciphertext)
    }

    /**
     * 2. Valida cifrado y descifrado clínico correcto con clave simétrica válida de 256 bits.
     */
    @Test
    fun testMedicalSheetEncryptionAndDecryptionRoundTrip() {
        val clinical = MedicalRescueData(
            bloodType = "AB-",
            allergies = "Ibuprofeno, Mariscos",
            medication = "Enalapril 10mg",
            conditions = "Asma leve",
            insurancePolicy = "AXA-MOTO-7721",
            iceName = "Carlos Hermano",
            icePhone = "+52 55 1122 3344"
        )

        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)
        val decrypted = MedicalSheetCrypto.decrypt(
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            key = validRescueKey
        )

        assertEquals(clinical.bloodType, decrypted.bloodType)
        assertEquals(clinical.allergies, decrypted.allergies)
        assertEquals(clinical.medication, decrypted.medication)
        assertEquals(clinical.conditions, decrypted.conditions)
        assertEquals(clinical.insurancePolicy, decrypted.insurancePolicy)
        assertEquals(clinical.iceName, decrypted.iceName)
        assertEquals(clinical.icePhone, decrypted.icePhone)
    }

    /**
     * 3. Valida que una alteración de 1 solo bit en el ciphertext lanza AEADBadTagException.
     */
    @Test
    fun testDecryptionFailsOnTamperedCiphertextThrowsBadTag() {
        val clinical = MedicalRescueData(bloodType = "A+")
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)

        // Manipulación intencional de 1 bit
        val corruptedCiphertext = encrypted.ciphertext.copyOf()
        corruptedCiphertext[0] = (corruptedCiphertext[0].toInt() xor 0x01).toByte()

        try {
            MedicalSheetCrypto.decrypt(
                iv = encrypted.iv,
                authTag = encrypted.authTag,
                ciphertext = corruptedCiphertext,
                key = validRescueKey
            )
            fail("Se esperaba AEADBadTagException ante ciphertext manipulado")
        } catch (e: Exception) {
            assertTrue(
                "La excepción debe ser de tipo AEADBadTagException, recibido: ${e.javaClass.simpleName}",
                e is AEADBadTagException
            )
        }
    }

    /**
     * 4. Valida que una alteración en el authTag lanza AEADBadTagException.
     */
    @Test
    fun testDecryptionFailsOnTamperedAuthTagThrowsBadTag() {
        val clinical = MedicalRescueData(bloodType = "B+")
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)

        val corruptedTag = encrypted.authTag.copyOf()
        corruptedTag[15] = (corruptedTag[15].toInt() xor 0x80).toByte()

        try {
            MedicalSheetCrypto.decrypt(
                iv = encrypted.iv,
                authTag = corruptedTag,
                ciphertext = encrypted.ciphertext,
                key = validRescueKey
            )
            fail("Se esperaba AEADBadTagException ante authTag manipulado")
        } catch (e: Exception) {
            assertTrue(
                "La excepción debe ser de tipo AEADBadTagException, recibido: ${e.javaClass.simpleName}",
                e is AEADBadTagException
            )
        }
    }

    /**
     * 5. Valida que intentar descifrar con una clave incorrecta es rechazado criptográficamente.
     */
    @Test
    fun testDecryptionFailsWithWrongKey() {
        val clinical = MedicalRescueData(bloodType = "O-")
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)
        val wrongKey = MedicalSheetCrypto.generateRescueKey()

        try {
            MedicalSheetCrypto.decrypt(
                iv = encrypted.iv,
                authTag = encrypted.authTag,
                ciphertext = encrypted.ciphertext,
                key = wrongKey
            )
            fail("Se esperaba fallo criptográfico con clave errónea")
        } catch (e: Exception) {
            assertTrue(
                "Debe lanzar AEADBadTagException",
                e is AEADBadTagException
            )
        }
    }

    /**
     * 6. Valida codificación y decodificación de claves en hexadecimal.
     */
    @Test
    fun testHexKeyEncodingDecodingRoundTrip() {
        val hex = MedicalSheetCrypto.keyToHex(validRescueKey)
        assertEquals(64, hex.length)
        val recovered = MedicalSheetCrypto.keyFromHex(hex)
        assertArrayEquals(validRescueKey, recovered)
    }

    /**
     * 7. Valida que los nodos intermedios actúan como repetidores ciegos (Zero-Knowledge):
     *    Retransmiten el paquete decrementando TTL pero NO pueden descifrar ni almacenar la ficha.
     */
    @Test
    fun testBlindRelayNodesDoNotDecryptAndRetransmit() {
        val bikerShortId: UShort = 101u
        val relayShortId: UShort = 202u
        val leaderShortId: UShort = 303u

        val clinical = MedicalRescueData(bloodType = "O+", allergies = "Sulfamidas")
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)
        val packet = MedicalSheetPacket.create(
            clubNetworkId = clubId,
            ownerShortId = bikerShortId,
            sequenceNum = 1u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            currentTtl = 3
        )

        // Nodo repetidor (No autorizado, sin clave)
        val relayStore = MedicalSheetStore()
        relayStore.setRescueContext(isAuthorized = false, rescueKey = null)
        assertFalse(relayStore.canDecrypt())

        val relayEngine = MeshRelayEngine(localShortId = relayShortId, clubNetworkId = clubId).apply {
            this.medicalSheetStore = relayStore
        }

        // Repetidor procesa el paquete
        val decision = relayEngine.processIncomingPacket(packet.toByteArray(), bikerShortId)

        assertTrue(decision is MeshRelayEngine.RelayDecision.Relayed)
        decision as MeshRelayEngine.RelayDecision.Relayed
        assertEquals(2, decision.newTtl)
        assertTrue(decision.isHighPriority)

        // Confirmar que el repetidor NO almacenó ni descifró la ficha
        assertEquals(0, relayStore.count)
        assertNull(relayStore.getSheet(bikerShortId))

        // Nodo Líder (Autorizado, posee clave de rescate)
        val leaderStore = MedicalSheetStore()
        leaderStore.setRescueContext(isAuthorized = true, rescueKey = validRescueKey)
        assertTrue(leaderStore.canDecrypt())

        val leaderEngine = MeshRelayEngine(localShortId = leaderShortId, clubNetworkId = clubId).apply {
            this.medicalSheetStore = leaderStore
        }

        // Líder recibe la trama retransmitida por el repetidor
        val leaderDecision = leaderEngine.processIncomingPacket(decision.relayedBytes, relayShortId)
        assertTrue(
            leaderDecision is MeshRelayEngine.RelayDecision.Relayed ||
                    leaderDecision is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted
        )

        // Confirmar que el Líder sí descifró y almacenó los datos clínicos
        assertEquals(1, leaderStore.count)
        val storedSheet = leaderStore.getSheet(bikerShortId)
        assertNotNull(storedSheet)
        assertEquals("O+", storedSheet?.data?.bloodType)
        assertEquals("Sulfamidas", storedSheet?.data?.allergies)
    }

    /**
     * 8. Simulación multi-salto de convoy completo (5 Nodos en cadena):
     *    N1 (Piloto en emergencia) -> N2 (Blind relay) -> N3 (Blind relay) -> N4 (Blind relay) -> N5 (Líder)
     */
    @Test
    fun testMultiHopSimulation5NodesBlindRelayChain() {
        val n1Id: UShort = 1u // Emisor de la ficha
        val n2Id: UShort = 2u // Relay ciego
        val n3Id: UShort = 3u // Relay ciego
        val n4Id: UShort = 4u // Relay ciego
        val n5Id: UShort = 5u // Líder autorizado

        val stores = mapOf(
            n1Id to MedicalSheetStore().apply { setRescueContext(false, null) },
            n2Id to MedicalSheetStore().apply { setRescueContext(false, null) },
            n3Id to MedicalSheetStore().apply { setRescueContext(false, null) },
            n4Id to MedicalSheetStore().apply { setRescueContext(false, null) },
            n5Id to MedicalSheetStore().apply { setRescueContext(true, validRescueKey) }
        )

        val engines = mutableMapOf<UShort, MeshRelayEngine>()
        for (id in listOf(n1Id, n2Id, n3Id, n4Id, n5Id)) {
            engines[id] = MeshRelayEngine(localShortId = id, clubNetworkId = clubId).apply {
                this.medicalSheetStore = stores[id]
            }
        }

        val clinical = MedicalRescueData(
            bloodType = "A-",
            allergies = "Polen, Látex",
            medication = "Insulina basal",
            conditions = "Diabetes Tipo 1",
            insurancePolicy = "GNP-AUTO-982",
            iceName = "Laura Esposa",
            icePhone = "+52 55 4433 2211"
        )
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)

        // N1 emite con TTL = 4
        val initialPacket = MedicalSheetPacket.create(
            clubNetworkId = clubId,
            ownerShortId = n1Id,
            sequenceNum = 10u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            currentTtl = 4
        )

        // Salto 1: N1 emite -> N2 recibe
        val d2 = engines[n2Id]!!.processIncomingPacket(initialPacket.toByteArray(), n1Id)
        assertTrue(d2 is MeshRelayEngine.RelayDecision.Relayed)
        assertEquals(3, (d2 as MeshRelayEngine.RelayDecision.Relayed).newTtl)
        assertEquals(0, stores[n2Id]!!.count) // N2 no puede leer

        // Salto 2: N2 retransmite -> N3 recibe
        val d3 = engines[n3Id]!!.processIncomingPacket(d2.relayedBytes, n2Id)
        assertTrue(d3 is MeshRelayEngine.RelayDecision.Relayed)
        assertEquals(2, (d3 as MeshRelayEngine.RelayDecision.Relayed).newTtl)
        assertEquals(0, stores[n3Id]!!.count) // N3 no puede leer

        // Salto 3: N3 retransmite -> N4 recibe
        val d4 = engines[n4Id]!!.processIncomingPacket(d3.relayedBytes, n3Id)
        assertTrue(d4 is MeshRelayEngine.RelayDecision.Relayed)
        assertEquals(1, (d4 as MeshRelayEngine.RelayDecision.Relayed).newTtl)
        assertEquals(0, stores[n4Id]!!.count) // N4 no puede leer

        // Salto 4: N4 retransmite -> N5 (Líder) recibe con TTL=1 (se consume localmente)
        val d5 = engines[n5Id]!!.processIncomingPacket(d4.relayedBytes, n4Id)
        assertTrue(d5 is MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted)
        assertEquals(MeshHeader.TYPE_MEDICAL_SHEET, (d5 as MeshRelayEngine.RelayDecision.ConsumedLocallyTtlExhausted).packetType)

        // Verificación de almacenamiento: Solo N5 tiene la ficha clínica
        assertEquals(0, stores[n1Id]!!.count)
        assertEquals(0, stores[n2Id]!!.count)
        assertEquals(0, stores[n3Id]!!.count)
        assertEquals(0, stores[n4Id]!!.count)
        assertEquals(1, stores[n5Id]!!.count)

        val leaderSheet = stores[n5Id]!!.getSheet(n1Id)
        assertNotNull(leaderSheet)
        assertEquals("A-", leaderSheet?.data?.bloodType)
        assertEquals("Diabetes Tipo 1", leaderSheet?.data?.conditions)
        assertEquals("Laura Esposa", leaderSheet?.data?.iceName)
    }

    /**
     * 9. Valida la Purga R12: Al finalizar la rodada o cerrarse la emergencia,
     *    la memoria volátil es sanitizada y queda completamente vacía.
     */
    @Test
    fun testPurgeAllOnRideClosedPurgesRAMStrictlyR12() {
        val store = MedicalSheetStore()
        store.setRescueContext(isAuthorized = true, rescueKey = validRescueKey)

        val clinical = MedicalRescueData(bloodType = "B-", allergies = "Ninguna")
        val encrypted = MedicalSheetCrypto.encrypt(clinical, validRescueKey)
        val packet = MedicalSheetPacket.create(
            clubNetworkId = clubId,
            ownerShortId = 77u,
            sequenceNum = 1u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext
        )

        val stored = store.decryptAndStore(packet)
        assertNotNull(stored)
        assertEquals(1, store.count)
        assertTrue(store.canDecrypt())

        // Cierre de rodada -> Disparar Purga R12
        store.purgeAllOnRideClosed()

        assertEquals(0, store.count)
        assertNull(store.getSheet(77u))
        assertFalse(store.canDecrypt())
        assertTrue(store.getAllSheets().isEmpty())
    }

    /**
     * 10. Valida que tramas duplicadas de ficha médica son descartadas (mitigación de tormentas).
     */
    @Test
    fun testMedicalSheetDeduplicationSuppressesStorms() {
        val engine = MeshRelayEngine(localShortId = 10u, clubNetworkId = clubId)
        val encrypted = MedicalSheetCrypto.encrypt(MedicalRescueData(), validRescueKey)
        val packet = MedicalSheetPacket.create(
            clubNetworkId = clubId,
            ownerShortId = 20u,
            sequenceNum = 5u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            currentTtl = 4
        )

        val firstDecision = engine.processIncomingPacket(packet.toByteArray(), 20u)
        assertTrue(firstDecision is MeshRelayEngine.RelayDecision.Relayed)

        // Segundo intento con mismo shortId y sequenceNum -> Duplicado
        val secondDecision = engine.processIncomingPacket(packet.toByteArray(), 20u)
        assertTrue(secondDecision is MeshRelayEngine.RelayDecision.DroppedDuplicate)
    }

    /**
     * 11. Valida que paquetes con clubNetworkId ajeno son descartados sin procesar.
     */
    @Test
    fun testMedicalSheetDroppedWrongNetwork() {
        val engine = MeshRelayEngine(localShortId = 10u, clubNetworkId = clubId)
        val foreignClubId: UInt = 0x99999999u
        val encrypted = MedicalSheetCrypto.encrypt(MedicalRescueData(), validRescueKey)
        val packet = MedicalSheetPacket.create(
            clubNetworkId = foreignClubId,
            ownerShortId = 20u,
            sequenceNum = 1u,
            rideId = testRideId,
            iv = encrypted.iv,
            authTag = encrypted.authTag,
            ciphertext = encrypted.ciphertext,
            currentTtl = 3
        )

        val decision = engine.processIncomingPacket(packet.toByteArray(), 20u)
        assertTrue(decision is MeshRelayEngine.RelayDecision.DroppedWrongNetwork)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual))
    }
}

