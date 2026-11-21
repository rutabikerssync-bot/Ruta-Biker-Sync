package com.rutabikersync.app.core.mesh

/**
 * Decide si una posición del sistema puede presentarse como ubicación actual de la rodada.
 * No crea ni corrige coordenadas: si no hay fecha/precisión fiable, se mantiene el estado sin fix.
 */
object LocalGpsPositionPolicy {
    const val MAX_FIX_AGE_MS = 30_000L
    const val MAX_ACCURACY_METERS = 100f
    const val MAX_FUTURE_SKEW_MS = 5_000L

    fun isFresh(fixTimeEpochMs: Long, nowEpochMs: Long): Boolean {
        if (fixTimeEpochMs <= 0L) return false
        return nowEpochMs - fixTimeEpochMs in -MAX_FUTURE_SKEW_MS..MAX_FIX_AGE_MS
    }

    fun isUsableFix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        fixTimeEpochMs: Long,
        nowEpochMs: Long
    ): Boolean =
        MeshTelemetry.isValidPosition(latitude, longitude) &&
            accuracyMeters.isFinite() &&
            accuracyMeters > 0f &&
            accuracyMeters <= MAX_ACCURACY_METERS &&
            isFresh(fixTimeEpochMs, nowEpochMs)
}
