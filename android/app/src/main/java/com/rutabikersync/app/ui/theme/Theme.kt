package com.rutabikersync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tokens de color y dimensiones de Ruta Biker Sync.
 * TODO(marca): Reemplazar paleta provisional cuando el dueño entregue el logo y manual de marca definitivos.
 * Tema oscuro por defecto de alto contraste legible bajo el sol (DESIGN.md §9 y SKELETONS.md §3).
 */
object BikerColors {
    val BackgroundDark = Color(0xFF0B0E14)
    val SurfaceDark = Color(0xFF151922)
    val SkeletonBaseDark = Color(0xFF2A2D33)
    val SkeletonHighlightDark = Color(0xFF3A3E46)
    val PrimaryAmber = Color(0xFFFFB300) // TODO(marca): acento provisional de alta visibilidad al sol
    val StatusGreen = Color(0xFF00E676)
    val StatusYellow = Color(0xFFFFEA00)
    val StatusRed = Color(0xFFFF1744)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFB0B8C4)
}

object BikerDimens {
    /** Regla innegociable 8 (DESIGN.md §1, ICONS.md §1): botones en rodada >= 72 dp para modo guantes. */
    val GlovesMinTouchTarget: Dp = 72.dp
    val GlovesIconSize: Dp = 40.dp
    val NormalIconSize: Dp = 24.dp
    val AvatarListSize: Dp = 48.dp
}

private val DarkColorScheme = darkColorScheme(
    primary = BikerColors.PrimaryAmber,
    onPrimary = Color.Black,
    background = BikerColors.BackgroundDark,
    onBackground = BikerColors.TextPrimary,
    surface = BikerColors.SurfaceDark,
    onSurface = BikerColors.TextPrimary,
    error = BikerColors.StatusRed,
    onError = Color.White
)

@Composable
fun RutaBikerSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
