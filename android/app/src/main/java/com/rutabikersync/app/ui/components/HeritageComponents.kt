package com.rutabikersync.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rutabikersync.app.R
import com.rutabikersync.app.ui.theme.BikerDimens
import com.rutabikersync.app.ui.theme.HeritageTheme
import com.rutabikersync.app.ui.theme.*

/**
 * Placa biselada con relieve 3D oficial "Heritage Premium" (luz cenital, sombra de profundidad y fondo grafito).
 */
@Composable
fun HeritageBevelCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    cardColor: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = HeritageTheme.colors.isDark

    val backgroundBrush = if (cardColor != null) {
        Brush.verticalGradient(
            colors = listOf(
                cardColor.copy(alpha = 0.98f),
                cardColor,
                cardColor.copy(alpha = 0.95f)
            )
        )
    } else if (!isDark) {
        // Heritage Warm: Pergamino / Arena cálido
        Brush.verticalGradient(
            colors = listOf(
                HeritageTheme.colors.surface,
                HeritageTheme.colors.surface
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1D2028),
                Color(0xFF13151A),
                Color(0xFF0F1115)
            )
        )
    }

    val borderBrush = if (borderColor != null) {
        Brush.verticalGradient(
            colors = listOf(
                borderColor.copy(alpha = 0.70f),
                borderColor.copy(alpha = 0.30f)
            )
        )
    } else if (!isDark) {
        // Heritage Warm: Borde costura de cuero
        Brush.verticalGradient(
            colors = listOf(
                HeritageTheme.colors.borderSubtle,
                HeritageTheme.colors.borderSubtle
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.30f),
                Color(0xFF454B5C).copy(alpha = 0.45f),
                Color(0xFF242733).copy(alpha = 0.25f),
                Color.Transparent
            )
        )
    }

    val shadowElevation = if (!isDark) 2.5.dp else 6.dp
    val ambientShadow = if (!isDark) Color(0x123E2723) else Color.Black
    val spotShadow = if (!isDark) Color(0x183E2723) else Color.Black.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = ambientShadow,
                spotColor = spotShadow
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush = backgroundBrush)
            .border(
                width = if (!isDark) 1.dp else 1.2.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(cornerRadius)
            )
            .padding(contentPadding)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides HeritageTheme.colors.textPrimary
        ) {
            Column(content = content)
        }
    }
}

/**
 * Borde mínimo blanco superior que se va desvaneciendo gradualmente mientras baja y llega al centro.
 */
fun Modifier.fadingTopBorder(
    cornerRadius: Dp = 12.dp,
    strokeWidth: Dp = 1.dp,
    topWhiteAlpha: Float = 0.30f,
    midColor: Color = Color(0xFF454B5C)
): Modifier = this.border(
    width = strokeWidth,
    brush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = topWhiteAlpha),
            midColor.copy(alpha = 0.45f),
            midColor.copy(alpha = 0.15f),
            Color.Transparent
        )
    ),
    shape = RoundedCornerShape(cornerRadius)
)


/**
 * Botón Placa de Latón Envejecido con relieve físico y degradado cálido,
 * adaptado a Coral Radiante en el estilo Urban Fresh.
 */
@Composable
fun HeritageBrassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val isDark = HeritageTheme.colors.isDark

    val buttonBgColors = if (!isDark) {
        // Heritage Warm: Cobre profundo / Terracota quemado elegante
        if (enabled) listOf(Color(0xFFC26640), Color(0xFFB85C38), Color(0xFF9C4A2F))
        else listOf(Color(0xFFEADBC8), Color(0xFFD7C4B2))
    } else if (enabled) listOf(
        Color(0xFFF6CE74),
        Color(0xFFDF9E32),
        Color(0xFFB8781B)
    ) else listOf(
        Color(0xFF4A4438),
        Color(0xFF2D2922)
    )

    val buttonBorderColors = if (!isDark) {
        if (enabled) listOf(Color(0xFFD47C56), Color(0xFF8B3A1A))
        else listOf(Color(0xFFD7C4B2), Color(0xFFC5B09E))
    } else if (enabled) listOf(
        Color(0xFFFFEEBA),
        Color(0xFF8A550C)
    ) else listOf(
        Color(0xFF6B6252),
        Color(0xFF1F1C17)
    )

    val textColor = if (!isDark) {
        if (enabled) Color.White else Color(0xFF8D7B68)
    } else {
        if (enabled) Color(0xFF101216) else Color(0xFF8A8275)
    }

    val shadowSpot = if (!isDark) {
        if (enabled) Color(0x309C4A2F) else Color.Transparent
    } else {
        if (enabled) Color(0xFFE5B869) else Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = if (!isDark) 4.dp else 6.dp,
                shape = RoundedCornerShape(14.dp),
                spotColor = shadowSpot
            )
            .clip(RoundedCornerShape(14.dp))
            .background(brush = Brush.verticalGradient(colors = buttonBgColors))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(colors = buttonBorderColors),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = textColor,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 0.4.sp
            )
        }
    }
}

