package com.bamnat.core.holiday

import java.time.LocalDate

/**
 * 관공서 공휴일, 대체공휴일 and 근로자의 날 for the years the app ships with [FEAT-05, OPS-18].
 * Built in so the calendar and pay work offline; a later release refreshes it from the
 * 특일 정보 data. Dates follow the 월력요항; 임시공휴일 are added when announced.
 */
object KoreanHolidays {
    const val VERSION = "2026.10"

    private val table: Map<LocalDate, String> = buildMap {
        fun add(y: Int, m: Int, d: Int, name: String) = put(LocalDate.of(y, m, d), name)
        // 2025
        add(2025, 1, 1, "신정")
        add(2025, 1, 27, "임시공휴일")
        add(2025, 1, 28, "설날 연휴"); add(2025, 1, 29, "설날"); add(2025, 1, 30, "설날 연휴")
        add(2025, 3, 1, "삼일절"); add(2025, 3, 3, "대체공휴일")
        add(2025, 5, 1, "근로자의 날")
        add(2025, 5, 5, "어린이날·부처님오신날"); add(2025, 5, 6, "대체공휴일")
        add(2025, 6, 3, "대통령 선거일")
        add(2025, 6, 6, "현충일")
        add(2025, 8, 15, "광복절")
        add(2025, 10, 3, "개천절")
        add(2025, 10, 5, "추석 연휴"); add(2025, 10, 6, "추석"); add(2025, 10, 7, "추석 연휴"); add(2025, 10, 8, "대체공휴일")
        add(2025, 10, 9, "한글날")
        add(2025, 12, 25, "성탄절")
        // 2026
        add(2026, 1, 1, "신정")
        add(2026, 2, 16, "설날 연휴"); add(2026, 2, 17, "설날"); add(2026, 2, 18, "설날 연휴")
        add(2026, 3, 1, "삼일절"); add(2026, 3, 2, "대체공휴일")
        add(2026, 5, 1, "근로자의 날")
        add(2026, 5, 5, "어린이날")
        add(2026, 5, 24, "부처님오신날"); add(2026, 5, 25, "대체공휴일")
        add(2026, 6, 3, "지방선거일")
        add(2026, 6, 6, "현충일")
        add(2026, 8, 15, "광복절"); add(2026, 8, 17, "대체공휴일")
        add(2026, 9, 24, "추석 연휴"); add(2026, 9, 25, "추석"); add(2026, 9, 26, "추석 연휴")
        add(2026, 10, 3, "개천절"); add(2026, 10, 5, "대체공휴일")
        add(2026, 10, 9, "한글날")
        add(2026, 12, 25, "성탄절")
        // 2027
        add(2027, 1, 1, "신정")
        add(2027, 2, 6, "설날 연휴"); add(2027, 2, 7, "설날"); add(2027, 2, 8, "설날 연휴"); add(2027, 2, 9, "대체공휴일")
        add(2027, 3, 1, "삼일절")
        add(2027, 5, 1, "근로자의 날")
        add(2027, 5, 5, "어린이날")
        add(2027, 5, 13, "부처님오신날")
        add(2027, 6, 6, "현충일")
        add(2027, 8, 15, "광복절"); add(2027, 8, 16, "대체공휴일")
        add(2027, 9, 14, "추석 연휴"); add(2027, 9, 15, "추석"); add(2027, 9, 16, "추석 연휴")
        add(2027, 10, 3, "개천절"); add(2027, 10, 4, "대체공휴일")
        add(2027, 10, 9, "한글날"); add(2027, 10, 11, "대체공휴일")
        add(2027, 12, 25, "성탄절"); add(2027, 12, 27, "대체공휴일")
    }

    /** The holiday's name, or null on an ordinary day. */
    fun nameOf(date: LocalDate): String? =
        if (date.year in years) table[date] else fixed["%02d-%02d".format(date.monthValue, date.dayOfMonth)]

    /** Solar-date holidays, used for years outside the table (no lunar dates or substitutes). */
    private val fixed = mapOf(
        "01-01" to "신정", "03-01" to "삼일절", "05-01" to "근로자의 날", "05-05" to "어린이날", "06-06" to "현충일",
        "08-15" to "광복절", "10-03" to "개천절", "10-09" to "한글날", "12-25" to "성탄절",
    )

    fun isHoliday(date: LocalDate): Boolean = nameOf(date) != null

    /** Years the table covers; outside them only fixed-date holidays are known. */
    val years: IntRange = 2025..2027
}
