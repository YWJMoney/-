package com.bamnat.app.ui.stats

import com.bamnat.app.ui.guide.FeatureCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.WorkTypePrefs
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.MonthSummary
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.pay.PayGaps
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.DayWork
import com.bamnat.core.work.ShiftMonth
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/*
 * Work-type cards of 통계: the first card a shift worker [SHFT-02] or a day worker [DAYW-02] sees, and the
 * cards after the pay: swaps and covers [SHFT-03], pay against what came in [SHFT-04], the 시간외 상한
 * [DAYW-05] and 징검다리 연휴 [DAYW-04]. Each shows only on a calendar of its own kind of work.
 */

/** A main calendar from before work styles were asked is a shift calendar, as its words are. */
private fun WorkStyle?.isShift() = this == null || this == WorkStyle.SHIFT

/** [SHFT-02][DAYW-02] The month's first card for a shift or day calendar; nothing for other kinds. */
@Composable
internal fun WorkTypeFirstCard(
    style: WorkStyle?,
    calendarId: String,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    summary: MonthSummary,
    payTotal: Long?,
    profile: PayProfile?,
    columns: Int,
) {
    when {
        // [GUIDE-05] Each card can be turned off for its calendar.
        style.isShift() -> FeatureCard("shift.firstCard", calendarId) { ShiftFirstCard(snapshot, month, payTotal) }
        style == WorkStyle.DAY -> FeatureCard("day.firstCard", calendarId) { DayFirstCard(calendarId, snapshot, month, summary, payTotal, profile, columns) }
    }
}

/** [SHFT-03][SHFT-04][DAYW-04][DAYW-05] The work type's cards after the month's pay. */
@Composable
internal fun WorkTypeMoreCards(
    style: WorkStyle?,
    calendarId: String,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    summary: MonthSummary,
    payTotal: Long?,
    profile: PayProfile?,
    book: MoneyBook?,
) {
    // [GUIDE-05] Each card can be turned off for its calendar.
    if (style?.usesPayEngine != false) FeatureCard("pay.gap", calendarId) { PayGapCard(calendarId, month, payTotal, profile, book, style) }
    if (style.isShift()) FeatureCard("shift.swapCard", calendarId) { SwapCard(snapshot, month) }
    // [SHFT-07] The year's night share, runs of nights and short rests.
    if (style.isShift()) FeatureCard("shift.health", calendarId) { HealthYearCard(snapshot) }
    if (style == WorkStyle.DAY) FeatureCard("day.overtimeCap", calendarId) { OvertimeCapCard(calendarId, summary) }
    // [LEAVE-04] 징검다리 for every kind of work that takes leave.
    if (style == null || style == WorkStyle.DAY || style == WorkStyle.SHIFT || style == WorkStyle.HOURLY) {
        FeatureCard("day.bridges", calendarId) { BridgeCard(snapshot, style) }
    }
}

