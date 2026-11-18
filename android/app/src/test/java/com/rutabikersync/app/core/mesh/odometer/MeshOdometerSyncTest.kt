package com.rutabikersync.app.core.mesh.odometer

import com.rutabikersync.app.core.gamification.GamificationEventType
import com.rutabikersync.app.core.gamification.GamificationSyncEngine
import com.rutabikersync.app.core.gamification.InMemoryPendingGamificationEventDao
import com.rutabikersync.app.core.gamification.PendingGamificationEvent
import com.rutabikersync.app.core.gamification.TelemetryApiClient
import com.rutabikersync.app.core.mesh.HeartbeatFrame
import com.rutabikersync.app.core.mesh.MeshHeader
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.core.mesh.RideSessionController
import com.rutabikersync.app.core.mesh.RideSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite de Pruebas Unitarias para el Odómetro de Malla, Persistencia en Room y Sincronización Diferida
 * (Fase 6 · Hito 5 · Etapa 3).
 *
 * Cobertura de Pruebas:
 * 1. `testOdometerAccumulatesKmWhenPeerHeartbeatWithin10s`: Acumula distancia si hay presencia mesh (<10s).
 * 2. `testOdometerFreezesWhenNoPeerHeartbeatsExceed10s`: Se congela si no hay heartbeats en >10s.
 * 3. `testOdometerResumesWhenPeerReconnects`: Reanuda acumulación al reencontrar compañeros sin sumar trayecto aislado.
 * 4. `testOdometerDoesNotAccumulateWhenStationary`: En paradas de convoy (semáforos 2 min), acumula 0 km aun con heartbeats activos.
 * 5. `testOdometerIgnoresGpsJitterWhenThresholdNotMet`: Descarta oscilaciones GPS menores a 3 metros.
 * 6. `testAntiCheatSolitaryRideAccumulatesZeroMeshKm`: Un piloto rodando en solitario acumula estrictamente 0 km de convoy.
 * 7. `testRoomInsertAndReadUnsyncedEvents`: Inserción y consulta en cola Room de eventos no sincronizados.
 * 8. `testRoomMarkAsSyncedAndDeleteSynced`: Marcado de sincronización y purga de eventos completados.
 * 9. `testRoomIsolationMultipleRides`: Aislamiento estricto de eventos por `rideId`.
 * 10. `testSyncEngineSuccessfulUploadMarksAndDeletes`: Subida exitosa a API, marcado y purga de cola.
 * 11. `testSyncEngineFailurePreservesEventsForRetry`: Falla de red preserva eventos para reintento con backoff exponencial.
 * 12. `testEndToEndRideMeshOdometerToRoomAndSync`: Simulación completa de rodada, acumulación, cierre, persistencia y sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshOdometerSyncTest {

    // Coordenadas de prueba (Ciudad de México)
    private val latAngel = 19.4270
    private val lonAngel = -99.1677

    // 1 km al norte aproximadamente (+0.0090 grados latitud)
    private val latReforma1Km = 19.4360
    private val lonReforma1Km = -99.1677

    // 2 km al norte aproximadamente (+0.0180 grados latitud)
    private val latReforma2Km = 19.4450
    private val lonReforma2Km = -99.1677

    @Test
    fun testOdometerAccumulatesKmWhenPeerHeartbeatWithin10s() {
        val odometer = MeshOdometer(heartbeatTimeoutMs = 10_000L)
        val t0 = 1_000L

        // Peer 2 emite heartbeat en t = 1000 ms
        odometer.onPeerHeartbeatReceived(peerShortId = 2u, nowMs = t0)
        assertFalse("El odómetro debe descongelarse al recibir heartbeat", odometer.isFrozen)

        // Ubicación inicial
        odometer.onLocationUpdate(latAngel, lonAngel, nowMs = t0)
        assertEquals(0.0, odometer.totalAccumulatedKm, 0.001)

        // Movimiento de 1 km a los 5 segundos (t = 6000 ms, ventana de 5s < 10s activa)
        val delta = odometer.onLocationUpdate(latReforma1Km, lonReforma1Km, nowMs = t0 + 5_000L)
        assertTrue("Debe registrar un desplazamiento mayor a 900 metros", delta > 900.0)
        assertTrue("Total acumulado debe ser cercano a 1.0 km", odometer.totalAccumulatedKm in 0.95..1.05)
        assertFalse(odometer.isFrozen)
    }

    @Test
    fun testOdometerFreezesWhenNoPeerHeartbeatsExceed10s() {
        val odometer = MeshOdometer(heartbeatTimeoutMs = 10_000L)
        val t0 = 1_000L

        // Heartbeat inicial en t0
        odometer.onPeerHeartbeatReceived(peerShortId = 2u, nowMs = t0)
        odometer.onLocationUpdate(latAngel, lonAngel, nowMs = t0)

        // Avance en convoy en t = 4000 ms (delta ~1 km)
        odometer.onLocationUpdate(latReforma1Km, lonReforma1Km, nowMs = t0 + 4_000L)
        val kmAtConvoy = odometer.totalAccumulatedKm
        assertTrue(kmAtConvoy > 0.9)

        // Transcurren 15 segundos sin ningún heartbeat de compañeros (t = 19_000 ms, delta = 18s > 10s)
        // El piloto avanza otro kilómetro en solitario
        val deltaIsolated = odometer.onLocationUpdate(latReforma2Km, lonReforma2Km, nowMs = t0 + 19_000L)

        assertEquals("No debe acumular metros mientras está aislado", 0.0, deltaIsolated, 0.0001)
        assertEquals("El odómetro debe congelar el kilometraje previo", kmAtConvoy, odometer.totalAccumulatedKm, 0.0001)
        assertTrue("El odómetro debe marcar estado congelado", odometer.isFrozen)
        assertEquals(1, odometer.isolatedCount)
    }

    @Test
    fun testOdometerResumesWhenPeerReconnects() {
        val odometer = MeshOdometer(heartbeatTimeoutMs = 10_000L)
        val t0 = 1_000L

        odometer.onPeerHeartbeatReceived(peerShortId = 2u, nowMs = t0)
        odometer.onLocationUpdate(latAngel, lonAngel, nowMs = t0)

        // Aislamiento: pasan 20 segundos
        odometer.onLocationUpdate(latReforma1Km, lonReforma1Km, nowMs = t0 + 20_000L)
        assertTrue(odometer.isFrozen)
        val frozenKm = odometer.totalAccumulatedKm
        assertEquals(0.0, frozenKm, 0.0001)

        // Reconexión: el compañero reaparece en t = 22_000 ms
        odometer.onPeerHeartbeatReceived(peerShortId = 2u, nowMs = t0 + 22_000L)
        assertFalse("Debe descongelarse al reencontrar al convoy", odometer.isFrozen)
        assertEquals(2, odometer.reconnectCount)

        // Movimiento de otro kilómetro ya en convoy (de latReforma1Km a latReforma2Km)
        val deltaReconnected = odometer.onLocationUpdate(latReforma2Km, lonReforma2Km, nowMs = t0 + 25_000L)
        assertTrue("Debe reanudar la acumulación", deltaReconnected > 900.0)
        assertTrue("Kilometraje total debe reflejar únicamente el tramo en convoy", odometer.totalAccumulatedKm > 0.9)
    }

    @Test
    fun testOdometerDoesNotAccumulateWhenStationary() {
        // Caso específico señalado por el usuario: convoy detenido en un semáforo durante 2 minutos.
        // Los heartbeats siguen llegando pero la distancia física es 0.
        val odometer = MeshOdometer(heartbeatTimeoutMs = 10_000L)
        var currentTime = 10_000L

        // Punto de anclaje inicial
        odometer.onPeerHeartbeatReceived(peerShortId = 3u, nowMs = currentTime)
        odometer.onLocationUpdate(latAngel, lonAngel, nowMs = currentTime)

        // Simular 2 minutos (120 segundos) en el semáforo con heartbeats cada 3 segundos
        for (i in 1..40) {
            currentTime += 3_000L
            odometer.onPeerHeartbeatReceived(peerShortId = 3u, nowMs = currentTime)
            val delta = odometer.onLocationUpdate(latAngel, lonAngel, nowMs = currentTime)
            assertEquals("Un nodo detenido debe acumular 0.0 metros", 0.0, delta, 0.00001)
        }

        assertFalse("El convoy sigue activo en el semáforo", odometer.isFrozen)
        assertEquals("Total de kilómetros tras 2 minutos parado debe ser estrictamente 0", 0.0, odometer.totalAccumulatedKm, 0.00001)
    }

    @Test
    fun testOdometerIgnoresGpsJitterWhenThresholdNotMet() {
        val odometer = MeshOdometer(minMovementThresholdMeters = 3.0)
        val t0 = 5_000L

        odometer.onPeerHeartbeatReceived(peerShortId = 2u, nowMs = t0)
        odometer.onLocationUpdate(latAngel, lonAngel, nowMs = t0)

        // Jitter de ~1 metro de ruido GPS
        val latJitter = latAngel + 0.000009 // ~1 metro
        val delta = odometer.onLocationUpdate(latJitter, lonAngel, nowMs = t0 + 1_000L)

        assertEquals("El ruido GPS menor a 3m debe ser ignorado", 0.0, delta, 0.0001)
        assertEquals(0.0, odometer.totalAccumulatedMeters, 0.0001)
    }

    @Test
    fun testAntiCheatSolitaryRideAccumulatesZeroMeshKm() {
        val odometer = MeshOdometer()
        var currentTime = 10_000L

        // El usuario viaja en solitario por 50 km sin ningún heartbeat de compañeros en la malla
        odometer.onLocationUpdate(19.4326, -99.1332, nowMs = currentTime)

        for (step in 1..50) {
            currentTime += 60_000L // 1 minuto por paso
            val latStep = 19.4326 + (step * 0.0090)
            val delta = odometer.onLocationUpdate(latStep, -99.1332, nowMs = currentTime)
            assertEquals(0.0, delta, 0.00001)
        }

        assertTrue("El odómetro debe permanecer congelado durante toda la rodada en solitario", odometer.isFrozen)
        assertEquals("Cero kilómetros de malla acumulados para rodadas sin compañeros", 0.0, odometer.totalAccumulatedKm, 0.00001)
    }

    @Test
    fun testRoomInsertAndReadUnsyncedEvents() = runTest {
        val dao = InMemoryPendingGamificationEventDao()

        val event1 = PendingGamificationEvent(
            rideId = "ride-abc-1",
            eventType = GamificationEventType.MESH_KM.name,
            value = 45_000L, // 45 km
            timestamp = 1000L
        )
        val event2 = PendingGamificationEvent(
            rideId = "ride-abc-1",
            eventType = GamificationEventType.RELAY_COUNT.name,
            value = 350L,
            timestamp = 2000L
        )

        dao.insert(event1)
        dao.insert(event2)

        val unsynced = dao.getUnsyncedEvents()
        assertEquals(2, unsynced.size)
        assertEquals("ride-abc-1", unsynced[0].rideId)
        assertEquals(45_000L, unsynced[0].value)
        assertFalse(unsynced[0].synced)
    }

    @Test
    fun testRoomMarkAsSyncedAndDeleteSynced() = runTest {
        val dao = InMemoryPendingGamificationEventDao()

        val event1 = PendingGamificationEvent(id = "e1", rideId = "ride-1", eventType = "MESH_KM", value = 10_000L)
        val event2 = PendingGamificationEvent(id = "e2", rideId = "ride-1", eventType = "RELAY_COUNT", value = 50L)

        dao.insertAll(listOf(event1, event2))
        assertEquals(2, dao.getUnsyncedEvents().size)

        // Marcar event1 como sincronizado
        dao.markAsSynced("e1")
        val remainingUnsynced = dao.getUnsyncedEvents()
        assertEquals(1, remainingUnsynced.size)
        assertEquals("e2", remainingUnsynced[0].id)

        // Purgar sincronizados
        val deletedCount = dao.deleteSyncedEvents()
        assertEquals(1, deletedCount)
        val allEvents = dao.getAllEvents()
        assertEquals(1, allEvents.size)
        assertEquals("e2", allEvents[0].id)
    }

    @Test
    fun testRoomIsolationMultipleRides() = runTest {
        val dao = InMemoryPendingGamificationEventDao()

        val ride1Event = PendingGamificationEvent(rideId = "ride-101", eventType = "MESH_KM", value = 12_000L)
        val ride2Event = PendingGamificationEvent(rideId = "ride-202", eventType = "MESH_KM", value = 30_000L)

        dao.insertAll(listOf(ride1Event, ride2Event))

        val ride1List = dao.getEventsForRide("ride-101")
        assertEquals(1, ride1List.size)
        assertEquals("ride-101", ride1List[0].rideId)

        dao.deleteEventsForRide("ride-101")
        assertEquals(0, dao.getEventsForRide("ride-101").size)
        assertEquals(1, dao.getEventsForRide("ride-202").size)
    }

    @Test
    fun testSyncEngineSuccessfulUploadMarksAndDeletes() = runTest {
        val dao = InMemoryPendingGamificationEventDao()
        dao.insert(PendingGamificationEvent(rideId = "ride-777", eventType = "MESH_KM", value = 25_000L))
        dao.insert(PendingGamificationEvent(rideId = "ride-777", eventType = "SOS_ASSIST", value = 1L))

        var payloadSent: Boolean = false
        val mockClient = object : TelemetryApiClient {
            override suspend fun sendTelemetrySummary(rideId: String, events: List<PendingGamificationEvent>, xpEarned: Int): Boolean {
                assertEquals("ride-777", rideId)
                assertEquals(2, events.size)
                payloadSent = true
                return true
            }
        }

        val engine = GamificationSyncEngine(dao, mockClient)
        val result = engine.syncPendingEvents()

        assertTrue(result is GamificationSyncEngine.SyncResult.Success)
        assertEquals(2, (result as GamificationSyncEngine.SyncResult.Success).syncedCount)
        assertTrue(payloadSent)
        assertTrue("Los eventos sincronizados deben haber sido purgados de SQLite", dao.getAllEvents().isEmpty())
    }

    @Test
    fun testSyncEngineFailurePreservesEventsForRetry() = runTest {
        val dao = InMemoryPendingGamificationEventDao()
        dao.insert(PendingGamificationEvent(id = "e-fail", rideId = "ride-888", eventType = "MESH_KM", value = 15_000L))

        val failingClient = object : TelemetryApiClient {
            override suspend fun sendTelemetrySummary(rideId: String, events: List<PendingGamificationEvent>, xpEarned: Int): Boolean {
                return false // Simula error de red o backend no disponible
            }
        }

        val engine = GamificationSyncEngine(dao, failingClient)
        val result = engine.syncPendingEvents()

        assertTrue(result is GamificationSyncEngine.SyncResult.Failure)
        assertEquals("Los eventos deben preservarse intactos ante fallo de sincronización", 1, dao.getUnsyncedEvents().size)
        assertFalse(dao.getUnsyncedEvents()[0].synced)
    }

    @Test
    fun testEndToEndRideMeshOdometerToRoomAndSync() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val medium = com.rutabikersync.app.core.transport.SimulatedBleMedium()
        val dao = InMemoryPendingGamificationEventDao()

        val controller1 = RideSessionController(
            scope = this,
            bleTransportFactory = { null },
            simulatedMedium = medium
        ).apply {
            gamificationDao = dao
        }

        val controller2 = RideSessionController(
            scope = this,
            bleTransportFactory = { null },
            simulatedMedium = medium
        )

        // 1. Iniciar rodada en ambos teléfonos con medio simulado compartido
        controller1.startRide(
            rideId = "ride-e2e-2026",
            localShortId = 1u,
            expectedPeerShortIds = setOf(2u),
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )
        controller2.startRide(
            rideId = "ride-e2e-2026",
            localShortId = 2u,
            expectedPeerShortIds = setOf(1u),
            forceSimulatedTransport = true,
            enablePeriodicHeartbeatLoop = false
        )

        val odo = controller1.meshOdometer
        assertNotNull("El odómetro debe estar inicializado al iniciar rodada", odo)

        // 2. El compañero (controller2) emite heartbeat real en el medio compartido en t = 1000 ms
        controller2.emitHeartbeatNow(nowEpochMs = 1_000L)
        assertTrue("Controller1 debe detectar al compañero en la malla", controller1.relayEngine!!.hasActivePeerWithin(10_000L, 1_000L))

        // 3. Simular movimiento GPS en convoy para controller1
        controller1.updateLocalTelemetry(latitude = 19.4270, longitude = -99.1677, speedKmh = 60)
        controller1.updateLocalTelemetry(latitude = 19.4360, longitude = -99.1677, speedKmh = 60)

        assertTrue("El odómetro debió acumular distancia en convoy", odo!!.totalAccumulatedKm > 0.5)

        // 4. Detener la rodada (debe encolar automáticamente en Room)
        controller1.stopAll()
        controller2.stopAll()

        val queuedEvents = dao.getUnsyncedEvents()
        assertEquals(1, queuedEvents.size)
        val kmEvent = queuedEvents[0]
        assertEquals("ride-e2e-2026", kmEvent.rideId)
        assertEquals(GamificationEventType.MESH_KM.name, kmEvent.eventType)
        assertTrue("El valor de metros debe ser mayor a 500", kmEvent.value > 500L)

        // 5. Worker / SyncEngine sincroniza los eventos con éxito
        val mockClient = object : TelemetryApiClient {
            override suspend fun sendTelemetrySummary(rideId: String, events: List<PendingGamificationEvent>, xpEarned: Int): Boolean = true
        }
        val syncEngine = GamificationSyncEngine(dao, mockClient)
        val syncResult = syncEngine.syncPendingEvents()

        assertTrue(syncResult is GamificationSyncEngine.SyncResult.Success)
        assertTrue("Los eventos sincronizados deben haber sido purgados de la cola Room", dao.getAllEvents().isEmpty())
    }
}
