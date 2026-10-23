package com.bamnat.core

import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.report.MonthCompare
import com.bamnat.core.report.PayReport
import com.bamnat.core.report.ReportDoc
import com.bamnat.core.report.RosterReport
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** [PROV-01][PROV-02][PROV-05] The pay report, the month comparison and the printed roster. */
class ReportTest {
    private val monday = LocalDate.of(2026, 9, 7)
    // Two nights, two days off, two days, one day off: night minutes every week.
    private val resolver = ScheduleResolver(Fixtures.all,
        WorkPattern.Rotation(monday, listOf(Fixtures.night.id, Fixtures.night.id, Fixtures.off.id, Fixtures.off.id, Fixtures.day.id, Fixtures.day.id, Fixtures.off.id)),
        emptyList())
    private val profile = PayProfile(
        occupation = Occupation.MANUFACTURING,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
    )
    private val oct = YearMonth.of(2026, 10)
    private fun pay(m: YearMonth) = PayCalculator().calculate(profile, m, resolver::resolve)
    private val who = PayReport.Who("제조업", "월급 300만 원")
    private val made = LocalDate.of(2026, 10, 22)

    @Test fun theMonthReportExplainsEveryLineAndSaysItIsAnEstimate() {
        val p = pay(oct)
        val doc = PayReport.month(p, who, made)
        val text = doc.text()
        assertEquals("2026년 10월 급여 리포트", doc.title)
        assertTrue(text, text.contains("세전 예상 합계 ${"%,d".format(p.total)}원"))
        p.lines.forEach { assertTrue(it.label, text.contains(it.formula)) }
        assertTrue(text.contains("요율 ${p.ratesVersion}"))
        assertTrue(text.contains(PayReport.NOTE))
        // Every worked day has a row, with its night minutes.
        val table = doc.blocks.filterIsInstance<ReportDoc.Table>().first { it.headers.first() == "날짜" }
        assertEquals(p.minutes.count { it.worked > 0 }, table.rows.size)
        assertTrue(table.rows.any { it[3].isNotEmpty() })
        // The lines' total row matches.
        val lines = doc.blocks.filterIsInstance<ReportDoc.Table>().first()
        assertEquals("%,d원".format(p.total), lines.total!!.last())
    }

    @Test fun theMonthCsvHasTheLinesThenTheDays() {
        val p = pay(oct)
        val csv = PayReport.monthCsv(p)
        assertTrue("엑셀이 한글을 읽게 BOM", csv.startsWith("﻿"))
        val rows = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(PayReport.MONTH_HEADER.joinToString(","), rows.first())
        assertEquals(p.lines.size, rows.count { it.startsWith("항목,") })
        assertEquals(p.minutes.count { it.worked > 0 }, rows.count { it.startsWith("날짜,") })
        assertTrue(rows.any { it.startsWith("합계,") && it.contains(",${p.total},") })
    }

    @Test fun theYearReportAddsTheMonthsUp() {
        val months = (1..12).map { pay(YearMonth.of(2026, it)) }
        val doc = PayReport.year(2026, months, who, made, closed = setOf(YearMonth.of(2026, 9)))
        val table = doc.blocks.filterIsInstance<ReportDoc.Table>().first()
        assertEquals(12, table.rows.size)
        assertEquals("%,d원".format(months.sumOf { it.total }), table.total!![5])
        assertEquals("마감", table.rows[8][6].substringBefore(","))
        val csv = PayReport.yearCsv(months).removePrefix("﻿").trimEnd().split("\r\n")
        // [PAYX-40] The 12 months, then what they rest on.
        assertEquals(14, csv.size)
        assertTrue(csv[10].startsWith("2026-10,"))
        assertTrue(csv.last(), csv.last().startsWith("계산 기준,") && csv.last().contains("공휴일") && csv.last().contains("근로기준법"))
        assertTrue(doc.footer.contains("요율 "))
    }

    @Test fun figuresSortTheMinutes() {
        val f = PayReport.figures(pay(oct))
        assertTrue(f.worked > 0)
        assertTrue("야간 근무가 있어요", f.night > 0)
        assertEquals(0, f.extra)
    }

