package com.shiftmate.calendar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiftmate.calendar.ui.theme.NumberStyle
import kotlinx.coroutines.launch
import java.time.LocalTime

private val ITEM_HEIGHT = 44.dp
private const val VISIBLE_ITEMS = 5
/** Each wheel repeats its values this many times so it can spin in both directions. */
private const val LOOPS = 200

/**
 * Picks a clock time with two thumb-friendly wheels (hours and minutes), shortcuts for common
 * shift times, and ± buttons. Replaces the clock dial, which needs precise taps.
 */
@Composable
fun TimePickerDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    presets: List<LocalTime> = DEFAULT_PRESETS,
    onConfirm: (LocalTime) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    // Bumped when a shortcut sets the time, so the wheels jump to it.
    var jump by remember { mutableIntStateOf(0) }
    val set: (LocalTime) -> Unit = { t ->
        hour = t.hour
        minute = t.minute
        jump++
    }
    val current = LocalTime.of(hour, minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${if (hour < 12) "오전" else "오후"} ${(hour % 12).let { if (it == 0) 12 else it }}시 ${minute}분",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth().height(ITEM_HEIGHT * VISIBLE_ITEMS), contentAlignment = Alignment.Center) {
                    // Band behind the selected row.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ITEM_HEIGHT)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Wheel(count = 24, value = hour, jump = jump, label = "시", onValue = { hour = it })
                        Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        Wheel(count = 60, value = minute, jump = jump, label = "분", onValue = { minute = it })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (delta in listOf(-30, -10, 10, 30)) {
                        TextButton(onClick = { set(current.plusMinutes(delta.toLong())) }) {
                            Text(if (delta > 0) "+${delta}분" else "${delta}분")
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in presets) {
                        AssistChip(onClick = { set(t) }, label = { Text("%02d:%02d".format(t.hour, t.minute)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current) }) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

val DEFAULT_PRESETS: List<LocalTime> = listOf(6, 7, 8, 9, 14, 18, 20, 22).map { LocalTime.of(it, 0) }

@Composable
private fun Wheel(count: Int, value: Int, jump: Int, label: String, onValue: (Int) -> Unit) {
    val middle = count * LOOPS / 2
    val state = rememberLazyListState(initialFirstVisibleItemIndex = middle + value)
    val scope = rememberCoroutineScope()
    // The row nearest the middle of the wheel is the chosen one.
    val selected by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.index
                ?: (middle + value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { selected }.collect { onValue(Math.floorMod(it, count)) }
    }
    // A shortcut moved the time: show it on the wheel.
    LaunchedEffect(jump) {
        if (jump > 0 && Math.floorMod(selected, count) != value) {
            val base = selected - Math.floorMod(selected, count)
            state.scrollToItem(base + value)
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
        modifier = Modifier
            .width(72.dp)
            .height(ITEM_HEIGHT * VISIBLE_ITEMS)
            .semantics { contentDescription = "$value$label" },
        contentPadding = PaddingValues(vertical = ITEM_HEIGHT * (VISIBLE_ITEMS / 2)),
    ) {
        items(count * LOOPS) { i ->
            val v = Math.floorMod(i, count)
            val isSelected = i == selected
            Box(
                Modifier
                    .height(ITEM_HEIGHT)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "%02d".format(v),
                    style = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (isSelected) 28.sp else 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(if (isSelected) 1f else 0.45f)
                        .clickable(interactionSource = null, indication = null) { scope.launch { state.animateScrollToItem(i) } },
                )
            }
        }
    }
}
