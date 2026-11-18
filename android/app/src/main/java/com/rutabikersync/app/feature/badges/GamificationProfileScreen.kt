package com.rutabikersync.app.feature.badges

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.*
import java.util.Locale

/**
 * Modelo representativo de una insignia táctica en la vitrina de honor.
 */
data class TacticalBadge(
    val id: String,
    val title: String,
    val description: String,
    val howToUnlock: String,
    val iconEmoji: String,
    val isUnlocked: Boolean,
    val unlockedDate: String? = null,
    val tier: String = "BRONCE", // "BRONCE", "PLATA", "ORO", "LEGENDARIO"
    val badgeRes: Int? = null
)

/**
 * Pantalla oficial de Perfil de Gamificación y Méritos Biker (Fase 7.0 · Hito 7.0.2).
 * Estética Dark Elite + Heritage:
 * 1. Medallón central con anillo en Oro Ámbar (#D4AF37) e icono distintivo por rango.
 * 2. Barra de progreso hacia el siguiente rango ("1.250 / 2.000 XP para Élite").
 * 3. Vitrina de insignias desbloqueadas vs bloqueadas con tooltip modal de desbloqueo.
 * 4. Tarjetas de mérito táctico (km malla, relays, asistencias SOS, horas off-grid, racha).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamificationProfileScreen(
    pilotName: String = "Comandante Águila",
    clubName: String = "Halcones del Valle MC",
    currentXp: Int = 1250,
    currentRank: BikerRank = BikerRank.VETERANO,
    nextRank: BikerRank? = BikerRank.ELITE,
    nextRankXp: Int = 2000,
    activeStreakMonths: Int = 4,
    totalMeshKm: Double = 642.5,
    totalRelays: Int = 184,
    totalSosAssists: Int = 3,
    badges: List<TacticalBadge> = defaultTacticalBadges(),
    onOpenRideHistory: () -> Unit = {},
    onShareProfile: () -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var selectedBadge by remember { mutableStateOf<TacticalBadge?>(null) }

    val goldAmberRing = Color(0xFFD4AF37)

    val rankBadgeEmoji = when (currentRank) {
        BikerRank.NOVATO -> "🥉"
        BikerRank.RIDER -> "🥈"
        BikerRank.VETERANO -> "🥇"
        BikerRank.ELITE -> "💎"
        BikerRank.LEYENDA -> "👑"
    }

    val progressFraction = remember(currentXp, currentRank, nextRankXp) {
        val baseRankXp = currentRank.minXp
        val range = (nextRankXp - baseRankXp).coerceAtLeast(1)
        val gained = (currentXp - baseRankXp).coerceAtLeast(0)
        (gained.toFloat() / range.toFloat()).coerceIn(0.04f, 1f)
    }

    Scaffold(
        containerColor = DarkSlate950,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSlate900,
                    titleContentColor = TextWhite,
                    navigationIconContentColor = TextWhite
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MilitaryTech,
                                contentDescription = null,
                                tint = goldAmberRing,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Expediente Biker & Mérito",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Text(
                            text = "$pilotName · $clubName",
                            fontSize = 11.sp,
                            color = TextMuted
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
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onShareProfile()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1F2630),
                            border = BorderStroke(1.2.dp, Color(0xFFD4AF37).copy(alpha = 0.7f)),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Compartir Expediente",
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Medallón Central con Rango Actual y Anillo Oro Ámbar (#D4AF37)
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkCardBg,
                    border = BorderStroke(1.dp, DarkBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF1B202B), DarkCardBg)
                                )
                            )
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Medallón Concéntrico con Borde Oro Ámbar y Grabado de Alta Definición
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF2C3240), Color(0xFF11141A))
                                    )
                                )
                                .border(BorderStroke(3.dp, goldAmberRing), CircleShape)
                        ) {
                            val rankDrawableRes = when (currentRank) {
                                BikerRank.NOVATO -> com.rutabikersync.app.R.drawable.ic_rank_novato
                                BikerRank.RIDER -> com.rutabikersync.app.R.drawable.ic_rank_rider
                                BikerRank.VETERANO -> com.rutabikersync.app.R.drawable.ic_rank_veterano
                                BikerRank.ELITE -> com.rutabikersync.app.R.drawable.ic_rank_elite
                                BikerRank.LEYENDA -> com.rutabikersync.app.R.drawable.ic_rank_leyenda
                            }
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = rankDrawableRes),
                                contentDescription = currentRank.title,
                                modifier = Modifier.size(80.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "RANGO: ${currentRank.title.uppercase()}",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = goldAmberRing,
                            letterSpacing = 1.5.sp
                        )

                        Text(
                            text = "$currentXp Puntos de Experiencia (XP)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 2. Barra de Progreso hacia el siguiente rango
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PROGRESO AL SIGUIENTE RANGO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted
                                )
                                Text(
                                    text = nextRank?.let {
                                        "$currentXp / $nextRankXp XP para ${it.title}"
                                    } ?: "$currentXp XP (Rango Máximo)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = goldAmberRing
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Barra Cobre Heritage (#C86D44) con fondo DarkSlate950
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(DarkSlate950)
                                    .border(1.dp, DarkBorderSubtle, RoundedCornerShape(5.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progressFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(HeritageCopper, HeritageCopperGlow)
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = nextRank?.let {
                                    val remaining = (nextRankXp - currentXp).coerceAtLeast(0)
                                    "Faltan $remaining XP para ascender a ${it.title}"
                                } ?: "¡Has alcanzado la cúspide motera de la hermandad!",
                                fontSize = 11.sp,
                                color = TextTertiary,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 3. Tarjetas de Mérito Táctico (Métricas del Piloto)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "MÉTRICAS TÁCTICAS DEL PILOTO:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // Fila 1: Km en Malla, Relays, Asistencias SOS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatCard(
                            title = "Km en Malla",
                            value = String.format(Locale.US, "%.1f", totalMeshKm),
                            unit = "km",
                            subtitle = "Convoy conectado",
                            color = EmeraldMesh,
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Relays",
                            value = "$totalRelays",
                            unit = "paq",
                            subtitle = "Malla multirrebote",
                            color = HeritageCopperGlow,
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Auxilios SOS",
                            value = "$totalSosAssists",
                            unit = "asist",
                            subtitle = "Seguridad vial",
                            color = RescueRed,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Fila 2: Horas Off-Grid y Racha Activa
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val hoursOffGrid = totalMeshKm / 42.0
                        StatCard(
                            title = "Horas Off-Grid",
                            value = String.format(Locale.US, "%.1f", hoursOffGrid),
                            unit = "hrs",
                            subtitle = "100% sin cobertura",
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Racha Mensual",
                            value = "$activeStreakMonths",
                            unit = "meses",
                            subtitle = "Constancia en ruta",
                            color = goldAmberRing,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 4. Botón Directo al Historial de Rodadas
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCardElevated,
                    border = BorderStroke(1.dp, DarkBorderProminent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenRideHistory()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = HeritageCopperGlow,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Ver Historial Completo de Rodadas",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                        Text(
                            text = "→",
                            fontSize = 16.sp,
                            color = HeritageCopperGlow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 5. Vitrina de Insignias de Honor
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VITRINA DE INSIGNIAS DE HONOR:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    Text(
                        text = "${badges.count { it.isUnlocked }} de ${badges.size} obtenidas",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = goldAmberRing
                    )
                }
            }

            // Grid de Insignias (2 columnas simétricas con textura Dark Elite)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.chunked(2).forEach { rowBadges ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowBadges.forEach { badge ->
                                BadgeGridItem(
                                    badge = badge,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedBadge = badge
                                    }
                                )
                            }
                            if (rowBadges.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Tooltip de Detalle e Instrucciones de Desbloqueo
    selectedBadge?.let { badge ->
        AlertDialog(
            onDismissRequest = { selectedBadge = null },
            containerColor = DarkCardBg,
            shape = RoundedCornerShape(16.dp),
            title = {
                val badgeBorderColor = when (badge.tier) {
                    "LEGENDARIO" -> HeritageGoldGlow
                    "ORO" -> goldAmberRing
                    "PLATA" -> TextWhite
                    else -> HeritageCopperGlow
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Medalla en Gran Resolución (120 dp) con iluminación y relieves apreciables
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2C3240), Color(0xFF141720))
                                )
                            )
                            .border(BorderStroke(2.dp, badgeBorderColor), RoundedCornerShape(24.dp))
                    ) {
                        if (badge.badgeRes != null) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = badge.badgeRes),
                                contentDescription = badge.title,
                                alpha = if (badge.isUnlocked) 1f else 0.4f,
                                modifier = Modifier.size(105.dp)
                            )
                        } else {
                            Text(text = badge.iconEmoji, fontSize = 54.sp)
                        }
                        if (!badge.isUnlocked) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.75f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Bloqueada",
                                        tint = TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = badge.title,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (badge.isUnlocked) "NIVEL: ${badge.tier}" else "BLOQUEADA (NIVEL: ${badge.tier})",
                        color = badgeBorderColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = badge.description,
                        color = TextMuted,
                        fontSize = 13.sp
                    )

                    if (badge.isUnlocked) {
                        badge.unlockedDate?.let { date ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkCardElevated,
                                border = BorderStroke(1.dp, DarkBorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "✓ Desbloqueada el $date",
                                    color = EmeraldMesh,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Explicación clara de desbloqueo (Requisito fundamental)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSlate900,
                        border = BorderStroke(1.dp, DarkBorderProminent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "💡 ¿CÓMO SE DESBLOQUEA?",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = goldAmberRing
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = badge.howToUnlock,
                                fontSize = 12.sp,
                                color = TextWhite
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedBadge = null }) {
                    Text("ENTENDIDO", color = HeritageCopperGlow, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    unit: String = "",
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCardBg,
        border = BorderStroke(1.dp, DarkBorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = TextMuted,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = color
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = unit,
                        fontSize = 10.sp,
                        color = TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 9.sp,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun BadgeGridItem(
    badge: TacticalBadge,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val goldAmberRing = Color(0xFFD4AF37)
    val borderColor = if (badge.isUnlocked) {
        when (badge.tier) {
            "LEGENDARIO" -> HeritageGoldGlow
            "ORO" -> goldAmberRing
            "PLATA" -> TextWhite
            else -> HeritageCopperGlow
        }
    } else {
        DarkBorderSubtle
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (badge.isUnlocked) DarkCardElevated else DarkCardBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (badge.isUnlocked) Color(0xFF1E232E) else Color(0xFF14171E))
                    .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            ) {
                if (badge.badgeRes != null) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = badge.badgeRes),
                        contentDescription = badge.title,
                        alpha = if (badge.isUnlocked) 1f else 0.35f,
                        modifier = Modifier.size(62.dp)
                    )
                } else {
                    Text(
                        text = badge.iconEmoji,
                        fontSize = 32.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                }
                if (!badge.isUnlocked) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Bloqueada",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = badge.title,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (badge.isUnlocked) TextWhite else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (badge.isUnlocked) badge.tier else "BLOQUEADA",
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (badge.isUnlocked) borderColor else TextTertiary
            )
        }
    }
}

/**
 * Catálogo canónico de insignias oficiales (GAMIFICATION.md).
 */
