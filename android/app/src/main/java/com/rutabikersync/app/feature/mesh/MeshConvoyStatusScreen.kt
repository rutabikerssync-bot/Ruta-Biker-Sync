package com.rutabikersync.app.feature.mesh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.mesh.MeshRelayEngine
import com.rutabikersync.app.ui.theme.*
import kotlin.math.max

/**
 * Niveles discretos de calidad de señal RSSI (4 barras tácticas).
 */
enum class RssiSignalLevel(val bars: Int, val label: String, val color: Color) {
    EXCELLENT(4, "Excelente", Color(0xFF34D399)), // >= -65 dBm (EmeraldMesh)
    GOOD(3, "Bueno", Color(0xFF6EE7B7)),        // >= -75 dBm
    FAIR(2, "Regular", Color(0xFFF59E0B)),      // >= -85 dBm (WarningAmber)
    CRITICAL(1, "Crítico", Color(0xFFEF4444));  // < -85 dBm (RescueRed)

    companion object {
        fun fromDbm(rssiDbm: Int): RssiSignalLevel = when {
            rssiDbm >= -65 -> EXCELLENT
            rssiDbm >= -75 -> GOOD
            rssiDbm >= -85 -> FAIR
            else -> CRITICAL
        }

        fun fromDbmOrNull(rssiDbm: Int?): RssiSignalLevel? = rssiDbm?.let { fromDbm(it) }
    }
}

/**
 * Semáforo del estado global de la malla BLE en convoy.
 */
enum class GlobalMeshStatus(val label: String, val color: Color, val emoji: String) {
    ACTIVE("Malla activa", Color(0xFF34D399), "🟢"),
    WEAK("Malla débil", Color(0xFFF59E0B), "🟡"),
    DOWN("Malla caída", Color(0xFFEF4444), "🔴");

    companion object {
        fun evaluate(peers: List<MeshPeerLiveState>): GlobalMeshStatus {
            if (peers.isEmpty()) return DOWN
            val knownRssi = peers.mapNotNull { it.rssiDbm }
            val knownPlr = peers.mapNotNull { it.plrPercent }
            val avgRssi = if (knownRssi.isNotEmpty()) knownRssi.average() else null
            val maxPlr = knownPlr.maxOrNull()
            return when {
                avgRssi != null && avgRssi < -82 -> WEAK
                maxPlr != null && maxPlr > 8.0 -> WEAK
                else -> ACTIVE
            }
        }
    }
}

/**
 * Formateo estandarizado de distancia en saltos de retransmisión RF.
 */
fun formatHopCount(hopCount: Byte): String = when (hopCount.toInt()) {
    0 -> "Directo"
    1 -> "1 salto"
    else -> "$hopCount saltos"
}

/**
 * Formateo de etiquetas de roles tácticos de convoy.
 */
fun formatConvoyRole(rawRole: String?): String = when (rawRole?.trim()?.lowercase().orEmpty()) {
    "", "no disponible", "desconocido", "unknown", "n/d" -> "No disponible"
    "punta", "leader", "líder", "capitán", "capitán de ruta" -> "Punta"
    "scout" -> "Scout"
    "sweep", "cola", "sargento", "barrendero" -> "Sweep"
    "rider" -> "Rider"
    else -> "Rider"
}

/**
 * Modelo representativo de un peer motociclista en la malla en vivo.
 * Campos de RF/identidad pueden ser null o "No disponible" cuando el stack no los expone.
 */
data class MeshPeerLiveState(
    val shortId: UShort,
    val name: String = "No disponible",
    val roleInConvoy: String = "No disponible",
    val rssiDbm: Int? = null,
    val hopCount: Byte,
    val batteryPercent: Int,
    val isLpn: Boolean = false,
    val isFriendNode: Boolean = false,
    val pendingMessagesCount: Int = 0,
    val isMedicalSheetShared: Boolean = false,
    val plrPercent: Double? = null,
    val lastSeenSecondsAgo: Int = 1,
    val expectedBeats: Int? = null,
    val receivedBeats: Int? = null
)

