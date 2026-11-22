package com.rutabikersync.app.feature.routes

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.*

/**
 * Mapeador de insignias y méritos tácticos desbloqueados en rodadas (Fase 7.0 · Hito 7.0.2).
 */
object BadgeIconMapper {
    data class BadgeDisplay(
        val icon: String,
        val label: String,
        val tintColor: Color
    )

    fun getBadgeDisplay(badgeId: String): BadgeDisplay {
        return when (badgeId.lowercase().trim()) {
            "piloto_protegido" -> BadgeDisplay("🛡️", "Piloto Protegido", HeritageBrassGold)
            "sin_cobertura" -> BadgeDisplay("📡", "Sin Cobertura", Color(0xFFE2E8F0))
            "convoy_seguro" -> BadgeDisplay("🚑", "Convoy Seguro", HeritageBrassGold)
            "primer_sos" -> BadgeDisplay("🆘", "Primer SOS", RescueRed)
            "primera_rodada" -> BadgeDisplay("🎉", "1ª Rodada", Color(0xFFCD7F32))
            "viajero" -> BadgeDisplay("🛣️", "Viajero (500 km)", Color(0xFFE2E8F0))
            "racha_3" -> BadgeDisplay("🔥", "Racha de 3", HeritageBrassGold)
            "centinela_malla" -> BadgeDisplay("⚡", "Centinela Malla", HeritageBrassGold)
            "nocturna" -> BadgeDisplay("🌙", "Rodada Nocturna", Color(0xFFE2E8F0))
            "leyenda" -> BadgeDisplay("👑", "Leyenda Biker", HeritageBrassGold)
            else -> BadgeDisplay("🏅", badgeId.replace("_", " ").capitalizeWords(), HeritageBrassGold)
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}

enum class DateFilter(val label: String) {
    ALL("Todo"),
    LAST_7_DAYS("7 días"),
    LAST_30_DAYS("30 días")
}

enum class SortOrder(val label: String) {
    NEWEST("Más reciente"),
    DISTANCE("Mayor km")
}

/**
 * Pantalla oficial de Historial de Rodadas y Bitácora de Convoy (Fase 7.0.2 · Opción A).
 * Estética Dark Elite + Heritage con telemetría de malla, XP acumulado y auditoría off-grid.
 */
@Composable
fun RideHistoryScreen(
    onDismiss: () -> Unit = {},
    onNavigateBack: () -> Unit = onDismiss,
    onOpenRidePlanner: () -> Unit = {}
) {
    val context = LocalContext.current
    val historyList by RideScheduleRepository.rideHistory.collectAsState()
    var selectedItemForDetail by remember { mutableStateOf<RideHistoryItem?>(null) }

    // Estados de filtros y ordenamiento
    var selectedDateFilter by remember { mutableStateOf(DateFilter.ALL) }
    var selectedSortOrder by remember { mutableStateOf(SortOrder.NEWEST) }
    var selectedClubFilter by remember { mutableStateOf("Todos") }

    // Obtener clubes únicos de la lista
    val availableClubs = remember(historyList) {
        listOf("Todos") + historyList.map { it.clubName }.filter { it.isNotBlank() }.distinct()
    }

    // Filtrar y ordenar la lista
    val filteredList = remember(historyList, selectedDateFilter, selectedSortOrder, selectedClubFilter) {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7 * 86400000L
        val thirtyDaysMs = 30 * 86400000L

        historyList.filter { item ->
            // Filtro por club
            val matchesClub = selectedClubFilter == "Todos" || item.clubName == selectedClubFilter

            // Filtro por fecha
            val matchesDate = when (selectedDateFilter) {
                DateFilter.ALL -> true
                DateFilter.LAST_7_DAYS -> (now - item.timestampEpochMs) <= sevenDaysMs
                DateFilter.LAST_30_DAYS -> (now - item.timestampEpochMs) <= thirtyDaysMs
            }

            matchesClub && matchesDate
        }.sortedWith { a, b ->
            when (selectedSortOrder) {
                SortOrder.NEWEST -> b.timestampEpochMs.compareTo(a.timestampEpochMs)
                SortOrder.DISTANCE -> b.distanceKm.compareTo(a.distanceKm)
            }
        }
    }

    // Estadísticas acumuladas
    val totalKm = remember(historyList) { historyList.sumOf { it.distanceKm } }
    val totalMeshKm = remember(historyList) { historyList.sumOf { it.meshKm } }
    val totalXp = remember(historyList) { historyList.sumOf { it.xpEarned } }
    val totalRides = remember(historyList) { historyList.size }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlate950)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // 1. Barra de Encabezado Superior (TopBar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(DarkCardElevated)
                        .border(1.dp, DarkBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = TextWhite
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = HeritageCopper,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Bitácora de Rodadas",
                            color = TextWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = if (historyList.isEmpty()) "Sin registros activos" else "$totalRides expedientes · Telemetría BLE",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Banner de Estadísticas Clave (Métricas Globales Dark Elite)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DarkCardBg,
                border = BorderStroke(1.dp, DarkBorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MÉTRICAS ACUMULADAS",
                            color = HeritageCopper,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(EmeraldMesh.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Malla Coded PHY",
                                color = EmeraldMesh,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        HistoryMetricPill(
                            icon = "🛣️",
                            label = "Km Totales",
                            value = String.format(java.util.Locale.US, "%.1f", totalKm),
                            accentColor = TextWhite
                        )
                        HistoryMetricPill(
                            icon = "📡",
                            label = "Km en Malla",
                            value = String.format(java.util.Locale.US, "%.1f", totalMeshKm),
                            accentColor = EmeraldMesh
                        )
                        HistoryMetricPill(
                            icon = "⭐",
                            label = "XP Obtenido",
                            value = "+$totalXp",
                            accentColor = HeritageBrassGold
                        )
                        HistoryMetricPill(
                            icon = "🏍️",
                            label = "Rodadas",
                            value = "$totalRides",
                            accentColor = HeritageCopper
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Barra de Filtros y Ordenamiento (Scroll Horizontal)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filtro por Club
                availableClubs.forEach { club ->
                    val isSelected = selectedClubFilter == club
                    FilterChipTappable(
                        text = if (club == "Todos") "Club: Todos" else club,
                        isSelected = isSelected,
                        onClick = { selectedClubFilter = club }
                    )
                }

                // Separador vertical sutil
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                        .background(DarkBorder)
                )

                // Filtro por Fecha
                DateFilter.values().forEach { filter ->
                    val isSelected = selectedDateFilter == filter
                    FilterChipTappable(
                        text = filter.label,
                        isSelected = isSelected,
                        onClick = { selectedDateFilter = filter }
                    )
                }

                // Separador vertical
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                        .background(DarkBorder)
                )

                // Ordenamiento
                SortOrder.values().forEach { sort ->
                    val isSelected = selectedSortOrder == sort
                    FilterChipTappable(
                        text = "⇅ ${sort.label}",
                        isSelected = isSelected,
                        onClick = { selectedSortOrder = sort }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Lista de Rodadas Filtradas o Estado Vacío
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = DarkCardBg,
                        border = BorderStroke(1.dp, DarkBorderSubtle)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("🏍️", fontSize = 42.sp)
                            Text(
                                text = "Bitácora en Cero",
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Aún no has completado rodadas. Sal a rodar en convoy para registrar tu primera bitácora.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(filteredList, key = { it.id }) { item ->
                        EnrichedRideHistoryCard(
                            item = item,
                            onClick = { selectedItemForDetail = item }
                        )
                    }
                }
            }
        }

        // 5. Modal de Detalle Analítico de Rodada (Overlay Oscuro Fluido)
        selectedItemForDetail?.let { detail ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { selectedItemForDetail = null }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.clickable(enabled = false) {}
                ) {
                    EnrichedRideDetailModal(
                        item = detail,
                        onDismiss = { selectedItemForDetail = null },
                        onShare = {
                            val shareIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "🏍️ Ruta Biker Sync · Resumen de Rodada\n" +
                                            "Ruta: ${detail.title}\n" +
                                            "Distancia: ${detail.distanceKm} km (${detail.meshKm} km en malla)\n" +
                                            "Duración: ${detail.durationMinutes} min\n" +
                                            "XP Ganado: +${detail.xpEarned} XP\n" +
                                            "Club: ${detail.clubName}"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Compartir Bitácora"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipTappable(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) HeritageCopper else DarkCardElevated)
            .border(
                1.dp,
                if (isSelected) HeritageCopper else DarkBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun HistoryMetricPill(
    icon: String,
    label: String,
    value: String,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkCardElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = icon, fontSize = 12.sp)
        Text(
            text = value,
            color = accentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = TextMuted,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun EnrichedRideHistoryCard(
    item: RideHistoryItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = DarkCardBg,
        border = BorderStroke(1.dp, DarkBorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fila Superior: Fecha, Club y Badge de Estado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.dateFormatted,
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text("·", color = TextMuted, fontSize = 10.sp)
                    Text(
                        text = item.clubName,
                        color = HeritageCopper,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Estado de la rodada (ancho no comprimible, maxLines = 1, cero salto de línea)
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (item.badgeStatus) {
                                "✅ Completada" -> EmeraldMesh.copy(alpha = 0.15f)
                                "⏸️ Cancelada" -> RescueRed.copy(alpha = 0.15f)
                                else -> WarningAmber.copy(alpha = 0.15f)
                            }
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = item.badgeStatus,
                        color = when (item.badgeStatus) {
                            "✅ Completada" -> EmeraldMesh
                            "⏸️ Cancelada" -> RescueRed
                            else -> WarningAmber
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Título de la Ruta
            Text(
                text = item.title.ifBlank { "Rodada sin nombre" },
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Fila de Métricas Analíticas: Km Totales, Km en Malla, Duración, XP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCardElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItemCompact(icon = "🛣️", text = String.format(java.util.Locale.US, "%.1f km", item.distanceKm), color = TextWhite)
                MetricItemCompact(icon = "📡", text = String.format(java.util.Locale.US, "%.1f km", item.meshKm), color = EmeraldMesh)
                MetricItemCompact(icon = "⏱️", text = "${item.durationMinutes} min", color = TextWhite)
                MetricItemCompact(icon = "⭐", text = "+${item.xpEarned} XP", color = HeritageBrassGold)
            }

            // Fila Táctica: Rol, Relays, Auxilio SOS e Insignias Desbloqueadas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge de Rol
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(DarkBorder)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.roleInRide,
                            color = TextWhite,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Relays aportados
                    Text(
                        text = "• ${item.relaysCount} relays",
                        color = TextMuted,
                        fontSize = 10.sp
                    )

                    // Asistencias SOS si hubiere
                    if (item.sosAssistsCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(RescueRed.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "🚨 ${item.sosAssistsCount} SOS",
                                color = RescueRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Mini-iconos de Insignias Desbloqueadas
                if (item.badgesUnlocked.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item.badgesUnlocked.take(3).forEach { badgeId ->
                            val badge = BadgeIconMapper.getBadgeDisplay(badgeId)
                            Text(
                                text = badge.icon,
                                fontSize = 12.sp
                            )
                        }
                        if (item.badgesUnlocked.size > 3) {
                            Text(
                                text = "+${item.badgesUnlocked.size - 3}",
                                color = HeritageBrassGold,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItemCompact(
    icon: String,
    text: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(text = icon, fontSize = 11.sp)
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Modal con el desglose analítico de la rodada seleccionada (Solo texto y métricas).
 */
@Composable
private fun EnrichedRideDetailModal(
    item: RideHistoryItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp),
        shape = RoundedCornerShape(18.dp),
        color = DarkCardBg,
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header con Fecha, Club y Botón Cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EXPEDIENTE DE RODADA",
                        color = HeritageCopper,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${item.dateFormatted} · ${item.clubName}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = TextMuted
                    )
                }
            }

            Text(
                text = item.title,
                color = TextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DarkBorder)
            )

            // Resumen de Métricas de Ruta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DetailMetricBox(label = "Km Totales", value = String.format(java.util.Locale.US, "%.1f km", item.distanceKm), color = TextWhite)
                DetailMetricBox(label = "Km Malla", value = String.format(java.util.Locale.US, "%.1f km", item.meshKm), color = EmeraldMesh)
                DetailMetricBox(label = "Duración", value = "${item.durationMinutes} min", color = TextWhite)
                DetailMetricBox(label = "XP Obtenido", value = "+${item.xpEarned}", color = HeritageBrassGold)
            }

            // Datos de Trayecto y Convoy
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = DarkCardElevated,
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "AUDITORÍA DE RUTA Y CONVOY",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("• Origen: ${item.originName}", color = TextWhite, fontSize = 11.sp)
                    Text("• Destino: ${item.destinationName}", color = TextWhite, fontSize = 11.sp)
                    Text("• Tipo de vía: ${item.roadType}", color = TextMuted, fontSize = 11.sp)
                    Text("• Rol desempeñado: ${item.roleInRide}", color = EmeraldMesh, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("• Paquetes relayados: ${item.relaysCount} tramas", color = TextMuted, fontSize = 11.sp)
                    if (item.sosAssistsCount > 0) {
                        Text("• Auxilios SOS atendidos: ${item.sosAssistsCount}", color = RescueRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("• Pilotos confirmados: ${item.confirmedParticipantsCount}", color = TextMuted, fontSize = 11.sp)
                }
            }

            // Insignias y Méritos Desbloqueados
            if (item.badgesUnlocked.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "INSIGNIAS Y LOGROS DESBLOQUEADOS (${item.badgesUnlocked.size})",
                        color = HeritageBrassGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.badgesUnlocked.forEach { badgeId ->
                            val badge = BadgeIconMapper.getBadgeDisplay(badgeId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkCardElevated)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(badge.icon, fontSize = 16.sp)
                                Column {
                                    Text(
                                        text = badge.label,
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Mérito registrado off-grid en la bitácora",
                                        color = TextMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Acciones: Compartir y Cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HeritageCopper),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent)
                ) {
                    Text("Compartir", color = HeritageCopper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HeritageCopper)
                ) {
                    Text("Cerrar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DetailMetricBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}
