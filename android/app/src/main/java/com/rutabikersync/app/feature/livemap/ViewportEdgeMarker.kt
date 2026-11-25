package com.rutabikersync.app.feature.livemap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.atan2
import kotlin.math.roundToInt

data class EdgeMarkerPlacement(
    val xPx: Float,
    val yPx: Float,
    val directionDegrees: Float,
    val isOutsideViewport: Boolean
)

/**
 * Calcula la posición en el borde del viewport y el ángulo de dirección hacia el rider
 * cuando su proyección cae fuera de pantalla.
 */
object ViewportEdgeProjector {
    private const val DEFAULT_EDGE_PADDING_PX = 56f

    fun project(
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
        edgePaddingPx: Float = DEFAULT_EDGE_PADDING_PX
    ): EdgeMarkerPlacement? {
        if (!xPx.isFinite() || !yPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return null
        if (RiderViewportLayout.isPointVisible(xPx, yPx, widthPx, heightPx)) {
            return EdgeMarkerPlacement(
                xPx = xPx,
                yPx = yPx,
                directionDegrees = 0f,
                isOutsideViewport = false
            )
        }

        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val dx = xPx - cx
        val dy = yPx - cy

        val padX = edgePaddingPx.coerceAtMost(widthPx / 2f - 1f).coerceAtLeast(0f)
        val padY = edgePaddingPx.coerceAtMost(heightPx / 2f - 1f).coerceAtLeast(0f)
        val maxHalfW = (cx - padX).coerceAtLeast(1f)
        val maxHalfH = (cy - padY).coerceAtLeast(1f)

        val scaleX = if (dx != 0f) kotlin.math.abs(maxHalfW / dx) else Float.POSITIVE_INFINITY
        val scaleY = if (dy != 0f) kotlin.math.abs(maxHalfH / dy) else Float.POSITIVE_INFINITY
        val scale = minOf(scaleX, scaleY).coerceAtMost(1f)

        val clampedX = (cx + dx * scale).coerceIn(padX, widthPx - padX)
        val clampedY = (cy + dy * scale).coerceIn(padY, heightPx - padY)

        // 0° = arriba (Norte de pantalla), 90° = derecha, 180° = abajo, 270° = izquierda
        val angleRad = atan2(dx.toDouble(), -dy.toDouble())
        val angleDeg = (((Math.toDegrees(angleRad) % 360.0) + 360.0) % 360.0).toFloat()

        return EdgeMarkerPlacement(
            xPx = clampedX,
            yPx = clampedY,
            directionDegrees = angleDeg,
            isOutsideViewport = true
        )
    }
}

@Composable
fun ViewportEdgeMarker(
    marker: RiderMarkerUiModel,
    placement: EdgeMarkerPlacement,
    onClick: () -> Unit
) {
    val accent = Color(marker.markerColorHex)
    val label = marker.nickname?.takeIf { it.isNotBlank() }
        ?: marker.fullName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "#${marker.shortId.toInt()}"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (placement.xPx - 28f).roundToInt(),
                    y = (placement.yPx - 28f).roundToInt()
                )
            }
            .zIndex(3f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE60F172A))
            .border(1.5.dp, accent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Navigation,
            contentDescription = "Dirección hacia $label",
            tint = accent,
            modifier = Modifier
                .size(16.dp)
                .rotate(placement.directionDegrees)
        )
        Text(
            text = label.take(8),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
