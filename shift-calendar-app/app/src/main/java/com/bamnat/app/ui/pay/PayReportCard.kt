package com.bamnat.app.ui.pay

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bamnat.app.appContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.report.ReportShare
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.money.ProPill
import com.bamnat.app.ui.pro.openPro
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.report.PayReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [PROV-01] 급여 리포트 (프로): the month on screen, or its year, as a PDF to keep or show the company, and
 * as a CSV for a spreadsheet. Made on the phone and handed to the share sheet; without 프로 the buttons
 * open the 프로 screen.
 */
@Composable
internal fun PayReportCard(vm: PayViewModel, profile: PayProfile, snapshot: ScheduleSnapshot, shown: MonthPay, closed: Boolean, gate: Gate,
    onEditSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val open = gate.allows(Feature.PAY_REPORT)
    val month = shown.month
    // [EASY-07] The guessed values a report would carry, asked about once before it leaves; none, it goes straight out.
    var checking by remember { mutableStateOf<Pair<List<com.bamnat.core.profile.Fact<*>>, () -> Unit>?>(null) }
    val profiles = remember { context.appContainer.profiles }
    fun run(what: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                vm.reportMade(what)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(context, "리포트를 만들지 못했어요", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }
    fun make(what: String, block: suspend () -> Unit) {
        if (!open) { openPro(); return }
        val feature = if (what == "counsel_zip") "pay.counsel" else "pay.report"
        scope.launch {
            val guessed = runCatching { com.bamnat.core.profile.ExportCheck.estimated(profiles.facts(vm.calendarId), feature) }.getOrDefault(emptyList())
            if (guessed.isEmpty()) run(what, block) else checking = guessed to { run(what, block) }
        }
    }
    checking?.let { (facts, go) ->
        com.bamnat.app.ui.guide.ExportCheckSheet(
            facts = facts,
            onConfirm = { f ->
                scope.launch { profiles.mark(vm.calendarId, f.key, com.bamnat.core.profile.Source.TYPED, why = "보내기 전에 확인") }
                val left = facts - f
                checking = if (left.isEmpty()) null.also { go() } else left to go
            },
            onFix = { checking = null; onEditSetup() },
            onGo = { checking = null; go() },
            onDismiss = { checking = null },
        )
    }
    val who = remember(profile, month) { vm.reportWho(profile, month) }
    suspend fun year() = vm.yearPays(profile, snapshot, month.year)
    SectionCard(title = "급여 리포트", trailing = { if (!open) ProPill() else if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) {
        Text("계산 풀이와 날짜별 근무를 PDF로 남기거나 CSV로 엑셀에서 열어요. 회사에 물어볼 때 근거로 보여 줄 수 있어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            ReportButton("${month.monthValue}월 PDF", pdf = true, enabled = !busy) {
                make("month_pdf") { ReportShare.pdf(context, PayReport.month(shown, who, Today.date(), closed), PayReport.fileName(month, "pdf")) }
            }
            ReportButton("${month.monthValue}월 CSV", pdf = false, enabled = !busy) {
                make("month_csv") { ReportShare.csv(context, PayReport.monthCsv(shown), PayReport.fileName(month, "csv"), "${month.year}년 ${month.monthValue}월 급여") }
            }
            ReportButton("${month.year}년 PDF", pdf = true, enabled = !busy) {
                make("year_pdf") {
                    val pays = year()
                    ReportShare.pdf(context, PayReport.year(month.year, pays, who, Today.date(), vm.closed.value.keys), PayReport.fileName(month.year, "pdf"))
                }
            }
            ReportButton("${month.year}년 CSV", pdf = false, enabled = !busy) {
                make("year_csv") { ReportShare.csv(context, PayReport.yearCsv(year(), vm.closed.value.keys), PayReport.fileName(month.year, "csv"), "${month.year}년 급여") }
            }
        }
        // [PAYX-45] Everything a 노무사 or the 1350 상담 asks for, as files a person reads, in one zip.
        OutlinedButton(onClick = {
            make("counsel_zip") {
                val today = Today.date()
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val pays = (0L..2L).map { month.minusMonths(it) }.reversed().mapNotNull { m -> if (m == month) shown else vm.monthPay(profile, snapshot, m) }
                    val files = linkedMapOf(
                        PayReport.fileName(month, "pdf") to ReportShare.pdfBytes(PayReport.month(shown, who, today, closed)),
                        PayReport.fileName(month, "csv") to PayReport.monthCsv(shown).toByteArray(Charsets.UTF_8),
                        "주별_근무시간_최근3개월.csv" to com.bamnat.core.report.CounselBundle.weeksCsv(pays).toByteArray(Charsets.UTF_8),
                    )
                    vm.leave.value?.let { files[com.bamnat.core.leave.LeaveExport.fileName(today)] = com.bamnat.core.leave.LeaveExport.csv(it).toByteArray(Charsets.UTF_8) }
                    files[com.bamnat.core.report.CounselBundle.README] =
                        com.bamnat.core.report.CounselBundle.readme(today, files.keys.toList(), shown.ratesVersion).toByteArray(Charsets.UTF_8)
                    com.bamnat.core.report.CounselBundle.zip(files)
                }
                ReportShare.zip(context, bytes, "bamnat-상담자료-$month.zip", "${month.year}년 ${month.monthValue}월 상담 자료")
            }
        }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("상담용 묶음 (급여·근무시간·연차)") }
        Text("노무사나 고용노동부 상담(1350)에 보여 줄 파일을 한 번에 만들어요. 보내는 곳은 직접 골라요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportButton(label: String, pdf: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(if (pdf) Icons.Outlined.Description else Icons.Outlined.TableChart, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
