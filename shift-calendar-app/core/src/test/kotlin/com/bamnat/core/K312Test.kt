package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CalcSnapshot
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayExplain
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateNews
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.SnapshotDiff
import com.bamnat.core.pay.SwapImpact
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WhatIf
import com.bamnat.core.pay.YearEndPreview
import com.bamnat.core.report.CounselBundle
import com.bamnat.core.report.PayReport
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream

/** 1.23 설명·비교·리포트 (row 311) and 혁신 (row 312). */
class K312Test {
    private val monday = LocalDate.of(2026, 9, 28)
    private val weekdays = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, 1L, 1L, null, null)), emptyList())
    private val monthly = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))))
    private val oct = YearMonth.of(2026, 10)

    @Test fun `every line explains itself with its law`() {
        val pay = PayCalculator().calculate(monthly, oct, weekdays::resolve)
        pay.lines.forEach { l ->
            val e = PayExplain.explain(l)
            assertTrue(e, e.contains("근거: ") && PayExplain.basisOf(l).isNotBlank())
        }
        assertTrue(PayExplain.basisOf(PayLine(LineKind.NIGHT, "야간", 1, "")).contains("제56조 제3항"))
        assertTrue(PayExplain.explain(PayLine(LineKind.BASE, "기본급", 1, "f", estimated = true)).contains("짐작"))
    }

    @Test fun `a closed month shows only the lines that changed`() {
        val before = listOf(PayLine(LineKind.BASE, "기본급", 3_000_000, ""), PayLine(LineKind.NIGHT, "야간근로수당", 100_000, "", minutes = 600))
        val after = listOf(PayLine(LineKind.BASE, "기본급", 3_000_000, ""), PayLine(LineKind.NIGHT, "야간근로수당", 134_000, "", minutes = 720),
            PayLine(LineKind.ALLOWANCE, "식대", 200_000, ""))
        val c = SnapshotDiff.lines(before, after)
        assertEquals(listOf("야간근로수당", "식대"), c.map { it.label })
        assertEquals("야간근로수당 2시간 늘어 +34,000원", c[0].text)
        assertEquals("식대 새로 생겨 +200,000원", c[1].text)
        val pay = PayCalculator().calculate(monthly, oct, weekdays::resolve)
        assertTrue(SnapshotDiff.of(CalcSnapshot.close(pay, 1), pay).isEmpty())
    }

    @Test fun `what if a raise or another month's hours`() {
        val base = WhatIf.answer(monthly, oct, weekdays::resolve, WhatIf.Ask())
        assertEquals(0L, base.diff)
        val up = WhatIf.answer(monthly, oct, weekdays::resolve, WhatIf.Ask(raisePercent = 10))
        // The 기본급 line goes up 10%, and every line paid by the 통상시급 with it.
        assertTrue("${up.diff}", up.diff >= 300_000L)
        assertEquals(3_300_000L, WhatIf.raised(monthly, 10, LocalDate.of(2026, 10, 1)).base.on(oct).value)
        // No night shift in the month: nothing to add per night.
        assertNull(WhatIf.answer(monthly, oct, weekdays::resolve, WhatIf.Ask(moreNights = 3)).nightDay)
        val nights = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(3L, 3L, null, null)), emptyList())
        val n = WhatIf.answer(monthly.copy(wageType = WageType.HOURLY, base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_320)))),
            oct, nights::resolve, WhatIf.Ask(moreNights = 2))
        assertTrue(n.nightDay!! > 0)
        assertEquals(n.nightDay!! * 2, n.diff)
    }

    @Test fun `year end preview pieces follow the income tax act`() {
        assertEquals(10_650_000L, YearEndPreview.earnedDeduction(36_000_000))
        assertEquals(20_000_000L, YearEndPreview.earnedDeduction(10_000_000_000))
        assertEquals(1_740_000L, YearEndPreview.basicTax(20_000_000))
        assertEquals(0L, YearEndPreview.basicTax(0))
        assertEquals(716_000L, YearEndPreview.earnedCredit(1_740_000, 36_000_000))
        assertEquals(550_000L, YearEndPreview.earnedCredit(1_000_000, 20_000_000))
        assertEquals(950_000L, YearEndPreview.childCredit(3))
        assertEquals(0L, YearEndPreview.childCredit(0))
        // Half a year counted: run on to twelve months.
        val r = YearEndPreview.estimate(2026, gross = 18_000_000, insurance = 800_000, pension = 810_000, withheld = 450_000, months = 6, dependents = 1, children = 0)
        assertEquals(36_000_000L, r.salary)
        assertEquals(900_000L, r.withheld)
        assertTrue(r.determined in 0..r.calculated)
        assertEquals(((r.withheld - r.determined) * 1.1).toLong(), r.refund, 1.0)
        assertTrue(YearEndPreview.LEFT_OUT.contains("신용카드"))
        val y = YearEndPreview.ofYear(monthly.copy(taxFamily = 1), YearMonth.of(2026, 10), weekdays::resolve)!!
        assertEquals(10, y.months)
        assertTrue(y.salary > 30_000_000)
    }

    private fun assertEquals(a: Long, b: Long, tolerance: Double) = assertTrue("$a vs $b", kotlin.math.abs(a - b) <= tolerance)

    @Test fun `a swap that puts a week over 52 hours says so`() {
        val hourly = monthly.copy(wageType = WageType.HOURLY, base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_320))))
        val sat = LocalDate.of(2026, 10, 3)
        val day = weekdays.resolve(LocalDate.of(2026, 10, 5))
        val r = SwapImpact.of(hourly, oct, weekdays::resolve, mapOf(sat to day.copy(date = sat)))
        assertTrue(r.diff > 0)
        assertFalse(r.overLimit)
        assertTrue(r.longestWeekAfter >= r.longestWeekBefore)
    }

    @Test fun `a new rates year is told once with the user's own wage`() {
        val y26 = RateTable.builtIn.first { it.effectiveFrom.year == 2026 }
        val y27 = y26.copy(effectiveFrom = LocalDate.of(2027, 1, 1), version = "2027.t", minimumHourlyWage = y26.minimumHourlyWage + 400)
        val table = listOf(y26, y27)
        val news = RateNews.of(LocalDate.of(2027, 1, 10), y26.minimumHourlyWage + 100, table)!!
        assertTrue(news.belowMinimum)
        assertEquals("rates-2027.t", news.key)
        assertTrue(news.lines.first().startsWith("최저임금 시급"))
        assertTrue(news.lines.last().contains("적어요"))
        // Months later the card is no longer news; before the year is close, neither.
        assertNull(RateNews.of(LocalDate.of(2027, 6, 1), null, table))
        assertNull(RateNews.of(LocalDate.of(2026, 9, 1), null, table))
    }

    @Test fun `the counsel bundle is readable files in one zip`() {
        val pay = PayCalculator().calculate(monthly, oct, weekdays::resolve)
        val weeks = CounselBundle.weeksCsv(listOf(pay))
        assertTrue(weeks.contains("52시간 넘음"))
        assertTrue(weeks.contains("2026-10-05,2026-10-11"))
        val files = linkedMapOf(
            "급여_2026-10.csv" to PayReport.monthCsv(pay).toByteArray(),
            "주별_근무시간.csv" to weeks.toByteArray(),
        )
        files[CounselBundle.README] = CounselBundle.readme(LocalDate.of(2026, 10, 23), files.keys.toList(), pay.ratesVersion).toByteArray()
        val names = mutableListOf<String>()
        ZipInputStream(CounselBundle.zip(files).inputStream(), Charsets.UTF_8).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name }
        }
        assertEquals(files.keys.toList(), names)
        assertTrue(CounselBundle.readme(LocalDate.of(2026, 10, 23), names, pay.ratesVersion).contains("1350"))
    }
}
