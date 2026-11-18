package com.rutabikersync.app.feature.badges

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rutabikersync.app.ui.theme.*

/**
 * F2. PANTALLA OFICIAL DE RANGO E INSIGNIAS (GAMIFICATION.md, SCREENS.md F2).
 * Estética Dark Elite (fondos carbón #0B0E14, acentos dorados y esmeraldas).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankAndBadgesScreen(
    repository: GamificationRepository? = null,
    onNavigateBack: () -> Unit = {},
    onOpenShareCard: () -> Unit = {}
) {
    val context = LocalContext.current
    val gamificationRepo = remember { repository ?: GamificationRepository(context) }
    val profile by gamificationRepo.profileFlow.collectAsState()

    var selectedFamily by remember { mutableStateOf(BadgeFamily.TODAS) }
    var badgeDetailSelected by remember { mutableStateOf<BadgeItem?>(null) }

    val filteredBadges = remember(profile.badges, selectedFamily) {
        if (selectedFamily == BadgeFamily.TODAS) {
            profile.badges
        } else {
            profile.badges.filter { it.family == selectedFamily }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Rango y Méritos Biker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = HeritageLeatherBrown
                        )
                        Text(
                            text = "Expediente de rodadas y trayectoria",
                            fontSize = 11.sp,
                            color = HeritageLeatherMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = HeritageLeatherBrown
                        )
                    }
                },
                actions = {
                    // Botón para tarjeta de rodada compartible (P2)
                    IconButton(onClick = onOpenShareCard) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Tarjeta Compartible",
                            tint = HeritageCopper
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeritageSand
                )
            )
        },
        containerColor = HeritageSand
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // CABECERA HERO CON PROGRESIÓN (Scrollable header)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 1. TARJETA HERO: RANGO Y XP
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = HeritageCard
                    ),
                    border = BorderStroke(1.dp, HeritageBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Escudo gigante del rango actual
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(CircleShape)
                                .background(HeritageCardWarm)
                                .border(2.dp, HeritageCopper, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = profile.rank.badgeRes),
                                contentDescription = profile.rank.title,
                                modifier = Modifier.size(52.dp)
                            )
                        }

                        // Info del nivel y barra de XP
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Rango: ${profile.rank.title}",
                                    color = HeritageLeatherBrown,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = HeritageCardWarm,
                                    border = BorderStroke(1.dp, HeritageBorder)
                                ) {
                                    Text(
                                        text = "${profile.xp} XP",
                                        color = HeritageCopper,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Barra de progreso de XP
                            LinearProgressIndicator(
                                progress = { profile.xpProgressPercent },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = HeritageCopper,
                                trackColor = HeritageCardWarm
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            val nextRankTitle = profile.nextRank?.title ?: "Máximo Rango"
                            val nextRankMinXp = profile.nextRank?.minXp ?: profile.xp
                            val remainingXp = (nextRankMinXp - profile.xp).coerceAtLeast(0)

                            Text(
                                text = if (profile.nextRank != null) {
                                    "Faltan $remainingXp XP para $nextRankTitle (${(profile.xpProgressPercent * 100).toInt()}%)"
                                } else {
                                    "¡Has alcanzado la cúspide de Leyenda Biker!"
                                },
                                color = HeritageLeatherMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. METRICAS DUALES: KILOMETRAJE + RACHA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Widget Kilometraje
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = HeritageCard),
                        border = BorderStroke(1.dp, HeritageBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = "🛣️", fontSize = 13.sp)
                                Text(
                                    text = "Kilometraje",
                                    color = HeritageLeatherMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%,.1f km", profile.totalKm),
                                color = HeritageLeatherBrown,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { profile.nextKmProgressPercent },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = HeritageCopper,
                                trackColor = HeritageCardWarm
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "A ${profile.nextKmRemaining.toInt()} km de ${profile.nextKmBadgeName}",
                                color = HeritageCopper,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Widget Racha Mensual
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = HeritageCard),
                        border = BorderStroke(1.dp, HeritageBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = "🔥", fontSize = 13.sp)
                                Text(
                                    text = "Racha Mensual",
                                    color = HeritageLeatherMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "${profile.streakMonths} meses seguidos",
                                color = HeritageCopper,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (profile.streakMonths >= 3) "¡Bonificación de +20 XP activa!" else "Rueda este mes para mantener",
                                style = MaterialTheme.typography.bodySmall,
                                color = HeritageLeatherMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3. EXPEDIENTE CRUZADO (Honor recibido de otros clubes)
                if (profile.crossClubHonorBadges.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = HeritageCard),
                        border = BorderStroke(1.dp, HeritageBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = HeritageGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Expediente Cruzado (${profile.crossClubHonorBadges.size})",
                                        color = HeritageLeatherBrown,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = "Clubes Hermanos",
                                    color = HeritageGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                profile.crossClubHonorBadges.forEach { badge ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = HeritageCardWarm,
                                        border = BorderStroke(1.dp, HeritageBorder),
                                        modifier = Modifier.clickable { badgeDetailSelected = badge }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Image(
                                                painter = painterResource(id = badge.iconRes),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = badge.name,
                                                    color = HeritageLeatherBrown,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = badge.issuerName ?: "Club Amigo",
                                                    color = HeritageCopper,
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // SELECTOR DE FAMILIAS (CHIPS HORIZONTALES)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BadgeFamily.values().forEach { family ->
                    val isSelected = family == selectedFamily
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFamily = family },
                        label = {
                            Text(
                                text = family.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = HeritageCardWarm,
                            labelColor = HeritageLeatherMuted,
                            selectedContainerColor = HeritageCopper,
                            selectedLabelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = HeritageBorder,
                            selectedBorderColor = HeritageCopper,
                            enabled = true,
                            selected = isSelected
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // REJILLA 3×N DE INSIGNIAS
            if (filteredBadges.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "🏍️", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Aún no tienes insignias en esta categoría",
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Participa en rodadas oficiales, acumula kilómetros y cumple roles de convoy.",
                            color = HeritageLeatherMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredBadges, key = { it.id }) { badge ->
                        BadgeGridItem(
                            badge = badge,
                            onClick = { badgeDetailSelected = badge }
                        )
                    }
                }
            }
        }
    }

    // DIÁLOGO DE DETALLE DE INSIGNIA
    badgeDetailSelected?.let { badge ->
        AlertDialog(
            onDismissRequest = { badgeDetailSelected = null },
            confirmButton = {
                TextButton(onClick = { badgeDetailSelected = null }) {
                    Text(text = "Cerrar", color = HeritageCopper, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (badge.unlocked) {
                    TextButton(onClick = {
                        badgeDetailSelected = null
                        onOpenShareCard()
                    }) {
                        Text(text = "Compartir", color = HeritageLeatherBrown)
                    }
                }
            },
            containerColor = HeritageSand,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(HeritageCardWarm)
                            .border(
                                1.5.dp,
                                if (badge.unlocked) HeritageCopper else HeritageBorder,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = badge.iconRes),
                            contentDescription = badge.name,
                            modifier = Modifier
                                .size(40.dp)
                                .alpha(if (badge.unlocked) 1.0f else 0.35f)
                        )
                        if (!badge.unlocked) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Bloqueado",
                                tint = HeritageRustRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = badge.name,
                            color = HeritageLeatherBrown,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = badge.family.label,
                            color = if (badge.unlocked) HeritageGreen else HeritageLeatherMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = badge.description,
                        color = HeritageLeatherBrown,
                        fontSize = 13.sp
                    )

                    if (badge.issuerName != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = HeritageCardWarm,
                            border = BorderStroke(1.dp, HeritageBorder)
                        ) {
                            Text(
                                text = "🏆 Condecoración otorgada por: ${badge.issuerName}",
                                color = HeritageCopper,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (badge.maxProgress > 0) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Progreso:",
                                    color = HeritageLeatherMuted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${badge.currentProgress.toInt()} / ${badge.maxProgress.toInt()} ${badge.unit}",
                                    color = HeritageLeatherBrown,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (badge.currentProgress / badge.maxProgress).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (badge.unlocked) HeritageGreen else HeritageCopper,
                                trackColor = HeritageCardWarm
                            )
                        }
                    }

                    badge.unlockedAtMillis?.let { timestamp ->
                        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
                        Text(
                            text = "Desbloqueada el: $dateStr",
                            color = HeritageGreen,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun BadgeGridItem(
    badge: BadgeItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (badge.unlocked) HeritageCard else HeritageCardWarm.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            1.dp,
            if (badge.unlocked) HeritageCopper.copy(alpha = 0.5f) else HeritageBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HeritageCardWarm)
                    .border(1.dp, HeritageBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = badge.iconRes),
                    contentDescription = badge.name,
                    modifier = Modifier
                        .size(42.dp)
                        .alpha(if (badge.unlocked) 1.0f else 0.28f)
                )

                if (!badge.unlocked) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(HeritageSand.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Bloqueada",
                            tint = HeritageLeatherMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = badge.name,
                color = if (badge.unlocked) HeritageLeatherBrown else HeritageLeatherMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (badge.unlocked) "Completada" else "Bloqueada",
                color = if (badge.unlocked) HeritageGreen else HeritageLeatherMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
