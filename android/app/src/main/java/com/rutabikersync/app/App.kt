package com.rutabikersync.app

import android.app.Application
import com.rutabikersync.app.core.network.RutaBikerMessagingService

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RutaBikerMessagingService.createNotificationChannels(this)
    }
}
