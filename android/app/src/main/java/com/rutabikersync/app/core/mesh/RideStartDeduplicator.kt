package com.rutabikersync.app.core.mesh

/**
 * Evita que un mismo arranque de rodada se despache dos veces.
 *
 * En la APK medida, `RideStarted` aparecía duplicado a 36–102 ms porque dos callers
 * independientes (`MainAppScaffold.startRideServiceNow` y el `LaunchedEffect` de
 * `LiveMapScreen`) solicitan el arranque al entrar en la cabina. El guard se aplica
 * en el punto común de despacho, antes de crear el `Intent` del servicio.
 *
 * No sustituye a las reglas de fase de [com.rutabikersync.app.core.ridelifecycle.RideLifecycle]:
 * solo suprime solicitudes idénticas dentro de una ventana corta. Un `reset()` explícito
 * permite iniciar de nuevo tras una parada legítima.
 */
class RideStartDeduplicator(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    companion object {
        /** Cubre los duplicados observados en hardware (36–102 ms) con margen. */
        const val DEFAULT_WINDOW_MS = 500L
    }

    private var lastRideId: String? = null
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun shouldStart(rideId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val duplicate = rideId == lastRideId &&
            nowMs >= lastAcceptedAtMs &&
            nowMs - lastAcceptedAtMs < windowMs
        if (duplicate) return false
        lastRideId = rideId
        lastAcceptedAtMs = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        lastRideId = null
        lastAcceptedAtMs = Long.MIN_VALUE
    }
}
