package com.bamnat.core.work

import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * [TYPE-01] The words each kind of work uses for the same parts of the app, so a shop owner reads
 * 영업일·정기휴무 and 매출, a part-timer 출근 and 시급, a freelancer 일감 and 받을 돈, instead of the
 * shift worker's 근무 주기 everywhere (근무유형별 시장조사, 2026-10-20).
 */
data class WorkWords(
    /** The bottom tab that holds hours, pay, jobs or sales. */
    val tab: String,
    /** That tab's heading. */
    val statsTitle: String,
    /** A working day ("근무", "출근", "영업"). */
    val workDay: String,
    /** A day off ("쉬는 날", "휴무"). */
    val offDay: String,
    /** The setting that makes the calendar repeat. */
    val pattern: String,
    val patternHint: String,
    /** The setting for the kinds of days and their hours. */
    val types: String,
    /** Where the income is set up. */
    val income: String,
    val incomeHint: String,
)

fun WorkStyle?.words(): WorkWords = when (this) {
    WorkStyle.DAY -> WorkWords(
        "근무·급여", "근무·급여", "출근", "쉬는 날",
        "일하는 요일", "월~금처럼 매주 일하는 요일과 시작일", "출근 시간", "급여 계산", "월급·연차·야근 수당·급여일",
    )
    WorkStyle.HOURLY -> WorkWords(
        "출근·시급", "출근·시급", "출근", "쉬는 날",
        "출근 요일", "매주 같은 요일에 일하면 정해 두세요", "알바 시간", "시급과 주휴수당", "시급·주휴수당·세금(3.3%·4대보험)",
    )
    WorkStyle.FIELD -> WorkWords(
        "일감·수입", "일감·수입", "일하는 날", "빈 날",
        "되풀이되는 일정", "매주 같은 요일 수업처럼 되풀이되면 정해요", "일정 종류", "일감과 받을 돈", "건별 금액·선금·3.3% 원천징수",
    )
    WorkStyle.BUSINESS -> WorkWords(
        "매출·영업", "매출·영업", "영업", "휴무",
        "영업일·정기휴무", "영업시간, 매주 쉬는 요일, 매달 쉬는 날, 공휴일", "영업시간", "매출과 경비", "하루 매출·경비·결제 수단",
    )
    else -> WorkWords(
        "근무·급여", "근무·급여", "근무", "쉬는 날",
        "근무 주기", "주주휴휴야야휴휴처럼 돌아가는 순서와 시작일", "근무 종류와 시간", "급여 계산", "기본급·수당·회사 규칙·연차·급여일",
    )
}

/** [TYPE-03] "매달 둘째 월요일": [nth] 1..5, or -1 for the month's last. */
data class NthWeekday(val nth: Int, val day: DayOfWeek) {
    fun matches(date: LocalDate): Boolean {
        if (date.dayOfWeek != day) return false
        val n = (date.dayOfMonth - 1) / 7 + 1
        return if (nth == -1) date.plusWeeks(1).monthValue != date.monthValue else n == nth
    }

    val label: String get() = (if (nth == -1) "마지막" else ORDINALS.getOrElse(nth - 1) { "${nth}째" }) + " " + DAY_NAMES[day.ordinal]

    companion object {
        val ORDINALS = listOf("첫째", "둘째", "셋째", "넷째", "다섯째")
        val DAY_NAMES = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")

        fun encode(list: List<NthWeekday>): String = list.joinToString(",") { "${it.nth}:${it.day.name}" }
        fun decode(raw: String?): List<NthWeekday> = raw.orEmpty().split(',').mapNotNull { p ->
            val (n, d) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val nth = n.toIntOrNull()?.takeIf { it == -1 || it in 1..5 } ?: return@mapNotNull null
            runCatching { NthWeekday(nth, DayOfWeek.valueOf(d)) }.getOrNull()
        }.distinct()
    }
}

