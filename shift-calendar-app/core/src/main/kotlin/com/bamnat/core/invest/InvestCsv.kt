package com.bamnat.core.invest

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * [INV-10] The investor's records as CSV files for a spreadsheet or a 세무사, written like the
 * ledger's: UTF-8 with a BOM so Excel reads Korean, and text that could pass for a formula quoted.
 * Trades carry what each sale made (moving average), so the file adds up on its own.
 */
object InvestCsv {
    val TRADES_HEADER = listOf(
        "날짜", "시각", "계좌", "구분", "종목코드", "종목명", "시장", "수량", "단가", "통화", "환율", "거래금액(원)", "수수료", "세금",
        "실현손익(원)", "이유", "감정", "태그", "원칙", "메모",
    )
    val CASH_HEADER = listOf("날짜", "계좌", "구분", "금액(원)", "세금(원)", "실수령(원)", "종목", "메모")
    val JOURNAL_HEADER = listOf("날짜", "오늘 평가", "지킨 원칙", "시장 메모", "복기")
    val HOLDINGS_HEADER = listOf("종목코드", "종목명", "시장", "보유수량", "평균단가", "매입금액(원)", "현재가", "평가금액(원)", "평가손익(원)", "실현손익(원)", "배당(원)")

    fun trades(book: InvestBook, result: PortfolioResult, from: LocalDate? = null, to: LocalDate? = null): String {
        val pnl = result.realizations.associate { it.tradeId to it.pnlWon }
        val sb = StringBuilder("﻿")
        line(sb, TRADES_HEADER)
        for (t in book.trades.filter { inRange(it.date, from, to) }.sortedWith(Portfolio.ORDER)) {
            line(sb, listOf(
                t.date.toString(), t.minute?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty(), text(book.account(t.accountId)?.name.orEmpty()),
                t.side.label, text(t.symbol), text(t.name), t.market.label, Portfolio.quantityText(t.quantity), number(t.price), t.currency,
                if (t.currency == "KRW") "" else number(t.fxRate), t.grossWon.roundToLong().toString(), number(t.fee), number(t.tax),
                pnl[t.id]?.roundToLong()?.toString().orEmpty(), text(t.reason), t.emotions.joinToString(" ") { it.label },
                t.tags.joinToString(" ") { "#$it" }, when (t.followedRules) { true -> "지킴"; false -> "어김"; null -> "" }, text(t.memo),
            ))
        }
        return sb.toString()
    }

    fun cash(book: InvestBook, from: LocalDate? = null, to: LocalDate? = null): String {
        val sb = StringBuilder("﻿")
        line(sb, CASH_HEADER)
        for (c in book.cash.filter { inRange(it.date, from, to) }.sortedWith(compareBy({ it.date }, { it.id }))) {
            line(sb, listOf(c.date.toString(), text(book.account(c.accountId)?.name.orEmpty()), c.kind.label, c.amount.toString(),
                c.tax.toString(), c.cashWon.toString(), text(c.symbol), text(c.memo)))
        }
        return sb.toString()
    }

    fun journal(book: InvestBook, from: LocalDate? = null, to: LocalDate? = null): String {
        val sb = StringBuilder("﻿")
        line(sb, JOURNAL_HEADER)
        val rules = book.settings.principles
        for (j in book.journal.filter { inRange(it.date, from, to) && !it.isEmpty }.sortedBy { it.date }) {
            // [INV-07] Kept rules in the list's order, then ones since taken off the list.
            val kept = rules.filter { it in j.checked } + j.checked.filter { it !in rules }
            line(sb, listOf(j.date.toString(), j.grade?.label.orEmpty(), kept.distinct().joinToString(" / ").let(::text),
                text(j.market), text(j.review)))
        }
        return sb.toString()
    }

    fun holdings(result: PortfolioResult): String {
        val sb = StringBuilder("﻿")
        line(sb, HOLDINGS_HEADER)
        for (h in result.byKey) {
            line(sb, listOf(text(h.symbol), text(h.name), h.market.label, Portfolio.quantityText(h.quantity), number(h.averagePrice),
                h.costWon.roundToLong().toString(), h.quote?.price?.let(::number).orEmpty(), h.valueWon?.roundToLong()?.toString().orEmpty(),
                h.unrealizedWon?.roundToLong()?.toString().orEmpty(), h.realizedWon.roundToLong().toString(), h.dividendsWon.toString()))
        }
        return sb.toString()
    }

