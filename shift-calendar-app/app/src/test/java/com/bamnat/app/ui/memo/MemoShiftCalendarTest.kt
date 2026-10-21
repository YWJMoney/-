package com.bamnat.app.ui.memo

import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoCalendars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** [MEMO-01] A dated memo in 모든 메모 or 공용 메모장 shows the shift of the calendar it belongs to. */
class MemoShiftCalendarTest {
    private val day = LocalDate.of(2026, 10, 21)
    private fun memo(vararg calendars: String) = Memo(date = day, body = "x", calendars = calendars.toSet())

    @Test fun theShiftIsTheOwnersOrNone() {
        assertEquals("a", shiftCalendar(listOf(memo("a")), "a"))
        assertEquals("a", shiftCalendar(listOf(memo(MemoCalendars.EVERY)), "a"))
        assertEquals("a", shiftCalendar(listOf(memo("b"), memo("a", "c")), "a"))
        // Calendar B's memo shows B's shift, not the one on screen.
        assertEquals("b", shiftCalendar(listOf(memo("b")), "a"))
        // An old memo with none is 내 달력's.
        assertEquals(MemoCalendars.MAIN, shiftCalendar(listOf(memo()), "a"))
        // Of several other calendars: no shift at all.
        assertNull(shiftCalendar(listOf(memo("b", "c")), "a"))
        assertNull(shiftCalendar(listOf(memo("b"), memo("c")), "a"))
    }
}
