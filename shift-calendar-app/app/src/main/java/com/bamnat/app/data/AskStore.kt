package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.profile.AskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate

/**
 * [EASY-05] What the question card remembers ([AskState]), per calendar: its own preferences file, so the
 * questions of one phone are not carried to another (the facts themselves are, in their own stores).
 */
class AskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ask", Context.MODE_PRIVATE)
    private val _states = MutableStateFlow(prefs.all.keys.associateWith { decode(prefs.getString(it, null)) })
    val states: StateFlow<Map<String, AskState>> = _states.asStateFlow()

    fun of(calendarId: String): AskState = _states.value[calendarId] ?: AskState()

    fun set(calendarId: String, state: AskState) {
        prefs.edit().putString(calendarId, encode(state).toString()).apply()
        _states.value = _states.value + (calendarId to state)
    }

    /** [EASY-09] 모두 처음부터. */
    fun clear(calendarId: String) {
        prefs.edit().remove(calendarId).apply()
        _states.value = _states.value - calendarId
    }

    companion object {
        fun encode(s: AskState): JSONObject = JSONObject()
            .putOpt("day", s.day?.toString()).put("n", s.shownToday).putOpt("cur", s.current)
            .put("until", JSONObject(s.snoozedUntil.mapValues { it.value.toString() }))
            .put("later", JSONObject(s.snoozes))

        fun decode(text: String?): AskState = runCatching {
            val o = JSONObject(text!!)
            val until = o.optJSONObject("until")
            val later = o.optJSONObject("later")
            AskState(
                day = o.optString("day").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                shownToday = o.optInt("n"),
                current = o.optString("cur").takeIf { it.isNotEmpty() },
                snoozedUntil = until?.keys()?.asSequence()?.associateWith { LocalDate.parse(until.getString(it)) }.orEmpty(),
                snoozes = later?.keys()?.asSequence()?.associateWith { later.getInt(it) }.orEmpty(),
            )
        }.getOrDefault(AskState())
    }
}
