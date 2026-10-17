package com.bamnat.core.pay

import com.bamnat.core.model.OffDayTreatment
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Minutes of one piece of work (the day's shift, or one piece of extra work) by how the law pays them [PAY-01]. */
data class WorkMinutes(
    /**
     * Within 8 hours a day and 40 a week: 소정근로 for the day's shift, 소정 외 근로 for extra work
     * (paid 100% on top of a monthly salary) [EXTRA-02].
     */
    val regular: Int = 0,
    /** Beyond 8 hours a day or 40 a week, or extra work the company counts as 연장. */
    val overtime: Int = 0,
    /** Work on a paid holiday (주휴일·공휴일·근로자의 날), first 8 hours. */
    val holidayWithin8h: Int = 0,
    val holidayOver8h: Int = 0,
    /** [EXTRA-02] Work on a day off the company treats as a holiday (휴무일이지만 휴일근로로 대우), first 8 hours. */
    val restWithin8h: Int = 0,
    val restOver8h: Int = 0,
    /** Minutes inside the night window; overlaps the categories above (a premium on top). */
    val night: Int = 0,
) {
    val worked: Int get() = regular + overtime + holidayWithin8h + holidayOver8h + restWithin8h + restOver8h

    operator fun plus(o: WorkMinutes) = WorkMinutes(
        regular + o.regular, overtime + o.overtime, holidayWithin8h + o.holidayWithin8h, holidayOver8h + o.holidayOver8h,
        restWithin8h + o.restWithin8h, restOver8h + o.restOver8h, night + o.night,
    )
}

/** [EXTRA-02] Minutes of the piece of extra work at [index] in the day's [ResolvedDay.extras]. */
data class ExtraMinutes(val index: Int, val kindId: Long, val minutes: WorkMinutes, val estimated: Boolean = false)

/** Minutes of one day sorted into the categories the law pays differently [PAY-01]. */
data class DayMinutes(
    val date: LocalDate,
    /** The day's own shift. */
    val shift: WorkMinutes = WorkMinutes(),
    /** Each piece of extra work, in the order it was counted (by its time on the clock). */
    val extras: List<ExtraMinutes> = emptyList(),
    /** True when a guess was needed, e.g. extra work with no clock times. */
    val estimated: Boolean = false,
) {
    val total: WorkMinutes get() = extras.fold(shift) { a, e -> a + e.minutes }

    /** 소정근로 minutes of the shift within the daily and weekly limits. */
    val regular: Int get() = shift.regular
    /** [EXTRA-02] Extra work within 8 hours a day and 40 a week: 100%, not 연장. */
    val extraRegular: Int get() = extras.sumOf { it.minutes.regular }
    val overtime: Int get() = total.overtime
    val holidayWithin8h: Int get() = total.holidayWithin8h
    val holidayOver8h: Int get() = total.holidayOver8h
    val restWithin8h: Int get() = total.restWithin8h
    val restOver8h: Int get() = total.restOver8h
    val night: Int get() = total.night
    val worked: Int get() = total.worked

    fun extra(index: Int): ExtraMinutes? = extras.firstOrNull { it.index == index }
}

/** Company choices that change how minutes are sorted, all with legal defaults. */
data class ClassifyOptions(
    /** null: no daily overtime (탄력적·포괄 근로 등, user-defined rules only). */
    val dailyLimitMinutes: Int? = 8 * 60,
    val weeklyLimitMinutes: Int? = 40 * 60,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** [EXTRA-02] Work on a day off that is not a paid holiday: by law, as 휴일근로, or all 연장. */
    val restDayWork: RestDayWork = RestDayWork.LAW,
    /**
     * [PAYX-07] Every scheduled minute is regular work, however long the shift (4조2교대 12시간
     * 근무처럼 근무표 시간이 기본급에 들어 있는 경우). Extra work beyond the plan is overtime; the
     * daily and weekly limits are not used.
     */
    val scheduleIsRegular: Boolean = false,
    /**
     * [PAYX-07] 탄력적 근로시간제 단위기간 (제51조). With [scheduleIsRegular], scheduled hours beyond
     * an average of 40 a week over the period are overtime, settled on the period's last day.
     */
    val flexPeriod: FlexPeriod? = null,
    /**
     * [PAYX-14] A shift that crosses midnight into or out of a paid holiday: true counts only the
     * minutes inside the holiday's 0~24시 as holiday work (역일 기준, the default); false counts the
     * whole shift by the day it started on (some companies' rule).
     */
    val holidayByClock: Boolean = true,
)

/** 탄력적 근로시간제 단위기간, with fixed starts so every month agrees on the periods. */
enum class FlexPeriod(val label: String, val months: Int) {
    MONTH("1개월 (매달 1일~말일)", 1),
    QUARTER("3개월 (1·4·7·10월 1일 시작)", 3);

    fun start(date: LocalDate): LocalDate = date.withDayOfMonth(1).minusMonths(((date.monthValue - 1) % months).toLong())
    fun end(date: LocalDate): LocalDate = start(date).plusMonths(months.toLong()).minusDays(1)
}

/**
 * Sorts each day's minutes [PAY-01, EXTRA-02]. Work belongs to the day it started on (a night shift
 * and the 잔업 after it count on the night's date); minutes on a paid holiday follow the clock unless
 * the company counts by the start day [PAYX-14].
 *
 * The law, piece by piece:
 * - Paid holiday (주휴일·공휴일·근로자의 날): 휴일근로, 8 hours then beyond (제56조 제2항), never also 연장.
 * - A day off (휴무일) is not a holiday: its work is 연장 only beyond 8 hours that day or 40 that week
 *   (제50조, hours actually worked), unless the company treats it as 휴일근로 or all as 연장.
 * - Each day has 8 hours; the shift fills them first and extra work takes what is left (100%), the
 *   rest is 연장. Each week has 40 hours; the shifts fill them first, then extra work in date order.
 *   Extra work is always the last hours, so a shift already in the salary is never paid again.
 * - Night minutes are counted on the clock, whatever else the minute is.
 * A break with a known start comes off the clock where it is (제54조); one without is taken out
 * evenly, so night minutes shrink in proportion (an estimate).
 */
class MinuteClassifier(
    private val rates: (LocalDate) -> Rates,
    private val options: ClassifyOptions = ClassifyOptions(),
) {
    /**
     * Classifies [days] in date order. [isHoliday] says whether a date is a paid holiday
     * (주휴일·공휴일·근로자의 날). Days before the first full week only feed the weekly count, and a
     * week cut off at the end is settled with the days it has; pass whole weeks.
     */
    fun classify(days: List<ResolvedDay>, isHoliday: (LocalDate) -> Boolean): List<DayMinutes> {
        val daily = days.sortedBy { it.date }.map { classifyOne(it, isHoliday) }
        val weekly = if (options.scheduleIsRegular || options.weeklyLimitMinutes == null) daily else settleWeeks(daily, options.weeklyLimitMinutes)
        val flex = options.flexPeriod?.takeIf { options.scheduleIsRegular }
        return if (flex == null) weekly else settleFlex(weekly, flex)
    }

    /** One day on its own, as a paid holiday when [holiday]; no weekly limit. */
    fun classifyDay(day: ResolvedDay, holiday: Boolean): DayMinutes = classifyOne(day) { holiday }

    /** A piece of work: its clock span (null when unknown), paid minutes and break. */
    private class Piece(
        val index: Int,
        val kindId: Long,
        val from: LocalDateTime?,
        val to: LocalDateTime?,
        val paid: Int,
        val breakMinutes: Int,
        val breakStart: LocalTime?,
        val restAsHoliday: Boolean,
    )

    private fun classifyOne(day: ResolvedDay, isHoliday: (LocalDate) -> Boolean): DayMinutes {
        val r = rates(day.date)
        val restDay = day.isRestDay
        val pieces = buildList {
            if (day.isWorkDay && day.scheduledMinutes > 0) {
                add(Piece(-1, 0, day.shiftFrom, day.shiftTo, day.scheduledMinutes, day.breakMinutes, day.breakStart, false))
            }
            day.extras.withIndex()
                // Extra work after the shift, by its place on the clock; pieces without times last.
                .sortedWith(compareBy({ it.value.from == null }, { it.value.from }))
                .forEach { (i, e) ->
                    val asHoliday = restDay && (options.restDayWork == RestDayWork.HOLIDAY || e.kind.offDay == OffDayTreatment.HOLIDAY)
                    add(Piece(i, e.kind.id, e.from, e.to, e.minutes, e.breakMinutes, e.breakStart, asHoliday))
                }
        }
        var slot = options.dailyLimitMinutes ?: Int.MAX_VALUE
        var holidayUsed = 0
        var restUsed = 0
        val holiday8h = r.dailyLimitMinutes
        var shift = WorkMinutes()
        val extras = mutableListOf<ExtraMinutes>()
        var estimated = false
        for (p in pieces) {
            val split = split(p, r)
            if (p.from == null) estimated = true
            // Paid-holiday minutes: on the clock, or the whole piece by the day it belongs to.
            val onHoliday = if (options.holidayByClock && split.date != null) {
                (if (isHoliday(split.date)) split.onFirst else 0) + (if (isHoliday(split.date.plusDays(1))) split.onNext else 0)
            } else if (isHoliday(day.date)) p.paid else 0
            var left = p.paid - onHoliday
            val hWithin = minOf(onHoliday, (holiday8h - holidayUsed).coerceAtLeast(0))
            holidayUsed += onHoliday
            var rWithin = 0
            var rOver = 0
            if (p.restAsHoliday && left > 0) {
                rWithin = minOf(left, (holiday8h - restUsed).coerceAtLeast(0))
                rOver = left - rWithin
                restUsed += left
                left = 0
            }
            val isShift = p.index < 0
            val regular: Int
            val overtime: Int
            when {
                isShift && options.scheduleIsRegular -> { regular = left; overtime = 0 }
                // Work beyond the plan, or on a day off the company counts as 연장, is all overtime.
                !isShift && (options.scheduleIsRegular || restDay && options.restDayWork == RestDayWork.OVERTIME) -> { regular = 0; overtime = left }
                else -> { regular = minOf(left, slot); overtime = left - regular; slot -= regular }
            }
            val m = WorkMinutes(regular, overtime, hWithin, onHoliday - hWithin, rWithin, rOver, split.night)
            if (isShift) shift = m else extras += ExtraMinutes(p.index, p.kindId, m, estimated = p.from == null)
        }
        return DayMinutes(day.date, shift, extras, estimated)
    }

    /** Paid minutes of a piece on the date it starts and on the next date, and its night minutes. */
    private class Split(val date: LocalDate?, val onFirst: Int, val onNext: Int, val night: Int)

    private fun split(p: Piece, r: Rates): Split {
        val from = p.from
        val to = p.to
        if (from == null || to == null) return Split(null, p.paid, 0, 0)
        val gross = Duration.between(from, to).toMinutes().toInt()
        if (gross <= 0 || p.paid <= 0) return Split(from.toLocalDate(), 0, 0, 0)
        val midnight = from.toLocalDate().plusDays(1).atStartOfDay()
        val grossNext = minutesAfter(from, to, midnight)
        val nightGross = nightMinutes(from, to, r)
        if (p.breakStart != null && p.breakMinutes > 0) {
            var bFrom = from.toLocalDate().atTime(p.breakStart)
            if (bFrom.isBefore(from)) bFrom = bFrom.plusDays(1)
            val bTo = bFrom.plusMinutes(p.breakMinutes.toLong())
            if (!bTo.isAfter(to)) {
                val next = (grossNext - minutesAfter(bFrom, bTo, midnight)).coerceIn(0, p.paid)
                return Split(from.toLocalDate(), p.paid - next, next, nightGross - nightMinutes(bFrom, bTo, r))
            }
        }
        val next = (grossNext * p.paid.toDouble() / gross).roundToInt().coerceIn(0, p.paid)
        return Split(from.toLocalDate(), p.paid - next, next, (nightGross * p.paid.toDouble() / gross).roundToInt())
    }

    private fun minutesAfter(from: LocalDateTime, to: LocalDateTime, at: LocalDateTime): Int {
        val s = maxOf(from, at)
        return if (to.isAfter(s)) Duration.between(s, to).toMinutes().toInt() else 0
    }

    /**
     * 주 40시간 (제50조 제1항), on hours actually worked: the week's shifts count first, in date order,
     * and past 40 hours a shift's 소정 minutes are 연장 on the later days. Extra work then takes what is
     * left of the 40 hours in date order, and the rest of it is 연장. Leave and holidays are not work.
     */
    private fun settleWeeks(list: List<DayMinutes>, limit: Int): List<DayMinutes> {
        val out = list.toMutableList()
        list.indices.groupBy { list[it].date.with(TemporalAdjusters.previousOrSame(options.weekStart)) }.values.forEach { week ->
            var used = 0
            for (i in week) {
                val d = out[i]
                val over = (used + d.shift.regular - limit).coerceIn(0, d.shift.regular)
                used += d.shift.regular - over
                if (over > 0) out[i] = d.copy(shift = d.shift.copy(regular = d.shift.regular - over, overtime = d.shift.overtime + over))
            }
            for (i in week) {
                val d = out[i]
                if (d.extras.none { it.minutes.regular > 0 }) continue
                out[i] = d.copy(extras = d.extras.map { e ->
                    val keep = minOf(e.minutes.regular, (limit - used).coerceAtLeast(0))
                    used += keep
                    val move = e.minutes.regular - keep
                    if (move == 0) e else e.copy(minutes = e.minutes.copy(regular = keep, overtime = e.minutes.overtime + move))
                })
            }
        }
        return out
    }

    /**
     * Moves regular minutes beyond 40 hours a week on average (총 일수 ÷ 7 × 40시간) to overtime on
     * the last day of each 단위기간 the list covers in full. A period cut off at either end is left
     * alone: the caller resolves from the period's start, and the next month settles a later end.
     */
    private fun settleFlex(list: List<DayMinutes>, flex: FlexPeriod): List<DayMinutes> {
        if (list.isEmpty()) return list
        val first = list.first().date
        val last = list.last().date
        val out = list.toMutableList()
        list.groupBy { flex.start(it.date) }.forEach { (start, period) ->
            val end = flex.end(start)
            if (start.isBefore(first) || end.isAfter(last)) return@forEach
            val days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
            val limit = (days * 40 * 60 / 7.0).roundToInt()
            val excess = period.sumOf { it.regular } - limit
            if (excess <= 0) return@forEach
            // The excess comes off regular minutes from the period's end backwards, so a day off at
            // the end does not end up with more overtime than was worked.
            var left = excess
            for (i in out.indices.reversed()) {
                if (left == 0) break
                val d = out[i]
                if (d.date.isBefore(start) || d.date.isAfter(end) || d.regular == 0) continue
                val take = minOf(left, d.regular)
                out[i] = d.copy(shift = d.shift.copy(regular = d.shift.regular - take, overtime = d.shift.overtime + take))
                left -= take
            }
        }
        return out
    }

    /**
     * Night minutes actually worked in a shift of [from, to) with [paid] working minutes. A break
     * that starts at [breakStart] and fits inside the shift is taken off where it falls; otherwise
     * the break comes off in proportion.
     */
    internal fun shiftNight(from: LocalDateTime, to: LocalDateTime, paid: Int, breakMinutes: Int, breakStart: LocalTime?, r: Rates): Int =
        split(Piece(-1, 0, from, to, paid, breakMinutes, breakStart, false), r).night

    /**
     * [EXTRA-08] Minutes worked in [from, to) between the clock times [start] and [end] (past
     * midnight when [end] is not after [start]), the break taken off as for night minutes.
     */
    internal fun windowMinutes(
        from: LocalDateTime, to: LocalDateTime, paid: Int, breakMinutes: Int, breakStart: LocalTime?,
        start: LocalTime, end: LocalTime, r: Rates,
    ): Int = shiftNight(from, to, paid, breakMinutes, breakStart, r.copy(nightStart = start, nightEnd = end))

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
