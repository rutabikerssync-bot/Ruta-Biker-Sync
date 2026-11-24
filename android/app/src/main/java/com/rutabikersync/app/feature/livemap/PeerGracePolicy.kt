package com.rutabikersync.app.feature.livemap

/**
 * Gracia visual del HUD (FIX B + Fase 2): cuando un peer supera el timeout se muestra "sin
 * señal" antes de excluirlo del conteo. Durante la gracia sigue sumando en `ridersCount`.
 *
 * Fase 2 deriva la gracia del timeout efectivo del peer (2×, tope 120 s) para absorber
 * cadencias ralentizadas y huecos de recepción sin caer prematuramente a "1 rider".
 * La detección de isla/SOS no depende de esta gracia: sigue gobernada por `isLost`.
 */
object PeerGracePolicy {
    const val GRACE_MS: Long = 60_000L
    const val MAX_GRACE_MS: Long = 120_000L
    const val GRACE_FACTOR: Long = 2L

    fun graceMsFor(timeoutMs: Long): Long =
        maxOf(GRACE_MS, timeoutMs.coerceAtMost(MAX_GRACE_MS) * GRACE_FACTOR)
            .coerceAtMost(MAX_GRACE_MS)

    fun isInGrace(
        nowEpochMs: Long,
        lostSinceEpochMs: Long,
        graceMs: Long = GRACE_MS
    ): Boolean {
        if (lostSinceEpochMs <= 0L) return false
        val elapsed = nowEpochMs - lostSinceEpochMs
        return elapsed in 0L until graceMs
    }
}
