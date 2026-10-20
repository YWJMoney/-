package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [DAYW-07] 출퇴근 자동 기록 as the user set it: off unless turned on, the one workplace point (latitude,
 * longitude, radius) and the calendar it writes to, whether it asks first, and today's arrival and a leave
 * waiting to be sure. Nothing else about where the phone is, is kept; all of it stays on the phone (no
 * backup either: a point on a map does not belong in a file that travels).
 */
class CommutePrefs private constructor(context: Context) {
    data class State(
        val enabled: Boolean = false,
        val calendarId: String = PersonalCalendar.MAIN,
        val lat: Double? = null,
        val lng: Double? = null,
        val radius: Int = DEFAULT_RADIUS,
        /** True writes at once; false asks with 맞아요·아니요 first. */
        val auto: Boolean = false,
        val arrivedOn: LocalDate? = null,
        val arrivedAt: LocalDateTime? = null,
        val leftAt: LocalDateTime? = null,
        /** A leave seen, written once the phone does not come back within the wait (a walk at lunch). */
        val pendingLeave: LocalDateTime? = null,
    ) {
        val hasPlace: Boolean get() = lat != null && lng != null
        /** Today's arrival, when there is one. */
        fun arrivalOn(date: LocalDate): LocalDateTime? = arrivedAt?.takeIf { arrivedOn == date }
    }

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("commute", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    fun setEnabled(on: Boolean) = edit {
        putBoolean(K_ON, on)
        // Turned off, the workplace point goes too.
        if (!on) { remove(K_LAT); remove(K_LNG); remove(K_ARRIVED); remove(K_LEFT); remove(K_PENDING) }
    }

    fun setPlace(lat: Double, lng: Double) = edit { putString(K_LAT, lat.toString()); putString(K_LNG, lng.toString()) }
    fun setRadius(m: Int) = edit { putInt(K_RADIUS, m.coerceIn(MIN_RADIUS, MAX_RADIUS)) }
    fun setAuto(auto: Boolean) = edit { putBoolean(K_AUTO, auto) }
    fun setCalendar(id: String) = edit { putString(K_CALENDAR, id) }
    fun arrived(at: LocalDateTime) = edit { putString(K_ARRIVED, at.toString()); remove(K_PENDING) }
    fun left(at: LocalDateTime) = edit { putString(K_LEFT, at.toString()); remove(K_PENDING) }
    fun leavePending(at: LocalDateTime?) = edit { if (at == null) remove(K_PENDING) else putString(K_PENDING, at.toString()) }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
            prefs.edit().apply(block).apply()
            _state.value = read()
        }
    }

    private fun time(key: String) = prefs.getString(key, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun read(): State {
        val arrived = time(K_ARRIVED)
        return State(
            enabled = prefs.getBoolean(K_ON, false),
            calendarId = prefs.getString(K_CALENDAR, null) ?: PersonalCalendar.MAIN,
            lat = prefs.getString(K_LAT, null)?.toDoubleOrNull(),
            lng = prefs.getString(K_LNG, null)?.toDoubleOrNull(),
            radius = prefs.getInt(K_RADIUS, DEFAULT_RADIUS),
            auto = prefs.getBoolean(K_AUTO, false),
            arrivedOn = arrived?.toLocalDate(),
            arrivedAt = arrived,
            leftAt = time(K_LEFT),
            pendingLeave = time(K_PENDING),
        )
    }

    companion object {
        const val DEFAULT_RADIUS = 150
        const val MIN_RADIUS = 100
        const val MAX_RADIUS = 500
        private const val K_ON = "on"
        private const val K_CALENDAR = "calendar"
        private const val K_LAT = "lat"
        private const val K_LNG = "lng"
        private const val K_RADIUS = "radius"
        private const val K_AUTO = "auto"
        private const val K_ARRIVED = "arrived"
        private const val K_LEFT = "left"
        private const val K_PENDING = "pending_leave"

        @Volatile private var instance: CommutePrefs? = null

        fun get(context: Context): CommutePrefs = instance ?: synchronized(this) {
            instance ?: CommutePrefs(context).also { instance = it }
        }
    }
}
