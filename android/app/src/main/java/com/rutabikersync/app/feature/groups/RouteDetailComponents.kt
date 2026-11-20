package com.rutabikersync.app.feature.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.core.clubroutes.ClubRouteEntity
import com.rutabikersync.app.ui.theme.BikerColors

@Composable
fun NextRideCard(
    route: ClubRouteEntity,
    rsvpStatus: String?,
    onClick: () -> Unit
) {
    val statusText = if (ClubRsvpUi.isAnswered(rsvpStatus)) {
        ClubRsvpUi.statusLabel(rsvpStatus!!)
    } else {
        "Sin respuesta"
    }
    val snippet = ClubRsvpUi.descriptionSnippet(route.description)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF241C15), RoundedCornerShape(12.dp))
            .border(1.dp, BikerColors.PrimaryAmber, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "PRÓXIMA RODADA",
            color = BikerColors.PrimaryAmber,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = route.title,
            color = Color(0xFFF5E6C8),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Salida: ${ClubRsvpUi.formatRouteDateTime(route.startsAt, full = false)}",
            color = BikerColors.TextSecondary,
            fontSize = 13.sp
        )
        if (snippet != null) {
            Text(
                text = snippet,
                color = Color(0xFFEADFC8),
                fontSize = 13.sp
            )
        }
        Text(
            text = "Tu respuesta: $statusText",
            color = if (ClubRsvpUi.isAnswered(rsvpStatus)) BikerColors.PrimaryAmber else BikerColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailBottomSheet(
    route: ClubRouteEntity,
    rsvpStatus: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scrollState = rememberScrollState()
    val statusText = if (ClubRsvpUi.isAnswered(rsvpStatus)) {
        ClubRsvpUi.statusLabel(rsvpStatus!!)
    } else {
        "Sin respuesta"
    }
    val description = route.description?.trim().takeUnless { it.isNullOrEmpty() } ?: "Sin descripción/concepto"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BikerColors.SurfaceDark,
        contentColor = Color(0xFFF5E6C8)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = route.title,
                color = Color(0xFFF5E6C8),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Salida: ${ClubRsvpUi.formatRouteDateTime(route.startsAt, full = true)}",
                color = BikerColors.PrimaryAmber,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Estado: ${route.status}",
                color = BikerColors.TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = "Descripción / concepto",
                color = BikerColors.PrimaryAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = Color(0xFFEADFC8),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Text(
                text = "Tu respuesta: $statusText",
                color = if (ClubRsvpUi.isAnswered(rsvpStatus)) BikerColors.PrimaryAmber else BikerColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
