package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.work.Commute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
        /** The day the last written leave went on, and that day as it was before it. */
        val undoDate: LocalDate? = null,
        val undo: Commute.Undo? = null,
        /** [DAYW-07] The workplace circle last handed to Play services ("lat,lng,radius"), or null. */
        val fence: String? = null,
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
        if (!on) { remove(K_LAT); remove(K_LNG); remove(K_ARRIVED); remove(K_LEFT); remove(K_PENDING); remove(K_UNDO); remove(K_FENCE) }
    }

    fun setPlace(lat: Double, lng: Double) = edit { putString(K_LAT, lat.toString()); putString(K_LNG, lng.toString()) }
    fun setRadius(m: Int) = edit { putInt(K_RADIUS, m.coerceIn(MIN_RADIUS, MAX_RADIUS)) }
    fun setAuto(auto: Boolean) = edit { putBoolean(K_AUTO, auto) }
    fun setCalendar(id: String) = edit { putString(K_CALENDAR, id) }
    fun arrived(at: LocalDateTime) = edit { putString(K_ARRIVED, at.toString()); remove(K_PENDING); remove(K_UNDO) }
    /** [DAYW-07] What a written leave changed on [date], to put back if the phone comes back that day. */
    fun leaveWritten(date: LocalDate, undo: Commute.Undo?) = edit { if (undo == null) remove(K_UNDO) else putString(K_UNDO, encode(date, undo)) }
    /** [DAYW-07] Back the same day: the leave was not 퇴근, so it is forgotten. */
    fun leaveUndone() = edit { remove(K_LEFT); remove(K_UNDO); remove(K_PENDING) }
    fun left(at: LocalDateTime) = edit { putString(K_LEFT, at.toString()); remove(K_PENDING) }
    /** [DAYW-07] 아니요 on an arrival: it was not 출근, so the real one that day can still be taken. */
    fun arrivalCleared(at: LocalDateTime) = edit { if (time(K_ARRIVED) == at) remove(K_ARRIVED) }
    fun fenceRegistered(key: String?) = edit { if (key == null) remove(K_FENCE) else putString(K_FENCE, key) }
    fun leavePending(at: LocalDateTime?) = edit { if (at == null) remove(K_PENDING) else putString(K_PENDING, at.toString()) }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
            prefs.edit().apply(block).apply()
            _state.value = read()
        }
    }

    private fun time(key: String) = prefs.getString(key, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun encode(date: LocalDate, u: Commute.Undo): String = JSONObject()
        .put("date", date.toString())
        .putOpt("cs", u.customStart?.toString())
        .putOpt("ce", u.customEnd?.toString())
        .put("kind", u.kindId)
        .putOpt("extra", u.extra?.let { e ->
            JSONObject().put("kind", e.kindId).putOpt("s", e.start?.toString()).putOpt("e", e.end?.toString()).put("b", e.breakMinutes)
                .putOpt("bs", e.breakStart?.toString()).put("m", e.minutes).putOpt("cov", e.coveredShiftId).put("rev", e.needsReview)
        })
        .toString()

    private fun decode(raw: String?): Pair<LocalDate, Commute.Undo>? = raw?.let {
        runCatching {
            val o = JSONObject(it)
            fun JSONObject.t(k: String) = optString(k).takeIf { v -> v.isNotEmpty() }?.let(LocalTime::parse)
            val extra = o.optJSONObject("extra")?.let { e ->
                ExtraEntry(e.getLong("kind"), e.t("s"), e.t("e"), e.optInt("b"), e.t("bs"), e.optInt("m"),
                    if (e.has("cov")) e.getLong("cov") else null, e.optBoolean("rev"))
            }
            LocalDate.parse(o.getString("date")) to Commute.Undo(o.t("cs"), o.t("ce"), o.getLong("kind"), extra)
        }.getOrNull()
    }

    private fun read(): State {
        val arrived = time(K_ARRIVED)
        val undo = decode(prefs.getString(K_UNDO, null))
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
            undoDate = undo?.first,
            undo = undo?.second,
            fence = prefs.getString(K_FENCE, null),
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
        private const val K_UNDO = "leave_undo"
        private const val K_FENCE = "fence"

        @Volatile private var instance: CommutePrefs? = null

        fun get(context: Context): CommutePrefs = instance ?: synchronized(this) {
            instance ?: CommutePrefs(context).also { instance = it }
        }
    }
}
