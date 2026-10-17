package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [PAYX-14] Overnight shifts across a 공휴일, [PAYX-15] pay periods that close mid-month. */
class M7Test {
    private val oct = YearMonth.of(2026, 10)
    private val hangul = LocalDate.of(2026, 10, 9) // 한글날, Friday
    private val dayShift = ShiftType(1, "주간", "주", 0xFF000000, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(2, "야간", "야", 0xFF000000, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(3, "휴무", "휴", 0xFF000000, null, null, isOff = true)
    private val profile = PayProfile(
        occupation = Occupation.CUSTOM, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 12_000L * 209))),
    )
    private fun com.bamnat.core.pay.MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }
    private val byStart = profile.copy(rules = profile.rules.copy(holidayByShiftStart = true))

    private fun only(shift: ShiftType, date: LocalDate) =
        ScheduleResolver(listOf(shift, off), WorkPattern.Rotation(date, listOf(shift.id) + List(60) { off.id }), emptyList())

    private fun holidayMinutes(p: PayProfile, shift: ShiftType, date: LocalDate, month: YearMonth = oct): Int =
        PayCalculator().calculate(p, month, only(shift, date)::resolve).minutes.single { it.date == date }
            .let { it.holidayWithin8h + it.holidayOver8h }

    @Test
    fun nightBeforeTheHolidayEndingAt7IsAnOrdinaryDayByStartDayRule() {
        // The night of 10/8 runs into 한글날 until 07:00.
        assertEquals(7 * 60, holidayMinutes(profile, night, hangul.minusDays(1)))
        assertEquals(0, holidayMinutes(byStart, night, hangul.minusDays(1)))
    }

    @Test
    fun shiftsStartingOnTheHolidayAreHolidayWorkByStartDayRule() {
        assertEquals(12 * 60, holidayMinutes(profile, dayShift, hangul))
        assertEquals(12 * 60, holidayMinutes(byStart, dayShift, hangul))
        // The night of 한글날 runs to 07:00 on the 10th: 5 hours on the holiday by the clock.
        assertEquals(5 * 60, holidayMinutes(profile, night, hangul))
        assertEquals(12 * 60, holidayMinutes(byStart, night, hangul))
    }

    @Test
    fun theOrdinaryPartOfASplitNightIsRegularThenOvertimePastEightHours() {
        val m = PayCalculator().calculate(profile, oct, only(night, hangul)::resolve).minutes.single { it.date == hangul }
        assertEquals(5 * 60, m.holidayWithin8h)
        assertEquals(7 * 60, m.regular)
        assertEquals(0, m.overtime)
        assertTrue(m.night > 0)
    }

    @Test
    fun aPeriodClosingOnThe20thPaidOnThe25th() {
        val p = PayPeriod(endDay = 20, payDay = 25)
        assertEquals(LocalDate.of(2026, 9, 21), p.start(oct))
        assertEquals(LocalDate.of(2026, 10, 20), p.end(oct))
        assertEquals(oct, p.monthOf(LocalDate.of(2026, 10, 20)))
        assertEquals(oct.plusMonths(1), p.monthOf(LocalDate.of(2026, 10, 21)))
        assertEquals(LocalDate.of(2026, 10, 25), p.payDate(oct))
        assertEquals("9/21~10/20", p.label(oct))
        // Short months close on their last day and the next period starts the day after.
        val p30 = PayPeriod(endDay = 30, payDay = 10, payNextMonth = true)
        assertEquals(LocalDate.of(2027, 2, 28), p30.end(YearMonth.of(2027, 2)))
        assertEquals(LocalDate.of(2027, 3, 1), p30.start(YearMonth.of(2027, 3)))
        assertEquals(LocalDate.of(2027, 3, 10), p30.payDate(YearMonth.of(2027, 2)))
        assertNull(PayPeriod().payDate(oct))
        assertEquals(oct.atDay(1), PayPeriod().start(oct))
    }

    @Test
    fun overtimeAfterTheCloseCountsInNextMonthsPay() {
        val p = profile.copy(period = PayPeriod(endDay = 20, payDay = 25))
        val d = LocalDate.of(2026, 10, 26)
        val r = ScheduleResolver(listOf(dayShift, off), WorkPattern.Rotation(d, listOf(off.id)), listOf(DayEntry(d, overtimeMinutes = 120)))
        val october = PayCalculator().calculate(p, oct, r::resolve)
        val november = PayCalculator().calculate(p, oct.plusMonths(1), r::resolve)
        assertEquals(0, october.sum(LineKind.OVERTIME))
        assertTrue(november.sum(LineKind.OVERTIME) > 0)
        assertTrue(november.days.any { it.date == d })
        assertTrue(october.days.none { it.date == d })
        // The whole period, from 9/21, is in October's days.
        assertEquals(LocalDate.of(2026, 9, 21), october.days.minOf { it.date })
    }
}
