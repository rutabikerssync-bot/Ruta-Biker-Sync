package com.rutabikersync.app.core.mesh

import android.Manifest
import android.content.pm.ServiceInfo
import android.os.Build
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private val fullApi35Perms = setOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @Test
    fun `Combo 1_API 35 y 36 con todos los permisos habilita mesh real y FGS 0x18 (LOCATION y CONNECTED_DEVICE)`() {
        for (sdk in listOf(Build.VERSION_CODES.VANILLA_ICE_CREAM, 36)) {
            val eligibility = RideService.computeEligibility(
                sdkInt = sdk,
                grantedPermissions = fullApi35Perms,
                forceSimulated = false
            )
            assertTrue(eligibility.meshViable)
            assertFalse(eligibility.effectiveForceSimulated)
            assertTrue(eligibility.shouldPromoteToForeground)
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                eligibility.foregroundServiceTypes
            )
            assertEquals(0x18, eligibility.foregroundServiceTypes)
            assertTrue(eligibility.postNotificationsGranted)
        }
    }

    @Test
    fun `Combo 2_API 35 y 36 con Ubicacion pero SIN permisos BLE marca meshViable false y pasa a simulado sin FGS`() {
        for (sdk in listOf(Build.VERSION_CODES.VANILLA_ICE_CREAM, 36)) {
            val eligibility = RideService.computeEligibility(
                sdkInt = sdk,
                grantedPermissions = setOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                forceSimulated = false
            )
            assertFalse(eligibility.meshViable)
            assertTrue(eligibility.effectiveForceSimulated)
            assertFalse(eligibility.shouldPromoteToForeground)
            assertEquals(0, eligibility.foregroundServiceTypes)
        }
    }

    @Test
    fun `Combo 3_API 35 y 36 con solo BLUETOOTH_SCAN sin BLUETOOTH_ADVERTISE marca meshViable false`() {
        for (sdk in listOf(Build.VERSION_CODES.VANILLA_ICE_CREAM, 36)) {
            val eligibility = RideService.computeEligibility(
                sdkInt = sdk,
                grantedPermissions = setOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                forceSimulated = false
            )
            assertFalse(eligibility.meshViable)
            assertTrue(eligibility.effectiveForceSimulated)
            assertFalse(eligibility.shouldPromoteToForeground)
            assertEquals(0, eligibility.foregroundServiceTypes)
        }
    }

    @Test
    fun `Combo 4_API 35 y 36 con BLUETOOTH_SCAN y ADVERTISE pero SIN Ubicacion usa FGS 0x10 CONNECTED_DEVICE y detecta POST_NOTIFICATIONS rechazado`() {
        for (sdk in listOf(Build.VERSION_CODES.VANILLA_ICE_CREAM, 36)) {
            val eligibility = RideService.computeEligibility(
                sdkInt = sdk,
                grantedPermissions = setOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                forceSimulated = false
            )
            assertTrue(eligibility.meshViable)
            assertFalse(eligibility.effectiveForceSimulated)
            assertTrue(eligibility.shouldPromoteToForeground)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, eligibility.foregroundServiceTypes)
            assertEquals(0x10, eligibility.foregroundServiceTypes)
            assertFalse(eligibility.postNotificationsGranted)
        }
    }

    @Test
    fun `Combo 5_API 30 Android 11 con BLUETOOTH legacy y Ubicacion habilita mesh real y FGS 0x18 sin exigir POST_NOTIFICATIONS`() {
        val api30Valid = RideService.computeEligibility(
            sdkInt = Build.VERSION_CODES.R,
            grantedPermissions = setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            forceSimulated = false
        )
        assertTrue(api30Valid.meshViable)
        assertFalse(api30Valid.effectiveForceSimulated)
        assertTrue(api30Valid.shouldPromoteToForeground)
        assertEquals(0x18, api30Valid.foregroundServiceTypes)
        assertTrue(api30Valid.postNotificationsGranted)
    }

    @Test
    fun `Combo 6_API 30 Android 11 con BLUETOOTH legacy pero SIN Ubicacion marca meshViable false porque el escaneo BLE exige ubicacion en API 26 a 30`() {
        val api30NoLocation = RideService.computeEligibility(
            sdkInt = Build.VERSION_CODES.R,
            grantedPermissions = setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ),
            forceSimulated = false
        )
        assertFalse(api30NoLocation.meshViable)
        assertTrue(api30NoLocation.effectiveForceSimulated)
        assertFalse(api30NoLocation.shouldPromoteToForeground)
        assertEquals(0, api30NoLocation.foregroundServiceTypes)
    }

    @Test
    fun `Combo 7_forceSimulated true con todos los permisos concedidos desactiva Foreground Service y corre en app`() {
        val forcedSim = RideService.computeEligibility(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            grantedPermissions = fullApi35Perms,
            forceSimulated = true
        )
        assertTrue(forcedSim.meshViable)
        assertTrue(forcedSim.effectiveForceSimulated)
        assertFalse(forcedSim.shouldPromoteToForeground)
        assertEquals(0, forcedSim.foregroundServiceTypes)
    }

    @Test
    fun `startRide registers expected convoy peers and reports 0_0 percent delivery when registered peer never emits`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val controller = RideSessionController(
            scope = backgroundScope,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        val t0 = 500_000L
        // El nodo #1 (Punta) inicia la prueba esperando a #2 (Medio) y #3 (Cierre), pero #2 y #3 nunca emiten
        val initialState = controller.startRide(
            rideId = "field-test-silent-peers",
            localShortId = 1u,
            forceSimulatedTransport = true,
            expectedPeerShortIds = setOf(2u, 3u),
            enablePeriodicHeartbeatLoop = false,
            nowEpochMs = t0
        )
        assertEquals(listOf(2, 3), initialState.expectedPeerShortIds)
        assertEquals(0, initialState.connectedRidersCount)

        // En t0 (0 s transcurridos, 0 latidos esperados aún): heartbeatsDeliveredPct es null ("N/D")
        val reportAt0s = controller.metrics.value
        assertNotNull(reportAt0s)
        assertEquals(0, reportAt0s!!.heartbeatsExpected)
        assertEquals(0, reportAt0s.heartbeatsReceived)
        assertEquals(null, reportAt0s.heartbeatsDeliveredPct)

        // A los 3 s (1 latido esperado por cada par registrado = 2 esperados, 0 recibidos):
        // heartbeatsDeliveredPct pasa a 0.0f (0.0 % con color de fallo, nunca "N/D")
        controller.emitHeartbeatNow(nowEpochMs = t0 + 3_000L)
        val reportAt3s = controller.metrics.value!!
        assertEquals(2, reportAt3s.heartbeatsExpected)
        assertEquals(0, reportAt3s.heartbeatsReceived)
        assertNotNull(reportAt3s.heartbeatsDeliveredPct)
        assertEquals(0.0f, reportAt3s.heartbeatsDeliveredPct!!, 0.001f)
        assertEquals(0, controller.state.value.connectedRidersCount)

        // A los 15 s (5 latidos perdidos por cada par = 10 esperados, 0 recibidos):
        // ambos pares silenciosos pasan a isLost = true y la entrega sigue en 0.0 %
        controller.emitHeartbeatNow(nowEpochMs = t0 + 15_000L)
        val reportAt15s = controller.metrics.value!!
        assertEquals(10, reportAt15s.heartbeatsExpected)
        assertEquals(0, reportAt15s.heartbeatsReceived)
        assertEquals(0.0f, reportAt15s.heartbeatsDeliveredPct!!, 0.001f)
        assertEquals(2, controller.state.value.lostRidersCount)
        assertEquals(0, controller.state.value.connectedRidersCount)

        controller.stopAll()
    }
}
