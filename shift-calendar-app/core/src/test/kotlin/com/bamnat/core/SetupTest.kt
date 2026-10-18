package com.bamnat.core

import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.schedule.ShiftRole
import com.bamnat.core.setup.FollowUp
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.setup.TodayQuestion
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [SETUP-01][SETUP-02] The first setup: bundles and their orders, the today question and the draft's steps. */
class SetupTest {
    private val today = LocalDate.of(2026, 10, 17)
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(today, java.time.LocalTime.of(h, m))

    /** 4조 2교대 주주휴휴야야휴휴 from the 2교대 bundle: 주간 -1, 야간 -2, 휴무 -3. */
    private fun twoShift(codes: String = "DDOONNOO"): SetupDraft {
        val draft = SetupDraft(style = WorkStyle.SHIFT).withShifts(ShiftBundles.TWO, ShiftBundles.byKey(ShiftBundles.TWO)!!.shifts)
        val preset = PatternPresets.all.first { it.codes == codes }
        return draft.copy(rotation = PatternPresets.toSteps(preset, draft.roles))
    }

    @Test fun `a night shift is still yesterday's until an hour after it ends`() {
        val two = twoShift().types
        assertEquals(today.minusDays(1), TodayQuestion.questionDate(at(3), two))
        assertEquals(today.minusDays(1), TodayQuestion.questionDate(at(7, 59), two))
        assertEquals(today, TodayQuestion.questionDate(at(8), two))
        assertEquals(today, TodayQuestion.questionDate(at(21), two))
        // 24시간 당번 09~09: at 8 the duty that began yesterday is still on; at half past 10 it is today.
        val duty = ShiftBundles.byKey(ShiftBundles.DUTY)!!.shifts.map { it.type }
        assertEquals(today.minusDays(1), TodayQuestion.questionDate(at(8), duty))
        assertEquals(today, TodayQuestion.questionDate(at(10, 30), duty))
        // Only day shifts: always today.
        assertEquals(today, TodayQuestion.questionDate(at(3), ShiftBundles.weekday.map { it.type }))
    }

    @Test fun `the second night of a 2-day night run puts step 0 three days back`() {
        val d = twoShift("DDNNOOOO")
        val night = d.types.first { it.name == "야간" }.id
        val follow = TodayQuestion.followUp(d.rotation, night, d::isOff) as FollowUp.RunDay
        assertEquals(listOf(2, 3), follow.steps)
        assertEquals(today.minusDays(3), TodayQuestion.anchor(today, follow.steps[1]))
        val pattern = d.copy(anchor = today.minusDays(3)).pattern(today) as WorkPattern.Rotation
        assertEquals(night, pattern.shiftTypeIdOn(today))
        // The day before is the first night (the save fills the past too).
        assertEquals(night, pattern.steps[pattern.stepIndexOn(today.minusDays(1))])
        assertEquals(d.types.first { it.name == "휴무" }.id, pattern.shiftTypeIdOn(today.plusDays(1)))
    }

    @Test fun `a day off is told apart by when the next work is`() {
        val d = twoShift()
        val off = d.types.first { it.isOff }.id
        val follow = TodayQuestion.followUp(d.rotation, off, d::isOff) as FollowUp.NextWork
        val day = d.types.first { it.name == "주간" }.id
        val night = d.types.first { it.name == "야간" }.id
        assertEquals(
            listOf(FollowUp.NextWork.Option(3, 1, night), FollowUp.NextWork.Option(7, 1, day),
                FollowUp.NextWork.Option(2, 2, night), FollowUp.NextWork.Option(6, 2, day)),
            follow.options,
        )
        // A shift with a single day needs no second question.
        val single = SetupDraft(style = WorkStyle.SHIFT).withShifts(ShiftBundles.DUTY, ShiftBundles.byKey(ShiftBundles.DUTY)!!.shifts)
        val xbo = PatternPresets.toSteps(PatternPresets.all.first { it.codes == "XBO" }, single.roles)
        assertNull(TodayQuestion.followUp(xbo, xbo[0], single::isOff))
        assertNull(TodayQuestion.followUp(xbo, xbo[1], single::isOff))
    }