/**
 * Pantalla oficial de Estado de la Malla y Convoy en Vivo (Fase 7.0 · Hito 7.0.2).
 * Estética Dark Elite de alto rendimiento para supervisar radiofrecuencia táctica en ruta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshConvoyStatusScreen(
    localShortId: UShort = 1u,
    localBatteryPercent: Int = 84,
    isLocalLpnActive: Boolean = false,
    isCurrentUserLeaderOrSergeant: Boolean = true,
    peers: List<MeshPeerLiveState> = emptyList(),
    isLpnToggleEnabled: Boolean = true,
    lpnToggleDisabledReason: String? = null,
    onToggleLocalLpn: (Boolean) -> Unit = {},
    onRefreshScan: () -> Unit = {},
    onOpenChatWithPeer: (UShort) -> Unit = {},
    onViewMedicalSheet: (UShort) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var isLpnState by remember { mutableStateOf(isLocalLpnActive) }
    var selectedMedicalSheetPeer by remember { mutableStateOf<MeshPeerLiveState?>(null) }

    val meshStatus = GlobalMeshStatus.evaluate(peers)

    Scaffold(
        containerColor = DarkSlate950,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSlate900,
                    titleContentColor = TextWhite,
                    navigationIconContentColor = TextWhite,
                    actionIconContentColor = TextWhite
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CellTower,
                                contentDescription = null,
                                tint = meshStatus.color,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Monitor de Malla BLE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = if (peers.isEmpty()) "Sin enlaces activos" else "${peers.size} pilotos enlazados",
                            fontSize = 11.sp,
                            color = if (peers.isEmpty()) WarningAmber else EmeraldMesh
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRefreshScan()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar escaneo BLE",
                            tint = HeritageBrassGold
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 28.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Tarjeta de Resumen General y Semáforo Global
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCardBg,
                    border = BorderStroke(1.dp, DarkBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TOPOLOGÍA RF DEL CONVOY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                            // Semáforo Global de la Malla
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DarkSlate950,
                                border = BorderStroke(1.dp, meshStatus.color)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${meshStatus.emoji} ${meshStatus.label.uppercase()}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = meshStatus.color
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricColumn(
                                title = "Pilotos Enlazados",
                                value = "${peers.size}",
                                color = TextWhite
                            )
                            MetricColumn(
                                title = "TTL Máximo",
                                value = "5 Saltos",
                                color = HeritageCopperGlow
                            )
                            val knownPlr = peers.mapNotNull { it.plrPercent }
                            val avgPlr = if (knownPlr.isNotEmpty()) knownPlr.average() else null
                            MetricColumn(
                                title = "PLR Promedio",
                                value = avgPlr?.let { "${String.format(java.util.Locale.US, "%.1f", it)}%" } ?: "No disponible",
                                color = when {
                                    avgPlr == null -> TextMuted
                                    avgPlr > 5.0 -> WarningAmber
                                    else -> EmeraldMesh
                                }
                            )
                            MetricColumn(
                                title = "Modulación",
                                value = "Coded PHY",
                                color = HeritageBrassGold
                            )
                        }
                    }
                }
            }

            // 2. Control de Batería y Ahorro LPN Local
            item {
                val lpnSubtitle = when {
                    !isLpnToggleEnabled -> lpnToggleDisabledReason ?: "No implementado aún"
                    isLpnState -> "Bypass automático para emergencias SOS"
                    else -> "Nodo Local #$localShortId · Batería: $localBatteryPercent%"
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCardElevated,
                    border = BorderStroke(1.dp, if (isLpnState && isLpnToggleEnabled) WarningAmber else DarkBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isLpnState && isLpnToggleEnabled) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                                contentDescription = null,
                                tint = if (isLpnState && isLpnToggleEnabled) WarningAmber else EmeraldMesh,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isLpnState && isLpnToggleEnabled) "Modo LPN Activo (Ahorro Batería)" else "Modo de Radio Completo",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = lpnSubtitle,
                                    fontSize = 11.sp,
                                    color = if (!isLpnToggleEnabled) WarningAmber else TextMuted
                                )
                            }
                        }

                        Switch(
                            checked = isLpnState && isLpnToggleEnabled,
                            onCheckedChange = { newState ->
                                if (!isLpnToggleEnabled) return@Switch
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isLpnState = newState
                                onToggleLocalLpn(newState)
                            },
                            enabled = isLpnToggleEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = WarningAmber,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkCardBg,
                                disabledCheckedThumbColor = TextMuted,
                                disabledUncheckedThumbColor = TextMuted,
                                disabledCheckedTrackColor = DarkCardBg,
                                disabledUncheckedTrackColor = DarkCardBg
                            )
                        )
                    }
                }
            }

            // 3. Título de Sección
            item {
                Text(
                    text = "NODOS DETECTADOS EN LA MALLA:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            // 4. Empty State o Lista de Nodos
            if (peers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DarkCardBg,
                        border = BorderStroke(1.dp, DarkBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CellTower,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Sin compañeros en rango.",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextWhite,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Acércate al convoy para reconectar.",
                                fontSize = 13.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = onRefreshScan,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated),
                                border = BorderStroke(1.dp, HeritageBrassGold),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = HeritageBrassGold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Escanear ahora", color = HeritageBrassGold, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                items(peers, key = { it.shortId.toInt() }) { peer ->
                    PeerConvoyCard(
                        peer = peer,
                        canViewMedicalSheet = isCurrentUserLeaderOrSergeant,
                        onOpenChat = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenChatWithPeer(peer.shortId)
                        },
                        onViewMedical = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedMedicalSheetPeer = peer
                            onViewMedicalSheet(peer.shortId)
                        }
                    )
                }
            }
        }
    }

    // Modal de Ficha Médica de Emergencia (solo Líder/Sargento)
    selectedMedicalSheetPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { selectedMedicalSheetPeer = null },
            containerColor = DarkCardBg,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MedicalServices,
                        contentDescription = null,
                        tint = RescueRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Ficha Médica de Emergencia",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Piloto: ${peer.name} (#${peer.shortId})",
                            color = HeritageBrassGold,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🔒 Ficha médica cifrada compartida temporalmente con el convoy para respuesta en caso de accidente.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSlate950,
                        border = BorderStroke(1.dp, DarkBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🩸 Tipo de Sangre: O+", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("⚠️ Alergias: Penicilina", color = TextWhite, fontSize = 12.sp)
                            Text("📞 Contacto ICE: Esposa (+52 55 1234 5678)", color = TextWhite, fontSize = 12.sp)
                            Text("🛡️ Póliza Médica: GMX-98231-M", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMedicalSheetPeer = null }) {
                    Text("Entendido", color = HeritageBrassGold, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

/**
 * Indicador táctico de 4 barras de señal RSSI con alturas crecientes.
 */
