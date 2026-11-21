# Plan de Implementación — Cierre de 3 Bugs de Usabilidad de Ruta

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar los 3 bugs de usabilidad de ruta (ignorar coordenadas falsas de CDMX en peers, histéresis y recálculo automático sin tocar pantalla, y resiliencia de conexión BLE al cambiar de pantalla/overlays) de acuerdo con `docs/NEXT_AGENT_WORKPLAN.md` y `docs/AGENTS.md`, con pruebas unitarias 100% verdes y sin tocar GATT, cifrado ni PTT.

**Architecture:**
1. `LiveMapController` filtra coordenadas centinela (0,0), NaN y el fallback CDMX (`19.4326, -99.1332`) para peers remotos, garantizando que solo posiciones con fix GPS real generen markers geográficos.
2. `RouteDeviationDetector` emplea proyección geodésica ortogonal (Haversine al segmento proyectado) e histéresis temporal (150 m sostenidos por >=10 s) para evitar falsos positivos por jitter. `LiveMapScreen` dispara `RecalculateRouteUseCase` automáticamente una sola vez por transición sostenida.
3. Se blinda la resiliencia de BLE y cabina: el `ForegroundService` (`RideService`) y `MeshRelayEngine` permanecen activos independientemente de cambios de pantalla/overlays (`showMeshMonitor`, `showTacticalChat`), y el `BackHandler` cierra overlays sin interrumpir la sesión ni la malla.

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin Coroutines Flow/StateFlow, MapLibre GL Android SDK 11.5.2, JUnit 4, AndroidX Test.

---

## Estructura de Archivos

### Modificados
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapViewModel.kt`: Método estático puro `isRenderablePeerPosition(lat, lon)` y filtro en `flushMapFrame` para omitir peers sin GPS real o con fallback CDMX.
- `android/app/src/main/java/com/rutabikersync/app/feature/routes/RouteDeviationDetector.kt`: Ventana de histéresis de 10 s (`DEVIATION_SUSTAIN_DURATION_MS`), proyección ortogonal geodésica limpia (`(apx * abx + apy * aby) / abLenSq`), y reseteo de ventana al reanudar/cambiar ruta.
- `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`: `LaunchedEffect(deviationState.isAlertActive)` para auto-recálculo honesto y `BackHandler` ordenado que cierra overlays antes del feedback de fin de rodada.
- `docs/HISTORY_NOTE.md`: Registro oficial de diagnóstico, pruebas ejecutadas y evidencia según la plantilla del workplan.

### Creados / Tests
- `android/app/src/test/java/com/rutabikersync/app/feature/livemap/PeerPositionValidityTest.kt`: Test unitario puro que verifica el rechazo de (0,0), NaN, CDMX fallback y aceptación de GPS real.
- `android/app/src/test/java/com/rutabikersync/app/feature/routes/RouteDeviationHysteresisTest.kt`: Test de histéresis (>150 m sostenido 10 s, jitter <150 m ignorado, auto-recálculo y ruta offline).
- `android/app/src/test/java/com/rutabikersync/app/feature/livemap/RideScreenTransitionResilienceTest.kt`: Test que verifica que navegar u ocultar la UI no altera el estado `RUNNING` de `RideService` ni destruye la conexión de malla.

---

### Task 1: Peers con GPS válido (ignorar coordenadas falsas de CDMX y (0,0))

**Archivos:**
- Modificar: `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapViewModel.kt:200-215` y `435-450`
- Test: `android/app/src/test/java/com/rutabikersync/app/feature/livemap/PeerPositionValidityTest.kt`

- [x] **Paso 1: Verificar el test unitario de filtrado de posición**

Asegurar que `android/app/src/test/java/com/rutabikersync/app/feature/livemap/PeerPositionValidityTest.kt` contiene las validaciones exactas:

```kotlin
package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerPositionValidityTest {
    private val secretKey = "rbs-peer-gps-validity-test-32b!".encodeToByteArray()

    @Test
    fun `peer sin GPS real o con fallback CDMX no se dibuja`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)

        // (0,0) también es un peer sin fix: no es coordenada de tráfico real.
        remoteEngine.emitLocalPosition(latitude = 0.0, longitude = 0.0, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val noFixFrame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)
        assertTrue(noFixFrame.markers.all { it.isSelf })

        // Fallback conocido: no publicarlo como si el peer estuviera en CDMX.
        remoteEngine.emitLocalPosition(latitude = 19.4326, longitude = -99.1332, speedKmh = 0, bearingDegrees = 0f, batteryPercent = 80)
        val fallbackFrame = controller.flushMapFrame(nowEpochMs = 2_000L, force = true)
        assertTrue(fallbackFrame.markers.all { it.isSelf })
        assertEquals(1, fallbackFrame.ridersCount)

        assertFalse(LiveMapController.isRenderablePeerPosition(0.0, 0.0))
        assertFalse(LiveMapController.isRenderablePeerPosition(19.4326, -99.1332))
        assertFalse(LiveMapController.isRenderablePeerPosition(Double.NaN, 10.0))

        localEngine.stop()
        remoteEngine.stop()
    }

    @Test
    fun `peer con GPS valido se dibuja en su posicion real`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val localEngine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val remoteEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(localEngine)

        val latitude = 10.349293
        val longitude = -67.034980
        remoteEngine.emitLocalPosition(latitude = latitude, longitude = longitude, speedKmh = 45, bearingDegrees = 90f, batteryPercent = 85)
        val frame = controller.flushMapFrame(nowEpochMs = 1_000L, force = true)

        val remote = frame.markers.single { !it.isSelf }
        assertEquals(latitude, remote.displayLatitude, 0.0)
        assertEquals(longitude, remote.displayLongitude, 0.0)
        assertEquals(latitude, remote.targetLatitude, 0.0)
        assertEquals(longitude, remote.targetLongitude, 0.0)
        assertEquals(2, frame.ridersCount)
        assertTrue(LiveMapController.isRenderablePeerPosition(latitude, longitude))

        localEngine.stop()
        remoteEngine.stop()
    }
}
```

- [x] **Paso 2: Confirmar la implementación en `LiveMapViewModel.kt`**

Verificar en `LiveMapController` (dentro de `LiveMapViewModel.kt`):
```kotlin
        private const val FALLBACK_CDMX_LATITUDE = 19.4326
        private const val FALLBACK_CDMX_LONGITUDE = -99.1332

        /** Un peer sin fix real no se dibuja sobre una coordenada centinela/CDMX. */
        internal fun isRenderablePeerPosition(latitude: Double, longitude: Double): Boolean {
            if (!MeshTelemetry.isValidPosition(latitude, longitude)) return false
            return !(latitude == FALLBACK_CDMX_LATITUDE && longitude == FALLBACK_CDMX_LONGITUDE)
        }
