package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/** [FLD-11] One line of a 견적서: what, how many in what unit, and the price of one. */
data class QuoteItem(val name: String, val quantity: Double = 1.0, val unit: String = "식", val unitPrice: Long = 0) {
    val amount: Long get() = (quantity * unitPrice).roundToLong().coerceAtLeast(0)
}

/** [FLD-11] How 부가세 sits on a 견적서: left off, added on top (별도), or already in the prices (포함). */
enum class QuoteVat(val label: String) { NONE("부가세 없음"), ADDED("부가세 별도"), INCLUDED("부가세 포함") }

/**
 * [FLD-11] 간단 견적서: items × quantity × unit price, with 부가세 as the user picks, for a client, kept
 * with the calendar and turned into a 가계약 job when the client says yes.
 */
data class Quote(
    val id: Long = 0,
    val date: LocalDate,
    val client: String = "",
    val title: String = "",
    val items: List<QuoteItem> = emptyList(),
    val vat: QuoteVat = QuoteVat.NONE,
    val validDays: Int = 30,
    val note: String = "",
    /** The job made from it; null until saved as a job. */
    val jobId: Long? = null,
) {
    private val itemsTotal: Long get() = items.sumOf { it.amount }

    /** 공급가액: the price before 부가세. */
    val supply: Long get() = when (vat) {
        QuoteVat.INCLUDED -> (itemsTotal / 1.1).roundToLong()
        else -> itemsTotal
    }

    /** 부가세: 10% of the supply, below a won dropped. */
    val vatAmount: Long get() = when (vat) {
        QuoteVat.NONE -> 0
        QuoteVat.ADDED -> supply / 10
        QuoteVat.INCLUDED -> itemsTotal - supply
    }

    val total: Long get() = supply + vatAmount

    val validUntil: LocalDate get() = date.plusDays(validDays.toLong().coerceAtLeast(0))

    /** [FLD-11] 일감으로 저장: a 가계약 job for the quote's total, on [date] unless another day is given. */
    fun toJob(on: LocalDate = date): Job = Job(
        date = on, title = title.ifBlank { items.firstOrNull()?.name ?: "견적" }, client = client.trim(), amount = total,
        status = JobStatus.TENTATIVE, note = "견적서(${date.monthValue}/${date.dayOfMonth})에서 만든 일감",
    )

    /** Worth keeping: something to quote. */
    val isBlank: Boolean get() = items.none { it.name.isNotBlank() || it.unitPrice > 0 }

    companion object {
        val UNITS = listOf("식", "개", "m²", "m", "평", "일", "시간", "회", "건", "세트")
    }
}

/**
 * [FLD-12] 공종별 평균 일당 참고표: 대한건설협회 건설업 임금실태조사 (시중노임단가), 2026년 상반기 적용
 * (2026-01-01 공표, 1일 8시간, 원). Built in to be refreshed once a year; a reference for setting 일당,
 * not what any site must pay.
 */
object TradeWages {
    data class Trade(val name: String, val daily: Long, val group: String)

    const val SOURCE = "대한건설협회 건설업 임금실태조사(시중노임단가) 2026년 상반기 적용"
    const val SOURCE_SHORT = "대한건설협회 2026년 상반기"
    const val NOTE = "1일 8시간 기준 평균 노임이에요. 현장·지역·숙련도에 따라 달라지는 참고값이에요."

    val ALL: List<Trade> = listOf(
        Trade("보통인부", 172_068, "공통"),
        Trade("특별인부", 226_122, "공통"),
        Trade("작업반장", 215_907, "공통"),
        Trade("형틀목공", 275_790, "골조"),
        Trade("건축목공", 277_642, "골조"),
        Trade("철근공", 268_187, "골조"),
        Trade("콘크리트공", 273_540, "골조"),
        Trade("비계공", 281_939, "골조"),
        Trade("용접공", 282_536, "골조"),
        Trade("조적공", 270_807, "마감"),
        Trade("미장공", 277_276, "마감"),
        Trade("타일공", 290_939, "마감"),
        Trade("도장공", 263_017, "마감"),
        Trade("방수공", 228_286, "마감"),
        Trade("내장공", 256_883, "마감"),
        Trade("도배공", 227_614, "마감"),
        Trade("유리공", 253_539, "마감"),
        Trade("줄눈공", 211_653, "마감"),
        Trade("석공", 268_908, "마감"),
        Trade("견출공", 250_903, "마감"),
        Trade("배관공", 247_897, "설비"),
        Trade("배관공(수도)", 262_580, "설비"),
        Trade("덕트공", 202_472, "설비"),
        Trade("보온공", 217_012, "설비"),
        Trade("내선전공", 273_676, "전기"),
        Trade("저압케이블전공", 304_156, "전기"),
        Trade("조경공", 235_204, "기타"),
        Trade("건설기계운전사", 283_297, "기타"),
        Trade("화물차운전사", 238_936, "기타"),
    )

    val GROUPS: List<String> get() = ALL.map { it.group }.distinct()

    /** Trades whose name holds [query], all of them for a blank one. */
    fun find(query: String): List<Trade> = query.trim().let { q -> if (q.isEmpty()) ALL else ALL.filter { q in it.name } }
}

/**
 * [FLD-13] 일용근로소득 원천징수 (소득세법 제47조·제59조·제86조): (일당 − 15만 원) × 6% less the 55% 세액공제,
 * i.e. 2.7% of what is over 15만 원, and 지방소득세 a tenth of that. Tax under 1,000원 for one payment is
 * not taken (소액부징수); paid day by day that is judged per day, paid together (월 1~2회 정산) on the sum,
 * so the same days can owe tax when paid at once. Apart from 3.3% 사업소득.
 */
object DailyWorkerTax {
    const val DEDUCTION = 150_000L
    const val SMALL = 1_000L

    /** 소득세 of one day before the 소액부징수 test, below a won dropped. */
    fun incomeTaxOfDay(daily: Long): Long = ((daily - DEDUCTION).coerceAtLeast(0) * 27 / 1000)

    data class Tax(val income: Long, val local: Long) {
        val total: Long get() = income + local
    }

    /** The tax on days [dailies] paid at once. */
    fun paidTogether(dailies: List<Long>): Tax {
        val income = dailies.sumOf(::incomeTaxOfDay).takeIf { it >= SMALL } ?: 0
        return Tax(income, income / 10)
    }

    /** The tax on days [dailies] each paid on the day. */
    fun paidDaily(dailies: List<Long>): Tax {
        val income = dailies.map(::incomeTaxOfDay).filter { it >= SMALL }.sum()
        return Tax(income, income / 10)
    }

    /** The 일당 under which a day paid by itself owes nothing (about 187,000원). */
    val TAX_FREE_UP_TO: Long get() = DEDUCTION + (SMALL * 1000 + 26) / 27 - 1

    data class Month(val days: Int, val wages: Long, val together: Tax, val daily: Tax)

    /** [FLD-13] A month of day-rate jobs (공수형), each day's 일당 being what that day's jobs came to. */
    fun month(month: YearMonth, jobs: List<Job>): Month {
        val byDay = jobs.filter { YearMonth.from(it.date) == month && it.units != null && !it.cancelled && it.amount > 0 }
            .groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.amount } }.values.toList()
        return Month(byDay.size, byDay.sum(), paidTogether(byDay), paidDaily(byDay))
    }
}