/**
 * [TYPE-03] How a shop opens: its hours, the weekdays it is always closed (정기휴무), the days of the
 * month it closes (매달 둘째·넷째 월요일), whether public holidays are closed, and whether an open day
 * that passed with no sales written counts as closed (임시휴무) by itself.
 */
data class ShopRules(
    val open: LocalTime = LocalTime.of(10, 0),
    val close: LocalTime = LocalTime.of(22, 0),
    val closedWeekdays: Set<DayOfWeek> = emptySet(),
    val monthly: List<NthWeekday> = emptyList(),
    val holidaysClosed: Boolean = false,
    val autoClosed: Boolean = true,
    /** The last day [ShopDays.autoClosed] already looked at, so a day is judged once. */
    val checkedUntil: LocalDate? = null,
    /** Days [ShopDays.autoClosed] turned into 휴무, so only those open again when sales come in. */
    val autoClosedDays: Set<LocalDate> = emptySet(),
)

object ShopDays {
    /** A day the shop is closed by its rules (the weekly ones included). */
    fun regularlyClosed(rules: ShopRules, date: LocalDate, isHoliday: (LocalDate) -> Boolean): Boolean =
        date.dayOfWeek in rules.closedWeekdays || rules.monthly.any { it.matches(date) } || (rules.holidaysClosed && isHoliday(date))

    /**
     * Days from [from] to [to] closed by the rules a weekly order cannot hold: the monthly ones and
     * holidays. They are written onto the calendar as days off, so every screen shows them.
     */
    fun extraClosed(rules: ShopRules, from: LocalDate, to: LocalDate, isHoliday: (LocalDate) -> Boolean): List<LocalDate> =
        days(from, to).filter { d -> d.dayOfWeek !in rules.closedWeekdays && (rules.monthly.any { it.matches(d) } || (rules.holidaysClosed && isHoliday(d))) }

    /**
     * [TYPE-04] Open days up to yesterday that passed with nothing sold written down: closed after all.
     * Only days after [ShopRules.checkedUntil] and within the last [LOOKBACK_DAYS] days are judged.
     */
    fun autoClosed(rules: ShopRules, today: LocalDate, isOpen: (LocalDate) -> Boolean, hasSales: (LocalDate) -> Boolean): List<LocalDate> {
        if (!rules.autoClosed) return emptyList()
        val last = today.minusDays(1)
        val first = maxOf(rules.checkedUntil?.plusDays(1) ?: last, today.minusDays(LOOKBACK_DAYS))
        if (first.isAfter(last)) return emptyList()
        return days(first, last).filter { isOpen(it) && !hasSales(it) }
    }

    /** "매주 일요일 · 둘째·넷째 월요일 · 공휴일 휴무", or that there is no regular day off. */
    fun describe(rules: ShopRules): String {
        val parts = mutableListOf<String>()
        if (rules.closedWeekdays.isNotEmpty()) {
            parts += "매주 " + DayOfWeek.entries.filter { it in rules.closedWeekdays }.joinToString("·") { NthWeekday.DAY_NAMES[it.ordinal].removeSuffix("요일") } + "요일"
        }
        rules.monthly.groupBy { it.day }.forEach { (day, list) ->
            parts += "매달 " + list.sortedBy { if (it.nth == -1) 9 else it.nth }.joinToString("·") { it.label.substringBefore(' ') } + " " + NthWeekday.DAY_NAMES[day.ordinal]
        }
        if (rules.holidaysClosed) parts += "공휴일"
        return if (parts.isEmpty()) "정기휴무 없음" else parts.joinToString(" · ") + " 휴무"
    }

    private fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        if (to.isBefore(from)) emptyList() else (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }

    const val LOOKBACK_DAYS = 31L
    /** How far ahead the monthly and holiday days off are written. */
    const val AHEAD_DAYS = 400L
}

