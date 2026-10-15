package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftIcon
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LeaveBook
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
import java.time.LocalTime
import java.time.YearMonth

/** 연차·반차·반반차 on the calendar and in pay [FEAT-10], shift icons [FEAT-01]. */
class LeaveTest {
    private val monday = LocalDate.of(2026, 9, 28)
    private val wed = LocalDate.of(2026, 10, 7)
    private val weekdays = listOf(1L, 1L, 1L, 1L, 1L, null, null)

    private fun resolver(vararg entries: DayEntry) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, weekdays), entries.toList())

    @Test
    fun `half day leave comes off the end of the shift`() {
        val d = resolver(DayEntry(wed, leave = Leave(LeaveKind.ANNUAL, 2))).resolve(wed)
        assertEquals(240, d.leaveMinutes)
        assertEquals(240, d.scheduledMinutes)
        assertEquals(30, d.breakMinutes)
        assertEquals(LocalTime.of(13, 30), d.end)
        assertEquals("반차", d.leave!!.label)
    }

    @Test
    fun `full day leave clears the work but keeps the shift`() {
        val d = resolver(DayEntry(wed, leave = Leave())).resolve(wed)
        assertTrue(d.isOff)
        assertNull(d.start)
        assertEquals(480, d.leaveMinutes)
        assertEquals("주간", d.shiftType!!.name)
        // Leave on a day off means nothing.
        val sat = LocalDate.of(2026, 10, 10)
        assertNull(resolver(DayEntry(sat, leave = Leave())).resolve(sat).leave)
    }

    @Test
    fun `leave codes round trip`() {
        for (k in LeaveKind.entries) for (q in 1..4) assertEquals(Leave(k, q), Leave.fromCode(Leave(k, q).code))
        assertNull(Leave.fromCode(0))
        assertEquals("반반차", Leave(quarters = 1).label)
        assertEquals("무급 휴가 반차", Leave(LeaveKind.UNPAID, 2).label)
    }

    private val monthly = PayProfile(
        occupation = Occupation.MANUFACTURING,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
    )

    @Test
    fun `monthly pay keeps paid leave and deducts unpaid leave`() {
        val month = YearMonth.of(2026, 10)
        val paid = PayCalculator().calculate(monthly, month, resolver(DayEntry(wed, leave = Leave()))::resolve) { false }
        assertEquals(3_000_000, paid.total)
        assertEquals(0, paid.day(wed)!!.amount)
        val unpaid = PayCalculator().calculate(monthly, month, resolver(DayEntry(wed, leave = Leave(LeaveKind.UNPAID)))::resolve) { false }
        val line = unpaid.lines.single { it.kind == LineKind.LEAVE }
        assertEquals(-8 * unpaid.ordinaryHourly, line.amount)
        assertEquals(3_000_000 + line.amount, unpaid.total)
    }

    @Test
    fun `hourly pay pays annual leave and keeps the weekly holiday pay`() {
        val hourly = monthly.copy(occupation = Occupation.PART_TIME, wageType = WageType.HOURLY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 11_000))))
        val month = YearMonth.of(2026, 10)
        val none = PayCalculator().calculate(hourly, month, resolver()::resolve) { false }
        val leave = PayCalculator().calculate(hourly, month, resolver(DayEntry(wed, leave = Leave()))::resolve) { false }
        assertEquals(88_000, leave.lines.single { it.kind == LineKind.LEAVE }.amount)
        assertEquals(none.total, leave.total)
        assertEquals(none.lines.single { it.label == "주휴수당" }.amount, leave.lines.single { it.label == "주휴수당" }.amount)
    }

    @Test
    fun `annual leave balance counts used and planned`() {
        val r = resolver(
            DayEntry(LocalDate.of(2026, 10, 5), leave = Leave()),
            DayEntry(wed, leave = Leave(quarters = 2)),
            DayEntry(LocalDate.of(2026, 10, 8), leave = Leave(quarters = 1)),
            DayEntry(LocalDate.of(2026, 10, 9), leave = Leave(LeaveKind.UNPAID)),
        )
        val b = LeaveBook.balance(2026, 15.0, today = wed, resolve = r::resolve)
        assertEquals(1.5, b.used, 1e-9)
        assertEquals(0.25, b.planned, 1e-9)
        assertEquals(13.25, b.remaining!!, 1e-9)
        assertEquals(1.0, b.byKind[LeaveKind.UNPAID]!!, 1e-9)
        assertEquals("13.25일", LeaveBook.days(13.25))
        assertEquals("2일", LeaveBook.days(2.0))
    }

    @Test
    fun `icons are suggested from the shift times`() {
        assertEquals(ShiftIcon.SUN, ShiftIcon.suggest(Fixtures.day))
        assertEquals(ShiftIcon.EVENING, ShiftIcon.suggest(Fixtures.evening))
        assertEquals(ShiftIcon.MOON, ShiftIcon.suggest(Fixtures.night))
        assertEquals(ShiftIcon.BED, ShiftIcon.suggest(Fixtures.off))
        assertEquals(ShiftIcon.MOON, ShiftIcon.of("moon"))
        assertNull(ShiftIcon.of("nope"))
    }

    @Test
    fun `2027 rates use the announced minimum wage`() {
        val r = com.bamnat.core.pay.RateTable.on(LocalDate.of(2027, 3, 1))
        assertEquals("2027.1", r.version)
        assertEquals(10_700, r.minimumHourlyWage)
        assertEquals(0.10, r.pensionRate!!, 1e-9)
        assertNull(r.longTermCareRate)
        assertEquals(10_320, com.bamnat.core.pay.RateTable.on(LocalDate.of(2026, 12, 31)).minimumHourlyWage)
    }
}
