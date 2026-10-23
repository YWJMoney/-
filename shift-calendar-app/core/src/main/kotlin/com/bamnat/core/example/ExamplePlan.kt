package com.bamnat.core.example

import com.bamnat.core.money.MoneyKind
import com.bamnat.core.pay.WageType
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** [EASY-01] A shift of the example, by name; [start] null is a day off. */
data class ExampleShift(val name: String, val label: String, val colorArgb: Long, val start: LocalTime?, val end: LocalTime?, val breakMinutes: Int = 0) {
    val isOff: Boolean get() = start == null
}

/** [EASY-01] Extra work on a day: 잔업 minutes, or 특근 hours on a day off. */
data class ExampleExtra(val date: LocalDate, val overtimeMinutes: Int = 0, val special: Pair<LocalTime, LocalTime>? = null)

data class ExampleMemo(val date: LocalDate?, val title: String, val body: String, val pinned: Boolean = false)

data class ExampleMoney(val date: LocalDate, val kind: MoneyKind, val amount: Long, val category: String, val note: String)

data class ExamplePay(val wageType: WageType, val base: Long, val payDay: Int, val hireDate: LocalDate)

data class ExampleJob(val date: LocalDate, val title: String, val amount: Long, val paid: Boolean)

data class ExampleSales(val date: LocalDate, val sales: Long, val costs: Long)

data class ExampleTrade(val date: LocalDate, val name: String, val buy: Boolean, val quantity: Int, val price: Long)

/**
 * [EASY-01] Everything the example calendar of one kind of work holds, made fresh from [today] so the month on
 * screen is always full. Pure data: the app lays it into the example calendar's own database (never backed
 * up, never shared). Names, amounts and places are made up and say so ("예시"), so nothing here reads as
 * someone's real record. The story follows the screenshots' sample data of 1.22 (2조 2교대, 인수인계 메모,
 * 출근길 커피…), one per kind of work.
 */
