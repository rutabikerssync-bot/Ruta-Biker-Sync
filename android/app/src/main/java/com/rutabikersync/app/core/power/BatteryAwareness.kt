package com.rutabikersync.app.core.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Perfiles de energía durante la rodada activa (Bloque 3.5, DESIGN.md §10 y Decisión D56):
 * - `NORMAL`: Batería >= 20% o cargando -> Heartbeat cada 3 s, mapa cada 1 s, escaneo BLE continuo.
 * - `POWER_SAVER`: Batería < 20% sin cargar (o activado manualmente) -> Heartbeat cada 5 s, mapa cada 3 s, escaneo BLE balanceado.
 * - `SURVIVAL`: Batería crítica <= 10% (incluye 5%) sin cargar -> Heartbeat cada 10 s, mapa congelado, solo SOS activo.
 *
 * `maxGattPeerLinks` (FASE 3.1) es el tope de **objetivos GATT CLIENTE** por rodada (8/4/2):
 * limita los enlaces salientes que abre este móvil, NO el número de peers BLE de la malla ni
 * las conexiones entrantes al servidor GATT. El roster autorizado completo se conserva para
 * el estado y el relay; solo se recorta la lista de destinos del cliente de heartbeats.
 */
enum class PowerProfile(
    val heartbeatIntervalMs: Long,
    val mapUpdateCadenceMs: Long,
    val isMapFrozen: Boolean,
    val onlySosActive: Boolean,
    val maxGattPeerLinks: Int,
    val displayLabel: String
) {
    NORMAL(
        heartbeatIntervalMs = 3_000L,
        mapUpdateCadenceMs = 1_000L,
        isMapFrozen = false,
        onlySosActive = false,
        maxGattPeerLinks = 8,
        displayLabel = "Normal (3 s)"
    ),
    POWER_SAVER(
        heartbeatIntervalMs = 5_000L,
        mapUpdateCadenceMs = 3_000L,
        isMapFrozen = false,
        onlySosActive = false,
        maxGattPeerLinks = 4,
        displayLabel = "Modo ahorro (5 s)"
    ),
    SURVIVAL(
        heartbeatIntervalMs = 10_000L,
        mapUpdateCadenceMs = 0L,
        isMapFrozen = true,
        onlySosActive = true,
        maxGattPeerLinks = 2,
        displayLabel = "Modo supervivencia (10 s · Solo SOS)"
    )
}

data class BatteryPowerState(
    val batteryPercent: Int = 85,
    val isCharging: Boolean = false,
    val autoBatterySaverEnabled: Boolean = true,
    val manualPowerSaverOverride: Boolean? = null,
    val profile: PowerProfile = PowerProfile.NORMAL,
    val lastTtsAlertMessage: String? = null
)

/**
 * Monitor y política de ahorro de batería de `RideService` (Bloque 3.5):
 * - **Decisión 3.1 (C):** Automático a < 20% por defecto (`autoBatterySaverEnabled = true`), pero configurable por el usuario
 *   y con soporte de botón manual `"Modo ahorro"` (`manualPowerSaverOverride`).
 * - **Decisión 3.3 (B):** En batería crítica (<= 10% / <= 5% sin cargar), activa `PowerProfile.SURVIVAL`
 *   (heartbeat cada 10 s, mapa congelado, solo SOS activo y TTS `"Batería crítica, modo supervivencia"`).
 */
