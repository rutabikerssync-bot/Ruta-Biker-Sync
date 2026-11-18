package com.rutabikersync.app.core.mesh

/**
 * Conmutador Adaptativo de Capa Física BLE (Fase 6 · Hito 1 · BLE_MESH_ARCHITECTURE_HITO_0.md §4.2).
 *
 * Administra la conmutación dinámica entre PHY 1M y Coded PHY S=8:
 * - Degradación: Si RSSI < -88 dBm (promedio de 3 heartbeats consecutivos) -> PHY_CODED_S8 (+8 dB sensibilidad).
 * - Retorno a 1M: Si RSSI > -78 dBm (promedio de 5 heartbeats consecutivos) -> PHY_1M (menor air-time para audio PTT).
 * - Ajuste 1 (Auditoría): Timeout de 60s en zona gris (-80 a -88 dBm). Si tras 60s en Coded PHY el RSSI
 *   promedia >= -80 dBm en las últimas 10 muestras, se fuerza el retorno preventivo a 1M PHY.
 */
class CodedPhySwitcher(
    private val onPhyChangeRequested: ((oldPhy: PhyMode, newPhy: PhyMode, reason: String) -> Unit)? = null
) {
    enum class PhyMode {
        PHY_1M,
        PHY_CODED_S8
    }

    companion object {
        const val DEGRADE_RSSI_THRESHOLD = -88.0
        const val DEGRADE_SAMPLE_COUNT = 3

        const val RECOVERY_RSSI_THRESHOLD = -78.0
        const val RECOVERY_SAMPLE_COUNT = 5

        const val GRAY_ZONE_TIMEOUT_MS = 60_000L // 60 segundos
        const val GRAY_ZONE_FORCE_1M_THRESHOLD = -80.0
        const val GRAY_ZONE_SAMPLE_COUNT = 10
    }

    private val lock = Any()

    @Volatile
    var currentPhy: PhyMode = PhyMode.PHY_1M
        private set

    @Volatile
    var codedPhyActivatedAtMs: Long = 0L
        private set

    private val recentRssiSamples = ArrayDeque<Int>()

    /**
     * Registra una muestra de RSSI recibida (habitualmente en cada Heartbeat recibido de un peer).
     * Evalúa las reglas de degradación, recuperación y timeout de zona gris.
     */
    fun onRssiSample(rssi: Int, nowMs: Long = System.currentTimeMillis()): PhyMode {
        synchronized(lock) {
            recentRssiSamples.addLast(rssi)
            while (recentRssiSamples.size > 20) {
                recentRssiSamples.removeFirst()
            }

            when (currentPhy) {
                PhyMode.PHY_1M -> {
                    // Evaluar degradación a Coded PHY S=8
                    if (recentRssiSamples.size >= DEGRADE_SAMPLE_COUNT) {
                        val lastSamples = recentRssiSamples.takeLast(DEGRADE_SAMPLE_COUNT)
                        val avgRssi = lastSamples.average()
                        if (avgRssi < DEGRADE_RSSI_THRESHOLD) {
                            switchPhy(PhyMode.PHY_CODED_S8, nowMs, "RSSI crítico ($avgRssi dBm < $DEGRADE_RSSI_THRESHOLD en $DEGRADE_SAMPLE_COUNT muestras)")
                        }
                    }
                }

                PhyMode.PHY_CODED_S8 -> {
                    // 1. Evaluar retorno estándar a 1M PHY
                    if (recentRssiSamples.size >= RECOVERY_SAMPLE_COUNT) {
                        val lastSamples = recentRssiSamples.takeLast(RECOVERY_SAMPLE_COUNT)
                        val avgRssi = lastSamples.average()
                        if (avgRssi > RECOVERY_RSSI_THRESHOLD) {
                            switchPhy(PhyMode.PHY_1M, nowMs, "RSSI recuperado ($avgRssi dBm > $RECOVERY_RSSI_THRESHOLD en $RECOVERY_SAMPLE_COUNT muestras)")
                            return currentPhy
                        }
                    }

                    // 2. Ajuste 1 (Auditoría): Timeout de 60s en zona gris (-80 a -88 dBm)
                    val timeInCodedMs = nowMs - codedPhyActivatedAtMs
                    if (timeInCodedMs >= GRAY_ZONE_TIMEOUT_MS && recentRssiSamples.size >= GRAY_ZONE_SAMPLE_COUNT) {
                        val lastSamples = recentRssiSamples.takeLast(GRAY_ZONE_SAMPLE_COUNT)
                        val avgRssi = lastSamples.average()
                        if (avgRssi >= GRAY_ZONE_FORCE_1M_THRESHOLD) {
                            switchPhy(PhyMode.PHY_1M, nowMs, "Timeout de 60s en zona gris: promedio $avgRssi dBm >= $GRAY_ZONE_FORCE_1M_THRESHOLD en 10 muestras")
                        }
                    }
                }
            }
            return currentPhy
        }
    }

    private fun switchPhy(newPhy: PhyMode, nowMs: Long, reason: String) {
        val oldPhy = currentPhy
        if (oldPhy == newPhy) return

        currentPhy = newPhy
        if (newPhy == PhyMode.PHY_CODED_S8) {
            codedPhyActivatedAtMs = nowMs
        } else {
            codedPhyActivatedAtMs = 0L
        }
        onPhyChangeRequested?.invoke(oldPhy, newPhy, reason)
    }

    fun reset() {
        synchronized(lock) {
            currentPhy = PhyMode.PHY_1M
            codedPhyActivatedAtMs = 0L
            recentRssiSamples.clear()
        }
    }
}

