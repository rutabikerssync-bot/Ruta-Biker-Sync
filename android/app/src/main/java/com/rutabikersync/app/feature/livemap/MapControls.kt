package com.rutabikersync.app.feature.livemap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.components.AppIcons
import com.rutabikersync.app.ui.theme.BikerColors
import com.rutabikersync.app.ui.theme.BikerDimens
import com.rutabikersync.app.ui.theme.HeritageTheme

/**
 * Definición de las 3 acciones flotantes máximas permitidas en modo guantes (DESIGN.md §1, §2 y SCREENS.md C1):
 * 1. Hablar (PTT)
 * 2. SOS
 * 3. Señales
 */
enum class GlovesPrimaryAction(val label: String) {
    TALK_PTT("Hablar"),
    SOS("SOS"),
    QUICK_SIGNALS("Señales")
}

object GlovesModePolicy {
    const val MAX_FLOATING_ACTIONS_IN_GLOVES_MODE: Int = 3
    val MIN_TOUCH_TARGET_DP: Float = BikerDimens.GlovesMinTouchTarget.value // 72.0 dp

    fun visiblePrimaryActions(): List<GlovesPrimaryAction> = listOf(
        GlovesPrimaryAction.TALK_PTT,
        GlovesPrimaryAction.SOS,
        GlovesPrimaryAction.QUICK_SIGNALS
    )
}

/**
 * Botón táctil grande (≥ 72 dp × 72 dp, icono de 40 dp, sin animaciones decorativas, con vibración háptica)
 * diseñado para operarse con guantes de motociclista bajo el sol (DESIGN.md §1 y ICONS.md §1 Regla 3).
 */
@Composable
fun GlovesActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BikerColors.SurfaceDark,
    contentColor: Color = BikerColors.TextPrimary,
    borderColor: Color = BikerColors.PrimaryAmber
) {
    val haptics = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .defaultMinSize(
                minWidth = BikerDimens.GlovesMinTouchTarget,
                minHeight = BikerDimens.GlovesMinTouchTarget
            )
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .semantics { contentDescription = label }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(BikerDimens.GlovesIconSize)
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * Controles laterales de cámara del mapa (≥ 72 dp):
 * - En modo guantes (`glovesMode = true`), reduce la columna lateral a los controles esenciales de cámara
 *   (`Mi ubicación` y `Seguir al líder 10 s`) para no saturar la vista en movimiento.
 * - En modo normal (`glovesMode = false`), incluye además `Capas` (`PMTiles Offline`) y `Zoom + / -`.
 */
@Composable
fun MapCameraControlsColumn(
    glovesMode: Boolean,
    isFollowingLeader: Boolean,
    followLeaderRemainingSeconds: Int,
    onCenterMyLocation: () -> Unit,
    onFollowLeader: () -> Unit,
    onToggleLayerInfo: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End,
        modifier = modifier
    ) {
        GlovesActionButton(
            icon = AppIcons.MyLocation,
            label = "Mi ubic.",
            onClick = onCenterMyLocation
        )

        GlovesActionButton(
            icon = AppIcons.FollowLeader,
            label = if (isFollowingLeader) "Líder (${followLeaderRemainingSeconds}s)" else "Seguir líder",
            onClick = onFollowLeader,
            containerColor = if (isFollowingLeader) BikerColors.PrimaryAmber else BikerColors.SurfaceDark,
            contentColor = if (isFollowingLeader) BikerColors.BackgroundDark else BikerColors.TextPrimary
        )

        if (!glovesMode) {
            GlovesActionButton(
                icon = AppIcons.MapLayers,
                label = "PMTiles",
                onClick = onToggleLayerInfo
            )
            GlovesActionButton(
                icon = AppIcons.ZoomIn,
                label = "Zoom +",
                onClick = onZoomIn
            )
            GlovesActionButton(
                icon = AppIcons.ZoomOut,
                label = "Zoom -",
                onClick = onZoomOut
            )
        }
    }
}

/**
 * Botón circular premium Heritage para modo guantes (≥ 72 dp).
 * Reproduce fielmente el diseño con doble aro metálico de latón envejecido, cuero oscuro y profundidad radial.
 */
@Composable
fun HeritageCircularGloveButton(
    icon: ImageVector,
    primaryText: String,
    secondaryText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    diameter: androidx.compose.ui.unit.Dp = 88.dp
) {
    val haptics = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        val outerRingBrush = if (isDanger) {
            Brush.sweepGradient(
                listOf(
                    Color(0xFFFF6B6B),
                    Color(0xFFD92D35),
                    Color(0xFF8B0D14),
                    Color(0xFFFF6B6B)
                )
            )
        } else {
            Brush.sweepGradient(
                listOf(
                    Color(0xFFF3D082),
                    Color(0xFFC99A45),
                    Color(0xFF7E5B1D),
                    Color(0xFFE5B869),
                    Color(0xFFF3D082)
                )
            )
        }

        val innerBackgroundBrush = if (isDanger) {
            Brush.radialGradient(
                listOf(
                    Color(0xFFF03A43),
                    Color(0xFFB71C24),
                    Color(0xFF5A0A10)
                )
            )
        } else {
            Brush.radialGradient(
                listOf(
                    Color(0xFF2E313C),
                    Color(0xFF1E2026),
                    Color(0xFF121316)
                )
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(outerRingBrush)
                .padding(3.5.dp) // Grosor del aro exterior metálico
                .clip(CircleShape)
                .background(innerBackgroundBrush)
                .border(
                    width = 1.dp,
                    color = if (isDanger) Color(0xFFFF9999).copy(alpha = 0.5f) else Color(0xFFF3D082).copy(alpha = 0.35f),
                    shape = CircleShape
                )
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "$primaryText $secondaryText",
                tint = if (isDanger) Color.White else if (primaryText.startsWith("Señal")) Color(0xFFE5B869) else Color(0xFFF3EBDD),
                modifier = Modifier.size(if (isDanger) 42.dp else 38.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = primaryText,
            color = if (isDanger) Color(0xFFFF6B6B) else Color(0xFFF3EBDD),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 13.sp
        )
        Text(
            text = secondaryText,
            color = if (isDanger) Color(0xFFFF9999) else Color(0xFFC99A45),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 11.sp
        )
    }
}

/**
 * Barra inferior de acciones de rodada en modo guantes (SCREENS.md C1 y DESIGN.md §2):
 * Exactamente 3 acciones circulares de cabina (≥ 88 dp) con aro metálico Heritage y vibración háptica:
 * 1. Hablar (PTT)
 * 2. Señal de ruta
 * 3. SOS Emergencia (Rojo radiante de alto contraste)
 */
@Composable
fun GlovesPrimaryActionBar(
    isSosActive: Boolean,
    onTalkPttClick: () -> Unit,
    onSosClick: () -> Unit,
    onQuickSignalsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        HeritageCircularGloveButton(
            icon = AppIcons.PttMic,
            primaryText = "Hablar",
            secondaryText = "(PTT)",
            onClick = onTalkPttClick,
            diameter = 88.dp
        )

        HeritageCircularGloveButton(
            icon = Icons.Default.PanTool,
            primaryText = "Señal",
            secondaryText = "de ruta",
            onClick = onQuickSignalsClick,
            diameter = 88.dp
        )

        HeritageCircularGloveButton(
            icon = AppIcons.SosAlert,
            primaryText = "SOS",
            secondaryText = if (isSosActive) "ACTIVO" else "Emergencia",
            onClick = onSosClick,
            isDanger = true,
            diameter = 88.dp
        )
    }
}
