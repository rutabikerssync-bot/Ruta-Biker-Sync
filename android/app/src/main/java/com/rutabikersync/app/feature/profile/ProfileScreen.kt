package com.rutabikersync.app.feature.profile

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.R
import com.rutabikersync.app.core.network.ClubApiClient
import com.rutabikersync.app.feature.auth.ProfileRepository
import com.rutabikersync.app.feature.auth.UserProfile
import com.rutabikersync.app.feature.auth.Visibility
import com.rutabikersync.app.feature.badges.BikerRank
import com.rutabikersync.app.feature.badges.GamificationProfileScreen
import com.rutabikersync.app.feature.profile.garage.BikeGarageRepository
import com.rutabikersync.app.feature.profile.garage.GarageStatus
import com.rutabikersync.app.feature.rides.RideHistoryScreen
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.components.HeritageAchievementBadge
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageClubPatchBadge
import com.rutabikersync.app.ui.components.HeritageEmblemBadge
import com.rutabikersync.app.ui.components.HeritageMetricCard
import kotlinx.coroutines.launch

/**
 * Pantalla Principal de Perfil Oficial Heritage Rediseñada (D98).
 * El botón superior de engranaje abre los Ajustes de Cuenta y Sistema (Login/Sesión,
 * Cerrar Sesión, Notificaciones, Visibilidad en mapa público y Buzón de Sugerencias).
 */
