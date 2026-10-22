package com.bamnat.app

/**
 * [PERF-01] When each step of a cold start first happened, in milliseconds from the app's start. Kept in
 * memory only, nothing is written or sent: the startup budget test reads it, and on a phone the same steps
 * end in Android's "Fully drawn" line (the calendar reports itself drawn once its month is there).
 */
object StartupTrace {
    enum class Step { APP, CONTAINER, ACTIVITY, CALENDAR }

    @Volatile private var origin = System.nanoTime()
    private val at = java.util.concurrent.ConcurrentHashMap<Step, Long>()

    /** Called first thing in [BamnatApp.onCreate]: a new process, so a new start. */
    fun begin() {
        origin = System.nanoTime()
        at.clear()
    }

    /** Notes [step] the first time it happens; later calls (a fold, a second window) leave it. */
    fun mark(step: Step) {
        at.putIfAbsent(step, (System.nanoTime() - origin) / 1_000_000)
    }

    /** Milliseconds from the start to [step], or null when it has not happened yet. */
    fun millis(step: Step): Long? = at[step]
}
