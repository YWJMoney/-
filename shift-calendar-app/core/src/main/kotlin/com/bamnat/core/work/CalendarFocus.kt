package com.bamnat.core.work

/**
 * [CAL-05] What a day on the calendar is mainly about, by how the user works: the shift (교대·일근·시급),
 * the booked jobs (현장·프리랜서), the shop's sales (자영업) or the day's trades and gain (투자) [INV-01]. Every place on the calendar that
 * names a day, adds to it or sums a month asks this instead of guessing from the shifts.
 */
enum class CalendarFocus {
    SHIFTS,
    JOBS,
    SALES,
    INVEST;

    /** Days are filled with money records rather than shifts. */
    val moneyFirst: Boolean get() = this != SHIFTS

    /** What a day with nothing on it is, in the words of this kind of work. */
    val emptyDay: String get() = when (this) {
        SHIFTS -> "쉬는 날"
        JOBS -> "잡힌 일감 없음"
        SALES -> "매출 안 적음"
        INVEST -> "매매 없음"
    }

    /** The one-tap way to add the day's main record; null where days are stamped with shifts. */
    val addLabel: String? get() = when (this) {
        SHIFTS -> null
        JOBS -> "일감 적기"
        SALES -> "매출 적기"
        INVEST -> "매매 기록"
    }

    /** A run of empty days in the month list, e.g. "3일 비어 있어요". */
    fun gap(days: Int): String = when (this) {
        SHIFTS -> "${days}일 쉬어요"
        JOBS -> "${days}일 비어 있어요"
        SALES -> "${days}일 매출 없음"
        INVEST -> "${days}일 매매 없음"
    }

    companion object {
        /** Not chosen yet counts as shift work, as the calendar always did. */
        fun of(style: WorkStyle?): CalendarFocus = when {
            style?.usesJobs == true -> JOBS
            style?.usesSales == true -> SALES
            style?.usesTrades == true -> INVEST
            else -> SHIFTS
        }
    }
}

/**
 * [CAL-07] Weekday work (일근) is off on public holidays in most workplaces, but a weekly pattern
 * repeats on them anyway; such days are offered to be marked off. Shift and part-time work go on
 * through holidays, so they are not asked.
 */
val WorkStyle?.asksHolidaysOff: Boolean get() = this == WorkStyle.DAY

/**
 * [CAL-05] What an empty calendar asks for first, by kind of work: the title, one line, and the
 * button. Shift and weekday work start from the repeating order; the rest from a first record.
 */
data class CalendarStart(val title: String, val text: String, val action: String)

fun WorkStyle?.calendarStart(): CalendarStart = when (this) {
    WorkStyle.DAY -> CalendarStart(
        "일하는 요일을 정해 주세요",
        "월~금처럼 매주 일하는 요일과 시간을 한 번만 정하면 달력이 자동으로 채워져요.",
        "요일과 시간 정하기",
    )
    WorkStyle.HOURLY -> CalendarStart(
        "일한 날을 칠해 보세요",
        "빠른 입력으로 날짜를 누르거나 끌면 알바 시간이 들어가요. 매주 같은 요일이면 요일로 정해 두세요.",
        "빠른 입력",
    )
    WorkStyle.FIELD -> CalendarStart(
        "이번 달 일감이 아직 없어요",
        "날짜를 고르고 일감을 적으면 달력에 표시되고, 시작 전에 알려 드리고, 받을 돈도 챙겨 드려요.",
        "일감 적기",
    )
    WorkStyle.INVEST -> CalendarStart(
        "원금부터 적어 보세요",
        "계좌에 넣은 돈(입금)을 적으면 원금이 되고, 매수·매도를 적으면 칸마다 그날 손익이 빨강·파랑으로 보여요.",
        "입금 적기",
    )
    WorkStyle.BUSINESS -> CalendarStart(
        "이번 달 매출이 아직 없어요",
        "날마다 매출과 경비를 적으면 달력 칸에 순수익이 보이고 이달 합계가 바로 나와요.",
        "오늘 매출 적기",
    )
    else -> CalendarStart(
        "근무 주기를 등록해 보세요",
        "주주야야비휴 같은 교대 순서나 요일별 근무를 한 번만 입력하면 달력이 자동으로 채워져요.",
        "근무 주기 정하기",
    )
}
