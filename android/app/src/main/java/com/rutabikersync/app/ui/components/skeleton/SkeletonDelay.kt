package com.rutabikersync.app.ui.components.skeleton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Reglas de tiempo de SKELETONS.md §5.1:
 * - Retraso antes de mostrar: 150 ms (evita parpadeo en respuestas rápidas)
 * - Tiempo mínimo visible: 400 ms (evita destello si llega a los 160 ms)
 * - A los 3 000 ms: muestra aviso secundario "Esto está tardando más de lo normal…"
 * - Tiempo máximo: 8 000 ms (pasa a Error(NETWORK) con Reintentar)
 */
object SkeletonTimingRules {
    const val SHOW_DELAY_MS: Long = 150L
    const val MIN_VISIBLE_MS: Long = 400L
    const val SLOW_HINT_MS: Long = 3_000L
    const val MAX_TIMEOUT_MS: Long = 8_000L
    const val AUTO_RETRY_MS: Long = 2_000L

    data class TimingDecision(
        val showSkeleton: Boolean,
        val showSlowHint: Boolean,
        val timedOutToNetworkError: Boolean
    )

    /**
     * Función pura determinista para verificar en pruebas unitarias el comportamiento exacto
     * dado el tiempo de carga transcurrido y el instante en que terminó la carga.
     */
    fun evaluateAt(
        elapsedMs: Long,
        loadingFinishedAtMs: Long? = null,
        showDelayMs: Long = SHOW_DELAY_MS,
        minVisibleMs: Long = MIN_VISIBLE_MS,
        slowHintMs: Long = SLOW_HINT_MS,
        maxTimeoutMs: Long = MAX_TIMEOUT_MS
    ): TimingDecision {
        if (loadingFinishedAtMs != null) {
            if (loadingFinishedAtMs < showDelayMs) {
                return TimingDecision(
                    showSkeleton = false,
                    showSlowHint = false,
                    timedOutToNetworkError = false
                )
            }
            val visibleUntil = showDelayMs + minVisibleMs
            val stillHoldingMinVisible = elapsedMs < visibleUntil
            return TimingDecision(
                showSkeleton = stillHoldingMinVisible,
                showSlowHint = false,
                timedOutToNetworkError = false
            )
        }

        if (elapsedMs >= maxTimeoutMs) {
            return TimingDecision(
                showSkeleton = false,
                showSlowHint = false,
                timedOutToNetworkError = true
            )
        }

        val show = elapsedMs >= showDelayMs
        val slow = elapsedMs >= slowHintMs
        return TimingDecision(
            showSkeleton = show,
            showSlowHint = slow,
            timedOutToNetworkError = false
        )
    }
}

data class DelayedSkeletonState(
    val showSkeleton: Boolean,
    val showSlowHint: Boolean,
    val timedOut: Boolean
)

@Composable
fun rememberDelayedSkeletonState(
    isLoading: Boolean,
    delayMs: Long = SkeletonTimingRules.SHOW_DELAY_MS,
    minVisibleMs: Long = SkeletonTimingRules.MIN_VISIBLE_MS,
    slowHintMs: Long = SkeletonTimingRules.SLOW_HINT_MS,
    maxTimeoutMs: Long = SkeletonTimingRules.MAX_TIMEOUT_MS
): State<DelayedSkeletonState> {
    val state = remember {
        mutableStateOf(
            DelayedSkeletonState(
                showSkeleton = false,
                showSlowHint = false,
                timedOut = false
            )
        )
    }
    var becameVisibleAtMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            state.value = DelayedSkeletonState(
                showSkeleton = false,
                showSlowHint = false,
                timedOut = false
            )
            delay(delayMs)
            becameVisibleAtMs = System.currentTimeMillis()
            state.value = DelayedSkeletonState(
                showSkeleton = true,
                showSlowHint = false,
                timedOut = false
            )
            delay(slowHintMs - delayMs)
            state.value = DelayedSkeletonState(
                showSkeleton = true,
                showSlowHint = true,
                timedOut = false
            )
            delay(maxTimeoutMs - slowHintMs)
            state.value = DelayedSkeletonState(
                showSkeleton = false,
                showSlowHint = false,
                timedOut = true
            )
        } else {
            val startedAt = becameVisibleAtMs
            if (startedAt != null && state.value.showSkeleton) {
                val visibleFor = System.currentTimeMillis() - startedAt
                if (visibleFor < minVisibleMs) {
                    delay(minVisibleMs - visibleFor)
                }
            }
            becameVisibleAtMs = null
            state.value = DelayedSkeletonState(
                showSkeleton = false,
                showSlowHint = false,
                timedOut = false
            )
        }
    }

    return state
}
