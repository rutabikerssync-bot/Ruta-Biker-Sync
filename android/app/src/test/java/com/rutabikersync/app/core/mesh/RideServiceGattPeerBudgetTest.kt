package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.power.PowerProfile
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 3.1: integración del tope GATT cliente en `RideSessionController.startRide`.
 *
 * Decisión de diseño: el factory `gattHeartbeatClientFactory` se inyecta para documentar el
 * contrato (recibe el conjunto capado), pero en JVM NO se invoca: el cliente real solo se crea
 * cuando la malla es real (`!isSimulated`) y, en ese caso, `startRide` exige un manifiesto
 * firmado en caché además de capacidades BLE reales. No hay Robolectric, mocks de `BleAdvTransport`
 * ni hardware en `testStandardDebugUnitTest`, así que la verificación se hace mediante el seam
 * `lastGattTargetPeers`, que el controller actualiza con el mismo conjunto que pasaría al factory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideServiceGattPeerBudgetTest {

    private val secretKey = "rbs-gatt-budget-test-key-32b!!!!".toByteArray(Charsets.UTF_8)
    /** Nueve shortIds sintéticos de prueba del roster (el local #1 se excluye). */
    private val authorizedPeers = (2..10).map { it.toUShort() }.toSet()

    private fun newController(
        scope: CoroutineScope,
        factoryTargets: MutableList<Set<UShort>>
    ): RideSessionController = RideSessionController(
        scope = scope,
        bleTransportFactory = { null },
        simulatedMedium = SimulatedBleMedium(),
        gattHeartbeatClientFactory = { _, peers ->
            factoryTargets.add(peers)
            null
        }
    )

    @Test
    fun `perfil NORMAL con nueve peers autorizados deja ocho objetivos Gatt`() = runTest(UnconfinedTestDispatcher()) {
        val factoryTargets = mutableListOf<Set<UShort>>()
        val controller = newController(backgroundScope, factoryTargets)
        assertTrue(controller.lastGattTargetPeers.isEmpty())

        val state = controller.startRide(
            rideId = "ride-gatt-budget-normal",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            expectedPeerShortIds = authorizedPeers,
            enablePeriodicHeartbeatLoop = false
        )

        assertEquals(PowerProfile.NORMAL, controller.batteryAwareness.state.value.profile)
        assertEquals((2..9).map { it.toUShort() }.toSet(), controller.lastGattTargetPeers)
        assertEquals(8, controller.lastGattTargetPeers.size)
        // En laboratorio el factory no se invoca: la malla real requiere manifiesto + BLE.
        assertTrue(factoryTargets.isEmpty())

        controller.stopAll()
    }

    @Test
    fun `override manual de ahorro antes de startRide deja cuatro objetivos Gatt`() = runTest(UnconfinedTestDispatcher()) {
        val factoryTargets = mutableListOf<Set<UShort>>()
        val controller = newController(backgroundScope, factoryTargets)
        assertEquals(PowerProfile.POWER_SAVER, controller.setManualPowerSaverOverride(true).powerProfile)

        controller.startRide(
            rideId = "ride-gatt-budget-saver",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            expectedPeerShortIds = authorizedPeers,
            enablePeriodicHeartbeatLoop = false
        )

        assertEquals((2..5).map { it.toUShort() }.toSet(), controller.lastGattTargetPeers)
        assertEquals(4, controller.lastGattTargetPeers.size)

        controller.stopAll()
    }

    @Test
    fun `bateria critica al ocho por ciento antes de startRide deja dos objetivos Gatt`() = runTest(UnconfinedTestDispatcher()) {
        val factoryTargets = mutableListOf<Set<UShort>>()
        val controller = newController(backgroundScope, factoryTargets)
        assertEquals(
            PowerProfile.SURVIVAL,
            controller.onBatteryStateChanged(batteryPercent = 8, isCharging = false).powerProfile
        )

        controller.startRide(
            rideId = "ride-gatt-budget-survival",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            expectedPeerShortIds = authorizedPeers,
            enablePeriodicHeartbeatLoop = false
        )

        assertEquals((2..3).map { it.toUShort() }.toSet(), controller.lastGattTargetPeers)
        assertEquals(2, controller.lastGattTargetPeers.size)

        controller.stopAll()
    }

    @Test
    fun `el estado conserva los nueve peers autorizados aunque el tope Gatt sea ocho`() = runTest(UnconfinedTestDispatcher()) {
        val controller = newController(backgroundScope, mutableListOf())

        val state = controller.startRide(
            rideId = "ride-gatt-budget-roster",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            expectedPeerShortIds = authorizedPeers,
            enablePeriodicHeartbeatLoop = false
        )

        // El recorte afecta solo a los objetivos del cliente GATT, nunca al roster del relay/estado.
        assertEquals((2..10).toList(), state.expectedPeerShortIds)
        assertEquals(9, state.expectedPeerShortIds.size)
        assertEquals(8, controller.lastGattTargetPeers.size)
        assertTrue(authorizedPeers.containsAll(controller.lastGattTargetPeers))

        controller.stopAll()
    }

    @Test
    fun `el perfil se conserva al iniciar y se reinicia en el cierre explicito`() = runTest(UnconfinedTestDispatcher()) {
        val controller = newController(backgroundScope, mutableListOf())
        controller.setManualPowerSaverOverride(true)

        controller.startRide(
            rideId = "ride-gatt-budget-profile-reset",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            expectedPeerShortIds = authorizedPeers,
            enablePeriodicHeartbeatLoop = false
        )
        assertEquals(PowerProfile.POWER_SAVER, controller.batteryAwareness.state.value.profile)

        controller.stopAll()
        assertEquals(PowerProfile.NORMAL, controller.batteryAwareness.state.value.profile)
    }
}
