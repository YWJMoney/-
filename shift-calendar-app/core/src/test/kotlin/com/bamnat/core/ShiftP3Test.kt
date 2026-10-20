package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyRoster
import com.bamnat.core.imports.DutyType
import com.bamnat.core.imports.RosterWord
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.model.isNight
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.ShiftHealth
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [SHFT-06] 듀티표 글자 인식, [SHFT-07] 연간 건강 지표, [SHFT-08] 워치에 보내는 근무. */
class ShiftP3Test {
    private val types = Fixtures.all.map { DutyType(it.id, it.name, it.label, it.isOff, it.start, it.isNight) }
    private val codes = DutyCodes(types)
    private val day = DutyCell.Shift(Fixtures.day.id)
    private val evening = DutyCell.Shift(Fixtures.evening.id)
    private val night = DutyCell.Shift(Fixtures.night.id)
    private val off = DutyCell.Shift(Fixtures.off.id)

    @Test
    fun `hospital codes match the calendar's kinds`() {
        assertEquals(day, codes.match("D"))
        assertEquals(evening, codes.match("e"))
        assertEquals(night, codes.match("N"))
        assertEquals(off, codes.match("OFF"))
        assertEquals(off, codes.match("O"))
        assertEquals(off, codes.match("휴"))
        assertEquals(night, codes.match("야"))
        assertEquals(DutyCell.Leave, codes.match("연차"))
        assertEquals(DutyCell.Shift(Fixtures.long.id), codes.match("장주간"))
        assertNull(codes.match("김민지"))
        assertEquals(listOf("D", "D", "E", "N"), codes.split("DDEN"))
        assertNull(codes.split("OFF"))
    }

    @Test
    fun `a pasted table with a name row pairs each code with its day`() {
        val text = """
            10월 근무표
            이름 1 2 3 4 5 6 7 8 9 10
            월 화 수 목 금 토 일 월 화 수
            김민지 D D E E N N O O 연 D
            박준호 N N O O D D E E N N
        """.trimIndent()
        val rows = DutyRoster.readText(text, codes)
        val minji = rows.first { it.label == "김민지" }
        assertEquals(10, minji.cells.size)
        assertEquals(day, minji.cells[1])
        assertEquals(evening, minji.cells[3])
        assertEquals(night, minji.cells[6])
        assertEquals(off, minji.cells[7])
        assertEquals(DutyCell.Leave, minji.cells[9])
        assertEquals(night, rows.first { it.label == "박준호" }.cells[1])
    }

    @Test
    fun `merged codes and unknown ones are read, two halves of the month join`() {
        val text = """
            1 2 3 4 5 6 7 8
            DDENNOX Z
            9 10 11 12 13 14 15 16
            E E N N O O D D
        """.trimIndent()
        val rows = DutyRoster.readText(text, codes)
        val row = rows.first()
        assertEquals(16, row.cells.size)
        assertEquals(night, row.cells[4])
        assertEquals(off, row.cells[7]) // X
        assertTrue(row.cells[8] is DutyCell.Unknown)
        assertEquals(evening, row.cells[9])
        assertEquals(1, row.unknown)
    }

    @Test
    fun `a two-letter name made of code letters stays a name`() {
        val text = """
            이름 1 2 3 4 5
            주연 D E N O D
            이나 N N O D D
            연주 O D D E E
        """.trimIndent()
        val rows = DutyRoster.readText(text, codes)
        val juyeon = rows.first { it.label == "주연" }
        assertEquals(mapOf(1 to day, 2 to evening, 3 to night, 4 to off, 5 to day), juyeon.cells)
        assertEquals(night, rows.first { it.label == "이나" }.cells[1])
        assertEquals(off, rows.first { it.label == "연주" }.cells[1])
    }

    @Test
    fun `tab-separated cells keep empty days in their place`() {
        val text = "이름\t1\t2\t3\t4\t5\n민지\tD\t\tN\tO\tD"
        val minji = DutyRoster.readText(text, codes).first { it.label == "민지" }
        assertEquals(mapOf(1 to day, 3 to night, 4 to off, 5 to day), minji.cells)
    }

    @Test
    fun `a photo's words go under the day above them, blanks stay blank`() {
        fun w(t: String, x: Int, y: Int) = RosterWord(t, x - 10, y - 10, x + 10, y + 10)
        val header = (1..7).map { w("$it", it * 50, 100) }
        // Day 3 is left blank in the photo; the codes still sit under their own days.
        val codesRow = listOf(w("민지", 0, 140), w("D", 50, 140), w("E", 100, 140), w("N", 200, 141), w("N", 250, 139), w("O", 300, 140), w("D", 352, 140))
        val rows = DutyRoster.read(header + codesRow, codes)
        val row = rows.first()
        assertEquals("민지", row.label)
        assertEquals(setOf(1, 2, 4, 5, 6, 7), row.cells.keys)
        assertEquals(night, row.cells[4])
    }

