package com.rutabikersync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.rutabikersync.app.core.mesh.BleMeshService
import com.rutabikersync.app.feature.auth.AuthState
import com.rutabikersync.app.feature.auth.AuthViewModel
import com.rutabikersync.app.feature.auth.ProfileSetupScreen
import com.rutabikersync.app.feature.auth.RoleSelectionScreen
import com.rutabikersync.app.feature.auth.WelcomeAuthScreen
import com.rutabikersync.app.ui.navigation.MainAppScaffold
import com.rutabikersync.app.ui.theme.RutaBikerSyncTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 2026
    }

    private var isVolumePttActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#080D12")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#111317"))
        )
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("rbs_theme_prefs", android.content.Context.MODE_PRIVATE)
        val startRidePreview = intent.getBooleanExtra("START_RIDE_PREVIEW", false)
        val startTacticalChat = intent.getBooleanExtra("START_TACTICAL_CHAT", false)
        val startMeshMonitor = intent.getBooleanExtra("START_MESH_MONITOR", false)
        val startRideHistory = intent.getBooleanExtra("START_RIDE_HISTORY", false)
        val loadDemoHistory = intent.getBooleanExtra("LOAD_DEMO_HISTORY", false)
        val startGamificationProfile = intent.getBooleanExtra("START_GAMIFICATION_PROFILE", false)
        val startProfileTab = intent.getBooleanExtra("START_PROFILE_TAB", false)
        val startClubTab = intent.getBooleanExtra("START_CLUB_TAB", false)
        val startValenciaRoute = intent.getBooleanExtra("START_VALENCIA_ROUTE", false)
        val focusKeyboard = intent.getBooleanExtra("FOCUS_KEYBOARD", false)

        if (loadDemoHistory) {
            com.rutabikersync.app.feature.routes.RideScheduleRepository.loadDemoHistory()
        }

        requestMeshPermissions()
        handleDeepLink(intent)
        com.rutabikersync.app.core.network.ClubApiClient.getInstance().initContext(applicationContext)
        com.rutabikersync.app.feature.groups.ClubRepository.getInstance().syncWithLocalProfile(applicationContext)
        com.rutabikersync.app.core.network.RutaBikerMessagingService.syncFcmToken(applicationContext)

        // Configuración segura de barras del sistema (Dark Elite: post-decorView para evitar NPE en HyperOS / Android 14)
        window.decorView.post {
            window.statusBarColor = android.graphics.Color.parseColor("#080D12")
            window.navigationBarColor = android.graphics.Color.parseColor("#111317")
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)?.apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        setContent {
            val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.provideFactory(application))
            val authState by authViewModel.authState.collectAsState()

            var isDarkMode by remember {
                mutableStateOf(prefs.getBoolean("dark_mode", true))
            }
            var showSplash by remember {
                mutableStateOf(!startRidePreview && !startTacticalChat && !startMeshMonitor && !startRideHistory && !startValenciaRoute)
            }
            RutaBikerSyncTheme(darkTheme = isDarkMode) {
                if (showSplash) {
                    com.rutabikersync.app.feature.splash.SplashScreen(
                        onNavigateToApp = { showSplash = false }
                    )
                } else {
                    when (val state = authState) {
                        is AuthState.NotAuthenticated -> {
                            WelcomeAuthScreen(
                                activity = this@MainActivity,
                                isDarkMode = isDarkMode,
                                onToggleDarkMode = {
                                    val newMode = !isDarkMode
                                    isDarkMode = newMode
                                    prefs.edit().putBoolean("dark_mode", newMode).apply()
                                },
                                onGoogleAuthSuccess = { email, displayName ->
                                    authViewModel.onGoogleLoginSuccess(
                                        uid = "google-${email.lowercase()}",
                                        email = email.lowercase(),
                                        displayName = displayName,
                                        photoUrl = null
                                    )
                                }
                            )
                        }
                        is AuthState.ProfileIncomplete -> {
                            ProfileSetupScreen(
                                email = state.email,
                                initialDisplayName = state.displayName,
                                onSaveProfile = { alias, moto, pin, ecName, ecPhone, blood ->
                                    authViewModel.saveProfile(
                                        aliasBiker = alias,
                                        motorcycleModel = moto,
                                        pinColorHex = pin,
                                        emergencyContactName = ecName,
                                        emergencyContactPhone = ecPhone,
                                        bloodType = blood
                                    )
                                },
                                onCancelToOtherAccount = {
                                    authViewModel.logout()
                                }
                            )
                        }
                        is AuthState.RoleNotChosen -> {
                            RoleSelectionScreen(
                                userName = state.profile.aliasBiker,
                                onRoleConfirmed = { role ->
                                    authViewModel.chooseRole(role)
                                },
                                userEmail = state.profile.email
                            )
                        }
                        is AuthState.Ready -> {
                            androidx.compose.runtime.LaunchedEffect(state.profile.uid) {
                                com.rutabikersync.app.feature.groups.ClubRepository
                                    .getInstance()
                                    .syncWithLocalProfile(applicationContext)
                                com.rutabikersync.app.core.network.ClubApiClient
                                    .getInstance()
                                    .ensureAuthenticatedUser()
                            }
                            MainAppScaffold(
                                isDarkMode = isDarkMode,
                                onToggleDarkMode = {
                                    val newMode = !isDarkMode
                                    isDarkMode = newMode
                                    prefs.edit().putBoolean("dark_mode", newMode).apply()
                                },
                                initialStartRidePreview = startRidePreview || startTacticalChat || startMeshMonitor,
                                initialStartTacticalChat = startTacticalChat,
                                initialStartMeshMonitor = startMeshMonitor,
                                initialStartRideHistory = startRideHistory,
                                initialFocusKeyboard = focusKeyboard,
                                initialStartGamificationProfile = startGamificationProfile,
                                initialSelectedTab = if (startProfileTab) 3 else if (startClubTab) 1 else 0,
                                onLogout = {
                                    authViewModel.logout()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        com.rutabikersync.app.core.network.RutaBikerMessagingService.syncFcmToken(applicationContext)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event?.repeatCount == 0 && !isVolumePttActive) {
                isVolumePttActive = true
                Log.i(TAG, "Hardware Key PTT presionado (keyCode=$keyCode) -> ACTION_PTT_START")
                sendPttAction(BleMeshService.ACTION_PTT_START)
                return true
            }
            if (isVolumePttActive) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (isVolumePttActive) {
                isVolumePttActive = false
                Log.i(TAG, "Hardware Key PTT liberado (keyCode=$keyCode) -> ACTION_PTT_STOP")
                sendPttAction(BleMeshService.ACTION_PTT_STOP)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "rbs" && uri.host == "ptt") {
            when (uri.getQueryParameter("action")) {
                "start" -> {
                    Log.i(TAG, "Deep Link rbs://ptt?action=start procesado")
                    sendPttAction(BleMeshService.ACTION_PTT_START)
                }
                "stop" -> {
                    Log.i(TAG, "Deep Link rbs://ptt?action=stop procesado")
                    sendPttAction(BleMeshService.ACTION_PTT_STOP)
                }
            }
        } else if (uri.scheme == "rbs" && uri.host == "debug") {
            if (uri.path == "/emit-multihop") {
                val ttl = uri.getQueryParameter("ttl")?.toIntOrNull() ?: 5
                Log.i(TAG, "Deep Link rbs://debug/emit-multihop procesado (ttl=$ttl)")
                val intentService = Intent(this, BleMeshService::class.java).apply {
                    action = BleMeshService.ACTION_EMIT_MULTIHOP
                    putExtra(BleMeshService.EXTRA_TTL, ttl)
                }
                try {
                    ContextCompat.startForegroundService(this, intentService)
                } catch (_: Exception) {
                    startService(intentService)
                }
            }
        }
    }

    private fun sendPttAction(action: String) {
        val intent = Intent(this, BleMeshService::class.java).apply {
            this.action = action
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Error iniciando servicio PTT vía foreground, fallback a startService: ${e.message}")
            try {
                startService(intent)
            } catch (ex: Exception) {
                val message = "No se pudo iniciar el PTT: ${ex.message ?: "error desconocido"}"
                Log.e(TAG, message)
                runCatching {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestMeshPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.i(TAG, "Solicitando permisos de radio y audio pendientes: $missing")
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
