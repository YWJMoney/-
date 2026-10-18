package com.bamnat.app.ui.work

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bamnat.core.work.WorkStyle

fun WorkStyle.icon(): ImageVector = when (this) {
    WorkStyle.SHIFT -> Icons.Filled.Autorenew
    WorkStyle.DAY -> Icons.Filled.WorkOutline
    WorkStyle.HOURLY -> Icons.Filled.AccessTime
    WorkStyle.FIELD -> Icons.Filled.Construction
    WorkStyle.BUSINESS -> Icons.Filled.Storefront
}

/**
 * What happens right after picking, shown under each choice so the next screen is no surprise.
 * [company]: the build has a company server, so shift and day work start from 회사 찾기 [COMM-01].
 */
fun WorkStyle.nextStep(company: Boolean = false): String = when (this) {
    WorkStyle.SHIFT -> if (company) "다음: 회사 찾기, 없으면 근무 순서 정하기" else "다음: 근무 순서(패턴) 정하기"
    WorkStyle.DAY -> if (company) "다음: 회사 찾기, 없으면 일하는 요일 정하기" else "다음: 일하는 요일 정하기"
    WorkStyle.HOURLY -> "다음: 시급 정하기"
    WorkStyle.FIELD -> "달력에 일감과 받을 돈을 적어요"
    WorkStyle.BUSINESS -> "달력에 매일 매출과 경비를 적어요"
}

/** [WORK-07] Said after the kind of work changed in settings with nothing more to set up. */
fun WorkStyle.changedMessage(): String = when (this) {
    WorkStyle.SHIFT -> "교대 근무로 바꿨어요. 근무 순서와 급여 설정은 그대로예요"
    WorkStyle.DAY -> "평일 근무로 바꿨어요. 일하는 요일과 급여 설정은 그대로예요"
    WorkStyle.HOURLY -> "시급 근무로 바꿨어요. 일한 날 달력에 시간을 적으면 급여가 계산돼요"
    WorkStyle.FIELD -> "현장·프리랜서로 바꿨어요. 달력에서 날짜를 눌러 일감과 받을 돈을 적어요"
    WorkStyle.BUSINESS -> "자영업으로 바꿨어요. 달력에서 날짜를 눌러 그날 매출과 경비를 적어요"
}

/**
 * [WORK-01] First launch: how do you work? The answer decides the first setup screen and what
 * the calendar and money tab show; it can be changed in settings at any time.
 */
@Composable
fun WorkStyleScreen(current: WorkStyle?, onPick: (WorkStyle) -> Unit, title: String = "어떻게 일하세요?", company: Boolean = false) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "고른 형태에 맞춰 달력과 돈 관리가 바뀌어요. 설정에서 언제든 바꿀 수 있고, 적어 둔 기록은 그대로 남아요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // Changing the kind of work later keeps the setup, so only the first run mentions 회사 찾기.
                WorkStyle.entries.forEach { style -> StyleCard(style, selected = style == current, company = company && current == null) { onPick(style) } }
            }
        }
    }
}

@Composable
private fun StyleCard(style: WorkStyle, selected: Boolean, company: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = scheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(style.icon(), null, tint = scheme.onSecondaryContainer) }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(style.label, style = MaterialTheme.typography.titleMedium)
                Text(style.description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Text("예: ${style.examples}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(style.nextStep(company), style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
