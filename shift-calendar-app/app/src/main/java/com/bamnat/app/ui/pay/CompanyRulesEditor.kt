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
import com.bamnat.core.pay.StandardHours
import com.bamnat.core.pay.Tier
import com.bamnat.core.hours.HoursFormat
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
    val kinds = com.bamnat.app.ui.calendar.LocalExtraKinds.current
    val rates = remember(month) { com.bamnat.core.pay.RateTable.on(month.atDay(1)) }
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
                Toggle("근무표 시간은 모두 기본 근무", "12시간 교대처럼 근무표대로 일한 시간이 기본급에 들어 있으면 켜 주세요. 잔업으로 적은 시간만 연장이 돼요",
                    draft.scheduleIsRegular) {
                    draft = draft.copy(scheduleIsRegular = it, flexPeriod = if (it) draft.flexPeriod else null)
                }
                if (draft.scheduleIsRegular) FlexPicker(draft.flexPeriod) { draft = draft.copy(flexPeriod = it) }
                if (!draft.scheduleIsRegular) {
                    Toggle("하루 8시간 넘으면 연장", "탄력적·선택적 근로시간제라면 꺼 주세요", draft.dailyLimitMinutes != null) {
                        draft = draft.copy(dailyLimitMinutes = if (it) 480 else null)
                    }
                    Toggle("주 40시간 넘으면 연장", "근무표대로 일한 시간이 주 40시간을 넘어도 기본급에 들어 있으면 꺼 주세요", draft.weeklyLimitMinutes != null) {
                        draft = draft.copy(weeklyLimitMinutes = if (it) 2400 else null)
                    }
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
                    // [PAY-04] The same formula on the user's own roster (시행령 제6조 제2항).
                    val weekly = remember(snapshot, month) { StandardHours.averageWeekly(month.atDay(1), resolve = snapshot.resolver::resolve) }
                    val fromRoster = StandardHours.monthly(weekly)
                    if (weekly > 0 && fromRoster != draft.monthlyHours) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("근무표로 계산하면 ${fromRoster}시간", style = MaterialTheme.typography.bodyLarge)
                                Text("1년 평균 주 ${HoursFormat.korean(weekly)} 소정근로 + 주휴. 계약서에 적힌 시간이 있으면 그것을 쓰세요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { draft = draft.copy(monthlyHours = fromRoster) }) { Text("적용") }
                        }
                    }
                }
            }

            SectionCard(title = "가산율 (100% 위에 더하는 몫)") {
                PremiumRow("연장", rules.overtimePremium, 0.5, listOf(0.5, 0.75, 1.0)) { setRules(rules.copy(overtimePremium = it)) }
                PremiumRow("야간 (${rules.nightLabel(rates)})", rules.nightPremium, 0.5, listOf(0.5, 0.75, 1.0)) { setRules(rules.copy(nightPremium = it)) }
                PremiumRow("휴일 8시간까지", rules.holidayPremiumWithin8h, 0.5, listOf(0.5, 1.0, 1.5)) { setRules(rules.copy(holidayPremiumWithin8h = it)) }
                PremiumRow("휴일 8시간 넘는 부분", rules.holidayPremiumOver8h, 1.0, listOf(1.0, 1.5, 2.0)) { setRules(rules.copy(holidayPremiumOver8h = it)) }
                Hint("예: 연장 가산 50%면 연장 1시간에 통상시급의 150%를 받아요." +
                    if (!draft.fiveOrMoreEmployees) " 5인 미만이라 법정 가산은 0%예요. 회사가 주는 가산을 골라 주세요." else "")
            }

            SectionCard(title = "야간 시간") {
                // [PAYX-12] Every shift on the calendar, base shifts included, earns the night premium in this window.
                NightWindowPicker(rules) { s, e -> setRules(rules.copy(nightStart = s, nightEnd = e)) }
                Hint("달력의 근무는 기본 근무로 계산해도 이 시간에 걸친 부분은 야간 가산을 받아요. 법은 22~06시예요(근로기준법 제56조 제3항). " +
                    "회사 야간 시간이 이 시간을 덜 덮으면 아래에 알려 드려요.")
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
                if (rules.publicHolidaysPaid) {
                    // [PAYX-07] Some companies pay a 공휴일 shift exactly like a 대근.
                    Label("공휴일에 근무표대로 일하면")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = rules.publicHolidayAs == null, onClick = { setRules(rules.copy(publicHolidayAs = null)) },
                            label = { Text("법정 휴일근로") })
                        listOf(ExtraWork.COVER, ExtraWork.SPECIAL).forEach { k ->
                            FilterChip(selected = rules.publicHolidayAs == k, onClick = { setRules(rules.copy(publicHolidayAs = k)) },
                                label = { Text("${kinds.name(k)}처럼 계산") })
                        }
                    }
                    if (rules.publicHolidayAs != null && rules.extraRule(rules.publicHolidayAs) == null) {
                        Hint("아래 ‘${kinds.name(rules.publicHolidayAs!!)}’ 계산 방식을 정해 주세요. 정하지 않으면 법정대로 계산해요.")
                    }
                    Toggle("공휴일 연차는 연차 일수에서 안 빠짐", "공휴일 근무일에 쉬어도 연차가 줄지 않는 회사라면 켜 주세요", rules.holidayLeaveFree) {
                        setRules(rules.copy(holidayLeaveFree = it))
                    }
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

            SectionCard(title = kinds.visible().joinToString(" · ") { kinds.name(it) }) {
                Hint("달력에서 날짜를 누르고 ‘${kinds.visible().joinToString("·") { kinds.name(it) }}’로 표시한 날에 적용돼요. 이름과 쓰는 종류는 설정 → 근무에서 바꿔요.")
                val shiftTypes = remember(snapshot) { snapshot.shiftTypes.filter { !it.isOff && it.hasTimes } }
                kinds.visible(rules.publicHolidayAs).forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ExtraRuleEditor(kinds.name(kind), kind, rules.extra[kind] ?: ExtraRule()) { r ->
                        setRules(rules.copy(extra = if (r.basis == ExtraBasis.LEGAL) rules.extra - kind else rules.extra + (kind to r)))
                    }
                    // [PAYX-11] A different way for each shift the extra work covered, e.g. 나이트 대근 1회 10만원.
                    if (shiftTypes.isNotEmpty()) PerShiftRules(kinds.name(kind), kind, shiftTypes, rules) { setRules(it) }
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
private fun ExtraRuleEditor(title: String, kind: ExtraWork, rule: ExtraRule, onChange: (ExtraRule) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExtraBasis.entries.forEach { b ->
            FilterChip(selected = rule.basis == b, onClick = {
                // Tiers usually already price the night hours, so the separate night premium starts off.
                onChange(rule.copy(basis = b, nightPremium = if (b == ExtraBasis.TIERED) false else rule.nightPremium))
            }, label = { Text(b.label) })
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
        ExtraBasis.TIERED -> {
            TierEditor("주간 근무일 때", rule.dayTiers) { onChange(rule.copy(dayTiers = it)) }
            TierEditor("야간 근무일 때 (야간 시간이 절반 이상)", rule.nightTiers) { onChange(rule.copy(nightTiers = it)) }
            Hint("근무 시작부터 순서대로 구간마다 통상시급 × 배율을 받아요. 마지막 구간은 남은 시간 전부에 적용돼요.")
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
        Toggle("야간 가산 따로 받기", "야간 시간에 걸친 부분에 야간 가산을 더해요", rule.nightPremium) { onChange(rule.copy(nightPremium = it)) }
    }
}

/** Blocks of hours with their rate, in shift order [PAYX-07]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierEditor(title: String, tiers: List<Tier>, onChange: (List<Tier>) -> Unit) {
    Label(title)
    tiers.forEachIndexed { i, t ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(minutes = (t.minutes - 60).coerceAtLeast(60)) }) }) { Text("−") }
            Text(PayCalculator.hours(t.minutes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(minutes = (t.minutes + 60).coerceAtMost(24 * 60)) }) }) { Text("+") }
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
                FilterChip(selected = t.premium == p, onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(premium = p) }) },
                    label = { Text(PayCalculator.pct(1.0 + p)) })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange(tiers + Tier(240, tiers.lastOrNull()?.premium ?: 0.5)) }) { Text("구간 추가") }
        if (tiers.size > 1) TextButton(onClick = { onChange(tiers.dropLast(1)) }) { Text("마지막 구간 빼기") }
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
    extra.forEach { (kind, r) -> add("${extraName(kind)}: ${r.describe()}") }
    extraByShift.forEach { (kind, byType) -> if (byType.isNotEmpty()) add("${extraName(kind)}: 근무별 ${byType.size}개") }
    if (hasNightWindow) add("야간 시간 ${nightLabel(com.bamnat.core.pay.RateTable.on(java.time.LocalDate.now()))}")
    if (overtimeRounding != MinuteRounding() && overtimeRounding.unit > 1) {
        add("연장 ${overtimeRounding.unit}분 단위 " + when (overtimeRounding.rounding) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" })
    }
    if (profile.wageType == WageType.MONTHLY && profile.monthlyHours != 209) add("월 소정근로 ${profile.monthlyHours}시간")
    if (profile.wageType == WageType.HOURLY && !weeklyHolidayPay) add("주휴수당 없음")
    annualLeaveDays?.let { add("연차 ${LeaveBook.days(it)}") }
}

/** [PAYX-12] The company's night window: the law's, a common alternative, or any hours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightWindowPicker(rules: CompanyRules, onChange: (java.time.LocalTime?, java.time.LocalTime?) -> Unit) {
    fun t(h: Int) = java.time.LocalTime.of(h, 0)
    val presets = listOf(22 to 6, 23 to 7, 22 to 7, 21 to 6)
    val start = rules.nightStart?.hour ?: 22
    val end = rules.nightEnd?.hour ?: 6
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { (s, e) ->
            val legal = s == 22 && e == 6
            FilterChip(
                selected = if (legal) !rules.hasNightWindow else rules.hasNightWindow && start == s && end == e,
                onClick = { if (legal) onChange(null, null) else onChange(t(s), t(e)) },
                label = { Text("%02d~%02d시".format(s, e) + if (legal) " (법정)" else "") },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("직접", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { onChange(t((start + 23) % 24), t(end)) }) { Text("−") }
        Text("%02d시".format(start), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onChange(t((start + 1) % 24), t(end)) }) { Text("+") }
        Text("~", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onChange(t(start), t((end + 23) % 24)) }) { Text("−") }
        Text("%02d시".format(end), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onChange(t(start), t((end + 1) % 24)) }) { Text("+") }
    }
}

/**
 * [PAYX-11] Pay for one kind of extra work by the shift it covered (주간·오후·야간 …). A shift with
 * no rule of its own uses the kind's rule above, so only the differences need entering.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerShiftRules(
    name: String, kind: ExtraWork, types: List<com.bamnat.core.model.ShiftType>, rules: CompanyRules, onChange: (CompanyRules) -> Unit,
) {
    val byType = rules.extraByShift[kind].orEmpty()
    var editing by remember(kind) { mutableStateOf<Long?>(null) }
    Label("근무별로 다르게 (대신 선 근무 기준)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        types.forEach { t ->
            val own = byType[t.id]
            FilterChip(
                selected = editing == t.id || own != null,
                onClick = { editing = if (editing == t.id) null else t.id },
                label = { Text(if (own != null) "${t.name} · ${own.describe().substringBefore(", ")}" else t.name) },
            )
        }
    }
    val id = editing ?: return
    val type = types.firstOrNull { it.id == id } ?: return
    fun set(r: ExtraRule?) {
        val next = if (r == null || r.basis == ExtraBasis.LEGAL) byType - id else byType + (id to r)
        onChange(rules.copy(extraByShift = if (next.isEmpty()) rules.extraByShift - kind else rules.extraByShift + (kind to next)))
    }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            ExtraRuleEditor("${type.name} $name", kind, byType[id] ?: rules.extra[kind] ?: ExtraRule()) { set(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (byType[id] != null) TextButton(onClick = { set(null); editing = null }) { Text("위 방식과 같게") }
                TextButton(onClick = { editing = null }) { Text("닫기") }
            }
            if (byType[id] == null) Hint("방식을 고르면 ${type.name} ${name}만 따로 계산해요. 고르기 전에는 위 방식대로예요.")
        }
    }
}
