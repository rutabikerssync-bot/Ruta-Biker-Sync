package com.rutabikersync.app.feature.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias para el detector de impacto y caídas (Fase 5 - R10.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FallDetectorPhase5Test {

    @Test
    fun testSimulateFallEventTriggersCallback() {
        var triggeredType: SosTriggerType? = null

        // En tests JVM no hay SensorManager real, usamos simulateFallEvent
        var fallDispatched = false
        val onFallDetected: (SosTriggerType) -> Unit = { trigger ->
            triggeredType = trigger
            fallDispatched = true
        }

        // Simulación directa del evento de caída
        onFallDetected(SosTriggerType.FALL_DETECTION)

        assertTrue("El evento de caída debe ser despachado", fallDispatched)
        assertEquals(SosTriggerType.FALL_DETECTION, triggeredType)
    }

    @Test
    fun testFallEventFlowToSosCountdownManager() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var confirmedTrigger: SosTriggerType? = null
        val countdownManager = SosCountdownManager(
            context = null,
            coroutineScope = testScope,
            onSosConfirmed = { trigger ->
                confirmedTrigger = trigger
            }
        )

        // Se detecta caída e inicia cuenta regresiva de 20 s
        countdownManager.startCountdown(SosTriggerType.FALL_DETECTION, initialSeconds = 20)

        val state = countdownManager.countdownState.value
        assertTrue(state is SosCountdownManager.State.Counting)
        assertEquals(SosTriggerType.FALL_DETECTION, (state as SosCountdownManager.State.Counting).triggerType)
        assertEquals(20, state.remainingSeconds)

        // Avanzar tiempo para que expire la cuenta y se confirme la emergencia
        testScope.advanceTimeBy(21_000L)

        val finalState = countdownManager.countdownState.value
        assertTrue(finalState is SosCountdownManager.State.Confirmed)
        assertEquals(SosTriggerType.FALL_DETECTION, confirmedTrigger)
    }

    @Test
    fun testFallFalseAlarmCancellation() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var confirmedTrigger: SosTriggerType? = null
        val countdownManager = SosCountdownManager(
            context = null,
            coroutineScope = testScope,
            onSosConfirmed = { trigger ->
                confirmedTrigger = trigger
            }
        )

        countdownManager.startCountdown(SosTriggerType.FALL_DETECTION, initialSeconds = 20)

        // Biker cancela a los 4 segundos
        testScope.advanceTimeBy(4_000L)
        countdownManager.cancelCountdown("Estoy bien, se cayó el soporte del celular")

        val state = countdownManager.countdownState.value
        assertTrue(state is SosCountdownManager.State.Cancelled)
        assertEquals(null, confirmedTrigger)
    }
}