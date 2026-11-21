package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pruebas unitarias JVM del Bloque 3.3 (`LiveMapController` + `RiderMarker` + `MapControls`):
 * 1. Convoy simulado de 5 riders sobre `SimulatedBleMedium`, cadencia de 1 s, abreviaturas siempre visibles (C)
 *    y privacidad según relación R4.7/R4.8 (`ALLIED_STRANGER` -> `"R#5"`).
 * 2. Interpolación lineal de posiciones (`interpolateMarkers`) y estados de marcador (`ON_ROUTE`,
 *    `OFF_ROUTE_DEVIATED`, `OFF_MESH_LOST`, `SOS_ACTIVE`) con símbolo + texto (ICONS.md §1 Regla 2).
 * 3. Pin del líder fijo + botón "Seguir al líder" por 10 segundos (B), PMTiles offline (A) y Modo Guantes ≥ 72 dp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModelTest {

    private val secretKey = "rbs-phase3-livemap-secret-32b!!!".encodeToByteArray()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // Evita que corrutinas de un test sigan usando Main cuando el siguiente
        // test cambia el dispatcher (fuga detectada en la suite completa).
        testDispatcher.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `Bloque 3_3 convoy de 5 riders actualiza a cadencia 1s con abreviatura siempre visible y privacidad R4_7`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u, 5u)
        medium.connectChain(nodeIds)

        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey
            ).also { it.start(backgroundScope) }
        }

        val mapController = LiveMapController(
            scope = backgroundScope,
            localShortId = 1u,
            leaderShortId = 1u,
            // FIX privacidad: los riders 1-4 son del mismo club y el 5 de otro;
            // sin groupId la regla redactaría todos los perfiles por defecto.
            initialDirectory = LiveMapController.defaultConvoyDirectory().map { entry ->
                entry.copy(
                    groupId = if (entry.shortId == 5u.toUShort()) "g-coyotes" else "g-halcones"
                )
            }
        )
        mapController.setLocalGroupId("g-halcones")
        mapController.bindToMeshEngine(engines.getValue(1u), enablePeriodicCadenceLoop = false)
        mapController.updateLocalRiderPose(latitude = 19.4326, longitude = -99.1332, speedKmh = 80)

        // Frame inicial en t = 1_000 ms (solo nodo propio antes de que hablen los demás)
        val initialFrame = mapController.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertEquals(1, initialFrame.markers.size)
        assertEquals(1, initialFrame.ridersCount)
        assertEquals(LiveMapConnectivityState.ALONE, initialFrame.connectivityState)

        // Los otros 4 riders del convoy emiten posición hacia el líder
        engines.getValue(2u).emitLocalPosition(19.4310, -99.1332, 78, 180f, 85, ttl = 4)
        engines.getValue(3u).emitLocalPosition(19.4295, -99.1332, 76, 180f, 82, ttl = 4)
        engines.getValue(4u).emitLocalPosition(19.4280, -99.1332, 75, 180f, 80, ttl = 4)
        engines.getValue(5u).emitLocalPosition(19.4265, -99.1332, 74, 180f, 79, ttl = 4)

        // Si se solicita un frame a los 400 ms (t = 1_400 ms), se respeta la cadencia de 1 s y no se recalcula aún
        val throttledFrame = mapController.flushMapFrame(nowEpochMs = 1_400L, force = false)
        assertEquals(1, throttledFrame.markers.size)

        // Al cumplirse 1 s (t = 2_000 ms), el mapa vuelca los 5 riders conectados
        val frameAt1s = mapController.flushMapFrame(nowEpochMs = 2_000L, force = false)
        assertEquals(5, frameAt1s.markers.size)
        assertEquals(4, frameAt1s.connectedNodesCount)
        // FIX contador HUD: 4 peers remotos + 1 local = 5 riders.
        assertEquals(5, frameAt1s.ridersCount)
        assertEquals(LiveMapConnectivityState.MESH_CONNECTED, frameAt1s.connectivityState)

        val byId = frameAt1s.markers.associateBy { it.shortId }

        // Decisión 3.2 (C): Nombre abreviado siempre visible + nombre completo al tocar
        assertEquals("J.M.", byId.getValue(1u).abbreviatedLabel)
        assertEquals("Juan Martínez (El Capi)", byId.getValue(1u).expandedDisplayName)
        assertEquals(RiderRideRole.LEADER, byId.getValue(1u).rideRole)

        assertEquals("C.V.", byId.getValue(2u).abbreviatedLabel)
        assertEquals(RiderRideRole.SCOUT, byId.getValue(2u).rideRole)

        assertEquals("M.T.", byId.getValue(3u).abbreviatedLabel)
        assertEquals(RiderRideRole.CAPTAIN, byId.getValue(3u).rideRole)

        assertEquals("E.G.", byId.getValue(4u).abbreviatedLabel)
        assertEquals(RiderRideRole.SWEEP, byId.getValue(4u).rideRole)

        // Regla R4.7/R4.8 reforzada: Rider 5 es de otro club -> perfil redactado,
        // avatar genérico y "Piloto #5" tanto en la etiqueta como al expandir.
        assertEquals("Piloto #5", byId.getValue(5u).abbreviatedLabel)
        assertEquals("Piloto #5", byId.getValue(5u).expandedDisplayName)
        assertTrue(byId.getValue(5u).profileRedacted)

        engines.values.forEach { it.stop() }
    }

    @Test
    fun `Bloque 3_3 interpolacion de posiciones evita saltos y detecta estados Desviado FueraDeMesh y SOS`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u, 3u, 4u)
        medium.connectChain(nodeIds)

        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey
            ).also { it.start(backgroundScope) }
        }

        val mapController = LiveMapController(
            scope = backgroundScope,
            localShortId = 1u,
            leaderShortId = 1u
        )
        mapController.bindToMeshEngine(engines.getValue(1u), enablePeriodicCadenceLoop = false)
        mapController.updateLocalRiderPose(latitude = 19.4300, longitude = -99.1332, speedKmh = 60)

        // Posición inicial del Rider 2 en lat = 19.4300
        engines.getValue(2u).emitLocalPosition(19.4300, -99.1332, 70, 180f, 90, ttl = 4)
        mapController.flushMapFrame(nowEpochMs = 1_000L, force = true)

        // Siguiente paquete del Rider 2 avanza a lat = 19.4400; Rider 3 se desvía >150 m del corredor (-99.1200)
        engines.getValue(2u).emitLocalPosition(19.4400, -99.1332, 75, 180f, 89, ttl = 4)
        engines.getValue(3u).emitLocalPosition(19.4300, -99.1200, 65, 90f, 85, ttl = 4)
        mapController.flushMapFrame(nowEpochMs = 2_000L, force = false)

        // Interpolación a mitad del intervalo (alpha = 0.5f) -> latitud de Rider 2 debe ser exactamente 19.4350
        val midFrame = mapController.interpolateMarkers(alpha = 0.5f)
        val rider2Mid = midFrame.markers.first { it.shortId == 2u.toUShort() }
        assertEquals(19.4300, rider2Mid.prevLatitude, 1e-6)
        assertEquals(19.4400, rider2Mid.targetLatitude, 1e-6)
        assertEquals(19.4350, rider2Mid.displayLatitude, 1e-6)
        assertEquals(RiderRouteStatus.ON_ROUTE, rider2Mid.routeStatus)
        assertEquals("✓ En ruta", rider2Mid.routeStatus.symbolAndLabel)

        // Rider 3 está desviado del corredor (>150 m) -> OFF_ROUTE_DEVIATED con símbolo ⚠
        val rider3 = midFrame.markers.first { it.shortId == 3u.toUShort() }
        assertEquals(RiderRouteStatus.OFF_ROUTE_DEVIATED, rider3.routeStatus)
        assertEquals("⚠ Desviado", rider3.routeStatus.symbolAndLabel)

        // Rider 4 emite un paquete y luego pierde señal por >15 s -> OFF_MESH_LOST ("✕ Sin señal")
        val nowMs = System.currentTimeMillis()
        engines.getValue(4u).emitLocalPosition(19.4250, -99.1332, 60, 180f, 80, ttl = 4)
        engines.getValue(1u).evaluatePeerTimeouts(nowEpochMs = nowMs + 20_000L)
        val afterTimeoutFrame = mapController.flushMapFrame(nowEpochMs = nowMs + 20_000L, force = true)
        val rider4Lost = afterTimeoutFrame.markers.first { it.shortId == 4u.toUShort() }
        assertEquals(RiderRouteStatus.OFF_MESH_LOST, rider4Lost.routeStatus)
        assertEquals("✕ Sin señal", rider4Lost.routeStatus.symbolAndLabel)
        // FIX contador HUD: los peers sin señal no cuentan; queda solo el local.
        assertEquals(1, afterTimeoutFrame.ridersCount)

        // Rider 2 activa SOS -> actualización inmediata sin esperar al tick de 1 s
        engines.getValue(2u).emitLocalPosition(19.4410, -99.1332, 0, 180f, 88, isSos = true, ttl = 4)
        val sosFrame = mapController.uiState.value
        val rider2Sos = sosFrame.markers.first { it.shortId == 2u.toUShort() }
        assertEquals(RiderRouteStatus.SOS_ACTIVE, rider2Sos.routeStatus)
        assertEquals("SOS ACTIVO", rider2Sos.routeStatus.symbolAndLabel)

        engines.values.forEach { it.stop() }
    }

    @Test
    fun `Bloque 3_3 Pin del lider fijo y boton Seguir al lider centra por 10s con PMTiles offline y Modo Guantes`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        medium.connectChain(listOf(1u, 2u))

        val leaderEngine = MeshRelayEngine(
            localShortId = 1u,
            transport = medium.createNodeTransport(1u),
            rideSecretKey = secretKey
        ).also { it.start(backgroundScope) }

        val memberEngine = MeshRelayEngine(
            localShortId = 2u,
            transport = medium.createNodeTransport(2u),
            rideSecretKey = secretKey
        ).also { it.start(backgroundScope) }

        // El miembro (shortId = 2u) observa el mapa; el líder es shortId = 1u
        val memberMapController = LiveMapController(
            scope = backgroundScope,
            localShortId = 2u,
            leaderShortId = 1u
        )
        memberMapController.bindToMeshEngine(memberEngine, enablePeriodicCadenceLoop = false)
        memberMapController.updateLocalRiderPose(latitude = 19.4100, longitude = -99.1332, speedKmh = 70)

        // El líder (1u) emite su posición en (19.4500, -99.1332)
        leaderEngine.emitLocalPosition(19.4500, -99.1332, 82, 180f, 92, ttl = 4)
        val frame = memberMapController.flushMapFrame(nowEpochMs = 5_000L, force = true)

        // Decisión 3.1 (A): PMTiles offline pre-descargado + atribución OSM visible
        assertEquals("asset://tiles/sample_corridor.pmtiles", frame.pmtilesAssetUri)
        assertEquals("© OpenStreetMap contributors", frame.osmAttribution)
        val sampleTileFile = File("src/main/assets/tiles/sample_corridor.pmtiles")
        assertTrue("Debe existir sample_corridor.pmtiles en assets/tiles", sampleTileFile.exists())

        // FIX distancia al líder: se mide desde la posición real del líder, no desde un
        // destino ni desde un default. (19.41 -> 19.45) son ~4.4 km.
        val memberMarker = frame.markers.first { it.shortId == 2u.toUShort() }
        val leaderMarker = frame.markers.first { it.shortId == 1u.toUShort() }
        assertTrue(
            "Distancia al líder coherente: $memberMarker.distanceToLeaderMeters",
            memberMarker.distanceToLeaderMeters in 4_400..4_500
        )
        assertEquals(0, leaderMarker.distanceToLeaderMeters)

        // Decisión 3.3 (B): El pin del líder está fijo en (19.4500, -99.1332) aunque el miembro esté en modo FREE
        assertEquals(CameraMode.FREE, frame.cameraMode)
        assertNotNull(frame.leaderPin)
        assertEquals(19.4500, frame.leaderPin!!.latitude, 1e-4)
        assertEquals("J.M.", frame.leaderPin!!.abbreviatedLabel)

        // Al tocar "Seguir al líder" en t = 10_000 ms, centra la cámara en el líder durante 10 s
        val followingState = memberMapController.followLeader(nowEpochMs = 10_000L, scheduleAutoRevert = false)
        assertEquals(CameraMode.FOLLOWING_LEADER, followingState.cameraMode)
        assertEquals(19.4500, followingState.cameraLatitude, 1e-4)
        assertEquals(10, followingState.followLeaderRemainingSeconds)

        // A los 5 s (t = 15_000 ms) sigue centrada en el líder
        val midFollow = memberMapController.flushMapFrame(nowEpochMs = 15_000L, force = true)
        assertEquals(CameraMode.FOLLOWING_LEADER, midFollow.cameraMode)
        assertEquals(5, midFollow.followLeaderRemainingSeconds)

        // A los 10 s (t = 20_000 ms) vuelve automáticamente a navegación libre (CameraMode.FREE)
        val expiredFollow = memberMapController.evaluateCameraTimeout(nowEpochMs = 20_000L)
        assertEquals(CameraMode.FREE, expiredFollow.cameraMode)
        assertEquals(0, expiredFollow.followLeaderRemainingSeconds)
        // Y el pin del líder permanece visible en el mapa
        assertNotNull(expiredFollow.leaderPin)

        // Regla Modo Guantes: botones >= 72 dp y máximo 3 acciones flotantes principales
        assertTrue(GlovesModePolicy.MIN_TOUCH_TARGET_DP >= 72f)
        assertEquals(3, GlovesModePolicy.visiblePrimaryActions().size)

        leaderEngine.stop()
        memberEngine.stop()
    }

    @Test
    fun `Paso 2 Cabina de Rodada Heritage valida politicas de guantes y acciones primarias de cabina`() {
        // Validación de dimensiones mínimas de toque >= 72 dp para guantes de moto
        assertTrue(com.rutabikersync.app.ui.theme.BikerDimens.GlovesMinTouchTarget.value >= 72f)
        assertEquals(40f, com.rutabikersync.app.ui.theme.BikerDimens.GlovesIconSize.value, 0.01f)

        // Exactamente 3 acciones de cabina esenciales para seguridad en movimiento
        val actions = GlovesModePolicy.visiblePrimaryActions()
        assertEquals(3, actions.size)
        assertTrue(actions.contains(GlovesPrimaryAction.TALK_PTT))
        assertTrue(actions.contains(GlovesPrimaryAction.SOS))
        assertTrue(actions.contains(GlovesPrimaryAction.QUICK_SIGNALS))
    }

    @Test
    fun `sin fix GPS o con coordenada centinela no se calcula distancia al lider`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveMapController(
            scope = backgroundScope,
            localShortId = 2u,
            leaderShortId = 1u
        )

        val beforeFix = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertFalse(beforeFix.gpsFixed)
        assertNull(beforeFix.leaderPin)
        assertTrue(beforeFix.markers.all { it.distanceToLeaderMeters == null })

        // (0,0) es un centinela: no debe convertirse en una posición propia válida.
        controller.updateLocalRiderPose(latitude = 0.0, longitude = 0.0, speedKmh = 0)
        val afterSentinel = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertFalse(afterSentinel.gpsFixed)
        assertTrue(afterSentinel.markers.all { it.distanceToLeaderMeters == null })
    }

    @Test
    fun `contador de convoy sigue a los marcadores activos incluido el local`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val nodeIds = listOf<UShort>(1u, 2u)
        medium.connectChain(nodeIds)
        val engines = nodeIds.associateWith { id ->
            MeshRelayEngine(
                localShortId = id,
                transport = medium.createNodeTransport(id),
                rideSecretKey = secretKey
            ).also { it.start(backgroundScope) }
        }
        val controller = LiveMapController(
            scope = backgroundScope,
            localShortId = 1u,
            leaderShortId = 1u
        )
        controller.bindToMeshEngine(engines.getValue(1u), enablePeriodicCadenceLoop = false)
        controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        // Sin peers: 1 marcador local, fallback singular.
        val alone = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertEquals(1, alone.markers.size)
        assertEquals(1, alone.ridersCount)
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(alone.ridersCount))

        // Un peer remoto enlazado: 2 marcadores, HUD en plural.
        engines.getValue(2u).emitLocalPosition(10.349300, -67.034904, 20, 180f, 80, ttl = 4)
        val twoPeers = controller.flushMapFrame(nowEpochMs = 2_000L, force = false)
        assertEquals(2, twoPeers.markers.size)
        assertEquals(2, twoPeers.ridersCount)
        assertEquals("2 riders", ConvoyRiderCountFormatter.format(twoPeers.ridersCount))

        // El peer se pierde: su marcador fantasma sigue visible, pero el HUD baja a 1.
        val nowMs = System.currentTimeMillis()
        engines.getValue(1u).evaluatePeerTimeouts(nowEpochMs = nowMs + 20_000L)
        val lostPeer = controller.flushMapFrame(nowEpochMs = nowMs + 20_000L, force = true)
        assertEquals(2, lostPeer.markers.size)
        assertEquals(1, lostPeer.ridersCount)
        assertEquals("1 rider", ConvoyRiderCountFormatter.format(lostPeer.ridersCount))

        engines.values.forEach { it.stop() }
    }

    @Test
    fun `seleccion de marcador conmuta y sobrevive a flushMapFrame`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveMapController(
            scope = backgroundScope,
            localShortId = 1u,
            leaderShortId = 1u,
            initialDirectory = emptyList()
        )
        controller.updateLocalRiderPose(latitude = 10.349246, longitude = -67.034904, speedKmh = 0)

        assertNull(controller.uiState.value.selectedRiderShortId)

        val selected = controller.selectRiderMarker(1u)
        assertEquals(1u.toUShort(), selected.selectedRiderShortId)

        // La selección debe preservarse entre frames mientras el marker siga en pantalla.
        val frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertEquals(1, frame.markers.size)
        assertEquals(1u.toUShort(), controller.uiState.value.selectedRiderShortId)

        // Un segundo toque sobre el mismo avatar cierra el detalle.
        assertNull(controller.selectRiderMarker(1u).selectedRiderShortId)

        // Tocar el mapa vacío también cierra el detalle (onSelectRider(null)).
        controller.selectRiderMarker(1u)
        assertNull(controller.selectRiderMarker(null).selectedRiderShortId)
    }
}