    @Test fun `a run that wraps around the end of the order is still one run`() {
        // 휴주주휴: the two 휴 days are one run across the end.
        val steps = listOf(-3L, -1L, -1L, -3L)
        val follow = TodayQuestion.followUp(steps, -3L) { it == -3L } as FollowUp.RunDay
        assertEquals(listOf(3, 0), follow.steps)
        assertEquals(1 to 2, TodayQuestion.runPosition(steps, 3))
        assertEquals(2 to 2, TodayQuestion.runPosition(steps, 0))
        assertEquals(1 to 2, TodayQuestion.runPosition(steps, 1))
    }

    @Test fun `a shift in two runs is told apart by the days that follow`() {
        // 주야휴주주휴: 주간 on steps 0, 3 and 4.
        val steps = listOf(-1L, -2L, -3L, -1L, -1L, -3L)
        val follow = TodayQuestion.followUp(steps, -1L) { it == -3L } as FollowUp.Upcoming
        assertEquals(listOf(0, 3, 4), follow.options.map { it.step })
        assertEquals(listOf(-2L, -3L, -1L), follow.options[0].days)
        assertTrue(follow.options.map { it.days }.distinct().size == 3)
        // An order that repeats inside itself (주야주야) has one answer for 주간.
        assertNull(TodayQuestion.followUp(listOf(-1L, -2L, -1L, -2L), -1L) { false })
    }

    @Test fun `each bundle offers only the orders its shifts can fill`() {
        val two = ShiftBundles.byKey(ShiftBundles.TWO)!!
        assertEquals(setOf("DDNNOO", "DNOO", "DDDNNNOOO", "DDNNOOOO", "DDOONNOO"), two.presets.map { it.codes }.toSet())
        assertEquals(4, ShiftBundles.byKey(ShiftBundles.THREE)!!.presets.size)
        assertEquals(4, ShiftBundles.byKey(ShiftBundles.HOSPITAL)!!.presets.size)
        val duty = ShiftBundles.byKey(ShiftBundles.DUTY)!!
        assertEquals(listOf("XB", "XBO", "XBXBOOOO"), duty.presets.map { it.codes })
        // 비번 takes the B days, 휴무 the O days.
        val d = SetupDraft(style = WorkStyle.SHIFT).withShifts(duty.key, duty.shifts)
        val ids = d.types.associate { it.name to it.id }
        assertEquals(listOf(ids["당번"], ids["비번"], ids["휴무"]), PatternPresets.toSteps(duty.presets[1], d.roles))
        // A 24-hour duty is 20 hours of work, a 2교대 shift 11.
        assertEquals(20 * 60, d.types.first { it.name == "당번" }.workMinutes)
        assertEquals(11 * 60, twoShift().types.first { it.name == "야간" }.workMinutes)
        // The old name hints read 비번 too.
        val byName = PatternPresets.toSteps(duty.presets[1], d.types)
        assertEquals(listOf(ids["당번"], ids["비번"], ids["휴무"]), byName)
    }

    @Test fun `the week average of an 8-day two-shift order is 38 and a half hours`() {
        val d = twoShift("DDNNOOOO")
        assertEquals(38 * 60 + 30, ShiftBundles.weeklyMinutes(d.rotation, d.types.associateBy { it.id }))
    }

