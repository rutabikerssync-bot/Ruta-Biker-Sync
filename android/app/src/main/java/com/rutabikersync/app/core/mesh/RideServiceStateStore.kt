package com.rutabikersync.app.core.mesh

import android.content.Context
import android.content.SharedPreferences

/**
 * Estado mínimo persistido de la rodada activa para reconstruir [RideService]
 * cuando Android lo reinicia vía `START_STICKY` con `intent == null`.
 */
data class PersistedRideParams(
    val rideId: String,
    val localShortId: UShort,
    val manifestKeyHex: String,
    val expectedPeerShortIds: Set<UShort>,
    val forceSimulated: Boolean,
    val bleModeOverrideCode: String?,
    val advertisingBurstDurationMs: Int,
    val relayEnabled: Boolean,
    val txRxRoleCode: String?,
    val unfilteredScanDiagnostic: Boolean,
    val advertisingDeliveryModeCode: String?
)

interface RideRecoveryStore {
    fun save(params: PersistedRideParams)
    fun load(): PersistedRideParams?
    fun clear()
}

class RideServiceStateStore(context: Context) : RideRecoveryStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun save(params: PersistedRideParams) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_RIDE_ID, params.rideId)
            .putInt(KEY_LOCAL_SHORT_ID, params.localShortId.toInt())
            .putString(KEY_MANIFEST_KEY_HEX, params.manifestKeyHex)
            .putString(
                KEY_EXPECTED_PEERS,
                params.expectedPeerShortIds.joinToString(",") { it.toInt().toString() }
            )
            .putBoolean(KEY_FORCE_SIMULATED, params.forceSimulated)
            .putString(KEY_BLE_MODE, params.bleModeOverrideCode)
            .putInt(KEY_ADV_BURST_MS, params.advertisingBurstDurationMs)
            .putBoolean(KEY_RELAY_ENABLED, params.relayEnabled)
            .putString(KEY_TX_RX_ROLE, params.txRxRoleCode)
            .putBoolean(KEY_UNFILTERED_SCAN, params.unfilteredScanDiagnostic)
            .putString(KEY_ADV_DELIVERY_MODE, params.advertisingDeliveryModeCode)
            .commit()
    }

    override fun load(): PersistedRideParams? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val rideId = prefs.getString(KEY_RIDE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val manifestKeyHex = prefs.getString(KEY_MANIFEST_KEY_HEX, null)?.takeIf { it.isNotBlank() } ?: return null
        val shortIdInt = prefs.getInt(KEY_LOCAL_SHORT_ID, -1)
        if (shortIdInt <= 0) return null
        val expectedPeers = prefs.getString(KEY_EXPECTED_PEERS, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id in 1..65535 }?.toUShort() }
            .toSet()
        return PersistedRideParams(
            rideId = rideId,
            localShortId = shortIdInt.toUShort(),
            manifestKeyHex = manifestKeyHex,
            expectedPeerShortIds = expectedPeers,
            forceSimulated = prefs.getBoolean(KEY_FORCE_SIMULATED, false),
            bleModeOverrideCode = prefs.getString(KEY_BLE_MODE, null),
            advertisingBurstDurationMs = prefs.getInt(KEY_ADV_BURST_MS, 500),
            relayEnabled = prefs.getBoolean(KEY_RELAY_ENABLED, true),
            txRxRoleCode = prefs.getString(KEY_TX_RX_ROLE, null),
            unfilteredScanDiagnostic = prefs.getBoolean(KEY_UNFILTERED_SCAN, false),
            advertisingDeliveryModeCode = prefs.getString(KEY_ADV_DELIVERY_MODE, null)
        )
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "rbs_active_ride_state_prefs"
        private const val KEY_ACTIVE = "active"
        private const val KEY_RIDE_ID = "ride_id"
        private const val KEY_LOCAL_SHORT_ID = "local_short_id"
        private const val KEY_MANIFEST_KEY_HEX = "manifest_key_hex"
        private const val KEY_EXPECTED_PEERS = "expected_peers"
        private const val KEY_FORCE_SIMULATED = "force_simulated"
        private const val KEY_BLE_MODE = "ble_mode"
        private const val KEY_ADV_BURST_MS = "adv_burst_ms"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
        private const val KEY_TX_RX_ROLE = "tx_rx_role"
        private const val KEY_UNFILTERED_SCAN = "unfiltered_scan"
        private const val KEY_ADV_DELIVERY_MODE = "adv_delivery_mode"
    }
}

/**
 * Orquestador puro JVM para el reinicio `START_STICKY` cuando `onStartCommand(null)`
 * ocurre tras un cierre del proceso por el sistema operativo.
 */
class StickyRideRecoveryHandler(
    private val store: RideRecoveryStore,
    private val startForegroundFirst: (PersistedRideParams) -> Boolean,
    private val restoreRide: (PersistedRideParams) -> Unit,
    private val stopWithoutRide: () -> Unit
) {
    fun handleNullIntent(): Boolean {
        val params = store.load()
        if (params == null) {
            stopWithoutRide()
            return false
        }
        if (!startForegroundFirst(params)) {
            return false
        }
        restoreRide(params)
        return true
    }
}
