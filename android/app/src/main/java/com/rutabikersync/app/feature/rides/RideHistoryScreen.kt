package com.rutabikersync.app.feature.rides

import androidx.compose.runtime.Composable

/**
 * Delegado de compatibilidad para RideHistoryScreen en el paquete feature.rides.
 * Redirige a la implementación oficial enriquecida en feature.routes.
 */
@Composable
fun RideHistoryScreen(
    onDismiss: () -> Unit = {},
    onNavigateBack: () -> Unit = onDismiss,
    onShareRide: (Any?) -> Unit = {}
) {
    com.rutabikersync.app.feature.routes.RideHistoryScreen(
        onDismiss = onDismiss,
        onNavigateBack = onNavigateBack
    )
}
