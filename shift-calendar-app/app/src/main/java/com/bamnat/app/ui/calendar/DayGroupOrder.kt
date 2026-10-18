package com.bamnat.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** [DAY-02][DAY-04] The day view's groups, by the key kept in the user's order. */
internal object DayGroupKey {
    const val WORK = "work"
    const val EXTRA = "extra"
    const val LEAVE = "leave"
    const val ALARM = "alarm"
    const val MEMO = "memo"
    const val MONEY = "money"
    const val BODY = "body"
    const val RECORD = "record"

    private val SHIFT_ORDER = listOf(WORK, EXTRA, LEAVE, ALARM, MEMO, MONEY, BODY, RECORD)
    /** [WORK-02][WORK-03] Field and shop work open on the day's money. */
    private val MONEY_ORDER = listOf(MONEY, MEMO, WORK, EXTRA, LEAVE, ALARM, BODY, RECORD)

    fun defaultOrder(moneyFirst: Boolean): List<String> = if (moneyFirst) MONEY_ORDER else SHIFT_ORDER

    /**
     * [DAY-04] The user's order first, then any group it does not name (one added in a later version, say)
     * where the default puts it. Unknown keys are dropped, so an old or edited order never hides a group.
     */
    fun order(custom: List<String>, moneyFirst: Boolean): List<String> {
        val all = defaultOrder(moneyFirst)
        val mine = custom.distinct().filter { it in all }
        return mine + all.filter { it !in mine }
    }

    fun label(key: String): String = when (key) {
        WORK -> "근무"
        EXTRA -> "추가 근무"
        LEAVE -> "휴가"
        ALARM -> "알람"
        MEMO -> "메모"
        MONEY -> "돈"
        BODY -> "몸"
        else -> "기록"
    }

    fun icon(key: String): ImageVector = when (key) {
        WORK -> Icons.Outlined.Work
        EXTRA -> Icons.Outlined.MoreTime
        LEAVE -> Icons.Outlined.BeachAccess
        ALARM -> Icons.Outlined.Alarm
        MEMO -> Icons.AutoMirrored.Outlined.StickyNote2
        MONEY -> Icons.Outlined.Payments
        BODY -> Icons.Outlined.Bedtime
        else -> Icons.Outlined.History
    }
}

/** [DAY-04] Moves the group at [from] one place up ([by] = -1) or down (+1); out of range leaves it as is. */
internal fun List<String>.moved(from: Int, by: Int): List<String> {
    val to = from + by
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * [DAY-04] The day view's groups in the user's order: each row moves up or down with its arrows (and with
 * TalkBack's actions), and the change shows at once behind the dialog. 기본 순서 goes back to the order
 * that fits the work style.
 */
@Composable
fun DayGroupOrderDialog(
    custom: List<String>,
    moneyFirst: Boolean,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = DayGroupKey.order(custom, moneyFirst)
    val haptics = LocalHapticFeedback.current
    val move: (Int, Int) -> Unit = { i, by ->
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onChange(order.moved(i, by))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("하루 화면 순서") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "날짜를 누르면 나오는 묶음의 순서예요. 자주 보는 것을 위로 올려 두세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                order.forEachIndexed { i, key ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    OrderRow(
                        index = i,
                        count = order.size,
                        label = DayGroupKey.label(key),
                        icon = DayGroupKey.icon(key),
                        onUp = { move(i, -1) },
                        onDown = { move(i, 1) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
        dismissButton = {
            TextButton(onClick = { onChange(emptyList()) }, enabled = custom.isNotEmpty()) { Text("기본 순서") }
        },
    )
}

@Composable
private fun OrderRow(index: Int, count: Int, label: String, icon: ImageVector, onUp: () -> Unit, onDown: () -> Unit) {
    val first = index == 0
    val last = index == count - 1
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // One stop for TalkBack: "2번째, 추가 근무", with 위로·아래로 as actions.
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    CustomAccessibilityAction("위로") { onUp(); true }.takeIf { !first },
                    CustomAccessibilityAction("아래로") { onDown(); true }.takeIf { !last },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "$label 위로") }
        IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "$label 아래로") }
    }
}
