package com.bamnat.app.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.NumberStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Small shared parts of the work tools [HRLY-09][HRLY-10][HRLY-11][FLD-11][SHOP-11]: a full-screen page
 * opened over the tab, a row that opens a tool, number and date fields, and a label–value line.
 */

/** A full-screen page with a back arrow and an optional action, scrolling its content above the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolScreen(
    title: String,
    onClose: () -> Unit,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") } },
                        actions = { if (action != null) TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) } },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

/** A row in a tools card: title, one line of what it does, and an arrow. */
@Composable
internal fun ToolLink(title: String, text: String, onClick: () -> Unit, accent: Color? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A number typed as text: whole numbers, or with [decimal] one point. */
@Composable
internal fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = false,
    maxLength: Int = 6,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            val cleaned = v.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') }
            val oneDot = cleaned.indexOf('.').let { i -> if (i < 0) cleaned else cleaned.substring(0, i + 1) + cleaned.substring(i + 1).replace(".", "") }
            onChange(oneDot.take(maxLength))
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** "12.5" → 12.5, a blank or odd value → 0. */
internal fun String.toNumber(): Double = toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

/** 12.0 → "12", 12.5 → "12.5", 0 → "". */
internal fun Double.asField(): String = when {
    this <= 0 -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

/** A date chosen with the system date picker; [clearable] adds 지우기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, clearable: Boolean = true, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date?.let { Fmt.fullDate(it) } ?: "정하지 않음", style = MaterialTheme.typography.bodyLarge)
        }
        if (clearable && date != null) TextButton(onClick = { onChange(null) }) { Text("지우기") }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } },
        ) { DatePicker(state = state) }
    }
}

/** A switch with its words, the whole row tappable. */
@Composable
internal fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit, text: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A label on the left and its value on the right. */
@Composable
internal fun ValueLine(label: String, value: String, strong: Boolean = false, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        // [A11Y-03] With large text the label fills its share, so the value keeps a gap instead of touching it.
        Spacer(Modifier.width(8.dp))
        Text(value, style = (if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium).merge(NumberStyle),
            color = color ?: Color.Unspecified)
    }
}

/** Small grey words under a section. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