@Composable
fun RssiBarsIndicator(level: RssiSignalLevel?) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(14.dp)
    ) {
        val barHeights = listOf(4.dp, 7.dp, 10.dp, 14.dp)
        for (i in 0 until 4) {
            val isLit = level != null && i < level.bars
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeights[i])
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isLit) level!!.color else Color(0xFF2C3240))
            )
        }
    }
}

/**
 * Tarjeta táctica individual de un compañero en la malla.
 */
@Composable
fun PeerConvoyCard(
    peer: MeshPeerLiveState,
    canViewMedicalSheet: Boolean,
    onOpenChat: () -> Unit,
    onViewMedical: () -> Unit
) {
    val signalLevel = RssiSignalLevel.fromDbmOrNull(peer.rssiDbm)
    val initials = peer.name
        .takeUnless { it.equals("No disponible", ignoreCase = true) }
        ?.split(" ")
        ?.mapNotNull { it.firstOrNull()?.toString() }
        ?.take(2)
        ?.joinToString("")
        ?.ifBlank { "?" }
        ?: "?"
    val formattedRole = formatConvoyRole(peer.roleInConvoy)
    val formattedHops = formatHopCount(peer.hopCount)
    val plrText = peer.plrPercent?.let { "PLR ${String.format(java.util.Locale.US, "%.1f", it)}%" } ?: "PLR N/D"
    val plrColor = when {
        peer.plrPercent == null -> TextMuted
        peer.plrPercent > 5.0 -> WarningAmber
        else -> EmeraldMesh
    }
    val rssiText = peer.rssiDbm?.let { "$it dBm" } ?: "No disponible"
    val rssiColor = signalLevel?.color ?: TextMuted

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkCardBg,
        border = BorderStroke(1.dp, DarkBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circular + Identidad
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(DarkSlate950)
                            .border(1.5.dp, HeritageBrassGold, CircleShape)
                    ) {
                        Text(
                            text = initials,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = HeritageBrassGold
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = if (peer.name.equals("No disponible", ignoreCase = true)) "Piloto #${peer.shortId}" else peer.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextWhite
                        )
                        Text(
                            text = "$formattedRole · $formattedHops · ${peer.lastSeenSecondsAgo}s",
                            fontSize = 11.sp,
                            color = HeritageCopperGlow
                        )
                        if (peer.expectedBeats != null && peer.receivedBeats != null) {
                            Text(
                                text = "Recibidos ${peer.receivedBeats}/${peer.expectedBeats}",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Badges de Estado del Nodo
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (peer.isFriendNode) {
                        val friendLabel = if (peer.pendingMessagesCount > 0) {
                            "📬 Nodo Amigo (${peer.pendingMessagesCount} cola)"
                        } else {
                            "📬 Nodo Amigo"
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkSlate950,
                            border = BorderStroke(1.dp, HeritageCopper)
                        ) {
                            Text(
                                text = friendLabel,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = HeritageCopperGlow,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (peer.isLpn) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkSlate950,
                            border = BorderStroke(1.dp, WarningAmber)
                        ) {
                            Text(
                                text = "🔋 LPN",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!peer.isLpn && !peer.isFriendNode) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkSlate950,
                            border = BorderStroke(1.dp, EmeraldMesh)
                        ) {
                            Text(
                                text = "🟢 En línea",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldMesh,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fila de Métricas RF en Vivo: RSSI, Saltos, PLR, Batería
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSlate950, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gráfico de 4 barras de RSSI
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RssiBarsIndicator(level = signalLevel)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rssiText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = rssiColor
                    )
                }

                // Saltos de Retransmisión
                Text(
                    text = formattedHops,
                    fontSize = 11.sp,
                    color = TextMuted
                )

                // PLR
                Text(
                    text = plrText,
                    fontSize = 11.sp,
                    color = plrColor
                )

                // Batería
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (peer.batteryPercent <= 15) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                        contentDescription = null,
                        tint = if (peer.batteryPercent <= 15) RescueRed else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${peer.batteryPercent}%",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fila de Acciones: Canal Directo (Unicast) y Ficha Médica
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Botón Canal Directo
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkCardElevated,
                    border = BorderStroke(1.dp, DarkBorderProminent),
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenChat)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = HeritageCopperGlow,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "💬 CANAL DIRECTO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                // Botón Ficha Médica (Solo si Líder/Sargento y la ficha está compartida)
                if (canViewMedicalSheet && peer.isMedicalSheetShared) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2C1618),
                        border = BorderStroke(1.dp, RescueRed.copy(alpha = 0.6f)),
                        modifier = Modifier.clickable(onClick = onViewMedical)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = RescueRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "FICHA",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RescueRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(title: String, value: String, color: Color) {
    Column {
        Text(text = title, fontSize = 10.sp, color = TextMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * Catálogo inicial de peers: inicia vacío. Los nodos se agregan en vivo al recibirse telemetría BLE real.
 */
fun defaultMeshConvoyPeers(): List<MeshPeerLiveState> = emptyList()

/**
 * Mapea el estado real expuesto por el motor de malla a la tarjeta de monitor.
 * Solo usa campos disponibles en [MeshRelayEngine.PeerTrack]; nombre/rol/RSSI/PLR crudos no inventados.
 */
fun mapPeerTrackToMeshPeerLiveState(
    track: MeshRelayEngine.PeerTrack,
    nowEpochMs: Long = System.currentTimeMillis()
): MeshPeerLiveState {
    val expected = track.expectedBeats.coerceAtLeast(0)
    val received = track.receivedBeats.coerceAtLeast(0)
    val plrPercent = if (expected > 0) {
        ((max(0, expected - received).toDouble() / expected.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    } else {
        null
    }
    val lastSeenSecondsAgo = if (track.lastSeenEpochMs > 0L) {
        ((nowEpochMs - track.lastSeenEpochMs).coerceAtLeast(0L) / 1000L).toInt()
    } else {
        0
    }
    return MeshPeerLiveState(
        shortId = track.shortId,
        name = "No disponible",
        roleInConvoy = "No disponible",
        rssiDbm = null,
        hopCount = track.lastHops.coerceIn(0, 127).toByte(),
        batteryPercent = track.lastBatteryPercent.coerceIn(0, 100),
        isLpn = false,
        isFriendNode = false,
        pendingMessagesCount = 0,
        isMedicalSheetShared = false,
        plrPercent = plrPercent,
        lastSeenSecondsAgo = lastSeenSecondsAgo,
        expectedBeats = expected,
        receivedBeats = received
    )
}

fun mapPeerTracksToMeshPeerLiveStates(
    peers: Map<UShort, MeshRelayEngine.PeerTrack>,
    nowEpochMs: Long = System.currentTimeMillis()
): List<MeshPeerLiveState> =
    peers.values
        .sortedBy { it.shortId.toInt() }
        .map { mapPeerTrackToMeshPeerLiveState(it, nowEpochMs) }
