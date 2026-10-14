package com.shiftmate.calendar

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app should open on the calendar. A restored process or a return after a while away asks
 * for it here, and the navigation goes back to the calendar tab.
 */
object HomeRequests {
    /** Time away after which coming back counts as opening the app again. */
    const val AWAY_MILLIS = 60_000L

    private val _count = MutableStateFlow(0)
    /** Increments on every request; the UI acts on changes. */
    val count: StateFlow<Int> = _count.asStateFlow()

    private var processStarted = false
    private var stoppedAt = 0L

    fun onCreate(restoring: Boolean) {
        // Saved state in a fresh process means Android restored the last screen; show the calendar.
        if (restoring && !processStarted) request()
        processStarted = true
    }

    fun onStop(changingConfigurations: Boolean) {
        stoppedAt = if (changingConfigurations) 0L else SystemClock.elapsedRealtime()
    }

    fun onStart() {
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= AWAY_MILLIS) request()
        stoppedAt = 0L
    }

    fun request() {
        _count.value += 1
    }
}
