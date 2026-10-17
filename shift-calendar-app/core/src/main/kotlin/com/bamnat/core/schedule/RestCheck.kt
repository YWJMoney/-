package com.bamnat.core.schedule

import com.bamnat.core.model.ExtraTiming
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [SLP-01][MKT-07][MKT-11] Rest between shifts, read off the calendar: less than 11 hours off
 * between two shifts (근로기준법 제59조 제2항 is the 11-hour rest required of 특례업종), a night shift
 * followed by a day shift within 24 hours, backward rotation (오후 → 주간, 야간 → 주간) and four
 * or more nights in a row (Delphi consensus: at most 2–3). These are reference signs, not a diagnosis.
 */
object RestCheck {
    enum class Kind(val label: String) {
        AFTER_NIGHT("야간 뒤 휴식 부족"),
        SHORT_REST("휴식 11시간 미만"),
        BACKWARD("역순환"),
        NIGHT_STREAK("연속 야간"),
        /** [MKT-11] Fewer than two days off after a run of nights (strict rules only). */
        SHORT_RECOVERY("야간 뒤 쉬는 날 부족"),
    }

    data class Issue(val date: LocalDate, val kind: Kind, val text: String)

    const val MIN_REST_MINUTES = 11 * 60
    const val AFTER_NIGHT_MINUTES = 24 * 60
    const val NIGHT_STREAK = 4
    /** [MKT-11] Delphi consensus in the plan (MKT-11): at most 2 nights in a row, 2 days off after nights. */
    const val STRICT_NIGHT_STREAK = 3
    const val STRICT_RECOVERY_DAYS = 2

    private data class Work(val date: LocalDate, val from: LocalDateTime, val to: LocalDateTime, val name: String) {
        val night: Boolean get() {
            val total = Duration.between(from, to).toMinutes()
            return total > 0 && nightMinutes(from, to) * 2 >= total
        }
    }

    /** Issues for [days] (one calendar day each, in order); pass the day before the range too. */
    fun check(days: List<ResolvedDay>, strict: Boolean = false): List<Issue> {
        val streakLimit = if (strict) STRICT_NIGHT_STREAK else NIGHT_STREAK
        val work = days.flatMap(::intervals).sortedBy { it.from }
        val issues = mutableListOf<Issue>()
        for ((a, b) in work.zipWithNext()) {
            val gap = Duration.between(a.to, b.from).toMinutes()
            if (gap < 0) continue
            val rest = if (gap == 0L) "쉬지 않고 바로" else "${hours(gap)} 쉬고"
            val end = "%02d:%02d".format(a.to.hour, a.to.minute)
            when {
                a.night && !b.night && gap < AFTER_NIGHT_MINUTES ->
                    issues += Issue(b.date, Kind.AFTER_NIGHT, "${a.name}(${end} 끝) 뒤 $rest ${b.name}")
                gap < MIN_REST_MINUTES ->
                    issues += Issue(b.date, Kind.SHORT_REST, "${a.name}(${end} 끝) 뒤 $rest ${b.name}")
                b.date == a.date.plusDays(1) && b.from.toLocalTime() < a.from.toLocalTime() && gap < AFTER_NIGHT_MINUTES ->
                    issues += Issue(b.date, Kind.BACKWARD, "${a.name} 다음 날 ${b.name} (시작이 앞당겨지는 순서)")
            }
        }
        // Nights in a row, counted by date; flagged on the day the streak reaches the limit.
        val nightDates = work.filter { it.night }.map { it.date }.distinct().sorted()
        var streak = 0
        var prev: LocalDate? = null
        for (d in nightDates) {
            streak = if (prev != null && d == prev.plusDays(1)) streak + 1 else 1
            if (streak == streakLimit) {
                var len = streak
                var next = d.plusDays(1)
                while (next in nightDates) { len++; next = next.plusDays(1) }
                issues += Issue(d, Kind.NIGHT_STREAK, "야간 ${len}일 연속 (${d.minusDays(streakLimit - 1L).monthValue}/${d.minusDays(streakLimit - 1L).dayOfMonth}부터)")
            }
            prev = d
        }
        if (strict) {
            // A run of nights ends on a night date with no night the day after; the next work
            // day should leave two whole days off in between.
            val workDates = work.map { it.date }.distinct().sorted()
            for (d in nightDates.filter { it.plusDays(1) !in nightDates }) {
                val next = workDates.firstOrNull { it.isAfter(d) } ?: continue
                val off = java.time.temporal.ChronoUnit.DAYS.between(d, next) - 1
                if (off < STRICT_RECOVERY_DAYS) {
                    issues += Issue(next, Kind.SHORT_RECOVERY, "야간이 끝난 ${d.monthValue}/${d.dayOfMonth} 뒤 쉬는 날 ${off}일")
                }
            }
        }
        return issues.sortedWith(compareBy({ it.date }, { it.kind.ordinal }))
    }

    /**
     * The day's blocks of work: the shift with any 잔업 or 조출 next to it as one, and each other piece
     * of extra work (특근, 대근) on its own, so a 대근 straight after a shift still shows no rest between.
     */
    private fun intervals(day: ResolvedDay): List<Work> = blocks(day).map { Work(day.date, it.from, it.to, it.name) }

    /** One stretch of work on the clock, named after the shift or the kind of extra work. */
    data class Block(val from: LocalDateTime, val to: LocalDateTime, val name: String)

    /** [EXTRA-01] [day]'s work on the clock, earliest first; pieces without times are left out. */
    fun blocks(day: ResolvedDay): List<Block> {
        val out = mutableListOf<Block>()
        val shiftFrom = day.shiftFrom?.takeIf { day.isWorkDay && day.leave?.isFullDay != true }
        val shiftTo = day.shiftTo
        var main = if (shiftFrom != null && shiftTo != null) Block(shiftFrom, shiftTo, day.shiftType?.name ?: "근무") else null
        for (e in day.extras) {
            val from = e.from ?: continue
            val to = e.to ?: continue
            val nextToShift = e.kind.timing is ExtraTiming.AfterShift || e.kind.timing is ExtraTiming.BeforeShift
            val m = main
            main = if (nextToShift && m != null && !from.isAfter(m.to) && !to.isBefore(m.from)) {
                m.copy(from = minOf(m.from, from), to = maxOf(m.to, to))
            } else {
                if (nextToShift && m == null) Block(from, to, e.kind.name) else { out += Block(from, to, e.kind.name); m }
            }
        }
        main?.let { out += it }
        return out.sortedBy { it.from }
    }

    /** Minutes of [from, to) inside 22:00~06:00. */
    internal fun nightMinutes(from: LocalDateTime, to: LocalDateTime): Long {
        var total = 0L
        var d = from.toLocalDate().minusDays(1)
        while (!d.isAfter(to.toLocalDate())) {
            val ns = d.atTime(22, 0)
            val ne = d.plusDays(1).atTime(6, 0)
            val s = maxOf(ns, from)
            val e = minOf(ne, to)
            if (e.isAfter(s)) total += Duration.between(s, e).toMinutes()
            d = d.plusDays(1)
        }
        return total
    }

    private fun hours(minutes: Long): String =
        if (minutes % 60 == 0L) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"
}
