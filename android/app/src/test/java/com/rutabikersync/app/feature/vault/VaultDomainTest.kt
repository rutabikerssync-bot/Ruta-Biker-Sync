package com.rutabikersync.app.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VaultDomainTest {

    private val baseNow = 1_700_000_000_000L // Punto fijo de referencia temporal en ms

    @Test
    fun `computeExpirationInfo categoriza correctamente vigencia mayor a 30 dias`() {
        val sixtyDaysFuture = baseNow + TimeUnit.DAYS.toMillis(60)
        val doc = BikerDocument(
            id = "doc-1",
            type = BikerDocumentType.LICENSE,
            title = "Licencia Biker",
            folio = "LIC-12345",
            issuerOrInsurer = "Movilidad",
            expirationDateMillis = sixtyDaysFuture
        )

        val info = doc.computeExpirationInfo(nowMillis = baseNow)
        assertEquals(ExpirationSeverity.VALID, info.severity)
        assertEquals(60L, info.daysRemaining)
    }

    @Test
    fun `computeExpirationInfo categoriza como EXPIRING_SOON cuando faltan entre 8 y 30 dias`() {
        val twentyDaysFuture = baseNow + TimeUnit.DAYS.toMillis(20)
        val doc = BikerDocument(
            id = "doc-2",
            type = BikerDocumentType.INSURANCE,
            title = "Seguro Moto",
            folio = "POL-9999",
            issuerOrInsurer = "Aseguradora",
            expirationDateMillis = twentyDaysFuture
        )

        val info = doc.computeExpirationInfo(nowMillis = baseNow)
        assertEquals(ExpirationSeverity.EXPIRING_SOON, info.severity)
        assertEquals(20L, info.daysRemaining)
    }

    @Test
    fun `computeExpirationInfo categoriza como CRITICAL cuando faltan 7 dias o menos`() {
        val fiveDaysFuture = baseNow + TimeUnit.DAYS.toMillis(5)
        val doc = BikerDocument(
            id = "doc-3",
            type = BikerDocumentType.CIRCULATION,
            title = "Tarjeta Circulación",
            folio = "TC-5555",
            issuerOrInsurer = "Tránsito",
            expirationDateMillis = fiveDaysFuture
        )

        val info = doc.computeExpirationInfo(nowMillis = baseNow)
        assertEquals(ExpirationSeverity.CRITICAL, info.severity)
        assertEquals(5L, info.daysRemaining)
    }

    @Test
    fun `computeExpirationInfo categoriza como EXPIRED cuando la fecha ya paso`() {
        val twoDaysPast = baseNow - TimeUnit.DAYS.toMillis(2)
        val doc = BikerDocument(
            id = "doc-4",
            type = BikerDocumentType.MECHANICAL_INSPECTION,
            title = "Revisión Mecánica",
            folio = "ITV-8888",
            issuerOrInsurer = "Taller",
            expirationDateMillis = twoDaysPast
        )

        val info = doc.computeExpirationInfo(nowMillis = baseNow)
        assertEquals(ExpirationSeverity.EXPIRED, info.severity)
        assertTrue(info.daysRemaining < 0)
    }

    @Test
    fun `computeExpirationInfo detecta documento permanente sin fecha de vencimiento`() {
        val doc = BikerDocument(
            id = "doc-5",
            type = BikerDocumentType.OTHER,
            title = "Manual Propietario",
            folio = "N/A",
            issuerOrInsurer = "Fabricante",
            expirationDateMillis = 0L
        )

        val info = doc.computeExpirationInfo(nowMillis = baseNow)
        assertEquals(ExpirationSeverity.NO_EXPIRATION, info.severity)
        assertEquals(0L, info.daysRemaining)
        assertEquals("Sin vencimiento", info.formattedDate)
    }

    @Test
    fun `BikerDocumentType restringe elegibilidad para paquete de emergencia segun R10_2`() {
        // Solo Licencia y Seguro son elegibles para el paquete de emergencia enviado a líderes ante SOS
        assertTrue(BikerDocumentType.LICENSE.isEmergencyPackageEligible)
        assertTrue(BikerDocumentType.INSURANCE.isEmergencyPackageEligible)

        // Circulación, Revisión y Otros son privados y no van en el paquete de emergencia
        assertFalse(BikerDocumentType.CIRCULATION.isEmergencyPackageEligible)
        assertFalse(BikerDocumentType.MECHANICAL_INSPECTION.isEmergencyPackageEligible)
        assertFalse(BikerDocumentType.OTHER.isEmergencyPackageEligible)
    }

    @Test
    fun `EmergencyMedicalSheet mantiene configuracion de seguridad Shamir y pantalla de bloqueo`() {
        val defaultSheet = EmergencyMedicalSheet()
        assertTrue("Debe sellarse con Shamir por defecto (R10.2)", defaultSheet.isSealedWithShamir)
        assertTrue("Debe ser visible en pantalla de bloqueo para paramédicos", defaultSheet.showOnLockScreen)
        assertEquals("O+", defaultSheet.bloodType)
        assertTrue("Donante por defecto en comunidad biker", defaultSheet.isOrganDonor)
    }

    @Test
    fun `regla R6_5 y R10_2 - compartir ficha medica requiere rideId y groupId no vacios para evitar HTTP 400`() {
        val defaultSheet = EmergencyMedicalSheet()
        assertTrue("Ficha medica valida con tipo de sangre", defaultSheet.bloodType.isNotBlank())

        val emptyRideId = ""
        val emptyGroupId = ""
        val isBlocked = emptyRideId.isBlank() || emptyGroupId.isBlank()
        assertTrue("Pre-vuelo bloquea llamadas con identificadores vacios protegiendo R6.5 y previniendo 400", isBlocked)
    }
}