    val TAX_HEADER = listOf("매도일", "계좌", "종목코드", "종목명", "시장", "수량", "양도가액(원)", "취득가액·경비(원)", "손익(원)", "매도 환율", "처음 산 날")

    /**
     * [INV-37] 해외 주식 양도소득세 신고(다음 해 5월)에 쓰는 표: [year]에 판 해외 주식, 먼저 산 것부터 판 것으로
     * (선입선출, [Portfolio.fifo]) — ISA·연금 계좌는 빼고 — 한 줄씩, 끝에 합계·기본공제·예상 세금
     * ([InvestTaxes.estimate]과 같은 수). 코인은 시행 전이면 참고로만 따로 적어요.
     */
    fun taxReport(book: InvestBook, result: PortfolioResult, year: Int): String {
        val skip = book.accounts.filter { it.type.taxDeferred || it.type == AccountType.ISA }.map { it.id }.toSet()
        val trades = book.trades.associateBy { it.id }
        val sales = Portfolio.fifo(book.trades).filter { it.date.year == year && it.market.foreign && it.accountId !in skip }.sortedBy { it.date }
        val sb = StringBuilder("\uFEFF")
        line(sb, listOf("${year}년 해외 주식 양도소득 (선입선출, 참고용)"))
        line(sb, TAX_HEADER)
        for (r in sales) {
            val t = trades[r.tradeId]
            line(sb, listOf(r.date.toString(), text(book.account(r.accountId)?.name.orEmpty()), text(t?.symbol.orEmpty()), text(r.name), r.market.label,
                Portfolio.quantityText(r.quantity), r.proceedsWon.roundToLong().toString(), r.costWon.roundToLong().toString(),
                r.pnlWon.roundToLong().toString(), t?.fxRate?.let(::number).orEmpty(), r.openedOn?.toString().orEmpty()))
        }
        val est = InvestTaxes.estimate(book, result, year)
        line(sb, emptyList())
        line(sb, listOf("손익 합계", "", "", "", "", "", "", "", est.foreignGain.toString()))
        line(sb, listOf("기본공제", "", "", "", "", "", "", "", InvestTaxes.FOREIGN_DEDUCTION.toString()))
        line(sb, listOf("과세표준", "", "", "", "", "", "", "", est.foreignTaxable.toString()))
        line(sb, listOf("예상 세금 (지방소득세 포함 ${Math.round(InvestTaxes.CAPITAL_GAINS_RATE * 100)}%)", "", "", "", "", "", "", "", est.foreignTax.toString()))
        if (est.cryptoGain != 0L) {
            line(sb, listOf(if (est.cryptoTaxed) "가상자산 손익" else "가상자산 손익 (${InvestTaxes.CRYPTO_FROM_YEAR}년부터 과세, 참고)", "", "", "", "", "", "", "",
                est.cryptoGain.toString()))
        }
        line(sb, emptyList())
        line(sb, listOf("환율은 거래에 적은 값이에요. 신고는 양도일·취득일의 기준환율로 해요(소득세법 시행령 제178조의5, 확인 필요). " +
            "증권사가 주는 양도소득 자료와 맞춰 보고 신고해 주세요."))
        return sb.toString()
    }

    fun fileName(kind: String, from: LocalDate?, to: LocalDate?): String =
        "bamnat-invest-$kind" + (if (from != null && to != null) "-$from-$to" else "") + ".csv"

    private fun inRange(d: LocalDate, from: LocalDate?, to: LocalDate?) = (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))

    /** Up to eight decimals without trailing zeros: 71500, 182.35, 0.00123. */
    internal fun number(v: Double): String = Portfolio.quantityText(v)

    private fun text(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    private fun line(sb: StringBuilder, cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) sb.append('"').append(c.replace("\"", "\"\"")).append('"') else sb.append(c)
        }
        sb.append("\r\n")
    }
}
