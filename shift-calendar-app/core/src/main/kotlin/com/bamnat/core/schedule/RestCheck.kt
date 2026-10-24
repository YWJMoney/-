package com.bamnat.core.schedule

import com.bamnat.core.model.ExtraTiming
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * [SLP-01][MKT-07][MKT-11] Rest between shifts, read off the calendar: less than 11 hours off
 * between two shifts (근로기준법 제59조 제2항 is the 11-hour rest required of 특례업종), a night shift
 * followed by a day shift within 24 hours, backward rotation (오후 → 주간, 야간 → 주간) and four
 * or more nights in a row (Delphi consensus: at most 2–3). These are reference signs, not a diagnosis.
 * [REST-01] Since 1.22 the limits are the user's [RestRules], shared with [DutyCheck].
 */
object RestCheck {
    enum class Kind(val label: String) {
        AFTER_NIGHT("야간 뒤 휴식 부족"),
        SHORT_REST("휴식 11시간 미만"),
        BACKWARD("역순환"),
        NIGHT_STREAK("연속 야간"),
        /** [MKT-11] Fewer days off after a run of nights than [RestRules.offsAfterNights]. */
        SHORT_RECOVERY("야간 뒤 쉬는 날 부족"),
        /** [REST-01] More work days in a row than [RestRules.maxWorkDaysInRow]. */
        LONG_RUN("연속 근무"),
        /** [REST-02] More nights in the month than [RestRules.maxNightsPerMonth]. */
        MONTH_NIGHTS("한 달 야간 많음"),
    }

