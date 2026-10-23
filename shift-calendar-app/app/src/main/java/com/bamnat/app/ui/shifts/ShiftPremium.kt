package com.bamnat.app.ui.shifts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.isNight

/** [SHFT-01] Steps offered for a kind's own premium, in percent. */
private val PremiumChoices = listOf(0, 10, 20, 30, 50, 100)
private const val PREMIUM_MAX = 300

/**
 * [SHFT-01] "이 근무 수당 n%": what this kind of day pays on top of the company's rules, a line of its own
 * in 급여 계산 (hours worked on it × 통상시급 × n%). Says when the kind is night work by the clock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShiftPremiumSection(type: ShiftType, onChange: (Int) -> Unit) {
    val p = type.premiumPercent
    SectionCard(title = "근무 수당") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("이 근무 수당", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (p == 0) "회사에서 이 근무에만 더 주는 몫이 있으면 넣어요"
                    else "일한 시간 × 통상시급 × $p%를 급여에 더해요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onChange((p - 5).coerceAtLeast(0)) }, enabled = p > 0) { Text("−") }
            Text("$p%", style = MaterialTheme.typography.titleMedium.merge(NumberStyle), modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedButton(onClick = { onChange((p + 5).coerceAtMost(PREMIUM_MAX)) }, enabled = p < PREMIUM_MAX) { Text("+") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in PremiumChoices) FilterChip(selected = p == c, onClick = { onChange(c) }, label = { Text(if (c == 0) "없음" else "$c%") })
        }
        if (type.isNight) {
            Text(
                "야간(22~06시)에 걸친 근무예요. 법정 야간 가산 50%는 따로 계산하니, 회사가 그 위에 더 주는 몫만 넣어 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [SHFT-01] Small 야간 and "수당 n%" marks beside a kind's name. */
@Composable
internal fun ShiftPremiumMarks(type: ShiftType) {
    if (type.isNight) {
        Spacer(Modifier.width(6.dp))
        SmallMark("야간")
    }
    if (type.premiumPercent > 0 && !type.isOff) {
        Spacer(Modifier.width(6.dp))
        SmallMark("수당 ${type.premiumPercent}%")
    }
}

@Composable
private fun SmallMark(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
