package com.bamnat.app.ui.components

import com.bamnat.core.model.ShiftIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.MemoPaletteDark
import com.bamnat.app.ui.theme.MemoPaletteLight
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ShiftType
import java.time.LocalTime

fun Long.asColor(): Color = Color(this.toInt())

/**
 * [SCALE-01] The width of a label column beside its value ("6/12 (토)", "기본급"), grown with the phone's
 * text size so the label does not break into a tower at 150%, but never past [MAX_LABEL_SHARE] of the row,
 * so the value beside it keeps its room on a small phone.
 */
fun Modifier.labelWidth(width: Dp): Modifier = layout { measurable, constraints ->
    val grown = (width * fontScale.coerceIn(1f, MAX_LABEL_GROWTH)).roundToPx()
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * MAX_LABEL_SHARE).toInt() else grown
    val w = maxOf(width.roundToPx(), minOf(grown, cap)).coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private const val MAX_LABEL_GROWTH = 1.7f
private const val MAX_LABEL_SHARE = 0.42f

private val DarkInk = Color(0xFF151927)

/**
 * [VIS-01] Readable text color on top of [background]: whichever of white and the app's dark ink
 * has the higher contrast (WCAG), so 오후 #F2994A or 대근 #0E9F6E get dark letters, not faint white.
 */
fun contentColorOn(background: Color): Color {
    val l = background.luminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onDark = (l + 0.05f) / (DarkInk.luminance() + 0.05f)
    return if (onDark > onWhite) DarkInk else Color.White
}

/** [DAY-02] Inside the day's groups sections drop their own card, so a group never holds a box in a box. */
val LocalFlatSections = androidx.compose.runtime.staticCompositionLocalOf { false }

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
    showIcon: Boolean = true,
) {
    val color = type?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.outline
    val label = text ?: type?.label ?: "휴"
    val shape = RoundedCornerShape(size / 3)
    // The icon only goes with the type's own label, not with stand-in text like "휴" [FEAT-01].
    val icon = if (showIcon && (text == null || text == type?.label)) ShiftIcon.of(type?.icon) else null
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
        val fg = if (outlined) color else contentColorOn(color)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon.vector, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.5f))
                Spacer(Modifier.width(1.dp))
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val textSize = size * 0.52f
            Text(
                text = label,
                color = fg,
                // [VIS-01] Sized to the badge, so a big system font cannot push it out of the cell.
                fontSize = with(density) { textSize.toSp() },
                // [VIS-02] The line too: the theme's 24sp line grew with the font and made the badge taller.
                // Counted from the text itself (em), as Android 14 scales big sp less than small ones.
                lineHeight = (BADGE_LINE / textSize).em,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The badge's line, as tall as the theme's body line at the normal font size. */
private val BADGE_LINE = 24.dp

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
    if (LocalFlatSections.current) {
        Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.padding(top = 4.dp))
            }
            content()
        }
        return
    }
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
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).semantics { heading() },
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
fun WarningBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    /** [SLP-15] One button that fixes it, at the end of the banner. */
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val cal = LocalCalendarColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cal.warningContainer)
            .padding(start = 14.dp, end = if (action != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = cal.warning)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cal.warning)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/**
 * − value + control for durations. [UX-01] The value sits in a fixed-width box, so 1시간 and
 * 1시간 30분 never move the buttons; holding − or + keeps stepping. Tapping the value allows exact
 * input to the minute.
 */
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
    var shown by remember { mutableStateOf(minutes) }
    // [STAB-01] Steps are written once the taps pause, not on every step of a hold (eleven a second):
    // each write redrew the calendar and recalculated pay until taps stopped answering.
    var unsaved by remember { mutableStateOf<Int?>(null) }
    val save by androidx.compose.runtime.rememberUpdatedState(onChange)
    androidx.compose.runtime.LaunchedEffect(minutes) { if (unsaved == null) shown = minutes }
    androidx.compose.runtime.LaunchedEffect(unsaved) {
        val v = unsaved ?: return@LaunchedEffect
        kotlinx.coroutines.delay(STEPPER_SAVE_DELAY_MILLIS)
        unsaved = null
        save(v)
    }
    // Leaving the screen right after a step still keeps it.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { unsaved?.let { save(it) } } }
    val set: (Int) -> Unit = { v ->
        shown = v
        unsaved = v
    }
    val haptics = LocalHapticFeedback.current
    // [VIS-02] A light tick per step, and a firmer one where it cannot go further.
    val stepTo: (Int) -> Unit = { v ->
        haptics.performHapticFeedback(if (v == 0 || v == max) HapticFeedbackType.Reject else HapticFeedbackType.SegmentFrequentTick)
        set(v)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(onStep = { stepTo((shown - step).coerceAtLeast(0)) }, enabled = shown > 0) {
            Icon(Icons.Filled.Remove, contentDescription = "$dialogTitle ${HoursFormat.korean(step)} 줄이기")
        }
        val style = MaterialTheme.typography.titleMedium.merge(NumberStyle)
        Box(
            Modifier
                .width(STEPPER_VALUE_WIDTH)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = dialogTitle) { editing = true }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                HoursFormat.korean(shown),
                style = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
            )
        }
        RepeatIconButton(onStep = { stepTo((shown + step).coerceAtMost(max)) }, enabled = shown < max) {
            Icon(Icons.Filled.Add, contentDescription = "$dialogTitle ${HoursFormat.korean(step)} 늘리기")
        }
    }
    if (editing) {
        DurationDialog(
            title = dialogTitle,
            initialMinutes = shown,
            maxMinutes = max,
            onDismiss = { editing = false },
            onConfirm = {
                shown = it
                unsaved = null
                onChange(it)
                editing = false
            },
        )
    }
}

/** Same width for every value, "0분" to "23시간 59분". */
private val STEPPER_VALUE_WIDTH = 104.dp

private const val STEPPER_SAVE_DELAY_MILLIS = 350L

/** A − or + button that steps once on a tap and keeps stepping while held. */
@Composable
private fun RepeatIconButton(onStep: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A hold already stepped; the release must not add one more.
    var repeated by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        kotlinx.coroutines.delay(450)
        while (true) {
            repeated = true
            step()
            kotlinx.coroutines.delay(90)
        }
    }
    FilledTonalIconButton(
        onClick = { if (!repeated) step(); repeated = false },
        enabled = enabled,
        interactionSource = interaction,
    ) { content() }
}

/** [TIME-01] Hours and minutes on the same up-and-down wheels as clock times, instead of typing. */
@Composable
fun DurationDialog(
    title: String,
    initialMinutes: Int,
    maxMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val start = initialMinutes.coerceIn(0, maxMinutes)
    var hours by remember { mutableStateOf(start / 60) }
    var mins by remember { mutableStateOf(start % 60) }
    val total = hours * 60 + mins
    val valid = total <= maxMinutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (valid) HoursFormat.korean(total) else "${HoursFormat.korean(maxMinutes)}까지 정할 수 있어요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Box(Modifier.fillMaxWidth().height(WHEEL_ROW_HEIGHT * 5), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ROW_HEIGHT)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WheelHalf(count = maxMinutes / 60 + 1, value = hours, jump = 0, label = "시간", onValue = { hours = it }, cyclic = false)
                        WheelHalf(count = 60, value = mins, jump = 0, label = "분", onValue = { mins = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(total) }, enabled = valid) { Text("확인") }
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
