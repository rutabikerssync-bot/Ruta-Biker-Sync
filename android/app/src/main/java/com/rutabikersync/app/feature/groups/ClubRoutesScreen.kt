package com.rutabikersync.app.feature.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import com.rutabikersync.app.ui.theme.BikerColors

/**
 * UI mínima de rutas y RSVP del club (Fase 5 · Hito 3).
 *
 * Pinta la caché Room primero: con o sin red el piloto ve las rutas publicadas
 * y su estado. Este espacio es solo informativo: la respuesta RSVP se registra
 * en PARTICIPANTES y las estadísticas en BITÁCORA.
 */
@Composable
fun ClubRoutesScreen(
    groupName: String,
    routes: List<ClubRouteEntity>,
    nextRoute: ClubRouteEntity?,
    rsvpByRoute: Map<String, String>,
    pendingCount: Int,
    onOpenParticipants: (routeId: String) -> Unit,
    onBack: () -> Unit
) {
    val pendingBanner = ClubRsvpUi.bannerMessage(null, pendingCount)
    var selectedRouteId by remember { mutableStateOf<String?>(null) }
    val selectedRoute = selectedRouteId?.let { id -> routes.firstOrNull { it.id == id } ?: if (nextRoute?.id == id) nextRoute else null }

    if (selectedRoute != null) {
        RouteDetailBottomSheet(
            route = selectedRoute,
            rsvpStatus = rsvpByRoute[selectedRoute.id],
            onDismiss = { selectedRouteId = null },
            onOpenParticipants = onOpenParticipants
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BikerColors.BackgroundDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RUTAS DEL CLUB",
                color = BikerColors.PrimaryAmber,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) {
                Text("Cerrar", color = BikerColors.PrimaryAmber)
            }
        }
        Text(groupName, color = BikerColors.TextSecondary, fontSize = 13.sp)

        if (pendingBanner != null) {
            Text(
                text = pendingBanner,
                color = Color(0xFFF5D061),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF241C15), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (nextRoute != null) {
                item(key = "next_${nextRoute.id}") {
                    NextRideCard(
                        route = nextRoute,
                        rsvpStatus = rsvpByRoute[nextRoute.id],
                        onClick = { selectedRouteId = nextRoute.id }
                    )
                }
            }

            if (routes.isEmpty()) {
                item {
                    Text(
                        text = "No hay rutas publicadas en este club.",
                        color = BikerColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                items(routes, key = { it.id }) { route ->
                    val status = ClubRsvpUi.resolveRouteStatus(null, rsvpByRoute[route.id])
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BikerColors.SurfaceDark, RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF8B6B3D), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRouteId = route.id }
                        ) {
                            Text(
                                text = route.title,
                                color = Color(0xFFF5E6C8),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = ClubRsvpUi.routeSubtitle(route.startsAt, status),
                                color = BikerColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