/**
 * Escudo o parche circular con relieve concéntrico y aro de latón envejecido.
 */
@Composable
fun HeritageEmblemBadge(
    imageVector: ImageVector? = null,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    glowColor: Color = Color(0xFFF3C766)
) {
    val isBright = HeritageTheme.isBrightRider
    Box(
        modifier = modifier
            .size(badgeSize)
            .shadow(
                elevation = if (isBright) 2.dp else 4.dp,
                shape = CircleShape,
                spotColor = if (isBright) Color(0x331769E0) else glowColor
            )
            .clip(CircleShape)
            .background(
                brush = if (isBright) {
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFEFF6FF),
                            Color(0xFFDBEAFE)
                        )
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2C313E),
                            Color(0xFF14161C),
                            Color(0xFF0B0C0F)
                        )
                    )
                }
            )
            .border(
                width = if (isBright) 1.5.dp else 1.8.dp,
                brush = if (isBright) {
                    Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFF93C5FD),
                            Color(0xFF1769E0),
                            Color(0xFF93C5FD),
                            Color(0xFF2563EB),
                            Color(0xFF93C5FD)
                        )
                    )
                } else {
                    Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFFF6CE74),
                            Color(0xFF8A550C),
                            Color(0xFFF6CE74),
                            Color(0xFFB8781B),
                            Color(0xFFF6CE74)
                        )
                    )
                },
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (painter != null) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize)
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = if (isBright && (glowColor == Color(0xFFF3C766) || glowColor == Color(0xFFD4AF37))) Color(0xFF1769E0) else glowColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Tarjeta base oficial con estética "Heritage Premium" (cuero, bordes metálicos sutiles y elevación cálida).
 */
@Composable
fun HeritageCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = BorderStroke(1.dp, HeritageTheme.colors.borderSubtle),
    elevation: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(BikerDimens.CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = HeritageTheme.colors.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = border
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

/**
 * Botón primario de latón envejecido (Brass) para acciones clave (Iniciar rodada, Iniciar sesión, Escanear QR).
 */
@Composable
fun HeritagePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HeritageTheme.colors.primary,
            contentColor = HeritageTheme.colors.onPrimary,
            disabledContainerColor = HeritageTheme.colors.borderSubtle,
            disabledContentColor = HeritageTheme.colors.textSecondary
        ),
        shape = RoundedCornerShape(BikerDimens.ButtonCornerRadius)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Botón secundario con contorno metálico cepillado o latón para acciones secundarias.
 */