    @Test fun `only the steps that apply are shown`() {
        val s = SetupStep.entries
        assertEquals(s, SetupDraft(style = WorkStyle.SHIFT).steps)
        assertEquals(s - SetupStep.TODAY, SetupDraft(style = WorkStyle.SHIFT, rosterMonthly = true).steps)
        val shared = SetupDraft(style = WorkStyle.SHIFT, rotation = listOf(-1L, null), source = SetupSource(SetupSource.Kind.TEMPLATE, "{}"))
        assertEquals(listOf(SetupStep.STYLE, SetupStep.COMPANY, SetupStep.TIMES, SetupStep.TODAY, SetupStep.ALARM, SetupStep.SUMMARY), shared.steps)
        assertEquals(6, SetupDraft(style = WorkStyle.DAY).steps.size)
        assertEquals(listOf(SetupStep.STYLE, SetupStep.TIMES, SetupStep.ORDER, SetupStep.ALARM, SetupStep.SUMMARY), SetupDraft(style = WorkStyle.HOURLY).steps)
        assertEquals(listOf(SetupStep.STYLE), SetupDraft(style = WorkStyle.FIELD).steps)
        assertEquals(3, SetupDraft(style = WorkStyle.SHIFT, step = SetupStep.BUNDLE).position)
    }

    @Test fun `times and today must be answered before going on`() {
        var d = twoShift().copy(step = SetupStep.TIMES)
        assertFalse(d.canLeave())
        d = d.copy(timesConfirmed = true)
        assertTrue(d.canLeave())
        val night = d.types.first { it.name == "야간" }
        val broken = d.copy(types = d.types.map { if (it.id == night.id) it.copy(breakMinutes = 12 * 60) else it })
        assertEquals(listOf("야간: 쉬는 시간이 근무 시간보다 길어요"), broken.timeProblems())
        assertFalse(broken.canLeave())
        assertFalse(d.canLeave(SetupStep.TODAY))
        assertFalse(d.copy(anchor = today).canLeave(SetupStep.TODAY))
        assertTrue(d.copy(anchor = today, todayConfirmed = true).canLeave(SetupStep.TODAY))
        // An order of days off only is no order.
        assertFalse(d.copy(rotation = listOf(d.types.first { it.isOff }.id)).canLeave(SetupStep.ORDER))
    }

    @Test fun `weekday work starts Monday to Friday and alarms follow the answer`() {
        val d = SetupDraft(style = WorkStyle.DAY).withShifts(null, ShiftBundles.weekday)
        val day = d.types.first { !it.isOff }
        assertEquals(List(5) { day.id } + listOf(null, null), d.weekly)
        val weekly = d.pattern(today) as WorkPattern.Weekly
        assertEquals(day.id, weekly.shiftTypeIdOn(LocalDate.of(2026, 10, 19)))
        assertNull(weekly.shiftTypeIdOn(LocalDate.of(2026, 10, 18)))

        val saved = d.typesToSave()
        assertTrue(saved.first { it.id == day.id }.alarmEnabled)
        assertEquals(60, saved.first { it.id == day.id }.alarms.single().offsetMinutes)
        assertFalse(saved.first { it.isOff }.alarmEnabled)
        assertFalse(d.copy(alarmMinutes = null).typesToSave().any { it.alarmEnabled })
        val perShift = d.copy(alarmByType = mapOf(day.id to 90)).typesToSave().first { it.id == day.id }
        assertEquals(90, perShift.alarms.single().offsetMinutes)
    }

    @Test fun `saving maps the temporary ids everywhere`() {
        val d = twoShift().copy(
            painted = mapOf(today to -2L, today.plusDays(1) to null),
            alarmByType = mapOf(-1L to 90),
            weekly = List(7) { -1L },
        )
        val ids = mapOf(-1L to 11L, -2L to 12L, -3L to 13L)
        val saved = d.remap(ids)
        assertEquals(setOf(11L, 12L, 13L), saved.types.map { it.id }.toSet())
        assertEquals(listOf(11L, 11L, 13L, 13L, 12L, 12L, 13L, 13L), saved.rotation)
        assertEquals(ShiftRole.NIGHT, saved.roles[12L])
        assertEquals(mapOf(11L to 90), saved.alarmByType)
        val painted = saved.paintedEntries()
        assertEquals(ShiftOverride.Shift(12L), painted[0].override)
        assertEquals(ShiftOverride.Off, painted[1].override)
    }
}
