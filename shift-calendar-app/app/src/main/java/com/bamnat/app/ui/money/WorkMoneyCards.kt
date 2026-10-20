package com.bamnat.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.appContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.DepositMatch
import com.bamnat.core.money.FieldRules
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.work.CardSettlement
import com.bamnat.core.work.ShopMoney
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * [FLD-06][SHOP-02] Cards in 돈 > 요약 for work calendars: a deposit that looks like a job's money
 * asks whether it is (field work), and card deposits still to come wait to be ticked (a shop).
 */
@Composable
fun WorkMoneyCards(calendarId: String, book: MoneyBook, style: WorkStyle?, today: LocalDate) {
    val c = LocalContext.current.appContainer
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val cal = calendars.firstOrNull { it.id == calendarId }
    if (style == WorkStyle.FIELD) DepositMatchCard(c, calendarId, book, cal?.field ?: FieldRules(), today)
    if (style == WorkStyle.BUSINESS) CardDepositsCard(c, calendarId, book, cal?.shopMoney ?: ShopMoney(), today)
}

/** [FLD-06] "이 입금, ○○ 일감 잔금인가요?" — 맞아요 ticks it paid, 아니에요 stops asking. */
@Composable
private fun DepositMatchCard(c: AppContainer, calendarId: String, book: MoneyBook, rules: FieldRules, today: LocalDate) {
    val matches = remember(book, rules.notMatched, today) { DepositMatch.pending(book.entries, book.jobs, book.receivables, rules.notMatched, today) }
    val m = matches.firstOrNull() ?: return
    val what = when (val t = m.target) {
        is DepositMatch.Target.ForJob -> "‘${t.name}’ 일감 " + when (t.part) {
            DepositMatch.Part.DEPOSIT -> "선금"
            DepositMatch.Part.BALANCE -> "잔금"
            DepositMatch.Part.ALL -> "돈"
        }
        is DepositMatch.Target.ForReceivable -> "‘${t.name}’ 받을 돈"
    }
    SectionCard(title = "입금 확인") {
        Text("${Fmt.shortDate(m.entry.date)} ${m.entry.title} ${Fmt.won(m.entry.amount)}", style = MaterialTheme.typography.bodyLarge)
        Text("이 입금, ${what}인가요?", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp))
        Text("맞으면 받은 돈으로 바꿔요. 같은 돈이 두 번 세지 않게 이 입금은 수입에서 빼고, 계좌 잔액은 그대로 둬요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = {
                c.calendars.updateField(calendarId) { it.copy(notMatched = (it.notMatched + m.entry.id).takeLast(FieldRules.KEEP_NOT_MATCHED)) }
            }) { Text("아니에요") }
            FilledTonalButton(onClick = {
                val money = c.calendars.money(calendarId)
                c.appScope.launch {
                    // The line stays as money into its account when it had one, so the balance is kept.
                    val kept = DepositMatch.kept(m.entry, book.accounts, m.target.name)
                    if (kept != null) money.save(kept) else money.delete(m.entry)
                    when (val t = m.target) {
                        is DepositMatch.Target.ForJob -> money.save(DepositMatch.paid(t.job, t.part))
                        is DepositMatch.Target.ForReceivable -> money.receive(t.r, m.entry.amount, m.entry.date)
                    }
                }
            }) { Text("맞아요") }
        }
        if (matches.size > 1) {
            Text("확인할 입금이 ${matches.size - 1}건 더 있어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** [SHOP-02] 카드 입금 예정: each day's card sales less the fee, due a few business days later, with 입금 확인. */
@Composable
private fun CardDepositsCard(c: AppContainer, calendarId: String, book: MoneyBook, rules: ShopMoney, today: LocalDate) {
    val waiting = remember(book.sales, rules, today) {
        CardSettlement.waiting(CardSettlement.expected(book.sales, rules, KoreanHolidays::isHoliday), today.plusDays(7)).sortedBy { it.payDate }
    }
    if (waiting.isEmpty()) return
    val cal = LocalCalendarColors.current
    SectionCard(title = "카드 입금 예정") {
        waiting.take(6).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val late = e.payDate.isBefore(today)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Fmt.shortDate(e.payDate)} · ${Fmt.won(e.amount)}", style = MaterialTheme.typography.bodyLarge,
                        color = if (late) cal.warning else MaterialTheme.colorScheme.onSurface)
                    Text("${Fmt.shortDate(e.saleDate)} 카드 매출 ${Fmt.won(e.card)}" + if (late) " · 예정일 지남" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    val money = c.calendars.money(calendarId)
                    val day = book.sales.firstOrNull { it.date == e.saleDate } ?: return@TextButton
                    c.appScope.launch { money.save(day.copy(cardSettled = true)) }
                }) { Text("입금 확인") }
            }
        }
        val total = waiting.sumOf { it.amount }
        Text("들어올 카드 매출 ${Fmt.won(total)} · 수수료 ${rules.feeLabel}, ${rules.settleDays}영업일 뒤로 셌어요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}
