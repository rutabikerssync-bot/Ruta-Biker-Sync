package com.rutabikersync.app.feature.livemap

/**
 * Regla pura de disponibilidad del PTT en cabina: requiere rodada/relay activos.
 */
object PttCabinaGate {
    const val UNAVAILABLE_MESSAGE = "Inicia la rodada para activar el PTT"

    fun resolveAction(
        isRecording: Boolean,
        isRideMeshActive: Boolean,
        onStop: () -> Unit,
        onStart: () -> Unit
    ): String? {
        if (isRecording) {
            onStop()
            return null
        }
        if (!isRideMeshActive) {
            return UNAVAILABLE_MESSAGE
        }
        onStart()
        return null
    }
}
