package com.bamnat.app.ui.pay

import com.bamnat.core.pay.LeaveBook
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.OffDayWork
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.WageType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * 회사 규칙 [PAYX-07]: everything a company decides on top of the law, edited as a draft with the
 * month's total updating live, then saved in one step (and undone in one step).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompanyRulesEditor(
    vm: PayViewModel,
    saved: PayProfile,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(saved) }
    var pickHire by remember { mutableStateOf(false) }
    val rules = draft.rules
    fun setRules(r: CompanyRules) { draft = draft.copy(rules = r) }
    val before = remember(saved, snapshot, month) { vm.calculate(saved, snapshot, month).total }
    val after = remember(draft, snapshot, month) { vm.calculate(draft, snapshot, month) }

    if (pickHire) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (draft.hireDate ?: LocalDate.now().minusYears(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickHire = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val hire = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        draft = draft.copy(
                            hireDate = hire,
                            rules = draft.rules.copy(annualLeaveDays = LeaveBook.legalDays(hire, LocalDate.now()).toDouble()),
                        )
                    }
                    pickHire = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickHire = false }) { Text("취소") } },
        ) { DatePicker(state = state, title = { Text("입사일", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("취업규칙·근로계약서·급여명세서를 보고 회사 방식에 맞춰 주세요. 고르지 않은 것은 법정 기준으로 계산해요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionCard(title = "기본") {
                Toggle("5인 이상 사업장", "아니면 법정 가산이 없어요. 회사가 주는 가산은 아래에서 따로 넣어요", draft.fiveOrMoreEmployees) {
                    draft = draft.copy(fiveOrMoreEmployees = it)
                }
                Toggle("하루 8시간 넘으면 연장", "탄력적·선택적 근로시간제라면 꺼 주세요", draft.dailyLimitMinutes != null) {
                    draft = draft.copy(dailyLimitMinutes = if (it) 480 else null)
                }
                if (draft.wageType == WageType.MONTHLY) {
                    Label("월 소정근로시간 (통상시급을 나누는 시간)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(209, 226, 243).forEach { h ->
                            FilterChip(selected = draft.monthlyHours == h, onClick = { draft = draft.copy(monthlyHours = h) },
                                label = { Text("${h}시간") })
                        }
                        OutlinedButton(onClick = { draft = draft.copy(monthlyHours = (draft.monthlyHours - 1).coerceAtLeast(100)) }) { Text("−") }
                        Text("${draft.monthlyHours}시간", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
                        OutlinedButton(onClick = { draft = draft.copy(monthlyHours = (draft.monthlyHours + 1).coerceAtMost(320)) }) { Text("+") }
                    }
                    Hint("주 40시간이면 209시간이 보통이에요. 토요일 4시간 유급이면 226시간, 8시간 유급이면 243시간이에요.")
                }
            }

            SectionCard(title = "가산율 (100% 위에 더하는 몫)") {
                PremiumRow("연장", rules.overtimePremium, 0.5, listOf(0.5, 0.75, 1.0)) { setRules(rules.copy(overtimePremium = it)) }
                PremiumRow("야간 (22~06시)", rules.nightPremium, 0.5, listOf(0.5, 0.75, 1.0)) { setRules(rules.copy(nightPremium = it)) }
                PremiumRow("휴일 8시간까지", rules.holidayPremiumWithin8h, 0.5, listOf(0.5, 1.0, 1.5)) { setRules(rules.copy(holidayPremiumWithin8h = it)) }
                PremiumRow("휴일 8시간 넘는 부분", rules.holidayPremiumOver8h, 1.0, listOf(1.0, 1.5, 2.0)) { setRules(rules.copy(holidayPremiumOver8h = it)) }
                Hint("예: 연장 가산 50%면 연장 1시간에 통상시급의 150%를 받아요.")
            }

            SectionCard(title = "쉬는 날과 공휴일") {
                Label("쉬는 날(휴무)에 일하면")
                OffDayWork.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { setRules(rules.copy(offDayWork = mode)) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = rules.offDayWork == mode, onClick = { setRules(rules.copy(offDayWork = mode)) })
                        Column {
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Toggle("공휴일 근무는 휴일근로", "근무표에서 공휴일을 다른 날로 바꿔 주는 회사(휴일 대체)라면 꺼 주세요", rules.publicHolidaysPaid) {
                    setRules(rules.copy(publicHolidaysPaid = it))
                }
                if (draft.wageType == WageType.MONTHLY) {
                    Label("월급제 휴일 근무")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HolidayPayMode.entries.forEach { mode ->
                            FilterChip(selected = draft.holidayPayMode == mode,
                                onClick = { draft = draft.copy(holidayPayMode = mode, estimatedFields = draft.estimatedFields - "holidayPayMode") },
                                label = { Text(mode.label) })
                        }
                    }
                }
            }

            SectionCard(title = "잔업·특근·대근") {
                Hint("달력에서 날짜를 누르고 ‘추가 근무’로 표시한 날에 적용돼요.")
                ExtraWork.entries.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ExtraRuleEditor(kind, rules.extra[kind] ?: ExtraRule()) { r ->
                        setRules(rules.copy(extra = if (r.basis == ExtraBasis.LEGAL) rules.extra - kind else rules.extra + (kind to r)))
                    }
                }
            }

            SectionCard(title = "연장 시간 끝자리") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 10, 15, 30, 60).forEach { u ->
                        FilterChip(selected = rules.overtimeRounding.unit == u,
                            onClick = { setRules(rules.copy(overtimeRounding = rules.overtimeRounding.copy(unit = u))) },
                            label = { Text(if (u == 1) "1분 그대로" else "${u}분 단위") })
                    }
                }
                if (rules.overtimeRounding.unit > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Rounding.entries.forEach { r ->
                            FilterChip(selected = rules.overtimeRounding.rounding == r,
                                onClick = { setRules(rules.copy(overtimeRounding = rules.overtimeRounding.copy(rounding = r))) },
                                label = { Text(when (r) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" }) })
                        }
                    }
                }
                Hint("하루마다 연장 시간에 적용해요. 버림은 일한 시간보다 적게 계산될 수 있어요.")
            }

            if (draft.wageType == WageType.HOURLY) {
                SectionCard(title = "시급제") {
                    Toggle("주휴수당", "주 15시간 이상이면 주마다 (주 근무시간 ÷ 40) × 8시간을 더 받아요", rules.weeklyHolidayPay) {
                        setRules(rules.copy(weeklyHolidayPay = it))
                    }
                }
            }
            SectionCard(title = "연차") {
                // [FEAT-10] Days granted this year; the pay screen shows used and left.
                val days = rules.annualLeaveDays
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = days == null, onClick = { setRules(rules.copy(annualLeaveDays = null)) }, label = { Text("입력 안 함") })
                    listOf(11.0, 15.0, 20.0, 25.0).forEach { d ->
                        FilterChip(selected = days == d, onClick = { setRules(rules.copy(annualLeaveDays = d)) }, label = { Text(LeaveBook.days(d)) })
                    }
                    OutlinedButton(onClick = { setRules(rules.copy(annualLeaveDays = ((days ?: 15.0) - 0.5).coerceAtLeast(0.0))) }) { Text("−") }
                    Text(days?.let(LeaveBook::days) ?: "—", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    OutlinedButton(onClick = { setRules(rules.copy(annualLeaveDays = ((days ?: 15.0) + 0.5).coerceAtMost(40.0))) }) { Text("+") }
                }
                // [PAY-10] 입사일 gives the legal number (근로기준법 제60조), which the user can still adjust.
                val today = remember { LocalDate.now() }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("입사일", style = MaterialTheme.typography.bodyLarge)
                        val legal = draft.hireDate?.let { LeaveBook.legalDays(it, today) }
                        Text(
                            if (legal == null) "넣으면 법정 연차를 계산해 채워요"
                            else "오늘 기준 법정 연차 ${legal}일 (80% 이상 출근 가정)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { pickHire = true }) {
                        Text(draft.hireDate?.let { "${it.year}.${it.monthValue}.${it.dayOfMonth}" } ?: "선택")
                    }
                }
                Hint("1년 이상 다니면 15일, 그 뒤 2년마다 1일씩 늘어 최대 25일이에요. 1년 안 됐으면 한 달 개근마다 1일(최대 11일)이에요. 근로기준법 제60조 · 5인 미만 사업장은 해당 없음.")
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                val delta = after.total - before
                Text(
                    "${month.monthValue}월 합계 ${won(before)}" + if (delta != 0L) " → ${won(after.total)} (${if (delta > 0) "+" else "−"}${won(kotlin.math.abs(delta))})" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (delta == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                after.warnings.firstOrNull { "법정" in it || "버리" in it }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = onClose) { Text("취소") }
                    Button(onClick = { vm.update { draft }; onClose() }, enabled = draft != saved) { Text("저장") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraRuleEditor(kind: ExtraWork, rule: ExtraRule, onChange: (ExtraRule) -> Unit) {
    Text(kind.label, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExtraBasis.entries.forEach { b ->
            FilterChip(selected = rule.basis == b, onClick = { onChange(rule.copy(basis = b)) }, label = { Text(b.label) })
        }
    }
    when (rule.basis) {
        ExtraBasis.LEGAL -> Hint(
            when (kind) {
                ExtraWork.OVERTIME -> "근무 뒤에 더 일한 시간을 연장(150%)으로 계산해요."
                else -> "쉬는 날이면 위의 ‘쉬는 날’ 규칙대로, 근무일이면 연장으로 계산해요."
            },
        )
        ExtraBasis.HOURLY_RATE -> {
            Label("통상시급의")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 1.5, 2.0, 2.5, 3.0).forEach { m ->
                    FilterChip(selected = rule.multiplier == m, onClick = { onChange(rule.copy(multiplier = m)) },
                        label = { Text(PayCalculator.pct(m)) })
                }
            }
        }
        ExtraBasis.FIXED_PER_TIME, ExtraBasis.FIXED_PER_HOUR -> MoneyField(
            if (rule.basis == ExtraBasis.FIXED_PER_TIME) "1회 금액" else "1시간 금액",
            rule.amount.takeIf { it > 0 }, { onChange(rule.copy(amount = it ?: 0)) },
        )
    }
    if (rule.basis != ExtraBasis.LEGAL) {
        if (rule.basis != ExtraBasis.FIXED_PER_TIME) {
            Label("최소 인정 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 4 * 60, 8 * 60).forEach { m ->
                    FilterChip(selected = rule.minMinutes == m, onClick = { onChange(rule.copy(minMinutes = m)) },
                        label = { Text(if (m == 0) "일한 만큼" else PayCalculator.hours(m)) })
                }
            }
        }
        Toggle("야간 가산 따로 받기", "22~06시 부분에 야간 가산을 더해요", rule.nightPremium) { onChange(rule.copy(nightPremium = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PremiumRow(title: String, value: Double?, legal: Double, options: List<Double>, onChange: (Double?) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text("법정 ${PayCalculator.pct(legal)}") })
        options.filter { it != legal }.forEach { o ->
            FilterChip(selected = value == o, onClick = { onChange(o) }, label = { Text(PayCalculator.pct(o)) })
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** One line per rule that differs from the law, for the result screen's summary. */
fun CompanyRules.summary(profile: PayProfile): List<String> = buildList {
    overtimePremium?.let { add("연장 가산 ${PayCalculator.pct(it)}") }
    nightPremium?.let { add("야간 가산 ${PayCalculator.pct(it)}") }
    holidayPremiumWithin8h?.let { add("휴일 가산 ${PayCalculator.pct(it)}") }
    holidayPremiumOver8h?.let { add("휴일 8시간 초과 가산 ${PayCalculator.pct(it)}") }
    if (offDayWork == OffDayWork.OVERTIME) add("쉬는 날 근무는 연장근로")
    if (!publicHolidaysPaid) add("공휴일은 휴일 대체")
    extra.forEach { (kind, r) -> add("${kind.label}: ${r.describe()}") }
    if (overtimeRounding != MinuteRounding() && overtimeRounding.unit > 1) {
        add("연장 ${overtimeRounding.unit}분 단위 " + when (overtimeRounding.rounding) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" })
    }
    if (profile.wageType == WageType.MONTHLY && profile.monthlyHours != 209) add("월 소정근로 ${profile.monthlyHours}시간")
    if (profile.wageType == WageType.HOURLY && !weeklyHolidayPay) add("주휴수당 없음")
    annualLeaveDays?.let { add("연차 ${LeaveBook.days(it)}") }
}
