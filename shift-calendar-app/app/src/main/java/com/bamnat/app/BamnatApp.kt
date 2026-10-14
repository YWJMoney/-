package com.bamnat.app

import android.app.Application
import android.content.Context
import com.bamnat.app.alarm.Notifications

class BamnatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
        container.start()
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as BamnatApp).container
