package com.bamnat.app.alarm

import android.content.Intent
import com.bamnat.core.alarm.PlannedAlarm
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Everything the ringing screen and service need, carried through intents. */
data class RingRequest(
    val shiftName: String,
    val shiftDate: LocalDate,
    val shiftStart: LocalTime,
    val offsetMinutes: Int,
    /** Shift-specific sound; null falls back to the default from settings. */
    val soundUri: String?,
    val triggerAtMillis: Long,
    val isSnooze: Boolean = false,
    /** Set by hand for that date from the calendar. */
    val custom: Boolean = false,
    /** [SCHED-01] A memo's schedule rings under the memo's own words instead of a shift's name. */
    val label: String? = null,
) {
    /** "주간 근무 알람", "알람" for a hand-set alarm on a day without work, or a memo schedule's words. */
    val title: String get() = label ?: if (shiftName.isBlank()) "알람" else "$shiftName 근무 알람"

    fun putInto(intent: Intent): Intent = intent
        .putExtra(X_NAME, shiftName)
        .putExtra(X_DATE, shiftDate.toEpochDay())
        .putExtra(X_START, shiftStart.toSecondOfDay())
        .putExtra(X_OFFSET, offsetMinutes)
        .putExtra(X_SOUND, soundUri)
        .putExtra(X_TRIGGER, triggerAtMillis)
        .putExtra(X_SNOOZE, isSnooze)
        .putExtra(X_CUSTOM, custom)
        .putExtra(X_LABEL, label)

    fun encode(): String = JSONObject()
        .put(X_NAME, shiftName)
        .put(X_DATE, shiftDate.toEpochDay())
        .put(X_START, shiftStart.toSecondOfDay())
        .put(X_OFFSET, offsetMinutes)
        .put(X_SOUND, soundUri ?: JSONObject.NULL)
        .put(X_TRIGGER, triggerAtMillis)
        .put(X_SNOOZE, isSnooze)
        .put(X_CUSTOM, custom)
        .put(X_LABEL, label ?: JSONObject.NULL)
        .toString()

    companion object {
        private const val X_NAME = "ring_name"
        private const val X_DATE = "ring_date"
        private const val X_START = "ring_start"
        private const val X_OFFSET = "ring_offset"
        private const val X_SOUND = "ring_sound"
        private const val X_TRIGGER = "ring_trigger"
        private const val X_SNOOZE = "ring_snooze"
        private const val X_CUSTOM = "ring_custom"
        private const val X_LABEL = "ring_label"

        fun from(plan: PlannedAlarm, triggerAtMillis: Long) = RingRequest(
            shiftName = plan.shiftName,
            shiftDate = plan.shiftDate,
            shiftStart = plan.shiftStart,
            offsetMinutes = plan.offsetMinutes,
            soundUri = plan.soundUri,
            triggerAtMillis = triggerAtMillis,
            custom = plan.custom,
        )

        fun fromIntent(intent: Intent?): RingRequest? {
            if (intent == null || !intent.hasExtra(X_NAME)) return null
            return RingRequest(
                shiftName = intent.getStringExtra(X_NAME) ?: return null,
                shiftDate = LocalDate.ofEpochDay(intent.getLongExtra(X_DATE, 0L)),
                shiftStart = LocalTime.ofSecondOfDay(intent.getIntExtra(X_START, 0).toLong().coerceIn(0, 86_399)),
                offsetMinutes = intent.getIntExtra(X_OFFSET, 0),
                soundUri = intent.getStringExtra(X_SOUND),
                triggerAtMillis = intent.getLongExtra(X_TRIGGER, System.currentTimeMillis()),
                isSnooze = intent.getBooleanExtra(X_SNOOZE, false),
                custom = intent.getBooleanExtra(X_CUSTOM, false),
                label = intent.getStringExtra(X_LABEL),
            )
        }

        fun decode(raw: String?): RingRequest? {
            if (raw.isNullOrEmpty()) return null
            return runCatching {
                val o = JSONObject(raw)
                RingRequest(
                    shiftName = o.getString(X_NAME),
                    shiftDate = LocalDate.ofEpochDay(o.getLong(X_DATE)),
                    shiftStart = LocalTime.ofSecondOfDay(o.getInt(X_START).toLong()),
                    offsetMinutes = o.getInt(X_OFFSET),
                    soundUri = if (o.isNull(X_SOUND)) null else o.getString(X_SOUND),
                    triggerAtMillis = o.getLong(X_TRIGGER),
                    isSnooze = o.optBoolean(X_SNOOZE, false),
                    custom = o.optBoolean(X_CUSTOM, false),
                    label = if (o.isNull(X_LABEL)) null else o.optString(X_LABEL),
                )
            }.getOrNull()
        }
    }
}
