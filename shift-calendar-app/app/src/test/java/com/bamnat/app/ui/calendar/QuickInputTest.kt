package com.bamnat.app.ui.calendar

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/** [CAL-01] Quick input: a drag paints every day it crosses in one write, and undo takes back only the shift. */
@RunWith(AndroidJUnit4::class)
class QuickInputTest {
    private val c get() = ApplicationProvider.getApplicationContext<BamnatApp>().container

    /** Lets the ViewModel's coroutines and the database's background writes finish. */
    private fun settle() = repeat(20) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stored(date: LocalDate): DayEntry? = runBlocking { c.scheduleRepository.entries.first() }.firstOrNull { it.date == date }

    @Test fun aDragPaintsEveryDayAndUndoTakesBackOnlyTheShift() {
        val vm = CalendarViewModel(c)
        val first = LocalDate.of(2026, 11, 2)
        val second = first.plusDays(1)
        // Quick input open with nothing chosen yet: a stray drag paints nothing.
        vm.setPaint(PaintTool.Pick)
        vm.paintDays(listOf(first, second))
        settle()
        assertNull(vm.lastPaint.value)
        assertNull(stored(first))

        // The finger passes a day twice; it is painted once.
        vm.setPaint(PaintTool.Off)
        vm.paintDays(listOf(first, second, second))
        settle()
        assertEquals("2일을 쉬는 날로 칠했어요", vm.lastPaint.value?.text)
        assertEquals(ShiftOverride.Off, stored(first)?.override)
        assertEquals(ShiftOverride.Off, stored(second)?.override)

        // The alarm turned off on one of them afterwards stays off when the paint is taken back.
        runBlocking { c.scheduleRepository.setAlarmSkipped(first, true) }
        vm.undoLastPaint()
        assertNull(vm.lastPaint.value)
        settle()
        assertEquals(ShiftOverride.None, stored(first)?.override)
        assertEquals(true, stored(first)?.alarmSkipped)
        assertNull(stored(second))

        // One day is said by its date, and a tap on the calendar ends what the bar can undo.
        vm.paintDays(listOf(second))
        settle()
        assertEquals("11월 3일을 쉬는 날로 칠했어요", vm.lastPaint.value?.text)
        vm.onDayTapped(first)
        assertNull(vm.lastPaint.value)
    }
}
