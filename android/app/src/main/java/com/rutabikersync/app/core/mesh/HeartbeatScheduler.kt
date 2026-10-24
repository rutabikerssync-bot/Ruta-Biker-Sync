package com.rutabikersync.app.core.mesh

import com.rutabikersync.app.core.power.PowerProfile
import com.rutabikersync.app.core.protocol.MeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Planificador adaptativo de Heartbeats BLE (Bloque 3.5, DESIGN.md §3 y §10):
 * - En `PowerProfile.NORMAL`: emite cada 3 s (`3_000L` ms).
 * - En `PowerProfile.POWER_SAVER` (< 20% batería): emite cada 5 s (`5_000L` ms).
 * - En `PowerProfile.SURVIVAL` (<= 10% / 5% batería): emite cada 10 s (`10_000L` ms).
 */
class HeartbeatScheduler(
    private val scope: CoroutineScope,
    private val engine: MeshRelayEngine,
    private val peerHealthMonitor: PeerHealthMonitor? = null
) {
    @Volatile
    var activePowerProfile: PowerProfile = PowerProfile.NORMAL
        private set

    @Volatile
    var currentIntervalMs: Long = PowerProfile.NORMAL.heartbeatIntervalMs
        private set

    @Volatile
    var heartbeatsSentCount: Int = 0
        private set

    @Volatile
    var lastEmittedAtEpochMs: Long = 0L
        private set

    private var loopJob: Job? = null

    fun applyPowerProfile(profile: PowerProfile) {
        activePowerProfile = profile
        currentIntervalMs = profile.heartbeatIntervalMs
    }

    fun startPeriodicLoop(
        telemetryProvider: () -> TelemetryPose
    ) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                emitHeartbeatNow(telemetryProvider(), now)
                delay(currentIntervalMs)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Emite un heartbeat inmediatamente y evalúa la salud de los peers y las islas en `nowEpochMs`.
     */
    suspend fun emitHeartbeatNow(
        pose: TelemetryPose,
        nowEpochMs: Long = System.currentTimeMillis()
    ): MeshPacket {
        val packet = engine.emitLocalPosition(
            latitude = pose.latitude,
            longitude = pose.longitude,
            speedKmh = pose.speedKmh,
            bearingDegrees = pose.bearingDegrees,
            batteryPercent = pose.batteryPercent,
            isSos = pose.isSos
        )
        heartbeatsSentCount++
        lastEmittedAtEpochMs = nowEpochMs
        peerHealthMonitor?.evaluateHealth(nowEpochMs) ?: engine.evaluatePeerTimeouts(nowEpochMs)
        return packet
    }

    /**
     * Ejecuta un pulso sólo si ya transcurrió `currentIntervalMs` desde la última emisión
     * (permite verificar en JVM el cambio de cadencia 3 s -> 5 s -> 10 s sin depender de relojes reales).
     */
    suspend fun tickIfDue(
        pose: TelemetryPose,
        nowEpochMs: Long
    ): Boolean {
        if (lastEmittedAtEpochMs == 0L || nowEpochMs - lastEmittedAtEpochMs >= currentIntervalMs) {
            emitHeartbeatNow(pose, nowEpochMs)
            return true
        }
        peerHealthMonitor?.evaluateHealth(nowEpochMs) ?: engine.evaluatePeerTimeouts(nowEpochMs)
        return false
    }

    data class TelemetryPose(
        val latitude: Double = 19.4326,
        val longitude: Double = -99.1332,
        val speedKmh: Int = 60,
        val bearingDegrees: Float = 180f,
        val batteryPercent: Int = 85,
        val isSos: Boolean = false
    )
}
