package com.bamnat.core.report

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth

/**
 * [PROV-05] The roster to print (프로): a month as a calendar with each day's shift, leave and extra work,
 * and a year on one landscape A4 page, a row per month. Days off print as their shift (휴) when the
 * calendar has one, and empty otherwise.
 */
object RosterReport {
    /** Grey for leave and extra work, which have no colour of their own on paper. */
    const val LEAVE_COLOR = 0xFF868E96.toInt()

    fun month(month: YearMonth, resolve: (LocalDate) -> ResolvedDay, title: String, made: LocalDate): ReportDoc {
        val days = (1..month.lengthOfMonth()).associateWith { d ->
            val day = resolve(month.atDay(d))
            ReportDoc.MonthGrid.Day(chips(day), note(day), KoreanHolidays.nameOf(day.date))
        }
        val used = (1..month.lengthOfMonth()).mapNotNull { resolve(month.atDay(it)).shiftType }.distinctBy { it.id }
        val blocks = listOf(
            ReportDoc.MonthGrid(month, days),
            ReportDoc.Legend(used.map { ReportDoc.Chip(it.label, it.colorArgb.toInt()) to it.name + (time(it.start, it.end)?.let { t -> " $t" } ?: "") }),
            ReportDoc.Paragraph("만든 날 ${made.year}.${made.monthValue}.${made.dayOfMonth} · 밤낮", small = true),
        )
        return ReportDoc("${title.ifBlank { "근무표" }} · ${month.year}년 ${month.monthValue}월", blocks, footer = "밤낮 근무표")
    }

    fun year(year: Int, resolve: (LocalDate) -> ResolvedDay, title: String, made: LocalDate): ReportDoc {
        val months = (1..12).map { YearMonth.of(year, it) }
        val days = months.associateWith { m ->
            (1..m.lengthOfMonth()).mapNotNull { d -> chips(resolve(m.atDay(d))).firstOrNull()?.let { d to it } }.toMap()
        }
        val used = months.flatMap { m -> (1..m.lengthOfMonth()).mapNotNull { resolve(m.atDay(it)).shiftType } }.distinctBy { it.id }
        val holidays = months.flatMap { m -> (1..m.lengthOfMonth()).map { m.atDay(it) } }.filter(KoreanHolidays::isHoliday).toSet()
        val blocks = listOf(
            ReportDoc.YearGrid(year, days, holidays),
            ReportDoc.Legend(used.map { ReportDoc.Chip(it.label, it.colorArgb.toInt()) to it.name }),
            ReportDoc.Paragraph("만든 날 ${made.year}.${made.monthValue}.${made.dayOfMonth} · 밤낮 · 빨간 날짜는 공휴일", small = true),
        )
        return ReportDoc("${title.ifBlank { "근무표" }} · ${year}년", blocks, landscape = true, footer = "밤낮 근무표")
    }

    fun fileName(month: YearMonth) = "bamnat-roster-$month.pdf"
    fun fileName(year: Int) = "bamnat-roster-$year.pdf"

    /** The day's shift, then its leave or the shift it covered as extra work. */
    private fun chips(day: ResolvedDay): List<ReportDoc.Chip> = buildList {
        day.shiftType?.let { add(ReportDoc.Chip(it.label, it.colorArgb.toInt())) }
        day.leave?.let { add(ReportDoc.Chip(it.label.take(2), LEAVE_COLOR)) }
        day.extras.forEach { e -> add(ReportDoc.Chip(e.kind.mark, LEAVE_COLOR)) }
    }.take(3)

    private fun note(day: ResolvedDay): String? =
        if (day.hasCustomTimes && day.start != null && day.end != null) time(day.start, day.end) else null

    private fun time(start: java.time.LocalTime?, end: java.time.LocalTime?): String? =
        if (start == null || end == null) null else "%02d:%02d~%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
}
