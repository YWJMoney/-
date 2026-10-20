package com.bamnat.app.ui.money

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.work.CardSettlement
import com.bamnat.core.work.ShopMoney

/**
 * [SHOP-01][SHOP-02][SHOP-06] Under a shop day's sales: how they were paid, when the card money
 * comes (or that it came), and the day's 외상 with who owes it.
 */
@Composable
fun SalesDayExtras(sales: DailySales) {
    val c = LocalContext.current.appContainer
    val calendar by c.calendars.activeCalendar.collectAsStateWithLifecycle(c.calendars.current())
    val book = rememberLedger(null)
    val rules = calendar.shopMoney ?: ShopMoney()
    val credits = remember(book.receivables, sales.date) { book.receivables.filter { it.date == sales.date && it.kind == ReceivableKind.SALE } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 6.dp)) {
        if (sales.byMethod > 0) {
            Text(
                listOf("카드" to sales.card, "현금" to sales.cash, "계좌" to sales.transfer, "배달앱" to sales.delivery)
                    .filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${wonBrief(it.second)}" },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        if (sales.card > 0) {
            val pay = remember(sales, rules) { CardSettlement.payDate(sales.date, rules.settleDays, KoreanHolidays::isHoliday) }
            Text(
                if (sales.cardSettled) "카드 매출 입금 확인했어요"
                else "카드 입금 예정 ${Fmt.shortDate(pay)} · ${Fmt.won(CardSettlement.afterFee(sales.card, rules.cardFeeBp))} (수수료 ${rules.feeLabel})",
                style = MaterialTheme.typography.bodySmall, color = if (sales.cardSettled) LocalCalendarColors.current.income else muted,
            )
        }
        credits.forEach { r ->
            Text("외상 ${r.who.ifBlank { "이름 없음" }} ${Fmt.won(r.amount)}" + if (r.left == 0L) " · 받음" else "",
                style = MaterialTheme.typography.bodySmall, color = if (r.left > 0) LocalCalendarColors.current.warning else muted)
        }
    }
}

/** [FLD-03] A job's step when it is not plain 확정: 가계약, 완료 or 취소. */
@Composable
fun JobStatusTag(job: Job) {
    val (text, color) = when {
        job.cancelled -> "취소" to MaterialTheme.colorScheme.onSurfaceVariant
        job.status == JobStatus.TENTATIVE -> "가계약" to MaterialTheme.colorScheme.tertiary
        job.status == JobStatus.DONE -> "완료" to MaterialTheme.colorScheme.primary
        else -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color,
        textDecoration = if (job.cancelled) TextDecoration.LineThrough else null)
}
