package com.bamnat.core

import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyRoster
import com.bamnat.core.imports.DutyType
import com.bamnat.core.imports.RosterRow
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [ROST-03][ROST-04][ROST-05] 듀티표 글 읽기 (1.24 순번 342·343): the 22 shapes of the 1.23 직접 점검 that worked and the 7
 * that went wrong (보고서 상-1·2·3, 중-6·7, 하). Rosters are made up for the test; no real person. Nothing may be saved
 * quietly in a wrong day: every row is checked day by day.
 */
class DutyRosterTextTest {
    private val nurse = listOf(
        DutyType(1, "데이", "D", false, LocalTime.of(7, 0)),
        DutyType(2, "이브닝", "E", false, LocalTime.of(15, 0)),
        DutyType(3, "나이트", "N", false, LocalTime.of(23, 0), night = true),
        DutyType(4, "오프", "O", true),
    )
    private val codes = DutyCodes(nurse)
    private val D = DutyCell.Shift(1)
    private val E = DutyCell.Shift(2)
    private val N = DutyCell.Shift(3)
    private val O = DutyCell.Shift(4)

    private val days31 = (1..31).joinToString(" ")
    private val month31 = "D D E E N N O O D D E E N N O O D D E E N N O O D D E E N N O"
    private val expected31 = month31.split(" ").map { mapOf("D" to D, "E" to E, "N" to N, "O" to O).getValue(it) }

    private fun one(text: String, c: DutyCodes = codes): RosterRow = DutyRoster.readText(text, c).first()
    private fun assertMonth(row: RosterRow, want: List<DutyCell> = expected31) =
        want.forEachIndexed { i, cell -> assertEquals("${i + 1}일", cell, row.cells[i + 1]) }

    // ---- 22 shapes that worked in 1.23 and must keep working ----

    @Test fun s01_standardWard() = assertMonth(one("$days31\n김민지 $month31"))

    @Test fun s02_lowerCase() = assertMonth(one("$days31\n김민지 ${month31.lowercase()}"))

    @Test fun s03_weekdayRow() {
        val week = (0 until 31).joinToString(" ") { listOf("수", "목", "금", "토", "일", "월", "화")[it % 7] }
        assertMonth(one("$days31\n$week\n김민지 $month31"))
    }

    @Test fun s04_twoHalves() {
        val (a, b) = month31.split(" ").let { it.take(15) to it.drop(15) }
        val row = one("${(1..15).joinToString(" ")}\n김민지 ${a.joinToString(" ")}\n${(16..31).joinToString(" ")}\n김민지 ${b.joinToString(" ")}")
        assertMonth(row)
    }

    @Test fun s05_spreadsheetTabsWithBlank() {
        val cells = month31.split(" ").toMutableList().also { it[4] = "" }
        val row = one("이름\t" + (1..31).joinToString("\t") + "\n김민지\t" + cells.joinToString("\t"))
        assertNull(row.cells[5])
        assertEquals(N, row.cells[6])
        assertEquals(30, row.cells.size)
    }

    @Test fun s06_teams() {
        val rows = DutyRoster.readText("조 $days31\nA조 $month31\nB조 ${month31.split(" ").drop(2).plus(listOf("D", "D")).joinToString(" ")}", codes)
        assertEquals(2, rows.size)
        assertMonth(rows.first { it.label == "A조" })
    }

    @Test fun s07_list() {
        val row = one("10/1 D\n10/2 E\n10/3 N\n10/4 O")
        assertEquals(listOf(D, E, N, O), (1..4).map { row.cells[it] })
    }

    @Test fun s08_mergedLetters() = assertMonth(one("$days31\n김민지 DDEENNOO DDEENNOO DDEENNOO DDEENNO"))

    @Test fun s09_namesOfCodeLetters() {
        val rows = DutyRoster.readText("$days31\n이나 $month31\n주연 $month31", codes)
        assertEquals(setOf("이나", "주연"), rows.map { it.label }.toSet())
        rows.forEach { assertMonth(it) }
    }

    @Test fun s10_commonCodesGuessed() {
        val factory = DutyCodes(listOf(DutyType(1, "주간", "주", false, LocalTime.of(8, 0)), DutyType(2, "휴무", "휴", true)))
        val row = one("1 2 3 4 5\n김공장 D D N O O", factory)
        assertTrue(row.guessed.containsAll(setOf(1, 2)))
    }

    @Test fun s11_police() {
        val police = DutyCodes(listOf(DutyType(1, "주간", "주", false, LocalTime.of(9, 0)), DutyType(2, "야간", "야", false, LocalTime.of(18, 0), night = true),
            DutyType(3, "비번", "비", true), DutyType(4, "휴무", "휴", true)))
        val row = one("1 2 3 4 5 6 7 8\n박경찰 주 야 비 휴 주 야 비 휴", police)
        assertEquals(DutyCell.Shift(2), row.cells[6])
        assertEquals(8, row.cells.size)
    }

