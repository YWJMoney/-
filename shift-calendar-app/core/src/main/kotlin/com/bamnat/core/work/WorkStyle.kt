package com.bamnat.core.work

/**
 * How the user works, chosen on first launch [WORK-01]. It decides which setup comes first, what
 * the calendar shows and where the month's income comes from [WORK-04]; everything else stays
 * reachable from settings, so a wrong pick never hides data.
 */
enum class WorkStyle(val label: String, val description: String, val examples: String) {
    SHIFT("교대·주기 근무", "정해진 순서로 근무가 돌아가요", "3조 2교대, 4조 2교대, 24시간 맞교대"),
    DAY("일근 (평일 근무)", "매주 같은 요일에 같은 시간 일해요", "사무직, 공장 주간, 공무원"),
    HOURLY("아르바이트·시급", "일한 날과 시간만큼 시급으로 받아요", "편의점, 카페, 파트타임"),
    FIELD("현장·프리랜서", "일정을 잡고 건마다 돈을 받아요", "인테리어, 시공, 강사, 촬영"),
    BUSINESS("자영업", "가게를 운영하며 매일 매출이 생겨요", "식당, 카페, 매장 운영");

    /** A repeating pattern drives the calendar, so setup starts at the pattern screen. */
    val usesPattern: Boolean get() = this == SHIFT || this == DAY

    /** Income is wages from the pay engine (hours × rates) [WORK-04]. */
    val usesPayEngine: Boolean get() = this == SHIFT || this == DAY || this == HOURLY

    /** Days hold booked jobs with a price, deposit and paid state [WORK-02]. */
    val usesJobs: Boolean get() = this == FIELD

    /** Days hold the shop's sales and costs [WORK-03]. */
    val usesSales: Boolean get() = this == BUSINESS

    /** What the money tab calls the main income line. */
    val incomeLabel: String get() = when (this) {
        SHIFT, DAY, HOURLY -> "급여 (예상)"
        FIELD -> "일감 수입"
        BUSINESS -> "가게 순수익"
    }
}
