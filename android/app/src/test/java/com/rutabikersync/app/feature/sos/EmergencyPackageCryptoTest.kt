package com.rutabikersync.app.feature.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas unitarias para el cifrado efímero AES-256-GCM del paquete de rescate médico (R10.1, R10.2, R10.3).
 */
class EmergencyPackageCryptoTest {

    private lateinit var crypto: EmergencyPackageCrypto
    private lateinit var repository: SosRepository

    private val samplePacket = EmergencyRescuePacket(
        riderId = "rider-test-01",
        fullName = "Alejandro Morales",
        riderAlias = "Centella",
        bloodType = "A-",
        allergies = listOf("Sulfas", "Polen"),
        medicalConditions = listOf("Asma leve"),
        regularMedications = listOf("Salbutamol inhalador"),
        organDonor = true,
        emergencyContacts = listOf(
            EmergencyContact(
                name = "Diana Morales",
                relationship = "Hermana",
                phoneNumber = "+57 300 123 4567",
                isPrimary = true
            )
        ),
        insuranceCompany = "Seguros Bolivar",
        insurancePolicyNumber = "BOL-998877",
        insurancePhone = "+57 1 8000 12345",
        bikeBrand = "KTM",
        bikeModel = "390 Adventure",
        bikePlate = "XYZ-123",
        bikeColor = "Naranja / Negro"
    )

    @Before
    fun setUp() {
        crypto = EmergencyPackageCrypto()
        repository = SosRepository()
    }

    @Test
    fun testEncryptionRequiresInitialization() {
        var errorThrown = false
        try {
            crypto.encryptPacket(samplePacket)
        } catch (e: IllegalStateException) {
            errorThrown = true
        }
        assertTrue("encryptPacket debe lanzar IllegalStateException si initForRide no fue llamado", errorThrown)
    }

    @Test
    fun testEncryptAndDecryptRoundTripWithActiveSos() {
        crypto.initForRide()

        val encryptedBlob = crypto.encryptPacket(samplePacket)
        assertNotNull(encryptedBlob)
        assertTrue(encryptedBlob.size > 20)

        // Simular alerta SOS activa en el convoy
        repository.emitLocalSos(
            senderShortId = 42u,
            senderName = "Alejandro Morales",
            senderRole = "PILOTO",
            lat = 4.7110,
            lon = -74.0721,
            triggerType = SosTriggerType.FALL_DETECTION
        )

        val decrypted = crypto.decryptPacket(encryptedBlob, repository)
        assertNotNull("El paquete debe descifrarse cuando hay un SOS activo", decrypted)
        assertEquals("Alejandro Morales", decrypted?.fullName)
        assertEquals("A-", decrypted?.bloodType)
        assertEquals(listOf("Sulfas", "Polen"), decrypted?.allergies)
        assertEquals("Centella", decrypted?.riderAlias)
        assertEquals(true, decrypted?.organDonor)
        assertEquals(1, decrypted?.emergencyContacts?.size)
        assertEquals("Diana Morales", decrypted?.emergencyContacts?.first()?.name)
        assertEquals("KTM", decrypted?.bikeBrand)
        assertEquals("XYZ-123", decrypted?.bikePlate)
    }

    @Test
    fun testAccessDeniedWithoutActiveSosLogsAudit() {
        crypto.initForRide()
        val encryptedBlob = crypto.encryptPacket(samplePacket)

        // Asegurar que NO hay alertas activas
        assertTrue(repository.activeAlerts.value.isEmpty())

        val decrypted = crypto.decryptPacket(encryptedBlob, repository)
        assertNull("El descifrado debe ser nulo si no hay SOS activo", decrypted)

        // Verificar que se registró intento en auditoría (R10.3)
        val audits = repository.accessAudits.value
        assertFalse("Debe registrarse en la auditoría el acceso no autorizado", audits.isEmpty())
        val lastAudit = audits.first()
        assertEquals("none", lastAudit.sosId)
        assertTrue(lastAudit.viewerUserName.contains("denegado"))
    }

    @Test
    fun testClearKeyDestroysSessionKey() {
        crypto.initForRide()
        val encryptedBlob = crypto.encryptPacket(samplePacket)

        // Emitir alerta SOS activa
        val alert = repository.emitLocalSos(
            senderShortId = 99u,
            senderName = "Piloto",
            senderRole = "MIEMBRO",
            lat = 19.4326,
            lon = -99.1332,
            triggerType = SosTriggerType.MANUAL
        )

        // Fin de rodada purga todo y destruye la clave
        repository.clearAllOnRideFinished()
        crypto.clearKey()

        // Re-emitir alerta para tener SOS activo pero con clave ya borrada
        repository.emitLocalSos(
            senderShortId = 99u,
            senderName = "Piloto",
            senderRole = "MIEMBRO",
            lat = 19.4326,
            lon = -99.1332,
            triggerType = SosTriggerType.MANUAL
        )

        val decryptedAfterClear = crypto.decryptPacket(encryptedBlob, repository)
        assertNull("Tras clearKey(), el descifrado debe fallar", decryptedAfterClear)
    }

    @Test
    fun testSosRepositoryIntegrationWithCrypto() {
        repository.updateMyRescuePacket(samplePacket)
        // Inicializar rodada en SosRepository
        repository.initRideCrypto()
        assertNotNull("encryptedRescuePacket debe existir tras initRideCrypto", repository.encryptedRescuePacket.value)

        // Intentar descifrar antes del SOS -> debe retornar null
        val blob = repository.encryptedRescuePacket.value!!
        val beforeSos = repository.decryptEmergencyPacket(blob)
        assertNull(beforeSos)

        // Emitir SOS
        repository.emitLocalSos(
            senderShortId = 1u,
            senderName = "Alejandro Morales",
            senderRole = "LÍDER",
            lat = 10.0,
            lon = -66.0,
            triggerType = SosTriggerType.MANUAL
        )

        // Ahora debe descifrar
        val afterSos = repository.decryptEmergencyPacket(blob)
        assertNotNull(afterSos)
        assertEquals("Alejandro Morales", afterSos?.fullName)
        assertEquals("A-", afterSos?.bloodType)
    }
}