class BatteryAwareness(
    initialAutoSaverEnabled: Boolean = true,
    private val onTtsBatteryAlert: ((String) -> Unit)? = null
) {
    companion object {
        const val POWER_SAVER_THRESHOLD_PERCENT: Int = 20
        const val SURVIVAL_THRESHOLD_PERCENT: Int = 10
        const val CRITICAL_FIVE_PERCENT: Int = 5

        const val TTS_POWER_SAVER_ACTIVATED: String = "Batería baja, modo ahorro activado"
        const val TTS_SURVIVAL_MODE_ACTIVATED: String = "Batería crítica, modo supervivencia"
    }

    private val _state = MutableStateFlow(
        BatteryPowerState(autoBatterySaverEnabled = initialAutoSaverEnabled)
    )
    val state: StateFlow<BatteryPowerState> = _state.asStateFlow()

    private var registeredReceiver: BroadcastReceiver? = null

    /**
     * Registra la escucha de `ACTION_BATTERY_CHANGED`, `ACTION_BATTERY_LOW`,
     * `ACTION_POWER_CONNECTED` y `ACTION_POWER_DISCONNECTED` en un `Context` de Android.
     */
    fun registerSystemBatteryReceiver(context: Context) {
        unregisterSystemBatteryReceiver(context)
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val initialCapacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 85
            val initialCharging = bm?.isCharging ?: false
            evaluateBatteryState(initialCapacity, initialCharging)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        intent.action == Intent.ACTION_POWER_CONNECTED

                    val pct = if (level >= 0) ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
                    else _state.value.batteryPercent

                    evaluateBatteryState(
                        batteryPercent = pct,
                        isCharging = if (intent.action == Intent.ACTION_POWER_DISCONNECTED) false else charging
                    )
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(receiver, filter)
            registeredReceiver = receiver
        }
    }

    fun unregisterSystemBatteryReceiver(context: Context?) {
        val receiver = registeredReceiver ?: return
        registeredReceiver = null
        if (context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Synchronized
    fun setAutoBatterySaverEnabled(enabled: Boolean): BatteryPowerState {
        val current = _state.value
        return recomputeAndPublish(
            batteryPercent = current.batteryPercent,
            isCharging = current.isCharging,
            autoSaver = enabled,
            manualOverride = current.manualPowerSaverOverride
        )
    }

    @Synchronized
    fun setManualPowerSaverOverride(override: Boolean?): BatteryPowerState {
        val current = _state.value
        return recomputeAndPublish(
            batteryPercent = current.batteryPercent,
            isCharging = current.isCharging,
            autoSaver = current.autoBatterySaverEnabled,
            manualOverride = override
        )
    }

    @Synchronized
    fun evaluateBatteryState(
        batteryPercent: Int,
        isCharging: Boolean
    ): BatteryPowerState {
        val current = _state.value
        return recomputeAndPublish(
            batteryPercent = batteryPercent.coerceIn(0, 100),
            isCharging = isCharging,
            autoSaver = current.autoBatterySaverEnabled,
            manualOverride = current.manualPowerSaverOverride
        )
    }

    @Synchronized
    fun resetToNormal(): BatteryPowerState {
        _state.value = BatteryPowerState(
            batteryPercent = _state.value.batteryPercent,
            isCharging = _state.value.isCharging,
            autoBatterySaverEnabled = _state.value.autoBatterySaverEnabled,
            manualPowerSaverOverride = null,
            profile = PowerProfile.NORMAL,
            lastTtsAlertMessage = null
        )
        return _state.value
    }

    private fun recomputeAndPublish(
        batteryPercent: Int,
        isCharging: Boolean,
        autoSaver: Boolean,
        manualOverride: Boolean?
    ): BatteryPowerState {
        val prevProfile = _state.value.profile

        val nextProfile = when {
            // Decisión 3.3 (B): Batería crítica (<= 10%, incluyendo 5%) sin cargador -> Modo supervivencia obligatorio
            !isCharging && batteryPercent <= SURVIVAL_THRESHOLD_PERCENT -> PowerProfile.SURVIVAL
            // Si el usuario forzó manualmente el estado de ahorro
            manualOverride == true -> PowerProfile.POWER_SAVER
            manualOverride == false -> PowerProfile.NORMAL
            // Decisión 3.1 (C): Automático a < 20% sin cargador cuando autoBatterySaverEnabled está activo
            !isCharging && autoSaver && batteryPercent < POWER_SAVER_THRESHOLD_PERCENT -> PowerProfile.POWER_SAVER
            else -> PowerProfile.NORMAL
        }

        val ttsAlert: String? = when {
            nextProfile == PowerProfile.SURVIVAL && prevProfile != PowerProfile.SURVIVAL -> {
                onTtsBatteryAlert?.invoke(TTS_SURVIVAL_MODE_ACTIVATED)
                TTS_SURVIVAL_MODE_ACTIVATED
            }
            nextProfile == PowerProfile.POWER_SAVER && prevProfile == PowerProfile.NORMAL -> {
                onTtsBatteryAlert?.invoke(TTS_POWER_SAVER_ACTIVATED)
                TTS_POWER_SAVER_ACTIVATED
            }
            else -> _state.value.lastTtsAlertMessage
        }

        val updated = BatteryPowerState(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            autoBatterySaverEnabled = autoSaver,
            manualPowerSaverOverride = manualOverride,
            profile = nextProfile,
            lastTtsAlertMessage = ttsAlert
        )
        _state.value = updated
        return updated
    }
}
