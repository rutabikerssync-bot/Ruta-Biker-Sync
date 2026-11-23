package com.rutabikersync.app.feature.groups

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.BikerColors

/**
 * Disparador DEV de cierre formal de rodada (COMPLETE_RIDE).
 *
 * Solo se monta desde el panel de desarrollador, que ya exige
 * `BuildConfig.DEV_PANEL_ENABLED`. La transición pasa por el mismo outbox que
 * el flujo normal: no hay mutación optimista ni salto del backend.
 */
@Composable
fun RideLifecycleDevTrigger(viewModel: Phase1ViewModel) {
    val roster by viewModel.rideRoster.collectAsState()
    val error by viewModel.rideRosterError.collectAsState()
    val routeId = roster?.routeId

    Button(
        onClick = {
            if (routeId != null) viewModel.completeRide(routeId)
        },
        enabled = routeId != null && com.rutabikersync.app.BuildConfig.DEV_PANEL_ENABLED,
        colors = ButtonDefaults.buttonColors(
            containerColor = BikerColors.PrimaryAmber,
            contentColor = BikerColors.BackgroundDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (routeId == null) {
                "DEV: completar rodada (sin ruta activa)"
            } else {
                "DEV: completar rodada"
            },
            fontWeight = FontWeight.Bold
        )
    }
    if (!error.isNullOrBlank()) {
        Text(error!!, fontSize = 11.sp, color = BikerColors.TextSecondary)
    }
}
