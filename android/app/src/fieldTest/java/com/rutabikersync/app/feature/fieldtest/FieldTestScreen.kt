package com.rutabikersync.app.feature.fieldtest

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    val activity = context as? Activity
    val rideState by RideService.sharedState.collectAsState()
    val metrics by RideService.sharedMetrics.collectAsState()

    var forceSimulation by remember { mutableStateOf(false) }
    var selectedNodeShortId by remember { mutableIntStateOf(1) }
    var testStartEpochMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var exportedJsonText by remember { mutableStateOf<String?>(null) }
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

    fun launchRideServiceNow(useSimulated: Boolean) {
        val expectedConvoyPeers = setOf<UShort>(1u, 2u, 3u) - selectedNodeShortId.toUShort()
        RideService.dispatchStartRide(
            context = context,
            rideId = "field-test-${System.currentTimeMillis()}",
            localShortId = selectedNodeShortId,
            forceSimulated = useSimulated,
            bleModeOverride = selectedTransportMode,
            expectedPeerShortIds = expectedConvoyPeers
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
            while (isActive) {
                elapsedSeconds = ((System.currentTimeMillis() - baseStartMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            testStartEpochMs = 0L
            elapsedSeconds = 0L
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

        // Selector de Rol / ID de Nodo en el convoy
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
                    text = "ID y Rol de este dispositivo en la prueba:",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        1 to "Punta (#1 Líder)",
                        2 to "Medio (#2 Centro)",
                        3 to "Cierre (#3 Sweep)"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = selectedNodeShortId == id,
                            onClick = { if (!isRunning) selectedNodeShortId = id },
                            enabled = !isRunning,
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }
            }
        }

        // Selector de Modo de Transporte BLE (Legacy / Extendido / Extendido+Coded PHY)
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
                    text = "Modo de Transporte BLE forzado (${bleCaps.resolveEffectiveMode(selectedTransportMode)}):",
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BleAdvTransport.TransportModeOverride.entries.forEach { modeOption ->
                        FilterChip(
                            selected = selectedTransportMode == modeOption,
                            onClick = { if (!isRunning) selectedTransportMode = modeOption },
                            enabled = !isRunning,
                            label = { Text(modeOption.displayLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BikerColors.PrimaryAmber,
                                selectedLabelColor = BikerColors.BackgroundDark
                            )
                        )
                    }
                }
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
                    RideService.dispatchImmediateStopRide(context)
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
            }
        }

        // 3. BOTÓN DE EXPORTAR MÉTRICAS (genera JSON estructurado)
        Button(
            onClick = {
                val durationSec = elapsedSeconds
                val reportJson = generateMetricsJson(
                    nodeRole = when (selectedNodeShortId) {
                        1 -> "Punta (#1 Líder)"
                        2 -> "Medio (#2 Centro)"
                        else -> "Cierre (#3 Sweep)"
                    },
                    shortId = selectedNodeShortId,
                    durationSeconds = durationSec,
                    rideState = rideState,
                    metrics = metrics,
                    bleCaps = bleCaps,
                    selectedTransportMode = selectedTransportMode
                )
                exportedJsonText = reportJson

                // Copiar al portapapeles automáticamente
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("RBS_FieldTest_Metrics", reportJson)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Métricas copiadas al portapapeles", Toast.LENGTH_SHORT).show()

                // Intent para compartir por WhatsApp / Email / Archivo
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Métricas Campo BLE - ${Build.MODEL}")
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
                    text = "EXPORTAR MÉTRICAS (JSON)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Vista previa del JSON exportado
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JSON Generado listo para pegar en el chat:",
                            color = BikerColors.PrimaryAmber,
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
    nodeRole: String,
    shortId: Int,
    durationSeconds: Long,
    rideState: com.rutabikersync.app.core.mesh.RideSessionState,
    metrics: com.rutabikersync.app.core.mesh.MeshRelayEngine.MeshFieldReport?,
    bleCaps: BleAdvTransport.BleCapabilities,
    selectedTransportMode: BleAdvTransport.TransportModeOverride
): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val nowMs = System.currentTimeMillis()
    val effectiveBleMode = bleCaps.resolveEffectiveMode(selectedTransportMode)
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

    val obj = JSONObject().apply {
        put("timestampEpochMs", nowMs)
        put("timestampIso", sdf.format(Date(nowMs)))
        put("deviceManufacturer", Build.MANUFACTURER)
        put("deviceModel", Build.MODEL)
        put("androidRelease", Build.VERSION.RELEASE)
        put("androidSdkInt", Build.VERSION.SDK_INT)
        put("bleBluetoothSupported", bleCaps.bluetoothSupported)
        put("bleBluetoothEnabled", bleCaps.bluetoothEnabled)
        put("bleMultipleAdvertisementSupported", bleCaps.multipleAdvertisementSupported)
        put("bleExtendedAdvertisingSupported", bleCaps.extendedAdvertisingSupported)
        put("bleCodedPhySupported", bleCaps.codedPhySupported)
        put("ble2MPhySupported", bleCaps.le2MPhySupported)
        put("bleMaxAdvertisingDataLength", bleCaps.maxAdvertisingDataLength)
        put("bleRecommendedMode", bleCaps.recommendedMode)
        put("forcedBleTransportMode", rideState.forcedBleMode ?: selectedTransportMode.code)
        put("effectiveBleTransportMode", effectiveBleMode)
        put("f3AdvertisingIntervalSetting", f3IntervalSetting)
        put("f3AdvertisingBurstDurationMs", if (isExtendedMode) 300 else 1500)
        put("f3HeartbeatCadenceMs", rideState.heartbeatIntervalMs)
        put("f3TxPowerLevelSetting", f3TxPowerSetting)
        put("f3LastMeasuredTxPowerDbm", rideState.f3LastMeasuredTxPowerDbm ?: JSONObject.NULL)
        put("f3StopAdvertisingSetOnFinish", true)
        put("f3AdvertisingSetsStarted", rideState.f3AdvertisingSetsStarted)
        put("f3AdvertisingSetsStopped", rideState.f3AdvertisingSetsStopped)
        put("assignedRole", nodeRole)
        put("localNodeShortId", shortId)
        put("expectedPeerShortIds", org.json.JSONArray(rideState.expectedPeerShortIds))
        put("testDurationSeconds", durationSeconds)
        put("testDurationFormatted", formatElapsed(durationSeconds))
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
    }
    return obj.toString(2)
}
