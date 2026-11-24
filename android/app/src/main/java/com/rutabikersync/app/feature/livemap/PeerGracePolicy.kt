package com.rutabikersync.app.feature.livemap

/**
 * Gracia visual del HUD (FIX B): cuando un peer supera el timeout se muestra "sin señal"
 * durante ~60 s ([GRACE_MS]) antes de excluirlo del conteo. Durante la gracia sigue sumando en
 * `ridersCount`, de modo que el HUD no cae a 1 por un hueco de radio.
 */
object PeerGracePolicy {
    const val GRACE_MS: Long = 60_000L

    fun isInGrace(nowEpochMs: Long, lostSinceEpochMs: Long): Boolean {
        if (lostSinceEpochMs <= 0L) return false
        val elapsed = nowEpochMs - lostSinceEpochMs
        return elapsed in 0L until GRACE_MS
    }
}
