package com.rutabikersync.app.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import com.rutabikersync.app.ui.components.HeritageBevelCard
import com.rutabikersync.app.ui.components.HeritageBrassButton
import com.rutabikersync.app.ui.components.HeritageEmblemBadge
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.R
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.feature.groups.DevPanelDialog
import com.rutabikersync.app.feature.groups.GroupsMainTabScreen
import com.rutabikersync.app.feature.groups.Phase1ViewModel
import com.rutabikersync.app.feature.groups.ProfileAndAuthTabScreen
import com.rutabikersync.app.feature.livemap.LiveMapScreen
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.feature.routes.RideScheduleRepository
import com.rutabikersync.app.feature.routes.ScheduledRide
import com.rutabikersync.app.feature.routes.AttendanceStatus
import com.rutabikersync.app.feature.rides.RideParticipantsScreen
import com.rutabikersync.app.feature.routes.ScheduleRideMapScreen
import com.rutabikersync.app.feature.routes.RideHistoryScreen
import com.rutabikersync.app.feature.routes.CorridorOfflineManager
import com.rutabikersync.app.feature.vault.VaultScreen

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rutabikersync.app.core.uxtest.UxTestTracker
import com.rutabikersync.app.core.uxtest.ModeratorControlDialog

import androidx.compose.material3.NavigationBarItemDefaults
import com.rutabikersync.app.ui.theme.HeritageTheme

/**
 * Navegación principal con barra inferior (SCREENS.md §2: Rodadas · Grupo · Rutas · Perfil)
 * y botón flotante DEV exclusivo de compilaciones Debug (BuildConfig.DEV_PANEL_ENABLED).
 */
