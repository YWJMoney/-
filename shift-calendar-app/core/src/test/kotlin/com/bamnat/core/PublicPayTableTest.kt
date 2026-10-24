package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PublicGrade
import com.bamnat.core.pay.PublicPayTable
import com.bamnat.core.pay.PublicServantPay
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * [JOB-02][JOB-03] 2026 봉급표 against the law's own numbers (공무원보수규정 별표 3·10, 2026. 1. 2. 개정), the
 * 봉급기준액 of 공무원수당 등에 관한 규정 제15조, 정근수당·가산금·명절휴가비, and 시간외 by 제15조 제4항·제5항.
 */
class PublicPayTableTest {
    private val on = LocalDate.of(2026, 11, 15)
    private fun g(id: String) = PublicPayTable.grade(id)!!

    @Test fun tableMatchesTheLaw() {
        assertEquals(2_133_000L, PublicPayTable.salary(g("GENERAL:9급"), 1, on))
        assertEquals(2_542_700L, PublicPayTable.salary(g("GENERAL:9급"), 10, on))
        assertEquals(3_133_300L, PublicPayTable.salary(g("GENERAL:7급"), 10, on))
        assertEquals(4_656_100L, PublicPayTable.salary(g("GENERAL:1급"), 1, on))
        assertEquals(8_001_400L, PublicPayTable.salary(g("GENERAL:1급"), 23, on))
        assertEquals(4_967_800L, PublicPayTable.salary(g("GENERAL:6급"), 32, on))
        assertNull(PublicPayTable.salary(g("GENERAL:9급"), 32, on))
        assertEquals(23, PublicPayTable.maxStep(g("GENERAL:1급"), on))
        assertEquals(2_133_000L, PublicPayTable.salary(g("POLICE:순경"), 1, on))
        assertEquals(2_922_200L, PublicPayTable.salary(g("POLICE:경장"), 10, on))
        assertEquals(2_922_200L, PublicPayTable.salary(g("FIRE:소방교"), 10, on))
        assertEquals(5_372_500L, PublicPayTable.salary(g("POLICE:경감"), 32, on))
        assertEquals(8_250_400L, PublicPayTable.salary(g("FIRE:소방정감"), 23, on))
        // Lowest first, as people look for their own.
        assertEquals("9급", PublicPayTable.grades(PublicPayTable.Series.GENERAL).first().name)
        assertEquals("소방사", PublicPayTable.grades(PublicPayTable.Series.FIRE).first().name)
        // Every column rises with the 호봉.
        PublicPayTable.tables.forEach { t -> (t.general + t.policeFire).forEach { c -> assertTrue(c.toList().zipWithNext().all { (a, b) -> a < b }) } }
    }

    @Test fun standardBase() {
        // 기준호봉 10호봉 × 60% for 8급 이하, × 55% above; 순경·소방사 9호봉 (규정 별표 12).
        assertEquals(1_525_620L, PublicPayTable.standardBase(g("GENERAL:9급"), on))
        assertEquals(2_236_740L, PublicPayTable.standardBase(g("GENERAL:5급"), on))
        assertEquals(1_557_060L, PublicPayTable.standardBase(g("POLICE:순경"), on))
        assertEquals(1_802_130L, PublicPayTable.standardBase(g("POLICE:경사"), on))
    }

    @Test fun regularAllowances() {
        assertEquals(0.10, PublicPayTable.longevityRate(0), 1e-9)
        assertEquals(0.20, PublicPayTable.longevityRate(3), 1e-9)
        assertEquals(0.25, PublicPayTable.longevityRate(5), 1e-9)
        assertEquals(0.45, PublicPayTable.longevityRate(9), 1e-9)
        assertEquals(0.50, PublicPayTable.longevityRate(25), 1e-9)
        assertEquals(30_000L, PublicPayTable.longevityExtra(4))
        assertEquals(50_000L, PublicPayTable.longevityExtra(7))
        assertEquals(110_000L, PublicPayTable.longevityExtra(22))
        assertEquals(130_000L, PublicPayTable.longevityExtra(30))
        assertEquals(6, PublicPayTable.years(LocalDate.of(2020, 3, 1), on))
    }

