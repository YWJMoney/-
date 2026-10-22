package com.bamnat.app.ui.calendar

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.UndoMessage
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * [CAL-05]~[CAL-13] The calendar for every kind of work: what it is about, leave and 알람 끄기 painted
 * over many days, ranges, holidays made days off, an added calendar kept from ringing, and undo
 * for every change on a day and every job or sales saved from it.
 */
@RunWith(AndroidJUnit4::class)
class CalendarKindsTest {
    private val c get() = ApplicationProvider.getApplicationContext<BamnatApp>().container
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    /** Lets the ViewModel's coroutines and the database's background writes finish. */
    private fun settle() = repeat(25) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stored(date: LocalDate): DayEntry? = runBlocking { c.scheduleRepository.entries.first() }.firstOrNull { it.date == date }

    /** Every message the screen would show, in order. */
    private fun messagesOf(vm: CalendarViewModel): MutableList<UndoMessage> {
        val got = mutableListOf<UndoMessage>()
        scope.launch { vm.messages.collect { got += it } }
        return got
    }

    /** A shift with times; the default shifts are made first when the app has not made them yet. */
    private fun dayShiftId(): Long = runBlocking {
        c.scheduleRepository.ensureDefaults()
        c.scheduleRepository.shiftTypes.first().first { !it.isOff && it.hasTimes }.id
    }