/** [SHFT-02] Each code's count this month, the night shifts and the expected pay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShiftFirstCard(snapshot: ScheduleSnapshot, month: YearMonth, payTotal: Long?) {
    val tally = remember(snapshot, month) { ShiftMonth.of(month, snapshot.shiftTypes, snapshot.resolver::resolve) }
    if (tally.counts.isEmpty()) return
    SectionCard(title = "${month.monthValue}월 근무") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tally.counts.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShiftBadge(c.type, size = 22.dp, outlined = c.type.isOff)
                    Spacer(Modifier.width(6.dp))
                    Text("${c.days}번", style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("야간 근무", "${tally.nights}번", Modifier.weight(1f), caption = "22~06시에 걸친 근무")
            StatTile("예상 급여", payTotal?.let { Fmt.won(it) } ?: "-", Modifier.weight(1f), caption = "세전")
        }
    }
}

/** [SHFT-03] The month's 교환 and 대근 with the people's names. */
@Composable
private fun SwapCard(snapshot: ScheduleSnapshot, month: YearMonth) {
    val tally = remember(snapshot, month) { ShiftMonth.of(month, snapshot.shiftTypes, snapshot.resolver::resolve) }
    if (tally.swaps.isEmpty()) return
    SectionCard(title = "교환·대근") {
        Text("교환 ${tally.swapped.size}일 · 대근 ${tally.covers.size}번", style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
        tally.swaps.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.shortDate(s.date), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), modifier = Modifier.width(52.dp))
                Text(
                    when {
                        s.cover && s.name.isNotBlank() -> "대근 · ${s.name} 대신"
                        s.cover -> "대근"
                        else -> "${s.name}${if (endsWithBatchim(s.name)) "과" else "와"} 교환"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (tally.names.isNotEmpty()) {
            Text(
                tally.names.joinToString(", ") { (n, c) -> if (c > 1) "$n ${c}번" else n },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun endsWithBatchim(word: String): Boolean {
    val c = word.trim().lastOrNull() ?: return false
    return c in '가'..'힣' && (c - '가') % 28 != 0
}

/** [DAYW-02] This month's 야근, 연차 used and left, and the estimated take-home pay. */
@Composable
private fun DayFirstCard(
    calendarId: String,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    summary: MonthSummary,
    payTotal: Long?,
    profile: PayProfile?,
    columns: Int,
) {
    val context = LocalContext.current
    val prefs = WorkTypePrefs.get(context)
    val choices by prefs.all.collectAsStateWithLifecycle()
    val mode = choices[calendarId]?.taxMode ?: TaxMode.INSURANCE_4
    val today = remember { LocalDate.now() }
    // [LEAVE-01] The 연차 원장 of this calendar.
    val ledger by remember(calendarId) { context.appContainer.calendars.leave(calendarId).summary }
        .collectAsStateWithLifecycle(null)
    val used = remember(snapshot, month) {
        (1..month.lengthOfMonth()).mapNotNull { snapshot.resolver.resolve(month.atDay(it)).leave }.filter { it.kind.annual }.sumOf { it.days }
    }
    val left = ledger?.takeIf { it.lots.isNotEmpty() }?.available
    val takeHome = payTotal?.let { TakeHome.estimate(it, mode, profile?.taxFamily ?: 1, month.atDay(1), profile?.taxChildren ?: 0,
        profile?.let { p -> TakeHome.nonTaxable(p, month) } ?: 0) }
    val overtimeDays = remember(snapshot, month) {
        (1..month.lengthOfMonth()).count { snapshot.resolver.resolve(month.atDay(it)).hasExtraWork }
    }
    SectionCard(title = "${month.monthValue}월 한눈에") {
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatTile("야근", HoursFormat.korean(summary.extraMinutes), m, caption = "${overtimeDays}일") },
            { m ->
                StatTile("연차", "${LeaveBook.days(used)} 씀", m,
                    caption = left?.let { "남은 ${LeaveBook.days(it)}" } ?: "입사일을 넣으면 남은 날이 보여요",
                    warning = (left ?: 0.0) < 0)
            },
            { m ->
                StatTile("예상 실수령", takeHome?.let { Fmt.won(it.net) } ?: "-", m,
                    caption = takeHome?.let { "세전 ${Fmt.won(it.gross)} · 참고용" } ?: "급여 계산을 설정하면 보여요")
            },
        )
        tiles.chunked(columns.coerceAtMost(3)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns.coerceAtMost(3) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        TaxModeChips(mode) { prefs.setTaxMode(calendarId, it) }
        if (mode == TaxMode.INSURANCE_4 && profile != null) TaxFamilyRow(calendarId, profile)
        takeHome?.takeIf { it.deductions.isNotEmpty() }?.let { r ->
            Text(
                r.deductions.joinToString(" · ") { (name, v) -> "$name ${Fmt.won(v)}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * [PAYR-04] 간이세액표's two questions for 근로소득세: 공제대상가족 수 (본인 포함) and 8~20세 자녀 수, kept in the pay
 * setup (so they go into backups with it). Small steppers right under the tax chips; no new menu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxFamilyRow(calendarId: String, profile: PayProfile) {
    val store = LocalContext.current.appContainer.calendars.pay(calendarId)
    val scope = rememberCoroutineScope()
    fun set(change: (PayProfile) -> PayProfile) = scope.launch { store.update(change) }
    Column(Modifier.padding(top = 4.dp)) {
        // Wraps under big text instead of squeezing the second label into a column.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Stepper("가족 ${profile.taxFamily}명", profile.taxFamily > 1, profile.taxFamily < 20,
                onMinus = { set { it.copy(taxFamily = it.taxFamily - 1, taxChildren = it.taxChildren.coerceAtMost(it.taxFamily - 2)) } },
                onPlus = { set { it.copy(taxFamily = it.taxFamily + 1) } })
            Stepper("8~20세 자녀 ${profile.taxChildren}명", profile.taxChildren > 0, profile.taxChildren < profile.taxFamily - 1,
                onMinus = { set { it.copy(taxChildren = it.taxChildren - 1) } },
                onPlus = { set { it.copy(taxChildren = it.taxChildren + 1) } })
        }
        Text("근로소득세는 국세청 간이세액표 값이에요. 가족 수는 본인·배우자를 포함해 세어 주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stepper(label: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onMinus, enabled = canMinus) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onPlus, enabled = canPlus) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

/** [DAYW-02] How tax comes off, for the take-home estimate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxModeChips(mode: TaxMode, onChange: (TaxMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TaxMode.INSURANCE_4 to "4대보험", TaxMode.WITHHOLDING_3_3 to "3.3%", TaxMode.NONE to "떼지 않음").forEach { (m, label) ->
            FilterChip(selected = mode == m, onClick = { onChange(m) }, label = { Text(label) })
        }
    }
}

/**
 * [SHFT-04] Once the month's 급여 came in (a 급여 line in 돈, around the pay day when one is set), how far it
 * was from the estimate after tax.
 */
@Composable
private fun PayGapCard(calendarId: String, month: YearMonth, payTotal: Long?, profile: PayProfile?, book: MoneyBook?, style: WorkStyle?) {
    val prefs = WorkTypePrefs.get(LocalContext.current)
    val choices by prefs.all.collectAsStateWithLifecycle()
    // [HRLY-04] A part-timer chose how tax comes off on the pay screen; others choose it on the first card.
    val mode = if (style == WorkStyle.HOURLY) profile?.taxMode ?: TaxMode.NONE else choices[calendarId]?.taxMode ?: TaxMode.INSURANCE_4
    val expected = payTotal?.let { TakeHome.estimate(it, mode, profile?.taxFamily ?: 1, month.atDay(1), profile?.taxChildren ?: 0,
        profile?.let { p -> TakeHome.nonTaxable(p, month) } ?: 0).net }
    val gap = remember(book, month, expected, profile) {
        book?.let { PayGaps.of(month, expected, profile?.period?.payDate(month), it.entries) }
    } ?: return
    val cal = LocalCalendarColors.current
    SectionCard(title = "${month.monthValue}월 급여 들어온 돈") {
        Text(
            gap.text,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                gap.matches -> MaterialTheme.colorScheme.primary
                gap.diff < 0 -> cal.warning
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            "들어온 돈 ${Fmt.won(gap.received)} (${gap.dates.joinToString(", ") { Fmt.shortDate(it) }}) · " +
                "예상 ${Fmt.won(gap.expected)} (" + when (mode) {
                    TaxMode.INSURANCE_4 -> "4대보험·세금 뺀 참고값"
                    TaxMode.WITHHOLDING_3_3 -> "3.3% 뺀 값"
                    TaxMode.NONE -> "세전"
                } + ")",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!gap.matches && gap.diff < 0) {
            Text("명세서와 근무 기록을 맞춰 보세요. 빠진 수당이나 공제가 있을 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** [DAYW-05] This month's extra hours against the monthly 시간외 상한, set here. */
@Composable
private fun OvertimeCapCard(calendarId: String, summary: MonthSummary) {
    val prefs = WorkTypePrefs.get(LocalContext.current)
    val choices by prefs.all.collectAsStateWithLifecycle()
    val capHours = choices[calendarId]?.overtimeCapHours
    var editing by remember { mutableStateOf(false) }
    val cal = LocalCalendarColors.current
    SectionCard(title = "시간외 상한", trailing = { TextButton(onClick = { editing = true }) { Text(if (capHours == null) "정하기" else "바꾸기") } }) {
        if (capHours == null) {
            Text("한 달 시간외 상한(예: ${DayWork.DEFAULT_CAP_HOURS}시간)을 정하면 이번 달 남은 시간을 알려 드려요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val cap = DayWork.OvertimeCap(summary.extraMinutes, capHours)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${HoursFormat.korean(cap.usedMinutes)} / ${capHours}시간", style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                    modifier = Modifier.weight(1f))
                Text(if (cap.over) "${HoursFormat.korean(cap.overMinutes)} 넘음" else "${HoursFormat.korean(cap.leftMinutes)} 남음",
                    style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                    color = if (cap.over) cal.warning else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { cap.share.toFloat().coerceIn(0f, 1f) },
                color = if (cap.over) cal.warning else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (cap.over) {
                Spacer(Modifier.height(8.dp))
                WarningBanner("시간외 상한을 넘었어요", "이번 달 시간외가 ${capHours}시간보다 ${HoursFormat.korean(cap.overMinutes)} 많아요. 넘은 시간은 인정되지 않을 수 있어요.")
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf((capHours ?: DayWork.DEFAULT_CAP_HOURS).toString()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("한 달 시간외 상한") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> text = v.filter(Char::isDigit).take(3) },
                        label = { Text("시간") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("잔업·특근처럼 달력에 넣은 추가 근무를 모두 더해요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.setOvertimeCap(calendarId, text.toIntOrNull()?.takeIf { it > 0 })
                    editing = false
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.setOvertimeCap(calendarId, null)
                    editing = false
                }) { Text("상한 없음") }
            },
        )
    }
}

/** [DAYW-04] 징검다리 연휴 in the next 12 months: 연차 1~2일로 길게 쉬는 날. */
@Composable
private fun BridgeCard(snapshot: ScheduleSnapshot, style: WorkStyle?) {
    val today = remember { LocalDate.now() }
    val bridges = remember(snapshot, style, today) { com.bamnat.app.ui.leave.LeaveBridges.of(snapshot, style, today) }
    if (bridges.isEmpty()) return
    SectionCard(title = "징검다리 연휴") {
        bridges.take(8).forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Fmt.shortDate(b.from)}~${Fmt.shortDate(b.to)}" + if (b.holidays.isNotEmpty()) " · ${b.holidays.joinToString("·")}" else "",
                        style = MaterialTheme.typography.bodyLarge.merge(NumberStyle))
                    Text("연차: " + b.leave.joinToString(", ") { "${Fmt.shortDate(it)}(${Fmt.dayOfWeekShort(it.dayOfWeek)})" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(b.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(com.bamnat.app.ui.leave.LeaveBridges.caption(style), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
