package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias JVM del Bloque 3.2 (`RideService` + `RideSessionController` + `MeshRelayEngine`):
 * 1. Selección de transporte: intenta `BleAdvTransport` real por defecto, con fallback a `SimulatedBleTransport`
 *    cuando no hay hardware BLE (JVM) o cuando se activa el modo desarrollo (`forceSimulatedTransport = true`).
 * 2. Notificación persistente en tiempo real: actualiza automáticamente el contador de riders conectados
 *    (`connectedRidersCount`) conforme llegan latidos de otros nodos en la mesh.
 * 3. Confirmación de 3 segundos al pulsar "Detener rodada" desde la notificación:
 *    - El primer toque pasa a `CONFIRMING_STOP` ("Confirmar cierre de rodada (3 s)").
 *    - Si pasan >3 s sin confirmar, vuelve automáticamente a `RUNNING`.
 *    - Si se confirma dentro de los 3 s, detiene la mesh y purga clips de voz y acceso de emergencia (R6.5).
 * 4. Emisión de SOS actualiza el estado de la notificación y transmite paquete de máxima prioridad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideServicePhase3Test {

    @Test
    fun `Bloque 3_2 startRide actualiza riders conectados en tiempo real en la notificacion persistente`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val secretKey = "rbs-phase3-test-secret-key-32b!!".toByteArray(Charsets.UTF_8)
        val notifications = mutableListOf<RideNotificationSnapshot>()

        val controller1 = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null }, // En JVM cae a SimulatedBleTransport
            simulatedMedium = medium,
            onNotificationUpdated = { notifications.add(it) }
        )
        val controller2 = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )
        val controller3 = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        val initial = controller1.startRide(
            rideId = "ride-f3-001",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        controller2.startRide(
            rideId = "ride-f3-001",
            localShortId = 2u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        controller3.startRide(
            rideId = "ride-f3-001",
            localShortId = 3u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )

        assertEquals(RideLifecycleState.RUNNING, initial.lifecycleState)
        assertTrue(initial.isUsingSimulatedTransport)
        assertEquals(0, initial.connectedRidersCount)
        assertEquals("Rodada activa · 0 riders conectados", initial.notification.title)

        // Rider 2 y Rider 3 emiten su heartbeat en la mesh -> la notificación de Rider 1 sube en tiempo real a 2 riders conectados
        controller2.emitHeartbeatNow(1_000L)
        controller3.emitHeartbeatNow(1_100L)

        val afterPeersJoined = controller1.state.value
        assertEquals(2, afterPeersJoined.connectedRidersCount)
        assertEquals("Rodada activa · 2 riders conectados", afterPeersJoined.notification.title)
        assertTrue(afterPeersJoined.notification.contentText.contains("2 riders conectados en la mesh"))

        // Rider 1 activa SOS -> la notificación refleja SOS ACTIVO inmediatamente
        controller1.triggerSos(active = true)
        assertTrue(controller1.state.value.isSosActive)
        assertTrue(controller1.state.value.notification.title.contains("SOS ACTIVO"))

        controller1.stopAll()
        controller2.stopAll()
        controller3.stopAll()
    }

    @Test
    fun `Bloque 3_2 Detener rodada desde la notificacion exige confirmacion de 3 segundos y limpia voz y emergencia R6_5`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val controller = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        controller.startRide(
            rideId = "ride-f3-stop-confirm",
            localShortId = 10u,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        assertEquals(RideLifecycleState.RUNNING, controller.state.value.lifecycleState)

        // 1. Primer toque en "Detener rodada" en t = 10_000 ms -> entra en CONFIRMING_STOP (no detiene aún)
        val firstTap = controller.onStopActionFromNotification(
            nowEpochMs = 10_000L,
            scheduleAutoRevert = false
        )
        assertEquals(RideLifecycleState.CONFIRMING_STOP, firstTap.lifecycleState)
        assertTrue(firstTap.notification.isConfirmingStop)
        assertEquals(3_000L, firstTap.notification.stopConfirmationRemainingMs)

        // 2. Si pasan más de 3 segundos (t = 13_500 ms) sin segundo toque -> vuelve automáticamente a RUNNING
        val expiredState = controller.expireStopConfirmationIfNeeded(nowEpochMs = 13_500L)
        assertEquals(RideLifecycleState.RUNNING, expiredState.lifecycleState)
        assertFalse(expiredState.notification.isConfirmingStop)

        // 3. Nuevo primer toque en t = 20_000 ms y confirmación dentro de los 3 s (t = 21_500 ms) -> detiene la rodada
        controller.onStopActionFromNotification(nowEpochMs = 20_000L, scheduleAutoRevert = false)
        assertEquals(RideLifecycleState.CONFIRMING_STOP, controller.state.value.lifecycleState)

        val confirmedStop = controller.onStopActionFromNotification(
            nowEpochMs = 21_500L,
            scheduleAutoRevert = false
        )
        assertEquals(RideLifecycleState.STOPPED, confirmedStop.lifecycleState)
        assertTrue(confirmedStop.voiceClipsPurgedOnClose)
        assertTrue(confirmedStop.emergencyAccessRevokedOnClose)
        assertEquals("Rodada finalizada", confirmedStop.notification.title)
    }
}