@Composable
fun ProfileScreen(
    onOpenGamification: () -> Unit = {},
    onOpenRideHistory: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ProfileRepository(context) }
    val garageRepository = remember { BikeGarageRepository(context) }
    val apiClient = remember { ClubApiClient.getInstance() }
    var garageState by remember { mutableStateOf(garageRepository.loadState()) }
    var activeSection by remember { mutableStateOf(0) } // 0: Piloto, 1: Mi Moto, 2: Garage

    LaunchedEffect(activeSection) {
        if (activeSection == 1 || activeSection == 2) {
            garageRepository.syncFromBackend(apiClient)?.let { synced ->
                garageState = synced
            }
        }
    }
    var profile by remember {
        mutableStateOf(
            repository.getUserProfile() ?: UserProfile(
                uid = "pilot-local",
                email = "piloto@rutabikersync.com",
                aliasBiker = "Capitán Oscar",
                motorcycleBrand = "BMW",
                motorcycleModel = "R1250GS",
                motorcycleDisplacement = 1254,
                motorcycleYear = 2024,
                motorcycleColor = "Negro",
                pinColorHex = "#E5B869"
            )
        )
    }

    var showEditScreen by remember { mutableStateOf(false) }
    var showSupportScreen by remember { mutableStateOf(false) }
    var showGamificationProfile by remember { mutableStateOf(false) }
    var showRideHistory by remember { mutableStateOf(false) }

    // Diálogos de Ajustes del Sistema y Sesión
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var isSuggestionMode by remember { mutableStateOf(false) }

    val settingsPrefs = remember { context.getSharedPreferences("rbs_settings_prefs", Context.MODE_PRIVATE) }
    var notificationsEnabled by remember {
        mutableStateOf(settingsPrefs.getBoolean("notifications_enabled", true))
    }
    var isPublicOnMap by remember(profile.profileVisibility) {
        mutableStateOf(profile.profileVisibility == Visibility.PUBLIC)
    }

    // Diálogo modal para previsualizar insignias en alta resolución
    var selectedBadgePreview by remember {
        mutableStateOf<Triple<String, String, Triple<String, Int, Color>>?>(null)
    }

    // Recargar perfil si volvemos de la edición
    LaunchedEffect(showEditScreen) {
        if (!showEditScreen) {
            repository.getUserProfile()?.let {
                profile = it
                isPublicOnMap = it.profileVisibility == Visibility.PUBLIC
            }
        }
    }

    if (showRideHistory) {
        RideHistoryScreen(
            onNavigateBack = { showRideHistory = false }
        )
        return
    }

    if (showGamificationProfile) {
        GamificationProfileScreen(
            pilotName = profile.aliasBiker.replace("Capit?n", "Capitán").ifBlank { "Capitán Oscar" },
            clubName = "Águilas del Camino MC",
            currentXp = 0,
            currentRank = BikerRank.NOVATO,
            nextRank = BikerRank.RIDER,
            nextRankXp = 100,
            activeStreakMonths = 0,
            totalMeshKm = 0.0,
            onOpenRideHistory = {
                showGamificationProfile = false
                showRideHistory = true
            },
            onShareProfile = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "🛡️ Expediente Biker — ${profile.aliasBiker}")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        """
                        ╔══════════════════════════════════════╗
                        ║   🏍️ RUTA BIKER SYNC · EXPEDIENTE    ║
                        ╚══════════════════════════════════════╝
                        
                        👤 Piloto: ${profile.aliasBiker}
                        ⚔️ Moto: ${profile.motorcycleBrand} ${profile.motorcycleModel}
                        🎖️ Rango: Novato
                        💎 XP Acumulado: 0 XP
                        📡 Km en Malla Táctica: 0 km
                        🔥 Racha Activa: 0 meses
                        
                        ⚡ ¡Conectado y transmitiendo en la malla táctica off-grid!
                        #RutaBikerSync #HermandadBiker #MallaTactical
                        """.trimIndent()
                    )
                }
                context.startActivity(Intent.createChooser(shareIntent, "Compartir Expediente Biker"))
            },
            onNavigateBack = { showGamificationProfile = false }
        )
        return
    }

    if (showEditScreen) {
        ProfileEditScreen(
            currentProfile = profile,
            onNavigateBack = { showEditScreen = false },
            onProfileSaved = { updated ->
                profile = updated
                isPublicOnMap = updated.profileVisibility == Visibility.PUBLIC
                showEditScreen = false
            }
        )
        return
    }

    if (showSupportScreen) {
        SupportScreen(
            isSuggestionMode = isSuggestionMode,
            onNavigateBack = {
                showSupportScreen = false
                isSuggestionMode = false
            }
        )
        return
    }

    val pinColor = runCatching { Color(android.graphics.Color.parseColor(profile.pinColorHex)) }
        .getOrDefault(Color(0xFFE5B869))

    // Decodificación de la foto de perfil en Base64
    val avatarBitmap = remember(profile.photoBase64) {
        if (!profile.photoBase64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(profile.photoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    // Decodificación de la foto de la moto en Base64
    val motoBitmap = remember(profile.motorcyclePhotoBase64) {
        if (!profile.motorcyclePhotoBase64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(profile.motorcyclePhotoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1117))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Cabecera Hero del Piloto
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Doble halo concéntrico con foto o iniciales
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(pinColor.copy(alpha = 0.12f))
                        .border(1.dp, pinColor.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2C3240), Color(0xFF11141A))
                                )
                            )
                            .border(2.dp, pinColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Foto de perfil",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = profile.aliasBiker.replace("Capit?n", "Capitán").take(2).uppercase().ifBlank { "CO" },
                                color = pinColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = profile.aliasBiker.replace("Capit?n", "Capitán").ifBlank { "Capitán Oscar" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                    Text(
                        text = if (profile.motorcycleBrand.isNotBlank()) {
                            "${profile.motorcycleBrand} ${profile.motorcycleModel}"
                        } else {
                            profile.motorcycleModel.ifBlank { "BMW R1250GS" }
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34D399))
                        )
                        Text(
                            text = "En línea",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "·",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Novato",
                            color = Color(0xFFD4AF37),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Botón de Engranaje: Abre Ajustes del Sistema y Cuenta (Login, Notificaciones, Privacidad Mapa, Sugerencias)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF242834), Color(0xFF13151B))
                        )
                    )
                    .border(1.5.dp, Color(0xFF454B5C), CircleShape)
                    .clickable {
                        showSettingsDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Ajustes del Sistema y Cuenta",
                    tint = Color(0xFFE5B869),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Selector segmentado Dark Elite: Piloto | Mi Moto | Garage
        val hasGarageAlerts = remember(garageState) {
            garageRepository.evaluateAll(garageState).any { it.status != GarageStatus.OK }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161922).copy(alpha = 0.82f),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Piloto", "Mi Moto", if (hasGarageAlerts) "Garage •" else "Garage")
                    .forEachIndexed { idx, label ->
                        val selected = activeSection == idx
                        Surface(
                            onClick = { activeSection = idx },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Color(0xFFE5B869).copy(alpha = 0.22f) else Color.Transparent,
                            border = if (selected) BorderStroke(1.dp, Color(0xFFE5B869).copy(alpha = 0.7f)) else null
                        ) {
                            Text(
                                text = label,
                                color = if (selected) Color(0xFFE5B869) else Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 9.dp)
                            )
                        }
                    }
            }
        }

        if (activeSection == 1) {
            MotorcycleProfileSection(
                profile = profile,
                motoBitmap = motoBitmap,
                coolingType = garageState.coolingType,
                odometerKm = garageState.odometerKm,
                onCoolingTypeChange = { newType ->
                    val updated = garageRepository.saveState(garageState.copy(coolingType = newType))
                    garageState = updated
                    scope.launch { garageRepository.pushToBackend(apiClient, updated) }
                },
                onEditProfile = { showEditScreen = true }
            )
            Spacer(modifier = Modifier.height(24.dp))
        } else if (activeSection == 2) {
            BikeGarageSection(
                state = garageState,
                repository = garageRepository,
                onStateChanged = { updated ->
                    garageState = updated
                    scope.launch { garageRepository.pushToBackend(apiClient, updated) }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (activeSection == 0) {
        // 2. Placa Biselada: Rango y Experiencia (XP) - Clickable para abrir perfil de gamificación
        HeritageBevelCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showGamificationProfile = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeritageEmblemBadge(
                    painter = painterResource(id = R.drawable.ic_rank_novato),
                    contentDescription = "Rango del piloto",
                    badgeSize = 52.dp,
                    iconSize = 44.dp,
                    glowColor = Color(0xFFF3C766)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Novato",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "0 / 100 XP",
                            color = Color(0xFFC7CBD6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Hacia Rider",
                            color = Color(0xFF8E95A5),
                            fontSize = 11.sp
                        )
                    }

                    // Barra de progreso dorada en relieve
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF101217))
                            .border(1.dp, Color(0xFF272B36), RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.08f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFF6CE74), Color(0xFFDF9E32))
                                    )
                                )
                        )
                    }
                }
            }
        }

        // 3. Cuadrícula de Métricas Oficiales (Km acumulados, Racha mensual, Rodadas)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeritageMetricCard(
                icon = AppIcons.TabRides,
                iconTint = Color(0xFFE5B869),
                value = "0 km",
                label = "Total acumulado",
                modifier = Modifier.weight(1f)
            )
            HeritageMetricCard(
                icon = AppIcons.RolePresident,
                iconTint = Color(0xFFF97316),
                value = "0 meses",
                label = "Racha mensual",
                modifier = Modifier.weight(1f)
            )
            HeritageMetricCard(
                icon = AppIcons.LeaderFallback,
                iconTint = Color(0xFFE5B869),
                value = "0",
                label = "Rodadas",
                modifier = Modifier.weight(1f)
            )
        }

        // 4. Sección: Hitos y Logros
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hitos y Logros",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1F2630),
                border = BorderStroke(1.2.dp, Color(0xFFD4AF37)),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showGamificationProfile = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🎖️ Ver todos",
                        color = Color(0xFFD4AF37),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "→",
                        color = Color(0xFFC86D44),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeritageAchievementBadge(
                title = "Aventurero",
                subtitle = "5.000 km",
                badgeRes = R.drawable.ic_achieve_aventurero,
                borderColor = Color(0xFFE5B869),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Aventurero",
                        "Superar los 5.000 km de carretera acumulados en convoy.",
                        Triple("ORO", R.drawable.ic_achieve_aventurero, Color(0xFFE5B869))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "1ª Rodada",
                subtitle = "Iniciación",
                badgeRes = R.drawable.ic_achieve_primera_rodada,
                borderColor = Color(0xFFF59E0B),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Primera Rodada",
                        "Completar la primera rodada oficial en convoy con el club.",
                        Triple("BRONCE", R.drawable.ic_achieve_primera_rodada, Color(0xFFF59E0B))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Nocturna",
                subtitle = "Ruta oscura",
                badgeRes = R.drawable.ic_achieve_rodada_nocturna,
                borderColor = Color(0xFF38BDF8),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Rodada Nocturna",
                        "Completar rodadas nocturnas oficiales bajo las estrellas.",
                        Triple("PLATA", R.drawable.ic_achieve_rodada_nocturna, Color(0xFF38BDF8))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Offline",
                subtitle = "Sin señal",
                badgeRes = R.drawable.ic_achieve_sin_cobertura,
                borderColor = Color(0xFF34D399),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Sin Cobertura",
                        "Rodar más de 30 minutos sin internet manteniendo la malla viva.",
                        Triple("PLATA", R.drawable.ic_achieve_sin_cobertura, Color(0xFF34D399))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Viajero",
                subtitle = "500 km",
                badgeRes = R.drawable.ic_achieve_500km,
                borderColor = Color(0xFFE5B869),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Viajero (500 km)",
                        "Acumular 500 km registrados en la malla del convoy.",
                        Triple("PLATA", R.drawable.ic_achieve_500km, Color(0xFFE5B869))
                    )
                }
            )
            HeritageAchievementBadge(
                title = "Primer SOS",
                subtitle = "Auxilio vial",
                badgeRes = R.drawable.ic_achieve_primer_sos,
                borderColor = Color(0xFFEF4444),
                modifier = Modifier.clickable {
                    selectedBadgePreview = Triple(
                        "Primer SOS",
                        "Emitir o confirmar recepción de un auxilio vial en ruta.",
                        Triple("LEGENDARIO", R.drawable.ic_achieve_primer_sos, Color(0xFFEF4444))
                    )
                }
            )
        }

        // 5. Sección: Insignias Recibidas
        Text(
            text = "Insignias Recibidas",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeritageClubPatchBadge(
                clubName = "Hermandad Asfalto",
                badgeText = "Club MC",
                patchColor = Color(0xFFE5B869),
                badgeRes = R.drawable.ic_rank_fundador,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        selectedBadgePreview = Triple(
                            "Hermandad Asfalto",
                            "Parche bordado de honor de membresía de hermandad biker fundadora.",
                            Triple("CLUB MC", R.drawable.ic_rank_fundador, Color(0xFFE5B869))
                        )
                    }
            )
            HeritageClubPatchBadge(
                clubName = "Riders Unidos",
                badgeText = "(Invitado)",
                patchColor = Color(0xFFEF4444),
                badgeRes = R.drawable.ic_rank_honor,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        selectedBadgePreview = Triple(
                            "Riders Unidos",
                            "Insignia de cortesía inter-club recibida en rodada de fraternidad.",
                            Triple("INVITADO", R.drawable.ic_rank_honor, Color(0xFFEF4444))
                        )
                    }
            )
            HeritageClubPatchBadge(
                clubName = "Valle Virgen",
                badgeText = "(Completada)",
                patchColor = Color(0xFF38BDF8),
                badgeRes = R.drawable.ic_rank_full_member,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        selectedBadgePreview = Triple(
                            "Valle Virgen",
                            "Parche conmemorativo por culminar la travesía completa de Valle Virgen.",
                            Triple("EXPEDICIÓN", R.drawable.ic_rank_full_member, Color(0xFF38BDF8))
                        )
                    }
            )
        }

        // 6. Botón Inferior: Centro de Soporte Técnico y FAQ
        Surface(
            onClick = {
                isSuggestionMode = false
                showSupportScreen = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF181C26).copy(alpha = 0.82f),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222938)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFE5B869),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Centro de Soporte Técnico",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Reportar problemas o consultar preguntas frecuentes",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color(0xFFCBD5E1),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        } // fin activeSection == 0

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Modal Diálogo Oficial: Ajustes de Sistema y Cuenta (Engranaje Superior)
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = Color(0xFF141720),
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF242C3D))
                                .border(1.dp, Color(0xFFE5B869), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Color(0xFFE5B869),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Ajustes y Cuenta",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "Sesión, privacidad y preferencias",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = { showSettingsDialog = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Información del Login / Cuenta
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1A1F2C),
                        border = BorderStroke(1.dp, Color(0xFF2E374A))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Cuenta de Usuario",
                                    color = Color(0xFFE5B869),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF34D399))
                                    )
                                    Text(
                                        text = "Sesión Activa",
                                        color = Color(0xFF34D399),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF262C3A))
                                        .border(1.5.dp, pinColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarBitmap != null) {
                                        Image(
                                            bitmap = avatarBitmap,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else {
                                        Text(
                                            text = profile.aliasBiker.take(2).uppercase().ifBlank { "CO" },
                                            color = pinColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.aliasBiker.ifBlank { "Capitán Oscar" },
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = profile.email.ifBlank { "piloto@rutabikersync.com" },
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // Botón Cerrar Sesión
                            Button(
                                onClick = { showLogoutConfirm = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2A1B1E),
                                    contentColor = Color(0xFFEF4444)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = "🚪 Cerrar Sesión",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 2. Mostrar en público en el mapa
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1A1F2C),
                        border = BorderStroke(1.dp, Color(0xFF2E374A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Mostrar en público en el mapa",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (isPublicOnMap) "Visible para toda la comunidad biker" else "Visible solo para miembros de tu club",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isPublicOnMap,
                                onCheckedChange = { checked ->
                                    isPublicOnMap = checked
                                    val newVis = if (checked) Visibility.PUBLIC else Visibility.CLUB_ONLY
                                    val updated = profile.copy(profileVisibility = newVis)
                                    profile = updated
                                    repository.saveUserProfile(updated)
                                    Toast.makeText(
                                        context,
                                        if (checked) "Visibilidad pública activada en mapa" else "Modo club activado",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFE5B869),
                                    checkedTrackColor = Color(0xFF384359),
                                    uncheckedThumbColor = Color(0xFF64748B),
                                    uncheckedTrackColor = Color(0xFF1E2430)
                                )
                            )
                        }
                    }

                    // 3. Notificaciones
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1A1F2C),
                        border = BorderStroke(1.dp, Color(0xFF2E374A))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Notificaciones de Rodada",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Convocatorias, chat táctico y llamadas SOS",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { checked ->
                                    notificationsEnabled = checked
                                    settingsPrefs.edit().putBoolean("notifications_enabled", checked).apply()
                                    Toast.makeText(
                                        context,
                                        if (checked) "Notificaciones activadas" else "Notificaciones silenciadas",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF34D399),
                                    checkedTrackColor = Color(0xFF1E3A2F),
                                    uncheckedThumbColor = Color(0xFF64748B),
                                    uncheckedTrackColor = Color(0xFF1E2430)
                                )
                            )
                        }
                    }

                    // 4. Buzón de sugerencias
                    Surface(
                        onClick = {
                            showSettingsDialog = false
                            isSuggestionMode = true
                            showSupportScreen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1A1F2C),
                        border = BorderStroke(1.dp, Color(0xFF2E374A))
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF272F3F)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("💡", fontSize = 18.sp)
                                }
                                Column {
                                    Text(
                                        text = "Buzón de Sugerencias",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Comparte ideas o mejoras para la plataforma",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSettingsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Listo", color = Color(0xFF101217), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Diálogo de Confirmación para Cerrar Sesión
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = Color(0xFF141720),
            shape = RoundedCornerShape(18.dp),
            title = {
                Text(
                    text = "¿Cerrar Sesión?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Se cerrará tu sesión activa en este dispositivo. Para volver a acceder deberás iniciar sesión con tu cuenta de Google.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        showSettingsDialog = false
                        repository.clearAll()
                        Toast.makeText(context, "Sesión cerrada correctamente", Toast.LENGTH_LONG).show()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Confirmar y Salir", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancelar", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // Modal de Detalle de Insignia en Alta Definición (130 dp)
    selectedBadgePreview?.let { preview ->
        val title = preview.first
        val description = preview.second
        val tier = preview.third.first
        val resId = preview.third.second
        val color = preview.third.third

        AlertDialog(
            onDismissRequest = { selectedBadgePreview = null },
            containerColor = Color(0xFF141720),
            shape = RoundedCornerShape(20.dp),
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2C3240), Color(0xFF101217))
                                )
                            )
                            .border(BorderStroke(2.5.dp, color), RoundedCornerShape(24.dp))
                    ) {
                        Image(
                            painter = painterResource(id = resId),
                            contentDescription = title,
                            modifier = Modifier.size(114.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "DISTINCIÓN: $tier",
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = description,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedBadgePreview = null
                        showGamificationProfile = true
                    }
                ) {
                    Text("Ver Catálogo Completo >", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBadgePreview = null }) {
                    Text("Cerrar", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}
