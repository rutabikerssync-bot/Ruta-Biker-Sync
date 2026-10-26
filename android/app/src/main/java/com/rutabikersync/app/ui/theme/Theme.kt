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
 * Tokens oficiales de diseño "Heritage Premium" de Ruta Biker Sync.
 * Estética de hermandad motera: cuero negro, asfalto, latón envejecido, metal cepillado y marfil cálido.
 */

// --- Paleta Dark Heritage ---
val HeritageDarkAsphalt = Color(0xFF101114) // Fondo principal
val HeritageDarkLeather = Color(0xFF1B1C20) // Superficie / Paneles / Tarjetas
val HeritageDarkLeatherElevated = Color(0xFF23252A) // Superficie elevada
val HeritageDarkMetal = Color(0xFF85817A)   // Metal cepillado / Bordes / Secundario
val HeritageDarkBrass = Color(0xFFC99A45)   // Latón envejecido / Primario / Acentos de honor
val HeritageDarkIvory = Color(0xFFF3EBDD)   // Marfil cálido / Texto primario
val HeritageDarkInk = Color(0xFF24231F)
val HeritageDarkDanger = Color(0xFFD92D35)  // SOS / Peligro
val HeritageDarkSafe = Color(0xFF18865B)    // Seguro / Al día / Conectado
val HeritageDarkWarning = Color(0xFFD99B26) // Advertencia / En gracia

// --- Paleta Light Heritage ---
val HeritageLightIvory = Color(0xFFF3EBDD)   // Fondo principal marfil cálido
val HeritageLightSurface = Color(0xFFFFFCF5) // Superficie clara
val HeritageLightSurfaceElevated = Color(0xFFF8F2E4)
val HeritageLightInk = Color(0xFF24231F)     // Tinta oscura / Texto primario
val HeritageLightBrass = Color(0xFF93651F)   // Latón oscuro de alto contraste
val HeritageLightMetal = Color(0xFF716D65)   // Metal secundario
val HeritageLightDanger = Color(0xFFB91F2B)  // SOS claro
val HeritageLightSafe = Color(0xFF14704B)    // Seguro claro
val HeritageLightWarning = Color(0xFFB57D18) // Advertencia clara

@Immutable
data class HeritageColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val primary: Color,
    val onPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val borderSubtle: Color,
    val borderProminent: Color,
    val danger: Color,
    val safe: Color,
    val warning: Color,
    val isDark: Boolean
)

val DarkHeritageColors = HeritageColors(
    background = HeritageDarkAsphalt,
    surface = HeritageDarkLeather,
    surfaceElevated = HeritageDarkLeatherElevated,
    primary = HeritageDarkBrass,
    onPrimary = HeritageDarkAsphalt,
    textPrimary = HeritageDarkIvory,
    textSecondary = HeritageDarkMetal,
    textTertiary = HeritageDarkMetal.copy(alpha = 0.7f),
    borderSubtle = HeritageDarkMetal.copy(alpha = 0.22f),
    borderProminent = HeritageDarkBrass.copy(alpha = 0.45f),
    danger = HeritageDarkDanger,
    safe = HeritageDarkSafe,
    warning = HeritageDarkWarning,
    isDark = true
)

val LightHeritageColors = HeritageColors(
    background = HeritageLightIvory,
    surface = HeritageLightSurface,
    surfaceElevated = HeritageLightSurfaceElevated,
    primary = HeritageLightBrass,
    onPrimary = Color.White,
    textPrimary = HeritageLightInk,
    textSecondary = HeritageLightMetal,
    textTertiary = HeritageLightMetal.copy(alpha = 0.75f),
    borderSubtle = HeritageLightMetal.copy(alpha = 0.3f),
    borderProminent = HeritageLightBrass.copy(alpha = 0.6f),
    danger = HeritageLightDanger,
    safe = HeritageLightSafe,
    warning = HeritageLightWarning,
    isDark = false
)

val LocalHeritageColors = staticCompositionLocalOf { DarkHeritageColors }

object HeritageTheme {
    val colors: HeritageColors
        @Composable
        get() = LocalHeritageColors.current

    val dimens: BikerDimens
        get() = BikerDimens
}

/**
 * Compatibilidad retroactiva para pantallas existentes que invocan [BikerColors].
 * Se vincula directamente a los tokens Heritage para elevar la calidad visual inmediatamente.
 */
object BikerColors {
    val BackgroundDark: Color get() = HeritageDarkAsphalt
    val SurfaceDark: Color get() = HeritageDarkLeather
    val SkeletonBaseDark: Color get() = HeritageDarkLeatherElevated
    val SkeletonHighlightDark: Color get() = Color(0xFF2D3038)
    val PrimaryAmber: Color get() = HeritageDarkBrass
    val StatusGreen: Color get() = HeritageDarkSafe
    val StatusYellow: Color get() = HeritageDarkWarning
    val StatusRed: Color get() = HeritageDarkDanger
    val TextPrimary: Color get() = HeritageDarkIvory
    val TextSecondary: Color get() = HeritageDarkMetal
}

object BikerDimens {
    /** Botones en rodada >= 72 dp para modo guantes (DESIGN.md §1). */
    val GlovesMinTouchTarget: Dp = 72.dp
    val GlovesIconSize: Dp = 40.dp
    val NormalIconSize: Dp = 24.dp
    val AvatarListSize: Dp = 48.dp
    val CardCornerRadius: Dp = 18.dp
    val ButtonCornerRadius: Dp = 14.dp
}

private val MaterialDarkColorScheme: ColorScheme = darkColorScheme(
    primary = HeritageDarkBrass,
    onPrimary = HeritageDarkAsphalt,
    secondary = HeritageDarkIvory,
    onSecondary = HeritageDarkAsphalt,
    background = HeritageDarkAsphalt,
    onBackground = HeritageDarkIvory,
    surface = HeritageDarkLeather,
    onSurface = HeritageDarkIvory,
    error = HeritageDarkDanger,
    onError = Color.White
)

private val MaterialLightColorScheme: ColorScheme = lightColorScheme(
    primary = HeritageLightBrass,
    onPrimary = Color.White,
    secondary = HeritageLightInk,
    onSecondary = Color.White,
    background = HeritageLightIvory,
    onBackground = HeritageLightInk,
    surface = HeritageLightSurface,
    onSurface = HeritageLightInk,
    error = HeritageLightDanger,
    onError = Color.White
)

@Composable
fun RutaBikerSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val heritageColors = if (darkTheme) DarkHeritageColors else LightHeritageColors
    val materialColors = if (darkTheme) MaterialDarkColorScheme else MaterialLightColorScheme

    CompositionLocalProvider(
        LocalHeritageColors provides heritageColors
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content
        )
    }
}
