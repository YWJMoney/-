package com.bamnat.core

import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyType
import com.bamnat.core.imports.RosterLayout
import com.bamnat.core.imports.RosterSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [IMPORT-03] 불규칙 근무표 files (made up, no real person): a ward's 가로형 roster and a store's 세로형 list. */
class RosterSheetTest {
    private val codes = DutyCodes(listOf(
        DutyType(1, "데이", "D", false, LocalTime.of(7, 0)),
        DutyType(2, "이브닝", "E", false, LocalTime.of(15, 0)),
        DutyType(3, "나이트", "N", false, LocalTime.of(23, 0), night = true),
        DutyType(4, "오프", "O", true),
    ))
    private val near = LocalDate.of(2026, 10, 20)

    @Test
    fun `a ward roster across the page, a line a nurse`() {
        val rows = listOf(
            listOf("2026년 11월 근무표"),
            listOf("이름") + (1..8).map { "$it" } + listOf("합계"),
            listOf("", "일", "월", "화", "수", "목", "금", "토", "일", ""),
            listOf("김간호", "D", "D", "E", "N", "N", "O", "O", "교육", "8"),
            listOf("이간호", "N", "N", "O", "O", "D", "D", "E", "E", "8"),
        )
        val read = RosterSheet.read(rows, codes, near = near)!!
        assertEquals(RosterLayout.WIDE, read.layout)
        assertEquals(YearMonth.of(2026, 11), read.month)
        assertEquals(listOf("김간호", "이간호"), read.people.map { it.name })
        val kim = read.people.first()
        assertEquals(8, kim.days.size)
        assertEquals(DutyCell.Shift(3), kim.days[3].cell)
        assertEquals(listOf("교육"), kim.unknown)
        // Nothing is written for a code not picked; once picked, it is.
        assertEquals(7, RosterSheet.resolve(kim, emptyMap()).size)
        assertEquals(DutyCell.Shift(1), RosterSheet.resolve(kim, mapOf("교육" to DutyCell.Shift(1)))[LocalDate.of(2026, 11, 8)])
    }

    @Test
    fun `a store's list down the page, a line a day`() {
        val rows = listOf(
            listOf("일자", "이름", "근무"),
            listOf("11/3", "박알바", "D"),
            listOf("11/4", "박알바", "E"),
            listOf("11/5", "최알바", "O"),
            listOf("11/6", "박알바", "휴"),
            listOf("11/7(토)", "박알바", "야간"),
        )
        val read = RosterSheet.read(rows, codes, near = near)!!
        assertEquals(RosterLayout.LONG, read.layout)
        val park = read.people.single { it.name == "박알바" }
        assertEquals(LocalDate.of(2026, 11, 3), park.days.first().date)
        assertEquals(listOf(DutyCell.Shift(1), DutyCell.Shift(2), DutyCell.Shift(4), DutyCell.Shift(3)), park.days.map { it.cell })
        assertEquals(1, read.people.single { it.name == "최알바" }.days.size)
    }

    @Test
    fun `a file with no dates is no roster`() {
        assertNull(RosterSheet.read(listOf(listOf("이름", "근무"), listOf("박알바", "D")), codes, near = near))
        assertEquals(YearMonth.of(2027, 1), RosterSheet.monthIn(listOf(listOf("2027. 1월 듀티"))))
    }
}
