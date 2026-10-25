package com.rutabikersync.app.feature.fieldtest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rutabikersync.app.R
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.ui.theme.BikerDimens
import java.util.Locale

/**
 * Pantalla de validación de campo BLE de la Fase 0.
 *
 * Cumple reglas de accesibilidad y modo guantes (RULES.md R8, DESIGN.md §1, ICONS.md §1):
 * - Botones de acción críticos de altura >= 72 dp.
 * - El color nunca es la única señal (acompañado siempre de icono y texto ✓ / ⚠).
 * - Sin skeletons ni dependencias de red durante la prueba de rodada/mesh.
 */
@Composable
fun BleFieldTestScreen(
    viewModel: BleFieldTestViewModel,
    onRequestBlePermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.uiState.collectAsState()
    val report = state.report
    var showSimulatedFallbackCard by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val requiredBlePermissions = remember {
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

    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val meshViable = RideService.isMeshViable(context)
        if (meshViable) {
            showSimulatedFallbackCard = false
            permanentlyDenied = false
            viewModel.setMode(useRealBle = true)
            viewModel.startFieldTest()
        } else {
            val isPermDenied = activity != null && requiredBlePermissions.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            permanentlyDenied = isPermDenied
            showSimulatedFallbackCard = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.phase0_title),
            style = MaterialTheme.typography.headlineSmall,
            color = BikerColors.PrimaryAmber,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.phase0_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = BikerColors.TextSecondary
        )

        // Tarjeta de capacidades BLE del teléfono
        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.ble_capabilities_label),
                    color = BikerColors.PrimaryAmber,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.bleCapabilitiesSummary,
                    color = BikerColors.TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Selector de modo (Hardware BLE Real vs Simulador 5 Riders) y TTL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !state.useRealBle,
                onClick = { viewModel.setMode(useRealBle = false) },
                enabled = !state.isRunning,
                label = { Text(stringResource(R.string.mode_simulated_mesh)) }
            )
            FilterChip(
                selected = state.useRealBle,
                onClick = {
                    onRequestBlePermissions()
                    viewModel.setMode(useRealBle = true)
                },
                enabled = !state.isRunning,
                label = { Text(stringResource(R.string.mode_real_ble)) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TTL inicial:",
                color = BikerColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
            listOf(2, 3, 4, 5).forEach { ttl ->
                FilterChip(
                    selected = state.initialTtl == ttl,
                    onClick = { viewModel.setInitialTtl(ttl) },
                    label = { Text("TTL $ttl") }
                )
            }
        }

        // Estado de la mesh (con icono + símbolo + texto, nunca solo color)
        val hasPeers = (report?.participants ?: 1) > 1
        val hasIslands = (report?.activeLostPeers?.isNotEmpty() == true) || state.islandSimulated
        val (statusText, statusColor, statusIcon) = when {
            !state.isRunning -> Triple(
                stringResource(R.string.mesh_status_solo),
                BikerColors.TextSecondary,
                Icons.AutoMirrored.Filled.BluetoothSearching
            )
            hasIslands -> Triple(
                "⚠ " + stringResource(R.string.mesh_status_partial),
                BikerColors.StatusYellow,
                Icons.Default.Warning
            )
            hasPeers -> Triple(
                "✓ " + stringResource(R.string.mesh_status_connected),
                BikerColors.StatusGreen,
                Icons.Default.CheckCircle
            )
            else -> Triple(
                stringResource(R.string.mesh_status_searching),
                BikerColors.PrimaryAmber,
                Icons.AutoMirrored.Filled.BluetoothSearching
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusText,
                        tint = statusColor
                    )
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                MetricRow(
                    label = stringResource(R.string.metric_delivery_pct),
                    value = String.format(Locale.US, "%.1f %%", report?.heartbeatsDeliveredPct ?: 100f)
                )
                MetricRow(
                    label = stringResource(R.string.metric_avg_hops),
                    value = String.format(Locale.US, "%.2f saltos", report?.avgHops ?: 0f)
                )
                MetricRow(
                    label = stringResource(R.string.metric_emitted_received),
                    value = "${report?.packetsEmitted ?: 0} / ${report?.packetsReceivedValid ?: 0}"
                )
                MetricRow(
                    label = stringResource(R.string.metric_relayed_dedup),
                    value = "${report?.packetsRelayed ?: 0} / ${report?.duplicatesDropped ?: 0}"
                )
                MetricRow(
                    label = stringResource(R.string.metric_islands_peers),
                    value = "${report?.islandEvents ?: 0} islas / ${report?.participants ?: 1} nodos"
                )
            }
        }

        if (showSimulatedFallbackCard) {
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
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                showSimulatedFallbackCard = false
                                viewModel.setMode(useRealBle = false)
                                viewModel.startFieldTest()
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
                        if (permanentlyDenied) {
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

        // Máximo 3 botones grandes de acción en modo guantes (>= 72 dp)
        Button(
            onClick = {
                if (state.isRunning) {
                    viewModel.stopFieldTest()
                } else if (!state.useRealBle) {
                    showSimulatedFallbackCard = false
                    viewModel.startFieldTest()
                } else if (RideService.isMeshViable(context)) {
                    showSimulatedFallbackCard = false
                    viewModel.startFieldTest()
                } else {
                    onRequestBlePermissions()
                    blePermissionLauncher.launch(requiredBlePermissions.toTypedArray())
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isRunning) BikerColors.StatusRed else BikerColors.StatusGreen,
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BikerDimens.GlovesMinTouchTarget)
        ) {
            Icon(
                imageVector = if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Text(
                text = "  " + if (state.isRunning) {
                    stringResource(R.string.btn_stop_test)
                } else {
                    stringResource(R.string.btn_start_test)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.emitPrioritySos() },
                enabled = state.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BikerColors.StatusRed,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BikerDimens.GlovesMinTouchTarget)
            ) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null)
                Text(
                    text = " SOS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    if (!state.useRealBle && state.isRunning) {
                        viewModel.toggleSimulatedIsland()
                    } else {
                        viewModel.exportReportJson()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BikerDimens.GlovesMinTouchTarget)
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null)
                Text(
                    text = " " + if (!state.useRealBle && state.isRunning) {
                        stringResource(R.string.btn_force_island)
                    } else {
                        stringResource(R.string.btn_export_report)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        state.exportedJson?.let { json ->
            Card(
                colors = CardDefaults.cardColors(containerColor = BikerColors.SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Reporte JSON (ride_mesh_metrics):",
                        color = BikerColors.PrimaryAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = json,
                        color = BikerColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Atribución OSM siempre presente según requerimiento 1.1 y ICONS.md §14
        Text(
            text = stringResource(R.string.osm_attribution),
            color = BikerColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = BikerColors.TextSecondary, fontSize = 14.sp)
        Text(
            text = value,
            color = BikerColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp
        )
    }
}
