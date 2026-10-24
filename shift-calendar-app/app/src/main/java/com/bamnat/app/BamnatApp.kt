package com.bamnat.app

import android.app.Application
import android.content.Context
import com.bamnat.app.alarm.BootAlarms
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.diag.CrashLog

class BamnatApp : Application() {
    /** Built on first use: after a reboot the app may start before the phone is unlocked [REL-10]. */
    val container: AppContainer by lazy { AppContainer(this).also { it.start(); StartupTrace.mark(StartupTrace.Step.CONTAINER) } }
    @Volatile private var building: Thread? = null

    override fun onCreate() {
        StartupTrace.begin()
        super.onCreate()
        CrashLog.install(this)
        watchMainThread()
        channelsOnce()
        // While locked the database and settings cannot be opened; alarms ring from BootAlarms then,
        // and the container starts with the first use after unlocking (BOOT_COMPLETED at the latest).
        // [PERF-06] 364 (검토 A1): built on a thread of its own, so its files are read while the main thread
        // makes the window; the first use on the main thread waits for it (lazy is synchronized).
        if (!BootAlarms.isLocked(this)) building = Thread({ container }, "bamnat-container").apply { start() }
        StartupTrace.mark(StartupTrace.Step.APP)
    }

    /**
     * [PERF-03] 324-2 (검토 A7): in a debug build the main thread says when it reads or writes the disk or the
     * network, in Logcat under StrictMode. Only a log: a tester's phone keeps working.
     */
    private fun watchMainThread() {
        if (!BuildConfig.DEBUG || isRobolectric) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
        )
    }

    /**
     * [PERF-03] 324-2 (검토 A5): the notification channels are made when this version of the app first starts,
     * not at every start (that is several calls to the system). Only the app itself can delete a channel, and clearing
     * the app's data clears the mark with them.
     */
    private fun channelsOnce() {
        // Before the first unlock the app's own storage cannot be read: the channels are made, as before.
        if (BootAlarms.isLocked(this)) return Notifications.createChannels(this)
        val prefs = getSharedPreferences(APP_STATE, MODE_PRIVATE)
        if (prefs.getInt(K_CHANNELS_FOR, 0) == BuildConfig.VERSION_CODE) return
        Notifications.createChannels(this)
        prefs.edit().putInt(K_CHANNELS_FOR, BuildConfig.VERSION_CODE).apply()
    }

    /**
     * [QA-04] Never called on a phone (Android ends the process instead); Robolectric calls it when a test
     * ends. The app's background jobs stop with it, so they cannot act on the next test's data: an earlier
     * test's link sync or phone-calendar sync landing in a later test made ShareLinksProTest and
     * PhoneCalendarTest fail only in the whole-app run.
     */
    override fun onTerminate() {
        building?.join()
        if (containerStarted()) container.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onTerminate()
    }

    private fun containerStarted() = (this::class.java.getDeclaredField("container\$delegate")
        .apply { isAccessible = true }.get(this) as Lazy<*>).isInitialized()
}

private const val APP_STATE = "app_state"
private const val K_CHANNELS_FOR = "channels_for"
private val isRobolectric = android.os.Build.FINGERPRINT == "robolectric"

val Context.appContainer: AppContainer
    get() = (applicationContext as BamnatApp).container
