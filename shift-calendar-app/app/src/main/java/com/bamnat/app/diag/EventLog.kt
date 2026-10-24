package com.bamnat.app.diag

import android.content.Context
import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.EventBook
import java.io.File
import com.bamnat.core.time.Today

/**
 * On-device event log for alarm diagnostics and usage counts [MEAS-03, MEAS-04].
 * A small text file in app storage; it never leaves the phone unless the user copies it.
 * Excluded from cloud backup (see res/xml/backup_rules.xml).
 */
class EventLog(context: Context) {
    private val file = File(context.filesDir, "diag/events.log")
    /** File work runs here, in order, so logging a screen change never touches the disk on the main thread. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "bamnat-eventlog").apply { isDaemon = true } }

    fun append(name: String, detail: String = "", atMillis: Long = Today.millis()) {
        io.execute {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(EventBook.encode(DiagEvent(atMillis, name, detail)) + "\n")
                appendsSinceTrim++
                // Counting lines on every write would be wasteful; check now and then.
                if (appendsSinceTrim >= 50) {
                    appendsSinceTrim = 0
                    EventBook.trimmed(file.readLines())?.let { kept -> file.writeText(kept.joinToString("\n", postfix = "\n")) }
                }
            }
        }
    }

    /** Waits for writes already queued, so what was just logged is in the result. */
    fun read(): List<DiagEvent> = io.submit<List<DiagEvent>> {
        runCatching { if (file.exists()) EventBook.decodeAll(file.readText()) else emptyList() }.getOrDefault(emptyList())
    }.get()

    fun clear() {
        io.submit { runCatching { file.delete() } }.get()
    }

    private var appendsSinceTrim = 0
}