    @Test fun theComparisonSaysWhatChanged() {
        val calc = WorkHoursCalculator(resolver, DayOfWeek.MONDAY)
        val now = MonthCompare.side(calc.month(oct), pay(oct))
        val before = MonthCompare.side(calc.month(oct.minusMonths(1)), pay(oct.minusMonths(1)))
        val rows = MonthCompare.rows(now, before)
        assertEquals(listOf("근무시간", "근무일", "야간", "휴일·쉬는 날 근무", "추가 근무", "급여(세전)"), rows.map { it.label })
        val worked = rows.first()
        val d = now.worked - before.worked
        assertEquals(if (d == 0) "같아요" else (if (d > 0) "+" else "−") + com.bamnat.core.hours.HoursFormat.hours(kotlin.math.abs(d).toDouble()) + "시간", worked.change)
        // Without a pay calculation there is no night or pay row.
        val plain = MonthCompare.rows(MonthCompare.side(calc.month(oct), null), MonthCompare.side(calc.month(oct.minusYears(1)), null))
        assertFalse(plain.any { it.label == "야간" || it.label.startsWith("급여") })
        assertTrue("작년 같은 달은 근무가 없어요", MonthCompare.side(calc.month(oct.minusYears(1)), null).empty)
        val year = MonthCompare.Year(2026, (1..12).map { MonthCompare.side(calc.month(YearMonth.of(2026, it)), null) })
        assertNull(year.pay)
        assertEquals((1..12).sumOf { calc.month(YearMonth.of(2026, it)).totalMinutes }, year.worked)
    }

    @Test fun theRosterPrintsEveryDay() {
        val doc = RosterReport.month(oct, resolver::resolve, "내 근무", made)
        val grid = doc.blocks.filterIsInstance<ReportDoc.MonthGrid>().single()
        assertEquals(31, grid.days.size)
        assertEquals("야", grid.days[oct.atDay(1).let { d -> (1..31).first { resolver.resolve(oct.atDay(it)).shiftType?.id == Fixtures.night.id } }]!!.chips.first().text)
        assertEquals("개천절", grid.days[3]!!.holiday)
        val legend = doc.blocks.filterIsInstance<ReportDoc.Legend>().single()
        assertTrue(legend.items.any { it.second.startsWith("야간 22:00~07:00") })
        val year = RosterReport.year(2026, resolver::resolve, "", made)
        assertTrue(year.landscape)
        assertEquals("근무표 · 2026년", year.title)
        val y = year.blocks.filterIsInstance<ReportDoc.YearGrid>().single()
        assertEquals(12, y.days.size)
        assertTrue(LocalDate.of(2026, 10, 3) in y.holidays)
    }
}

/** [FEAT-07] The shifts as the phone's calendar events, and what a change rewrites. */
class ShiftEventsTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val resolver = ScheduleResolver(Fixtures.all,
        WorkPattern.Rotation(monday, listOf(Fixtures.night.id, Fixtures.day.id, Fixtures.off.id)), emptyList())

    @Test fun workDaysAreEventsAndDaysOffAreNot() {
        val days = resolver.resolveRange(monday, monday.plusDays(5))
        val events = com.bamnat.core.schedule.ShiftEvents.of(days)
        assertEquals(4, events.size)
        val night = events.first()
        assertEquals("야간", night.title)
        assertEquals(monday.atTime(22, 0), night.start)
        assertEquals(monday.plusDays(1).atTime(7, 0), night.end)
        assertEquals("${monday}", night.key)
    }

    @Test fun onlyWhatChangedIsRewritten() {
        val days = resolver.resolveRange(monday, monday.plusDays(5))
        val events = com.bamnat.core.schedule.ShiftEvents.of(days)
        val existing = events.associate { it.key to it.fingerprint }.toMutableMap()
        assertTrue(com.bamnat.core.schedule.ShiftEvents.plan(existing, events).empty)
        existing[events[1].key] = "old"
        existing["2020-01-01"] = "gone"
        existing.remove(events[2].key)
        val plan = com.bamnat.core.schedule.ShiftEvents.plan(existing, events)
        assertEquals(listOf(events[2]), plan.insert)
        assertEquals(listOf(events[1]), plan.update)
        assertEquals(setOf("2020-01-01"), plan.delete)
    }
}
