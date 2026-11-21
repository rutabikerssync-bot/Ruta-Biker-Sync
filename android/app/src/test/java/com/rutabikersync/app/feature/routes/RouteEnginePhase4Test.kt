package com.rutabikersync.app.feature.routes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteEnginePhase4Test {

    private val origin = LatLngPoint(10.3538, -67.0425)       // Los Teques
    private val destination = LatLngPoint(10.3452, -67.1648)  // El Jarillo

    @org.junit.Before
    fun setUp() {
        DefaultRouteCatalog.customAssetLoader = { fileName ->
            val file = java.io.File("src/main/assets/routes/$fileName").takeIf { it.exists() }
                ?: java.io.File("app/src/main/assets/routes/$fileName").takeIf { it.exists() }
            file?.readText()
        }
    }

    @org.junit.After
    fun tearDown() {
        DefaultRouteCatalog.customAssetLoader = null
    }

    @Test
    fun `Bloque 4_1 BRouter y perfiles de moto generan rutas, elevacion y hash SHA-256`() {
        // 1. Perfil rápido / autopista
        val fastRoute = MotorcycleRoutingEngine.planMotorcycleRoute(
            origin = origin,
            destination = destination,
            destinationName = "El Jarillo",
            profile = MotorcycleRoutingProfile.FASTEST
        )
        assertNotNull(fastRoute)
        assertTrue("La ruta rápida debe tener al menos 10 waypoints", fastRoute.waypoints.size >= 10)
        assertTrue("La distancia debe ser > 10 km", fastRoute.distanceKm > 10.0)

        // 2. Perfil curvas de montaña
        val curvyRoute = MotorcycleRoutingEngine.planMotorcycleRoute(
            origin = origin,
            destination = destination,
            destinationName = "El Jarillo",
            profile = MotorcycleRoutingProfile.CURVY_SCENIC
        )
        assertTrue("Ruta curvas debe ser recomendada", curvyRoute.isRecommended)
        assertTrue("Ruta curvas debe tener mayor distancia por sinuosidad", curvyRoute.distanceKm >= fastRoute.distanceKm)

        // 3. Perfil de elevación
        val elevation = MotorcycleRoutingEngine.generateElevationProfile(curvyRoute.waypoints)
        assertEquals(curvyRoute.waypoints.size, elevation.size)
        val ascent = MotorcycleRoutingEngine.calculateTotalAscentMeters(elevation)
        val descent = MotorcycleRoutingEngine.calculateTotalDescentMeters(elevation)
        assertTrue("El ascenso acumulado debe ser positivo", ascent > 0)
        assertTrue("El descenso acumulado debe ser positivo", descent > 0)

        // 4. Hash criptográfico SHA-256
        val hash = MotorcycleRoutingEngine.computeRouteSha256(curvyRoute.waypoints)
        assertEquals("El hash SHA-256 debe tener 64 caracteres hexadecimales", 64, hash.length)

        // 5. POIs
        val pois = MotorcycleRoutingEngine.generateRoutePois(curvyRoute.waypoints, "El Jarillo")
        assertTrue("Deben existir al menos 2 POIs", pois.size >= 2)
        assertEquals(PoiCategory.GAS_STATION, pois.first().category)
    }

    @Test
    fun `Bloque 4_2 GpxManager importa y exporta rutas GPX 1_1 con integridad`() {
        val testRoute = DefaultRouteCatalog.tovarDirecta

        // 1. Exportar a formato GPX
        val gpxXml = GpxManager.exportToGpxString(
            route = testRoute,
            clubName = "Los Halcones de la Montaña MG"
        )
        assertNotNull(gpxXml)
        assertTrue("Debe contener cabecera XML", gpxXml.contains("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue("Debe ser versión 1.1", gpxXml.contains("""version="1.1""""))
        assertTrue("Debe contener track points", gpxXml.contains("<trkpt"))
        assertTrue("Debe contener elevación", gpxXml.contains("<ele>"))

        // 2. Re-importar el XML generado
        val importedRoute = GpxManager.parseGpxContent(gpxXml, "Ruta Reimportada")
        assertNotNull(importedRoute)
        assertEquals(testRoute.name, importedRoute.name)
        assertEquals(testRoute.waypoints.size, importedRoute.waypoints.size)
        assertTrue("Distancia debe ser similar (+/- 1 km)", kotlin.math.abs(importedRoute.distanceKm - testRoute.distanceKm) < 1.0)
    }

    @Test
    fun `Bloque 4_3 CorridorOfflineManager calcula bounding box y tamano estimado de PMTiles`() {
        val points = DefaultRouteCatalog.tovarDirecta.waypoints

        // 1. Bounding box con buffer de 10 km
        val (sw, ne) = CorridorOfflineManager.computeBoundingBoxWithBuffer(points, bufferKm = 10)
        assertTrue("SW latitud debe ser menor a NE latitud", sw.latitude < ne.latitude)
        assertTrue("SW longitud debe ser menor a NE longitud", sw.longitude < ne.longitude)

        // 2. Estimación de tamaño de teselas
        val size5Km = CorridorOfflineManager.estimateCorridorSizeMb(48.5, bufferKm = 5)
        val size10Km = CorridorOfflineManager.estimateCorridorSizeMb(48.5, bufferKm = 10)
        val size15Km = CorridorOfflineManager.estimateCorridorSizeMb(48.5, bufferKm = 15)

        assertTrue("Buffer 10km debe ser mayor que 5km", size10Km > size5Km)
        assertTrue("Buffer 15km debe ser mayor que 10km", size15Km > size10Km)
        assertTrue("Tamano estimado razonable", size10Km in 10.0..50.0)
    }

    @Test
    fun `Bloque 4_4 RoutePolylineMeshRelay fragmenta y reensambla con validacion de hash SHA-256`() {
        val originalRoute = DefaultRouteCatalog.tovarElJarillo

        // 1. Fragmentación en paquetes <= 480 caracteres (BLE MTU 512B)
        val chunks = RoutePolylineMeshRelay.fragmentRouteForMesh(originalRoute, senderName = "Capitán Oscar")
        assertTrue("La ruta debe fragmentarse en al menos 1 chunk", chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue("Cada chunk debe ser <= 480 chars", chunk.payloadString.length <= RoutePolylineMeshRelay.MAX_CHUNK_PAYLOAD_CHARS)
            assertEquals("Todos los chunks deben compartir el routeId", originalRoute.id, chunk.routeId)
            assertEquals("Todos los chunks deben compartir el totalChunks", chunks.size, chunk.totalChunks)
        }

        // 2. Procesamiento y reensamblado en el nodo receptor
        var completedEvent: RouteMeshReceivedEvent? = null
        chunks.forEach { chunk ->
            val result = RoutePolylineMeshRelay.processIncomingChunk(chunk, senderName = "Capitán Oscar")
            if (result != null) {
                completedEvent = result
            }
        }

        assertNotNull("La ruta debe haberse reensamblado al recibir el último chunk", completedEvent)
        assertTrue("El hash SHA-256 debe estar verificado (R6.1)", completedEvent!!.isHashVerified)
        assertEquals(originalRoute.waypoints.size, completedEvent!!.route.waypoints.size)
        assertEquals(originalRoute.name, completedEvent!!.route.name)

        RoutePolylineMeshRelay.clearOnRideFinished()
    }

    @Test
    fun `Bloque 4_5 RouteDeviationDetector detecta desvio ortogonal y modo Voy a parar con auto-reanudacion`() {
        val polyline = listOf(
            LatLngPoint(10.3500, -67.0500),
            LatLngPoint(10.3600, -67.0500),
            LatLngPoint(10.3700, -67.0500)
        )
        RouteDeviationDetector.setActiveRoutePolyline(polyline)

        // 1. Piloto sobre la ruta (desvío 0 m)
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0500), speedKmh = 45f)
        val stateOnTrack = RouteDeviationDetector.deviationState.value
        assertEquals(DeviationStatus.ON_TRACK, stateOnTrack.status)
        assertFalse(stateOnTrack.isAlertActive)
        assertTrue("Distancia debe ser menor a 50m", stateOnTrack.distanceToRouteMeters < 50)

        // 2. Piloto se desvía 350 metros hacia el este
        // A latitud 10°, 0.003° de longitud ≈ 327 metros
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0470), speedKmh = 35f, nowEpochMs = 10_000L)
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0470), speedKmh = 35f, nowEpochMs = 20_000L)
        val stateDeviated = RouteDeviationDetector.deviationState.value
        assertEquals(DeviationStatus.DEVIATED, stateDeviated.status)
        assertTrue("Alerta de desvío debe estar activa", stateDeviated.isAlertActive)
        assertTrue("Distancia debe superar el umbral de 150m", stateDeviated.distanceToRouteMeters >= 150)

        // 3. Piloto activa 'Voy a parar' (por combustible) -> Se silencia la alarma (CHECKLIST F)
        RouteDeviationDetector.activateVoluntaryStop(StopReason.FUEL)
        val stateStopped = RouteDeviationDetector.deviationState.value
        assertEquals(DeviationStatus.VOLUNTARY_STOP, stateStopped.status)
        assertFalse("La alarma debe estar silenciada en parada voluntaria", stateStopped.isAlertActive)
        assertEquals(StopReason.FUEL, stateStopped.stopReason)

        // 4. Si el piloto sigue detenido (velocidad baja), se mantiene en parada voluntaria
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0470), speedKmh = 5f)
        assertEquals(DeviationStatus.VOLUNTARY_STOP, RouteDeviationDetector.deviationState.value.status)

        // 5. Auto-reanudación: el piloto arranca y supera 18 km/h de velocidad
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0470), speedKmh = 25f, nowEpochMs = 20_000L)
        RouteDeviationDetector.updateRiderLocation(LatLngPoint(10.3550, -67.0470), speedKmh = 25f, nowEpochMs = 40_000L)
        val stateResumed = RouteDeviationDetector.deviationState.value
        assertEquals("Debe auto-reanudar el monitoreo al arrancar", DeviationStatus.DEVIATED, stateResumed.status)
        assertTrue(stateResumed.isAlertActive)

        RouteDeviationDetector.reset()
    }
}
