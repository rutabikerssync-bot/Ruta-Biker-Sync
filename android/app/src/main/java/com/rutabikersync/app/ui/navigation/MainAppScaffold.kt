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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

/**
 * Navegación principal con barra inferior (SCREENS.md §2: Rodadas · Grupo · Rutas · Perfil)
 * y botón flotante DEV exclusivo de compilaciones Debug (BuildConfig.DEV_PANEL_ENABLED).
 */
@Composable
fun MainAppScaffold(
    phase1ViewModel: Phase1ViewModel = viewModel()
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        UxTestTracker.init(context)
    }

    var selectedTab by remember { mutableIntStateOf(if (BuildConfig.FIELD_TEST_MODE) 0 else 1) }
    var showDevPanel by remember { mutableStateOf(false) }
    var showModeratorDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val uxParticipant by UxTestTracker.participantId.collectAsState()

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
        containerColor = BikerColors.BackgroundDark,
        floatingActionButton = {
            if (BuildConfig.DEV_PANEL_ENABLED) {
                FloatingActionButton(
                    onClick = {
                        UxTestTracker.logEvent("DEV_PANEL", "OPEN_PANEL", "btn_dev_panel")
                        showDevPanel = true
                    },
                    containerColor = BikerColors.PrimaryAmber,
                    contentColor = BikerColors.BackgroundDark
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
            NavigationBar(
                containerColor = BikerColors.SurfaceDark,
                contentColor = BikerColors.TextPrimary
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(AppIcons.TabRides, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.nav_rides)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(AppIcons.TabGroups, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.nav_groups)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(AppIcons.TabRoutes, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.nav_routes)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(AppIcons.TabProfile, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.nav_profile)) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    RidesReleaseHomeScreen()
                }
                1 -> GroupsMainTabScreen(viewModel = phase1ViewModel)
                2 -> RoutesPlaceholderScreen()
                else -> ProfileAndAuthTabScreen(viewModel = phase1ViewModel)
            }

            if (BuildConfig.UX_TEST_MODE) {
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
private fun RoutesPlaceholderScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Rutas y Navegación Offline",
            color = BikerColors.PrimaryAmber,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Próximamente: Planificador de rutas para motociclistas y mapas descargables para navegación sin conexión celular.",
                color = BikerColors.TextPrimary,
                fontSize = 14.sp
            )
            Text(
                text = "© OpenStreetMap contributors",
                color = BikerColors.TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RidesReleaseHomeScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val rideState by RideService.sharedState.collectAsState()
    var forceSimulated by remember { mutableStateOf(false) }
    var showLiveMap by remember { mutableStateOf(!BuildConfig.FIELD_TEST_MODE) }
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

    if (BuildConfig.FIELD_TEST_MODE && !showLiveMap) {
        FieldTestHost(onOpenLiveMap = { showLiveMap = true })
        return
    }

    if (showLiveMap) {
        Box(modifier = Modifier.fillMaxSize()) {
            LiveMapScreen()
            if (BuildConfig.FIELD_TEST_MODE) {
                Button(
                    onClick = { showLiveMap = false },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                ) {
                    Text(
                        text = "← Panel Métricas",
                        color = BikerColors.BackgroundDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Rodada Activa y Conexión de Grupo",
            color = BikerColors.PrimaryAmber,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = rideState.notification.title,
                color = BikerColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = rideState.notification.contentText,
                color = BikerColors.TextSecondary,
                fontSize = 14.sp
            )

            // Justificación explícita de permisos (SCREENS.md A5)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Permisos de rodada activa:",
                    color = BikerColors.PrimaryAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Ubicación: para el mapa compartido y la mesh durante la rodada.",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp
                )
                Text(
                    text = "• Bluetooth: para conectar con los otros riders.",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp
                )
                Text(
                    text = "• Notificaciones: para el aviso persistente de 'Rodada activa'.",
                    color = BikerColors.TextPrimary,
                    fontSize = 12.sp
                )
            }

            // Aviso si POST_NOTIFICATIONS está rechazado en Android 13+ (API 33+)
            if (!RideService.isPostNotificationsGranted(context)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.StatusYellow.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "⚠ Permiso de notificaciones (POST_NOTIFICATIONS) rechazado: el servicio sigue corriendo pero la notificación no se muestra.",
                        color = BikerColors.StatusYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            (startErrorBroadcastMessage ?: rideState.lastStartError)?.let { errorMsg ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.StatusRed.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚠ $errorMsg",
                        color = BikerColors.StatusRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (showPermissionRationale) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Antes de continuar, concede los permisos de Ubicación, Bluetooth y Notificaciones para activar la mesh real.",
                        color = BikerColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = {
                            UxTestTracker.logEvent("PERMISSIONS", "REQUEST_START", "btn_solicitar_permisos")
                            showPermissionRationale = false
                            permissionLauncher.launch(requiredRidePermissions.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                    ) {
                        Text(
                            text = "Continuar y solicitar permisos",
                            color = BikerColors.BackgroundDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showSimulatedFallbackPrompt) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BikerColors.BackgroundDark, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Sin Bluetooth/Ubicación no hay mesh real. ¿Quieres probar en modo simulado?",
                        color = BikerColors.StatusYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                UxTestTracker.logEvent("PERMISSIONS", "FALLBACK_SIMULATED", "btn_probar_simulado")
                                forceSimulated = true
                                showSimulatedFallbackPrompt = false
                                startRideServiceNow(useSimulated = true)
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

            if (BuildConfig.DEV_PANEL_ENABLED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Modo Dev: forzar SimulatedBleTransport",
                        color = BikerColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Switch(
                        checked = forceSimulated,
                        onCheckedChange = { forceSimulated = it }
                    )
                }
            }

            if (rideState.lifecycleState == RideLifecycleState.IDLE ||
                rideState.lifecycleState == RideLifecycleState.STOPPED
            ) {
                Button(
                    onClick = {
                        UxTestTracker.logEvent("RODADAS_HOME", "TAP_START_RIDE", "btn_iniciar_rodada")
                        if (forceSimulated) {
                            startRideServiceNow(useSimulated = true)
                        } else if (RideService.isMeshViable(context)) {
                            startRideServiceNow(useSimulated = false)
                        } else {
                            showPermissionRationale = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BikerColors.PrimaryAmber)
                ) {
                    Text(
                        text = "Iniciar rodada (BLE Real por defecto)",
                        color = BikerColors.BackgroundDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            UxTestTracker.logEvent("RODADAS_HOME", "TAP_STOP_RIDE", "btn_detener_rodada")
                            RideService.dispatchStopRide(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                    ) {
                        Text(
                            text = if (rideState.lifecycleState == RideLifecycleState.CONFIRMING_STOP) {
                                "Confirmar cierre (3 s)"
                            } else {
                                "Detener rodada"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = {
                            val isUxMode = BuildConfig.UX_TEST_MODE
                            val meshBroadcastAllowed = isUxMode && UxTestTracker.isSosMeshBroadcastEnabled

                            if (!isUxMode || meshBroadcastAllowed) {
                                UxTestTracker.logEvent(
                                    screen = "RODADAS_HOME",
                                    action = "TAP_SOS",
                                    target = if (isUxMode) "btn_sos_prueba" else "btn_sos",
                                    details = "task=T9,broadcast=true"
                                )
                                val sosIntent = Intent(context, RideService::class.java).apply {
                                    action = RideService.ACTION_TRIGGER_SOS
                                }
                                context.startService(sosIntent)
                            } else {
                                UxTestTracker.logEvent(
                                    screen = "RODADAS_HOME",
                                    action = "TAP_SOS",
                                    target = "btn_sos_prueba",
                                    details = "task=T9,broadcast=false"
                                )
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusRed)
                    ) {
                        Text(
                            text = if (BuildConfig.UX_TEST_MODE) "SOS (PRUEBA)" else "SOS",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}
