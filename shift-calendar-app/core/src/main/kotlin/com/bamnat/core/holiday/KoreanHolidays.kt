package com.bamnat.core.holiday

import java.time.LocalDate

/**
 * 관공서 공휴일, 대체공휴일 and 근로자의 날 for the years the app ships with [FEAT-05, OPS-18].
 * Built in so the calendar and pay work offline; a later release refreshes it from the
 * 특일 정보 data. Dates follow the 월력요항; 임시공휴일 are added when announced.
 */
object KoreanHolidays {
    const val VERSION = "2026.10b"

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
        add(2026, 5, 1, "노동절")
        add(2026, 5, 5, "어린이날")
        add(2026, 5, 24, "부처님오신날"); add(2026, 5, 25, "대체공휴일")
        add(2026, 6, 3, "지방선거일")
        add(2026, 6, 6, "현충일")
        add(2026, 7, 17, "제헌절")
        add(2026, 8, 15, "광복절"); add(2026, 8, 17, "대체공휴일")
        add(2026, 9, 24, "추석 연휴"); add(2026, 9, 25, "추석"); add(2026, 9, 26, "추석 연휴")
        add(2026, 10, 3, "개천절"); add(2026, 10, 5, "대체공휴일")
        add(2026, 10, 9, "한글날")
        add(2026, 12, 25, "성탄절")
        // 2027
        add(2027, 1, 1, "신정")
        add(2027, 2, 6, "설날 연휴"); add(2027, 2, 7, "설날"); add(2027, 2, 8, "설날 연휴"); add(2027, 2, 9, "대체공휴일")
        add(2027, 3, 1, "삼일절")
        add(2027, 5, 1, "노동절")
        add(2027, 5, 5, "어린이날")
        add(2027, 5, 13, "부처님오신날")
        add(2027, 6, 6, "현충일")
        add(2027, 7, 17, "제헌절"); add(2027, 7, 19, "대체공휴일")
        add(2027, 8, 15, "광복절"); add(2027, 8, 16, "대체공휴일")
        add(2027, 9, 14, "추석 연휴"); add(2027, 9, 15, "추석"); add(2027, 9, 16, "추석 연휴")
        add(2027, 10, 3, "개천절"); add(2027, 10, 4, "대체공휴일")
        add(2027, 10, 9, "한글날"); add(2027, 10, 11, "대체공휴일")
        add(2027, 12, 25, "성탄절"); add(2027, 12, 27, "대체공휴일")
    }

    /** The holiday's name, or null on an ordinary day. */
    fun nameOf(date: LocalDate): String? =
        if (date.year in years) table[date] else computed.getOrPut(date.year) { rules(date.year) }[date]

    fun isHoliday(date: LocalDate): Boolean = nameOf(date) != null

    /**
     * [CAL-04] Ways to write [name] under a date, longest first: the name as it is, then shortened
     * (대체공휴일 → 대체휴일, 부처님오신날 → 부처님날), then only the first of two holidays on one day.
     * A day cell shows the first that fits its width.
     */
    fun labels(name: String): List<String> {
        val parts = name.split('·')
        val short = parts.map { SHORT[it] ?: it }
        return listOf(name, short.joinToString("·"), short.first()).distinct()
    }

    private val SHORT = mapOf(
        "설날 연휴" to "설 연휴",
        "추석 연휴" to "추석연휴",
        "대체공휴일" to "대체휴일",
        "임시공휴일" to "임시휴일",
        "부처님오신날" to "부처님날",
        "근로자의 날" to "근로자날",
        "대통령 선거일" to "선거일",
        "지방선거일" to "선거일",
        "국회의원 선거일" to "선거일",
    )

    /**
     * 5월 1일 노동절(2025년까지 근로자의 날) [PAYX-07]. A paid holiday by its own law at every
     * workplace, 5인 미만 included, and it cannot be swapped for another day (휴일 대체).
     */
    fun isLaborDay(date: LocalDate): Boolean = date.monthValue == 5 && date.dayOfMonth == 1

    /** Years the table covers (checked against the 월력요항, with 임시공휴일 and 선거일). */
    val years: IntRange = 2025..2027

    /**
     * [OPS-18] Days outside [years] the rules cannot work out: 2024's 선거일 and 임시공휴일 (국군의 날), and the
     * 선거일 the law already fixes ahead (제23대 국회의원 선거, 임기만료 2028-05-29).
     */
    private val ELECTIONS = mapOf(
        LocalDate.of(2024, 4, 10) to "국회의원 선거일",
        LocalDate.of(2024, 10, 1) to "임시공휴일",
        LocalDate.of(2028, 4, 12) to "국회의원 선거일",
    )

    private val computed = java.util.concurrent.ConcurrentHashMap<Int, Map<LocalDate, String>>()

    /**
     * Holidays of any other year from the rules of 관공서의 공휴일에 관한 규정 [FEAT-05]: solar dates,
     * lunar dates from [KoreanLunar], and 대체공휴일 (설·추석 연휴 on a Sunday or another holiday;
     * 어린이날 on a weekend or another holiday; 삼일절·부처님오신날·제헌절·광복절·개천절·한글날·성탄절 on a
     * weekend). 임시공휴일 and 선거일 cannot be known ahead, so they are missing.
     */
    internal fun rules(year: Int): Map<LocalDate, String> {
        val days = sortedMapOf<LocalDate, String>()
        // [OPS-18] 선거일 fixed by 공직선거법 제34조 (임기만료일 전 50일 이후 첫 수요일 등), known years ahead.
        ELECTIONS.filterKeys { it.year == year }.forEach { (d, n) -> days[d] = n }
        fun add(d: LocalDate?, name: String) {
            if (d == null || d.year != year && name != "설날 연휴") return
            days[d] = days[d]?.let { "$it·$name" } ?: name
        }
        add(LocalDate.of(year, 1, 1), "신정")
        val seollal = KoreanLunar.toSolar(year, 1, 1)
        add(seollal?.minusDays(1), "설날 연휴"); add(seollal, "설날"); add(seollal?.plusDays(1), "설날 연휴")
        add(LocalDate.of(year, 3, 1), "삼일절")
        add(LocalDate.of(year, 5, 1), if (year >= 2026) "노동절" else "근로자의 날")
        add(LocalDate.of(year, 5, 5), "어린이날")
        val buddha = KoreanLunar.toSolar(year, 4, 8)
        add(buddha, "부처님오신날")
        add(LocalDate.of(year, 6, 6), "현충일")
        // 제헌절 is a 공휴일 again from 2026 (공휴일에 관한 법률 개정, 2026.1.29 국회 통과).
        val constitution = LocalDate.of(year, 7, 17).takeIf { year >= 2026 }
        add(constitution, "제헌절")
        add(LocalDate.of(year, 8, 15), "광복절")
        val chuseok = KoreanLunar.toSolar(year, 8, 15)
        add(chuseok?.minusDays(1), "추석 연휴"); add(chuseok, "추석"); add(chuseok?.plusDays(1), "추석 연휴")
        add(LocalDate.of(year, 10, 3), "개천절")
        add(LocalDate.of(year, 10, 9), "한글날")
        add(LocalDate.of(year, 12, 25), "성탄절")

        val base = days.toMap()
        fun weekend(d: LocalDate) = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY
        // 근로자의 날 is not a 관공서 공휴일, so it neither causes nor blocks a substitute.
        // Whether 노동절 itself gets a 대체공휴일 was reported both ways in 2026, so it is left out.
        val laborDay = setOf("근로자의 날", "노동절")
        fun overlaps(d: LocalDate) = (base[d] ?: "").split('·').count { it !in laborDay } > 1
        fun substitute(after: LocalDate) {
            var d = after.plusDays(1)
            while (weekend(d) || days[d]?.let { it !in laborDay } == true) d = d.plusDays(1)
            add(d, "대체공휴일")
        }
        for (group in listOfNotNull(seollal, chuseok)) {
            val span = (-1L..1L).map { group.plusDays(it) }
            if (span.any { it.dayOfWeek == java.time.DayOfWeek.SUNDAY || overlaps(it) }) substitute(span.last())
        }
        val children = LocalDate.of(year, 5, 5)
        if (weekend(children) || overlaps(children)) substitute(children)
        for (d in listOfNotNull(LocalDate.of(year, 3, 1), buddha, constitution, LocalDate.of(year, 8, 15), LocalDate.of(year, 10, 3),
            LocalDate.of(year, 10, 9), LocalDate.of(year, 12, 25))) {
            if (weekend(d) && !(d == buddha && children == buddha)) substitute(d)
        }
        return days.filterKeys { it.year == year }
    }
}
