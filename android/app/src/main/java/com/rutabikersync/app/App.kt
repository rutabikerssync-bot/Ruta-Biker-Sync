package com.rutabikersync.app

import android.app.Application
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
        // Alcance de aplicación: SOS y señales de convoy suenan aunque no exista
        // cabina/Compose montada. La cabina solo publica su estado de PTT.
        AppAudioBootstrap.initialize(
            installSosHub = { SosToneHub.ensureInitialized(this) },
            installConvoyHub = { ConvoySignalAudioHub.ensureInitialized(this) }
        )
        applicationScope.launch {
            RideService.sharedState
                .distinctUntilChangedBy { it.isSosActive }
                .collect { state -> SosToneHub.setLocalActive(state.isSosActive) }
        }
        RutaBikerMessagingService.createNotificationChannels(this)
    }

    override fun onTerminate() {
        // Android no garantiza esta callback en dispositivos reales; además se
        // libera al terminar el servicio de rodada (ver RideService.onDestroy).
        ConvoySignalAudioHub.release()
        super.onTerminate()
    }
}
