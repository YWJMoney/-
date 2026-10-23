package com.bamnat.app.ui.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.CountStepper
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StepperRow
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WhatIf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * [PAYX-38] 만약에 계산: the month again with a raise, more night shifts or another 월 소정근로시간 ([WhatIf]). Nothing
 * is saved; it only shows what would move the pay. For monthly pay the hours chips also show how far the 추정
 * range closes when the company's own number is known.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhatIfCard(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth) {
    var raise by rememberSaveable { mutableIntStateOf(0) }
    var nights by rememberSaveable { mutableIntStateOf(0) }
    var hours by rememberSaveable { mutableStateOf<Int?>(null) }
    val ask = WhatIf.Ask(raise, nights, hours)
    val answer by produceState<WhatIf.Answer?>(null, profile, snapshot, month, ask) {
        delay(200) // the steppers repeat while held; work once they pause
        value = withContext(Dispatchers.Default) { runCatching { WhatIf.answer(profile, month, snapshot.resolver::resolve, ask) }.getOrNull() }
    }
    SectionCard(title = "만약에 계산") {
        StepperRow(if (profile.wageType == WageType.MONTHLY) "기본급 올리면" else "시급 올리면") {
            CountStepper(raise, { raise = it }, "인상률", range = 0..30, format = { "$it%" })
        }
        StepperRow("야간 근무 더 하면", hint = answer?.nightDay?.let { "야간 하루에 약 ${won(it)}" } ?: "이 달 야간 근무가 있어야 어림할 수 있어요") {
            CountStepper(nights, { nights = it }, "야간 근무", range = 0..15, format = { "${it}번" }, enabled = answer?.nightDay != null || nights > 0)
        }
        if (profile.wageType == WageType.MONTHLY) {
            Text("월 소정근로시간이 이렇다면", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = hours == null, onClick = { hours = null }, label = { Text("지금 (${profile.monthlyHours})") })
                listOf(174, 180, 209, 226, 243).filter { it != profile.monthlyHours }.forEach { h ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("${h}시간") })
                }
            }
        }
        answer?.let { a ->
            Text(
                if (a.diff == 0L) "지금과 같아요 · ${won(a.total)}"
                else "${won(a.total)} (${if (a.diff > 0) "+" else "−"}${won(kotlin.math.abs(a.diff))})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("세전 금액이고 저장되지 않아요. 야간 근무는 이 달 야간 하루 평균으로 어림한 값이에요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
