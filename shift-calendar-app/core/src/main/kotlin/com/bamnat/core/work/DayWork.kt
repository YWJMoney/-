package com.bamnat.core.work

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Pure helpers for day workers (일근): a calendar of exceptions, 지금 퇴근, 징검다리 연휴 and the 시간외 상한. */
object DayWork {
    /**
     * [DAYW-01] A working day that just follows the weekly pattern: no change by hand, no own times,
     * no leave and no extra work. A DAY calendar leaves its badge out, so only the exceptions show.
     */
    fun isRoutine(day: ResolvedDay): Boolean =
        day.isWorkDay && day.source == DaySource.PATTERN && !day.hasCustomTimes && day.leave == null && day.extras.isEmpty() &&
            day.entry?.customBreakMinutes == null

    /** [DAYW-03] What 지금 퇴근 does to the day. */
    sealed interface LeaveNow {
        /** Past the end: extra work (잔업) from the shift's end to now. */
        data class Overtime(val entry: ExtraEntry, val minutes: Int) : LeaveNow
        /** Before the end: the day ends now instead. */
        data class EarlyEnd(val start: LocalTime, val end: LocalTime) : LeaveNow
        /** At the end (within the rounding), or no shift with times today: nothing to write. */
        data object OnTime : LeaveNow
    }

    /** [DAYW-03] Minutes 지금 퇴근 rounds to. */
    const val LEAVE_STEP_MINUTES = 10

    /**
     * [DAYW-03] Leaving at [now] on [day]: after the shift's end it is 잔업 ([kindId], built-in 잔업 by
     * default) from the end to now, before it the day ends at now; both rounded to [LEAVE_STEP_MINUTES].
     */
    fun leaveNow(day: ResolvedDay, now: LocalDateTime, kindId: Long = ExtraKind.OVERTIME_ID): LeaveNow {
        val from = day.shiftFrom ?: return LeaveNow.OnTime
        val to = day.shiftTo ?: return LeaveNow.OnTime
        if (!day.isWorkDay) return LeaveNow.OnTime
        val at = round(now)
        return when {
            at.isAfter(to) -> {
                val minutes = Duration.between(to, at).toMinutes().toInt().coerceAtMost(16 * 60)
                LeaveNow.Overtime(ExtraEntry(kindId, start = to.toLocalTime(), end = to.plusMinutes(minutes.toLong()).toLocalTime()), minutes)
            }
            at.isBefore(to) && at.isAfter(from) -> LeaveNow.EarlyEnd(from.toLocalTime(), at.toLocalTime())
            else -> LeaveNow.OnTime
        }
    }

    /** To the nearest [LEAVE_STEP_MINUTES]. */
    fun round(t: LocalDateTime): LocalDateTime {
        val base = t.truncatedTo(ChronoUnit.HOURS)
        val m = Duration.between(base, t).seconds / 60.0
        val steps = Math.round(m / LEAVE_STEP_MINUTES)
        return base.plusMinutes(steps * LEAVE_STEP_MINUTES)
    }

    /** [DAYW-04] A 징검다리 연휴: [leave] days off work that make [from]..[to] one run of days off. */
    data class Bridge(val leave: List<LocalDate>, val from: LocalDate, val to: LocalDate, val holidays: List<String>) {
        val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
        /** "연차 1일로 4일 쉬어요". */
        val text: String get() = "연차 ${leave.size}일로 ${days}일 쉬어요"
    }

    /** [DAYW-04] Fewest days in a row worth suggesting. */
    const val MIN_BRIDGE_DAYS = 4

