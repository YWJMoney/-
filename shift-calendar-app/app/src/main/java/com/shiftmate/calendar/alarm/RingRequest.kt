package com.shiftmate.calendar.alarm

import android.content.Intent
import com.shiftmate.core.alarm.PlannedAlarm
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
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(X_NAME, shiftName)
        .putExtra(X_DATE, shiftDate.toEpochDay())
        .putExtra(X_START, shiftStart.toSecondOfDay())
        .putExtra(X_OFFSET, offsetMinutes)
        .putExtra(X_SOUND, soundUri)
        .putExtra(X_TRIGGER, triggerAtMillis)
        .putExtra(X_SNOOZE, isSnooze)

    fun encode(): String = JSONObject()
        .put(X_NAME, shiftName)
        .put(X_DATE, shiftDate.toEpochDay())
        .put(X_START, shiftStart.toSecondOfDay())
        .put(X_OFFSET, offsetMinutes)
        .put(X_SOUND, soundUri ?: JSONObject.NULL)
        .put(X_TRIGGER, triggerAtMillis)
        .put(X_SNOOZE, isSnooze)
        .toString()

    companion object {
        private const val X_NAME = "ring_name"
        private const val X_DATE = "ring_date"
        private const val X_START = "ring_start"
        private const val X_OFFSET = "ring_offset"
        private const val X_SOUND = "ring_sound"
        private const val X_TRIGGER = "ring_trigger"
        private const val X_SNOOZE = "ring_snooze"

        fun from(plan: PlannedAlarm, triggerAtMillis: Long) = RingRequest(
            shiftName = plan.shiftName,
            shiftDate = plan.shiftDate,
            shiftStart = plan.shiftStart,
            offsetMinutes = plan.offsetMinutes,
            soundUri = plan.soundUri,
            triggerAtMillis = triggerAtMillis,
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
                )
            }.getOrNull()
        }
    }
}
