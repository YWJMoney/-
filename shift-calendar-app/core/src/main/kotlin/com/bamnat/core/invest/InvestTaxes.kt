package com.bamnat.core.invest

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * [INV-03] The fee and tax a trade most likely paid, offered in the trade sheet and always editable:
 * the account's own fee rate (or the market's: 국내 0.015%, 해외 0.25%, 코인 0.05%) and, on selling
 * Korean shares, 증권거래세 0.20% (2026: 코스피 0.05% + 농어촌특별세 0.15%, 코스닥 0.20%). Korean ETFs
 * pay no 거래세.
 */
object InvestFees {
    data class Suggestion(val fee: Double, val tax: Double)

    fun suggest(market: Market, side: Side, quantity: Double, price: Double, account: InvestAccount?): Suggestion {
        val gross = quantity * price
        if (gross <= 0) return Suggestion(0.0, 0.0)
        // [INV-33] The account's own fee for this market, else its one fee, else the market's usual one.
        val rate = account?.feeFor(market) ?: market.defaultFeeRate
        val fee = round(gross * rate / 100, market)
        // 거래세 is cut to the won below, as brokers do.
        val tax = if (side == Side.SELL && market.sellTaxRate > 0) floor(gross * market.sellTaxRate / 100) else 0.0
        return Suggestion(fee, tax)
    }

    /** Won are whole; dollars keep cents. */
    fun round(v: Double, market: Market): Double = if (market.foreign) Math.round(v * 100) / 100.0 else floor(v)

    /** [INV-08] Units a plan's amount buys at [price]: whole shares, or coins to eight decimals. */
    fun unitsFor(amount: Long, price: Double, market: Market, fx: Double): Double? {
        if (amount <= 0 || price <= 0) return null
        val units = amount / (price * fx)
        val q = if (market == Market.CRYPTO) floor(units * 1e8) / 1e8 else floor(units)
        return q.takeIf { it > 0 }
    }
}

/** [INV-12] What tax this year's trading may come to, from the rules in force on 2026-10-20 (참고용). */
data class TaxEstimate(
    val year: Int,
    /** 해외 주식: gains less losses this year, matched oldest first. */
    val foreignGain: Long,
    val foreignTaxable: Long,
    val foreignTax: Long,
    /** How much more foreign gain stays inside the 250만 원 deduction. */
    val foreignDeductionLeft: Long,
    val cryptoGain: Long,
    /** False before 2027, when coins are not taxed yet. */
    val cryptoTaxed: Boolean,
    /** What coins would owe at the 2027 rules, for planning. */
    val cryptoTax: Long,
    val dividendsGross: Long,
    val dividendTax: Long,
    val interestGross: Long,
    /** 금융소득: dividends and interest before tax; over 2,000만 원 it is taxed with other income. */
    val financialIncome: Long,
    val tradeTax: Long,
    /** Net gain held in ISA accounts so far, against the 비과세 limit. */
    val isaGain: Long,
    val isaLimit: Long,
    val isaTaxOver: Long,
    val hasIsa: Boolean,
    /** Gains in 연금저축·IRP, taxed only when drawn. */
    val deferredGain: Long,
    val domesticGain: Long,
) {
    val financialNear: Boolean get() = financialIncome >= InvestTaxes.FINANCIAL_INCOME_LIMIT * 8 / 10
    val financialOver: Boolean get() = financialIncome > InvestTaxes.FINANCIAL_INCOME_LIMIT
}

object InvestTaxes {
    const val FOREIGN_DEDUCTION = 2_500_000L
    const val CAPITAL_GAINS_RATE = 0.22
    const val CRYPTO_DEDUCTION = 2_500_000L
    const val CRYPTO_FROM_YEAR = 2027
    const val ISA_LIMIT = 2_000_000L
    const val ISA_LIMIT_LOW_INCOME = 4_000_000L
    const val ISA_OVER_RATE = 0.099
    const val FINANCIAL_INCOME_LIMIT = 20_000_000L
    /** 배당소득세: 14% and 지방소득세 1.4%. */
    const val DIVIDEND_RATE = 0.154
    /** US dividends are taxed 15% there, and nothing more here below the 2,000만 원 line. */
    const val US_DIVIDEND_RATE = 0.15