    @Test fun overtimeByTheRegulation() {
        val mon = LocalDate.of(2026, 11, 2)
        // 평일 1시간 30분 → 30분, 평일 50분 → 0, 토요일 2시간 → 2시간: 150분, 2시간 (1시간 미만 버림).
        val week = mapOf(mon to 90, mon.plusDays(1) to 50, mon.plusDays(5) to 120)
        assertEquals(2, PublicPayTable.overtime(week, onSite = false).hours)
        // 현업: every minute, no cap: 260분 → 4시간.
        assertEquals(PublicPayTable.Overtime(4, null), PublicPayTable.overtime(week, onSite = true))
        // 20 weekdays of 4 hours: 60 hours counted, 57 paid.
        val month = (0L until 30L).map { LocalDate.of(2026, 11, 1).plusDays(it) }.filterNot { PublicPayTable.restDay(it) }.take(20).associateWith { 240 }
        val ot = PublicPayTable.overtime(month, onSite = false)
        assertEquals(20, month.size)
        assertEquals(60, ot.hours)
        assertEquals(57, ot.paidHours)
        assertTrue(ot.over)
        assertEquals("60/57", ot.text)
        assertEquals("80시간", PublicPayTable.overtime(month, onSite = true).text)
        // 공휴일 counts like a Saturday.
        assertTrue(PublicPayTable.restDay(LocalDate.of(2026, 10, 3)))
        assertFalse(PublicPayTable.restDay(mon))
    }

    @Test fun pickingAGradeFillsPayAndAllowances() {
        val p = PayPresets.firstSetup(PayProfile(Occupation.PUBLIC, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_500_000)))))
        val set = PublicServantPay.apply(p, PublicGrade("GENERAL:9급", 10, LocalDate.of(2020, 3, 1), onSite = false), on)
        assertEquals(2_542_700L, set.base.on(on).value)
        assertEquals(2_500_000L, set.base.on(LocalDate.of(2026, 10, 1)).value)
        assertEquals(RulePay.PerHour(10_949), set.customRules.single { it.id == PublicServantPay.OVERTIME_ID }.pay)
        assertEquals(3, set.customRules.size)
        assertFalse(PublicServantPay.ESTIMATED in set.estimatedFields)
        val longevity = set.items.single { it.id == PublicServantPay.LONGEVITY_ID }
        assertEquals(Cadence.BONUS, longevity.cadence)
        assertEquals(setOf(1, 7), longevity.months)
        assertEquals(0.30, longevity.percentOfBase!!, 1e-9)
        assertEquals(50_000L, set.items.single { it.id == PublicServantPay.LONGEVITY_EXTRA_ID }.amount.on(on).value)
        assertEquals(0.60, set.items.single { it.id == PublicServantPay.FESTIVAL_ID }.percentOfBase!!, 1e-9)
        // Picking again replaces, never doubles.
        val again = PublicServantPay.apply(set, set.publicGrade!!.copy(step = 11), on)
        assertEquals(3, again.items.count { it.id.startsWith("public-") })
        assertEquals(2_624_400L, again.base.on(on).value)
    }

    @Test fun overtimeLineUsesTheCountedHours() {
        val f = Fixtures
        val p = PublicServantPay.apply(
            PayPresets.firstSetup(PayProfile(Occupation.PUBLIC, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_542_700))))),
            PublicGrade("GENERAL:9급", 10, onSite = false), on,
        )
        val mon = LocalDate.of(2026, 11, 2)
        // Mon–Fri 주간 with 2 hours of 잔업 each day: 10 hours worked late, 5 counted after the daily hour.
        val extras = (0L until 5L).map { DayEntry(mon.plusDays(it), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))) }
        val r = ScheduleResolver(f.all, WorkPattern.Rotation(mon, listOf(f.day.id, f.day.id, f.day.id, f.day.id, f.day.id, f.off.id, f.off.id)), extras)
        val pay = PayCalculator().calculate(p, YearMonth.of(2026, 11), r::resolve)
        val line = pay.lines.single { it.label.contains("시간외근무수당") }
        assertEquals(5 * 10_949L, line.amount)
        assertTrue(line.formula, line.formula.contains("5시간"))
        // 현업: all 10 hours.
        val onSite = p.copy(publicGrade = p.publicGrade!!.copy(onSite = true))
        assertEquals(10 * 10_949L, PayCalculator().calculate(onSite, YearMonth.of(2026, 11), r::resolve).lines.single { it.label.contains("시간외근무수당") }.amount)
    }
}
