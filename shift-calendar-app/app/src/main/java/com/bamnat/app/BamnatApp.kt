package com.bamnat.app

import android.app.Application
import android.content.Context
import com.bamnat.app.alarm.BootAlarms
import com.bamnat.app.alarm.Notifications

class BamnatApp : Application() {
    /** Built on first use: after a reboot the app may start before the phone is unlocked [REL-10]. */
    val container: AppContainer by lazy { AppContainer(this).also { it.start() } }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // While locked the database and settings cannot be opened; alarms ring from BootAlarms then,
        // and the container starts with the first use after unlocking (BOOT_COMPLETED at the latest).
        if (!BootAlarms.isLocked(this)) container
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as BamnatApp).container
