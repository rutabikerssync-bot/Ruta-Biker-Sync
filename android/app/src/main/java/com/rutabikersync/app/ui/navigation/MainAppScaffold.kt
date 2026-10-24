package com.rutabikersync.app.ui.navigation

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.R
import com.rutabikersync.app.core.mesh.RideLifecycleState
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.feature.fieldtest.BleFieldTestScreen
import com.rutabikersync.app.feature.fieldtest.BleFieldTestViewModel
import com.rutabikersync.app.feature.groups.DevPanelDialog
import com.rutabikersync.app.feature.groups.GroupsMainTabScreen
import com.rutabikersync.app.feature.groups.Phase1ViewModel
import com.rutabikersync.app.feature.groups.ProfileAndAuthTabScreen
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors

/**
 * Navegación principal con barra inferior (SCREENS.md §2: Rodadas · Grupo · Rutas · Perfil)
 * y botón flotante DEV exclusivo de compilaciones Debug (BuildConfig.DEV_PANEL_ENABLED).
 */
@Composable
fun MainAppScaffold(
    phase1ViewModel: Phase1ViewModel = viewModel(),
    bleFieldTestViewModel: BleFieldTestViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(1) } // Inicia en Grupo (Fase 1)
    var showDevPanel by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BikerColors.BackgroundDark,
        floatingActionButton = {
            if (BuildConfig.DEV_PANEL_ENABLED) {
                FloatingActionButton(
                    onClick = { showDevPanel = true },
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
                    if (BuildConfig.DEV_PANEL_ENABLED) {
                        BleFieldTestScreen(viewModel = bleFieldTestViewModel)
                    } else {
                        RidesReleaseHomeScreen()
                    }
                }
                1 -> GroupsMainTabScreen(viewModel = phase1ViewModel)
                2 -> RoutesPlaceholderScreen()
                else -> ProfileAndAuthTabScreen(viewModel = phase1ViewModel)
            }
        }

        if (BuildConfig.DEV_PANEL_ENABLED && showDevPanel) {
            DevPanelDialog(
                viewModel = phase1ViewModel,
                onDismiss = { showDevPanel = false }
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
            text = "E1–E4 · Rutas Offline y Planificador BRouter",
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
                text = "Programado para Fase 3 (Mapa en vivo, BRouter offline y descargas PMTiles/MBTiles ±10 km).",
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
    val rideState by RideService.sharedState.collectAsState()
    var forceSimulated by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "B1 · Rodada Activa y Mesh BLE (Fase 3)",
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
                        val intent = Intent(context, RideService::class.java).apply {
                            action = RideService.ACTION_START_RIDE
                            putExtra(RideService.EXTRA_RIDE_ID, "ride-live-01")
                            putExtra(RideService.EXTRA_LOCAL_SHORT_ID, 1)
                            putExtra(RideService.EXTRA_FORCE_SIMULATED, forceSimulated)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
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
                            val stopIntent = Intent(context, RideService::class.java).apply {
                                action = RideService.ACTION_STOP_RIDE
                            }
                            context.startService(stopIntent)
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
                            val sosIntent = Intent(context, RideService::class.java).apply {
                                action = RideService.ACTION_TRIGGER_SOS
                            }
                            context.startService(sosIntent)
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BikerColors.StatusRed)
                    ) {
                        Text(text = "SOS", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
