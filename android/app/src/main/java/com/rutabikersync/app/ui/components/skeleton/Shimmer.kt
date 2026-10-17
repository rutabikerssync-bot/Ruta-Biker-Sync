package com.rutabikersync.app.ui.components.skeleton

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.rutabikersync.app.R

val LocalSkeletonAnimated = compositionLocalOf { true }
val LocalSkeletonProgress = compositionLocalOf { 0f }
val LocalRideMode = compositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        } catch (_: Throwable) {
            false
        }
    }
}

@Composable
fun rememberBatterySaverOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Contenedor que sincroniza una única transición de brillo para todos sus hijos (SKELETONS.md §4 y §8).
 * En modo guantes, ahorro de batería o reducir animaciones, el skeleton es estático.
 * TalkBack anuncia "Cargando" una sola vez y oculta las cajas hijas.
 */
@Composable
fun SkeletonScope(
    forceStatic: Boolean = false,
    content: @Composable () -> Unit
) {
    val reduceMotion = rememberReduceMotion()
    val batterySaver = rememberBatterySaverOn()
    val glovesMode = LocalRideMode.current
    val animated = !(reduceMotion || batterySaver || glovesMode || forceStatic)

    val progress = if (animated) {
        val transition = rememberInfiniteTransition(label = "skeleton_scope")
        val value by transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = SkeletonTokens.shimmerDurationMs,
                    easing = LinearEasing
                )
            ),
            label = "shimmer_x"
        )
        value
    } else {
        0f
    }

    val loadingAnnouncement = stringResource(id = R.string.skeleton_loading_a11y)

    CompositionLocalProvider(
        LocalSkeletonAnimated provides animated,
        LocalSkeletonProgress provides progress
    ) {
        Box(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = loadingAnnouncement
            }
        ) {
            content()
        }
    }
}

/**
 * Aplica el brillo sincronizado del SkeletonScope actual o fondo estático si las animaciones están desactivadas.
 */
fun Modifier.shimmer(): Modifier = composed {
    val animated = LocalSkeletonAnimated.current
    val x = LocalSkeletonProgress.current
    if (!animated) {
        this.background(SkeletonTokens.base)
    } else {
        this.drawWithCache {
            val brush = Brush.linearGradient(
                colors = listOf(
                    SkeletonTokens.base,
                    SkeletonTokens.highlight,
                    SkeletonTokens.base
                ),
                start = Offset(size.width * x, 0f),
                end = Offset(size.width * (x + 0.5f), size.height)
            )
            onDrawBehind {
                drawRect(brush = brush)
            }
        }
    }
}
