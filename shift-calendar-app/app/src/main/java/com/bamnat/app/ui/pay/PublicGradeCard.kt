package com.bamnat.app.ui.pay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PublicGrade
import com.bamnat.core.pay.PublicPayTable
import java.time.LocalDate

/**
 * [JOB-02][JOB-03] 공무원 봉급표 (순번 270): 계급·호봉·근무 연수를 고르면 봉급, 시간외·야간·휴일 수당 단가,
 * 정근수당·가산금·명절휴가비가 규정대로 채워져요. Shown on 급여 only for 공무원·경찰·소방, so nobody else sees it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PublicGradeCard(profile: PayProfile, month: LocalDate, enabled: Boolean, onApply: (PublicGrade) -> Unit) {
    val series = when (profile.occupation) {
        Occupation.POLICE -> PublicPayTable.Series.POLICE
        Occupation.FIRE -> PublicPayTable.Series.FIRE
        else -> PublicPayTable.Series.GENERAL
    }
    val grades = PublicPayTable.grades(series)
    val saved = profile.publicGrade?.takeIf { g -> grades.any { it.id == g.gradeId } }
    var gradeId by remember(saved, series) { mutableStateOf(saved?.gradeId ?: grades.first().id) }
    var step by remember(saved, series) { mutableIntStateOf(saved?.step ?: 1) }
    var years by remember(saved) { mutableIntStateOf(PublicPayTable.years(saved?.serviceStart, month)) }
    var onSite by remember(saved) { mutableStateOf(saved?.onSite ?: true) }
    val grade = PublicPayTable.grade(gradeId) ?: grades.first()
    val maxStep = PublicPayTable.maxStep(grade, month)
    if (step > maxStep) step = maxStep
    val salary = PublicPayTable.salary(grade, step, month)

    SectionCard(title = "공무원 봉급표") {
        Text("계급과 호봉을 고르면 봉급과 수당 단가를 규정대로 채워요. 명세서와 다르면 아래 '내 급여 값'에서 고칠 수 있어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            grades.forEach { g ->
                FilterChip(selected = g.id == gradeId, onClick = { gradeId = g.id }, enabled = enabled, label = { Text(g.name) })
            }
        }
        Stepper("호봉", "${step}호봉", step, 1..maxStep, enabled) { step = it }
        Stepper("근무 연수", "${years}년", years, 0..40, enabled) { years = it }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { onSite = !onSite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("교대·현업 근무", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = onSite, onCheckedChange = { onSite = it }, enabled = enabled)
        }
        Text(
            if (onSite) "현업은 시간외를 근무한 시간 그대로 세고 월 57시간 상한을 두지 않아요 (제15조 제4항 단서·제5항)."
            else "평일은 하루 1시간을 빼고 세고, 시간외는 한 달 57시간까지 줘요 (제15조 제4항·제5항).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        salary?.let {
            Text("${grade.name} ${step}호봉 봉급 ${won(it)} · 정근수당 ${(PublicPayTable.longevityRate(years) * 100).toInt()}% (1·7월)",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        }
        Text("근거: ${PublicPayTable.table(month).source}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { onApply(PublicGrade(grade.id, step, month.withDayOfMonth(1).minusYears(years.toLong()), onSite)) },
            enabled = enabled && salary != null,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(if (saved == null) "봉급표로 채우기" else "다시 채우기") }
    }
}

@Composable
private fun Stepper(label: String, value: String, current: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    // [UX-08] The app's one stepper; [value] is how the current number reads.
    com.bamnat.app.ui.components.StepperRow(label) {
        com.bamnat.app.ui.components.CountStepper(current, onChange, name = label, range = range, enabled = enabled,
            format = { if (it == current) value else it.toString() })
    }
}