    /**
     * One sign. [pattern] names the order behind it when it has a name on the ward (N-O-D 나오데, E-D 이브데이),
     * [source] says where the limit comes from [REST-02].
     */
    data class Issue(val date: LocalDate, val kind: Kind, val text: String, val source: String = "", val pattern: String? = null)

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
        /** Starts in the afternoon (오후·이브닝), not a night. */
        val evening: Boolean get() = !night && from.hour in 12..17
        /** Starts in the morning (주간·데이). */
        val day: Boolean get() = !night && from.hour in 4..11
    }

    /** Issues for [days] (one calendar day each, in order) by the rules before 1.22; pass the days before the range too. */
    fun check(days: List<ResolvedDay>, strict: Boolean = false): List<Issue> = check(days, RestRules.legacy(strict))

    /**
     * [REST-01] Issues for [days] by [rules]. Pass the days before the range as well: [lookbackFrom] says how many.
     * Signs that say the same thing about one day (a day shift right after nights is short rest, too few days
     * off and backward rotation at once) come once, the most telling first [REST-02].
     */
    fun check(days: List<ResolvedDay>, rules: RestRules): List<Issue> {
        if (!rules.on) return emptyList()
        val work = days.flatMap(::intervals).sortedBy { it.from }
        val issues = mutableListOf<Issue>()
        val minRest = rules.minRestHours * 60L
        for ((a, b) in work.zipWithNext()) {
            val gap = Duration.between(a.to, b.from).toMinutes()
            // [SET-04] Times that overlap are a wrong time more often than a real day: said, not skipped (ux-review 1 P25).
            if (gap < 0) {
                issues += Issue(b.date, Kind.SHORT_REST, "${a.name}(${"%02d:%02d".format(a.to.hour, a.to.minute)} 끝)과 ${b.name} 시간이 겹쳐요",
                    RestRules.Sources.REST)
                continue
            }
            val rest = if (gap == 0L) "쉬지 않고 바로" else "${hours(gap)} 쉬고"
            val end = "%02d:%02d".format(a.to.hour, a.to.minute)
            val nextDay = b.date == a.date.plusDays(1)
            when {
                a.night && !b.night && gap < AFTER_NIGHT_MINUTES ->
                    issues += Issue(b.date, Kind.AFTER_NIGHT, "${a.name}(${end} 끝) 뒤 $rest ${b.name}", RestRules.Sources.NIGHTS,
                        pattern = if (b.day) "N-D" else null)
                gap < minRest ->
                    issues += Issue(b.date, Kind.SHORT_REST, "${a.name}(${end} 끝) 뒤 $rest ${b.name}", RestRules.Sources.REST,
                        pattern = if (a.evening && b.day && nextDay) "E-D(이브데이)" else null)
                nextDay && b.from.toLocalTime() < a.from.toLocalTime() && gap < AFTER_NIGHT_MINUTES ->
                    issues += Issue(b.date, Kind.BACKWARD, "${a.name} 다음 날 ${b.name} (시작이 앞당겨지는 순서)", RestRules.Sources.ORDER,
                        pattern = if (a.evening && b.day) "E-D(이브데이)" else null)
            }
        }
        // Nights in a row, counted by date; flagged on the day the streak passes the limit.
        val nightDates = work.filter { it.night }.map { it.date }.distinct().sorted()
        if (rules.maxNightsInRow > 0) {
            val streakLimit = rules.maxNightsInRow + 1
            var streak = 0
            var prev: LocalDate? = null
            for (d in nightDates) {
                streak = if (prev != null && d == prev.plusDays(1)) streak + 1 else 1
                if (streak == streakLimit) {
                    var len = streak
                    var next = d.plusDays(1)
                    while (next in nightDates) { len++; next = next.plusDays(1) }
                    val from = d.minusDays(streakLimit - 1L)
                    issues += Issue(d, Kind.NIGHT_STREAK, "${from.monthValue}/${from.dayOfMonth}부터 야간 ${len}일 연속 (${rules.note("${streakLimit}일 연속부터")})", RestRules.Sources.NIGHTS)
                }
                prev = d
            }
        }
        val workDates = work.map { it.date }.distinct().sorted()
        if (rules.offsAfterNights > 0) {
            // A run of nights ends on a night date with no night the day after; the next work day should
            // leave whole days off in between.
            for (d in nightDates.filter { it.plusDays(1) !in nightDates }) {
                val next = workDates.firstOrNull { it.isAfter(d) } ?: continue
                val off = ChronoUnit.DAYS.between(d, next) - 1
                val nextWork = work.first { it.date == next }
                // Another night after a day off (야비야비) carries the run on; the rest is counted when the nights end.
                if (off < rules.offsAfterNights && !nextWork.night) {
                    issues += Issue(next, Kind.SHORT_RECOVERY, "야간이 끝난 ${d.monthValue}/${d.dayOfMonth} 뒤 쉬는 날 ${off}일 (${rules.note("${rules.offsAfterNights}일보다 적으면")})",
                        RestRules.Sources.NIGHTS, pattern = if (off == 1L && nextWork.day) "N-O-D(나오데)" else null)
                }
            }
        }
        if (rules.maxWorkDaysInRow > 0) {
            var run = 0
            var prev: LocalDate? = null
            for (d in workDates) {
                run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
                if (run == rules.maxWorkDaysInRow + 1) {
                    val from = d.minusDays(run - 1L)
                    issues += Issue(d, Kind.LONG_RUN, "${from.monthValue}/${from.dayOfMonth}부터 ${run}일째 쉬는 날 없이 근무 (${rules.note("${rules.maxWorkDaysInRow + 1}일째부터")})", RestRules.Sources.WEEK)
                }
                prev = d
            }
        }
        if (rules.maxNightsPerMonth > 0) {
            nightDates.groupBy { YearMonth.from(it) }.forEach { (m, ds) ->
                ds.getOrNull(rules.maxNightsPerMonth)?.let { d ->
                    issues += Issue(d, Kind.MONTH_NIGHTS, "${m.monthValue}월 야간 ${ds.size}일 (${rules.note("한 달 ${rules.maxNightsPerMonth + 1}일째부터")})", RestRules.Sources.NIGHTS)
                }
            }
        }
        return dedupe(issues).sortedWith(compareBy({ it.date }, { it.kind.ordinal }))
    }

    /** The signs about the rest before one day: only the first of them is kept, with any pattern name the others had. */
    private val SAME_REST = listOf(Kind.AFTER_NIGHT, Kind.SHORT_REST, Kind.BACKWARD, Kind.SHORT_RECOVERY)

    private fun dedupe(issues: List<Issue>): List<Issue> {
        val (rest, other) = issues.partition { it.kind in SAME_REST }
        val kept = rest.groupBy { it.date }.map { (_, same) ->
            val first = same.minBy { SAME_REST.indexOf(it.kind) }
            val name = first.pattern ?: same.firstNotNullOfOrNull { it.pattern }
            first.copy(pattern = name)
        }
        return (kept + other).map { i -> i.pattern?.takeUnless { i.text.startsWith(it) }?.let { i.copy(text = "$it · ${i.text}") } ?: i }
    }

    /**
     * [REST-02] The same sign over a month as one line: its kind and order name, how many times and on which days
     * ("연속 야간 3번 · 2·7·12일"), so the card stays short; each day's own sign stays in [issues].
     */
    data class Group(val kind: Kind, val pattern: String?, val issues: List<Issue>) {
        val date: LocalDate get() = issues.first().date
        val title: String get() = if (issues.size == 1) kind.label else "${kind.label} ${issues.size}번"
        val text: String get() = if (issues.size == 1) issues.first().text else {
            val days = issues.map { it.date.dayOfMonth }.distinct()
            (pattern?.let { "$it · " } ?: "") + days.take(GROUP_DAYS).joinToString("·") + (if (days.size > GROUP_DAYS) "…" else "") + "일"
        }
    }

    private const val GROUP_DAYS = 4

    /** [issues] by kind and order name, in the order they first come. */
    fun group(issues: List<Issue>): List<Group> =
        issues.groupBy { it.kind to it.pattern }.map { (k, list) -> Group(k.first, k.second, list) }.sortedBy { it.date }

    /** [NURS-05] The dates among [days] with night work (half or more of a block of work in 22:00~06:00). */
    fun nightDates(days: List<ResolvedDay>): List<LocalDate> =
        days.flatMap(::intervals).filter { it.night }.map { it.date }.distinct().sorted()

    /** [REST-01] The first day to pass with a range starting at [from], so runs and month counts that end in it are seen. */
    fun lookbackFrom(from: LocalDate, rules: RestRules): LocalDate {
        val back = maxOf(NIGHT_STREAK, rules.maxNightsInRow + 1, rules.maxWorkDaysInRow + 1, rules.offsAfterNights + 2).toLong()
        val run = from.minusDays(back)
        return if (rules.maxNightsPerMonth > 0) minOf(run, from.withDayOfMonth(1)) else run
    }

    /**
     * The day's blocks of work: the shift with any 잔업 or 조출 next to it as one, and each other piece
     * of extra work (특근, 대근) on its own, so a 대근 straight after a shift still shows no rest between.
     */
    private fun intervals(day: ResolvedDay): List<Work> = blocks(day).map { Work(day.date, it.from, it.to, it.name) }

    /**
     * One stretch of work on the clock, named after the shift or the kind of extra work ("야간 대근" for
     * a 대근 of a 야간), in the colour the calendar gives it [SLP-14].
     */
    data class Block(val from: LocalDateTime, val to: LocalDateTime, val name: String, val colorArgb: Long? = null)

    /** [EXTRA-01] [day]'s work on the clock, earliest first; pieces without times are left out. */
    fun blocks(day: ResolvedDay): List<Block> {
        val out = mutableListOf<Block>()
        val shiftFrom = day.shiftFrom?.takeIf { day.isWorkDay && day.leave?.isFullDay != true }
        val shiftTo = day.shiftTo
        var main = if (shiftFrom != null && shiftTo != null) Block(shiftFrom, shiftTo, day.shiftType?.name ?: "근무", day.shiftType?.colorArgb) else null
        for (e in day.extras) {
            val from = e.from ?: continue
            val to = e.to ?: continue
            val nextToShift = e.kind.timing is ExtraTiming.AfterShift || e.kind.timing is ExtraTiming.BeforeShift
            val name = e.covered?.name?.takeUnless { it in e.kind.name }?.let { "$it ${e.kind.name}" } ?: e.kind.name
            val piece = Block(from, to, name, e.covered?.colorArgb ?: e.kind.colorArgb)
            val m = main
            main = if (nextToShift && m != null && !from.isAfter(m.to) && !to.isBefore(m.from)) {
                m.copy(from = minOf(m.from, from), to = maxOf(m.to, to))
            } else {
                if (nextToShift && m == null) piece else { out += piece; m }
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
