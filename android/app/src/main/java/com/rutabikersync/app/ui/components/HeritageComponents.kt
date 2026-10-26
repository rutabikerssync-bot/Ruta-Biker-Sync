package com.rutabikersync.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.BikerDimens
import com.rutabikersync.app.ui.theme.HeritageTheme

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
        HeritageBadgeType.SAFE -> Triple(
            HeritageTheme.colors.safe,
            HeritageTheme.colors.safe.copy(alpha = 0.14f),
            HeritageTheme.colors.safe
        )
        HeritageBadgeType.WARNING -> Triple(
            HeritageTheme.colors.warning,
            HeritageTheme.colors.warning.copy(alpha = 0.16f),
            HeritageTheme.colors.warning
        )
        HeritageBadgeType.DANGER -> Triple(
            HeritageTheme.colors.danger,
            HeritageTheme.colors.danger.copy(alpha = 0.16f),
            HeritageTheme.colors.danger
        )
        HeritageBadgeType.NEUTRAL -> Triple(
            HeritageTheme.colors.textSecondary,
            HeritageTheme.colors.textSecondary.copy(alpha = 0.12f),
            HeritageTheme.colors.textSecondary
        )
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
            fontWeight = FontWeight.Bold
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(HeritageTheme.colors.danger.copy(alpha = 0.2f))
            .border(1.5.dp, HeritageTheme.colors.danger, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Barra de progreso de llenado en rojo peligro
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(HeritageTheme.colors.danger)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isHolding) "MANTÉN PRESIONADO... (${(3 - (progress * 3)).toInt() + 1}s)" else "Mantén presionado 3 s para detener",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp
            )
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
