package com.rutabikersync.app.core.audio

import android.util.Log

/**
 * Arranque único de los hubs acústicos de la aplicación (P0-A1, FASE 2).
 *
 * Separado de [com.rutabikersync.app.App] para poder comprobar en un test JVM
 * que el arranque instala exactamente un hub de SOS y uno de señales de convoy,
 * sin instanciar un `Application` de Android ni usar Robolectric.
 */
object AppAudioBootstrap {

    fun initialize(
        installSosHub: () -> Unit,
        installConvoyHub: () -> Unit
    ) {
        installSosHub()
        installConvoyHub()
        Log.i("AppAudioBootstrap", "hub de audio inicializado con alcance de aplicacion")
    }
}
