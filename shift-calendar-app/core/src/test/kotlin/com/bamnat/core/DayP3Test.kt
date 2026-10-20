package com.bamnat.core

import com.bamnat.core.imports.AttendanceField
import com.bamnat.core.imports.AttendanceImport
import com.bamnat.core.imports.AttendanceRow
import com.bamnat.core.imports.Sheets
import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.Commute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [DAYW-06] 근태 엑셀·CSV 가져오기, [DAYW-07] 출퇴근 자동 기록. */
class DayP3Test {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    // 월~금 주간 09:00–18:00.
    private val weekly = WorkPattern.Weekly(LocalDate.of(2026, 1, 1), DayOfWeek.entries.associateWith { if (it.value <= 5) Fixtures.day.id else null })
    private val resolver = ScheduleResolver(Fixtures.all, weekly, emptyList())
    private val thu = LocalDate.of(2026, 10, 22)
    private val sat = LocalDate.of(2026, 10, 24)

    @Test
    fun `a CSV export in CP949 with a header row is read and its columns guessed`() {
        val text = "사원명,근무일자,출근시간,퇴근시간,총근무시간,연장근무,근태구분\r\n" +
            "민지,2026-10-21(수),08:55,18:00,8:00,,정상\r\n" +
            "민지,2026-10-22(목),09:32,20:40,10:08,2:40,지각\r\n" +
            "민지,2026-10-23(금),,,,,연차\r\n" +
            "민지,2026.10.26,\"09:00\",13:00,4:00,,오후반차\r\n"
        val rows = Sheets.read(text.toByteArray(charset("MS949")))
        assertEquals(5, rows.size)
        val map = AttendanceImport.guess(rows)!!
        assertEquals(0, map.headerRow)
        assertEquals(1, map.columns[AttendanceField.DATE])
        assertEquals(2, map.columns[AttendanceField.START])
        assertEquals(3, map.columns[AttendanceField.END])
        assertEquals(4, map.columns[AttendanceField.HOURS])
        assertEquals(5, map.columns[AttendanceField.OVERTIME])
        assertEquals(6, map.columns[AttendanceField.KIND])
        val days = AttendanceImport.read(rows, map, 2026)
        assertEquals(4, days.size)
        assertEquals(t(9, 32), days[1].start)
        assertEquals(160, days[1].overtimeMinutes)
        assertEquals(LeaveKind.ANNUAL, days[2].leave!!.kind)
        assertEquals(4, days[2].leave!!.quarters)
        assertEquals(2, days[3].leave!!.quarters)
    }

