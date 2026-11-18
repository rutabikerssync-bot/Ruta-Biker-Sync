package com.rutabikersync.app.feature.mesh

import androidx.compose.ui.graphics.Color
import com.rutabikersync.app.ui.theme.DarkCardElevated
import com.rutabikersync.app.ui.theme.DarkBorderProminent
import com.rutabikersync.app.ui.theme.DarkSlate950
import com.rutabikersync.app.ui.theme.EmeraldMeshGlow
import com.rutabikersync.app.ui.theme.HeritageCopperDark
import com.rutabikersync.app.ui.theme.HeritageCopperGlow
import com.rutabikersync.app.ui.theme.HeritageGoldGlow
import com.rutabikersync.app.ui.theme.HeritageGreenLight
import com.rutabikersync.app.ui.theme.HeritageBrassGold
import com.rutabikersync.app.ui.theme.TextWhite
import kotlin.math.pow

/**
 * Paleta centralizada del Chat Táctico Off-Grid con contraste WCAG AA verificado.
 *
 * Motivo: en campo (2026-11-18) el mensaje preset "⛽ Gasolinera a 2 km" aparecía
 * ilegible sobre el acento naranja. Se usan fondos oscuros de la paleta Dark Elite
 * con texto blanco de alto contraste, y se distingue Broadcast (naranja oscuro) de
 * Unicast (verde oscuro) tanto en el fondo de la burbuja como en el badge de canal.
 *
 * Reglas de contraste verificadas en `TacticalChatContrastTest`:
 * - Texto de cuerpo / cabecera / metadatos >= 4.5:1 (WCAG AA texto normal).
 */
data class ChatBubblePalette(
    val background: Color,
    val border: Color,
    val text: Color,
    val headerText: Color,
    val metaText: Color,
    val badgeBackground: Color,
    val badgeText: Color
)

private val ChatTextBody = TextWhite                 // #F0F6FC
private val ChatTextHeader = HeritageGoldGlow        // #F3C644
val ChatTextMeta = Color(0xFFCBD5E1)                 // Slate-300, legible sobre fondos oscuros

/** Broadcast saliente: naranja oscuro Heritage + texto blanco (~7.2:1). */
val ChatBubbleOutgoingBroadcast = ChatBubblePalette(
    background = HeritageCopperDark,   // #8B3A1A
    border = HeritageCopperGlow,       // #E07A4D
    text = ChatTextBody,
    headerText = ChatTextHeader,
    metaText = ChatTextMeta,
    badgeBackground = DarkSlate950,
    badgeText = EmeraldMeshGlow
)

/** Unicast saliente: verde oscuro Heritage + texto blanco (~9.2:1). */
val ChatBubbleOutgoingUnicast = ChatBubblePalette(
    background = HeritageGreenLight,   // #064E3B
    border = EmeraldMeshGlow,          // #34D399
    text = ChatTextBody,
    headerText = ChatTextHeader,
    metaText = ChatTextMeta,
    badgeBackground = DarkSlate950,
    badgeText = HeritageCopperGlow
)

/** Entrante: superficie elevada + texto blanco (alto contraste). */
val ChatBubbleIncoming = ChatBubblePalette(
    background = DarkCardElevated,     // #1F2630
    border = DarkBorderProminent,      // #384354
    text = ChatTextBody,
    headerText = HeritageBrassGold,
    metaText = ChatTextMeta,
    badgeBackground = DarkSlate950,
    badgeText = EmeraldMeshGlow
)

/** Selecciona la paleta según dirección y canal del mensaje. */
fun chatBubblePalette(isOutgoing: Boolean, isBroadcast: Boolean): ChatBubblePalette = when {
    isOutgoing && isBroadcast -> ChatBubbleOutgoingBroadcast
    isOutgoing -> ChatBubbleOutgoingUnicast
    else -> ChatBubbleIncoming.copy(
        badgeText = if (isBroadcast) EmeraldMeshGlow else HeritageCopperGlow
    )
}

/** Luminancia relativa sRGB (WCAG 2.1) para un [Color] opaco de Compose. */
fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) +
        0.7152f * channel(color.green) +
        0.0722f * channel(color.blue)
}

/** Ratio de contraste WCAG 2.1 entre dos colores (1.0 .. 21.0). */
fun contrastRatio(foreground: Color, background: Color): Double {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
