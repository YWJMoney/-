package com.bamnat.core

import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepSchedule
import com.bamnat.core.work.CombinedView
import com.bamnat.core.work.WatchDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [LIFE-02] 연차 taken on the calendar reaches the parts that follow the day without anyone setting them again:
 * the shift's alarm, the sleep plan, the watch and the combined view of several calendars. Pay has its own
 * tests (LeaveTest: paid leave pays, unpaid deducts). One day each: 주간 09~18 with its alarm an hour before.
 */
class LifeLinksTest {
    private val monday = LocalDate.of(2026, 9, 28)
    private val full = LocalDate.of(2026, 10, 7)
    private val half = LocalDate.of(2026, 10, 8)
    private val work = LocalDate.of(2026, 10, 9)
    private val resolver = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, 1L, 1L, null, null)),
        listOf(DayEntry(full, leave = Leave(LeaveKind.ANNUAL, 4)), DayEntry(half, leave = Leave(LeaveKind.ANNUAL, 2))))

    @Test fun aDayOfLeaveRingsNoShiftAlarmAndHalfADayStillWakesForTheMorning() {
        assertEquals(emptyList<Any>(), AlarmPlanner.alarmsOn(resolver, full))
        // 반차 comes off the end of the shift, so the morning is still worked and still rung for.
        assertEquals(listOf(LocalDateTime.of(half, LocalTime.of(8, 0))), AlarmPlanner.alarmsOn(resolver, half).map { it.triggerAt })
        assertEquals(listOf(LocalDateTime.of(work, LocalTime.of(8, 0))), AlarmPlanner.alarmsOn(resolver, work).map { it.triggerAt })
        // The next alarm after the evening before the day off is the morning after it.
        assertEquals(LocalDateTime.of(half, LocalTime.of(8, 0)), AlarmPlanner.nextAlarm(resolver, full.minusDays(1).atTime(20, 0))?.triggerAt)
    }

    @Test fun theSleepPlanSeesTheDayOfLeaveAsADayOffAndHalfADayAsAShortShift() {
        assertEquals(emptyList<Any>(), SleepSchedule.work(resolver.resolve(full)))
        val morning = SleepSchedule.work(resolver.resolve(half)).single()
        assertEquals(LocalDateTime.of(half, LocalTime.of(9, 0)), morning.from)
        assertEquals(LocalDateTime.of(half, LocalTime.of(13, 30)), morning.to)
    }

    @Test fun theWatchAndTheCombinedViewShowTheLeaveAsTimeOff() {
        val watch = WatchDays.day(resolver.resolve(full))
        assertTrue("the watch shows the day as off", watch.off)
        assertEquals("연차", watch.name)
        assertEquals(emptyList<Any>(), CombinedView.spansOf("main", resolver.resolve(full), emptyList()))
        assertEquals(1, CombinedView.spansOf("main", resolver.resolve(half), emptyList()).size)
    }
}
