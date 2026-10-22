package com.bamnat.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.components.MinutesStepper
import kotlinx.coroutines.delay

/** [SET-01][VIS-02] Every row is at least this tall, so it is easy to hit. */
private val ROW_MIN_HEIGHT = 56.dp

/**
 * [UX-04] The row a search result or another screen pointed at. A new object each time, so pointing at
 * the same row twice scrolls to it again.
 */
internal class SettingsFocus(val row: String)

/** [UX-04] The row of the open group to scroll to and light up, if any. */
internal val LocalSettingsFocus = compositionLocalOf<SettingsFocus?> { null }

/**
 * [UX-04] Marks the row named [row]: when settings was opened for it, the group scrolls it into view
 * once the page has slid in, and a soft band stays behind it so the eye lands on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.focusMark(row: String): Modifier {
    val focus = LocalSettingsFocus.current
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val pointed = focus?.row == row
    LaunchedEffect(focus) {
        if (!pointed) { glow.snapTo(0f); return@LaunchedEffect }
        delay(FOCUS_DELAY_MILLIS)
        requester.bringIntoView()
        glow.animateTo(1f, tween(400))
    }
    val band = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            val a = glow.value
            if (a <= 0f) return@drawBehind
            val out = 8.dp.toPx()
            val corner = CornerRadius(12.dp.toPx())
            drawRoundRect(band.copy(alpha = 0.10f * a), topLeft = Offset(-out, 0f),
                size = Size(size.width + out * 2, size.height), cornerRadius = corner)
            drawRoundRect(band.copy(alpha = a), topLeft = Offset(-out, size.height * 0.2f),
                size = Size(4.dp.toPx(), size.height * 0.6f), cornerRadius = CornerRadius(2.dp.toPx()))
        }
}

/** The group page slides in for 250 ms; the row is scrolled to after that. */
private const val FOCUS_DELAY_MILLIS = 320L

/**
 * [SET-01][VIS-02] A setting that is on or off. The whole row toggles and is read once, as a switch;
 * the text keeps a gap from the switch. [warning] says why it will not work as set (a channel the
 * system turned off), with a way to fix it.
 */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    warning: String? = null,
    fixLabel: String = "켜기",
    onFix: (() -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.focusMark(title).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (warning != null) WarningLine(warning, fixLabel, onFix)
    }
}

/** A line under a setting that says it will not work as set, with one button that fixes it. */
@Composable
internal fun WarningLine(text: String, fixLabel: String, onFix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        if (onFix != null) TextButton(onClick = onFix) { Text(fixLabel) }
    }
}

/** A row that opens a screen or does one thing. */
@Composable
internal fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .focusMark(title)
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** [SET-01] One choice out of a few, as chips under a title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(title: String, subtitle: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.focusMark(title).fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            for ((value, label) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

/** A permission or system setting the alarm needs: a check when it is fine, a fix button when not. */
@Composable
internal fun PermissionRow(ok: Boolean, title: String, fixLabel: String, onFix: () -> Unit) {
    Row(
        Modifier.focusMark(title).fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (ok) "허용됨" else "허용 안 됨",
            tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text(fixLabel) }
    }
}

/** An hour limit: the title above, the stepper under it, so a narrow cover screen keeps both readable. */
@Composable
internal fun LimitRow(title: String, subtitle: String, hours: Int, onChange: (Int) -> Unit) {
    Column(Modifier.focusMark(title).fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MinutesStepper(
            minutes = hours * 60,
            onChange = { m -> onChange((m / 60).coerceIn(1, 168)) },
            step = 60,
            max = 168 * 60,
            dialogTitle = title,
            modifier = Modifier.widthIn(min = 0.dp).padding(top = 4.dp),
        )
    }
}

/** [SET-01] A group on the settings list: what it holds, and how it stands now. [badge]: new answers, counted. */
@Composable
internal fun GroupRow(icon: ImageVector, title: String, summary: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (selected) colors.surface else colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (badge > 0) {
                    Spacer(Modifier.width(8.dp))
                    // [UX-05] Read as words, not as a bare number.
                    Badge(Modifier.clearAndSetSemantics { contentDescription = "새 답 ${badge}개" }) {
                        Text(if (badge > 9) "9+" else "$badge")
                    }
                }
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.outline)
    }
}

/** Small print under a section: what a setting does, where it comes from. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
