package com.bamnat.wear

import com.bamnat.core.work.WatchDay
import com.bamnat.core.work.WatchDays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [SHFT-08] What the complication shows on one day, from [start] (local midnight) to [end]. Kept free of
 * Android so the timeline can be tested on the JVM.
 */
data class ComplicationDay(val start: Instant, val end: Instant, val day: WatchDay?, val tomorrow: WatchDay?) {
    val shortText: String get() = day?.label ?: "-"
    val shortTitle: String get() = if (day == null) "밤낮" else "오늘"
    val longText: String get() = day?.line ?: WatchText.NO_DATA
    val longTitle: String get() = tomorrow?.let { "내일 ${it.line}" } ?: "오늘 근무"
    val description: String get() = day?.let { "오늘 ${it.line}" } ?: WatchText.NO_DATA
}

object ComplicationDays {
    /** How many days ahead the timeline holds; a new copy from the phone (or the midnight alarm) renews it. */
    const val AHEAD = 31

    fun today(days: List<WatchDay>?, today: LocalDate, zone: ZoneId) = day(days, today, zone)

    /**
     * [SHFT-08] The days after [today], each from its local midnight, so the watch face moves on at midnight by
     * itself. Past the last stored day one long entry says "no data" instead of falling back to today's.
     */
    fun ahead(days: List<WatchDay>, today: LocalDate, zone: ZoneId): List<ComplicationDay> {
        val last = days.maxOfOrNull { it.date } ?: return emptyList()
        val n = (last.toEpochDay() - today.toEpochDay()).coerceIn(0, AHEAD.toLong())
        val entries = (1..n).map { day(days, today.plusDays(it), zone) }
        val tailStart = today.plusDays(n + 1).atStartOfDay(zone).toInstant()
        return entries + ComplicationDay(tailStart, tailStart.plusSeconds(365L * 86_400), null, null)
    }

    private fun day(days: List<WatchDay>?, date: LocalDate, zone: ZoneId) = ComplicationDay(
        date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(),
        days?.let { WatchDays.on(it, date) }, days?.let { WatchDays.on(it, date.plusDays(1)) },
    )
}
