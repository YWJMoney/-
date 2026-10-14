package com.shiftmate.calendar

import android.app.Application
import android.content.Context
import com.shiftmate.calendar.alarm.Notifications

class ShiftMateApp : Application() {
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
    get() = (applicationContext as ShiftMateApp).container
