package com.rutabikersync.app.feature.fieldtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
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
    val rideState by RideService.sharedState.collectAsState()
    val metrics by RideService.sharedMetrics.collectAsState()

    var forceSimulation by remember { mutableStateOf(false) }
    var selectedNodeShortId by remember { mutableIntStateOf(1) }
    var testStartEpochMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var exportedJsonText by remember { mutableStateOf<String?>(null) }
    var showBatteryGuideDialog by remember { mutableStateOf(false) }

    val isRunning = rideState.lifecycleState == RideLifecycleState.RUNNING ||
        rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP

    // Cronómetro en vivo de duración de la prueba
    LaunchedEffect(isRunning, testStartEpochMs) {
        if (isRunning) {
            if (testStartEpochMs == 0L) {
                testStartEpochMs = System.currentTimeMillis()
            }
            while (isActive) {
                elapsedSeconds = ((System.currentTimeMillis() - testStartEpochMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            testStartEpochMs = 0L
            elapsedSeconds = 0L
        }
    }

    val batteryGuide = remember { ManufacturerBatteryGuide() }
    val batterySnapshot = remember { batteryGuide.snapshot() }

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
                    text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                    color = BikerColors.TextSecondary,
                    fontSize = 12.sp,
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

        // 1. BOTÓN GIGANTE DE ACCIÓN (>= 72 dp de alto según modo guantes)
        val buttonMinHeight = 76.dp
        if (!isRunning) {
            Button(
                onClick = {
                    val intent = Intent(context, RideService::class.java).apply {
                        action = RideService.ACTION_START_RIDE
                        putExtra(RideService.EXTRA_RIDE_ID, "field-test-${System.currentTimeMillis()}")
                        putExtra(RideService.EXTRA_LOCAL_SHORT_ID, selectedNodeShortId)
                        putExtra(RideService.EXTRA_FORCE_SIMULATED, forceSimulation)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
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
            val isConfirming = rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP
            Button(
                onClick = {
                    val stopIntent = Intent(context, RideService::class.java).apply {
                        action = RideService.ACTION_STOP_RIDE
                    }
                    context.startService(stopIntent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = buttonMinHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConfirming) BikerColors.StatusRed else Color(0xFFDC2626)
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
                        text = if (isConfirming) "CONFIRMAR DETENCIÓN (3s)" else "DETENER PRUEBA BLE",
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

                    val deliveryPct = metrics?.heartbeatsDeliveredPct ?: 100f
                    MetricBox(
                        title = "% Entrega Heartbeats",
                        value = "${String.format(Locale.US, "%.1f", deliveryPct)}%",
                        subtitle = if (deliveryPct >= 85f) "Meta >=85% cumplida" else "Bajo umbral (evaluar BLE Ext)",
                        color = if (deliveryPct >= 85f) BikerColors.StatusGreen else BikerColors.StatusRed,
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
                        subtitle = "Latidos emitidos",
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
                    val avgHops = metrics?.avgHops ?: 1.0f
                    MetricBox(
                        title = "Saltos Promedio",
                        value = String.format(Locale.US, "%.2f", avgHops),
                        subtitle = "Multi-salto con TTL",
                        color = BikerColors.TextPrimary,
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
                    metrics = metrics
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
    metrics: com.rutabikersync.app.core.mesh.MeshRelayEngine.MeshFieldReport?
): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val nowMs = System.currentTimeMillis()

    val obj = JSONObject().apply {
        put("timestampEpochMs", nowMs)
        put("timestampIso", sdf.format(Date(nowMs)))
        put("deviceManufacturer", Build.MANUFACTURER)
        put("deviceModel", Build.MODEL)
        put("androidRelease", Build.VERSION.RELEASE)
        put("androidSdkInt", Build.VERSION.SDK_INT)
        put("assignedRole", nodeRole)
        put("localNodeShortId", shortId)
        put("testDurationSeconds", durationSeconds)
        put("testDurationFormatted", formatElapsed(durationSeconds))
        put("transportName", rideState.transportName)
        put("isSimulatedTransport", rideState.isUsingSimulatedTransport)
        put("connectedPeersCount", rideState.connectedRidersCount)
        put("lostPeersCount", rideState.lostRidersCount)
        put("totalPacketsEmitted", metrics?.packetsEmitted ?: 0)
        put("totalPacketsReceivedValid", metrics?.packetsReceivedValid ?: 0)
        put("heartbeatsDeliveredPct", (metrics?.heartbeatsDeliveredPct ?: 100f).toDouble())
        put("averageHops", (metrics?.avgHops ?: 1.0f).toDouble())
        put("islandEventsDetected", metrics?.islandEvents ?: 0)
        put("duplicatesDropped", metrics?.duplicatesDropped ?: 0)
        put("invalidAuthDropped", metrics?.invalidAuthDropped ?: 0)
    }
    return obj.toString(2)
}
