package com.rutabikersync.app.feature.fieldtest

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.core.power.ManufacturerBatteryGuide
import com.rutabikersync.app.core.power.ManufacturerBatteryGuideCard
import com.rutabikersync.app.core.transport.BleAdvTransport
import android.util.Log
import androidx.compose.ui.window.Dialog
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rutabikersync.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FieldTestConfigSnapshot(
    val testStartEpochMs: Long,
    val localNodeShortId: Int,
    val totalPhonesInTest: Int,
    val nodeRole: String,
    val expectedPeerShortIds: List<Int>,
    val forcedBleTransportMode: String?,
    val effectiveBleTransportMode: String,
    val advertisingBurstDurationMs: Int,
    val scanMode: String = "SCAN_MODE_LOW_LATENCY (2)",
    val matchMode: String = "MATCH_MODE_AGGRESSIVE (1)",
    val callbackType: String = "CALLBACK_TYPE_ALL_MATCHES (1)",
    val relayEnabled: Boolean = true,
    val txRxRole: BleAdvTransport.TxRxRole = BleAdvTransport.TxRxRole.BOTH,
    val unfilteredScanDiagnostic: Boolean = false,
    val bleCapabilitiesSnapshot: BleAdvTransport.BleCapabilities,
    val radioAtStart: RadioEnvironmentSnapshot? = null,
    val advertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST
)

data class RadioEnvironmentSnapshot(
    val timestampUtc: String = "",
    val wifiEnabled: Boolean = false,
    val mobileDataEnabled: Boolean? = null,
    val airplaneModeOn: Boolean = false,
    val btEnabled: Boolean = true,
    val connectedBtAudio: Boolean = false,
    val otherConnectedBtDevices: Boolean = false,
    val nearbySharingState: String = "NO_DISPONIBLE_POR_API",
    val fastPairLikelyActive: Boolean = false
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("timestampUtc", timestampUtc)
        put("airplaneModeOn", airplaneModeOn)
        put("wifiEnabled", wifiEnabled)
        put("mobileDataEnabled", mobileDataEnabled ?: JSONObject.NULL)
        put("btEnabled", btEnabled)
        put("connectedBtAudio", connectedBtAudio)
        put("otherConnectedBtDevices", otherConnectedBtDevices)
        put("nearbySharingState", nearbySharingState)
        put("fastPairLikelyActive", fastPairLikelyActive)
    }
}

data class CsvRowCounts(
    val tx: Int = 0,
    val rx: Int = 0,
    val relayTx: Int = 0
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("tx", tx)
        put("rx", rx)
        put("relay_tx", relayTx)
    }
}

fun parseCsvRowCounts(csvContent: String): CsvRowCounts {
    var tx = 0
    var rx = 0
    var relayTx = 0
    val lines = csvContent.lineSequence().drop(1)
    for (line in lines) {
        if (line.isBlank()) continue
        val parts = line.split(',')
        val dir = if (parts.size >= 9) parts[8].trim() else parts.last().trim()
        when (dir) {
            "TX" -> tx++
            "RX" -> rx++
            "RELAY_TX" -> relayTx++
        }
    }
    return CsvRowCounts(tx = tx, rx = rx, relayTx = relayTx)
}

