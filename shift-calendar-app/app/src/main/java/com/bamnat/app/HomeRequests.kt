package com.bamnat.app

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

    /**
     * True while a screen the app opened itself (file picker, permission page) is in front: coming
     * back from it is not opening the app again, and its result still needs the screen that asked.
     */
    var awaitingResult = false

    fun onCreate(restoring: Boolean, awaitingResult: Boolean = false) {
        // Saved state in a fresh process means Android restored the last screen; show the calendar.
        if (restoring && !processStarted && !awaitingResult) request()
        processStarted = true
        this.awaitingResult = awaitingResult
    }

    fun onStop(changingConfigurations: Boolean) {
        stoppedAt = if (changingConfigurations) 0L else SystemClock.elapsedRealtime()
    }

    fun onStart() {
        if (stoppedAt > 0 && !awaitingResult && SystemClock.elapsedRealtime() - stoppedAt >= AWAY_MILLIS) request()
        stoppedAt = 0L
        awaitingResult = false
    }

    fun request() {
        _count.value += 1
    }

    /** [SLP-15] The app comes forward for a tapped notice, which says where to go. */
    fun followingLink() {
        stoppedAt = 0L
    }
}
