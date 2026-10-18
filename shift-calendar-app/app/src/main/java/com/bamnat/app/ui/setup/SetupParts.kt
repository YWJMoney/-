package com.bamnat.app.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.model.ShiftType
import java.time.LocalDate

/** The question of a step and a line on how to answer it. */
@Composable
internal fun StepHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One answer as a large card: a tap answers and, where nothing else is asked, moves on. */
@Composable
internal fun ChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Surface(shape = CircleShape, color = scheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = scheme.onSecondaryContainer) }
                }
            }
            Column(Modifier.padding(start = if (icon != null) 14.dp else 0.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                extra?.invoke()
            }
            if (selected) Icon(Icons.Filled.CheckCircle, "고름", tint = scheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** An order of shifts as badges, wrapping on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeStrip(steps: List<Long?>, types: Map<Long, ShiftType>, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { id ->
            val t = id?.let(types::get)
            ShiftBadge(t, size = size, text = t?.label ?: "휴", outlined = t == null || t.isOff)
        }
    }
}

/** 첫째, 둘째 … 열째, then 11번째. */
internal fun ordinal(n: Int): String = ORDINALS.getOrNull(n - 1) ?: "${n}번째"

private val ORDINALS = listOf("첫째", "둘째", "셋째", "넷째", "다섯째", "여섯째", "일곱째", "여덟째", "아홉째", "열째")

/** "오늘", "내일", "모레", else "10/21(수)". */
internal fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "오늘"
    today.plusDays(1) -> "내일"
    today.plusDays(2) -> "모레"
    today.minusDays(1) -> "어제"
    else -> "${Fmt.shortDate(date)}(${Fmt.dayOfWeekShort(date.dayOfWeek)})"
}

/** A shift's name for a step, "휴무" for a plain day off. */
internal fun nameOf(id: Long?, types: Map<Long, ShiftType>): String = id?.let(types::get)?.name ?: "휴무"

/** "07:00~19:00", "쉬는 날" for a day off. */
internal fun hoursOf(t: ShiftType?): String =
    if (t == null || !t.hasTimes) "쉬는 날" else "${Fmt.time(t.start)}~${Fmt.time(t.end)}"
