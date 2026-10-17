package com.bamnat.core

import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.ClassifyOptions
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.MinuteClassifier
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Two shifts on one day, e.g. 주간 then a 야간 대근 [FEAT-02, EXTRA-01]. */
class SecondShiftTest {
    private val date = LocalDate.of(2026, 10, 7)
    private val classifier = MinuteClassifier({ RateTable.on(it) })

    /** A 대근 that only says which shift it covered takes that shift's hours. */
    private fun cover(type: ShiftType) = DayEntry(date, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, coveredShiftId = type.id)))

    private fun resolver(pattern: Long?, vararg entries: DayEntry) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(date, listOf(pattern)), entries.toList())

    @Test
    fun `a second shift adds its own minutes and night time`() {
        val day = resolver(Fixtures.day.id, cover(Fixtures.night)).resolve(date)
        val extra = day.extras.single()
        assertEquals("야간", extra.covered!!.name)
        assertEquals(LocalTime.of(22, 0), extra.start)
        assertEquals(480 + 480, day.totalMinutes)
        assertEquals(480, day.extraMinutes)
        val m = classifier.classifyDay(day, holiday = false)
        // The day shift already fills 8 hours, so the whole night shift is overtime.
        assertEquals(480, m.regular)
        assertEquals(0, m.extraRegular)
        assertEquals(480, m.overtime)
        assertEquals(427, m.night)
    }

    @Test
    fun `a cover on a day off is work outside the roster by law and holiday work when the company says`() {
        // [EXTRA-02] A 휴무일 is not a holiday: within 8 hours and 40 a week it is paid 100%, not 150%.
        val day = resolver(null, cover(Fixtures.night)).resolve(date)
        val m = classifier.classify(listOf(day)) { false }.single()
        assertEquals(480, m.extraRegular)
        assertEquals(0, m.regular + m.overtime + m.holidayWithin8h + m.restWithin8h)
        val asHoliday = MinuteClassifier({ RateTable.on(it) }, ClassifyOptions(restDayWork = RestDayWork.HOLIDAY))
            .classify(listOf(day)) { false }.single()
        assertEquals(480, asHoliday.restWithin8h)
        // A paid holiday is 휴일근로 under every rule.
        assertEquals(480, classifier.classify(listOf(day)) { true }.single().holidayWithin8h)
    }

    @Test
    fun `a short first shift leaves room for regular time`() {
        val four = Fixtures.day.copy(id = 9, start = LocalTime.of(9, 0), end = LocalTime.of(13, 0), breakMinutes = 0)
        val r = ScheduleResolver(Fixtures.all + four, WorkPattern.Rotation(date, listOf(four.id)), listOf(cover(Fixtures.evening)))
        val m = classifier.classifyDay(r.resolve(date), holiday = false)
        // 4h + 7h30m: the shift's 4h, 4h more within 8 (100%), 3h30m overtime.
        assertEquals(240, m.regular)
        assertEquals(240, m.extraRegular)
        assertEquals(210, m.overtime)
    }

    @Test
    fun `night allowance counts the second night shift and alarms ring for both`() {
        val profile = PayProfile(
            occupation = Occupation.NURSE, wageType = WageType.MONTHLY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
            items = listOf(PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 30_000))), Cadence.PER_NIGHT_SHIFT)),
        )
        val r = resolver(Fixtures.day.id, cover(Fixtures.night))
        val pay = PayCalculator().calculate(profile, YearMonth.of(2026, 10), r::resolve) { false }
        assertEquals(30_000, pay.day(date)!!.parts.single { it.label == "나이트 수당" }.amount)
        assertEquals("주간 + 야간 대근", pay.day(date)!!.label)
        val alarms = AlarmPlanner.alarmsOn(r, date)
        assertTrue(alarms.any { it.shiftName == "주간" })
        assertTrue(alarms.any { it.shiftName == "야간" })
    }
}
