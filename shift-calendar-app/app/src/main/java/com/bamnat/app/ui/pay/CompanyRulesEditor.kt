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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.calendar.ExtraKindMark
import java.time.DayOfWeek
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.StandardHours
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
    onOpenKinds: () -> Unit,
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
                if (!draft.fiveOrMoreEmployees) {
                    // [EXTRA-02] 근로기준법 시행령 제7조 별표 1: these do not apply under five employees.
                    Hint("5인 미만 사업장은 연장·야간·휴일 가산, 주 40시간·52시간, 연차가 법으로 정해져 있지 않아요(근로기준법 시행령 제7조 별표 1). " +
                        "회사가 주는 가산이 있으면 아래 가산율에서 골라 주세요.")
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

            // [PAYX-15] The days a month's pay covers and the pay day, e.g. 전월 21일~20일, 25일 지급.
            SectionCard(title = "정산 기간과 월급날") {
                val period = draft.period
                Label("근무를 며칠까지 모아 그 달 급여로 계산하나요?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = period.endDay == null, onClick = { draft = draft.copy(period = period.copy(endDay = null)) },
                        label = { Text("말일 (1일~말일)") })
                    listOf(15, 20).forEach { d ->
                        FilterChip(selected = period.endDay == d, onClick = { draft = draft.copy(period = period.copy(endDay = d)) },
                            label = { Text("${d}일") })
                    }
                    OutlinedButton(onClick = { draft = draft.copy(period = period.copy(endDay = ((period.endDay ?: 31) - 1).coerceIn(1, 30))) }) { Text("−") }
                    Text(period.endDay?.let { "${it}일" } ?: "말일", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.CenterVertically).widthIn(min = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    OutlinedButton(onClick = {
                        val next = (period.endDay ?: 30) + 1
                        draft = draft.copy(period = period.copy(endDay = next.takeIf { it <= 30 }))
                    }) { Text("+") }
                }
                Hint("${month.monthValue}월 급여는 ${period.label(month)} 근무로 계산해요.")
                Label("월급날")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = period.payDay == null, onClick = { draft = draft.copy(period = period.copy(payDay = null)) },
                        label = { Text("안 정함") })
                    listOf(10, 25).forEach { d ->
                        FilterChip(selected = period.payDay == d, onClick = { draft = draft.copy(period = period.copy(payDay = d)) },
                            label = { Text("${d}일") })
                    }
                    OutlinedButton(onClick = { draft = draft.copy(period = period.copy(payDay = ((period.payDay ?: 26) - 1).coerceIn(1, 31))) }) { Text("−") }
                    Text(period.payDay?.let { "${it}일" } ?: "−", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.CenterVertically).widthIn(min = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    OutlinedButton(onClick = { draft = draft.copy(period = period.copy(payDay = ((period.payDay ?: 24) + 1).coerceIn(1, 31))) }) { Text("+") }
                }
                if (period.payDay != null) {
                    Toggle("마감한 다음 달에 지급", "예: 말일 마감, 다음 달 10일 지급", period.payNextMonth) {
                        draft = draft.copy(period = period.copy(payNextMonth = it))
                    }
                    period.payDate(month)?.let { Hint("${month.monthValue}월 급여는 ${it.monthValue}월 ${it.dayOfMonth}일에 들어와요.") }
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
                // [EXTRA-02] 휴무일 work is not 휴일근로 by law; the company may still pay it so.
                Label("쉬는 날(휴무일)에 일하면")
                RestDayWork.entries.forEach { mode ->
                    val selected = rules.restDayWork == mode
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { setRules(rules.copy(restDayWork = mode)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(mode.label + if (mode == RestDayWork.LAW) " (근로기준법)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Hint("법으로 휴무일 근무는 휴일근로가 아니에요. 하루 8시간·주 40시간을 넘는 시간만 연장이에요(근로기준법 제50조·제56조). " +
                    "취업규칙에 휴일근로로 준다고 되어 있으면 바꿔 주세요.")
                Label("주휴일 (일주일에 한 번 쉬는 유급 휴일)")
                val fixed = rules.weeklyHoliday as? WeeklyHoliday.Fixed
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = rules.weeklyHoliday == WeeklyHoliday.Unknown,
                        onClick = { setRules(rules.copy(weeklyHoliday = WeeklyHoliday.Unknown)) }, label = { Text("정해지지 않음") })
                    FilterChip(selected = rules.weeklyHoliday == WeeklyHoliday.FirstRestDay,
                        onClick = { setRules(rules.copy(weeklyHoliday = WeeklyHoliday.FirstRestDay)) }, label = { Text("주마다 첫 쉬는 날") })
                    // A company that fixes the day mostly fixes Sunday; the days below change it.
                    FilterChip(selected = fixed != null,
                        onClick = { if (fixed == null) setRules(rules.copy(weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY))) },
                        label = { Text("요일로 정하기") })
                }
                if (fixed != null) WeekdayPicker(fixed.day) { setRules(rules.copy(weeklyHoliday = WeeklyHoliday.Fixed(it))) }
                Hint("주휴일에 일하면 위 방식과 상관없이 휴일근로예요(근로기준법 제55조). 교대 근무는 따로 정하지 않은 곳이 많아요. " +
                    "모르면 ‘정해지지 않음’으로 두면 쉬는 날을 모두 휴무일로 계산해요.")
                Toggle("공휴일 근무는 휴일근로", "근무표에서 공휴일을 다른 날로 바꿔 주는 회사(휴일 대체)라면 꺼 주세요", rules.publicHolidaysPaid) {
                    setRules(rules.copy(publicHolidaysPaid = it))
                }
                if (rules.publicHolidaysPaid) {
                    // [PAYX-07] Some companies pay a 공휴일 shift exactly like a 대근.
                    Label("공휴일에 근무표대로 일하면")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = rules.publicHolidayAs == null, onClick = { setRules(rules.copy(publicHolidayAs = null)) },
                            label = { Text("법정 휴일근로") })
                        kinds.visible(listOfNotNull(rules.publicHolidayAs)).forEach { k ->
                            FilterChip(selected = rules.publicHolidayAs == k.id, onClick = { setRules(rules.copy(publicHolidayAs = k.id)) },
                                label = { Text("${k.name}처럼 계산") }, leadingIcon = { ExtraKindMark(k, size = 18.dp) })
                        }
                    }
                    rules.publicHolidayAs?.let { id ->
                        if (rules.extraRule(id) == null) Hint("‘${kinds.name(id)}’ 급여 방식이 법대로라 공휴일도 법정대로 계산해요. 아래에서 방식을 정해 주세요.")
                    }
                    // [PAYX-14] Overnight shifts across a holiday: by the clock unless the company says otherwise.
                    Toggle("밤샘 근무는 시작한 날로 휴일 판단", "끄면 공휴일 0시~24시에 일한 시간만 휴일근로예요. 회사가 근무 시작일로 정하면 켜 주세요", rules.holidayByShiftStart) {
                        setRules(rules.copy(holidayByShiftStart = it))
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

            // [EXTRA-04] How each kind is paid lives with its times on one screen; here is what it says.
            SectionCard(title = "연장 휴무일 근무 지정") {
                kinds.visible(rules.extra.keys + rules.extraByShift.keys).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        ExtraKindMark(k, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.name, style = MaterialTheme.typography.bodyLarge)
                            Text(rules.payText(k.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedButton(onClick = { if (draft != saved) vm.update { draft }; onOpenKinds() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("종류·시간·가산 정하기")
                }
                Hint("대근 8시간에 기본급 10% + 야간 가산 50%처럼 회사 방식이 있으면 종류마다 체크해요." + if (draft != saved) " 여기서 바꾼 내용은 저장하고 넘어가요." else "")
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
                // [PAY-12][REL-08] Days left over from last year that the company lets you carry.
                val carry = rules.annualLeaveCarry
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("작년에서 넘어온 연차", style = MaterialTheme.typography.bodyLarge)
                        Text("회사가 이월해 준 날만 넣어요. 올해 연차에 더해 셈해요",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { setRules(rules.copy(annualLeaveCarry = (carry - 0.5).coerceAtLeast(0.0))) }, enabled = carry > 0) { Text("−") }
                    Text(LeaveBook.days(carry), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    OutlinedButton(onClick = { setRules(rules.copy(annualLeaveCarry = (carry + 0.5).coerceAtMost(40.0))) }) { Text("+") }
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
private fun PremiumRow(title: String, value: Double?, legal: Double, options: List<Double>, onChange: (Double?) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text("법정 ${PayCalculator.pct(legal)}") })
        options.filter { it != legal }.forEach { o ->
            FilterChip(selected = value == o, onClick = { onChange(o) }, label = { Text(PayCalculator.pct(o)) })
        }
    }
}

/** One line per rule that differs from the law, for the result screen's summary. */
fun CompanyRules.summary(profile: PayProfile): List<String> = buildList {
    overtimePremium?.let { add("연장 가산 ${PayCalculator.pct(it)}") }
    nightPremium?.let { add("야간 가산 ${PayCalculator.pct(it)}") }
    holidayPremiumWithin8h?.let { add("휴일 가산 ${PayCalculator.pct(it)}") }
    holidayPremiumOver8h?.let { add("휴일 8시간 초과 가산 ${PayCalculator.pct(it)}") }
    if (restDayWork != RestDayWork.LAW) add("쉬는 날 근무는 ${restDayWork.label} 계산 (${restDayWork.detail})")
    when (val w = weeklyHoliday) {
        WeeklyHoliday.Unknown -> Unit
        WeeklyHoliday.FirstRestDay -> add("주휴일은 주마다 첫 쉬는 날")
        is WeeklyHoliday.Fixed -> add("주휴일 ${Fmt.dayOfWeekShort(w.day)}요일")
    }
    if (!publicHolidaysPaid) add("공휴일은 휴일 대체")
    extra.forEach { (kind, r) -> add("${extraName(kind)}: ${r.describe()}") }
    extraByShift.forEach { (kind, byType) -> if (byType.isNotEmpty()) add("${extraName(kind)}: 근무별 ${byType.size}개") }
    if (hasNightWindow) add("야간 시간 ${nightLabel(com.bamnat.core.pay.RateTable.on(java.time.LocalDate.now()))}")
    if (overtimeRounding != MinuteRounding() && overtimeRounding.unit > 1) {
        add("연장 ${overtimeRounding.unit}분 단위 " + when (overtimeRounding.rounding) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" })
    }
    if (profile.wageType == WageType.MONTHLY && profile.monthlyHours != 209) add("월 소정근로 ${profile.monthlyHours}시간")
    if (profile.wageType == WageType.HOURLY && !weeklyHolidayPay) add("주휴수당 없음")
    annualLeaveDays?.let { add("연차 ${LeaveBook.days(it)}" + if (annualLeaveCarry > 0) " + 이월 ${LeaveBook.days(annualLeaveCarry)}" else "") }
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

/** [EXTRA-04] How a kind is paid, in a line: its rule, and how many shifts have their own. */
/** Seven days in one row, the way alarm apps pick them, so the days never wrap apart. */
@Composable
private fun WeekdayPicker(selected: DayOfWeek, onPick: (DayOfWeek) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        DayOfWeek.entries.forEach { d ->
            val on = d == selected
            Box(
                Modifier.weight(1f).height(48.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(d) })
                    .semantics { contentDescription = "${Fmt.dayOfWeekShort(d)}요일" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(Fmt.dayOfWeekShort(d), style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun CompanyRules.payText(kind: Long): String {
    val own = extraByShift[kind].orEmpty().count { !it.value.isLegal }
    return (extraRule(kind)?.describe() ?: "법대로") + if (own > 0) " · 근무별 ${own}개" else ""
}