    @Test fun eachKindOfWorkHasItsFocus() {
        val vm = CalendarViewModel(c)
        assertEquals(CalendarFocus.SHIFTS, vm.focus.value)
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.FIELD) }
        settle()
        assertEquals(CalendarFocus.JOBS, vm.focus.value)
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.BUSINESS) }
        settle()
        assertEquals(CalendarFocus.SALES, vm.focus.value)
    }

    @Test fun leaveIsPaintedOnWorkingDaysOnlyAndUndoTakesBackOnlyTheLeave() {
        val vm = CalendarViewModel(c)
        val work = LocalDate.of(2026, 11, 2)
        val off = work.plusDays(1)
        runBlocking { c.scheduleRepository.setOverride(work, ShiftOverride.Shift(dayShiftId())) }
        settle()
        vm.setPaint(PaintTool.Pick)
        vm.setMark(DayMark.LEAVE)
        assertEquals(DayMark.LEAVE, vm.mark.value)
        vm.paintDays(listOf(work, off))
        settle()
        assertEquals("11월 2일에 연차를 넣었어요 · 근무 없는 1일은 빼고", vm.lastPaint.value?.text)
        assertEquals(Leave(LeaveKind.ANNUAL, 4), stored(work)?.leave)
        assertNull(stored(off)?.leave)

        // The alarm changed afterwards stays as it is when the leave is taken back.
        runBlocking { c.scheduleRepository.setAlarmSkipped(work, true) }
        vm.undoLastPaint()
        settle()
        assertNull(stored(work)?.leave)
        assertEquals(true, stored(work)?.alarmSkipped)

        // A tap on a day off says why nothing was painted, with nothing to undo.
        vm.onDayTapped(off)
        settle()
        assertEquals("11월 3일은 근무가 없어 연차를 넣지 않았어요", vm.lastPaint.value?.text)
        assertNull(vm.lastPaint.value?.undo)
        // Choosing a shift stamp again leaves the marks.
        vm.setPaint(PaintTool.Off)
        assertNull(vm.mark.value)
    }

    @Test fun alarmOffIsPaintedWhereAnAlarmWouldRing() {
        val vm = CalendarViewModel(c)
        val work = LocalDate.of(2026, 11, 9)
        runBlocking { c.scheduleRepository.setOverride(work, ShiftOverride.Shift(dayShiftId())) }
        settle()
        vm.setPaint(PaintTool.Pick)
        vm.setMark(DayMark.ALARM_OFF)
        vm.onDayTapped(work)
        settle()
        assertEquals(true, stored(work)?.alarmSkipped)
        assertEquals("11월 9일 알람을 껐어요", vm.lastPaint.value?.text)
        // The same tap again turns it back on.
        vm.onDayTapped(work)
        settle()
        assertEquals(false, stored(work)?.alarmSkipped)
    }

    @Test fun aLongPressStartsARangeAndTheNextTapPaintsEveryDayBetween() {
        val vm = CalendarViewModel(c)
        val from = LocalDate.of(2026, 11, 20)
        val to = from.plusDays(2)
        // Nothing to stamp yet: a long press starts nothing.
        vm.setPaint(PaintTool.Pick)
        vm.startRange(from)
        assertNull(vm.rangeFrom.value)

        vm.setPaint(PaintTool.Off)
        vm.startRange(to)
        assertEquals(to, vm.rangeFrom.value)
        assertEquals("11월 22일부터 · 끝 날짜를 누르세요", vm.lastPaint.value?.text)
        assertNull(vm.lastPaint.value?.undo)
        // Picked backwards, it still covers both ends.
        vm.onDayTapped(from)
        settle()
        assertNull(vm.rangeFrom.value)
        assertEquals("3일을 쉬는 날로 칠했어요", vm.lastPaint.value?.text)
        for (d in 0L..2L) assertEquals(ShiftOverride.Off, stored(from.plusDays(d))?.override)
    }

    @Test fun holidaysBecomeDaysOffWithUndoOrStayWorkingDays() {
        val vm = CalendarViewModel(c)
        val got = messagesOf(vm)
        val days = listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9))
        vm.restHolidays(days)
        settle()
        assertEquals("공휴일 2일을 쉬는 날로 바꿨어요", got.last().message)
        days.forEach { assertEquals(ShiftOverride.Off, stored(it)?.override) }
        vm.runUndo(got.last())
        settle()
        days.forEach { assertNull(stored(it)) }

        // Kept as working days, the month is not asked again until that is undone.
        val month = java.time.YearMonth.of(2026, 10)
        vm.keepHolidayWork(month)
        assertTrue(vm.holidaysKept.value.any { it.endsWith(":$month") })
        vm.runUndo(got.last())
        settle()
        assertFalse(vm.holidaysKept.value.any { it.endsWith(":$month") })
    }

    @Test fun anAddedCalendarCanBeKeptFromRinging() {
        val id = c.calendars.begin("라마바전자")
        c.calendars.finishSetup(id, WorkStyle.SHIFT)
        val vm = CalendarViewModel(c)
        val got = messagesOf(vm)
        settle()
        assertTrue(vm.calendarAlarmsOn.value)
        vm.setCalendarAlarms(false)
        settle()
        assertFalse(c.calendars.get(id)!!.alarms)
        assertFalse(vm.calendarAlarmsOn.value)
        assertEquals("‘라마바전자’ 알람을 껐어요", got.last().message)
        vm.runUndo(got.last())
        settle()
        assertTrue(c.calendars.get(id)!!.alarms)
        // The main calendar always rings.
        c.calendars.setActive(com.bamnat.app.data.PersonalCalendar.MAIN)
        settle()
        vm.setCalendarAlarms(false)
        settle()
        assertTrue(vm.calendarAlarmsOn.value)
    }

    @Test fun changesOnADaySayThemselvesWithUndo() {
        val vm = CalendarViewModel(c)
        val got = messagesOf(vm)
        val date = LocalDate.of(2026, 11, 12)
        vm.assign(date, ShiftOverride.Off)
        settle()
        assertEquals("11월 12일을 쉬는 날로 바꿨어요", got.last().message)
        vm.runUndo(got.last())
        settle()
        assertNull(stored(date))

        vm.setDayAlarms(date, listOf(com.bamnat.core.model.DayAlarm(java.time.LocalTime.of(6, 0))))
        settle()
        assertEquals("11월 12일 알람 1개를 정했어요", got.last().message)
        vm.runUndo(got.last())
        settle()
        assertNull(stored(date)?.customAlarms)
    }

    @Test fun aJobOrSalesSavedFromTheCalendarCanBeTakenBack() {
        val vm = CalendarViewModel(c)
        val got = messagesOf(vm)
        val money = c.calendars.money(com.bamnat.app.data.PersonalCalendar.MAIN)
        val date = LocalDate.of(2026, 11, 16)
        vm.saveJob(Job(date = date, title = "욕실 타일 시공", amount = 450_000))
        settle()
        assertEquals("11월 16일에 ‘욕실 타일 시공’ 일감을 적었어요", got.last().message)
        val saved = runBlocking { money.book.first() }.jobs.single { it.title == "욕실 타일 시공" }
        // Marked paid by mistake, it goes back to unpaid.
        vm.saveJob(saved.copy(balancePaid = true))
        settle()
        assertEquals("‘욕실 타일 시공’ 일감을 고쳤어요", got.last().message)
        vm.runUndo(got.last())
        settle()
        assertFalse(runBlocking { money.job(saved.id) }!!.balancePaid)
        vm.runUndo(got[got.size - 2])
        settle()
        assertNull(runBlocking { money.job(saved.id) })

        vm.saveSales(DailySales(date, 820_000, 120_000))
        settle()
        assertEquals("11월 16일 매출 820,000원을 적었어요", got.last().message)
        vm.runUndo(got.last())
        settle()
        assertTrue(runBlocking { money.book.first() }.sales.none { it.date == date })
    }
}
