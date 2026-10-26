package com.rutabikersync.app.feature.livemap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Barra inferior de acciones de rodada en modo guantes (SCREENS.md C1 y DESIGN.md §2):
 * Exactamente 3 acciones flotantes de ≥ 72 dp con icono + texto y vibración háptica:
 * 1. Hablar (PTT)
 * 2. SOS (Rojo de alto contraste)
 * 3. Señales rápidas
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        GlovesActionButton(
            icon = AppIcons.PttMic,
            label = GlovesPrimaryAction.TALK_PTT.label,
            onClick = onTalkPttClick,
            modifier = Modifier.weight(1f)
        )

        GlovesActionButton(
            icon = AppIcons.SosAlert,
            label = if (isSosActive) {
                if (com.rutabikersync.app.BuildConfig.UX_TEST_MODE) "SOS PRUEBA ACTIVO" else "SOS ACTIVO"
            } else {
                if (com.rutabikersync.app.BuildConfig.UX_TEST_MODE) "SOS (PRUEBA)" else GlovesPrimaryAction.SOS.label
            },
            onClick = onSosClick,
            containerColor = BikerColors.StatusRed,
            contentColor = Color.White,
            borderColor = Color.White,
            modifier = Modifier.weight(1f)
        )

        GlovesActionButton(
            icon = AppIcons.QuickSignals,
            label = GlovesPrimaryAction.QUICK_SIGNALS.label,
            onClick = onQuickSignalsClick,
            modifier = Modifier.weight(1f)
        )
    }
}
