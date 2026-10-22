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
 * [FLD-12][PAYR-05] 공종별 평균 일당 참고표: 대한건설협회 건설업 임금실태조사 (시중노임단가, 1일 8시간, 원).
 * The table is data (`remote/v1/wages.json`, 2026년 하반기 적용 2026-09-01, 대한건설협회 거래가격 원문과 대조
 * 2026-10-22, research/1.22-원문대조.md 4장); the association publishes on 1월 1일 and 9월 1일 and a newer signed
 * file replaces it without an app update. A reference for setting 일당, not what any site must pay.
 */
object TradeWages {
    data class Trade(val name: String, val daily: Long, val group: String)

    /** One published table: [label] like "2026년 하반기", in force [from]. */
    data class Table(val from: java.time.LocalDate, val label: String, val source: String, val trades: List<Trade>)

    const val NOTE = "1일 8시간 기준 평균 노임이에요. 현장·지역·숙련도에 따라 달라지는 참고값이에요."

    @Volatile private var table: Table? = null

    /** The table in use: the built-in file, or a newer signed one ([use]). Empty only if packaging lost the file. */
    val current: Table get() = table ?: bundled

    private val bundled: Table by lazy {
        com.bamnat.core.remote.RemoteFiles.bundled(com.bamnat.core.remote.RemoteFiles.WAGES)?.let { runCatching { read(it) }.getOrNull() }
            ?: Table(java.time.LocalDate.MIN, "", "", emptyList())
    }
    private val bundledVersion: Long by lazy {
        com.bamnat.core.remote.RemoteFiles.bundled(com.bamnat.core.remote.RemoteFiles.WAGES)?.version ?: 0L
    }

    val SOURCE: String get() = current.source
    val SOURCE_SHORT: String get() = "대한건설협회 ${current.label}"
    val ALL: List<Trade> get() = current.trades

    /** [REMOTE-02] A downloaded wages file newer than the built-in one; null goes back to it. */
    fun use(file: com.bamnat.core.remote.RemoteFile?) {
        table = file?.takeIf { it.version > bundledVersion }?.let(::read)
    }

    fun read(f: com.bamnat.core.remote.RemoteFile): Table {
        if (f.kind != com.bamnat.core.remote.RemoteFiles.WAGES) throw com.bamnat.core.remote.RemoteFileException("kind ${f.kind}")
        val c = f.data.obj("construction")
        val from = try { java.time.LocalDate.parse(c.str("from")) } catch (e: java.time.format.DateTimeParseException) {
            throw com.bamnat.core.remote.RemoteFileException("from")
        }
        val trades = c.objs("trades").map { Trade(it.str("name"), it.long("daily"), it.strOr("group", "기타")) }
        if (trades.isEmpty() || trades.any { it.daily !in 50_000..2_000_000 }) throw com.bamnat.core.remote.RemoteFileException("trades")
        return Table(from, c.str("label"), c.str("source"), trades)
    }

    /** [PAYR-06] The published 일당 for a trade named like [name] (광장 임금표), null when it is not in the table. */
    fun of(name: String): Trade? = name.replace(" ", "").let { n -> ALL.firstOrNull { it.name == n } }

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
