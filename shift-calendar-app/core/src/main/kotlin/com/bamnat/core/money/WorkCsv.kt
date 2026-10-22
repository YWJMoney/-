package com.bamnat.core.money

import java.time.LocalDate

/**
 * [SHOP-07] A shop's daily sales (by how they were paid) and a field worker's jobs as CSV files, for
 * a 세무사 or a spreadsheet on a PC, written like [LedgerCsv]: UTF-8 with a BOM so Excel reads Korean.
 */
object WorkCsv {
    val SALES_HEADER = listOf("날짜", "매출", "카드", "현금", "계좌이체", "배달앱", "경비", "순수익", "센 현금", "카드 입금 확인", "메모")
    val JOBS_HEADER = listOf("시작일", "끝난 날", "일", "거래처", "장소", "상태", "공수", "금액", "3.3%", "받을 금액", "선금", "선금 받음", "잔금 받음", "받은 돈", "남은 돈", "경비", "입금 약속일", "메모")

    fun sales(days: List<DailySales>): String {
        val sb = StringBuilder("﻿")
        line(sb, SALES_HEADER)
        for (s in days.sortedBy { it.date }) {
            line(sb, listOf(
                s.date.toString(), s.sales.toString(), s.card.toString(), s.cash.toString(), s.transfer.toString(), s.delivery.toString(),
                s.costs.toString(), s.net.toString(), s.cashCounted?.toString().orEmpty(), if (s.card > 0) yes(s.cardSettled) else "", text(s.note),
            ))
        }
        return sb.toString()
    }

    fun jobs(jobs: List<Job>): String {
        val sb = StringBuilder("﻿")
        line(sb, JOBS_HEADER)
        for (j in jobs.sortedWith(compareBy({ it.date }, { it.id }))) {
            line(sb, listOf(
                j.date.toString(), j.lastDate.toString(), text(j.title), text(j.client), text(j.place), j.status.label,
                j.units?.let(DayRate::trim).orEmpty(), j.amount.toString(), j.withheld.toString(), j.payable.toString(),
                j.deposit.toString(), if (j.deposit > 0) yes(j.depositPaid) else "", yes(j.balancePaid), j.received.toString(), j.unpaid.toString(),
                j.costs.toString(), j.due?.toString().orEmpty(), text(j.note),
            ))
        }
        return sb.toString()
    }

    /** [LEDG-27] 받을 돈 written down (외상·밀린 일당·빌려준 돈…), each with what came back and what is left on [today]. */
    val RECEIVABLES_HEADER = listOf("적은 날", "누구", "무엇", "종류", "금액", "받은 돈", "남은 돈", "약속일", "상태", "마지막 받은 날", "받으면 수입", "메모")

    fun receivables(list: List<Receivable>, today: LocalDate): String {
        val sb = StringBuilder("\uFEFF")
        line(sb, RECEIVABLES_HEADER)
        for (r in list.sortedWith(compareBy({ it.date }, { it.id }))) {
            line(sb, listOf(
                r.date.toString(), text(r.who), text(r.title), r.kind.label, r.amount.toString(), r.received.toString(), r.left.toString(),
                r.due?.toString().orEmpty(), r.status(today).label, r.lastPaid?.toString().orEmpty(), yes(r.countAsIncome), text(r.note),
            ))
        }
        return sb.toString()
    }

    fun fileName(kind: String, from: LocalDate?, to: LocalDate?): String =
        "bamnat-$kind" + (if (from != null && to != null) "-$from-$to" else "") + ".csv"

    private fun yes(b: Boolean) = if (b) "예" else "아니요"

    /** Text a spreadsheet must not treat as a formula. */
    private fun text(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    private fun line(sb: StringBuilder, cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) sb.append('"').append(c.replace("\"", "\"\"")).append('"') else sb.append(c)
        }
        sb.append("\r\n")
    }
}
