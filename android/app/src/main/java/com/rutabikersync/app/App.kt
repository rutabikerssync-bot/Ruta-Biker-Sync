package com.rutabikersync.app

import android.app.Application
import android.util.Log
import com.rutabikersync.app.core.audio.AppAudioBootstrap
import com.rutabikersync.app.core.audio.ConvoySignalAudioHub
import com.rutabikersync.app.core.audio.SosToneHub
import com.rutabikersync.app.core.mesh.RideService
import com.rutabikersync.app.core.network.RutaBikerMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Alcance de aplicación: SOS y señales de convoy suenan aunque no exista cabina montada.
        AppAudioBootstrap.initialize(
            installSosHub = { SosToneHub.ensureInitialized(this) },
            installConvoyHub = { com.rutabikersync.app.core.audio.ConvoySignalAudioHub.ensureInitialized(this) }
        )
        com.rutabikersync.app.core.mesh.sos.SosSigningHub.initialize(this)
        // P0 auditoría 2026-11-25: caché cifrada del manifiesto para arrancar sin red.
        com.rutabikersync.app.core.crypto.RideManifestKeyProvider.initialize(this)
        applicationScope.launch {
            RideService.sharedState
                .distinctUntilChangedBy { it.isSosActive }
                .collect { state -> SosToneHub.setLocalActive(state.isSosActive) }
        }
        RutaBikerMessagingService.createNotificationChannels(this)
    }

    override fun onTerminate() {
        Log.i("AppAudioBootstrap", "liberando hub de audio de aplicacion")
        ConvoySignalAudioHub.release()
        super.onTerminate()
    }
}