fun queryRadioEnvironment(context: Context): RadioEnvironmentSnapshot {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val timestampUtc = sdf.format(Date())

    val airplaneModeOn = runCatching {
        val global = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        if (global) true else {
            @Suppress("DEPRECATION")
            Settings.System.getInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 0) != 0
        }
    }.getOrDefault(false)

    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val wifiActiveTransport = runCatching {
        val net = connManager?.activeNetwork
        val caps = connManager?.getNetworkCapabilities(net)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false) || wifiActiveTransport

    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val cellularActiveTransport = runCatching {
        val net = connManager?.activeNetwork
        val caps = connManager?.getNetworkCapabilities(net)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)
    val mobileDataEnabled = if (airplaneModeOn) {
        false
    } else {
        runCatching {
            cellularActiveTransport || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telephonyManager?.isDataEnabled == true)
        }.getOrDefault(cellularActiveTransport)
    }

    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val btAdapter = btManager?.adapter
    val btEnabled = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)

    val connectedBtAudio = runCatching {
        btAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
        btAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(false)

    val otherConnectedBtDevices = runCatching {
        val bonded = btAdapter?.bondedDevices ?: emptySet()
        bonded.isNotEmpty() && (
            btAdapter?.getProfileConnectionState(BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED ||
            btAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
            btAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        )
    }.getOrDefault(false)

    val nearbyState = runCatching {
        val quickShareVal = Settings.Secure.getInt(context.contentResolver, "nearby_sharing_slice", -1)
        val nearbyEnabled = Settings.Secure.getInt(context.contentResolver, "nearby_sharing_enabled", -1)
        when {
            quickShareVal == 1 || nearbyEnabled == 1 -> "ACTIVADO"
            quickShareVal == 0 || nearbyEnabled == 0 -> "DESACTIVADO"
            else -> "NO_DISPONIBLE_POR_API"
        }
    }.getOrElse { "ERROR: ${it.message}" }

    val fastPairLikely = runCatching {
        context.packageManager.getPackageInfo("com.google.android.gms", 0) != null
    }.getOrDefault(false)

    return RadioEnvironmentSnapshot(
        timestampUtc = timestampUtc,
        wifiEnabled = wifiEnabled,
        mobileDataEnabled = mobileDataEnabled,
        airplaneModeOn = airplaneModeOn,
        btEnabled = btEnabled,
        connectedBtAudio = connectedBtAudio,
        otherConnectedBtDevices = otherConnectedBtDevices,
        nearbySharingState = nearbyState,
        fastPairLikelyActive = fastPairLikely
    )
}
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Pantalla de prueba de campo BLE para el Bloque 3.6 (Fase 3).
 *
 * Exclusiva del build/flavor `fieldTest`:
 * 1. Botón gigante de "INICIAR PRUEBA BLE" (>= 72 dp de alto para uso con guantes).
 * 2. Contador en vivo de paquetes enviados/recibidos, peers conectados, % de entrega e islas detectadas (vía RideService).
 * 3. Botón de "EXPORTAR MÉTRICAS" que genera y comparte JSON estructurado con resultados medidos.
 * 4. Botón de "FORZAR SIMULACIÓN" para validar el convoy multi-salto sin requerir otra moto.
 */
@Composable
fun FieldTestScreen(
    onOpenLiveMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val rideState by RideService.sharedState.collectAsState()
    val metrics by RideService.sharedMetrics.collectAsState()

    var screenKeepOnActiveDuringTest by remember { mutableStateOf(false) }

    var forceSimulation by remember { mutableStateOf(false) }
    var totalPhonesInTest by remember { mutableIntStateOf(2) }
    var selectedNodeShortId by remember { mutableIntStateOf(1) }
    var selectedBurstDurationMs by remember { mutableIntStateOf(BleAdvTransport.DEFAULT_ADVERTISING_BURST_DURATION_MS) }
    var manualDistanceMetersText by remember { mutableStateOf("1") }
    var testStartEpochMs by remember { mutableLongStateOf(0L) }
    var testEndEpochMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var exportedJsonText by remember { mutableStateOf<String?>(null) }
    var exportedFilePath by remember { mutableStateOf<String?>(null) }
    var relayEnabled by remember { mutableStateOf(true) }
    var txRxRole by remember { mutableStateOf(BleAdvTransport.TxRxRole.BOTH) }
    var unfilteredScanDiagnostic by remember { mutableStateOf(false) }
    var advertisingDeliveryMode by remember { mutableStateOf(BleAdvTransport.AdvertisingDeliveryMode.BURST) }
    var radioAtStart by remember { mutableStateOf<RadioEnvironmentSnapshot?>(null) }
    var radioAtEnd by remember { mutableStateOf<RadioEnvironmentSnapshot?>(null) }
    var activeTestSnapshot by remember { mutableStateOf<FieldTestConfigSnapshot?>(null) }
    var lastTestSnapshot by remember { mutableStateOf<FieldTestConfigSnapshot?>(null) }
    var lastSavedUniqueJsonName by remember { mutableStateOf<String?>(null) }
    var lastSavedUniqueCsvName by remember { mutableStateOf<String?>(null) }
    var lastFinishedDurationSeconds by remember { mutableStateOf<Long?>(null) }
    var showShortRunWarningBanner by remember { mutableStateOf(false) }
    var shortRunWarningDurationSec by remember { mutableStateOf(0L) }
    var hasAutoSavedCurrentRun by remember { mutableStateOf(false) }
    var showBatteryGuideDialog by remember { mutableStateOf(false) }
    var showSimulatedFallbackPrompt by remember { mutableStateOf(false) }
    var permanentlyDeniedDetected by remember { mutableStateOf(false) }
    var showBluetoothDisabledPrompt by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val isRunning = rideState.lifecycleState == RideLifecycleState.RUNNING ||
        rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP

    // Mantener la pantalla encendida durante la prueba (FLAG_KEEP_SCREEN_ON y view.keepScreenOn) para evitar que Android pause el escáner BLE
    DisposableEffect(isRunning) {
        val window = activity?.window
        if (isRunning) {
            screenKeepOnActiveDuringTest = true
            view.keepScreenOn = true
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i("FieldTestScreen", "Pantalla mantenida encendida (FLAG_KEEP_SCREEN_ON y view.keepScreenOn activos)")
        } else {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val batteryGuide = remember { ManufacturerBatteryGuide() }
    val batterySnapshot = remember { batteryGuide.snapshot() }
    val bleCaps = remember(isRunning) { BleAdvTransport(context).queryCapabilities() }
    var selectedTransportMode by remember {
        mutableStateOf(
            when {
                bleCaps.extendedAdvertisingSupported && bleCaps.codedPhySupported ->
                    BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY
                bleCaps.extendedAdvertisingSupported ->
                    BleAdvTransport.TransportModeOverride.EXTENDED_1M
                else ->
                    BleAdvTransport.TransportModeOverride.LEGACY_1M
            }
        )
    }

    val expectedConvoyPeers = remember(totalPhonesInTest, selectedNodeShortId) {
        RideService.computeExpectedPeerIds(totalPhonesInTest, selectedNodeShortId)
    }

    fun nodeRoleLabel(id: Int, total: Int): String = when {
        id == 1 -> "Punta (#1 Líder)"
        id == total -> "Cierre (#$id Sweep)"
        else -> "Medio (#$id)"
    }

    fun autoSaveCurrentTest(isManualExport: Boolean = false): String? {
        if (!isManualExport && hasAutoSavedCurrentRun) return exportedJsonText
        val snapshot = activeTestSnapshot ?: lastTestSnapshot
        val startMs = snapshot?.testStartEpochMs
            ?: if (testStartEpochMs > 0L) testStartEpochMs
            else rideState.startedAtEpochMs
        val nowMs = System.currentTimeMillis()
        val endMs = if (testEndEpochMs > 0L) {
            testEndEpochMs
        } else if (isRunning) {
            nowMs
        } else if (startMs > 0L && elapsedSeconds > 0L) {
            startMs + elapsedSeconds * 1000L
        } else {
            nowMs
        }
        val durationSec = if (startMs > 0L && endMs >= startMs) {
            ((endMs - startMs) / 1000L).coerceAtLeast(0L)
        } else {
            elapsedSeconds
        }
        lastFinishedDurationSeconds = durationSec

        if (durationSec < 10L) {
            showShortRunWarningBanner = true
            shortRunWarningDurationSec = durationSec
        } else {
            showShortRunWarningBanner = false
        }

        val isScreenKeepOn = screenKeepOnActiveDuringTest ||
            (activity?.window?.attributes?.let { (it.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0 } == true) ||
            view.keepScreenOn ||
            isRunning

        val fileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val modeForFilename = snapshot?.effectiveBleTransportMode ?: bleCaps.resolveEffectiveMode(selectedTransportMode)
        val modeClean = when (modeForFilename) {
            "EXTENDED_CODED_PHY" -> "coded"
            "EXTENDED_1M" -> "ext1m"
            "LEGACY_1M" -> "legacy"
            else -> modeForFilename.lowercase().replace("_", "")
        }
        val parsedDist = manualDistanceMetersText.trim().toDoubleOrNull()
        val distStr = parsedDist?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
        } ?: manualDistanceMetersText.trim().ifEmpty { "1" }
        val exportShortId = snapshot?.localNodeShortId ?: selectedNodeShortId

        val uniqueJsonName = "fieldtest_${fileTimestamp}_${modeClean}_${distStr}m_${exportShortId}.json"
        val uniqueCsvName = "fieldtest_${fileTimestamp}_${modeClean}_${distStr}m_${exportShortId}.csv"
        val csvContent = RideService.exportPacketsCsv()

        val extDir = context.getExternalFilesDir(null)
        val extUniqueCsv = extDir?.let { File(it, uniqueCsvName) }
        val intUniqueCsv = File(context.filesDir, uniqueCsvName)
        val csvPathForJson = extUniqueCsv?.absolutePath ?: intUniqueCsv.absolutePath

        val endRadioSnapshot = radioAtEnd ?: queryRadioEnvironment(context).also { radioAtEnd = it }

        val reportJson = generateMetricsJson(
            context = context,
            snapshot = snapshot,
            nodeRole = nodeRoleLabel(exportShortId, snapshot?.totalPhonesInTest ?: totalPhonesInTest),
            shortId = exportShortId,
            totalPhonesInTest = snapshot?.totalPhonesInTest ?: totalPhonesInTest,
            selectedBurstDurationMs = selectedBurstDurationMs,
            expectedPeerIdsFallback = expectedConvoyPeers.map { it.toInt() }.sorted(),
            durationSeconds = durationSec,
            manualDistanceMeters = manualDistanceMetersText.trim().toDoubleOrNull(),
            manualDistanceText = manualDistanceMetersText.trim(),
            testStartEpochMs = startMs,
            testEndEpochMs = endMs,
            rideState = rideState,
            metrics = metrics,
            bleCaps = bleCaps,
            selectedTransportMode = selectedTransportMode,
            screenKeepOnActive = isScreenKeepOn,
            csvPath = csvPathForJson,
            radioAtStart = radioAtStart ?: snapshot?.radioAtStart,
            radioAtEnd = endRadioSnapshot,
            csvContent = csvContent,
            advertisingDeliveryMode = snapshot?.advertisingDeliveryMode ?: advertisingDeliveryMode
        )

        exportedJsonText = reportJson
        lastSavedUniqueJsonName = uniqueJsonName
        lastSavedUniqueCsvName = uniqueCsvName
        hasAutoSavedCurrentRun = true

        val extUniqueJson = extDir?.let { File(it, uniqueJsonName) }
        val extLatestJson = extDir?.let { File(it, "fieldtest_metrics.json") }
        val extLatestCsv = extDir?.let { File(it, "fieldtest_metrics.csv") }
        runCatching {
            extUniqueJson?.writeText(reportJson)
            extLatestJson?.writeText(reportJson)
            extUniqueCsv?.writeText(csvContent)
            extLatestCsv?.writeText(csvContent)
        }
        val intUniqueJson = File(context.filesDir, uniqueJsonName)
        val intLatestJson = File(context.filesDir, "fieldtest_metrics.json")
        val intLatestCsv = File(context.filesDir, "fieldtest_metrics.csv")
        runCatching {
            intUniqueJson.writeText(reportJson)
            intLatestJson.writeText(reportJson)
            intUniqueCsv.writeText(csvContent)
            intLatestCsv.writeText(csvContent)
        }
        val savedPath = extUniqueJson?.absolutePath ?: intUniqueJson.absolutePath
        exportedFilePath = savedPath

        Log.i("FieldTestScreen", "Auto-saved metrics: $uniqueJsonName ($savedPath) duration=${durationSec}s")
        return reportJson
    }

    fun launchRideServiceNow(useSimulated: Boolean) {
        val now = System.currentTimeMillis()
        testStartEpochMs = now
        testEndEpochMs = 0L
        hasAutoSavedCurrentRun = false
        showShortRunWarningBanner = false
        screenKeepOnActiveDuringTest = true
        view.keepScreenOn = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val startRadio = queryRadioEnvironment(context)
        radioAtStart = startRadio
        radioAtEnd = null

        val snapshot = FieldTestConfigSnapshot(
            testStartEpochMs = now,
            localNodeShortId = selectedNodeShortId,
            totalPhonesInTest = totalPhonesInTest,
            nodeRole = nodeRoleLabel(selectedNodeShortId, totalPhonesInTest),
            expectedPeerShortIds = expectedConvoyPeers.map { it.toInt() }.sorted(),
            forcedBleTransportMode = selectedTransportMode.code,
            effectiveBleTransportMode = bleCaps.resolveEffectiveMode(selectedTransportMode),
            advertisingBurstDurationMs = selectedBurstDurationMs,
            scanMode = "SCAN_MODE_LOW_LATENCY (2)",
            matchMode = "MATCH_MODE_AGGRESSIVE (1)",
            callbackType = "CALLBACK_TYPE_ALL_MATCHES (1)",
            relayEnabled = relayEnabled,
            txRxRole = txRxRole,
            unfilteredScanDiagnostic = unfilteredScanDiagnostic,
            bleCapabilitiesSnapshot = bleCaps,
            radioAtStart = startRadio,
            advertisingDeliveryMode = advertisingDeliveryMode
        )
        activeTestSnapshot = snapshot
        lastTestSnapshot = snapshot

        RideService.dispatchStartRide(
            context = context,
            rideId = "field-test-$now",
            localShortId = selectedNodeShortId,
            forceSimulated = useSimulated,
            bleModeOverride = selectedTransportMode,
            advertisingBurstDurationMs = selectedBurstDurationMs,
            expectedPeerShortIds = expectedConvoyPeers,
            relayEnabled = relayEnabled,
            txRxRole = txRxRole,
            unfilteredScanDiagnostic = unfilteredScanDiagnostic,
            advertisingDeliveryMode = advertisingDeliveryMode
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val meshViable = RideService.isMeshViable(context)
        if (meshViable) {
            showSimulatedFallbackPrompt = false
            permanentlyDeniedDetected = false
            if (!forceSimulation && !RideService.isBluetoothAdapterEnabled(context)) {
                Log.w("RideService", "BluetoothDisabled: permissions granted but Bluetooth adapter is OFF; prompting user to enable Bluetooth")
                showBluetoothDisabledPrompt = true
            } else {
                showBluetoothDisabledPrompt = false
                launchRideServiceNow(useSimulated = forceSimulation)
            }
        } else {
            val isPermDenied = activity != null && requiredPermissions.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            Log.w("RideService", "PermissionsDenied: meshViable=false permanentlyDenied=$isPermDenied")
            permanentlyDeniedDetected = isPermDenied
            showSimulatedFallbackPrompt = true
        }
    }

    // Cronómetro en vivo sincronizado con el inicio real de RideService
    LaunchedEffect(isRunning, rideState.startedAtEpochMs) {
        if (isRunning) {
            val baseStartMs = if (rideState.startedAtEpochMs > 0L) {
                rideState.startedAtEpochMs
            } else {
                if (testStartEpochMs == 0L) testStartEpochMs = System.currentTimeMillis()
                testStartEpochMs
            }
            testStartEpochMs = baseStartMs
            testEndEpochMs = 0L
            while (isActive) {
                elapsedSeconds = ((System.currentTimeMillis() - baseStartMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            if (testStartEpochMs > 0L) {
                if (testEndEpochMs == 0L) {
                    testEndEpochMs = System.currentTimeMillis()
                }
                if (radioAtEnd == null) {
                    radioAtEnd = queryRadioEnvironment(context)
                }
                if (!hasAutoSavedCurrentRun) {
                    autoSaveCurrentTest(isManualExport = false)
                }
            }
        }
    }

    LaunchedEffect(bleCaps) {
        Log.i(
            "RideService",
            "Table3_1_BleCapabilities model=${Build.MODEL} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "btSupported=${bleCaps.bluetoothSupported} btEnabled=${bleCaps.bluetoothEnabled} " +
                "multiAdv=${bleCaps.multipleAdvertisementSupported} extAdv=${bleCaps.extendedAdvertisingSupported} " +
                "codedPhy=${bleCaps.codedPhySupported} phy2M=${bleCaps.le2MPhySupported} " +
                "maxAdvDataLen=${bleCaps.maxAdvertisingDataLength} mode=${bleCaps.recommendedMode}"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabecera: Título y acceso rápido a Guía de Batería y Mapa
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Bloque 3.6 · Prueba de Campo BLE",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    color = BikerColors.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "BLE: ${bleCaps.recommendedMode} (BT=${if (bleCaps.bluetoothEnabled) "ON" else "OFF"}, ExtAdv=${bleCaps.extendedAdvertisingSupported}, CodedPHY=${bleCaps.codedPhySupported})",
                    color = if (bleCaps.bluetoothEnabled) BikerColors.StatusGreen else BikerColors.StatusYellow,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "PHY 2M: ${bleCaps.le2MPhySupported} · MaxAdvData: ${bleCaps.maxAdvertisingDataLength} B",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showBatteryGuideDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BikerColors.PrimaryAmber)
                ) {
                    Icon(AppIcons.BatteryStd, contentDescription = "Batería OEM", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(text = "OEM", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (onOpenLiveMap != null) {
                    OutlinedButton(
                        onClick = onOpenLiveMap,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BikerColors.TextPrimary)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = "Mapa en vivo", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(text = "Mapa", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Selector de Nº de Teléfonos (2 a 5) e ID / Rol de este teléfono
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Nº de teléfonos en la prueba (2 a 5):",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (2..5).forEach { count ->
                        FilterChip(
                            selected = totalPhonesInTest == count,
                            onClick = {
                                if (!isRunning) {
                                    totalPhonesInTest = count
                                    if (selectedNodeShortId > count) {
                                        selectedNodeShortId = count
                                    }
                                }
                            },
                            enabled = !isRunning,
                            label = { Text("$count telfs", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }

                Text(
                    text = "ID de este teléfono (1 a $totalPhonesInTest) · ${nodeRoleLabel(selectedNodeShortId, totalPhonesInTest)}:",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..totalPhonesInTest).forEach { id ->
                        val shortLabel = when {
                            id == 1 -> "#1 Punta"
                            id == totalPhonesInTest -> "#$id Cierre"
                            else -> "#$id Medio"
                        }
                        FilterChip(
                            selected = selectedNodeShortId == id,
                            onClick = { if (!isRunning) selectedNodeShortId = id },
                            enabled = !isRunning,
                            label = { Text(shortLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }

                Text(
                    text = "Pares esperados registrados al iniciar: ${expectedConvoyPeers.sorted().joinToString(", ") { "#$it" }}",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Card de Capacidades de Hardware BLE (Emisión y Recepción)
        val canEmitLegacy = bleCaps.multipleAdvertisementSupported
        val canHearLegacy = bleCaps.bluetoothSupported
        val canEmitExt = bleCaps.extendedAdvertisingSupported
        val canHearExt = bleCaps.extendedAdvertisingSupported
        val canEmitCoded = bleCaps.extendedAdvertisingSupported && bleCaps.codedPhySupported
        val canHearCoded = bleCaps.extendedAdvertisingSupported && bleCaps.codedPhySupported

        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Capacidades BLE del Hardware (Emisión / Recepción):",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                CapabilityRow("Legacy 1M (Estándar BLE)", emit = canEmitLegacy, hear = canHearLegacy)
                CapabilityRow("Extendido 1M (BLE 5.0+)", emit = canEmitExt, hear = canHearExt)
                CapabilityRow("Extendido Coded PHY (Largo Alcance)", emit = canEmitCoded, hear = canHearCoded)

                Text(
                    text = "Longitud máx anuncio: ${bleCaps.maxAdvertisingDataLength} B · 2M PHY: ${if (bleCaps.le2MPhySupported) "SÍ" else "NO"} · Recomendado: ${bleCaps.recommendedMode}",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Selector de Modo de Transporte BLE + Duración de Ráfaga F3 + ScanSettings
        val effectiveBleMode = bleCaps.resolveEffectiveMode(selectedTransportMode)
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Modo de Transporte BLE forzado ($effectiveBleMode):",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BleAdvTransport.TransportModeOverride.entries.forEach { modeOption ->
                        val isSupported = when (modeOption) {
                            BleAdvTransport.TransportModeOverride.LEGACY_1M -> canEmitLegacy
                            BleAdvTransport.TransportModeOverride.EXTENDED_1M -> canEmitExt
                            BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY -> canEmitCoded
                        }
                        FilterChip(
                            selected = selectedTransportMode == modeOption,
                            onClick = { if (!isRunning && isSupported) selectedTransportMode = modeOption },
                            enabled = !isRunning && isSupported,
                            label = {
                                Text(
                                    text = if (isSupported) modeOption.displayLabel else "${modeOption.displayLabel} (No soportado)",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark,
                                disabledContainerColor = Color(0xFF1E293B),
                                disabledLabelColor = Color(0xFF64748B)
                            )
                        )
                    }
                }

                val isCurrentSelectionSupported = when (selectedTransportMode) {
                    BleAdvTransport.TransportModeOverride.LEGACY_1M -> canEmitLegacy
                    BleAdvTransport.TransportModeOverride.EXTENDED_1M -> canEmitExt
                    BleAdvTransport.TransportModeOverride.EXTENDED_CODED_PHY -> canEmitCoded
                }
                if (!isCurrentSelectionSupported) {
                    Text(
                        text = "⚠ Hardware no soporta ${selectedTransportMode.displayLabel}. Se degradará automáticamente a: $effectiveBleMode",
                        color = BikerColors.StatusYellow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Anuncio BLE: RÁFAGA 1 s / CONTINUO",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BleAdvTransport.AdvertisingDeliveryMode.entries.forEach { mode ->
                        FilterChip(
                            selected = advertisingDeliveryMode == mode,
                            onClick = { if (!isRunning) advertisingDeliveryMode = mode },
                            enabled = !isRunning,
                            label = { Text(mode.displayLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }

                if (advertisingDeliveryMode == BleAdvTransport.AdvertisingDeliveryMode.BURST) {
                    Text(
                        text = "Duración de ráfaga F3 (igual para Legacy y Extendido): ${selectedBurstDurationMs} ms",
                        color = BikerColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BleAdvTransport.SUPPORTED_BURST_DURATIONS_MS.forEach { burstMs ->
                            FilterChip(
                                selected = selectedBurstDurationMs == burstMs,
                                onClick = { if (!isRunning) selectedBurstDurationMs = burstMs },
                                enabled = !isRunning,
                                label = { Text("${burstMs} ms", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BikerColors.PrimaryAmber,
                                    selectedLabelColor = BikerColors.BackgroundDark
                                )
                            )
                        }
                    }
                }

                Text(
                    text = "ScanSettings: ${BleAdvTransport.describeScanSettings(effectiveBleMode)}",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Toggle: Forzar Simulación (para pruebas sin otra moto)
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FORZAR SIMULACIÓN",
                        color = if (forceSimulation) BikerColors.PrimaryAmber else BikerColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (forceSimulation) {
                            "Activo: Simula convoy multi-salto (5 nodos) en memoria"
                        } else {
                            "Hardware BLE Real: Transmite por BleAdvTransport (1M/Extended PHY)"
                        },
                        color = BikerColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Switch(
                    checked = forceSimulation,
                    onCheckedChange = { if (!isRunning) forceSimulation = it },
                    enabled = !isRunning,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BikerColors.PrimaryAmber,
                        checkedTrackColor = BikerColors.PrimaryAmber.copy(alpha = 0.5f)
                    )
                )
            }
        }

        // Configuración de Relé, Rol de Tráfico y Diagnóstico de Escáner
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Control de Relé y Tráfico (Protocolo de Campo):",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // 1. Relé ON/OFF
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Relé Mesh (Retransmisión)",
                            color = if (relayEnabled) BikerColors.StatusGreen else BikerColors.StatusYellow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (relayEnabled) {
                                "ACTIVO: retransmite ráfagas ajenas (convoy normal)"
                            } else {
                                "DESACTIVADO (R1): no retransmite (alcance directo puro)"
                            },
                            color = BikerColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = relayEnabled,
                        onCheckedChange = { if (!isRunning) relayEnabled = it },
                        enabled = !isRunning,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BikerColors.StatusGreen,
                            checkedTrackColor = BikerColors.StatusGreen.copy(alpha = 0.5f)
                        )
                    )
                }

                // 2. Rol de tráfico (Ambos, Solo TX, Solo RX)
                Text(
                    text = "Rol de Tráfico en la Prueba:",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BleAdvTransport.TxRxRole.entries.forEach { role ->
                        FilterChip(
                            selected = txRxRole == role,
                            onClick = { if (!isRunning) txRxRole = role },
                            enabled = !isRunning,
                            label = { Text(role.displayLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }

                // 3. Escáner amplio diagnóstico (sin filtro UUID)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diagnóstico: Escáner sin filtro UUID",
                            color = if (unfilteredScanDiagnostic) BikerColors.StatusRed else BikerColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (unfilteredScanDiagnostic) {
                                "⚠ Activo: recibe todos los anuncios BLE (solo diagnóstico)."
                            } else {
                                "Normal: filtro ServiceData UUID (requisito Android 8.1+)"
                            },
                            color = BikerColors.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = unfilteredScanDiagnostic,
                        onCheckedChange = { if (!isRunning) unfilteredScanDiagnostic = it },
                        enabled = !isRunning,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BikerColors.StatusRed,
                            checkedTrackColor = BikerColors.StatusRed.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        rideState.lastStartError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = BikerColors.StatusRed.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠ $err",
                    color = BikerColors.StatusRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (showSimulatedFallbackPrompt) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Sin Bluetooth/Ubicación no hay mesh real. ¿Quieres probar en modo simulado?",
                        color = BikerColors.StatusYellow,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                forceSimulation = true
                                showSimulatedFallbackPrompt = false
                                launchRideServiceNow(useSimulated = true)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                        ) {
                            Text(
                                text = "Probar en modo simulado",
                                color = BikerColors.BackgroundDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        if (permanentlyDeniedDetected) {
                            OutlinedButton(
                                onClick = {
                                    val settingsIntent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(settingsIntent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Abrir ajustes de la app",
                                    color = BikerColors.PrimaryAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showBluetoothDisabledPrompt) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚠ El Bluetooth del teléfono está apagado. Enciéndelo para iniciar la prueba BLE real.",
                        color = BikerColors.StatusYellow,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                showBluetoothDisabledPrompt = false
                                runCatching {
                                    context.startActivity(
                                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }.onFailure {
                                    context.startActivity(
                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                        ) {
                            Text(
                                text = "Encender Bluetooth",
                                color = BikerColors.BackgroundDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                forceSimulation = true
                                showBluetoothDisabledPrompt = false
                                launchRideServiceNow(useSimulated = true)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Probar en modo simulado",
                                color = BikerColors.PrimaryAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Aviso si POST_NOTIFICATIONS está rechazado en Android 13+ (API 33+)
        if (!RideService.isPostNotificationsGranted(context)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BikerColors.StatusYellow.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠ Permiso de notificaciones (POST_NOTIFICATIONS) rechazado: el servicio sigue activo pero Android oculta el aviso persistente.",
                    color = BikerColors.StatusYellow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Selector / Entrada manual de distancia de prueba (para curva distancia -> entrega)
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Distancia de la prueba (manual para curva distancia -> % entrega):",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Selecciona o escribe la distancia física entre teléfonos (se registrará en el JSON):",
                    color = BikerColors.TextSecondary,
                    fontSize = 11.sp
                )

                val distancePresets = listOf("1", "25", "50", "100", "150", "200", "300")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    distancePresets.forEach { dist ->
                        FilterChip(
                            selected = manualDistanceMetersText == dist,
                            onClick = { manualDistanceMetersText = dist },
                            label = { Text(if (dist == "1") "1m (B0)" else "${dist}m", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = manualDistanceMetersText,
                    onValueChange = { manualDistanceMetersText = it },
                    label = { Text("Distancia escrita en metros", color = BikerColors.TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("Ej: 1, 25, 50, 100, 150, 200, 300...", color = BikerColors.TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BikerColors.PrimaryAmber,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = BikerColors.TextPrimary,
                        unfocusedTextColor = BikerColors.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showShortRunWarningBanner) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "ADVERTENCIA: Corrida muy corta (${shortRunWarningDurationSec} s)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "La última corrida duró menos de 10 s. Los datos pueden ser incompletos o fruto de una pulsación accidental.",
                            color = Color(0xFFFECACA),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 1. BOTÓN GIGANTE DE ACCIÓN (>= 72 dp de alto según modo guantes)
        val buttonMinHeight = 76.dp
        if (!isRunning) {
            Button(
                onClick = {
                    if (forceSimulation) {
                        showSimulatedFallbackPrompt = false
                        showBluetoothDisabledPrompt = false
                        launchRideServiceNow(useSimulated = true)
                    } else if (RideService.isMeshViable(context)) {
                        showSimulatedFallbackPrompt = false
                        if (!RideService.isBluetoothAdapterEnabled(context)) {
                            Log.w("RideService", "BluetoothDisabled: Bluetooth adapter is OFF; prompting user to enable Bluetooth")
                            showBluetoothDisabledPrompt = true
                        } else {
                            showBluetoothDisabledPrompt = false
                            launchRideServiceNow(useSimulated = false)
                        }
                    } else {
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = buttonMinHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = BikerColors.BackgroundDark,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "INICIAR PRUEBA BLE",
                        color = BikerColors.BackgroundDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    testEndEpochMs = System.currentTimeMillis()
                    radioAtEnd = queryRadioEnvironment(context)
                    autoSaveCurrentTest(isManualExport = false)
                    RideService.dispatchImmediateStopRide(context)
                    val name = lastSavedUniqueJsonName ?: "fieldtest.json"
                    Toast.makeText(context, "Prueba detenida y guardada:\n$name", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = buttonMinHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "DETENER PRUEBA BLE",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 2. CONTADOR EN VIVO DE MÉTRICAS MESH (usando RideService)
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MÉTRICAS EN TIEMPO REAL",
                        color = BikerColors.PrimaryAmber,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Duración: ${formatElapsed(elapsedSeconds)}",
                        color = BikerColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Estado de conectividad y peers conectados
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val connectedPeers = rideState.connectedRidersCount
                    MetricBox(
                        title = "Peers Conectados",
                        value = "$connectedPeers",
                        subtitle = if (connectedPeers > 0) "✓ En contacto mesh" else "Buscando compañeros...",
                        color = if (connectedPeers > 0) BikerColors.StatusGreen else BikerColors.StatusYellow,
                        modifier = Modifier.weight(1f)
                    )

                    val deliveryPct = metrics?.heartbeatsDeliveredPct
                    MetricBox(
                        title = "% Entrega Heartbeats",
                        value = if (deliveryPct != null) {
                            "${String.format(Locale.US, "%.1f", deliveryPct)}%"
                        } else {
                            "N/D"
                        },
                        subtitle = when {
                            deliveryPct == null -> "Sin datos (0 pares / 0 Rx)"
                            deliveryPct >= 85f -> "Meta >=85% cumplida"
                            else -> "Bajo umbral (evaluar BLE Ext)"
                        },
                        color = when {
                            deliveryPct == null -> BikerColors.TextSecondary
                            deliveryPct >= 85f -> BikerColors.StatusGreen
                            else -> BikerColors.StatusRed
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBox(
                        title = "Paquetes Enviados (Tx)",
                        value = "${metrics?.packetsEmitted ?: 0}",
                        subtitle = "Latidos emitidos (1 c/3s)",
                        color = BikerColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    MetricBox(
                        title = "Paquetes Recibidos (Rx)",
                        value = "${metrics?.packetsReceivedValid ?: 0}",
                        subtitle = "Válidos y verificados",
                        color = BikerColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val avgHops = metrics?.avgHops
                    MetricBox(
                        title = "Saltos Promedio",
                        value = if (avgHops != null) {
                            String.format(Locale.US, "%.2f", avgHops)
                        } else {
                            "N/D"
                        },
                        subtitle = if (avgHops != null) "Multi-salto con TTL" else "Sin paquetes recibidos",
                        color = if (avgHops != null) BikerColors.TextPrimary else BikerColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )

                    val islands = metrics?.islandEvents ?: 0
                    MetricBox(
                        title = "Islas Detectadas",
                        value = "$islands",
                        subtitle = if (islands == 0) "Convoy íntegro" else "Partición momentánea",
                        color = if (islands == 0) BikerColors.StatusGreen else BikerColors.StatusYellow,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Descarte de duplicados y modo de transporte
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ecos/Duplicados filtrados: ${metrics?.duplicatesDropped ?: 0}",
                        color = BikerColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Transporte: ${rideState.transportName}",
                        color = BikerColors.PrimaryAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val primaryPeer = metrics?.peerReports?.firstOrNull()
                if (primaryPeer != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Peer #${primaryPeer.peerShortId}: ${primaryPeer.burstsHeardFromPeer}/${primaryPeer.burstsEmittedByPeer} ráfagas (${String.format(Locale.US, "%.1f", primaryPeer.peerDeliveryRatePct)}%)",
                            color = if (primaryPeer.peerDeliveryRatePct >= 85f) BikerColors.StatusGreen else BikerColors.StatusYellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isRunning) "Pantalla: ON" else "Pantalla: Auto",
                            color = BikerColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 3. BOTÓN DE EXPORTAR MÉTRICAS (genera JSON estructurado y guarda archivo local)
        Button(
            onClick = {
                val reportJson = autoSaveCurrentTest(isManualExport = true) ?: exportedJsonText
                val uniqueJsonName = lastSavedUniqueJsonName ?: "fieldtest_metrics.json"

                val duration = lastFinishedDurationSeconds ?: 0L
                if (duration < 10L) {
                    Toast.makeText(
                        context,
                        "⚠️ ADVERTENCIA: Corrida de solo ${duration}s (<10s). Posible prueba accidental o incompleta.",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Copiar al portapapeles automáticamente
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("RBS_FieldTest_Metrics", reportJson)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Métricas JSON y CSV guardadas ($uniqueJsonName) y copiadas", Toast.LENGTH_SHORT).show()

                // Intent para compartir por WhatsApp / Email / Archivo
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Métricas Campo BLE - ${Build.MODEL} ($uniqueJsonName)")
                    putExtra(Intent.EXTRA_TEXT, reportJson)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(shareIntent, "Exportar Métricas BLE"))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                Text(
                    text = "EXPORTAR MÉTRICAS (JSON + CSV)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Vista previa del JSON exportado y rutas de extracción adb pull
        exportedJsonText?.let { json ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BikerColors.PrimaryAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Archivo de esta corrida guardado en el teléfono:",
                        color = BikerColors.PrimaryAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = exportedFilePath ?: "/sdcard/Android/data/com.rutabikersync.app.fieldtest/files/fieldtest_metrics.json",
                        color = BikerColors.StatusGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Comando adb pull al PC (corrida única):\nadb -s <serie> pull /sdcard/Android/data/com.rutabikersync.app.fieldtest/files/${File(exportedFilePath ?: "fieldtest_metrics.json").name}\n\nComando adb pull al PC (última corrida):\nadb -s <serie> pull /sdcard/Android/data/com.rutabikersync.app.fieldtest/files/fieldtest_metrics.json fieldtest_metrics.json",
                        color = Color(0xFF93C5FD),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JSON Generado listo para pegar en el chat:",
                            color = BikerColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("RBS_FieldTest_Metrics", json)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(text = "Copiar", fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = json,
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color(0xFF030712), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showBatteryGuideDialog) {
        Dialog(onDismissRequest = { showBatteryGuideDialog = false }) {
            ManufacturerBatteryGuideCard(
                snapshot = batterySnapshot,
                onOpenSettings = {
                    batteryGuide.openSystemBatterySettings(context)
                },
                onSkip = { showBatteryGuideDialog = false },
                onDontShowAgain = {
                    batteryGuide.markCompletedOrDontShowAgain()
                    showBatteryGuideDialog = false
                }
            )
        }
    }
}

@Composable
private fun CapabilityRow(title: String, emit: Boolean, hear: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = BikerColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Emitir: ${if (emit) "✓ SÍ" else "✗ NO"}",
                color = if (emit) BikerColors.StatusGreen else BikerColors.StatusRed,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Oír: ${if (hear) "✓ SÍ" else "✗ NO"}",
                color = if (hear) BikerColors.StatusGreen else BikerColors.StatusRed,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MetricBox(
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(BikerColors.BackgroundDark, RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, color = BikerColors.TextSecondary, fontSize = 11.sp)
        Text(text = value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = subtitle, color = BikerColors.TextSecondary, fontSize = 10.sp)
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

private fun generateMetricsJson(
    context: Context,
    snapshot: FieldTestConfigSnapshot?,
    nodeRole: String,
    shortId: Int,
    totalPhonesInTest: Int,
    selectedBurstDurationMs: Int,
    expectedPeerIdsFallback: List<Int>,
    durationSeconds: Long,
    manualDistanceMeters: Double?,
    manualDistanceText: String,
    testStartEpochMs: Long,
    testEndEpochMs: Long,
    rideState: com.rutabikersync.app.core.mesh.RideSessionState,
    metrics: com.rutabikersync.app.core.mesh.MeshRelayEngine.MeshFieldReport?,
    bleCaps: BleAdvTransport.BleCapabilities,
    selectedTransportMode: BleAdvTransport.TransportModeOverride,
    screenKeepOnActive: Boolean = true,
    csvPath: String? = null,
    radioAtStart: RadioEnvironmentSnapshot? = null,
    radioAtEnd: RadioEnvironmentSnapshot? = null,
    csvContent: String = "",
    advertisingDeliveryMode: BleAdvTransport.AdvertisingDeliveryMode = BleAdvTransport.AdvertisingDeliveryMode.BURST
): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val nowMs = System.currentTimeMillis()

    val effectiveBleMode = snapshot?.effectiveBleTransportMode ?: bleCaps.resolveEffectiveMode(selectedTransportMode)
    val effectiveForcedMode = snapshot?.forcedBleTransportMode ?: (rideState.forcedBleMode ?: selectedTransportMode.code)
    val effectiveDeliveryMode = snapshot?.advertisingDeliveryMode ?: rideState.f3AdvertisingDeliveryMode
    val effectiveShortId = snapshot?.localNodeShortId ?: shortId
    val effectiveTotalPhones = snapshot?.totalPhonesInTest ?: totalPhonesInTest
    val effectiveRole = snapshot?.nodeRole ?: nodeRole
    val effectiveBurstDuration = snapshot?.advertisingBurstDurationMs
        ?: if (rideState.lifecycleState != RideLifecycleState.IDLE) rideState.f3AdvertisingBurstDurationMs else selectedBurstDurationMs
    val effectiveRelayEnabled = snapshot?.relayEnabled ?: rideState.relayEnabled
    val effectiveTxRxRole = snapshot?.txRxRole ?: rideState.txRxRole
    val effectiveUnfilteredScan = snapshot?.unfilteredScanDiagnostic ?: rideState.unfilteredScanDiagnostic
    val effectivePeers = snapshot?.expectedPeerShortIds
        ?: if (rideState.expectedPeerShortIds.isNotEmpty()) rideState.expectedPeerShortIds else expectedPeerIdsFallback

    val isExtendedMode = effectiveBleMode == "EXTENDED_1M" || effectiveBleMode == "EXTENDED_CODED_PHY"
    val f3IntervalSetting = if (isExtendedMode) {
        "INTERVAL_MEDIUM (400 slots = 250ms) / SOS: INTERVAL_LOW (160 slots = 100ms)"
    } else {
        "ADVERTISE_MODE_BALANCED (~250ms) / SOS: ADVERTISE_MODE_LOW_LATENCY (~100ms)"
    }
    val f3TxPowerSetting = if (isExtendedMode) {
        "TX_POWER_HIGH (+1 dBm nominal)"
    } else {
        "ADVERTISE_TX_POWER_HIGH (+1 dBm nominal)"
    }

    val actualStartMs = snapshot?.testStartEpochMs
        ?: if (testStartEpochMs > 0L) testStartEpochMs
        else if (rideState.startedAtEpochMs > 0L) rideState.startedAtEpochMs
        else (nowMs - durationSeconds * 1000L).coerceAtLeast(0L)

    val actualEndMs = if (testEndEpochMs > 0L) {
        testEndEpochMs
    } else if (rideState.lifecycleState == RideLifecycleState.RUNNING || rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP) {
        nowMs
    } else if (actualStartMs > 0L && durationSeconds > 0L) {
        actualStartMs + durationSeconds * 1000L
    } else {
        nowMs
    }

    val computedDurationSeconds = if (actualStartMs > 0L && actualEndMs >= actualStartMs) {
        ((actualEndMs - actualStartMs) / 1000L).coerceAtLeast(0L)
    } else {
        durationSeconds
    }

    val parsedDistance = manualDistanceMeters ?: manualDistanceText.toDoubleOrNull()
    val startRadio = radioAtStart ?: snapshot?.radioAtStart ?: queryRadioEnvironment(context)
    val endRadio = radioAtEnd ?: queryRadioEnvironment(context)
    val csvCounts = parseCsvRowCounts(csvContent)
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isInteractive = powerManager?.isInteractive ?: true
    val effectiveScreenKeepOn = screenKeepOnActive ||
        (context.findActivity()?.window?.attributes?.let {
            (it.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        } == true) ||
        (rideState.lifecycleState == RideLifecycleState.RUNNING || rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP)

    val obj = JSONObject().apply {
        put("timestampEpochMs", nowMs)
        put("timestampIso", sdf.format(Date(nowMs)))
        put("gitCommit", BuildConfig.GIT_COMMIT)
        put("gitDirty", BuildConfig.GIT_DIRTY)
        put("versionName", BuildConfig.VERSION_NAME)
        put("csvPath", csvPath ?: JSONObject.NULL)
        put("relayEnabled", effectiveRelayEnabled)
        put("txRxRole", effectiveTxRxRole.code)
        put("txRxRoleLabel", effectiveTxRxRole.displayLabel)
        put("unfilteredScanDiagnostic", effectiveUnfilteredScan)
        put("testStartEpochMs", actualStartMs)
        put("testStartUtc", sdf.format(Date(actualStartMs)))
        put("testEndEpochMs", actualEndMs)
        put("testEndUtc", sdf.format(Date(actualEndMs)))
        put("distanceMeters", parsedDistance ?: JSONObject.NULL)
        put("distanceMetersRaw", manualDistanceText.ifEmpty { "1" })
        put("distanceLabel", if (parsedDistance != null) "${parsedDistance} m" else manualDistanceText.ifEmpty { "1 m" })
        put("deviceManufacturer", Build.MANUFACTURER)
        put("deviceModel", Build.MODEL)
        put("androidRelease", Build.VERSION.RELEASE)
        put("androidSdkInt", Build.VERSION.SDK_INT)

        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            Build.HARDWARE
        }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.BOARD
        }
        put("socManufacturer", socManufacturer)
        put("socModel", socModel)
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)

        put("screenKeepOnActive", effectiveScreenKeepOn)
        put("screenIsInteractive", isInteractive)

        // Estado de radio observado durante el inicio de la prueba (compatibilidad retroactiva)
        put("radioWifiEnabled", startRadio.wifiEnabled)
        put("radioMobileDataEnabled", startRadio.mobileDataEnabled ?: JSONObject.NULL)
        put("radioAirplaneModeOn", startRadio.airplaneModeOn)
        put("radioNearbySharingState", startRadio.nearbySharingState)
        put("radioFastPairLikelyActive", startRadio.fastPairLikelyActive)

        // Bloque radioDiagnostic consolidado
        put("radioDiagnostic", JSONObject().apply {
            put("airplaneModeOn", startRadio.airplaneModeOn)
            put("wifiEnabled", startRadio.wifiEnabled)
            put("mobileDataEnabled", startRadio.mobileDataEnabled ?: JSONObject.NULL)
            put("btEnabled", startRadio.btEnabled)
            put("connectedBtAudio", startRadio.connectedBtAudio)
            put("otherConnectedBtDevices", startRadio.otherConnectedBtDevices)
        })

        // Snapshots explícitos al inicio y fin de la prueba
        put("radioAtStart", startRadio.toJsonObject())
        put("radioAtEnd", endRadio.toJsonObject())

        put("bleBluetoothSupported", bleCaps.bluetoothSupported)
        put("bleBluetoothEnabled", bleCaps.bluetoothEnabled)
        put("bleMultipleAdvertisementSupported", bleCaps.multipleAdvertisementSupported)
        put("bleExtendedAdvertisingSupported", bleCaps.extendedAdvertisingSupported)
        put("bleCodedPhySupported", bleCaps.codedPhySupported)
        put("ble2MPhySupported", bleCaps.le2MPhySupported)
        put("bleMaxAdvertisingDataLength", bleCaps.maxAdvertisingDataLength)
        put("bleRecommendedMode", bleCaps.recommendedMode)
        put("canEmitLegacy1M", bleCaps.multipleAdvertisementSupported)
        put("canHearLegacy1M", bleCaps.bluetoothSupported)
        put("canEmitExtended1M", bleCaps.extendedAdvertisingSupported)
        put("canHearExtended1M", bleCaps.extendedAdvertisingSupported)
        put("canEmitExtendedCodedPhy", bleCaps.extendedAdvertisingSupported && bleCaps.codedPhySupported)
        put("canHearExtendedCodedPhy", bleCaps.extendedAdvertisingSupported && bleCaps.codedPhySupported)
        put("forcedBleTransportMode", effectiveForcedMode)
        put("effectiveBleTransportMode", effectiveBleMode)
        put("f3AdvertisingMode", effectiveDeliveryMode.code)
        val approxDutyCycle = if (effectiveDeliveryMode == BleAdvTransport.AdvertisingDeliveryMode.CONTINUOUS) {
            100.0
        } else {
            ((effectiveBurstDuration.toDouble() / rideState.heartbeatIntervalMs.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        }
        put("f3AdvertisingDutyCycleApproxPct", approxDutyCycle)
        put("f3AdvertisingIntervalSetting", f3IntervalSetting)
        put("f3AdvertisingBurstDurationMs", effectiveBurstDuration)
        put("f3HeartbeatCadenceMs", rideState.heartbeatIntervalMs)
        put("f3TxPowerLevelSetting", f3TxPowerSetting)
        put("f3LastMeasuredTxPowerDbm", rideState.f3LastMeasuredTxPowerDbm ?: JSONObject.NULL)
        put("f3StopAdvertisingSetOnFinish", true)
        val setsDesc = if (effectiveDeliveryMode == BleAdvTransport.AdvertisingDeliveryMode.CONTINUOUS) {
            "Sets BLE creados en hardware: 1 = set continuo persistente con actualización de datos in-place (Opción A); N = start/stop por cada ráfaga"
        } else {
            "Sets BLE creados en hardware: start/stop por cada ráfaga de 1 s"
        }
        put("f3AdvertisingSetsStartedDescription", setsDesc)
        put("f3AdvertisingSetsStarted", rideState.f3AdvertisingSetsStarted)
        put("f3AdvertisingSetsStopped", rideState.f3AdvertisingSetsStopped)
        put("f3CsvRowCounts", csvCounts.toJsonObject())
        put("f3ScanSettingsDescription", BleAdvTransport.describeScanSettings(effectiveBleMode))
        put("f3ScanMode", "SCAN_MODE_LOW_LATENCY (2)")
        put("f3ScanLegacyOnly", !isExtendedMode)
        put("f3ScanPhy", if (isExtendedMode) "PHY_LE_ALL_SUPPORTED (255)" else "PHY_LE_1M (1)")
        put("f3ScanReportDelayMs", 0)
        put("totalPhonesInTest", effectiveTotalPhones)
        put("assignedRole", effectiveRole)
        put("localNodeShortId", effectiveShortId)
        put("expectedPeerShortIds", org.json.JSONArray(effectivePeers))
        put("testDurationSeconds", computedDurationSeconds)
        put("testDurationFormatted", formatElapsed(computedDurationSeconds))
        put("shortRunWarning", computedDurationSeconds < 10)
        put("transportName", rideState.transportName)
        put("isSimulatedTransport", rideState.isUsingSimulatedTransport)
        put("connectedPeersCount", rideState.connectedRidersCount)
        put("lostPeersCount", rideState.lostRidersCount)
        put("totalPacketsEmitted", metrics?.packetsEmitted ?: 0)
        put("totalPacketsReceivedValid", metrics?.packetsReceivedValid ?: 0)
        put("heartbeatsExpected", metrics?.heartbeatsExpected ?: 0)
        put("heartbeatsReceived", metrics?.heartbeatsReceived ?: 0)
        put("heartbeatsDeliveredPct", metrics?.heartbeatsDeliveredPct?.toDouble() ?: JSONObject.NULL)
        put("averageHops", metrics?.avgHops?.toDouble() ?: JSONObject.NULL)
        put("islandEventsDetected", metrics?.islandEvents ?: 0)
        put("duplicatesDropped", metrics?.duplicatesDropped ?: 0)
        put("invalidAuthDropped", metrics?.invalidAuthDropped ?: 0)
        put("f3AdvertisingBurstsStarted", rideState.f3AdvertisingBurstsStarted)
        put("f3AdvertisingBurstsSuccess", rideState.f3AdvertisingBurstsSuccess)
        put("f3AdvertisingFailuresCount", rideState.f3AdvertisingFailuresCount)
        put("f3LastAdvertisingFailure", rideState.f3LastAdvertisingFailure ?: JSONObject.NULL)
        put("f3LogicalHeartbeatsEmitted", rideState.f3LogicalHeartbeatsEmitted)
        put("f3ScanStartsCount", rideState.f3ScanStartsCount)
        put("f3ScanFailuresCount", rideState.f3ScanFailuresCount)
        put("f3LastScanFailure", rideState.f3LastScanFailure ?: JSONObject.NULL)
        put("connectedBluetoothDevicesCount", rideState.connectedBluetoothDevicesCount)
        put("hasOtherConnectedBluetoothDevices", rideState.hasOtherConnectedBluetoothDevices)
        put("connectedBluetoothAudio", rideState.connectedBluetoothAudio)
        put("connectedBluetoothDeviceNames", org.json.JSONArray(rideState.connectedBluetoothDeviceNames))

        val peerReportsArray = org.json.JSONArray()
        val peerReports = metrics?.peerReports ?: emptyList()
        for (pr in peerReports) {
            val pObj = JSONObject().apply {
                put("peerShortId", pr.peerShortId)
                put("firstSequenceObserved", pr.firstSequenceObserved ?: JSONObject.NULL)
                put("lastSequenceObserved", pr.lastSequenceObserved ?: JSONObject.NULL)
                put("burstsEmittedByPeer", pr.burstsEmittedByPeer)
                put("burstsHeardFromPeer", pr.burstsHeardFromPeer)
                put("peerDeliveryRatePct", pr.peerDeliveryRatePct.toDouble())
                put("isLost", pr.isLost)
            }
            peerReportsArray.put(pObj)
        }
        put("peerDeliveryReports", peerReportsArray)

        val primaryPeer = peerReports.firstOrNull()
        put("primaryPeerShortId", primaryPeer?.peerShortId ?: JSONObject.NULL)
        put("primaryPeerBurstsEmitted", primaryPeer?.burstsEmittedByPeer ?: JSONObject.NULL)
        put("primaryPeerBurstsHeard", primaryPeer?.burstsHeardFromPeer ?: JSONObject.NULL)
        put("primaryPeerDeliveryRatePct", primaryPeer?.peerDeliveryRatePct?.toDouble() ?: JSONObject.NULL)
    }
    return obj.toString(2)
}