    @Test
    fun `an xlsx workbook is read from its XML, shared strings, dates and times as numbers`() {
        val shared = """<?xml version="1.0"?><sst><si><t>날짜</t></si><si><t>출근</t></si><si><t>퇴근</t></si><si><r><t>구</t></r><r><t>분</t></r></si><si><t>연차 &amp; 휴가</t></si></sst>"""
        val sheet = """<?xml version="1.0"?><worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
            <row r="2"><c r="A2"><v>46317</v></c><c r="B2"><v>0.375</v></c><c r="C2"><v>0.8125</v></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>2026-10-23</t></is></c><c r="D3" t="s"><v>4</v></c></row>
            </sheetData></worksheet>"""
        val wb = """<workbook xmlns:r="x"><sheets><sheet name="근태" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId1" Type="ws" Target="worksheets/sheet1.xml"/></Relationships>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet, "xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels)
                    .forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            }
        }.toByteArray()
        val rows = Sheets.read(bytes)
        assertEquals(listOf("날짜", "출근", "퇴근", "구분"), rows[0])
        assertEquals("", rows[2][1])
        val map = AttendanceImport.guess(rows)!!
        val days = AttendanceImport.read(rows, map, 2026)
        assertEquals(LocalDate.of(2026, 10, 22), days[0].date)
        assertEquals(t(9), days[0].start)
        assertEquals(t(19, 30), days[0].end)
        assertEquals(LeaveKind.ANNUAL, days[1].leave!!.kind)
        assertEquals(2, Sheets.columnOf("C7"))
        assertEquals(27, Sheets.columnOf("AB1"))
    }

    @Test
    fun `dates, times and lengths in the ways exports write them`() {
        assertEquals(LocalDate.of(2026, 3, 5), AttendanceImport.parseDate("2026/3/5", 2000))
        assertEquals(LocalDate.of(2026, 3, 5), AttendanceImport.parseDate("3월 5일", 2026))
        assertEquals(LocalDate.of(2026, 3, 5), AttendanceImport.parseDate("20260305", 2000))
        assertEquals(LocalDate.of(2026, 3, 5), AttendanceImport.parseDate("26.03.05", 2000))
        assertNull(AttendanceImport.parseDate("합계", 2026))
        assertEquals(t(18, 30), AttendanceImport.parseTime("오후 6:30"))
        assertEquals(t(9, 2), AttendanceImport.parseTime("2026-10-01 09:02:31"))
        assertEquals(t(7, 5), AttendanceImport.parseTime("0705"))
        assertEquals(t(18), AttendanceImport.parseTime("18시"))
        assertNull(AttendanceImport.parseTime("-"))
        assertEquals(510, AttendanceImport.parseMinutes("8:30"))
        assertEquals(510, AttendanceImport.parseMinutes("8.5"))
        assertEquals(510, AttendanceImport.parseMinutes("8시간 30분"))
        assertEquals(90, AttendanceImport.parseMinutes("90분"))
        assertTrue(AttendanceImport.kindOf("결근").absent)
        assertEquals(1, AttendanceImport.kindOf("오전 반반차").leave!!.quarters)
        assertEquals(LeaveKind.PAID, AttendanceImport.kindOf("병가").leave!!.kind)
        assertNull(AttendanceImport.kindOf("정상").leave)
    }

    @Test
    fun `an imported day writes a late start, overtime, leave and work on a day off`() {
        val late = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, t(9, 32), t(20, 40)))
        assertEquals(t(9, 32), late.customStart)
        assertEquals(t(18), late.customEnd)
        assertEquals(1, late.extras.size)
        assertEquals(ExtraKind.OVERTIME_ID, late.extras[0].kindId)
        assertEquals(t(20, 40), late.extras[0].end)

        val early = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, t(8, 55), t(16, 0)))
        assertEquals(t(9), early.customStart)
        assertEquals(t(16), early.customEnd)

        val onTime = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, t(8, 52), t(18, 4)))
        assertEquals(DayEntry(thu), onTime)

        val weekend = AttendanceImport.apply(resolver.resolve(sat), DayEntry(sat), AttendanceRow(sat, t(10), t(15)))
        assertEquals(ExtraKind.SPECIAL_ID, weekend.extras.single().kindId)
        assertEquals(30, weekend.extras.single().breakMinutes)

        val leave = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, leave = AttendanceImport.kindOf("연차").leave))
        assertEquals(4, leave.leave!!.quarters)
        assertTrue(leave.extras.isEmpty())

        val absent = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, absent = true))
        assertEquals(Attendance.ABSENT, absent.attendance)

        val lengthOnly = AttendanceImport.apply(resolver.resolve(thu), DayEntry(thu), AttendanceRow(thu, overtimeMinutes = 90))
        assertEquals(90, lengthOnly.extras.single().minutes)
    }

    @Test
    fun `arriving and leaving the workplace write what 지금 퇴근 would`() {
        val day = resolver.resolve(thu)
        assertEquals(Commute.Write.None, Commute.arrive(day, thu.atTime(8, 47)))
        assertEquals(Commute.Write.None, Commute.arrive(day, thu.atTime(9, 6)))
        assertEquals(Commute.Write.Late(t(9, 30), t(18)), Commute.arrive(day, thu.atTime(9, 27)))
        val ot = Commute.leave(day, thu.atTime(8, 50), thu.atTime(19, 42)) as Commute.Write.Overtime
        assertEquals(100, ot.minutes)
        assertEquals(Commute.Write.EarlyEnd(t(9), t(15)), Commute.leave(day, null, thu.atTime(15, 1)))
        assertEquals(Commute.Write.None, Commute.leave(day, null, thu.atTime(18, 3)))
        val rest = Commute.leave(resolver.resolve(sat), sat.atTime(9, 58), sat.atTime(14, 2)) as Commute.Write.RestDayWork
        assertEquals(t(10), rest.entry.start)
        assertEquals(240 - 30, rest.minutes)
        assertEquals(Commute.Write.None, Commute.leave(resolver.resolve(sat), sat.atTime(10, 0), sat.atTime(10, 10)))
        assertEquals(Commute.Write.None, Commute.leave(resolver.resolve(sat), null, sat.atTime(14, 0)))
        // A second leave moves the 잔업 instead of adding another.
        val once = Commute.apply(DayEntry(thu), ot)
        val twice = Commute.apply(once, Commute.leave(day, null, LocalDateTime.of(thu, t(20, 0))))
        assertEquals(1, twice.extras.size)
        assertEquals(t(20), twice.extras[0].end)
    }

    @Test
    fun `a long lunch out of the fence is put back and the evening leave counts from the planned end`() {
        val day = resolver.resolve(thu)
        // 12:00 out, not back within the wait: an earlier end is written.
        val lunch = Commute.leave(day, thu.atTime(8, 50), thu.atTime(12, 0))
        assertEquals(Commute.Write.EarlyEnd(t(9), t(12)), lunch)
        val before = DayEntry(thu)
        val undo = Commute.undoOf(before, lunch)!!
        val written = Commute.apply(before, lunch)
        assertEquals(t(12), written.customEnd)
        // Back at 13:05: the day is as it was before the leave.
        val back = Commute.undo(written, undo)
        assertEquals(before, back)
        // Even without the undo, a leave at 18:30 counts from 18:00, not from the 12:00 written.
        val stale = ScheduleResolver(Fixtures.all, weekly, listOf(written)).resolve(thu)
        val evening = Commute.leave(stale, thu.atTime(8, 50), thu.atTime(18, 30)) as Commute.Write.Overtime
        assertEquals(30, evening.minutes)
        assertEquals(t(18), evening.entry.start)
        // And 잔업 takes away the earlier end written that day.
        assertEquals(t(18), Commute.apply(written, evening).customEnd)
        assertNull(Commute.apply(before, evening).customEnd)
        // A 잔업 already there comes back when its replacement is undone.
        val ot = Commute.leave(day, null, thu.atTime(19, 0)) as Commute.Write.Overtime
        val withOt = Commute.apply(before, ot)
        val later = Commute.leave(day, null, thu.atTime(20, 0))
        assertEquals(withOt, Commute.undo(Commute.apply(withOt, later), Commute.undoOf(withOt, later)!!))
    }

    @Test
    fun `a leave after midnight belongs to yesterday only while that arrival is open and recent`() {
        val fri = thu.plusDays(1)
        // Night: in at 21:00, out at 01:00.
        assertEquals(thu, Commute.leaveDay(fri.atTime(1, 0), thu.atTime(21, 0), null))
        assertEquals(thu, Commute.leaveDay(thu.atTime(18, 0), thu.atTime(9, 0), null))
        // Day 2 with a missed 출근: day 1 was already left, or its arrival is too old.
        assertNull(Commute.leaveDay(fri.atTime(18, 0), thu.atTime(9, 0), thu.atTime(18, 0)))
        assertNull(Commute.leaveDay(fri.atTime(18, 0), thu.atTime(9, 0), null))
        assertNull(Commute.leaveDay(fri.atTime(18, 0), null, null))
        // The leave being finished right now is not one recorded after the arrival.
        assertEquals(thu, Commute.leaveDay(fri.atTime(1, 0), thu.atTime(21, 0), fri.atTime(1, 0)))
        // Back just after midnight, minutes after leaving: a walk.
        assertTrue(Commute.cameBack(thu.atTime(23, 50), fri.atTime(0, 20), 90))
        assertTrue(!Commute.cameBack(thu.atTime(12, 0), thu.atTime(14, 0), 90))
    }
}
