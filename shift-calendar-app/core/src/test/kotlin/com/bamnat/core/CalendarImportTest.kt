package com.bamnat.core

import com.bamnat.core.imports.CalendarImport
import com.bamnat.core.imports.CalendarImport.As
import com.bamnat.core.imports.CalendarImport.Skip
import com.bamnat.core.imports.DeviceCalendar
import com.bamnat.core.imports.DeviceEvent
import com.bamnat.core.model.ShiftType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [IMPORT-01] Samsung Calendar events read as shifts, days off or memos. */
class CalendarImportTest {
    private val day = ShiftType(11, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(12, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(13, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val types = listOf(day, night, off)

    private var id = 1L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)
    private fun ev(title: String, from: LocalDateTime, to: LocalDateTime, allDay: Boolean = false, location: String = "", cal: Long = 1) =
        DeviceEvent(id++, cal, title, from, to, allDay, location)
    private fun allDay(title: String, d: Int, days: Int = 1) = ev(title, at(d, 0), at(d + days, 0), allDay = true)

    @Test
    fun titlesThatNameAShiftBecomeThatShift() {
        val p = CalendarImport.propose(listOf(allDay("야간", 3), allDay("[주간 근무]", 4), allDay("N", 5), allDay("나이트", 6),
            allDay("비번", 7), allDay("데이", 8), allDay("휴", 9)), types)
        assertEquals(listOf(As.SHIFT, As.SHIFT, As.SHIFT, As.SHIFT, As.OFF, As.SHIFT, As.OFF), p.map { it.kind })
        assertEquals(listOf(12L, 11L, 12L, 12L, 13L, 11L, 13L), p.map { it.shiftTypeId })
        assertEquals("이름이 같아요", p[0].reason)
        assertEquals("‘나이트’를 야간으로 읽었어요", p[3].reason)
    }

    @Test
    fun hoursThatMatchAShiftBecomeThatShiftWhenTheTitleIsShort() {
        val p = CalendarImport.propose(listOf(
            ev("A조", at(3, 18, 50), at(4, 7, 10)),
            ev("출근", at(4, 7), at(4, 19)),
            ev("병원 정기 검진 예약", at(5, 7), at(5, 19)),
            ev("치과", at(6, 15), at(6, 16), location = "가나다치과"),
        ), types)
        assertEquals(listOf(As.SHIFT, As.SHIFT, As.MEMO, As.MEMO), p.map { it.kind })
        assertEquals("시간이 야간과 같아요", p[0].reason)
        assertEquals("치과\n15:00–16:00 · 가나다치과", CalendarImport.memoText(p[3].event))
    }

    @Test
    fun oneShiftADayAndTripsStayMemos() {
        val p = CalendarImport.propose(listOf(allDay("주간", 3), ev("야간", at(3, 19), at(4, 7)), allDay("휴무", 10, days = 3)), types)
        assertEquals(listOf(As.SHIFT, As.MEMO, As.MEMO), p.map { it.kind })
        assertEquals("같은 날 근무가 이미 있어요", p[1].reason)
        assertEquals(LocalDate.of(2026, 11, 12), p[2].event.lastDate)
        assertEquals("휴무\n~11/12", CalendarImport.memoText(p[2].event))
    }

    @Test
    fun whatWasBroughtInOrIsAlreadyThereIsLeftOut() {
        val first = allDay("야간", 3)
        val same = allDay("주간", 4)
        val holiday = DeviceEvent(99, 7, "개천절", at(5, 0), at(6, 0), allDay = true)
        val p = CalendarImport.propose(listOf(first, same, holiday, allDay("휴무", 6)), types, imported = setOf(first.key),
            current = { if (it.dayOfMonth == 4) 11L else if (it.dayOfMonth == 6) 13L else null }, generatedCalendars = setOf(7L))
        assertEquals(listOf(Skip.IMPORTED, Skip.SAME, Skip.GENERATED, Skip.SAME), p.map { it.skip })
        assertEquals(CalendarImport.Count(0, 0, 0), CalendarImport.count(p))
        val fresh = CalendarImport.count(CalendarImport.propose(listOf(allDay("야간", 3), allDay("휴무", 4), allDay("회식", 5)), types))
        assertEquals(CalendarImport.Count(1, 1, 1), fresh)
        assertEquals(3, fresh.total)
    }

    @Test
    fun calendarsKnowWhereTheyComeFrom() {
        assertTrue(DeviceCalendar(1, "내 캘린더", "My calendar", "LOCAL").samsung)
        assertTrue(DeviceCalendar(2, "Samsung account", "a@b.c", "com.osp.app.signin").samsung)
        assertEquals("삼성 캘린더", DeviceCalendar(2, "x", "a@b.c", "com.osp.app.signin").source)
        assertEquals("Google 캘린더", DeviceCalendar(3, "a@gmail.com", "a@gmail.com", "com.google").source)
        assertTrue(DeviceCalendar(4, "대한민국의 공휴일", "a@gmail.com", "com.google").generated)
        assertFalse(DeviceCalendar(5, "근무", "a@gmail.com", "com.google").generated)
        assertNull(CalendarImport.propose(listOf(allDay("회식", 3)), types).single().shiftTypeId)
        assertEquals("야간", CalendarImport.normalize(" 야간 근무 "))
    }
}
