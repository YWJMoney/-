package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.bamnat.core.money.CaptureFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LEDG-22] 결제 알림으로 적기 as the user tuned it: apps left out, words that drop a text, the
 * smallest amount written, and the apps that sent a payment notification lately (to choose from).
 * [LEDG-24] Also the places the user said are not a monthly payment. All of it stays on the phone.
 */
class AutoRecordPrefs(context: Context) {
    data class State(
        val filter: CaptureFilter = CaptureFilter(),
        /** Package → the app's name, apps that sent a notification read as a payment. */
        val seenApps: Map<String, String> = emptyMap(),
        val notRepeating: Set<String> = emptySet(),
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("auto_record", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    fun setFilter(filter: CaptureFilter) = edit {
        putStringSet(K_BLOCKED, filter.blockedApps)
        putString(K_WORDS, filter.words.map { CaptureFilter.clean(it) }.filter { it.isNotEmpty() }.distinct().take(CaptureFilter.MAX_WORDS).joinToString("\n"))
        putLong(K_MIN, filter.minAmount.coerceAtLeast(0))
    }

    /** Remembered when a payment notification comes; only new apps are written. */
    fun sawApp(pkg: String, label: String) {
        if (current.seenApps[pkg] == label) return
        val next = (current.seenApps + (pkg to label.replace('\t', ' ').replace('\n', ' '))).entries.toList().takeLast(MAX_APPS)
        edit { putStringSet(K_SEEN, next.map { "${it.key}\t${it.value}" }.toSet()) }
    }

    fun notRepeating(key: String) = edit { putStringSet(K_NOT_REPEATING, current.notRepeating + key) }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
            prefs.edit().apply(block).apply()
            _state.value = read()
        }
    }

    private fun read(): State = State(
        filter = CaptureFilter(
            blockedApps = prefs.getStringSet(K_BLOCKED, null).orEmpty().toSet(),
            words = prefs.getString(K_WORDS, null).orEmpty().split("\n").filter { it.isNotBlank() },
            minAmount = prefs.getLong(K_MIN, 0),
        ),
        seenApps = prefs.getStringSet(K_SEEN, null).orEmpty().mapNotNull { line ->
            line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap(),
        notRepeating = prefs.getStringSet(K_NOT_REPEATING, null).orEmpty().toSet(),
    )

    private companion object {
        const val K_BLOCKED = "blocked_apps"
        const val K_WORDS = "drop_words"
        const val K_MIN = "min_amount"
        const val K_SEEN = "seen_apps"
        const val K_NOT_REPEATING = "not_repeating"
        const val MAX_APPS = 30
    }
}