/** [TYPE-05] A shop's month: open and closed days, a typical day's sales and each weekday's average. */
data class ShopMonth(
    val openDays: Int,
    val closedDays: Int,
    val salesDays: Int,
    val sales: Long,
    val net: Long,
    /** Sales ÷ days with sales: closed days do not pull the average down. */
    val perOpenDay: Long,
    val byWeekday: Map<DayOfWeek, Long>,
    val best: DailySales?,
    val worst: DailySales?,
) {
    /** The weekday that sells best on average, when there are sales on at least two weekdays. */
    val bestWeekday: DayOfWeek? get() = byWeekday.filterValues { it > 0 }.takeIf { it.size >= 2 }?.maxByOrNull { it.value }?.key
}

/** [TYPE-06] A part-timer's week and whether it earns 주휴수당 (15 hours or more, 근로기준법 제55조·제18조). */
data class HourlyWeek(val start: LocalDate, val minutes: Int, val absent: Int) {
    val holidayPay: Boolean get() = minutes >= HOLIDAY_PAY_MINUTES && absent == 0
    /** 주휴 hours: weekly hours ÷ 40 × 8, at most 8. */
    val holidayPayMinutes: Int get() = if (!holidayPay) 0 else minOf(minutes / 5, 8 * 60)
    val shortBy: Int get() = (HOLIDAY_PAY_MINUTES - minutes).coerceAtLeast(0)

    companion object {
        const val HOLIDAY_PAY_MINUTES = 15 * 60
    }
}

/** [TYPE-07] A freelancer's month by client: how many jobs, what they earned and what is still owed. */
data class ClientTotal(val client: String, val jobs: Int, val earned: Long, val owed: Long)

object WorkStats {
    fun shop(month: YearMonth, sales: List<DailySales>, isOpen: (LocalDate) -> Boolean): ShopMonth {
        val inMonth = sales.filter { YearMonth.from(it.date) == month }
        val sold = inMonth.filter { it.sales > 0 }
        val dates = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val open = dates.count { isOpen(it) || sold.any { s -> s.date == it } }
        val byWeekday = sold.groupBy { it.date.dayOfWeek }.mapValues { (_, l) -> l.sumOf { it.sales } / l.size }
        return ShopMonth(
            openDays = open,
            closedDays = dates.size - open,
            salesDays = sold.size,
            sales = inMonth.sumOf { it.sales },
            net = inMonth.sumOf { it.net },
            perOpenDay = if (sold.isEmpty()) 0 else sold.sumOf { it.sales } / sold.size,
            byWeekday = DayOfWeek.entries.associateWith { byWeekday[it] ?: 0 },
            best = sold.maxByOrNull { it.sales },
            worst = sold.takeIf { it.size >= 2 }?.minByOrNull { it.sales },
        )
    }

    /** Each week that starts in [month], with its hours ([minutesOn]) and days marked absent ([absentOn]). */
    fun hourlyWeeks(month: YearMonth, weekStart: DayOfWeek, minutesOn: (LocalDate) -> Int, absentOn: (LocalDate) -> Boolean): List<HourlyWeek> {
        var start = month.atDay(1)
        while (start.dayOfWeek != weekStart) start = start.minusDays(1)
        val out = mutableListOf<HourlyWeek>()
        while (!start.isAfter(month.atEndOfMonth())) {
            val days = (0L..6L).map { start.plusDays(it) }
            out += HourlyWeek(start, days.sumOf(minutesOn), days.count(absentOn))
            start = start.plusWeeks(1)
        }
        return out
    }

    fun clients(month: YearMonth, jobs: List<Job>): List<ClientTotal> =
        jobs.filter { YearMonth.from(it.date) == month }
            .groupBy { it.client.trim().ifEmpty { "거래처 미정" } }
            .map { (c, l) -> ClientTotal(c, l.size, l.sumOf { it.payable }, l.sumOf { it.unpaid }) }
            .sortedWith(compareByDescending<ClientTotal> { it.earned }.thenBy { it.client })
}