@Composable
fun MainAppScaffold(
    phase1ViewModel: Phase1ViewModel = viewModel(),
    isDarkMode: Boolean = true,
    onToggleDarkMode: () -> Unit = {},
    initialStartRidePreview: Boolean = false,
    initialStartTacticalChat: Boolean = false,
    initialStartMeshMonitor: Boolean = false,
    initialStartRideHistory: Boolean = false,
    initialFocusKeyboard: Boolean = false,
    initialStartGamificationProfile: Boolean = false,
    initialSelectedTab: Int = 0,
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        UxTestTracker.init(context)
    }

    var selectedTab by remember { mutableIntStateOf(initialSelectedTab) }
    var showLiveMapPreview by remember { mutableStateOf(initialStartRidePreview) }
    var showDevPanel by remember { mutableStateOf(false) }
    var showModeratorDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val uxParticipant by UxTestTracker.participantId.collectAsState()
    val rideState by RideService.sharedState.collectAsState()
    val isRideActive = rideState.lifecycleState == RideLifecycleState.RUNNING || rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP
    val isFullscreenCockpit = isRideActive || showLiveMapPreview

    LaunchedEffect(selectedTab) {
        val screenName = when (selectedTab) {
            0 -> "RODADAS_HOME"
            1 -> "GROUPS_TAB"
            2 -> "ROUTES_TAB"
            else -> "PROFILE_TAB"
        }
        UxTestTracker.logEvent(screen = screenName, action = "SCREEN_ENTER", target = "tab_$selectedTab")
    }

    Scaffold(
        containerColor = HeritageTheme.colors.background,
        floatingActionButton = {
            if (BuildConfig.DEV_PANEL_ENABLED && !isFullscreenCockpit) {
                FloatingActionButton(
                    onClick = {
                        UxTestTracker.logEvent("DEV_PANEL", "OPEN_PANEL", "btn_dev_panel")
                        showDevPanel = true
                    },
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = HeritageTheme.colors.primary,
                    contentColor = HeritageTheme.colors.onPrimary
                ) {
                    Text(
                        text = stringResource(id = R.string.dev_panel_btn),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }
        },
        bottomBar = {
            if (!isFullscreenCockpit) {
                com.rutabikersync.app.ui.components.HeritageBottomNavigationBar(
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreenCockpit) PaddingValues(0.dp) else innerPadding)
        ) {
            if (isFullscreenCockpit) {
                LiveMapScreen(
                    onStopRideHoldCompleted = {
                        showLiveMapPreview = false
                    }
                )
            } else {
                when (selectedTab) {
                    0 -> RidesReleaseHomeScreen(
                        phase1ViewModel = phase1ViewModel,
                        onStartLiveMapPreview = { showLiveMapPreview = true },
                        initialOpenRideHistory = initialStartRideHistory
                    )
                    1 -> GroupsMainTabScreen(viewModel = phase1ViewModel)
                    2 -> VaultScreen(phase1ViewModel = phase1ViewModel)
                    else -> com.rutabikersync.app.feature.profile.ProfileScreen(onLogout = onLogout)
                }
            }

            if (BuildConfig.UX_TEST_MODE && !isFullscreenCockpit) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BikerColors.PrimaryAmber)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    enteredPin = ""
                                    pinError = false
                                    showPinDialog = true
                                },
                                onTap = {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Mantén presionado 2 s para control de moderador",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "UX TEST · $uxParticipant",
                        color = BikerColors.BackgroundDark,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (BuildConfig.DEV_PANEL_ENABLED && showDevPanel) {
            DevPanelDialog(
                viewModel = phase1ViewModel,
                onDismiss = { showDevPanel = false }
            )
        }

        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = {
                    showPinDialog = false
                    enteredPin = ""
                    pinError = false
                },
                title = {
                    Text(
                        text = "Acceso Moderador UX",
                        color = BikerColors.PrimaryAmber,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Ingresa el PIN de seguridad de 4 dígitos para abrir el panel de control del moderador:",
                            color = BikerColors.TextPrimary,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = {
                                if (it.length <= 4) {
                                    enteredPin = it
                                    pinError = false
                                }
                            },
                            label = { Text("PIN (4 dígitos)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = pinError,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinError) {
                            Text(
                                text = "PIN incorrecto. Intenta de nuevo.",
                                color = BikerColors.StatusRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (UxTestTracker.verifyPin(enteredPin)) {
                                UxTestTracker.logEvent("MODERATOR_PANEL", "PIN_AUTH_SUCCESS", "badge_ux_test")
                                showPinDialog = false
                                enteredPin = ""
                                pinError = false
                                showModeratorDialog = true
                            } else {
                                UxTestTracker.logEvent("MODERATOR_PANEL", "PIN_AUTH_FAIL", "badge_ux_test")
                                pinError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                    ) {
                        Text("Acceder", color = BikerColors.BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showPinDialog = false
                            enteredPin = ""
                            pinError = false
                        }
                    ) {
                        Text("Cancelar")
                    }
                },
                containerColor = BikerColors.SurfaceDark
            )
        }

        if (showModeratorDialog) {
            ModeratorControlDialog(
                onDismiss = { showModeratorDialog = false }
            )
        }
    }
}

@Composable
private fun VaultTabScreen() {
    val context = LocalContext.current
    var showAddDocDialog by remember { mutableStateOf(false) }
    var docNameInput by remember { mutableStateOf("") }
    var docNumberInput by remember { mutableStateOf("") }
    var showOnLockScreen by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera oficial Bóveda (Screen 4) con escudo en relieve
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeritageEmblemBadge(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(id = R.string.vault_title),
                badgeSize = 46.dp,
                iconSize = 22.dp,
                glowColor = Color(0xFFF3C766)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.vault_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = stringResource(id = R.string.vault_subtitle),
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
            }
        }

        // Sección: Documentos de la Moto
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.vault_docs_title),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = R.string.vault_view_all),
                    color = Color(0xFFE5B869),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Grid 2x2 de documentos biselados
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultDocBevelCard(
                    title = stringResource(id = R.string.vault_doc_license),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    icon = Icons.Default.CreditCard,
                    badgeBg = Color(0xFF382A14),
                    badgeBorder = Color(0xFFE5B869),
                    modifier = Modifier.weight(1f)
                )
                VaultDocBevelCard(
                    title = stringResource(id = R.string.vault_doc_insurance),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    icon = Icons.Default.VerifiedUser,
                    badgeBg = Color(0xFF122C20),
                    badgeBorder = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultDocBevelCard(
                    title = stringResource(id = R.string.vault_doc_circulation),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    icon = Icons.Default.Description,
                    badgeBg = Color(0xFF0E2829),
                    badgeBorder = Color(0xFF14B8A6),
                    modifier = Modifier.weight(1f)
                )
                VaultDocBevelCard(
                    title = stringResource(id = R.string.vault_doc_inspection),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    icon = Icons.Default.Build,
                    badgeBg = Color(0xFF132338),
                    badgeBorder = Color(0xFF3B82F6),
                    modifier = Modifier.weight(1f)
                )
            }

            // Botón Placa de Latón: + Agregar documento
            HeritageBrassButton(
                text = stringResource(id = R.string.vault_btn_add_document),
                onClick = { showAddDocDialog = true },
                icon = Icons.Default.Add
            )
        }

        // Sección: Ficha Médica de Emergencia (Sellado con Shamir) con relieve
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    HeritageEmblemBadge(
                        imageVector = Icons.Default.LocalHospital,
                        contentDescription = null,
                        badgeSize = 36.dp,
                        iconSize = 18.dp,
                        glowColor = Color(0xFFF3C766)
                    )
                    Text(
                        text = stringResource(id = R.string.vault_medical_title),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🔒 ${stringResource(id = R.string.vault_medical_sealed_shamir)}",
                        color = Color(0xFF34D399),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_blood_type), color = Color(0xFF8E95A5), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_blood_type_empty), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_allergies), color = Color(0xFF8E95A5), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_allergies_empty), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_donor), color = Color(0xFF8E95A5), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_donor_empty), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_emergency_contact), color = Color(0xFF8E95A5), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_emergency_contact_empty), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF272B36))
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Switch "Mostrar en pantalla de bloqueo"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mostrar en pantalla de bloqueo",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = showOnLockScreen,
                    onCheckedChange = { showOnLockScreen = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF101114),
                        checkedTrackColor = Color(0xFFF3C766),
                        uncheckedThumbColor = Color(0xFF8E95A5),
                        uncheckedTrackColor = Color(0xFF272B36)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showAddDocDialog) {
        AlertDialog(
            onDismissRequest = { showAddDocDialog = false },
            title = {
                Text("Agregar Documento a la Bóveda", color = Color(0xFFE5B869), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ingresa los datos del documento para cifrarlos localmente con clave Shamir:", color = Color.White, fontSize = 12.sp)
                    OutlinedTextField(
                        value = docNameInput,
                        onValueChange = { docNameInput = it },
                        label = { Text("Tipo de documento (ej. Licencia, Seguro)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = docNumberInput,
                        onValueChange = { docNumberInput = it },
                        label = { Text("Número / Folio") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAddDocDialog = false
                        android.widget.Toast.makeText(context, "Documento cifrado y guardado en Bóveda", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869))
                ) {
                    Text("Cifrar y Guardar", color = Color(0xFF101114), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDocDialog = false }) {
                    Text("Cancelar")
                }
            },
            containerColor = Color(0xFF181A20)
        )
    }
}

@Composable
private fun VaultDocBevelCard(
    title: String,
    badge: String,
    icon: ImageVector,
    badgeBg: Color,
    badgeBorder: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1D24), Color(0xFF111317))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF3E4454), Color(0xFF181A20))
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .border(1.dp, badgeBorder, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeBorder,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = badge,
                color = if (badge.contains("no cargado", ignoreCase = true)) Color(0xFF8E95A5) else Color(0xFF10B981),
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RidesReleaseHomeScreen(
    phase1ViewModel: Phase1ViewModel,
    onStartLiveMapPreview: () -> Unit,
    initialOpenRideHistory: Boolean = false
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val rideState by RideService.sharedState.collectAsState()
    val currentRide by RideScheduleRepository.currentRide.collectAsState()
    val groups by phase1ViewModel.repository.groups.collectAsState()
    val currentUser by phase1ViewModel.repository.currentUser.collectAsState()
    val rideRoster by phase1ViewModel.rideRoster.collectAsState()
    val rideRosterError by phase1ViewModel.rideRosterError.collectAsState()
    val isStartingRide by phase1ViewModel.isStartingRide.collectAsState()
    val rsvpPendingCount by phase1ViewModel.rsvpPendingCount.collectAsState()

    val primaryGroup = groups.firstOrNull { group ->
        group.principalLeaderId == currentUser.id ||
            group.leaderIds.contains(currentUser.id) ||
            group.members.any { it.userId == currentUser.id }
    }
    val canPublishClubRoute = primaryGroup?.let { group ->
        group.principalLeaderId == currentUser.id || group.leaderIds.contains(currentUser.id)
    } == true

    LaunchedEffect(currentRide?.clubRouteId, primaryGroup?.id) {
        val publishedRouteId = currentRide?.clubRouteId
        if (publishedRouteId != null) {
            phase1ViewModel.loadRideRoster(publishedRouteId)
        } else if (primaryGroup != null) {
            phase1ViewModel.loadUpcomingRideRoster(primaryGroup.id)
        }
    }
    LaunchedEffect(rideRoster?.routeId) {
        val routeId = rideRoster?.routeId ?: return@LaunchedEffect
        while (isActive) {
            delay(3000L)
            phase1ViewModel.loadRideRoster(routeId)
        }
    }

    val activityIntent = (context as? Activity)?.intent
    val startValenciaRoute = activityIntent?.getBooleanExtra("START_VALENCIA_ROUTE", false) ?: false
    val startJarilloRoute = activityIntent?.getBooleanExtra("START_JARILLO_ROUTE", false) ?: false
    var showScheduleMapPlanner by remember { mutableStateOf(startValenciaRoute || startJarilloRoute) }
    var showRideHistoryDialog by remember { mutableStateOf(initialOpenRideHistory) }
    var forceSimulated by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showSimulatedFallbackPrompt by remember { mutableStateOf(false) }
    var permanentlyDeniedDetected by remember { mutableStateOf(false) }
    var startErrorBroadcastMessage by remember { mutableStateOf<String?>(null) }

    if (showScheduleMapPlanner) {
        ScheduleRideMapScreen(
            onDismiss = { showScheduleMapPlanner = false },
            onRideSaved = { showScheduleMapPlanner = false },
            onPublishClubRoute = if (canPublishClubRoute) {
                { title, startsAt, description ->
                    phase1ViewModel.publishClubRoute(primaryGroup.id, title, startsAt, description)
                }
            } else {
                null
            }
        )
        return
    }

    if (showRideHistoryDialog) {
        RideHistoryScreen(
            onDismiss = { showRideHistoryDialog = false },
            onNavigateBack = { showRideHistoryDialog = false },
            onOpenRidePlanner = {
                showRideHistoryDialog = false
                showScheduleMapPlanner = true
            }
        )
        return
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == RideService.ACTION_RIDE_START_ERROR) {
                    startErrorBroadcastMessage =
                        intent.getStringExtra(RideService.EXTRA_RIDE_START_ERROR_MESSAGE)
                }
            }
        }
        val filter = IntentFilter(RideService.ACTION_RIDE_START_ERROR)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val requiredRidePermissions = remember {
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

    fun startRideServiceNow(useSimulated: Boolean) {
        startErrorBroadcastMessage = null
        val eligibility = RideService.dispatchStartRide(
            context = context,
            rideId = "ride-live-01",
            localShortId = run {
                val androidId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).orEmpty()
                val uniqueSeed = "${android.os.Build.MODEL}:$androidId"
                ((uniqueSeed.hashCode() and 0x7FFF) % 65534) + 1
            },
            forceSimulated = useSimulated
        )
        if (!useSimulated && !eligibility.shouldPromoteToForeground) {
            startErrorBroadcastMessage =
                "Malla BLE no viable en este dispositivo o sin permisos/Bluetooth activo. " +
                    "Se inició en modo simulado dentro de la app sin notificación persistente en primer plano."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val meshViable = RideService.isMeshViable(context)
        if (meshViable) {
            permanentlyDeniedDetected = false
            showSimulatedFallbackPrompt = false
            startRideServiceNow(useSimulated = forceSimulated)
        } else {
            val permDeniedPermanently = activity != null && requiredRidePermissions.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            permanentlyDeniedDetected = permDeniedPermanently
            showSimulatedFallbackPrompt = true
        }
    }

    // Recepción reactiva de ciclo de rodada para miembros convocados (Fase C · Sincronización)
    LaunchedEffect(Unit) {
        RideService.incomingRideLifecycleEvents.collect { incoming ->
            when (incoming.event) {
                com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_OUTBOUND -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(false)
                    onStartLiveMapPreview()
                    startRideServiceNow(useSimulated = forceSimulated)
                }
                com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.START_RETURN -> {
                    com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(true)
                    onStartLiveMapPreview()
                    startRideServiceNow(useSimulated = forceSimulated)
                }
                com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.ARRIVE_DESTINATION,
                com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.COMPLETE_RIDE,
                com.rutabikersync.app.core.mesh.MeshRelayEngine.RideLifecycleEvent.SAFE_HOME -> {
                    rideRoster?.let { phase1ViewModel.loadRideRoster(it.routeId) }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera RODADAS (Screen 2) con Escudo en relieve y Acciones
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeritageEmblemBadge(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_logo_ruta_biker_sync),
                    contentDescription = "Club",
                    badgeSize = 46.dp,
                    iconSize = 34.dp,
                    glowColor = Color(0xFFF3C766)
                )
                Column {
                    Text(
                        text = "RODADAS",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Clima: 28°C · Despejado",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp
                    )
                }
            }

            // Botón: 📖 Bitácora de Rodadas (Historial y Estadísticas de Club)
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2E1C0C), Color(0xFF1E2129))
                        )
                    )
                    .border(1.2.dp, Color(0xFFDF9E32), RoundedCornerShape(10.dp))
                    .clickable {
                        UxTestTracker.logEvent("RODADAS_HOME", "TAP_RIDE_HISTORY", "btn_bitacora_rodadas")
                        showRideHistoryDialog = true
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📖", fontSize = 14.sp)
                    Text(
                        text = "Bitácora de Rodadas",
                        color = Color(0xFFFFEEBA),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Tarjeta: Próxima Rodada (Biselada y viva)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp)) {
                CardBackdropImage(imageRes = R.drawable.card_convocatoria_club)
                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(14.dp)) {
                    if (currentRide != null) {
                        val ride = currentRide!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = R.string.rides_next_ride),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = ride.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${ride.dateSchedule} · ${ride.departureTime}",
                                    color = Color(0xFFE5B869),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "${ride.origin.name} \u2192 ${ride.destination.name}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${ride.distanceKm} km · ${ride.estimatedMinutes} min",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 12.sp
                                )
                                if (!ride.description.isNullOrBlank()) {
                                    Text(
                                        text = ride.description,
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "✓ Convocada",
                                    color = Color(0xFF34D399),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Próxima Convocatoria de Club",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = stringResource(id = R.string.rides_no_scheduled),
                                    color = Color(0xFFE5B869),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Exclusivo de la directiva del club para trazar rutas oficiales y convocar a los miembros.",
                                    color = Color(0xFF8E95A5),
                                    fontSize = 11.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF242732))
                                    .border(1.dp, Color(0xFF3E4455), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Sin programar",
                                    color = Color(0xFF8E95A5),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        HeritageBrassButton(
                            text = "🗺️ + Crear Ruta Oficial de Club",
                            onClick = { showScheduleMapPlanner = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Tarjeta: Participantes Convocados (Biselada) - Solo si hay rodada convocada
        if (currentRide != null && rideRoster == null) {
            HeritageBevelCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) {
                val confirmations = currentRide!!.confirmations
                val confirmedCount = confirmations.count { it.attendanceStatus == AttendanceStatus.CONFIRMED }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Participantes Convocados ($confirmedCount/${confirmations.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$confirmedCount confirmados",
                        color = Color(0xFF34D399),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    confirmations.forEach { participant ->
                        ParticipantAvatar(
                            name = participant.displayName.split(" ").firstOrNull() ?: participant.displayName,
                            isLead = participant.attendanceStatus == AttendanceStatus.CONFIRMED
                        )
                    }
                }
            }
        }

        // Participantes reales de la rodada publicada, también para miembros sin copia local del mapa.
        rideRoster?.let { roster ->
            HeritageBevelCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                RideParticipantsScreen(
                    roster = roster,
                    currentUserId = currentUser.id,
                    currentUserIsLeader = roster.participants.firstOrNull { it.userId == currentUser.id }?.isLeader == true,
                    pendingCount = rsvpPendingCount,
                    isStarting = isStartingRide,
                    errorMessage = rideRosterError,
                    onRsvp = { routeId, _, status -> phase1ViewModel.requestRsvp(routeId, status) },
                    onArrive = { routeId, userId -> phase1ViewModel.requestOnSite(routeId, userId) },
                    onStart = { routeId ->
                        if (roster.outboundStartedAt != null) {
                            if (roster.returnStartedAt != null) {
                                com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(true)
                            }
                            onStartLiveMapPreview()
                            startRideServiceNow(useSimulated = forceSimulated)
                        } else {
                            phase1ViewModel.startRide(routeId) {
                                onStartLiveMapPreview()
                                startRideServiceNow(useSimulated = forceSimulated)
                            }
                        }
                    },
                    onArriveDestination = { routeId ->
                        phase1ViewModel.arriveDestination(routeId)
                    },
                    onStartReturn = { routeId ->
                        if (roster.returnStartedAt != null) {
                            com.rutabikersync.app.feature.routes.RideScheduleRepository.setReturnPhase(true)
                            onStartLiveMapPreview()
                            startRideServiceNow(useSimulated = forceSimulated)
                        } else {
                            phase1ViewModel.startReturn(routeId) {
                                onStartLiveMapPreview()
                                startRideServiceNow(useSimulated = forceSimulated)
                            }
                        }
                    }
                )
            }
        }

        // Tarjeta: Ruta y Corredor Vial (Biselada)
        HeritageBevelCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 230.dp)) {
                if (currentRide != null) {
                    Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(14.dp)) {
                        val isOfflineDownloaded = remember(currentRide) {
                            val routeId = currentRide?.selectedRoute?.id ?: "route-tovar-directa"
                            CorridorOfflineManager.isCorridorDownloaded(context, routeId) ||
                                CorridorOfflineManager.isRegionDownloaded(context, "caracas-junquito-tovar")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentRide!!.selectedRoute.name.ifBlank { stringResource(id = R.string.rides_route_title) },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isOfflineDownloaded) "✓ Offline Listo" else "En Línea",
                                color = if (isOfflineDownloaded) Color(0xFF10B981) else Color(0xFFE5B869),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    UxTestTracker.logEvent("RODADAS_HOME", "TAP_VIEW_MAP", "btn_ver_mapa")
                                    onStartLiveMapPreview()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFE5B869)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFC99A45))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NearMe,
                                    contentDescription = "Ver Mapa",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFFE5B869)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ver Mapa", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    UxTestTracker.logEvent("RODADAS_HOME", "TAP_PLAN_ROUTE", "btn_trazar_ruta")
                                    showScheduleMapPlanner = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF38BDF8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Trazar",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Trazar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    CardBackdropImage(imageRes = R.drawable.card_rodada_solitaria)
                    Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Rodada Individual / Solitaria",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF242732))
                                    .border(1.dp, Color(0xFF3E4455), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Modo Libre",
                                    color = Color(0xFF8E95A5),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Para usuarios simples y premium: crea y rueda en solitario sin registrar estadísticas de uso ni gamificación del club.",
                            color = Color(0xFF8E95A5),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    UxTestTracker.logEvent("RODADAS_HOME", "TAP_PLAN_ROUTE", "btn_crear_ruta_solitaria")
                                    showScheduleMapPlanner = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF38BDF8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8))
                            ) {
                                Text("🗺️ Ruta Libre", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            Button(
                                onClick = {
                                    UxTestTracker.logEvent("RODADAS_HOME", "TAP_START_RIDE", "btn_iniciar_recorrido")
                                    onStartLiveMapPreview()
                                    startRideServiceNow(useSimulated = forceSimulated)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFDF6E32),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.rides_btn_start_ride_preview),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Manejo de advertencias de permisos o errores
        (startErrorBroadcastMessage ?: rideState.lastStartError)?.let { errorMsg ->
            Text(
                text = "⚠ $errorMsg",
                color = Color(0xFFD92D35),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (showPermissionRationale) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E2026))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Para la mesh real y el mapa en convoy, se requieren permisos de Ubicación, Bluetooth y Notificaciones.",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        showPermissionRationale = false
                        permissionLauncher.launch(requiredRidePermissions.toTypedArray())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869))
                ) {
                    Text("Conceder permisos", color = Color(0xFF101114), fontWeight = FontWeight.Bold)
                }
            }
        }

        // La puerta real de inicio vive en el roster para líderes; miembros no líderes
        // conservan la activación de cabina/malla sin llamar a /v1/routes/:id/start.
        if (rideRoster == null || !canPublishClubRoute) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 156.dp)) {
                CardBackdropImage(imageRes = R.drawable.card_iniciar_cabina)
                HeritageBrassButton(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    text = stringResource(id = R.string.rides_btn_start_active_cockpit),
                    onClick = {
                        UxTestTracker.logEvent("RODADAS_HOME", "TAP_START_RIDE", "btn_iniciar_rodada")
                        onStartLiveMapPreview()
                        startRideServiceNow(useSimulated = forceSimulated)
                    },
                    icon = Icons.Default.PlayArrow
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Botón Rutas y Corredor sin señal
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E2129))
                .border(1.dp, Color(0xFFC99A45).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable {
                    showScheduleMapPlanner = true
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🗺️ Rutas y Corredor sin señal",
                color = Color(0xFFE5B869),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

/** Fondo decorativo de las tarjetas de rodadas; el texto se monta encima con velo oscuro. */
@Composable
private fun BoxScope.CardBackdropImage(imageRes: Int) {
    Image(
        painter = painterResource(id = imageRes),
        contentDescription = null,
        modifier = Modifier.matchParentSize(),
        contentScale = ContentScale.Crop,
        alpha = 0.88f
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x330F1115), Color(0xD90F1115))
                )
            )
    )
}

@Composable
private fun ParticipantAvatar(name: String, isLead: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF1F222A))
                .border(2.dp, if (isLead) Color(0xFF10B981) else Color(0xFF4B5563), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLead) Icons.Default.Shield else AppIcons.TabProfile,
                contentDescription = name,
                tint = if (isLead) Color(0xFF34D399) else Color(0xFFD1D5DB),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = name,
            color = if (isLead) Color(0xFF34D399) else Color(0xFF9CA3AF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
