package com.bamnat.app.ui.pay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.calendar.HintStore
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateNews
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.YearEndPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import com.bamnat.core.time.Today

/**
 * [PAYX-42] 연말정산 미리보기: from January to the month on screen, the 결정세액 roughly against the 간이세액표
 * withholding, run on to the year. Only for 4대보험 pay, where 소득세 is withheld each month.
 */
@Composable
internal fun YearEndCard(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth) {
    if (profile.taxMode != TaxMode.INSURANCE_4) return
    var open by remember { mutableStateOf(false) }
    val r by produceState<YearEndPreview.Result?>(null, open, profile, snapshot, month) {
        if (open) value = withContext(Dispatchers.Default) { runCatching { YearEndPreview.ofYear(profile, month, snapshot.resolver::resolve) }.getOrNull() }
    }
    SectionCard(title = "${month.year}년 연말정산 미리보기", trailing = { if (!open) TextButton(onClick = { open = true }) { Text("계산하기") } }) {
        if (!open) {
            Text("1월부터 이 달까지 근무로 내년 2월에 돌려받을지, 더 낼지 어림해요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val x = r ?: return@SectionCard
        Text(
            if (x.refund >= 0) "약 ${won(x.refund)} 돌려받아요" else "약 ${won(-x.refund)} 더 내요",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (x.refund >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Line("총급여 (한 해로 어림, ${x.months}개월 기준)", won(x.salary))
        Line("근로소득공제", "−" + won(x.earnedDeduction))
        Line("과세표준", won(x.taxBase))
        Line("산출세액 − 세액공제", "${won(x.calculated)} − ${won(x.credits)}")
        Line("결정세액 (지방소득세 ${won(x.local)} 따로)", won(x.determined))
        Line("매달 뗀 소득세 (간이세액표 어림)", won(x.withheld))
        Text(YearEndPreview.LEFT_OUT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("소득세법 제47조·제55조·제59조로 셌어요. 정확한 값은 홈택스 연말정산 미리보기에서 확인해 주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** [PAYX-44] The new year's 최저임금·보험료율 once, with the user's own 시급 against the new 최저임금. */
@Composable
internal fun RateNewsCard(profile: PayProfile, ordinaryHourly: Long) {
    val context = LocalContext.current
    val hints = remember { HintStore(context) }
    val hourly = if (profile.wageType == WageType.HOURLY) profile.base.on(Today.date()).value else ordinaryHourly
    val news = remember(hourly) { RateNews.of(Today.date(), hourly) } ?: return
    var gone by remember(news.key) { mutableStateOf(hints.done(news.key)) }
    if (gone) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${news.from.year}년 ${news.from.monthValue}월부터 바뀌어요", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            news.lines.forEach { l ->
                Text("· $l", style = MaterialTheme.typography.bodyMedium,
                    color = if (news.belowMinimum && l.contains("적어요")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text("급여 계산은 바뀐 기준으로 저절로 맞춰져요 (최저임금법 제6조).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = { hints.markDone(news.key); gone = true }) { Text("알겠어요") }
        }
    }
}
