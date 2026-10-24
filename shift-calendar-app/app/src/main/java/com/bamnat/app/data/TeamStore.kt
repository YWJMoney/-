package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.schedule.CoverFinder
import com.bamnat.core.schedule.TeamRoster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [CREW-01] Each calendar's teams for 모든 조 보기 (names, how far behind mine, which is mine), on this
 * phone and in the backup. A calendar without a saved roster gets one suggested from its rotation.
 * [CREW-04] Each calendar's rest rule for 대근 찾기 sits beside it under "<id>#rest".
 */
class TeamStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _rosters = MutableStateFlow(read())
    val rosters: StateFlow<Map<String, TeamRoster>> = _rosters.asStateFlow()
    private val _rest = MutableStateFlow(readRest())
    /** Calendar id → minutes of rest a team needs around a 대근; missing means [CoverFinder.DEFAULT_REST_MINUTES]. */
    val rest: StateFlow<Map<String, Int>> = _rest.asStateFlow()

    private fun read(): Map<String, TeamRoster> =
        prefs.all.filterKeys { !it.endsWith(REST) }.mapNotNull { (k, v) -> TeamRoster.decode(v as? String)?.let { k to it } }.toMap()

    private fun readRest(): Map<String, Int> =
        prefs.all.filterKeys { it.endsWith(REST) }.mapNotNull { (k, v) -> restOf(v as? String)?.let { k.removeSuffix(REST) to it } }.toMap()

    fun get(calendarId: String): TeamRoster? = _rosters.value[calendarId]

    fun set(calendarId: String, roster: TeamRoster?) {
        prefs.edit().apply { if (roster == null) remove(calendarId) else putString(calendarId, roster.encode()) }.apply()
        _rosters.value = read()
    }

    fun restMinutes(calendarId: String): Int = _rest.value[calendarId] ?: CoverFinder.DEFAULT_REST_MINUTES

    fun setRest(calendarId: String, minutes: Int) {
        val m = minutes.coerceIn(CoverFinder.MIN_REST_SETTING, CoverFinder.MAX_REST_SETTING)
        prefs.edit().apply {
            if (m == CoverFinder.DEFAULT_REST_MINUTES) remove(calendarId + REST) else putString(calendarId + REST, m.toString())
        }.apply()
        _rest.value = readRest()
    }

    /** For the backup: calendar id → encoded roster, and "<id>#rest" → minutes. */
    fun export(): Map<String, String> =
        _rosters.value.mapValues { it.value.encode() } + _rest.value.map { (k, v) -> k + REST to v.toString() }

    fun import(all: Map<String, String>) {
        prefs.edit().clear().apply {
            all.forEach { (k, v) ->
                if (k.endsWith(REST)) { if (restOf(v) != null) putString(k, v) }
                else if (TeamRoster.decode(v) != null) putString(k, v)
            }
        }.apply()
        _rosters.value = read()
        _rest.value = readRest()
    }

    companion object {
        const val PREFS = "team_rosters"
        const val REST = "#rest"

        private fun restOf(v: String?): Int? =
            v?.toIntOrNull()?.takeIf { it in CoverFinder.MIN_REST_SETTING..CoverFinder.MAX_REST_SETTING }
    }
}
