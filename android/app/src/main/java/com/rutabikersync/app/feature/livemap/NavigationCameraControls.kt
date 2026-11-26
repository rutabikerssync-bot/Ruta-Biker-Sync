package com.rutabikersync.app.feature.livemap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.DarkCardElevated
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown

/**
 * Control compacto de re-centrado de cámara bajo la brújula N.
 * Se define fuera de `LiveMapScreen` para no incrementar el método de ART.
 */
@Composable
fun NavigationCameraControls(
    navCameraMode: NavCameraMode,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavCameraChip(
        icon = Icons.Default.MyLocation,
        label = "Re-centrar",
        active = navCameraMode == NavCameraMode.FOLLOW_HEADING_3D,
        onClick = onRecenter,
        modifier = modifier
    )
}

@Composable
private fun NavCameraChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (active) HeritageCopper.copy(alpha = 0.85f) else DarkCardElevated.copy(alpha = 0.78f)
            )
            .border(
                1.2.dp,
                HeritageCopper.copy(alpha = if (active) 1f else 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Color.White else HeritageCopper,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = if (active) Color.White else HeritageLeatherBrown,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            lineHeight = 11.sp
        )
    }
}
