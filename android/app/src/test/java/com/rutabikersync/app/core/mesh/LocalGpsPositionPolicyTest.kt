package com.rutabikersync.app.core.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGpsPositionPolicyTest {

    @Test
    fun `coordenadas validas pero viejas o imprecisas no son un fix actual`() {
        val now = 1_000_000L

        assertTrue(
            LocalGpsPositionPolicy.isUsableFix(
                latitude = 10.349246,
                longitude = -67.034904,
                accuracyMeters = 12f,
                fixTimeEpochMs = now - 5_000L,
                nowEpochMs = now
            )
        )
        assertFalse(
            "Una posición cacheada puede ser geográficamente válida y estar obsoleta",
            LocalGpsPositionPolicy.isUsableFix(
                latitude = 10.349246,
                longitude = -67.034904,
                accuracyMeters = 12f,
                fixTimeEpochMs = now - LocalGpsPositionPolicy.MAX_FIX_AGE_MS - 1L,
                nowEpochMs = now
            )
        )
        assertFalse(
            LocalGpsPositionPolicy.isUsableFix(
                latitude = 10.349246,
                longitude = -67.034904,
                accuracyMeters = LocalGpsPositionPolicy.MAX_ACCURACY_METERS + 1f,
                fixTimeEpochMs = now,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `sin fecha o con fecha futura inaceptable no se inventa precision ni frescura`() {
        val now = 1_000_000L

        assertFalse(
            LocalGpsPositionPolicy.isUsableFix(
                latitude = 10.349246,
                longitude = -67.034904,
                accuracyMeters = 8f,
                fixTimeEpochMs = 0L,
                nowEpochMs = now
            )
        )
        assertFalse(
            LocalGpsPositionPolicy.isUsableFix(
                latitude = 10.349246,
                longitude = -67.034904,
                accuracyMeters = 8f,
                fixTimeEpochMs = now + 60_000L,
                nowEpochMs = now
            )
        )
        assertFalse(
            LocalGpsPositionPolicy.isUsableFix(
                latitude = Double.NaN,
                longitude = -67.034904,
                accuracyMeters = 8f,
                fixTimeEpochMs = now,
                nowEpochMs = now
            )
        )
    }
}
