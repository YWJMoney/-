package com.shiftmate.calendar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiftmate.calendar.ui.theme.LocalCalendarColors
import com.shiftmate.calendar.ui.theme.MemoPaletteDark
import com.shiftmate.calendar.ui.theme.MemoPaletteLight
import com.shiftmate.calendar.ui.theme.NumberStyle
import com.shiftmate.core.hours.HoursFormat
import com.shiftmate.core.model.ShiftType
import java.time.LocalTime

fun Long.asColor(): Color = Color(this.toInt())

/** Readable text color on top of [background]. */
fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF151927) else Color.White

@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun memoBackground(index: Int): Color {
    val palette = if (isDarkSurface()) MemoPaletteDark else MemoPaletteLight
    val c = palette.getOrElse(index) { Color.Unspecified }
    return if (c == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerLow else c
}

/** Rounded pill with the shift's color and label, the core visual of the calendar. */
@Composable
fun ShiftBadge(
    type: ShiftType?,
    modifier: Modifier = Modifier,
    text: String? = null,
    size: Dp = 22.dp,
    outlined: Boolean = false,
) {
    val color = type?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.outline
    val label = text ?: type?.label ?: "휴"
    val shape = RoundedCornerShape(size / 3)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (outlined) Modifier.border(1.5.dp, color, shape)
                else Modifier.background(color, shape),
            )
            .widthIn(min = size)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (outlined) color else contentColorOn(color),
            fontSize = (size.value * 0.52f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Card section with a title, used on detail and settings screens. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.padding(top = 10.dp))
            }
            content()
        }
    }
}

/** Number tile for hour totals. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cal = LocalCalendarColors.current
    val bg = if (warning) cal.warningContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (warning) cal.warning.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (warning) {
                    Icon(Icons.Filled.WarningAmber, null, tint = cal.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                // Three tiles share the narrow folded screen, so text shrinks instead of being cut off.
                BasicText(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (warning) cal.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 2,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp),
                )
            }
            BasicText(
                value,
                style = MaterialTheme.typography.titleLarge.merge(NumberStyle).copy(
                    color = if (warning) cal.warning else MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp),
            )
            if (caption != null) {
                BasicText(
                    caption,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp),
                )
            }
        }
    }
}

@Composable
fun WarningBanner(title: String, message: String, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cal.warningContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = cal.warning)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cal.warning)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** − value + control for durations in 30-minute steps. Tapping the value allows exact input. */
@Composable
fun MinutesStepper(
    minutes: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 30,
    max: Int = 24 * 60,
    dialogTitle: String = "시간 입력",
) {
    var editing by remember { mutableStateOf(false) }
    // Shown value runs ahead of the database so quick repeated taps all count.
    var shown by remember(minutes) { mutableStateOf(minutes) }
    val set: (Int) -> Unit = { v ->
        shown = v
        onChange(v)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { set((shown - step).coerceAtLeast(0)) }, enabled = shown > 0) {
            Icon(Icons.Filled.Remove, contentDescription = "줄이기")
        }
        Text(
            HoursFormat.korean(shown),
            style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 64.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { editing = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
        FilledTonalIconButton(onClick = { set((shown + step).coerceAtMost(max)) }, enabled = shown < max) {
            Icon(Icons.Filled.Add, contentDescription = "늘리기")
        }
    }
    if (editing) {
        DurationDialog(
            title = dialogTitle,
            initialMinutes = shown,
            maxMinutes = max,
            onDismiss = { editing = false },
            onConfirm = {
                set(it)
                editing = false
            },
        )
    }
}

@Composable
fun DurationDialog(
    title: String,
    initialMinutes: Int,
    maxMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hours by remember { mutableStateOf((initialMinutes / 60).toString()) }
    var mins by remember { mutableStateOf((initialMinutes % 60).toString()) }
    val h = hours.toIntOrNull()
    val m = mins.toIntOrNull()
    val total = if (h != null && m != null && h >= 0 && m in 0..59) h * 60 + m else null
    val valid = total != null && total <= maxMinutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hours,
                    onValueChange = { v -> hours = v.filter(Char::isDigit).take(2) },
                    label = { Text("시간") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = mins,
                    onValueChange = { v -> mins = v.filter(Char::isDigit).take(2) },
                    label = { Text("분") },
                    singleLine = true,
                    isError = m != null && m > 59,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { total?.let(onConfirm) }, enabled = valid) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