@Composable
fun HeritageOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isProminent: Boolean = false
) {
    val borderColor = if (isProminent) HeritageTheme.colors.borderProminent else HeritageTheme.colors.borderSubtle
    val contentColor = if (isProminent) HeritageTheme.colors.primary else HeritageTheme.colors.textPrimary

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        border = BorderStroke(1.2.dp, borderColor),
        shape = RoundedCornerShape(BikerDimens.ButtonCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor
        )
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/**
 * Tipos de estado canónicos para insignias de membresía, cuotas y rodadas.
 */
enum class HeritageBadgeType {
    SAFE,       // Al día, Activo, GPS OK, Conectado (Verde)
    WARNING,    // En gracia, Pendiente, Desviado, Vence pronto (Ámbar)
    DANGER,     // Vencido, SOS, Desconectado (Rojo)
    NEUTRAL     // Invitado, Inactivo (Metal)
}

/**
 * Insignia de estado tipo píldora con punto indicador luminoso.
 */
@Composable
fun HeritageStatusBadge(
    text: String,
    type: HeritageBadgeType = HeritageBadgeType.SAFE,
    modifier: Modifier = Modifier
) {
    val (dotColor, bgColor, textColor) = when (type) {
        HeritageBadgeType.SAFE -> Triple(HeritageGreen, HeritageGreenLight, HeritageGreen)
        HeritageBadgeType.WARNING -> Triple(HeritageAmber, HeritageCardWarm, HeritageAmber)
        HeritageBadgeType.DANGER -> Triple(HeritageRustRed, HeritageRustRed.copy(alpha = 0.14f), HeritageRustRed)
        HeritageBadgeType.NEUTRAL -> Triple(HeritageLeatherMuted, HeritageCardWarm, HeritageLeatherMuted)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgColor)
            .border(1.dp, dotColor.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Encabezado de sección con tipografía contundente de latón y enlace opcional de acción.
 */
@Composable
fun HeritageSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            color = HeritageTheme.colors.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp
        )
        if (actionText != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = actionText,
                    color = HeritageTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Tarjeta de métrica de perfil / carretera (ej. 4.850 km | 6 meses racha | 18 rodadas).
 */
@Composable
fun HeritageStatBox(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    HeritageCard(
        modifier = modifier,
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = HeritageTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = value,
                    color = HeritageTheme.colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                text = label,
                color = HeritageTheme.colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Botón gigante para Modo Guantes en la Cabina de Rodada (Hablar PTT, Señales, SOS).
 * Cumple con la regla innegociable de >= 72 dp de área táctil.
 */
@Composable
fun HeritageCabinActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    isActive: Boolean = false
) {
    val containerColor = when {
        isDanger -> HeritageTheme.colors.danger
        isActive -> HeritageTheme.colors.primary
        else -> HeritageTheme.colors.surfaceElevated
    }
    val contentColor = when {
        isDanger -> Color.White
        isActive -> HeritageTheme.colors.onPrimary
        else -> HeritageTheme.colors.textPrimary
    }

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 88.dp)
            .widthIn(min = BikerDimens.GlovesMinTouchTarget),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(1.5.dp, if (isDanger) HeritageTheme.colors.danger else HeritageTheme.colors.borderSubtle),
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = label,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Botón de parada protegida de 3 segundos para salir de la rodada sin toques accidentales.
 */
/**
 * Botón de parada protegida de 3 segundos para salir de la rodada sin toques accidentales.
 * Reproduce exactamente la cápsula con botón rojo circular y chevron '>' a la izquierda.
 */
@Composable
fun HeritageStopRideHoldButton(
    onHoldCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            val totalTime = 3000L
            while (isHolding) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / totalTime).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    isHolding = false
                    progress = 0f
                    onHoldCompleted()
                    break
                }
                kotlinx.coroutines.delay(16L)
            }
        } else {
            progress = 0f
        }
    }

    val capsuleBorderBrush = Brush.linearGradient(
        listOf(
            Color(0xFFFF5252),
            Color(0xFFD92D35),
            Color(0xFF8B0D14)
        )
    )

    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HeritageCardWarm)
            .border(
                1.5.dp,
                if (isHolding) HeritageRustRed else HeritageRustRed.copy(alpha = 0.6f),
                RoundedCornerShape(14.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Fondo de progreso suave
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HeritageRustRed.copy(alpha = 0.22f * progress))
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = if (isHolding) progress else 0f,
                    modifier = Modifier.fillMaxSize(),
                    color = HeritageRustRed,
                    trackColor = HeritageBorder,
                    strokeWidth = 3.dp
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = HeritageRustRed,
                    modifier = Modifier.size(14.dp)
                )
            }

            Text(
                text = if (isHolding) {
                    val remaining = ((3000L - (progress * 3000f).toLong()) / 1000L).coerceAtLeast(1L)
                    "Soltar cancela · Deteniendo en ${remaining}s..."
                } else {
                    "Mantén presionado para detener"
                },
                color = HeritageRustRed,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Barra inferior oficial de 4 pestañas Heritage (Rodadas · Mi Club · Bóveda · Perfil)
 * con estética de cuero oscuro, latón envejecido y línea de selección activa.
 */
@Composable
fun HeritageBottomNavigationBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple(stringResource(R.string.nav_tab_rides), AppIcons.TabRides, 0),
        Triple(stringResource(R.string.nav_tab_club), AppIcons.TabGroups, 1),
        Triple(stringResource(R.string.nav_tab_vault), AppIcons.LeaderFallback, 2),
        Triple(stringResource(R.string.nav_tab_profile), AppIcons.TabProfile, 3)
    )

    val isDark = HeritageTheme.colors.isDark
    val barBg = if (!isDark) HeritageTheme.colors.surface else Color(0xFF111317)
    val borderCol = HeritageTheme.colors.borderSubtle
    val barBorderBrush = if (!isDark) {
        Brush.horizontalGradient(listOf(borderCol, borderCol, borderCol))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF20232B), Color(0xFF434A5B), Color(0xFF20232B)))
    }
    val activeColor = if (!isDark) HeritageTheme.colors.primary else Color(0xFFF3C766)
    val inactiveColor = if (!isDark) HeritageTheme.colors.textSecondary else Color(0xFF7E8494)

    Surface(
        color = barBg,
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (!isDark) 6.dp else 0.dp, RectangleShape, ambientColor = Color(0x06183153), spotColor = Color(0x0A183153))
            .border(
                width = 1.dp,
                brush = barBorderBrush,
                shape = RectangleShape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { (title, icon, index) ->
                val isSelected = selectedTabIndex == index
                val itemColor = if (isSelected) activeColor else inactiveColor

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = itemColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = title,
                            color = itemColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        // Indicador inferior con píldora
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 20.dp else 0.dp)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSelected) activeColor else Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Encabezado de marca superior "RUTA BIKER SYNC" con selector de tema claro/oscuro.
 */
