package com.bamnat.app.ui.money

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.core.money.MoneyEntry

/**
 * [COMN-04] A line in 확인 대기함: swiped right it is confirmed and counts from then on, swiped left
 * it is thrown away (with undo). Tapped it opens to fix, and saving it confirms it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingRow(e: MoneyEntry, onOpen: () -> Unit, onConfirm: () -> Unit, onDelete: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onConfirm() }
            SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(HapticFeedbackType.Reject); onDelete() }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val tone = if (toRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            val bg by animateColorAsState(
                if (state.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent else tone.copy(alpha = 0.3f),
                label = "pending",
            )
            Row(Modifier.fillMaxSize().background(bg).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End) {
                if (toRight) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text("확인", style = MaterialTheme.typography.labelLarge)
                } else if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Text("지우기", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Delete, null)
                }
            }
        },
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) { EntryRow(e, showDate = true, onClick = onOpen) }
            IconButton(onClick = onConfirm) { Icon(Icons.Filled.Check, "확인", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** [COMN-04] The switch in 자동 기록: 확인하고 적기 (off is 바로 적기, as it always was). */
@Composable
internal fun ConfirmFirstControl() {
    val c = LocalContext.current.appContainer
    val prefs by c.autoRecordPrefs.state.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(top = 8.dp).toggleable(prefs.confirmFirst, role = Role.Switch) { c.autoRecordPrefs.setConfirmFirst(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("확인하고 적기", style = MaterialTheme.typography.titleSmall)
            Text(
                if (prefs.confirmFirst) "알림으로 들어온 결제는 확인 대기함에 쌓이고, 확인한 것만 합계에 들어가요."
                else "지금은 바로 적기예요. 알림으로 들어온 결제가 곧바로 합계에 들어가요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = prefs.confirmFirst, onCheckedChange = null)
    }
}