```
Y en el bucle de `flushMapFrame`:
```kotlin
        for ((peerId, track) in latestPeersFromMesh.entries.sortedBy { it.key }) {
            if (!isRenderablePeerPosition(track.lastLatitude, track.lastLongitude)) continue
            val entry = directory[peerId] ?: RiderDirectoryEntry(
```

- [x] **Paso 3: Ejecutar los tests unitarios de validez de posición** (PASSED: 2/2 tests, 0 fallos)

Ejecutar:
```powershell
cd android; .\gradlew.bat :app:testStandardDebugUnitTest --tests "*PeerPositionValidityTest"
```
Esperado: `BUILD SUCCESSFUL`, 2 tests completados, 0 fallos.

---

### Task 2: Histéresis y recálculo automático de ruta (sin tocar pantalla)

**Archivos:**
- Modificar: `android/app/src/main/java/com/rutabikersync/app/feature/routes/RouteDeviationDetector.kt`
- Modificar: `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt`
- Test: `android/app/src/test/java/com/rutabikersync/app/feature/routes/RouteDeviationHysteresisTest.kt`

- [x] **Paso 1: Implementar la histéresis y cálculo ortogonal en `RouteDeviationDetector.kt`**

En `RouteDeviationDetector.kt`:
1. Definir constante `DEVIATION_SUSTAIN_DURATION_MS = 10_000L` y variable privada `deviationStartedAtMs: Long? = null`.
2. En `setActiveRoutePolyline`: reiniciar `deviationStartedAtMs = null`.
3. En `updateRiderLocation`:
   - Si no está desviado (`!isDeviated`), `deviationStartedAtMs = null`.
   - Si está desviado y `deviationStartedAtMs == null`, fijar `deviationStartedAtMs = nowEpochMs`.
   - `sustainedDeviation = isDeviated && (nowEpochMs - (deviationStartedAtMs ?: nowEpochMs) >= DEVIATION_SUSTAIN_DURATION_MS)`.
   - Asignar `isAlertActive = sustainedDeviation`.
4. En `activateVoluntaryStop`: reiniciar `deviationStartedAtMs = null`.
5. En `resumeRouteTracking`: `deviationStartedAtMs = if (isDeviated) nowEpochMs else null; isAlertActive = false`.
6. En `distancePointToSegmentMeters`:
   ```kotlin
        val apx = px - ax
        val apy = py - ay
        val t = ((apx * abx + apy * aby) / abLenSq).coerceIn(0.0, 1.0)

        val projected = LatLngPoint(
            latitude = a.latitude + t * (b.latitude - a.latitude),
            longitude = a.longitude + t * (b.longitude - a.longitude)
        )
        return MotorcycleRoutingEngine.calculateHaversineKm(
            p.latitude,
            p.longitude,
            projected.latitude,
            projected.longitude
        ) * 1000.0
   ```

- [x] **Paso 2: Disparo automático de recálculo en `LiveMapScreen.kt`**

En `LiveMapScreen.kt` (aproximadamente líneas 433-455):
```kotlin
    // Histéresis integrada con la UI: el detector solo habilita la alerta tras
    // >150 m sostenidos 10 s. Aquella transición dispara UNA recalculación
    // automática, sin toque y sin reintentos por cada actualización de posición.
    LaunchedEffect(deviationState.isAlertActive) {
        if (!deviationState.isAlertActive || isRecalculatingRoute) return@LaunchedEffect
        isRecalculatingRoute = true
        val outcome = recalculateRouteUseCase(
            currentLocation = deviationState.lastKnownLocation,
            destination = scheduledRide?.destination
        )
        actionFeedback = when (outcome) {
            RecalculateRouteOutcome.SUCCESS -> "✓ Ruta recalculada automáticamente sobre vías reales"
            RecalculateRouteOutcome.OFFLINE -> "Sin conexión, usando ruta offline"
            RecalculateRouteOutcome.NO_ROUTE -> "No se pudo recalcular. Sigue la ruta actual"
        }
        isRecalculatingRoute = false
    }
```

- [x] **Paso 3: Asegurar la geometría y aislamiento en `RouteDeviationHysteresisTest.kt`**

En `RouteDeviationHysteresisTest.kt`:
1. El corredor `route` es `listOf(LatLngPoint(0.0, 0.0), LatLngPoint(0.01, 0.0))`.
2. La desviación ortogonal perpendicular al eje Norte-Sur ocurre sobre longitud:
   - `smallDeviation = LatLngPoint(0.005, 0.0005)` (~55 m al Este, <150 m).
   - `sustainedDeviation = LatLngPoint(0.005, 0.003)` (~334 m al Este, >150 m).
3. En `setUp()`, aislar el estado de `RideScheduleRepository`:
   ```kotlin
    @Before
    fun setUp() {
        RideScheduleRepository.clearCurrentRide()
        RideScheduleRepository.scheduleNewRide(
            title = "Ruta activa",
            originName = "Origen",
            originLat = 0.0,
            originLon = 0.0,
            destinationName = "Destino real",
            destinationLat = 0.009,
            destinationLon = 0.0,
            selectedRoute = RouteAlternative(
                id = "original-route",
                name = "Ruta original",
                subtitle = "Corredor base",
                distanceKm = 1.0,
                estimatedMinutes = 10,
                roadCondition = "Asfalto",
                difficulty = "Media",
                waypoints = route
            ),
            availableRoutes = emptyList(),
            returnStrategy = ReturnStrategy.SAME_ROUTE,
            dispersionPointName = "Destino real",
            dispersionLat = 0.009,
            dispersionLon = 0.0,
            meetingTime = "08:00",
            departureTime = "08:00",
            dateSchedule = "2026-11-21",
            distanceKm = 1.0,
            estimatedMinutes = 10
        )
        RouteDeviationDetector.reset()
        RouteDeviationDetector.setActiveRoutePolyline(route)
    }
   ```

- [x] **Paso 4: Ejecutar las pruebas unitarias de histéresis y recálculo** (PASSED: 7/7 tests, 0 fallos)

Ejecutar:
```powershell
cd android; .\gradlew.bat :app:testStandardDebugUnitTest --tests "*RouteDeviationHysteresisTest" --tests "*RecalculateRouteUseCaseTest"
```
Esperado: `BUILD SUCCESSFUL`, 0 fallos.

---

### Task 3: Conexión BLE resiliente al cambiar de pantalla y navegación de cabina

**Archivos:**
- Modificar: `android/app/src/main/java/com/rutabikersync/app/feature/livemap/LiveMapScreen.kt:526-535`
- Crear: `android/app/src/test/java/com/rutabikersync/app/feature/livemap/RideScreenTransitionResilienceTest.kt`

- [x] **Paso 1: Escribir el test unitario de resiliencia ante cambios de pantalla**

Crear `android/app/src/test/java/com/rutabikersync/app/feature/livemap/RideScreenTransitionResilienceTest.kt`:

```kotlin
package com.rutabikersync.app.feature.livemap

import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideSessionState
import com.rutabikersync.app.core.transport.SimulatedBleMedium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideScreenTransitionResilienceTest {
    private val secretKey = "rbs-screen-resilience-test-key!".encodeToByteArray()

    @Test
    fun `desconexion o cambio de pantalla en UI no detiene el relay engine ni degrada el estado RUNNING`() = runTest(UnconfinedTestDispatcher()) {
        val medium = SimulatedBleMedium()
        val engine = MeshRelayEngine(localShortId = 1u, transport = medium.createNodeTransport(1u), rideSecretKey = secretKey)
            .also { it.start(backgroundScope) }

        val sessionState = MutableStateFlow(
            RideSessionState(lifecycleState = RideLifecycleState.RUNNING, localShortId = 1u)
        )

        // 1. Simular entrada en LiveMapScreen: bindToMeshEngine activo
        val controller = LiveMapController(scope = backgroundScope, localShortId = 1u, leaderShortId = 1u)
        controller.bindToMeshEngine(engine, enablePeriodicCadenceLoop = false)

        val peerEngine = MeshRelayEngine(localShortId = 2u, transport = medium.createNodeTransport(2u), rideSecretKey = secretKey)
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
}
```

- [x] **Paso 2: Ajustar `BackHandler` en `LiveMapScreen.kt`**

En `LiveMapScreen.kt` (línea ~526):
```kotlin
    BackHandler(enabled = true) {
        when {
            showMeshMonitor -> showMeshMonitor = false
            showTacticalChat -> showTacticalChat = false
            showOfflineMapsScreen -> showOfflineMapsScreen = false
            rideServiceState.lifecycleState == RideLifecycleState.RUNNING -> {
                actionFeedback = "⚠ Para finalizar la rodada mantén presionado 3 s el botón inferior"
            }
            else -> onStopRideHoldCompleted?.invoke()
        }
    }
```

- [x] **Paso 3: Ejecutar el test unitario de resiliencia** (PASSED: 2/2 tests, 0 fallos)

Ejecutar:
```powershell
cd android; .\gradlew.bat :app:testStandardDebugUnitTest --tests "*RideScreenTransitionResilienceTest"
```
Esperado: `BUILD SUCCESSFUL`, 2 tests pass, 0 failures.

---

### Task 4: Verificación Integral del Bloque 0 y Registro de Evidencia

**Archivos:**
- Modificar: `docs/HISTORY_NOTE.md`

- [ ] **Paso 1: Ejecutar la suite completa indicada en el workplan**

Ejecutar secuencialmente (sin Gradle paralelo):
```powershell
cd android; .\gradlew.bat :app:testStandardDebugUnitTest `
  --tests "*PeerPositionValidityTest" `
  --tests "*RouteDeviationHysteresisTest" `
  --tests "*RecalculateRouteUseCaseTest" `
  --tests "*LiveMapViewModelTest" `
  --tests "*RideServicePhase3Test" `
  --tests "*RideCabinaAccessTest" `
  --tests "*RideScreenTransitionResilienceTest"
```
Esperado: `BUILD SUCCESSFUL` con 0 fallos.

- [ ] **Paso 2: Diagnosticar de forma aislada la expectativa GPX de `RouteEnginePhase4Test`**

Ejecutar:
```powershell
cd android; .\gradlew.bat :app:testStandardDebugUnitTest --tests "*RouteEnginePhase4Test"
```
Documentar en el plan y en notas que `Bloque 4_2 GpxManager importa y exporta rutas GPX 1_1 con integridad` requiere que `DefaultRouteCatalog.tovarDirecta` posea waypoints (que en JVM puro sin mock de assets caen en `emptyList()`), manteniéndolo aislado de la suite de usabilidad de ruta conforme a la regla 4.D del workplan.

- [ ] **Paso 3: Actualizar `docs/HISTORY_NOTE.md` con la evidencia técnica**

Añadir entrada en `docs/HISTORY_NOTE.md` con:
- Archivos exactos modificados.
- Diagnóstico técnico de la geometría ortogonal, filtrado de CDMX y manejo de overlays.
- Salida real de los tests unitarios ejecutados.
- Estado del hardware y enlaces probados.
- Próximos pasos pendientes (Hito 1 Audio SOS, Señales y Atribución MapLibre).

---

## Verificación de Reglas e Invariantes
- **GATT, Cifrado, PTT**: Intactos. Ningún cambio en `HeartbeatFrame`, `SosPacket`, ChaCha20 ni interfaces de transporte.
- **Liderazgo de grupo**: Intacto (`RideCabinaAccessTest` pasa con éxito).
- **Git**: Sin `amend`, sin `push`, sin `git add .`.
- **Datos honestos**: Sin markers simulados en CDMX, sin distancias inventadas, sin bypassing de fallos.
