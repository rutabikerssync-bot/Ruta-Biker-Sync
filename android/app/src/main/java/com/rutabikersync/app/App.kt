package com.rutabikersync.app

import android.app.Application
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
        // Alcance de aplicación: el SOS suena aunque no exista cabina/Compose montada.
        SosToneHub.ensureInitialized(this)
        applicationScope.launch {
            RideService.sharedState
                .distinctUntilChangedBy { it.isSosActive }
                .collect { state -> SosToneHub.setLocalActive(state.isSosActive) }
        }
        RutaBikerMessagingService.createNotificationChannels(this)
    }
}
