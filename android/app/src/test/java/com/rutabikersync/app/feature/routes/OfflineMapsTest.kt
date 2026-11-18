package com.rutabikersync.app.feature.routes

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineMapsTest {

    @Test
    fun testCanonicalRegionsParsingAndBbox() {
        val regions = CorridorOfflineManager.CANONICAL_REGIONS
        assertEquals("Deben existir exactamente 3 regiones canónicas", 3, regions.size)

        val laGuaira = regions.find { it.id == "caracas-la-guaira" }
        assertNotNull(laGuaira)
        assertEquals("Caracas — La Guaira", laGuaira!!.name)
        assertEquals(10.50, laGuaira.minLat, 0.001)
        assertEquals(-67.05, laGuaira.minLng, 0.001)
        assertEquals(10.62, laGuaira.maxLat, 0.001)
        assertEquals(-66.90, laGuaira.maxLng, 0.001)
        assertEquals(14.5, laGuaira.estimatedSizeMb, 0.1)

        val tovar = regions.find { it.id == "caracas-junquito-tovar" }
        assertNotNull(tovar)
        assertEquals(18.2, tovar!!.estimatedSizeMb, 0.1)

        val valencia = regions.find { it.id == "valencia-puerto-cabello" }
        assertNotNull(valencia)
        assertEquals(15.8, valencia!!.estimatedSizeMb, 0.1)
    }

    @Test
    fun testStorageLimitAndQuotaCalculation() {
        assertEquals("El límite de seguridad debe ser 2 GB", 2L * 1024 * 1024 * 1024, CorridorOfflineManager.MAX_STORAGE_BYTES)
        val limitMb = CorridorOfflineManager.getStorageLimitMb()
        assertEquals(2048.0, limitMb, 0.1)

        // Simular descarga de 15 MB con almacenamiento en 0
        val canDownload = CorridorOfflineManager.canDownloadRegion(null, 15.0)
        assertTrue("Debe permitir descargar cuando no excede 2 GB", canDownload)
    }

    @Test
    fun testQuotaExceededEnforcement() {
        // Simular intento de descarga de más de 2049 MB (excede 2 GB)
        val exceedsQuota = CorridorOfflineManager.canDownloadRegion(null, 2049.0)
        assertFalse("Debe bloquear descargas que superen el límite de 2 GB", exceedsQuota)
        assertEquals(
            "Límite de almacenamiento alcanzado. Elimina una región para descargar otra.",
            CorridorOfflineManager.QUOTA_EXCEEDED_MESSAGE
        )
    }

    @Test
    fun testPmtilesIntegrityVerification() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "pmtiles_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Archivo válido con magic bytes PMTiles\u0003
            val validFile = File(tempDir, "valid.pmtiles")
            validFile.outputStream().use { out ->
                out.write("PMTiles\u0003".toByteArray(Charsets.UTF_8))
                out.write(ByteArray(1024) { 0x55.toByte() })
            }
            assertTrue("Debe validar archivo con magic bytes PMTiles", CorridorOfflineManager.verifyPmtilesIntegrity(validFile))

            // Archivo inválido con magic bytes corruptos
            val corruptedFile = File(tempDir, "corrupted.pmtiles")
            corruptedFile.writeText("NOT_A_VALID_PMTILES_ARCHIVE_DATA")
            assertFalse("Debe rechazar archivo con cabecera corrupta", CorridorOfflineManager.verifyPmtilesIntegrity(corruptedFile))

            // Archivo vacío
            val emptyFile = File(tempDir, "empty.pmtiles")
            emptyFile.writeBytes(ByteArray(0))
            assertFalse("Debe rechazar archivo de 0 bytes", CorridorOfflineManager.verifyPmtilesIntegrity(emptyFile))

            // Archivo inexistente
            val missingFile = File(tempDir, "non_existent.pmtiles")
            assertFalse("Debe retornar false para archivo inexistente", CorridorOfflineManager.verifyPmtilesIntegrity(missingFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOfflineCorridorResolutionNearest() {
        // Coordenadas en la Autopista Caracas - La Guaira: 10.55, -67.00
        val regions = CorridorOfflineManager.CANONICAL_REGIONS
        val laGuaira = regions.first { it.id == "caracas-la-guaira" }
        assertTrue("10.55 debe estar dentro de la latitud de La Guaira", 10.55 in laGuaira.minLat..laGuaira.maxLat)
        assertTrue("-67.00 debe estar dentro de la longitud de La Guaira", -67.00 in laGuaira.minLng..laGuaira.maxLng)

        // Coordenadas en Madrid, España (fuera de corredores): 40.41, -3.70
        val isInsideMadrid = regions.any { 40.41 in it.minLat..it.maxLat && -3.70 in it.minLng..it.maxLng }
        assertFalse("Coordenadas en España no deben coincidir con ningún corredor venezolano", isInsideMadrid)
    }

    @Test
    fun testDynamicCustomRouteRegistrationAndBbox() {
        val waypoints = listOf(
            LatLngPoint(10.35, -67.04), // Los Teques
            LatLngPoint(10.37, -67.15), // El Jarillo
            LatLngPoint(10.41, -67.28)  // Colonia Tovar
        )

        val customRoute = CorridorOfflineManager.registerCustomRoute(
            routeId = "route-los-teques-tovar-dinamica",
            routeName = "Rodada de Altura Dinámica",
            distanceKm = 48.5,
            waypoints = waypoints,
            bufferKm = 10
        )

        assertEquals("route-los-teques-tovar-dinamica", customRoute.id)
        assertEquals("Rodada de Altura Dinámica", customRoute.name)
        assertTrue("Min latitud debe incluir margen de buffer", customRoute.minLat < 10.35)
        assertTrue("Max latitud debe incluir margen de buffer", customRoute.maxLat > 10.41)
        assertTrue("Min longitud debe incluir margen de buffer", customRoute.minLng < -67.28)
        assertTrue("Max longitud debe incluir margen de buffer", customRoute.maxLng > -67.04)
        assertTrue("El tamaño estimado debe ser positivo y razonable", customRoute.estimatedSizeMb in 10.0..30.0)

        val allRegions = CorridorOfflineManager.getAllRegions()
        assertEquals("La ruta personalizada debe aparecer primera en allRegions", "route-los-teques-tovar-dinamica", allRegions.first().id)
        assertTrue("Deben listarse al menos 4 regiones totales (1 dinámica + 3 canónicas)", allRegions.size >= 4)
    }

    @Test
    fun testConfirmedAttendeeRouteDownloadEligibility() {
        val waypoints = listOf(
            LatLngPoint(10.20, -68.00),
            LatLngPoint(10.45, -67.98)
        )
        val region = CorridorOfflineManager.registerCustomRoute(
            routeId = "ride-convocada-01",
            routeName = "Valencia a Puerto Cabello Express",
            distanceKm = 35.0,
            waypoints = waypoints,
            bufferKm = 10
        )

        assertTrue(CorridorOfflineManager.canDownloadRegion(null, region.estimatedSizeMb))
        val found = CorridorOfflineManager.findRegionById("ride-convocada-01")
        assertNotNull("Debe resolver la región de la ruta convocada por su ID", found)
        assertEquals("ride-convocada-01.pmtiles", found!!.fileName)
    }
}