    @Test
    fun `a roster written one day per line reads too`() {
        val rows = DutyRoster.readText("10/1 D\n10/2 (금) E\n3일 야간\n4 휴", codes)
        assertEquals(mapOf(1 to day, 2 to evening, 3 to night, 4 to off), rows.first().cells)
    }

    @Test
    fun `cells write the day as an override, leave as leave`() {
        val d = LocalDate.of(2026, 11, 3)
        assertEquals(ShiftOverride.Shift(Fixtures.night.id), DutyRoster.apply(DayEntry(d), night).override)
        assertEquals(ShiftOverride.Off, DutyRoster.apply(DayEntry(d), DutyCell.Off).override)
        assertNotNull(DutyRoster.apply(DayEntry(d), DutyCell.Leave).leave)
        assertEquals(DayEntry(d), DutyRoster.apply(DayEntry(d), DutyCell.Unknown("?")))
    }

    @Test
    fun `the year's nights, runs, quick returns and weekend work`() {
        // 주 주 야 야 휴 휴 from 2026-01-01 (Thu), every 6 days.
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 1, 1), listOf(Fixtures.day.id, Fixtures.day.id, Fixtures.night.id, Fixtures.night.id, null, null))
        val r = ScheduleResolver(Fixtures.all, pattern, emptyList())
        val y = ShiftHealth.year(2026, r::resolve, KoreanHolidays::nameOf, until = LocalDate.of(2026, 3, 31))
        assertEquals(3, y.months.size)
        val jan = y.months[0]
        // Jan 1..31: cycles start 1,7,13,19,25,31 → work days 1,2,3,4,7,8,9,10,…,31.
        assertEquals(21, jan.workDays)
        assertEquals(10, jan.nights)
        assertEquals(0.5, y.nightShare, 0.05)
        assertEquals(2, y.longestNightRun)
        // 주간 ends 18:00, 야간 starts 22:00 the next day: 28 hours, fine. 주간 → 주간 15 hours, fine. No quick returns.
        assertEquals(0, y.quickReturns)
        assertTrue(jan.holidayDays >= 1) // 신정 1/1 is a 주간
        assertTrue(jan.weekendDays > 0)
        assertEquals(y.months.maxBy { it.nights }.nights, y.busiestNightMonth!!.nights)
    }

    @Test
    fun `a day shift soon after a night is a quick return`() {
        // 야간 ends 07:00, 오후 starts 14:00 the same day: 7 hours.
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 1, 1), listOf(Fixtures.night.id, Fixtures.evening.id, null))
        val r = ScheduleResolver(Fixtures.all, pattern, emptyList())
        val y = ShiftHealth.year(2026, r::resolve, { null }, until = LocalDate.of(2026, 1, 9))
        assertEquals(3, y.quickReturns)
    }

    @Test
    fun `the watch payload goes there and back`() {
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 10, 1), listOf(Fixtures.day.id, Fixtures.night.id, Fixtures.off.id))
        val r = ScheduleResolver(Fixtures.all, pattern, emptyList())
        val today = LocalDate.of(2026, 10, 20)
        val (from, until) = WatchDays.window(today)
        assertEquals(LocalDate.of(2026, 9, 19), from)
        val days = WatchDays.of(from, until, r::resolve)
        val p = WatchPayload(days, LocalDateTime.of(2026, 10, 20, 8, 0), LocalDateTime.of(2026, 10, 20, 21, 0), "야간 근무", "내 달력")
        val back = WatchDays.decode(WatchDays.encode(p))!!
        assertEquals(p, back)
        val oct20 = WatchDays.on(back.days, today)!!
        assertEquals("야간 22:00–07:00", oct20.line)
        assertEquals("휴무", WatchDays.on(back.days, today.plusDays(1))!!.line)
        assertNull(WatchDays.alarmAhead(back, LocalDateTime.of(2026, 10, 20, 22, 0)))
        assertEquals("3시간 전", WatchDays.age(p.syncedAt, LocalDateTime.of(2026, 10, 20, 11, 5)))
        assertNull(WatchDays.decode("garbage"))
        // A newer copy whose phone clock went back an hour still replaces the old one: compared by epoch millis.
        val sent = p.copy(sentAtMillis = 1_000_000L)
        assertEquals(sent, WatchDays.decode(WatchDays.encode(sent)))
        val later = sent.copy(syncedAt = p.syncedAt.minusHours(1), sentAtMillis = 2_000_000L)
        assertTrue(WatchDays.replaces(later, sent))
        assertTrue(!WatchDays.replaces(sent, later))
        assertTrue(WatchDays.replaces(sent, p))
        assertTrue(!WatchDays.replaces(p.copy(syncedAt = p.syncedAt.minusHours(1)), p))
        val grid = WatchDays.monthGrid(2026, 10)
        assertEquals(LocalDate.of(2026, 10, 1), grid[0][3]) // Thursday
        assertTrue(grid.all { it.size == 7 })
        assertEquals(LocalTime.of(22, 0), oct20.start)
    }
}
