package com.bamnat.app.ui.company

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.pay.MoneyField
import com.bamnat.app.ui.pay.MonthStepper
import com.bamnat.app.ui.pay.baseName
import com.bamnat.app.ui.pay.won
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.WageType
import java.time.LocalDate
import java.time.YearMonth

/**
 * [COMM-01] The pay setup after a coworker's setup was applied: the rules, allowances and bonus are
 * already in, so only what differs from person to person is asked. Only the 기본급 is needed; an
 * allowance left blank is 금액 모름 on the pay screen [PAYS-01], so the user never has to stop here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompanyPayScreen(vm: CompanyPayViewModel, onBack: () -> Unit, onByHand: () -> Unit) {
    var wageType by rememberSaveable { mutableStateOf(WageType.MONTHLY) }
    var base by rememberSaveable { mutableStateOf<Long?>(null) }
    var rough by rememberSaveable { mutableStateOf(false) }
    var dailyHours by rememberSaveable { mutableStateOf(8) }
    // A year back, so the months already worked come to an amount too [PAYS-01].
    var since by remember { mutableStateOf(YearMonth.now().minusMonths(12)) }
    val amounts = remember { mutableStateMapOf<String, Long>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val summary = vm.summary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("급여 계산 설정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeroIcon(Icons.Outlined.Payments)
                Text("거의 다 됐어요! 내 금액만 넣어요", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${vm.company.ifEmpty { "같은 회사" }} 분들이 맞춘 계산 방식이 들어 있어요. 사람마다 다른 금액만 넣으면 끝나요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary != null) {
                    SoftCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("이미 들어 있어요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                            if (summary.shiftTypes.isNotEmpty()) Ready("근무 ${summary.shiftTypes.size}가지와 시간" + (summary.cycleDays?.let { " · ${it}일 순서" } ?: ""))
                            if (summary.hasRules) Ready("연장·야간·휴일 가산과 회사 규칙")
                            if (summary.extraKinds.isNotEmpty()) Ready("연장·휴무일 근무: ${summary.extraKinds.joinToString(" · ")}")
                            if (summary.items.isNotEmpty()) Ready("수당 이름: ${summary.items.joinToString(" · ")}")
                        }
                    }
                }

                Text("급여 형태", style = MaterialTheme.typography.titleSmall)
                val wages = listOf(WageType.MONTHLY to "월급", WageType.HOURLY to "시급", WageType.DAILY to "일당")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    wages.forEachIndexed { i, (type, label) ->
                        SegmentedButton(selected = wageType == type, onClick = { wageType = type },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = wages.size)) { Text(label) }
                    }
                }
                MoneyField(
                    label = "내 ${wageType.baseName()}",
                    value = base,
                    onChange = { base = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "명세서나 계약서의 ${wageType.baseName()}",
                )
                FilterChip(selected = rough, onClick = { rough = !rough }, label = { Text("대략이에요") })
                if (wageType == WageType.DAILY) {
                    // [PAYS-03] 일당 ÷ 하루 소정근로시간 is the hourly rate premiums are paid on.
                    Text("일당은 하루 몇 시간 일하는 값인가요?", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (4..8).forEach { h ->
                            FilterChip(selected = dailyHours == h, onClick = { dailyHours = h }, label = { Text("${h}시간") })
                        }
                    }
                    base?.let { b ->
                        val hourly = b / dailyHours
                        val minimum = remember { RateTable.on(LocalDate.now()).minimumHourlyWage }
                        Text("시간당 ${won(hourly)}" + if (hourly < minimum) " · 최저임금(${won(minimum)})보다 적어요" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hourly < minimum) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("이 금액을 받기 시작한 달", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MonthStepper(since, { since = it })
                }

                if (vm.needs.isNotEmpty()) {
                    Text("내 수당 금액", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    vm.needs.forEach { need ->
                        MoneyField(
                            label = "${need.label} · ${need.hint}",
                            value = amounts[need.key],
                            onChange = { v -> if (v == null) amounts.remove(need.key) else amounts[need.key] = v },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "모르면 비워 두세요",
                        )
                    }
                }
                vm.bonusNote?.let { note ->
                    Note("상여는 기본급으로 저절로 계산돼요: $note")
                }
                Note(
                    (if (base == null) "${wageType.baseName()}만 넣으면 시작할 수 있어요. " else "") +
                        "모르는 수당은 비워 두면 ‘금액 모름’으로 표시하고 합계에서 빼 둬요. 급여 화면에서 언제든 넣을 수 있어요.",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = {
                        val b = base ?: return@Button
                        saving = true
                        error = null
                        vm.save(wageType, b, rough, dailyHours * 60, since, amounts.toMap(),
                            done = { saving = false }, failed = { saving = false; error = it })
                    },
                    enabled = !saving && (base ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("계산 시작")
                }
                TextButton(onClick = onByHand, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("회사 설정 없이 처음부터 직접 할게요")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Ready(text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Note(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
