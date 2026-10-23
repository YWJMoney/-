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
    /**
     * [NAV-01] Time away after which coming back counts as opening the app again. Shorter than this
     * (a look at a message, a call), the user is put back exactly where they were.
     */
    const val AWAY_MILLIS = 30 * 60_000L

    private val _count = MutableStateFlow(0)
    /** Increments on every request; the UI acts on changes. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** The last request the navigation followed. */
    var handled = 0

    private var processStarted = false
    private var stoppedAt = 0L
    private var stoppedOn: java.time.LocalDate? = null

    /** Today's date; a test moves it. */
    internal var today: () -> java.time.LocalDate = { java.time.LocalDate.now() }

    /**
     * True while a screen the app opened itself (file picker, permission page) is in front: coming
     * back from it is not opening the app again, and its result still needs the screen that asked.
     */
    var awaitingResult = false

    /**
     * [NAV-01] Saved state in a fresh process means Android brought the app back after closing it in
     * the background: the last screen stays when the user left it a short while ago ([leftAt], on
     * the [SystemClock.elapsedRealtime] clock), else the calendar.
     */
    fun onCreate(restoring: Boolean, awaitingResult: Boolean = false, leftAt: Long = 0L) {
        if (restoring && !processStarted && !awaitingResult && !leftRecently(leftAt)) request()
        processStarted = true
        this.awaitingResult = awaitingResult
    }

    /** [NAV-01] Left less than [AWAY_MILLIS] before [now]; a time from before a restart is not. */
    internal fun leftRecently(leftAt: Long, now: Long = SystemClock.elapsedRealtime()): Boolean =
        leftAt > 0 && now - leftAt in 0 until AWAY_MILLIS

    /**
     * When the app went to the background, kept with the saved screen state. Before Android 9 the
     * state is saved just before onStop, so then it is now.
     */
    val leftAt: Long get() = stoppedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()

    fun onStop(changingConfigurations: Boolean) {
        stoppedAt = if (changingConfigurations) 0L else SystemClock.elapsedRealtime()
        stoppedOn = if (changingConfigurations) null else today()
    }

    fun onStart() {
        // [285] A new day is opening the app again too, however short the time away (23:55 → 00:05).
        val newDay = stoppedOn != null && stoppedOn != today()
        if (stoppedAt > 0 && !awaitingResult && (SystemClock.elapsedRealtime() - stoppedAt >= AWAY_MILLIS || newDay)) request()
        stoppedAt = 0L
        stoppedOn = null
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
