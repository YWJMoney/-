package com.bamnat.app.ui.components

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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch
import java.time.LocalTime

internal val WHEEL_ROW_HEIGHT = 44.dp
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
                Box(Modifier.fillMaxWidth().height(WHEEL_ROW_HEIGHT * VISIBLE_ITEMS), contentAlignment = Alignment.Center) {
                    // Band behind the selected row.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ROW_HEIGHT)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WheelHalf(count = 24, value = hour, jump = jump, label = "시", onValue = { hour = it })
                        WheelHalf(count = 60, value = minute, jump = jump, label = "분", onValue = { minute = it })
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

// [SET-04] With the hospital's 15·23시 and the 12-hour shifts' 19시 (ux-review 1 P31); the row scrolls.
val DEFAULT_PRESETS: List<LocalTime> = listOf(6, 7, 8, 9, 14, 15, 18, 19, 20, 22, 23).map { LocalTime.of(it, 0) }

/**
 * One wheel with its unit, taking an equal share of the row. [TIME-02] The whole share turns the
 * wheel, not only the digits, so a thumb anywhere over 시 or 분 works; the share ends where the next
 * wheel's begins, so a swipe never turns the neighbour.
 */
@Composable
internal fun RowScope.WheelHalf(
    count: Int,
    value: Int,
    jump: Int,
    label: String,
    onValue: (Int) -> Unit,
    cyclic: Boolean = true,
) {
    Box(Modifier.weight(1f).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Wheel(count, value, jump, label, onValue, cyclic, Modifier.fillMaxWidth())
        // The unit sits right of the centred digits. An invisible copy on the left keeps the digits
        // centred over the wheel. Plain text takes no touches, so swipes over it still turn the wheel.
        Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            WheelUnitText(label, Modifier.alpha(0f))
            Text("00", style = WheelDigits, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(0f))
            WheelUnitText(label)
        }
    }
}

@Composable
private fun WheelUnitText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

private val WheelDigits: TextStyle @Composable get() = MaterialTheme.typography.headlineSmall.merge(NumberStyle)

/**
 * One column of numbers that turns up and down. [TIME-01] The rows above and below the chosen one
 * stay readable (at 3시 you see 2 above and 4 below), fading further out, so you can tell which way
 * to turn. A [cyclic] wheel runs past the end back to the start, like a clock; a plain one stops.
 */
@Composable
internal fun Wheel(
    count: Int,
    value: Int,
    jump: Int,
    label: String,
    onValue: (Int) -> Unit,
    cyclic: Boolean = true,
    modifier: Modifier = Modifier.width(64.dp),
) {
    val middle = if (cyclic) count * LOOPS / 2 else 0
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
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        var first = true
        snapshotFlow { selected }.collect {
            onValue(Math.floorMod(it, count))
            // [VIS-02] A light tick for each number that passes the middle, like a real wheel.
            if (!first) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            first = false
        }
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
        modifier = modifier
            .height(WHEEL_ROW_HEIGHT * VISIBLE_ITEMS)
            .semantics {
                contentDescription = "$value$label"
                // [VIS-02] TalkBack moves the wheel one step at a time, not only a page.
                val unit = if (label == "시") "1시간" else "1$label"
                val last = if (cyclic) count * LOOPS - 1 else count - 1
                customActions = listOf(
                    CustomAccessibilityAction("$unit 늘리기") { scope.launch { state.animateScrollToItem((selected + 1).coerceAtMost(last)) }; true },
                    CustomAccessibilityAction("$unit 줄이기") { scope.launch { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }; true },
                )
            },
        contentPadding = PaddingValues(vertical = WHEEL_ROW_HEIGHT * (VISIBLE_ITEMS / 2)),
    ) {
        items(if (cyclic) count * LOOPS else count) { i ->
            val v = Math.floorMod(i, count)
            val distance = kotlin.math.abs(i - selected)
            val isSelected = distance == 0
            Box(
                Modifier
                    .height(WHEEL_ROW_HEIGHT)
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) { scope.launch { state.animateScrollToItem(i) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "%02d".format(v),
                    style = WheelDigits,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = when (distance) { 0 -> 28.sp; 1 -> 22.sp; else -> 18.sp },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(when (distance) { 0 -> 1f; 1 -> 0.7f; else -> 0.3f }),
                )
            }
        }
    }
}
