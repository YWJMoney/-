package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.schedule.TeamRoster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [CREW-01] Each calendar's teams for 모든 조 보기 (names, how far behind mine, which is mine), on this
 * phone and in the backup. A calendar without a saved roster gets one suggested from its rotation.
 */
class TeamStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _rosters = MutableStateFlow(read())
    val rosters: StateFlow<Map<String, TeamRoster>> = _rosters.asStateFlow()

    private fun read(): Map<String, TeamRoster> =
        prefs.all.mapNotNull { (k, v) -> TeamRoster.decode(v as? String)?.let { k to it } }.toMap()

    fun get(calendarId: String): TeamRoster? = _rosters.value[calendarId]

    fun set(calendarId: String, roster: TeamRoster?) {
        prefs.edit().apply { if (roster == null) remove(calendarId) else putString(calendarId, roster.encode()) }.apply()
        _rosters.value = read()
    }

    /** For the backup: calendar id → encoded roster. */
    fun export(): Map<String, String> = _rosters.value.mapValues { it.value.encode() }

    fun import(all: Map<String, String>) {
        prefs.edit().clear().apply { all.forEach { (k, v) -> if (TeamRoster.decode(v) != null) putString(k, v) } }.apply()
        _rosters.value = read()
    }

    companion object {
        const val PREFS = "team_rosters"
    }
}
