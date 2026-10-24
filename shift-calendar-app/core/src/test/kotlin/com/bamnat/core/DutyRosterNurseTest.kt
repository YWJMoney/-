package com.bamnat.core

import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyRoster
import com.bamnat.core.imports.DutyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * [NURS-03] 듀티표 shapes from hospital rosters (made up for the test, no real person): a middle shift S,
 * leave written as 병·생·AL, days with their weekday in the cell, and a roster starting in the month before.
 */
class DutyRosterNurseTest {
    private val types = listOf(
        DutyType(1, "데이", "D", false, LocalTime.of(6, 30)),
        DutyType(2, "이브닝", "E", false, LocalTime.of(14, 0)),
        DutyType(3, "나이트", "N", false, LocalTime.of(22, 0), night = true),
        DutyType(4, "미들", "M", false, LocalTime.of(9, 0)),
        DutyType(5, "오프", "O", true),
    )
    private val codes = DutyCodes(types)

    @Test fun middleAndLeaveCodes() {
        assertEquals(DutyCell.Shift(4), codes.match("S"))
        assertEquals(DutyCell.Shift(2), codes.match("E"))
        listOf("병", "생", "AL", "SL", "공가").forEach { assertEquals(it, DutyCell.Leave, codes.match(it)) }
        assertEquals(DutyCell.Shift(5), codes.match("PH"))
    }

    @Test fun daysWithWeekdays() {
        val text = """
            이름 1(일) 2(월) 3(화) 4(수) 5(목) 6(금) 7(토)
            김간호 D D E N N O O
        """.trimIndent()
        val row = DutyRoster.readText(text, codes).first()
        assertEquals("김간호", row.label)
        assertEquals(DutyCell.Shift(3), row.cells[4])
        assertEquals(7, row.cells.size)
    }

    @Test fun rosterFromTheMonthBefore() {
        val text = """
            이름 29 30 31 1 2 3 4 5 6 7 8
            박간호 N N O D D E E N N O O
        """.trimIndent()
        val row = DutyRoster.readText(text, codes).first()
        // 29~31 are last month's: they never become this month's 29~31.
        assertTrue(row.cells.keys.all { it in 1..8 })
        assertEquals(DutyCell.Shift(1), row.cells[1])
        assertEquals(8, row.cells.size)
    }
}
