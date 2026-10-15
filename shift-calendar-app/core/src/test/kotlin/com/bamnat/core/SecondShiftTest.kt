package com.bamnat.core

import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.MinuteClassifier
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Two shifts on one day, e.g. 주간 then a 야간 대근 [FEAT-02]. */
class SecondShiftTest {
    private val date = LocalDate.of(2026, 10, 7)
    private val classifier = MinuteClassifier({ RateTable.on(it) })

    private fun resolver(pattern: Long?, vararg entries: DayEntry) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(date, listOf(pattern)), entries.toList())

    @Test
    fun `a second shift adds its own minutes and night time`() {
        val day = resolver(Fixtures.day.id, DayEntry(date, secondShiftId = Fixtures.night.id)).resolve(date)
        assertEquals("야간", day.second!!.type.name)
        assertEquals(480 + 480, day.totalMinutes)
        assertEquals(480, day.extraMinutes)
        val m = classifier.classifyDay(day, holiday = false)
        // The day shift already fills 8 hours, so the whole night shift is overtime.
        assertEquals(480, m.regular)
        assertEquals(480, m.overtime)
        assertEquals(427, m.night)
    }

    @Test
    fun `a second shift on a day off is holiday work`() {
        val day = resolver(null, DayEntry(date, secondShiftId = Fixtures.night.id)).resolve(date)
        val m = classifier.classify(listOf(day)) { false }.single()
        assertEquals(480, m.holidayWithin8h)
        assertEquals(0, m.regular + m.overtime)
    }

    @Test
    fun `a short first shift leaves room for regular time`() {
        val four = Fixtures.day.copy(id = 9, start = LocalTime.of(9, 0), end = LocalTime.of(13, 0), breakMinutes = 0)
        val r = ScheduleResolver(Fixtures.all + four, WorkPattern.Rotation(date, listOf(four.id)),
            listOf(DayEntry(date, secondShiftId = Fixtures.evening.id)))
        val m = classifier.classifyDay(r.resolve(date), holiday = false)
        // 4h + 7h30m: 8h regular, 3h30m overtime.
        assertEquals(480, m.regular)
        assertEquals(210, m.overtime)
    }

    @Test
    fun `night allowance counts the second night shift and alarms ring for both`() {
        val profile = PayProfile(
            occupation = Occupation.NURSE, wageType = WageType.MONTHLY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
            items = listOf(PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 30_000))), Cadence.PER_NIGHT_SHIFT)),
        )
        val r = resolver(Fixtures.day.id, DayEntry(date, secondShiftId = Fixtures.night.id))
        val pay = PayCalculator().calculate(profile, YearMonth.of(2026, 10), r::resolve) { false }
        assertEquals(30_000, pay.day(date)!!.parts.single { it.label == "나이트 수당" }.amount)
        assertEquals("주간 + 야간", pay.day(date)!!.label)
        val alarms = AlarmPlanner.alarmsOn(r, date)
        assertTrue(alarms.any { it.shiftName == "주간" })
        assertTrue(alarms.any { it.shiftName == "야간" })
    }
}
