package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.example.BrowseState
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * [EASY-01] Whether the user is looking around with the example calendar, and whether they chose to keep it
 * (예시는 두고 새 달력에서 시작). Its own preferences file, not `settings.xml`, so no backup carries it: a phone
 * restored elsewhere starts without an example.
 */
class BrowseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<BrowseState> = _state.asStateFlow()
    val current: BrowseState get() = _state.value

    /** The example stays as a calendar of its own after the user started with their own. */
    var kept: Boolean
        get() = prefs.getBoolean(K_KEPT, false)
        set(value) { prefs.edit().putBoolean(K_KEPT, value).apply() }

    fun set(next: BrowseState) {
        prefs.edit()
            .putBoolean(K_ON, next.browsing)
            .putString(K_STYLE, next.style?.name)
            .putString(K_SINCE, next.since?.toString())
            .putString(K_KEEP, next.keepBrowsingOn?.toString())
            .apply()
        _state.value = next
    }

    private fun read(): BrowseState = BrowseState(
        browsing = prefs.getBoolean(K_ON, false),
        style = prefs.getString(K_STYLE, null)?.let { s -> WorkStyle.entries.firstOrNull { it.name == s } },
        since = prefs.getString(K_SINCE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        keepBrowsingOn = prefs.getString(K_KEEP, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    private companion object {
        const val K_ON = "on"
        const val K_STYLE = "style"
        const val K_SINCE = "since"
        const val K_KEEP = "keep_on"
        const val K_KEPT = "kept"
    }
}
