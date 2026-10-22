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
 * [NURS-04] 듀티표 사진 → 한 달 연달아 넣기 (순번 275): the days to look at are the cells not read, the cells a common
 * code was guessed for, and the days the photo lost; cells in the calendar's own words are sure.
 */
class DutyRosterTypingTest {
    private val types = listOf(
        DutyType(1, "데이", "D", false, LocalTime.of(6, 30)),
        DutyType(2, "이브닝", "E", false, LocalTime.of(14, 0)),
        DutyType(3, "나이트", "N", false, LocalTime.of(22, 0), night = true),
        DutyType(5, "오프", "O", true),
    )
    private val codes = DutyCodes(types)

    @Test fun doubtfulCells() {
        // 1–7 read, 4 lost, 5 a scribble, 6 written 야간 (a common code, not the calendar's own N), 7 휴 (a day off).
        val text = "1 2 3 4 5 6 7 8\n김간호 D E N . ㅁ 야간 휴 O"
        val row = DutyRoster.readText(text, codes).first()
        assertEquals(DutyCell.Shift(3), row.cells[6])
        assertTrue(row.guessed.toString(), 6 in row.guessed)
        assertTrue(7 !in row.guessed)
        val doubt = row.doubtful(30)
        assertTrue(doubt.toString(), 6 in doubt)
        assertTrue(doubt.toString(), (1..3).none { it in doubt })
        assertTrue(doubt.toString(), 8 !in doubt)
        // A day the photo lost between read ones is doubtful too.
        val gap = com.bamnat.core.imports.RosterRow("", sortedMapOf(1 to DutyCell.Shift(1), 3 to DutyCell.Shift(1), 4 to DutyCell.Unknown("?")))
        assertEquals(setOf(2, 4), gap.doubtful(30))
    }
}
