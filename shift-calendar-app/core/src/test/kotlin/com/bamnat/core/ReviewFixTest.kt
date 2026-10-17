package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** Regressions for the full code review of 2026-10-17. */
class ReviewFixTest {
    private val day = ShiftType(1, "주간", "주", 0, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(2, "야간", "야", 0, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(3, "휴무", "휴", 0, null, null, isOff = true)
    private val cycle = listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)
    private val since = LocalDate.of(2025, 1, 1)
    private val roster = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(LocalDate.of(2026, 10, 1), cycle), emptyList())
    private fun profile(wage: WageType) = PayProfile(Occupation.CUSTOM, wage,
        ValueTimeline(listOf(DatedValue(since, if (wage == WageType.HOURLY) 12_000 else 2_500_000))),
        scheduleIsRegular = true, flexPeriod = FlexPeriod.QUARTER, rules = CompanyRules(weeklyHolidayPay = true))

    @Test
    fun `a quarter's excess never adds minutes that were not worked`() {
        // 2026-12-31 is a day off in this roster; the 1,577 excess minutes used to land there on top.
        val dec = PayCalculator().calculate(profile(WageType.HOURLY), YearMonth.of(2026, 12), roster::resolve) { false }
        val scheduled = (1..31).sumOf { roster.resolve(LocalDate.of(2026, 12, it)).scheduledMinutes }
        assertEquals(scheduled, dec.minutes.sumOf { it.worked })
        assertEquals(1_577, dec.lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.minutes })
        assertEquals(0, dec.minutes.single { it.date == LocalDate.of(2026, 12, 31) }.worked)
    }

    @Test
    fun `weekly holiday pay counts each week once under a quarterly period`() {
        val dec = PayCalculator().calculate(profile(WageType.HOURLY), YearMonth.of(2026, 12), roster::resolve) { false }
        val jan = PayCalculator().calculate(profile(WageType.HOURLY), YearMonth.of(2027, 1), roster::resolve) { false }
        // Weeks run Monday to Sunday: December owns the four ending Dec 6, 13, 20, 27; Jan 3 ends in January.
        assertTrue(dec.lines.single { it.label == "주휴수당" }.formula.startsWith("4주"))
        assertTrue(jan.lines.single { it.label == "주휴수당" }.formula.startsWith("5주"))
    }

    @Test
    fun `a cover shift on a day of annual leave is not holiday work`() {
        val wed = LocalDate.of(2026, 10, 14)
        val r = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(LocalDate.of(2026, 10, 12), List(5) { day.id } + List(2) { off.id }),
            listOf(DayEntry(wed, leave = Leave(), extras = listOf(ExtraEntry(ExtraKind.COVER_ID, coveredShiftId = night.id)))))
        val p = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(since, 2_500_000))))
        val m = PayCalculator().calculate(p, YearMonth.of(2026, 10), r::resolve).minutes.single { it.date == wed }
        assertEquals(0, m.holidayWithin8h + m.holidayOver8h)
        assertTrue(m.worked > 0)
    }

    @Test
    fun `zero monthly hours does not make the hourly rate infinite`() {
        val p = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(since, 2_500_000))), monthlyHours = 0)
        val pay = PayCalculator().calculate(p, YearMonth.of(2026, 12), roster::resolve)
        assertTrue(pay.ordinaryHourly < 10_000_000)
    }

    @Test
    fun `payment texts with foreign amounts, bank words in names and huge numbers`() {
        val now = LocalDateTime.of(2026, 10, 17, 12, 0)
        val fx = (PaymentParser.parse("우리(5678)승인 USD 12.30 (16,800원) 10/12 APPLE", now) as PaymentParser.Result.Ok).parsed
        assertEquals(LocalDate.of(2026, 10, 12), fx.date)
        val shop = (PaymentParser.parse("GS25 우리동네점 3,000원 결제", now) as PaymentParser.Result.Ok).parsed
        assertNull(shop.issuer)
        assertTrue(shop.merchant, shop.merchant.contains("우리동네점"))
        assertTrue(PaymentParser.parse("결제 123456789012345678901원", now) is PaymentParser.Result.Fail)
    }
}
