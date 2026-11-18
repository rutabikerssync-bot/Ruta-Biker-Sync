package com.rutabikersync.app.feature.livemap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.rutabikersync.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                label = "Capas",
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
 * 1. Chip de Estado Superior (Glassmorphism / MD3 Clean)
 * Chips flotantes con RoundedCornerShape(20.dp) y fondo semi-transparente Color.Black.copy(alpha = 0.65f).
 */
@Composable
fun HudStatusChip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    iconTint: Color = HeritageCopper,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(HeritageCardWarm.copy(alpha = 0.92f))
            .border(1.dp, HeritageBorder, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = HeritageLeatherBrown,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 2. Banner de Desvío Limpio (MD3 High Contrast / Vintage Heritage)
 * Tarjeta pergamino con lomo de cuero cobre de 4dp y botón "Voy a parar".
 */
@Composable
fun OffRouteBanner(
    distanceMeters: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distanceFormatted = if (distanceMeters >= 1000) {
        "+%.1f km".format(distanceMeters / 1000f)
    } else {
        "+$distanceMeters m"
    }

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = HeritageCard),
        border = BorderStroke(1.dp, HeritageBorder),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(42.dp)
                    .background(HeritageCopper, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Te has desviado ($distanceFormatted)",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = HeritageLeatherBrown
                )
                Text(
                    text = "Fuera de la ruta del convoy",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = HeritageLeatherMuted
                )
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onStop,
                border = BorderStroke(1.dp, HeritageCopper),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = HeritageCopper
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Voy a parar",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Botón circular premium para modo guantes (76 dp) - Vintage Heritage / Cobre & Cuero.
 * Fondo HeritageCardWarm con borde HeritageCopper de 2dp para Hablar y Señal.
 * Fondo HeritageRustRed con halo exterior para SOS.
 */
@Composable
fun HeritageCircularGloveButton(
    icon: ImageVector,
    primaryText: String,
    secondaryText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    isActive: Boolean = false,
    audioAmplitude: Float = 0f,
    diameter: androidx.compose.ui.unit.Dp = 76.dp
) {
    val haptics = LocalHapticFeedback.current
    val bgColor = when {
        isDanger -> HeritageRustRed
        isActive -> EmeraldMesh
        else -> HeritageCardWarm
    }
    val borderColor = when {
        isDanger -> HeritageRustRed
        isActive -> Color(0xFF34D399)
        else -> HeritageCopper
    }
    val iconColor = when {
        isDanger -> Color.White
        isActive -> Color.White
        else -> HeritageLeatherBrown
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(
                    if (isDanger) {
                        Modifier
                            .size(diameter + 10.dp)
                            .background(HeritageRustRed.copy(alpha = 0.25f), CircleShape)
                            .padding(5.dp)
                    } else if (isActive) {
                        // Halo dinámico reactivo a la modulación de voz
                        val haloExtra = 10.dp + (14.dp * audioAmplitude.coerceIn(0f, 1f))
                        Modifier
                            .size(diameter + haloExtra)
                            .background(EmeraldMesh.copy(alpha = (0.20f + audioAmplitude * 0.40f).coerceIn(0.20f, 0.65f)), CircleShape)
                            .padding(haloExtra / 2)
                    } else {
                        Modifier.size(diameter)
                    }
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(if (isActive) 2.5.dp else 2.dp, borderColor, CircleShape)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
            ) {
                if (isActive && !isDanger) {
                    // Ecualizador de ondas de audio en tiempo real (VU-meter)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val amp = audioAmplitude.coerceIn(0f, 1f)
                        val bar1H = 6.dp + (18.dp * (amp * 0.85f).coerceIn(0.12f, 1f))
                        val bar2H = 8.dp + (24.dp * (amp * 1.0f).coerceIn(0.15f, 1f))
                        val bar3H = 10.dp + (26.dp * (amp * 0.95f).coerceIn(0.18f, 1f))
                        val bar4H = 6.dp + (20.dp * (amp * 0.75f).coerceIn(0.12f, 1f))

                        Box(modifier = Modifier.width(4.5.dp).height(bar1H).clip(CircleShape).background(Color.White))
                        Box(modifier = Modifier.width(4.5.dp).height(bar2H).clip(CircleShape).background(Color.White))
                        Box(modifier = Modifier.width(4.5.dp).height(bar3H).clip(CircleShape).background(Color.White))
                        Box(modifier = Modifier.width(4.5.dp).height(bar4H).clip(CircleShape).background(Color.White))
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = "$primaryText $secondaryText",
                        tint = iconColor,
                        modifier = Modifier.size(if (isDanger) 36.dp else 32.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = primaryText,
            color = if (isActive) EmeraldMesh else HeritageLeatherBrown,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = secondaryText,
            color = if (isActive) EmeraldMesh else HeritageLeatherMuted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Barra inferior de acciones de rodada en modo guantes (SCREENS.md C1 y DESIGN.md §2):
 * Exactamente 3 acciones circulares de cabina (76 dp) de alto contraste:
 * 1. Hablar (PTT)
 * 2. Señal de ruta
 * 3. SOS Emergencia — Opción B: mantener presionado 3 segundos con anillo de progreso.
 */
@Composable
fun GlovesPrimaryActionBar(
    isSosActive: Boolean,
    isPttActive: Boolean = false,
    audioAmplitude: Float = 0f,
    pttSecondaryText: String? = null,
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
            primaryText = if (isPttActive) "Hablando" else "Hablar",
            secondaryText = pttSecondaryText ?: (if (isPttActive) "EN VIVO" else "(PTT)"),
            onClick = onTalkPttClick,
            isActive = isPttActive,
            audioAmplitude = audioAmplitude,
            diameter = 76.dp
        )

        HeritageCircularGloveButton(
            icon = Icons.Default.PanTool,
            primaryText = "Señal",
            secondaryText = "de ruta",
            onClick = onQuickSignalsClick,
            diameter = 76.dp
        )

        // SOS: si ya está activo → toque simple para cancelar.
        // Si no está activo → mantener presionado 3 s para confirmar.
        if (isSosActive) {
            HeritageCircularGloveButton(
                icon = AppIcons.SosAlert,
                primaryText = "SOS",
                secondaryText = "¡ACTIVO!",
                onClick = onSosClick,
                isDanger = true,
                isActive = true,
                diameter = 76.dp
            )
        } else {
            SosHoldButton(
                onSosConfirmed = onSosClick,
                diameter = 76.dp
            )
        }
    }
}


/**
 * Botón SOS con confirmación por mantenimiento presionado (Opción B, Hito 4 · Etapa 4).
 *
 * Comportamiento:
 * - El usuario debe mantener presionado durante [holdDurationMs] ms (por defecto 3 s).
 * - Durante el hold, se anima un anillo de progreso rojo y se emite feedback háptico incremental.
 * - Si suelta antes: anillo se resetea, se muestra "SOS cancelado" vía retroalimentación háptica leve.
 * - Al completar los 3 s: se dispara [onSosConfirmed] con un patrón háptico de emergencia (Morse SOS).
 *
 * Cumplimiento de DESIGN.md §1 y §2: botón de 76 dp (≥72 dp mínimo de modo guantes).
 */
@Composable
fun SosHoldButton(
    onSosConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 76.dp,
    holdDurationMs: Long = 3000L
) {
    val haptics = LocalHapticFeedback.current
    var progress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    // Animación y conteo de progreso mientras se mantiene presionado (3 segundos)
    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            var lastHapticTime = 0L
            while (isHolding) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / holdDurationMs).coerceIn(0f, 1f)

                // Feedback háptico incremental
                val interval = when {
                    elapsed < 1000L -> 500L
                    elapsed < 2000L -> 300L
                    else -> 150L
                }
                if (elapsed - lastHapticTime >= interval) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    lastHapticTime = elapsed
                }

                if (progress >= 1f) {
                    isHolding = false
                    progress = 0f
                    // Morse SOS al confirmar
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSosConfirmed()
                    break
                }
                delay(16L)
            }
        } else {
            progress = 0f
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter + 10.dp)
                .background(HeritageRustRed.copy(alpha = 0.25f), CircleShape)
                .padding(5.dp)
        ) {
            // Anillo de progreso superpuesto
            if (isHolding || progress > 0f) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(diameter),
                    color = Color(0xFFEF4444),
                    trackColor = Color(0xFF7F1D1D).copy(alpha = 0.6f),
                    strokeWidth = 5.dp,
                    strokeCap = StrokeCap.Round
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(HeritageRustRed)
                    .border(2.dp, HeritageRustRed, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHolding = true
                                tryAwaitRelease()
                                isHolding = false
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = AppIcons.SosAlert,
                    contentDescription = "SOS Emergencia — Mantener presionado 3 segundos",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "SOS",
            color = HeritageLeatherBrown,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isHolding) "Mantener…" else "Emergencia",
            color = if (isHolding) Color(0xFFEF4444) else HeritageLeatherMuted,
            fontSize = 10.sp,
            fontWeight = if (isHolding) FontWeight.Bold else FontWeight.Normal
        )
    }
}
