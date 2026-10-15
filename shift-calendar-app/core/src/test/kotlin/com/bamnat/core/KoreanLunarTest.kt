package com.bamnat.core

import com.bamnat.core.holiday.KoreanLunar
import com.bamnat.core.holiday.LunarDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 음력 [FEAT-05], checked against published Korean holiday dates and leap months. */
class KoreanLunarTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `seollal and chuseok match the published dates`() {
        val seollal = listOf("1990-01-27", "2000-02-05", "2010-02-14", "2015-02-19", "2020-01-25", "2021-02-12", "2022-02-01",
            "2023-01-22", "2024-02-10", "2025-01-29", "2026-02-17", "2027-02-07", "2028-01-27", "2029-02-13", "2030-02-03")
        for (s in seollal) {
            assertEquals(s, LunarDate(d(s).year, 1, 1), KoreanLunar.fromSolar(d(s)))
            assertEquals(s, d(s), KoreanLunar.toSolar(d(s).year, 1, 1))
        }
        val chuseok = listOf("2015-09-27", "2020-10-01", "2021-09-21", "2022-09-10", "2023-09-29", "2024-09-17", "2025-10-06",
            "2026-09-25", "2027-09-15", "2028-10-03", "2029-09-22", "2030-09-12")
        for (s in chuseok) assertEquals(s, d(s), KoreanLunar.toSolar(d(s).year, 8, 15))
        for (s in listOf("2024-05-15", "2025-05-05", "2026-05-24", "2027-05-13")) assertEquals(s, d(s), KoreanLunar.toSolar(d(s).year, 4, 8))
    }

    @Test
    fun `leap months follow the Korean clock`() {
        // 2012 had 윤3월 in Korea (China: 윤4월); 2020 윤4월, 2023 윤2월, 2025 윤6월.
        assertEquals(LunarDate(2012, 3, 1, leap = true), KoreanLunar.fromSolar(d("2012-04-21")))
        assertEquals(LunarDate(2020, 4, 1, leap = true), KoreanLunar.fromSolar(d("2020-05-23")))
        assertEquals(LunarDate(2023, 2, 1, leap = true), KoreanLunar.fromSolar(d("2023-03-22")))
        assertEquals(d("2025-07-25"), KoreanLunar.toSolar(2025, 6, 1, leap = true))
        assertNull(KoreanLunar.toSolar(2026, 6, 1, leap = true))
    }

    @Test
    fun `days run on across a month and a year`() {
        // 2025 음력 12월 is a short month; 2028 설날 is 27 January in Korea (new moon 00:12 KST), a day after China.
        assertEquals(LunarDate(2025, 12, 29), KoreanLunar.fromSolar(d("2026-02-16")))
        assertEquals(LunarDate(2027, 12, 30), KoreanLunar.fromSolar(d("2028-01-26")))
        assertEquals(LunarDate(2026, 8, 24).short, "8.24")
        assertEquals("윤6.1", LunarDate(2025, 6, 1, true).short)
        // Every day of 2026 maps to one lunar date and back.
        var day = d("2026-01-01")
        while (day.year == 2026) {
            val l = KoreanLunar.fromSolar(day)
            assertEquals(day, KoreanLunar.toSolar(l.year, l.month, l.day, l.leap))
            day = day.plusDays(1)
        }
    }
}

class HolidayRulesTest {
    @Test
    fun `rules reproduce the published 2025 to 2027 holidays`() {
        val special = setOf("임시공휴일", "대통령 선거일", "지방선거일")
        for (y in 2025..2027) {
            val published = (1..365).map { java.time.LocalDate.of(y, 1, 1).plusDays(it - 1L) }
                .filter { it.year == y }.mapNotNull { d -> com.bamnat.core.holiday.KoreanHolidays.nameOf(d)?.let { d to it } }
                .filter { it.second !in special }.map { it.first }.toSet()
            val computed = com.bamnat.core.holiday.KoreanHolidays.rules(y).keys
            assertEquals("year $y", published, computed)
        }
        // A year outside the table now knows its lunar holidays: 2028 설날 is 27 January.
        assertEquals("설날", com.bamnat.core.holiday.KoreanHolidays.nameOf(java.time.LocalDate.of(2028, 1, 27)))
    }
}