    /**
     * [DAYW-04] Bridges from [from] for [months] months: 1 or 2 days of leave on working days next to a
     * 공휴일 that make [MIN_BRIDGE_DAYS] or more days off in a row with it (the weekly days off and other
     * holidays count). For each holiday's run of days off the best 1-day choice is kept (longest, then one
     * that joins two runs of days off), and a 2-day choice only when it gives at least 2 days more, or when
     * one day is not enough. Earliest first.
     */
    fun bridges(
        from: LocalDate,
        months: Long = 12,
        isWorkDay: (LocalDate) -> Boolean,
        holidayName: (LocalDate) -> String? = KoreanHolidays::nameOf,
    ): List<Bridge> {
        val until = from.plusMonths(months)
        fun off(d: LocalDate, leave: Set<LocalDate>) = d in leave || holidayName(d) != null || !isWorkDay(d)
        fun run(d: LocalDate, leave: Set<LocalDate>): Pair<LocalDate, LocalDate> {
            var a = d; var b = d
            while (off(a.minusDays(1), leave) && ChronoUnit.DAYS.between(a, d) < 30) a = a.minusDays(1)
            while (off(b.plusDays(1), leave) && ChronoUnit.DAYS.between(d, b) < 30) b = b.plusDays(1)
            return a to b
        }
        fun bridge(leave: Set<LocalDate>, a: LocalDate, b: LocalDate) = Bridge(leave.sorted(), a, b,
            generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.mapNotNull(holidayName).distinct().toList())
        val result = mutableListOf<Bridge>()
        val seen = mutableSetOf<Pair<LocalDate, LocalDate>>()
        var d = from
        while (d.isBefore(until)) {
            // A 공휴일 on a day that would be worked; one on a weekly day off gives nothing new.
            if (holidayName(d) != null && isWorkDay(d)) {
                val natural = run(d, emptySet())
                if (seen.add(natural)) {
                    val (a0, b0) = natural
                    val candidates = (1L..2L).flatMap { listOf(a0.minusDays(it), b0.plusDays(it)) }
                        .filter { !it.isBefore(from) && !off(it, emptySet()) }.distinct().sorted()
                    val sets = candidates.map { setOf(it) } +
                        candidates.flatMapIndexed { i, x -> candidates.drop(i + 1).map { y -> setOf(x, y) } }
                    val options = sets.mapNotNull { leave ->
                        val (a, b) = run(d, leave)
                        if (leave.any { it.isBefore(a) || it.isAfter(b) }) return@mapNotNull null
                        // Joins two runs of days off: a day off on both sides of each leave day.
                        val joins = leave.all { l -> off(l.minusDays(1), leave - l) && off(l.plusDays(1), leave - l) }
                        Triple(bridge(leave, a, b), joins, leave.size)
                    }.filter { it.first.days >= MIN_BRIDGE_DAYS }
                    val order = compareByDescending<Triple<Bridge, Boolean, Int>> { it.first.days }.thenByDescending { it.second }
                        .thenBy { it.first.from }
                    val one = options.filter { it.third == 1 }.sortedWith(order).firstOrNull()?.first
                    val two = options.filter { it.third == 2 }.sortedWith(order).firstOrNull()?.first
                    one?.let(result::add)
                    if (two != null && (one == null || two.days >= one.days + 2)) result += two
                }
            }
            d = d.plusDays(1)
        }
        return result.sortedWith(compareBy({ it.from }, { it.leave.size }))
    }

    /** [LEAVE-04] Fewest days in a row worth suggesting to a shift or part-time worker, whose rest days come in runs anyway. */
    const val MIN_REST_BRIDGE_DAYS = 5

    /**
     * [LEAVE-04] 휴무 연결 for every kind of work (교대·알바·현장): 1 or 2 working days between two runs of days off
     * that, taken as leave, make [minDays] or more days off in a row. A shift roster's 공휴일 are working days like
     * any other, so only the roster's own days off count. The longest per month, at most [perMonth], earliest first.
     */
    fun restBridges(
        from: LocalDate,
        months: Long = 12,
        isWorkDay: (LocalDate) -> Boolean,
        minDays: Int = MIN_REST_BRIDGE_DAYS,
        perMonth: Int = 2,
        holidayName: (LocalDate) -> String? = KoreanHolidays::nameOf,
    ): List<Bridge> {
        val until = from.plusMonths(months)
        val found = mutableListOf<Bridge>()
        var d = from
        while (d.isBefore(until)) {
            // A run of 1~2 working days with a day off right before it.
            if (isWorkDay(d) && !isWorkDay(d.minusDays(1))) {
                var e = d
                while (isWorkDay(e.plusDays(1)) && ChronoUnit.DAYS.between(d, e) < 2) e = e.plusDays(1)
                val gap = ChronoUnit.DAYS.between(d, e).toInt() + 1
                if (gap <= 2 && !isWorkDay(e.plusDays(1))) {
                    var a = d.minusDays(1)
                    while (!isWorkDay(a.minusDays(1)) && ChronoUnit.DAYS.between(a, d) < 30) a = a.minusDays(1)
                    var b = e.plusDays(1)
                    while (!isWorkDay(b.plusDays(1)) && ChronoUnit.DAYS.between(e, b) < 30) b = b.plusDays(1)
                    val leave = generateSequence(d) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.toList()
                    val bridge = Bridge(leave, a, b, generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.mapNotNull(holidayName).distinct().toList())
                    if (bridge.days >= minDays) found += bridge
                }
                d = e.plusDays(1)
                continue
            }
            d = d.plusDays(1)
        }
        return found.groupBy { java.time.YearMonth.from(it.leave.first()) }.values
            .flatMap { list -> list.sortedWith(compareByDescending<Bridge> { it.days }.thenBy { it.leave.size }.thenBy { it.from }).take(perMonth) }
            .sortedWith(compareBy({ it.from }, { it.leave.size }))
    }

    /** [DAYW-05] This month's extra hours against the monthly 시간외 상한. */
    data class OvertimeCap(val usedMinutes: Int, val capHours: Int) {
        val capMinutes: Int get() = capHours * 60
        val leftMinutes: Int get() = (capMinutes - usedMinutes).coerceAtLeast(0)
        val overMinutes: Int get() = (usedMinutes - capMinutes).coerceAtLeast(0)
        val over: Boolean get() = usedMinutes > capMinutes
        /** Share of the cap used, 0..1+ (over 1 when past it). */
        val share: Double get() = if (capMinutes <= 0) 0.0 else usedMinutes.toDouble() / capMinutes
    }

    /** [DAYW-05] A cap people often have (공무원 시간외근무 월 57시간). */
    const val DEFAULT_CAP_HOURS = 57
}
