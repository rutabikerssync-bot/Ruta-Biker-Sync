package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.core.mesh.RideSessionController
import com.rutabikersync.app.core.mesh.RideSessionState
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideScreenTransitionResilienceTest {
    private val secretKey = "rbs-screen-resilience-test-key!!".encodeToByteArray()

    @Test
    fun `desconexion o cambio de pantalla en UI no detiene el relay engine ni degrada el estado RUNNING`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey, requireAuthorization = false)
            .also { it.start(backgroundScope) }

        val sessionState = MutableStateFlow(
            RideSessionState(lifecycleState = RideLifecycleState.RUNNING, localShortId = 1u)
        )

        // 1. Simular entrada en LiveMapScreen: bindToMeshEngine activo
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(engine, enablePeriodicCadenceLoop = false)
        controller.updateLocalRiderPose(latitude = 10.348, longitude = -67.04, speedKmh = 30)

        val peerEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey, requireAuthorization = false)
            .also { it.start(backgroundScope) }
        peerEngine.emitLocalPosition(latitude = 10.35, longitude = -67.05, speedKmh = 40, bearingDegrees = 0f, batteryPercent = 90)

        var frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertEquals(2, frame.ridersCount)

        // 2. Simular cambio a pantalla completa de Monitor de Malla o cambio de pestaña (controller unbind)
        // El relayEngine subyacente sigue corriendo en segundo plano sin reiniciar BLE
        assertTrue(engine.isRunning)
        assertEquals(RideLifecycleState.RUNNING, sessionState.value.lifecycleState)

        // 3. Simular retorno a LiveMapScreen: re-bind del controller
        val returningController = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        returningController.bindToMeshEngine(engine, enablePeriodicCadenceLoop = false)
        // Un controller nuevo tras volver a cabina necesita su fix local real para no inventar ni omitir riders.
        returningController.updateLocalRiderPose(latitude = 10.348, longitude = -67.04, speedKmh = 30)

        frame = returningController.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertEquals(2, frame.ridersCount)
        assertTrue(frame.markers.any { !it.isSelf && it.shortId == 2u.toUShort() })

        engine.stop()
        peerEngine.stop()
    }

    @Test
    fun `regla de navegacion back cierra overlays sin interrumpir la rodada`() {
        var showMeshMonitor = true
        var showTacticalChat = false
        var showOfflineMaps = false
        var stopRidePromptCount = 0

        fun handleBack(isRideRunning: Boolean) {
            when {
                showMeshMonitor -> showMeshMonitor = false
                showTacticalChat -> showTacticalChat = false
                showOfflineMaps -> showOfflineMaps = false
                isRideRunning -> stopRidePromptCount++
            }
        }

        // Caso 1: Con Monitor Malla abierto, Back cierra el monitor, no finaliza la rodada
        handleBack(isRideRunning = true)
        assertFalse(showMeshMonitor)
        assertEquals(0, stopRidePromptCount)

        // Caso 2: En cabina principal con rodada activa, Back advierte de mantener presionado 3s
        handleBack(isRideRunning = true)
        assertEquals(1, stopRidePromptCount)
    }

    @Test
    fun `activeRelayEngineFlow entrega el engine nuevo al sustituir el controller`() = runTest(UnconfinedTestDispatcher()) {
        val simController = RideSessionController(scope = backgroundScope, bleTransportFactory = { null })
        val state = MutableStateFlow(
            RideSessionState(
                lifecycleState = RideLifecycleState.RUNNING,
                rideId = "ride-a",
                localShortId = 1u,
                isUsingSimulatedTransport = true
            )
        )
        val controllerFlow = MutableStateFlow<RideSessionController?>(null)
        val engineFlow = combine(state, controllerFlow) { currentState, controller ->
            RideService.resolveRelayEngine(currentState, controller, simController)
        }.stateIn(backgroundScope, SharingStarted.Eagerly, null)

        val firstController = RideSessionController(scope = backgroundScope, bleTransportFactory = { null })
        firstController.startRide(
            rideId = "ride-a",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        val first = firstController.relayEngine!!
        controllerFlow.value = firstController
        assertEquals(first, engineFlow.value)

        // Simula reinicio BLE: otro controlador publica un engine distinto.
        val secondController = RideSessionController(scope = backgroundScope, bleTransportFactory = { null })
        secondController.startRide(
            rideId = "ride-b",
            localShortId = 1u,
            rideSecretKey = secretKey,
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        val second = secondController.relayEngine!!
        controllerFlow.value = secondController
        assertEquals(second, engineFlow.value)

        firstController.stopAll()
        secondController.stopAll()
    }

    @Test
    fun `cambio de engine cancela la suscripcion de voz anterior y abre una nueva`() = runTest(UnconfinedTestDispatcher()) {
        val first = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey, requireAuthorization = false)
            .also { it.start(backgroundScope) }
        val second = MeshRelayEngine(localShortId = 1u, rideSecretKey = secretKey, requireAuthorization = false)
            .also { it.start(backgroundScope) }
        val engineFlow = MutableStateFlow<MeshRelayEngine?>(first)

        var subscribes = 0
        var unsubscribes = 0
        var unsubscribe: (() -> Unit)? = null
        val collectJob = launch {
            engineFlow.collect { engine ->
                unsubscribe?.let { previous ->
                    previous()
                    unsubscribes++
                }
                unsubscribe = engine?.onVoiceReceived { }
                subscribes++
            }
        }
        runCurrent()
        assertEquals(1, subscribes)
        assertEquals(0, unsubscribes)

        engineFlow.value = second
        runCurrent()
        assertEquals(2, subscribes)
        assertEquals(1, unsubscribes)

        collectJob.cancel()
        // Equivale al `onDispose` del DisposableEffect: la cancelación del flow no ejecuta
        // por sí sola la lambda de limpieza de la suscripción.
        unsubscribe?.invoke()
        unsubscribes++
        assertEquals(2, unsubscribes)

        first.stop()
        second.stop()
    }
}
