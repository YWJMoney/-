package com.bamnat.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.pro.ProLock
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.report.MonthCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * [PROV-02] 지난달·작년 비교 (프로): this month's hours, night work, work on days off, extra work and pay beside
 * last month's and the same month a year ago. Without 프로 the card shows the user's own figures blurred.
 */
@Composable
internal fun CompareCard(vm: StatsViewModel, calc: WorkHoursCalculator, snap: ScheduleSnapshot, month: YearMonth, profile: PayProfile?, gate: Gate) {
    val sides by produceState<List<MonthCompare.Side>?>(null, calc, snap, month, profile) {
        value = runCatching {
            withContext(Dispatchers.Default) {
                listOf(month, month.minusMonths(1), month.minusYears(1)).map { m -> MonthCompare.side(calc.month(m), profile?.let { vm.monthPay(it, snap, m) }) }
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }
    val s = sides ?: return
    val (now, last, lastYear) = s
    ProLock(Feature.WORK_COMPARE, gate) {
        SectionCard(title = "${month.monthValue}월 비교") {
            CompareTable(now, last, "지난달 ${last.month.monthValue}월")
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            if (lastYear.empty) {
                Text("작년 ${lastYear.month.year % 100}년 ${lastYear.month.monthValue}월은 달력에 근무가 없어 비교할 게 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CompareTable(now, lastYear, "작년 ${lastYear.month.year % 100}년 ${lastYear.month.monthValue}월")
            }
            Text(if (profile == null) "급여를 설정하면 야간과 급여도 비교해요." else "급여는 세금 떼기 전 예상이에요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CompareTable(now: MonthCompare.Side, before: MonthCompare.Side, beforeLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1.3f))
            Text("${now.month.monthValue}월", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            Text(beforeLabel, Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("차이", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        MonthCompare.rows(now, before).forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
                Text(r.now, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), textAlign = TextAlign.End)
                Text(r.before, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.change, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), textAlign = TextAlign.End,
                    fontWeight = FontWeight.SemiBold,
                    color = when (r.more) { true -> Color(0xFFE8590C); false -> Color(0xFF1C7ED6); null -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
        }
    }
}

/** [PROV-02] 올해 근무·급여 (프로): every month of the year with its hours and pay, and the year so far. */
@Composable
internal fun YearWorkCard(vm: StatsViewModel, calc: WorkHoursCalculator, snap: ScheduleSnapshot, month: YearMonth, profile: PayProfile?, gate: Gate) {
    val year by produceState<MonthCompare.Year?>(null, calc, snap, month.year, profile) {
        value = runCatching {
            withContext(Dispatchers.Default) {
                MonthCompare.Year(month.year, (1..12).map { m ->
                    val ym = YearMonth.of(month.year, m)
                    MonthCompare.side(calc.month(ym), profile?.let { vm.monthPay(it, snap, ym) })
                })
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }
    val y = year ?: return
    val now = YearMonth.now()
    ProLock(Feature.WORK_COMPARE, gate) {
        SectionCard(title = "${y.year}년 근무·급여") {
            y.months.forEach { s ->
                val ahead = s.month.isAfter(now)
                val color = if (ahead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.month.monthValue}월" + if (ahead) " 예상" else "", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = color)
                    Text(HoursFormat.korean(s.worked), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = color)
                    Text(s.night?.let { "야간 " + HoursFormat.korean(it) } ?: "${s.workDays}일", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.pay?.let { Text(wonShort(it), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = color, textAlign = TextAlign.End) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("한 해", Modifier.width(64.dp), style = MaterialTheme.typography.titleSmall)
                Text(HoursFormat.korean(y.worked), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                Text(y.night?.let { "야간 " + HoursFormat.korean(it) } ?: "${y.workDays}일", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                y.pay?.let { Text(wonShort(it), style = MaterialTheme.typography.titleSmall.merge(NumberStyle)) }
            }
            Text("근무표대로 앞으로의 달도 셌어요. 급여 리포트(PDF·CSV)는 근무·급여 화면에서 만들어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** "312.4만" for the year card's narrow column. */
private fun wonShort(v: Long): String = if (v >= 10_000) "%.1f만".format(v / 10_000.0).replace(".0만", "만") else "%,d원".format(v)