data class ExamplePlan(
    val style: WorkStyle,
    val shifts: List<ExampleShift>,
    /** Shift names in order for a rotation from [rotationStart]; empty for [weekly] work or none. */
    val rotation: List<String> = emptyList(),
    val rotationStart: LocalDate? = null,
    /** Monday..Sunday shift names for weekly work; null is a day off. Empty when not weekly. */
    val weekly: List<String?> = emptyList(),
    /** Days with another shift than the order's, by name. */
    val swaps: Map<LocalDate, String> = emptyMap(),
    val extras: List<ExampleExtra> = emptyList(),
    val leave: List<LocalDate> = emptyList(),
    val memos: List<ExampleMemo> = emptyList(),
    val money: List<ExampleMoney> = emptyList(),
    val pay: ExamplePay? = null,
    val jobs: List<ExampleJob> = emptyList(),
    val sales: List<ExampleSales> = emptyList(),
    val trades: List<ExampleTrade> = emptyList(),
) {
    companion object {
        const val NAME = "예시 달력"

        fun of(style: WorkStyle, today: LocalDate): ExamplePlan = when (style) {
            WorkStyle.SHIFT -> shift(today)
            WorkStyle.DAY -> day(today)
            WorkStyle.HOURLY -> hourly(today)
            WorkStyle.FIELD -> field(today)
            WorkStyle.BUSINESS -> business(today)
            WorkStyle.INVEST -> invest(today)
        }

        private val DAY_SHIFT = ExampleShift("주간", "주", 0xFF2F80ED, LocalTime.of(8, 0), LocalTime.of(20, 0), 60)
        private val NIGHT_SHIFT = ExampleShift("야간", "야", 0xFF6C5CE7, LocalTime.of(20, 0), LocalTime.of(8, 0), 60)
        private val OFF = ExampleShift("휴무", "휴", 0xFF8E9AAF, null, null)

        /** The month's spending every example shares: small and common, so the 돈 tab is not empty. */
        private fun spending(today: LocalDate): List<ExampleMoney> {
            val first = today.withDayOfMonth(1)
            fun d(n: Long) = first.plusDays(n.coerceAtMost(today.dayOfMonth - 1L))
            return listOf(
                ExampleMoney(d(0), MoneyKind.EXPENSE, 4_500, "카페", "출근길 커피 (예시)"),
                ExampleMoney(d(1), MoneyKind.EXPENSE, 32_000, "식비", "팀 점심 (예시)"),
                ExampleMoney(d(3), MoneyKind.EXPENSE, 13_500, "구독", "영상 구독 (예시)"),
                ExampleMoney(d(6), MoneyKind.EXPENSE, 55_000, "통신", "휴대폰 요금 (예시)"),
            )
        }

        private fun shift(today: LocalDate): ExamplePlan {
            val start = today.withDayOfMonth(1).minusMonths(1)
            val order = listOf("주간", "주간", "야간", "야간", "휴무")
            fun on(d: LocalDate) = order[Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(start, d), order.size.toLong()).toInt()]
            val offDay = (1..10).map { today.minusDays(it.toLong()) }.first { on(it) == "휴무" }
            val workDay = (1..6).map { today.plusDays(it.toLong()) }.first { on(it) == "주간" }
            return ExamplePlan(
                WorkStyle.SHIFT, listOf(DAY_SHIFT, NIGHT_SHIFT, OFF), order, start,
                extras = listOf(ExampleExtra(today.minusDays(2), overtimeMinutes = 120), ExampleExtra(offDay, special = LocalTime.of(9, 0) to LocalTime.of(18, 0))),
                leave = listOf(workDay),
                memos = listOf(
                    ExampleMemo(today, "인수인계 (예시)", "3번 라인 점검 요청\n야간조에 키 반납 확인"),
                    ExampleMemo(today.plusDays(5), "건강검진 (예시)", "오전 9시", pinned = true),
                ),
                money = spending(today),
                pay = ExamplePay(WageType.MONTHLY, 2_600_000, 25, today.minusYears(2).withDayOfMonth(1)),
            )
        }

        private fun day(today: LocalDate): ExamplePlan {
            val work = ExampleShift("주간", "주", 0xFF2F80ED, LocalTime.of(9, 0), LocalTime.of(18, 0), 60)
            val week = List(5) { "주간" } + listOf(null, null)
            val friday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            return ExamplePlan(
                WorkStyle.DAY, listOf(work, OFF), weekly = week,
                extras = listOf(ExampleExtra(friday.minusDays(2), overtimeMinutes = 90)),
                leave = listOf(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))),
                memos = listOf(ExampleMemo(today, "주간 회의 (예시)", "오후 2시 3층 회의실")),
                money = spending(today),
                pay = ExamplePay(WageType.MONTHLY, 2_900_000, 10, today.minusYears(1).minusMonths(3).withDayOfMonth(1)),
            )
        }

        private fun hourly(today: LocalDate): ExamplePlan {
            val evening = ExampleShift("저녁 알바", "저", 0xFFD6457A, LocalTime.of(18, 0), LocalTime.of(23, 0), 30)
            val week = listOf(null, "저녁 알바", null, "저녁 알바", "저녁 알바", "저녁 알바", null)
            return ExamplePlan(
                WorkStyle.HOURLY, listOf(evening, OFF), weekly = week,
                memos = listOf(ExampleMemo(today, "근무 바꾸기 (예시)", "토요일 대타 가능한지 점장님께 묻기")),
                money = spending(today),
                pay = ExamplePay(WageType.HOURLY, 11_000, 10, today.minusMonths(4).withDayOfMonth(1)),
            )
        }

        private fun field(today: LocalDate): ExamplePlan = ExamplePlan(
            WorkStyle.FIELD, emptyList(),
            jobs = listOf(
                ExampleJob(today, "욕실 타일 시공 (예시)", 1_200_000, paid = false),
                ExampleJob(today.minusDays(5), "도배 (예시)", 800_000, paid = true),
                ExampleJob(today.plusDays(2), "현장 실측 (예시)", 0, paid = false),
            ),
            memos = listOf(ExampleMemo(today.plusDays(2), "실측 준비 (예시)", "줄자·레이저 거리계")),
            money = spending(today),
        )

        private fun business(today: LocalDate): ExamplePlan = ExamplePlan(
            WorkStyle.BUSINESS, emptyList(),
            sales = (0..4).map { ExampleSales(today.minusDays(it.toLong()), 820_000L + it * 70_000L, 240_000L + it * 15_000L) },
            memos = listOf(ExampleMemo(today, "재료 주문 (예시)", "원두 2kg, 우유 12팩")),
            money = spending(today),
        )

        private fun invest(today: LocalDate): ExamplePlan = ExamplePlan(
            WorkStyle.INVEST, emptyList(),
            trades = listOf(
                ExampleTrade(today.minusDays(9), "예시 전자", buy = true, quantity = 10, price = 71_000),
                ExampleTrade(today.minusDays(3), "예시 지수 ETF", buy = true, quantity = 5, price = 38_500),
                ExampleTrade(today.minusDays(1), "예시 전자", buy = false, quantity = 4, price = 74_200),
            ),
            memos = listOf(ExampleMemo(today, "매매 일지 (예시)", "분할 매수 원칙 지키기")),
        )
    }
}
