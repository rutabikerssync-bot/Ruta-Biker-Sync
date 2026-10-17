package com.rutabikersync.app.ui.components.skeleton

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tokens de diseño para el sistema de Skeleton Screens (SKELETONS.md §3).
 * TODO(marca): ajustar con la paleta final de la marca y comprobar contraste bajo el sol.
 */
object SkeletonTokens {
    val baseDark = Color(0xFF2A2D33)
    val highlightDark = Color(0xFF3A3E46)
    val baseLight = Color(0xFFE3E5E8)
    val highlightLight = Color(0xFFF2F3F5)

    // Oscuro por defecto (SKELETONS.md §2 principio 6)
    val base: Color = baseDark
    val highlight: Color = highlightDark

    val radiusSm: Dp = 4.dp
    val radiusMd: Dp = 12.dp

    const val shimmerDurationMs: Int = 1200
    const val shimmerAngleDeg: Float = 20f

    val lineHeightBody: Dp = 14.dp
    val lineHeightTitle: Dp = 20.dp
    val lineGap: Dp = 8.dp
}