@Composable
fun HeritageTopBrandHeader(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "RUTA BIKER SYNC",
                color = HeritageTheme.colors.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "CONECTA · PROTEGE · AVANZA",
                color = HeritageTheme.colors.textSecondary,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold
            )
        }

        IconButton(
            onClick = onToggleDarkMode,
            modifier = Modifier
                .clip(CircleShape)
                .background(HeritageTheme.colors.surfaceElevated)
                .border(1.dp, HeritageTheme.colors.borderSubtle, CircleShape)
        ) {
            Icon(
                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = "Cambiar tema",
                tint = HeritageTheme.colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Tarjeta biselada para métricas del piloto (Km acumulados, Racha mensual, Rodadas).
 */
@Composable
fun HeritageMetricCard(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val isLight = !HeritageTheme.colors.isDark
    val iconBgColor = if (isLight) iconTint.copy(alpha = 0.12f) else iconTint.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isLight) 1.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = Color(0x0C183153)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLight) HeritageCard else Color(0xFF14161D))
            .border(
                width = 1.dp,
                color = if (isLight) HeritageBorder else Color(0xFF242733),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = value,
                color = if (isLight) HeritageLeatherBrown else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = label,
                color = if (isLight) HeritageLeatherMuted else Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * Escudo de insignia / logro con relieve (Aventurero, Lluvia, Montaña, MC Honor).
 * Soporta efecto de bloqueo para insignias aún no conseguidas.
 */
@Composable
fun HeritageAchievementBadge(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    badgeRes: Int? = null,
    borderColor: Color = Color(0xFFE5B869),
    iconTint: Color = Color(0xFFF6CE74),
    isUnlocked: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isLight = !HeritageTheme.colors.isDark

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.width(88.dp)
    ) {
        Box(
            modifier = Modifier
                .shadow(
                    elevation = if (isLight && isUnlocked) 2.dp else 0.dp,
                    shape = RoundedCornerShape(18.dp),
                    spotColor = if (isUnlocked) borderColor.copy(alpha = 0.35f) else Color.Transparent
                )
                .size(74.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (isUnlocked) {
                        if (isLight) HeritageCard else Color(0xFF1F232D)
                    } else {
                        if (isLight) HeritageCardWarm else Color(0xFF13151B)
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (isUnlocked) {
                        if (isLight) borderColor.copy(alpha = 0.6f) else borderColor
                    } else {
                        if (isLight) HeritageBorder else Color(0xFF272B36)
                    },
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (badgeRes != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = badgeRes),
                    contentDescription = title,
                    alpha = if (isUnlocked) 1f else 0.35f,
                    modifier = Modifier.size(64.dp)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isUnlocked) iconTint else Color(0xFF94A3B8),
                    modifier = Modifier.size(36.dp)
                )
            }

            if (!isUnlocked) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isLight) HeritageLeatherBrown.copy(alpha = 0.8f) else Color(0xFF0F172A).copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔒",
                        fontSize = 11.sp
                    )
                }
            }
        }
        Text(
            text = title,
            color = if (isUnlocked) {
                if (isLight) HeritageLeatherBrown else Color.White
            } else {
                if (isLight) HeritageLeatherMuted else Color(0xFF64748B)
            },
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = if (isLight) HeritageLeatherMuted else Color(0xFF94A3B8),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Parche bordado de club aliado recibido.
 */
@Composable
fun HeritageClubPatchBadge(
    clubName: String,
    badgeText: String,
    patchColor: Color,
    icon: ImageVector? = Icons.Default.Shield,
    badgeRes: Int? = null,
    modifier: Modifier = Modifier
) {
    val isDark = HeritageTheme.colors.isDark
    val bgBrush = if (!isDark) {
        Brush.verticalGradient(listOf(HeritageCard, HeritageCard))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF1F222B), Color(0xFF0F1116)))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.width(96.dp)
    ) {
        Box(
            modifier = Modifier
                .shadow(
                    elevation = if (!isDark) 2.dp else 0.dp,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = Color(0x0A183153),
                    spotColor = Color(0x0C183153)
                )
                .size(74.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bgBrush)
                .border(1.5.dp, patchColor, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (badgeRes != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = badgeRes),
                    contentDescription = clubName,
                    modifier = Modifier.size(64.dp)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = patchColor,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Text(
            text = clubName,
            color = HeritageTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            text = badgeText,
            color = if (!isDark) HeritageTheme.colors.primary else Color(0xFFE5B869),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}