    /** [INV-09] The tax usually withheld from a dividend of [gross] won from a holding on [market]. */
    fun dividendTax(gross: Long, market: Market): Long =
        floor(gross * (if (market.foreign) US_DIVIDEND_RATE else DIVIDEND_RATE)).toLong()

    fun estimate(book: InvestBook, result: PortfolioResult, year: Int): TaxEstimate {
        val inYear = { d: LocalDate -> d.year == year }
        val deferred = book.accounts.filter { it.type.taxDeferred }.map { it.id }.toSet()
        val isa = book.accounts.filter { it.type == AccountType.ISA }.map { it.id }.toSet()
        val taxable = { r: Realization -> r.accountId !in deferred && r.accountId !in isa }
        // 해외 주식 are matched oldest first, as the tax office does.
        val fifo = Portfolio.fifo(book.trades)
        val foreignGain = fifo.filter { it.market.foreign && inYear(it.date) && taxable(it) }.sumOf { it.pnlWon }.roundToLong()
        val foreignTaxable = (foreignGain - FOREIGN_DEDUCTION).coerceAtLeast(0)
        val cryptoGain = result.realizations.filter { it.market == Market.CRYPTO && inYear(it.date) && taxable(it) }.sumOf { it.pnlWon }.roundToLong()
        val cryptoTax = ((cryptoGain - CRYPTO_DEDUCTION).coerceAtLeast(0) * CAPITAL_GAINS_RATE).roundToLong()
        val cash = book.cash.filter { inYear(it.date) }
        val dividends = cash.filter { it.kind == CashKind.DIVIDEND }
        val interest = cash.filter { it.kind == CashKind.INTEREST }
        // ISA tax is on everything it made over its life, gains and losses together.
        val isaGain = result.realizations.filter { it.accountId in isa }.sumOf { it.pnlWon }.roundToLong() +
            book.cash.filter { it.accountId in isa && it.kind.isIncome }.sumOf { it.amount } -
            book.cash.filter { it.accountId in isa && it.kind == CashKind.FEE }.sumOf { it.amount }
        val limit = book.settings.isaLimit
        return TaxEstimate(
            year = year,
            foreignGain = foreignGain,
            foreignTaxable = foreignTaxable,
            foreignTax = (foreignTaxable * CAPITAL_GAINS_RATE).roundToLong(),
            foreignDeductionLeft = (FOREIGN_DEDUCTION - foreignGain).coerceIn(0, FOREIGN_DEDUCTION),
            cryptoGain = cryptoGain,
            cryptoTaxed = year >= CRYPTO_FROM_YEAR,
            cryptoTax = cryptoTax,
            dividendsGross = dividends.sumOf { it.amount },
            dividendTax = dividends.sumOf { it.tax } + interest.sumOf { it.tax },
            interestGross = interest.sumOf { it.amount },
            financialIncome = dividends.sumOf { it.amount } + interest.sumOf { it.amount },
            tradeTax = book.trades.filter { inYear(it.date) }.sumOf { it.tax * it.fxRate }.roundToLong(),
            isaGain = isaGain,
            isaLimit = limit,
            isaTaxOver = ((isaGain - limit).coerceAtLeast(0) * ISA_OVER_RATE).roundToLong(),
            hasIsa = isa.isNotEmpty(),
            deferredGain = result.realizations.filter { it.accountId in deferred && inYear(it.date) }.sumOf { it.pnlWon }.roundToLong(),
            domesticGain = result.realizations.filter { !it.market.foreign && it.market != Market.CRYPTO && inYear(it.date) && taxable(it) }
                .sumOf { it.pnlWon }.roundToLong(),
        )
    }
}
