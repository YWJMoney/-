package com.bamnat.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.WorkCsv
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [SHOP-07] Beside the ledger's CSV: the shop's daily sales with how they were paid, and the jobs,
 * over the same period, shared as their own files.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WorkCsvButtons(book: MoneyBook, range: Pair<LocalDate?, LocalDate?>, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (from, to) = range
    val inRange = { d: LocalDate -> (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to)) }
    val sales = remember(book.sales, range) { book.sales.filter { inRange(it.date) } }
    val jobs = remember(book.jobs, range) { book.jobs.filter { inRange(it.date) } }
    // [LEDG-27] 받을 돈 written down in the same period.
    val owed = remember(book.receivables, range) { book.receivables.filter { inRange(it.date) } }
    if (sales.isEmpty() && jobs.isEmpty() && owed.isEmpty()) return
    fun share(csv: String, name: String) = scope.launch {
        runCatching { LedgerExport.share(context, csv, name) }.onFailure { onMessage("공유하지 못했어요") }
        onDismiss()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("매출·일감·받을 돈도 따로 내보낼 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sales.isNotEmpty()) {
                OutlinedButton(onClick = { share(WorkCsv.sales(sales), WorkCsv.fileName("sales", from, to)) }) { Text("매출 ${sales.size}일") }
            }
            if (jobs.isNotEmpty()) {
                OutlinedButton(onClick = { share(WorkCsv.jobs(jobs), WorkCsv.fileName("jobs", from, to)) }) { Text("일감 ${jobs.size}건") }
            }
            if (owed.isNotEmpty()) {
                OutlinedButton(onClick = { share(WorkCsv.receivables(owed, Today.date()), WorkCsv.fileName("receivables", from, to)) }) { Text("받을 돈 ${owed.size}건") }
            }
        }
    }
}
