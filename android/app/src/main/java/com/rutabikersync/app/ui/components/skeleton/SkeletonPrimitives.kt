package com.rutabikersync.app.ui.components.skeleton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Componentes primitivos de Skeleton Screens (SKELETONS.md §4).
 */
@Composable
fun SkeletonBox(
    width: Dp? = null,
    height: Dp,
    radius: Dp = SkeletonTokens.radiusMd,
    modifier: Modifier = Modifier
) {
    val sizedModifier = if (width != null) {
        modifier.width(width).height(height)
    } else {
        modifier.fillMaxWidth().height(height)
    }
    Box(
        modifier = sizedModifier
            .clip(RoundedCornerShape(radius))
            .shimmer()
    )
}

@Composable
fun SkeletonLine(
    widthFraction: Float = 0.8f,
    height: Dp = SkeletonTokens.lineHeightBody,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction.coerceIn(0.1f, 1f))
            .height(height)
            .clip(RoundedCornerShape(SkeletonTokens.radiusSm))
            .shimmer()
    )
}

@Composable
fun SkeletonCircle(
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .shimmer()
    )
}

@Composable
fun SkeletonChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(72.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .shimmer()
    )
}

@Composable
fun SkeletonBadgeGrid(
    cols: Int = 3,
    rows: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(cols) {
                    SkeletonCircle(size = 56.dp)
                }
            }
        }
    }
}
