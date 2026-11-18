package com.rutabikersync.app.feature.sos

import com.rutabikersync.app.feature.vault.EmergencyMedicalSheet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias para el sistema de SOS, cuenta regresiva de 20 s
 * y auditoría del paquete de rescate médico (Fase 5 - R10.2, R10.3, R10.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosEmergencyTest {

    @Test
    fun testCountdownCompletesAndConfirmsSosAfter20Seconds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var confirmedTrigger: SosTriggerType? = null
        val manager = SosCountdownManager(
            context = null,
            coroutineScope = testScope,
            onSosConfirmed = { trigger ->
                confirmedTrigger = trigger
            }
        )

        // Iniciar cuenta regresiva por detección de caída
        manager.startCountdown(SosTriggerType.FALL_DETECTION, initialSeconds = 20)

        // Estado inicial
        val initialState = manager.countdownState.value
        assertTrue(initialState is SosCountdownManager.State.Counting)
        assertEquals(20, (initialState as SosCountdownManager.State.Counting).remainingSeconds)

        // Avanzar 10 segundos
        testScope.advanceTimeBy(10_000L)
        val midState = manager.countdownState.value
        assertTrue(midState is SosCountdownManager.State.Counting)
        assertTrue((midState as SosCountdownManager.State.Counting).remainingSeconds in 9..11)

        // Avanzar los segundos restantes hasta completar la cuenta de 20s
        testScope.advanceTimeBy(15_000L)
        val finalState = manager.countdownState.value
        assertTrue(finalState is SosCountdownManager.State.Confirmed)
        assertEquals(SosTriggerType.FALL_DETECTION, confirmedTrigger)
    }

    @Test
    fun testCancelFalseAlarmPreventsSosDispatch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var confirmedTrigger: SosTriggerType? = null
        val manager = SosCountdownManager(
            context = null,
            coroutineScope = testScope,
            onSosConfirmed = { trigger ->
                confirmedTrigger = trigger
            }
        )

        manager.startCountdown(SosTriggerType.MANUAL, initialSeconds = 20)

        // El usuario se da cuenta a los 5 segundos de que fue una falsa alarma
        testScope.advanceTimeBy(5_000L)
        manager.cancelCountdown("Estoy bien, fue un toque accidental")

        val state = manager.countdownState.value
        assertTrue(state is SosCountdownManager.State.Cancelled)
        assertEquals("Estoy bien, fue un toque accidental", (state as SosCountdownManager.State.Cancelled).reason)

        // Avanzar más allá de los 20 segundos
        testScope.advanceTimeBy(25_000L)

        // NUNCA debe haberse confirmado
        assertEquals(null, confirmedTrigger)
    }

    @Test
    fun testImmediateConfirmationBypassesCountdown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var confirmedTrigger: SosTriggerType? = null
        val manager = SosCountdownManager(
            context = null,
            coroutineScope = testScope,
            onSosConfirmed = { trigger ->
                confirmedTrigger = trigger
            }
        )

        manager.startCountdown(SosTriggerType.MANUAL, initialSeconds = 20)
        manager.confirmImmediately()

        val state = manager.countdownState.value
        assertTrue(state is SosCountdownManager.State.Confirmed)
        assertEquals(SosTriggerType.MANUAL, confirmedTrigger)
    }

    @Test
    fun testRescuePacketAndAudit60MinutesRule() {
        val repository = SosRepository()
        repository.updateMyRescuePacket(
            EmergencyRescuePacket(
                riderId = "u-founder",
                fullName = "Carlos Rivas",
                riderAlias = "Rayo Negro",
                bloodType = "O+",
                allergies = listOf("Penicilina", "AINEs")
            )
        )

        // 1. Emisión de SOS local con paquete médico
        val alert = repository.emitLocalSos(
            senderShortId = 101u,
            senderName = "Carlos Rivas",
            senderRole = "LÍDER",
            lat = 10.4064,
            lon = -67.2886,
            triggerType = SosTriggerType.MANUAL
        )

        assertEquals("Carlos Rivas", alert.senderName)
        assertNotNull(alert.rescuePacket)
        assertEquals("O+", alert.rescuePacket?.bloodType)
        assertTrue(alert.rescuePacket?.allergies?.contains("Penicilina") == true)

        // 2. Registro de auditoría por apertura de paquete médico (Regla R10.3)
        val audit = repository.logAccessToEmergencyPacket(
            sosId = alert.sosId,
            viewerUserId = "u-leader-2",
            viewerUserName = "Pedro Gómez (Road Captain)",
            targetRiderName = alert.senderName
        )

        assertEquals("Pedro Gómez (Road Captain)", audit.viewerUserName)
        assertEquals("Carlos Rivas", audit.targetRiderName)
        assertFalse(audit.isExpired)
        assertTrue(audit.remainingMinutes in 59..60)

        // 3. Resolución de emergencia
        repository.resolveSos(alert.sosId)
        assertTrue(repository.activeAlerts.value.isEmpty())

        // 4. Purga segura al finalizar rodada
        repository.clearAllOnRideFinished()
        assertTrue(repository.activeAlerts.value.isEmpty())
    }

    @Test
    fun testEmergencyMedicalSheetPropertiesAndLockScreenEligibility() {
        val sheet = EmergencyMedicalSheet(
            bloodType = "O+",
            allergies = "Penicilina",
            chronicConditions = "Ninguna",
            habitualMedications = "Ninguna",
            isOrganDonor = true,
            emergencyContactName = "María Rivas",
            emergencyContactRelationship = "Hermana",
            emergencyContactPhone = "+52 55 1234 5678",
            medicalInsuranceName = "Seguro Biker Integral",
            insurancePolicyNumber = "POL-99281-MX",
            showOnLockScreen = true
        )

        // Verificación de campos vitales para paramédicos y auxiliadores
        assertTrue(sheet.showOnLockScreen)
        assertEquals("O+", sheet.bloodType)
        assertEquals("Penicilina", sheet.allergies)
        assertEquals("+52 55 1234 5678", sheet.emergencyContactPhone)
        assertEquals("María Rivas", sheet.emergencyContactName)
        assertEquals("Hermana", sheet.emergencyContactRelationship)
        assertTrue(sheet.isOrganDonor)
        assertEquals("POL-99281-MX", sheet.insurancePolicyNumber)

        // Privacidad (R10.2): Cuando el usuario desactiva mostrar en pantalla de bloqueo
        val privateSheet = sheet.copy(showOnLockScreen = false)
        assertFalse(privateSheet.showOnLockScreen)
    }
}