    @Test fun s12_fire() {
        val fire = DutyCodes(listOf(DutyType(1, "당번", "당", false, LocalTime.of(9, 0)), DutyType(2, "비번", "비", true), DutyType(3, "휴무", "휴", true)))
        val row = one("1 2 3 4 5 6\n최소방 당 비 휴 당 비 휴", fire)
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), (1..6).map { (row.cells[it] as DutyCell.Shift).shiftTypeId })
    }

    @Test fun s13_dayWithWeekdayInCell() = assertEquals(D, one("1(수) 2(목) 3(금) 4(토) 5(일)\n김민지 D E N O O").cells[1])

    @Test fun s14_dayWithIl() = assertEquals(E, one("1일 2일 3일 4일 5일\n김민지 D E N O O").cells[2])

    @Test fun s15_commas() = assertEquals(N, one("1,2,3,4,5\n김민지,D,E,N,O,O").cells[3])

    @Test fun s16_noName() = assertEquals("", one("1 2 3 4 5\nD E N O O").label)

    @Test fun s17_koreanWords() = assertEquals(listOf(D, E, N, O), (1..4).map { one("1 2 3 4 5\n김민지 데이 이브닝 나이트 오프 오프").cells[it] })

    @Test fun s18_manyPeoplePick() = assertEquals(3, DutyRoster.readText("$days31\n가 $month31\n나나 $month31\n다다 $month31", codes).size)

    @Test fun s19_monthBefore() = assertTrue(one("29 30 31 1 2 3 4 5\n김민지 N N O D D E E N").cells.keys.all { it in 1..5 })

    @Test fun s20_unknownStaysUnknown() = assertTrue(one("1 2 3 4 5\n김민지 D Q E N O").cells[2] is DutyCell.Unknown)

    @Test fun s21_offWords() = assertEquals(O, one("1 2 3 4 5\n김민지 휴 OFF X 비 -").cells[1])

    @Test fun s22_annualLeave() = assertEquals(DutyCell.Leave(), one("1 2 3 4 5\n김민지 D 연차 E N O").cells[2])

    // ---- 7 that went wrong in 1.23 ----

    /** 상-1: a mark after a code moved every later day one day back, quietly. */
    @Test fun f1_marksAfterCodes() {
        val marked = month31.split(" ").mapIndexed { i, c -> if (i % 5 == 0) "$c*" else if (i % 7 == 3) "$c/" else c }.joinToString(" ")
        val row = one("$days31\n김민지 $marked")
        assertEquals("김민지", row.label)
        assertMonth(row)
        assertEquals("*", row.notes[1])
        assertEquals("/", row.notes[4])
    }

    /** 상-1: a mark on a 한글 code and a code stuck to the name. */
    @Test fun f1b_stuckToTheName() {
        val factory = DutyCodes(listOf(DutyType(1, "주간", "주", false, LocalTime.of(8, 0)), DutyType(2, "야간", "야", false, LocalTime.of(20, 0), night = true),
            DutyType(3, "휴무", "휴", true)))
        val row = one("1 2 3 4 5 6\n김민지주* 야/ 휴 주 야 휴", factory)
        assertEquals("김민지", row.label)
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), (1..6).map { (row.cells[it] as DutyCell.Shift).shiftTypeId })
        // The day split off the name is one to look at.
        assertTrue(1 in row.doubtful(30))
        val latin = one("$days31\n김민지D* ${month31.split(" ").drop(1).joinToString(" ")}")
        assertEquals("김민지", latin.label)
        assertMonth(latin)
        assertTrue(1 in latin.doubtful(31))
    }

    /** 상-2: N-O was three cells, 11D and 9E none. */
    @Test fun f2_joinedAndTimed() {
        val row = one("1 2 3 4 5 6 7 8\n김민지 D N-O O 11D 9E N/O O N→O")
        assertEquals(listOf(D, N, O, D, E, N, O, N), (1..8).map { row.cells[it] })
        assertEquals("N-O", row.notes[2])
        assertEquals("11", row.notes[4])
        assertEquals("9", row.notes[5])
    }

    /** 상-3: every leave word was a whole day of 연차. */
    @Test fun f3_leaveKinds() {
        val row = one("1 2 3 4 5 6 7 8 9\n김민지 반 반차 오전반 반반차 생휴 병가 공가 경조 연차")
        assertEquals(DutyCell.Leave(quarters = 2), row.cells[1])
        assertEquals(DutyCell.Leave(quarters = 2), row.cells[2])
        assertEquals(DutyCell.Leave(quarters = 2, fromStart = true), row.cells[3])
        assertEquals(DutyCell.Leave(quarters = 1), row.cells[4])
        assertEquals(DutyCell.Leave(LeaveKind.UNPAID), row.cells[5])
        (6..8).forEach { assertTrue("${it}일", (row.cells[it] as DutyCell.Unknown).leave) }
        assertEquals(DutyCell.Leave(), row.cells[9])
        // Nothing but 연차 and 반차 counts against 연차; an unknown leave is not written at all.
        val d = LocalDate.of(2026, 10, 1)
        assertEquals(Leave(LeaveKind.ANNUAL, 2), DutyRoster.apply(DayEntry(d), row.cells.getValue(2)).leave)
        assertEquals(0.5, DutyRoster.apply(DayEntry(d), row.cells.getValue(2)).leave!!.days, 0.0)
        assertFalse(DutyRoster.apply(DayEntry(d), row.cells.getValue(5)).leave!!.kind.annual)
        assertNull(DutyRoster.apply(DayEntry(d), row.cells.getValue(6)).leave)
    }

    /** [ROST-06] The answer the user gave once is the calendar's: 병가 read again needs no question. */
    @Test fun f3b_learnedLeave() {
        val learned = DutyCodes(nurse, learned = mapOf("병가" to DutyCell.Leave(LeaveKind.PAID)))
        assertEquals(DutyCell.Leave(LeaveKind.PAID), learned.match("병가"))
        assertEquals("병가", learned.codeOf("병가*"))
        // A code the calendar did not know, learned as a kind of work, is no longer a guess.
        val m = DutyCodes(nurse, learned = mapOf("M" to D))
        assertFalse(m.guessedWork("M", D))
    }

    /** 중-6: the month a roster names, and days a month does not have. */
    @Test fun f4_monthAndLength() {
        val near = LocalDate.of(2026, 10, 24)
        assertEquals(YearMonth.of(2026, 10), DutyRoster.monthIn("10월 근무표\n$days31\n김민지 $month31", near))
        assertEquals(YearMonth.of(2026, 11), DutyRoster.monthIn("2026년 11월 듀티\n1 2 3", near))
        assertEquals(YearMonth.of(2026, 9), DutyRoster.monthIn("2026-09\n1 2 3", near))
        assertEquals(YearMonth.of(2027, 1), DutyRoster.monthIn("1월 근무표", LocalDate.of(2026, 12, 20)))
        assertNull(DutyRoster.monthIn("$days31\n김민지 $month31", near))
        val row = one("$days31\n김민지 $month31")
        assertEquals(listOf(31), DutyRoster.beyond(row, YearMonth.of(2026, 11)))
        assertTrue(DutyRoster.beyond(row, YearMonth.of(2026, 10)).isEmpty())
    }

    /** 중-7: dates in the row of days, and full-width letters from a 한글 document. */
    @Test fun f5_datesAndFullWidth() {
        val dated = one((1..31).joinToString(" ") { "10/$it" } + "\n김민지 $month31")
        assertMonth(dated)
        assertEquals(YearMonth.of(2026, 10), DutyRoster.monthIn((1..31).joinToString(" ") { "10/$it" }, LocalDate.of(2026, 10, 24)))
        assertMonth(one((1..31).joinToString(" ") { "10.$it" } + "\n김민지 $month31"))
        val wide = one("１ ２ ３ ４ ５\n김민지 Ｄ Ｅ Ｎ Ｏ Ｏ")
        assertEquals(listOf(D, E, N, O, O), (1..5).map { wide.cells[it] })
    }

    /** 하: a job title with the name. */
    @Test fun f6_nameAndTitle() {
        assertEquals("김민지", one("1 2 3 4 5\n김민지RN D E N O O").label)
        assertEquals("박수진", one("1 2 3 4 5\n박수진 CN D E N O O").label)
        assertEquals(D, one("1 2 3 4 5\n박수진 CN D E N O O").cells[1])
    }

    /** 하: what a roster overwrites (leave, a day changed by hand), said before saving. */
    @Test fun f7_overwrites() {
        val d1 = LocalDate.of(2026, 10, 1)
        val d2 = d1.plusDays(1)
        val d3 = d1.plusDays(2)
        val entries = mapOf(
            d1 to DayEntry(d1, leave = Leave()),
            d2 to DayEntry(d2, override = ShiftOverride.Shift(2)),
            d3 to DayEntry(d3, override = ShiftOverride.Shift(1)),
        )
        val cells = mapOf(d1 to D, d2 to N, d3 to D)
        assertEquals(setOf(d1, d2), DutyRoster.overwrites(cells, entries))
    }
}
