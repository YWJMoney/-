package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class HolidayLabelTest {
    @Test
    fun `short names come after the full name`() {
        assertEquals(listOf("대체공휴일", "대체휴일"), KoreanHolidays.labels("대체공휴일"))
        assertEquals(listOf("한글날"), KoreanHolidays.labels("한글날"))
        assertEquals(listOf("설날 연휴", "설 연휴"), KoreanHolidays.labels("설날 연휴"))
    }

    @Test
    fun `two holidays on one day end with the first alone`() {
        assertEquals(
            listOf("어린이날·부처님오신날", "어린이날·부처님날", "어린이날"),
            KoreanHolidays.labels("어린이날·부처님오신날"),
        )
    }

    @Test
    fun `every holiday of the shipped years has a short label of at most five letters`() {
        var d = LocalDate.of(2025, 1, 1)
        while (d.year <= 2030) {
            KoreanHolidays.nameOf(d)?.let { name ->
                val last = KoreanHolidays.labels(name).last()
                assertTrue("$d $name -> $last", last.length <= 5)
            }
            d = d.plusDays(1)
        }
    }
}
