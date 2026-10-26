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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.LocalContext
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

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
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
    initialStartRidePreview: Boolean = false
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        UxTestTracker.init(context)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
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
                    containerColor = HeritageTheme.colors.primary,
                    contentColor = HeritageTheme.colors.onPrimary
                ) {
                    Text(
                        text = stringResource(id = R.string.dev_panel_btn),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
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
                        onStartLiveMapPreview = { showLiveMapPreview = true }
                    )
                    1 -> GroupsMainTabScreen(viewModel = phase1ViewModel)
                    2 -> VaultTabScreen()
                    else -> ProfileAndAuthTabScreen(viewModel = phase1ViewModel)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabecera oficial Bóveda (Screen 4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E2026))
                    .border(1.2.dp, Color(0xFFC99A45), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.LeaderFallback,
                    contentDescription = stringResource(id = R.string.vault_title),
                    tint = Color(0xFFE5B869),
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.vault_title),
                    color = Color.White,
                    fontSize = 18.sp,
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.vault_docs_title),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = R.string.vault_view_all),
                    color = Color(0xFFC99A45),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Grid 2x2 de documentos (Empty state: Documento no cargado)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultDocCard(
                    title = stringResource(id = R.string.vault_doc_license),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    isWarning = false,
                    modifier = Modifier.weight(1f)
                )
                VaultDocCard(
                    title = stringResource(id = R.string.vault_doc_insurance),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    isWarning = false,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultDocCard(
                    title = stringResource(id = R.string.vault_doc_circulation),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    isWarning = false,
                    modifier = Modifier.weight(1f)
                )
                VaultDocCard(
                    title = stringResource(id = R.string.vault_doc_inspection),
                    badge = stringResource(id = R.string.vault_doc_not_loaded),
                    isWarning = false,
                    modifier = Modifier.weight(1f)
                )
            }

            // Botón + Agregar documento
            Button(
                onClick = { showAddDocDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.vault_btn_add_document),
                    color = Color(0xFF101114),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // Sección: Ficha Médica de Emergencia (Sellado con Shamir)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF181A20))
                .border(1.dp, Color(0xFF2C2E38), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFFE5B869),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.vault_medical_title),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = "🔒 ${stringResource(id = R.string.vault_medical_sealed_shamir)}",
                    color = Color(0xFF10B981),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_blood_type), color = Color(0xFF9CA3AF), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_blood_type_empty), color = Color(0xFF9CA3AF), fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_allergies), color = Color(0xFF9CA3AF), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_allergies_empty), color = Color(0xFF9CA3AF), fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_donor), color = Color(0xFF9CA3AF), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_donor_empty), color = Color(0xFF9CA3AF), fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(id = R.string.vault_emergency_contact), color = Color(0xFF9CA3AF), fontSize = 12.sp)
                Text(stringResource(id = R.string.vault_emergency_contact_empty), color = Color(0xFF9CA3AF), fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
        }
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
private fun VaultDocCard(
    title: String,
    badge: String,
    isWarning: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181A20))
            .border(1.dp, Color(0xFF2C2E38), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 14.sp
        )
        Text(
            text = badge,
            color = if (isWarning) Color(0xFFE5B869) else Color(0xFF10B981),
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RidesReleaseHomeScreen(
    onStartLiveMapPreview: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val rideState by RideService.sharedState.collectAsState()
    var forceSimulated by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showSimulatedFallbackPrompt by remember { mutableStateOf(false) }
    var permanentlyDeniedDetected by remember { mutableStateOf(false) }
    var startErrorBroadcastMessage by remember { mutableStateOf<String?>(null) }

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
        RideService.dispatchStartRide(
            context = context,
            rideId = "ride-live-01",
            localShortId = 1,
            forceSimulated = useSimulated
        )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabecera RODADAS (Screen 2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2026))
                        .border(1.2.dp, Color(0xFFC99A45), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Club",
                        tint = Color(0xFFE5B869),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "RODADAS",
                        color = Color.White,
                        fontSize = 18.sp,
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

            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar",
                tint = Color(0xFFE5B869),
                modifier = Modifier.size(24.dp)
            )
        }

        // Tarjeta: Próxima Rodada
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF181A20))
                .border(1.dp, Color(0xFF2C2E38), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Banner de paisaje montañoso
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFF2B3A42), Color(0xFF1A1F24), Color(0xFF38464E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width * 0.28f, size.height * 0.3f)
                        lineTo(size.width * 0.55f, size.height * 0.65f)
                        lineTo(size.width * 0.82f, size.height * 0.22f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(p, Color(0xFF14181B).copy(alpha = 0.85f))
                }
            }

            if (BuildConfig.UX_TEST_MODE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.rides_next_ride),
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
                            text = stringResource(id = R.string.rides_no_scheduled_desc),
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2C2E38))
                            .border(1.dp, Color(0xFF4B5563), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Sin programar",
                            color = Color(0xFF9CA3AF),
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
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(id = R.string.rides_next_ride),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Sáb, 26 Abr 2025 · 07:30 AM",
                            color = Color(0xFFE5B869),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Valle de la Virgen → Mérida",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "312 km · 5 h 45 min",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp
                        )
                    }

                    // Insignia Confirmada
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Confirmada",
                            color = Color(0xFF34D399),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Tarjeta: Participantes
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF181A20))
                .border(1.dp, Color(0xFF2C2E38), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (BuildConfig.UX_TEST_MODE) {
                Text(
                    text = stringResource(id = R.string.rides_participants_empty),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = stringResource(id = R.string.rides_participants_empty_desc),
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "Participantes (12)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ParticipantAvatar(name = "Capitán", isLead = true)
                    ParticipantAvatar(name = "Road Mar.", isLead = false)
                    ParticipantAvatar(name = "Scout", isLead = false)
                    ParticipantAvatar(name = "Sweep", isLead = false)
                    ParticipantAvatar(name = "Invitado", isLead = false)
                }
            }
        }

        // Tarjeta: Ruta y Corredor
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF181A20))
                .border(1.dp, Color(0xFF2C2E38), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.rides_route_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (BuildConfig.UX_TEST_MODE) stringResource(id = R.string.rides_route_empty) else "✓ Descargado",
                    color = if (BuildConfig.UX_TEST_MODE) Color(0xFFE5B869) else Color(0xFF10B981),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            // Preview del mapa con botón "Ver mapa"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF121418))
                    .border(1.dp, Color(0xFF2E313C), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.BottomEnd
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.8f)
                        cubicTo(
                            size.width * 0.35f, size.height * 0.7f,
                            size.width * 0.6f, size.height * 0.2f,
                            size.width * 0.9f, size.height * 0.4f
                        )
                    }
                    drawPath(
                        p,
                        Color(0xFF38BDF8),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.5f)
                    )
                }

                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF181A20).copy(alpha = 0.92f))
                        .border(1.dp, Color(0xFFC99A45), RoundedCornerShape(8.dp))
                        .clickable {
                            UxTestTracker.logEvent("RODADAS_HOME", "TAP_VIEW_MAP", "btn_ver_mapa")
                            onStartLiveMapPreview()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = stringResource(id = R.string.rides_btn_view_map),
                        tint = Color(0xFFE5B869),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.rides_btn_view_map),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
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

        // Botón principal de latón envejecido: "Iniciar Rodada (Cabina Activa)"
        Button(
            onClick = {
                UxTestTracker.logEvent("RODADAS_HOME", "TAP_START_RIDE", "btn_iniciar_rodada")
                onStartLiveMapPreview()
                if (forceSimulated) {
                    startRideServiceNow(useSimulated = true)
                } else if (RideService.isMeshViable(context)) {
                    startRideServiceNow(useSimulated = false)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B869)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = stringResource(id = R.string.rides_btn_start_active_cockpit),
                color = Color(0xFF101114),
                fontWeight = FontWeight.Black,
                fontSize = 14.sp
            )
        }
    }
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
                .border(2.dp, if (isLead) Color(0xFFE5B869) else Color(0xFF4B5563), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLead) Icons.Default.Shield else AppIcons.TabProfile,
                contentDescription = name,
                tint = if (isLead) Color(0xFFE5B869) else Color(0xFFD1D5DB),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = name,
            color = if (isLead) Color(0xFFE5B869) else Color(0xFF9CA3AF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
