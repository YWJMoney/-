package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** [OPS-18] 2028 from the rules (설날 1/27 by KST new moon), with the 국회의원 선거일 the law already fixes (순번 40). */
class Holidays2028Test {
    @Test fun year2028() {
        assertEquals("국회의원 선거일", KoreanHolidays.nameOf(LocalDate.of(2028, 4, 12)))
        assertEquals("설날", KoreanHolidays.nameOf(LocalDate.of(2028, 1, 27)))
        assertEquals("추석", KoreanHolidays.nameOf(LocalDate.of(2028, 10, 3))?.split('·')?.first { it == "추석" })
        assertEquals("부처님오신날", KoreanHolidays.nameOf(LocalDate.of(2028, 5, 2)))
        assertEquals("국회의원 선거일", KoreanHolidays.nameOf(LocalDate.of(2024, 4, 10)))
        assertEquals("임시공휴일", KoreanHolidays.nameOf(LocalDate.of(2024, 10, 1)))
        assertEquals("대체공휴일", KoreanHolidays.nameOf(LocalDate.of(2024, 5, 6)))
    }
}
