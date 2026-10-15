package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Minutes of one day sorted into the categories the law pays differently [PAY-01]. */
data class DayMinutes(
    val date: LocalDate,
    /** Paid minutes within the daily and weekly limits on a normal day. */
    val regular: Int = 0,
    /** Beyond 8 hours a day, 40 hours a week, or extra hours the user entered. */
    val overtime: Int = 0,
    /** Work on a holiday, first 8 hours. */
    val holidayWithin8h: Int = 0,
    /** Work on a holiday beyond 8 hours. */
    val holidayOver8h: Int = 0,
    /** 22:00~06:00 minutes; overlaps the categories above (an extra premium on top). */
    val night: Int = 0,
    /** True when a guess was needed, e.g. no clock times for holiday work. */
    val estimated: Boolean = false,
) {
    val worked: Int get() = regular + overtime + holidayWithin8h + holidayOver8h
}

/** Company choices that change how minutes are sorted, all with legal defaults. */
data class ClassifyOptions(
    /** null: no daily overtime (탄력적·포괄 근로 등, user-defined rules only). */
    val dailyLimitMinutes: Int? = 8 * 60,
    val weeklyLimitMinutes: Int? = 40 * 60,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** Work on a day off counts as holiday work; false makes all of it overtime [PAYX-07]. */
    val offDayWorkIsHoliday: Boolean = true,
)

/**
 * Sorts each day's minutes [PAY-01]. A shift that runs past midnight belongs to the day it
 * started on, including the holiday decision (an assumption the settings can change later).
 * Breaks are taken out evenly across the shift, so night minutes shrink in proportion.
 */
class MinuteClassifier(
    private val rates: (LocalDate) -> Rates,
    private val options: ClassifyOptions = ClassifyOptions(),
) {
    /**
     * Classifies [days] in date order. [isHoliday] says whether a date is a paid holiday
     * (주휴일·공휴일); days the user marked as extra work on a day off count as holidays too.
     * Days before the first full week only feed the weekly count; pass a whole week range.
     */
    fun classify(days: List<ResolvedDay>, isHoliday: (LocalDate) -> Boolean): List<DayMinutes> {
        val sorted = days.sortedBy { it.date }
        val weeklyRegular = HashMap<LocalDate, Int>()
        return sorted.map { day ->
            val offDayWork = day.holidayWorkMinutes > 0 || (day.second != null && !day.isWorkDay)
            val raw = classifyDay(day, isHoliday(day.date) || (offDayWork && options.offDayWorkIsHoliday))
            val limit = options.weeklyLimitMinutes ?: return@map raw
            val week = day.date.with(TemporalAdjusters.previousOrSame(options.weekStart))
            val before = weeklyRegular[week] ?: 0
            val over = (before + raw.regular - limit).coerceAtLeast(0).coerceAtMost(raw.regular)
            weeklyRegular[week] = before + raw.regular - over
            if (over == 0) raw else raw.copy(regular = raw.regular - over, overtime = raw.overtime + over)
        }
    }

    fun classifyDay(day: ResolvedDay, holiday: Boolean): DayMinutes {
        val first = classifyFirst(day, holiday)
        val second = day.second ?: return first
        // [FEAT-02] The second shift keeps its own clock, so its night minutes are its own.
        val r = rates(day.date)
        val from = day.date.atTime(second.start)
        val to = if (second.end.isAfter(second.start)) day.date.atTime(second.end) else day.date.plusDays(1).atTime(second.end)
        val gross = Duration.between(from, to).toMinutes().toInt()
        val minutes = second.minutes
        val night = if (gross == 0) 0 else (nightMinutes(from, to, r) * minutes.toDouble() / gross).roundToInt()
        if (holiday) return holidaySplit(day.date, first.worked + minutes, first.night + night, r, first.estimated)
        // On a working day it fills what is left of the daily limit, then is overtime; on a day
        // off counted as 연장 by company rule all of it is overtime.
        val room = if (!day.isWorkDay) 0 else options.dailyLimitMinutes?.let { (it - first.regular - first.overtime).coerceAtLeast(0) } ?: minutes
        val regular = minOf(minutes, room)
        return first.copy(regular = first.regular + regular, overtime = first.overtime + minutes - regular, night = first.night + night)
    }

    private fun classifyFirst(day: ResolvedDay, holiday: Boolean): DayMinutes {
        val r = rates(day.date)
        val start = day.start
        val end = day.end
        val scheduled = if (day.isWorkDay) day.scheduledMinutes else 0
        val extra = day.overtimeMinutes
        val holidayOnly = day.holidayWorkMinutes

        if (start == null || end == null) {
            // Extra work on a day off with no clock times: count it, assume daytime.
            val minutes = scheduled + extra + holidayOnly
            if (minutes == 0) return DayMinutes(day.date)
            if (holiday) return holidaySplit(day.date, minutes, 0, r, estimated = true)
            // A day off worked as 연장 by company rule: every minute is overtime.
            return if (holidayOnly > 0 && scheduled == 0) DayMinutes(day.date, overtime = minutes, estimated = true)
            else DayMinutes(day.date, regular = minutes - extra, overtime = extra, estimated = true)
        }

        val from = day.date.atTime(start)
        var to = if (end.isAfter(start)) day.date.atTime(end) else day.date.plusDays(1).atTime(end)
        val gross = Duration.between(from, to).toMinutes().toInt()
        val paidScheduled = scheduled.coerceAtMost(gross)
        // Extra hours run on after the shift ends.
        val extraEnd = to.plusMinutes((extra + holidayOnly).toLong())
        val nightGross = nightMinutes(from, to, r)
        val night = if (gross == 0) 0 else (nightGross * paidScheduled.toDouble() / gross).roundToInt() +
            nightMinutes(to, extraEnd, r)
        to = extraEnd
        val total = paidScheduled + extra + holidayOnly

        if (holiday) return holidaySplit(day.date, total, night, r, estimated = false)
        val limit = options.dailyLimitMinutes
        val dailyOver = if (limit == null) 0 else (paidScheduled - limit).coerceAtLeast(0)
        return DayMinutes(
            date = day.date,
            regular = paidScheduled - dailyOver,
            overtime = dailyOver + extra + holidayOnly,
            night = night,
        )
    }

    private fun holidaySplit(date: LocalDate, minutes: Int, night: Int, r: Rates, estimated: Boolean): DayMinutes {
        val within = minutes.coerceAtMost(r.dailyLimitMinutes)
        return DayMinutes(date, holidayWithin8h = within, holidayOver8h = minutes - within, night = night, estimated = estimated)
    }

    /** Minutes of [from, to) inside the night window, across any number of midnights. */
    internal fun nightMinutes(from: LocalDateTime, to: LocalDateTime, r: Rates): Int {
        if (!to.isAfter(from)) return 0
        var total = 0L
        var day = from.toLocalDate().minusDays(1)
        while (!day.isAfter(to.toLocalDate())) {
            val ns = day.atTime(r.nightStart)
            val ne = if (r.nightEnd.isAfter(r.nightStart)) day.atTime(r.nightEnd) else day.plusDays(1).atTime(r.nightEnd)
            val s = maxOf(ns, from)
            val e = minOf(ne, to)
            if (e.isAfter(s)) total += Duration.between(s, e).toMinutes()
            day = day.plusDays(1)
        }
        return total.toInt()
    }
}
