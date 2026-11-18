package com.rutabikersync.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutabikersync.app.ui.theme.HeritageCard
import com.rutabikersync.app.ui.theme.HeritageCopper
import com.rutabikersync.app.ui.theme.HeritageGreen
import com.rutabikersync.app.ui.theme.HeritageLeatherBrown
import com.rutabikersync.app.ui.theme.HeritageLeatherMuted
import com.rutabikersync.app.ui.theme.HeritageRustRed

/**
 * Botón PTT de Alta Resistencia para Modo Guantes (Hito 2 · Audio PTT).
 * Cumple con:
 * - Tamaño ergonométrico >= 72 dp (por defecto 76 dp).
 * - Ciclo táctil completo: "Pulsar para hablar" -> "Hablando..." -> "Liberar para enviar".
 * - Feedback háptico inmediato en presión y liberación.
 * - Soporte para estado de transmisión y recepción activa.
 */
@Composable
fun PttButton(
    modifier: Modifier = Modifier,
    isTransmitting: Boolean = false,
    receivingSenderId: Int? = null,
    isEnabled: Boolean = true,
    diameter: Dp = 76.dp,
    onStartTalking: () -> Unit,
    onStopTalking: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var isPressedLocally by remember { mutableStateOf(false) }
    val isActuallyTalking = isTransmitting || isPressedLocally

    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val (bgColor, borderColor, iconColor) = when {
        !isEnabled -> Triple(HeritageCard.copy(alpha = 0.5f), HeritageLeatherMuted.copy(alpha = 0.3f), HeritageLeatherMuted)
        isActuallyTalking -> Triple(HeritageRustRed, Color.White, Color.White)
        receivingSenderId != null -> Triple(HeritageGreen, Color.White, Color.White)
        else -> Triple(HeritageCard, HeritageCopper, HeritageCopper)
    }

    val primaryLabel = when {
        isActuallyTalking -> "Hablando..."
        receivingSenderId != null -> "Recibiendo"
        else -> "Pulsar para hablar"
    }

    val secondaryLabel = when {
        isActuallyTalking -> "Liberar para enviar"
        receivingSenderId != null -> "Piloto #$receivingSenderId"
        else -> "Radio PTT"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(diameter + 12.dp)
        ) {
            // Halo pulsante al transmitir o recibir
            if (isActuallyTalking || receivingSenderId != null) {
                Box(
                    modifier = Modifier
                        .size(diameter + 12.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(if (isActuallyTalking) HeritageRustRed.copy(alpha = 0.3f) else HeritageGreen.copy(alpha = 0.3f))
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(2.5.dp, borderColor, CircleShape)
                    .pointerInput(isEnabled) {
                        if (!isEnabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown().also { it.consume() }
                            isPressedLocally = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStartTalking()

                            val upOrCancel = waitForUpOrCancellation()
                            isPressedLocally = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStopTalking()

                            upOrCancel?.consume()
                        }
                    }
            ) {
                Icon(
                    imageVector = if (receivingSenderId != null) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Mic,
                    contentDescription = "$primaryLabel $secondaryLabel",
                    tint = iconColor,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = primaryLabel,
            color = if (isActuallyTalking) HeritageRustRed else HeritageLeatherBrown,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            text = secondaryLabel,
            color = HeritageLeatherMuted,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp
        )
    }
}

