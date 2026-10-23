package com.bamnat.app

import android.app.Application
import android.content.Context
import com.bamnat.app.alarm.BootAlarms
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.diag.CrashLog

class BamnatApp : Application() {
    /** Built on first use: after a reboot the app may start before the phone is unlocked [REL-10]. */
    val container: AppContainer by lazy { AppContainer(this).also { it.start(); StartupTrace.mark(StartupTrace.Step.CONTAINER) } }

    override fun onCreate() {
        StartupTrace.begin()
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        // While locked the database and settings cannot be opened; alarms ring from BootAlarms then,
        // and the container starts with the first use after unlocking (BOOT_COMPLETED at the latest).
        if (!BootAlarms.isLocked(this)) container
        StartupTrace.mark(StartupTrace.Step.APP)
    }

    /**
     * [QA-04] Never called on a phone (Android ends the process instead); Robolectric calls it when a test
     * ends. The app's background jobs stop with it, so they cannot act on the next test's data: an earlier
     * test's link sync or phone-calendar sync landing in a later test made ShareLinksProTest and
     * PhoneCalendarTest fail only in the whole-app run.
     */
    override fun onTerminate() {
        if (containerStarted()) container.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onTerminate()
    }

    private fun containerStarted() = (this::class.java.getDeclaredField("container\$delegate")
        .apply { isAccessible = true }.get(this) as Lazy<*>).isInitialized()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as BamnatApp).container
