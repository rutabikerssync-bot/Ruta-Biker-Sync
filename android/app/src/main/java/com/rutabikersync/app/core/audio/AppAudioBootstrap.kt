package com.rutabikersync.app.core.audio

/**
 * Arranque único de los hubs acústicos de la aplicación (P1).
 *
 * Se mantiene separado de [com.rutabikersync.app.App] para poder comprobar en un
 * test JVM que el arranque instala exactamente un hub de SOS y uno de señales de
 * convoy, sin depender de un `Application` de Android ni de Robolectric.
 */
object AppAudioBootstrap {

    fun initialize(
        installSosHub: () -> Unit,
        installConvoyHub: () -> Unit
    ) {
        installSosHub()
        installConvoyHub()
    }
}
