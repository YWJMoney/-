package com.bamnat.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * [CAL-50] 상-4: what the calendar has to say above the month (a question, an alarm that will not ring, records to
 * check, 공휴일 근무, the month's recap, a tip, what the app did today …) shows one card at a time, so the month is on
 * the first screen even on a 320×568 phone or a phone on its side. The rest fold into one line, "알림 2개 더 보기",
 * that opens them all.
 *
 * Every notice is composed, so each keeps deciding for itself whether it has something to say (most read their
 * own stores); one that draws nothing is 0 high and is not counted. The ones folded away are measured but not
 * placed, and their words leave the accessibility tree. A notice that records being seen (a tip, a question)
 * reads [LocalNoticeFolded] so a folded one is not counted as shown.
 */
@Composable
internal fun NoticeStack(
    notices: List<Notice>,
    modifier: Modifier = Modifier,
    /** How many may be open at once: 1, or 0 on a phone on its side where only the line fits. */
    open: Int = 1,
    state: NoticeStackState = rememberNoticeStackState(),
) {
    val present = notices.filter { (state.heights[it.key] ?: 0) > 0 }
    val shownKeys = if (state.expanded) present.map { it.key }.toSet() else present.take(open).map { it.key }.toSet()
    val folded = present.filter { it.key !in shownKeys }
    Column(modifier.fillMaxWidth()) {
        notices.forEach { n ->
            val shown = n.key in shownKeys || (state.heights[n.key] ?: 0) == 0 && present.size < open
            androidx.compose.runtime.key(n.key) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            if (state.heights[n.key] != p.height) state.heights[n.key] = p.height
                            if (shown) layout(p.width, p.height) { p.place(0, 0) } else layout(p.width, 0) {}
                        }
                        .then(if (shown) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    CompositionLocalProvider(LocalNoticeFolded provides !shown) { n.content() }
                }
            }
        }
        if (folded.isNotEmpty() || state.expanded && present.size > open) {
            FoldLine(
                count = folded.size,
                first = folded.firstNotNullOfOrNull { it.label },
                expanded = state.expanded,
                onToggle = { state.expanded = !state.expanded },
            )
        }
    }
}

/** [CAL-50] One notice of a [NoticeStack]: its place in the order is its priority; [label] names it on the folded line. */
internal class Notice(val key: String, val label: String? = null, val content: @Composable () -> Unit)

/** [CAL-50] How high each notice drew itself, and whether the user opened them all. */
internal class NoticeStackState(expanded: Boolean) {
    val heights = mutableStateMapOf<String, Int>()
    var expanded by mutableStateOf(expanded)
    /** Notices with something to say right now. */
    val count: Int get() = heights.values.count { it > 0 }
}

@Composable
internal fun rememberNoticeStackState(): NoticeStackState =
    rememberSaveable(saver = androidx.compose.runtime.saveable.Saver({ it.expanded }, { NoticeStackState(it) })) { NoticeStackState(false) }

/** [CAL-50] True inside a notice folded away by [NoticeStack]: it is measured, not seen. */
internal val LocalNoticeFolded = compositionLocalOf { false }

@Composable
private fun FoldLine(count: Int, first: String?, expanded: Boolean, onToggle: () -> Unit) {
    val text = if (expanded) "알림 접기" else "알림 ${count}개 더 보기"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "접기" else "펼치기", onClick = onToggle)
            .padding(horizontal = 20.dp)
            .semantics {
                role = Role.Button
                contentDescription = if (expanded) text else text + (first?.let { ", $it" } ?: "")
                stateDescription = if (expanded) "펼쳐짐" else "접힘"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        // [VIS-04] At large text the first folded notice's name goes under the count instead of being squeezed beside it.
        val stack = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
        val name: @Composable (Modifier) -> Unit = { m ->
            if (!expanded && first != null) Text(
                if (stack) first else " · $first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (stack) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = m,
            )
        }
        if (stack) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                name(Modifier)
            }
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            if (!expanded && first != null) name(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        }
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
