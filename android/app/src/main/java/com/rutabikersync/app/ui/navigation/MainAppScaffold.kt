package com.rutabikersync.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutabikersync.app.BuildConfig
import com.rutabikersync.app.R
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "B1 · Mis Rodadas",
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
                text = "Aún no tienes rodadas activas. Ve a la pestaña Grupo para ver tus clubes.",
                color = BikerColors.TextPrimary,
                fontSize = 14.sp
            )
        }
    }
}
