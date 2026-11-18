package com.rutabikersync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modos de diseño disponibles en Ruta Biker Sync:
 * - [HERITAGE_DARK]: Estilo canónico "Dark Elite + Heritage" (Slate-950, Cobre, Oro y Esmeralda).
 * - [VINTAGE_HERITAGE]: Estilo auténtico biker clásico (fondos arena/crema ahumado, cuero, cobre y latón).
 * - [BRIGHT_RIDER]: Estilo deportivo diurno de alto contraste.
 * - [URBAN_FRESH]: Estilo fresco urbano.
 */
enum class AppThemeMode {
    HERITAGE_DARK,
    VINTAGE_HERITAGE,
    BRIGHT_RIDER,
    URBAN_FRESH
}

@Immutable
data class HeritageColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val borderSubtle: Color,
    val borderProminent: Color,
    val danger: Color,
    val safe: Color,
    val warning: Color,
    val isDark: Boolean,
    val isUrbanFresh: Boolean = false,
    val isBrightRider: Boolean = false,
    val honorGold: Color = HeritageBrassGold
)

/**
 * Paleta canónica Dark Elite + Heritage (Oficial Ruta Biker Sync).
 */
val DarkEliteHeritageColors = HeritageColors(
    background = DarkSlate950,
    surface = DarkCardBg,
    surfaceElevated = DarkCardElevated,
    primary = HeritageCopper,
    onPrimary = Color.White,
    secondary = HeritageBrassGold,
    textPrimary = TextWhite,
    textSecondary = TextMuted,
    textTertiary = TextTertiary,
    borderSubtle = DarkBorderSubtle,
    borderProminent = HeritageCopper,
    danger = RescueRed,
    safe = EmeraldMesh,
    warning = WarningAmber,
    isDark = true,
    isUrbanFresh = false,
    isBrightRider = false,
    honorGold = HeritageBrassGold
)

/**
 * Paleta Vintage opcional (conservada para vistas históricas o modo pergamino).
 */
val VintageHeritageColors = HeritageColors(
    background = Color(0xFFEFE9DF),
    surface = Color(0xFFFAF7F2),
    surfaceElevated = Color(0xFFEADBC8),
    primary = HeritageCopper,
    onPrimary = Color.White,
    secondary = HeritageBrassGold,
    textPrimary = Color(0xFF3E2723),
    textSecondary = Color(0xFF6D4C41),
    textTertiary = Color(0xFF8D7B68),
    borderSubtle = Color(0xFFD7C4B2),
    borderProminent = HeritageCopper,
    danger = RescueRed,
    safe = EmeraldMesh,
    warning = WarningAmber,
    isDark = false,
    isUrbanFresh = false,
    isBrightRider = false,
    honorGold = HeritageBrassGold
)

val DarkHeritageColors = DarkEliteHeritageColors

val LocalHeritageColors = staticCompositionLocalOf { DarkEliteHeritageColors }
val LocalAppThemeMode = staticCompositionLocalOf { AppThemeMode.HERITAGE_DARK }

object HeritageTheme {
    val colors: HeritageColors
        @Composable
        get() = LocalHeritageColors.current

    val isBrightRider: Boolean
        @Composable
        get() = colors.isBrightRider

    val isUrbanFresh: Boolean
        @Composable
        get() = colors.isUrbanFresh

    val isDark: Boolean
        @Composable
        get() = colors.isDark

    val dimens: BikerDimens
        get() = BikerDimens
}

/**
 * Compatibilidad retroactiva con tokens existentes.
 */
object BikerColors {
    val BackgroundDark: Color get() = DarkSlate950
    val SurfaceDark: Color get() = DarkCardBg
    val SkeletonBaseDark: Color get() = DarkCardElevated
    val SkeletonHighlightDark: Color get() = DarkBorderSubtle
    val PrimaryAmber: Color get() = HeritageCopper
    val StatusGreen: Color get() = EmeraldMesh
    val StatusYellow: Color get() = HeritageBrassGold
    val StatusRed: Color get() = RescueRed
    val TextPrimary: Color get() = TextWhite
    val TextSecondary: Color get() = TextMuted
}

object BikerDimens {
    val GlovesMinTouchTarget: Dp = 72.dp
    val GlovesIconSize: Dp = 40.dp
    val NormalIconSize: Dp = 24.dp
    val AvatarListSize: Dp = 48.dp
    val CardCornerRadius: Dp = 16.dp
    val ButtonCornerRadius: Dp = 12.dp
}

private val DarkEliteColorScheme: ColorScheme = darkColorScheme(
    primary = HeritageCopper,
    onPrimary = Color.White,
    primaryContainer = DarkCardElevated,
    onPrimaryContainer = TextWhite,
    secondary = HeritageBrassGold,
    onSecondary = DarkSlate950,
    secondaryContainer = DarkBorderProminent,
    onSecondaryContainer = TextWhite,
    background = DarkSlate950,
    onBackground = TextWhite,
    surface = DarkCardBg,
    onSurface = TextWhite,
    surfaceVariant = DarkCardElevated,
    onSurfaceVariant = TextMuted,
    outline = DarkBorderSubtle,
    outlineVariant = DarkBorderProminent,
    error = RescueRed,
    onError = Color.White
)

private val VintageColorScheme: ColorScheme = lightColorScheme(
    primary = HeritageCopper,
    onPrimary = Color.White,
    secondary = HeritageBrassGold,
    onSecondary = Color(0xFF3E2723),
    background = Color(0xFFEFE9DF),
    onBackground = Color(0xFF3E2723),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF3E2723),
    surfaceVariant = Color(0xFFEADBC8),
    onSurfaceVariant = Color(0xFF6D4C41),
    outline = Color(0xFFD7C4B2),
    error = RescueRed,
    onError = Color.White
)

@Composable
fun RutaBikerTheme(
    content: @Composable () -> Unit
) {
    RutaBikerSyncTheme(
        themeMode = AppThemeMode.HERITAGE_DARK,
        darkTheme = true,
        content = content
    )
}

@Composable
fun RutaBikerSyncTheme(
    themeMode: AppThemeMode = AppThemeMode.HERITAGE_DARK,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val effectiveColors = if (themeMode == AppThemeMode.VINTAGE_HERITAGE && !darkTheme) {
        VintageHeritageColors
    } else {
        DarkEliteHeritageColors
    }

    val materialColors = if (themeMode == AppThemeMode.VINTAGE_HERITAGE && !darkTheme) {
        VintageColorScheme
    } else {
        DarkEliteColorScheme
    }

    CompositionLocalProvider(
        LocalHeritageColors provides effectiveColors,
        LocalAppThemeMode provides themeMode
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = BikerTypography,
            content = content
        )
    }
}
