package com.bamnat.core.alarm

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayAlarm
import java.time.LocalTime

/**
 * Stores alarm lists in one text column: items separated by ';', each "value:1" (on) or
 * "value:0" (off). Shift alarms store minutes before the start, day alarms minutes after midnight.
 * An empty list is written as [NONE] so it is not mistaken for "never saved".
 */
object AlarmCodec {
    const val NONE = "-"

    fun encodeSpecs(list: List<AlarmSpec>): String =
        if (list.isEmpty()) NONE else list.joinToString(";") { "${it.offsetMinutes}:${if (it.enabled) 1 else 0}" }

    /** Null when [raw] holds nothing saved yet (a database row from before multiple alarms). */
    fun decodeSpecs(raw: String?): List<AlarmSpec>? {
        if (raw.isNullOrBlank()) return null
        if (raw == NONE) return emptyList()
        return items(raw).map { (v, on) -> AlarmSpec(v.coerceIn(0, AlarmPlanner.MAX_OFFSET_MINUTES), on) }
    }

    fun encodeDayAlarms(list: List<DayAlarm>?): String? = when {
        list == null -> null
        list.isEmpty() -> NONE
        else -> list.joinToString(";") { "${it.time.hour * 60 + it.time.minute}:${if (it.enabled) 1 else 0}" }
    }

    fun decodeDayAlarms(raw: String?): List<DayAlarm>? {
        if (raw.isNullOrBlank()) return null
        if (raw == NONE) return emptyList()
        return items(raw).map { (v, on) ->
            val m = v.coerceIn(0, 24 * 60 - 1)
            DayAlarm(LocalTime.of(m / 60, m % 60), on)
        }
    }

    private fun items(raw: String): List<Pair<Int, Boolean>> =
        raw.split(';').mapNotNull { item ->
            val parts = item.split(':')
            val value = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            value to (parts.getOrNull(1)?.trim() != "0")
        }
}