fun defaultTacticalBadges(): List<TacticalBadge> = listOf(
    TacticalBadge(
        id = "b_vault",
        title = "Piloto Protegido",
        description = "Bóveda biométrica completada y credencial activa.",
        howToUnlock = "Se obtiene al configurar la Bóveda Biométrica y respaldar la credencial E2EE de identidad.",
        iconEmoji = "🛡️",
        isUnlocked = true,
        unlockedDate = "12 Nov 2026",
        tier = "ORO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_rank_honor
    ),
    TacticalBadge(
        id = "b_offline",
        title = "Sin Cobertura",
        description = "Rodar más de 30 min sin internet con la malla BLE activa.",
        howToUnlock = "Se obtiene en rodadas oficiales manteniendo la red de malla viva por más de 30 minutos sin datos móviles.",
        iconEmoji = "📡",
        isUnlocked = true,
        unlockedDate = "10 Nov 2026",
        tier = "PLATA",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_sin_cobertura
    ),
    TacticalBadge(
        id = "b_convoy",
        title = "Convoy Seguro",
        description = "Ficha médica cifrada compartida con el convoy.",
        howToUnlock = "Se obtiene al compartir la ficha de emergencia con el convoy al inicio de la rodada.",
        iconEmoji = "🚑",
        isUnlocked = true,
        unlockedDate = "08 Nov 2026",
        tier = "ORO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_trotamundos
    ),
    TacticalBadge(
        id = "b_sos",
        title = "Primer SOS",
        description = "Recibir o responder a una alerta SOS de emergencia.",
        howToUnlock = "Se obtiene al emitir o confirmar recepción de un auxilio vial en ruta.",
        iconEmoji = "🆘",
        isUnlocked = true,
        unlockedDate = "04 Nov 2026",
        tier = "LEGENDARIO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_primer_sos
    ),
    TacticalBadge(
        id = "b_first_ride",
        title = "Primera Rodada",
        description = "Completar la primera rodada oficial en convoy.",
        howToUnlock = "Se obtiene al finalizar con éxito tu primera salida grupal con el club.",
        iconEmoji = "🎉",
        isUnlocked = true,
        unlockedDate = "01 Oct 2026",
        tier = "BRONCE",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_primera_rodada
    ),
    TacticalBadge(
        id = "b_viajero",
        title = "Viajero (500 km)",
        description = "Acumular 500 km en rodadas válidas de convoy.",
        howToUnlock = "Se obtiene al sumar 500 kilómetros registrados en la malla del convoy.",
        iconEmoji = "🛣️",
        isUnlocked = true,
        unlockedDate = "02 Nov 2026",
        tier = "PLATA",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_500km
    ),
    TacticalBadge(
        id = "b_racha3",
        title = "Racha de 3",
        description = "Rodar al menos 1 vez al mes durante 3 meses consecutivos.",
        howToUnlock = "Se obtiene manteniendo la constancia de rodada mensual ininterrumpida por un trimestre.",
        iconEmoji = "🔥",
        isUnlocked = true,
        unlockedDate = "01 Nov 2026",
        tier = "ORO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_rodada_3_grupos
    ),
    TacticalBadge(
        id = "b_relay",
        title = "Centinela de Malla",
        description = "Retransmitir más de 100 paquetes multirrebote en la red.",
        howToUnlock = "Se obtiene cuando tu teléfono actúa como repetidor para compañeros distantes en el convoy.",
        iconEmoji = "⚡",
        isUnlocked = false,
        unlockedDate = null,
        tier = "ORO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_cartografo
    ),
    TacticalBadge(
        id = "b_night",
        title = "Rodada Nocturna",
        description = "Completar 1 rodada nocturna después de las 20:00 o antes de las 06:00.",
        howToUnlock = "Se obtiene al rodar en convoy durante horarios nocturnos.",
        iconEmoji = "🌙",
        isUnlocked = false,
        unlockedDate = null,
        tier = "PLATA",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_rodada_nocturna
    ),
    TacticalBadge(
        id = "b_leyenda",
        title = "Leyenda Biker",
        description = "Alcanzar el rango supremo con más de 5.000 XP acumulados.",
        howToUnlock = "Se obtiene al llegar a 5.000 XP de mérito acumulado en la hermandad.",
        iconEmoji = "👑",
        isUnlocked = false,
        unlockedDate = null,
        tier = "LEGENDARIO",
        badgeRes = com.rutabikersync.app.R.drawable.ic_achieve_legendario
    )
)
