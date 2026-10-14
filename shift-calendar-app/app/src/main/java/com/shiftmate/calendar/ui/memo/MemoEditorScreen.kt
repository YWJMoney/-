package com.shiftmate.calendar.ui.memo

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.ConfirmDialog
import com.shiftmate.calendar.ui.components.memoBackground
import com.shiftmate.calendar.ui.theme.MemoPaletteLight
import com.shiftmate.core.memo.Memo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoEditorScreen(vm: MemoEditorViewModel, onBack: () -> Unit) {
    val memo by vm.memo.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val savedAt by vm.savedAt.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showColors by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val background = memoBackground(memo.colorIndex)

    Scaffold(
        containerColor = background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                title = {
                    Text(
                        savedAt?.let { "저장됨 · " + savedTime(it) } ?: if (memo.id == 0L) "새 메모" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = {
                    // Nothing can be changed until an existing memo has finished loading.
                    if (!loaded) return@TopAppBar
                    IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소") }
                    IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행") }
                    IconButton(onClick = vm::togglePinned) {
                        Icon(
                            if (memo.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            if (memo.pinned) "고정 해제" else "고정",
                            tint = if (memo.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더 보기") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (memo.isChecklist) "일반 메모로 바꾸기" else "체크리스트로 바꾸기") },
                                leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                                onClick = { menu = false; vm.toggleChecklist() },
                            )
                            DropdownMenuItem(
                                text = { Text("색상") },
                                leadingIcon = { Icon(Icons.Filled.Palette, null) },
                                onClick = { menu = false; showColors = !showColors },
                            )
                            DropdownMenuItem(
                                text = { Text("공유") },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = {
                                    menu = false
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, memo.title)
                                        .putExtra(Intent.EXTRA_TEXT, shareText(memo))
                                    runCatching { context.startActivity(Intent.createChooser(send, "메모 공유")) }
                                },
                                enabled = !memo.isBlank,
                            )
                            if (memo.isChecklist && memo.checkedCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("완료 항목 지우기") },
                                    leadingIcon = { Icon(Icons.Filled.Check, null) },
                                    onClick = { menu = false; vm.clearChecked() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (showColors) {
                ColorRow(selected = memo.colorIndex, onSelect = vm::setColor)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val date = memo.date
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(if (date != null) Fmt.fullDate(date) else "날짜 붙이기") },
                    leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) },
                    trailingIcon = if (date != null) {
                        { Icon(Icons.Filled.Close, "날짜 떼기", Modifier.size(18.dp).clickable { vm.setDate(null) }) }
                    } else {
                        null
                    },
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                PlainField(
                    value = memo.title,
                    onValueChange = vm::setTitle,
                    placeholder = "제목",
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.size(8.dp))
                if (memo.isChecklist) {
                    ChecklistEditor(memo = memo, vm = vm)
                } else {
                    PlainField(
                        value = memo.body,
                        onValueChange = vm::setBody,
                        placeholder = "내용을 적어 보세요",
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = false,
                        modifier = Modifier.heightIn(min = 240.dp),
                        autoFocus = memo.id == 0L && memo.title.isEmpty(),
                    )
                }
            }
            val chars = if (memo.isChecklist) memo.checklist.sumOf { it.text.length } else memo.body.length
            Text(
                "${chars}자" + if (memo.isChecklist) " · 완료 ${memo.checkedCount}/${memo.itemCount}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (showDatePicker) {
        val initialMillis = (memo.date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        vm.setDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "메모를 삭제할까요?",
            message = "삭제한 뒤 잠시 동안 되돌릴 수 있어요.",
            confirmLabel = "삭제",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.delete(onBack)
            },
        )
    }
}

@Composable
private fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (i in MemoPaletteLight.indices) {
            val color = memoBackground(i)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (i == selected) 3.dp else 1.dp,
                        color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (i == selected) Icon(Icons.Filled.Check, "선택됨", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Default,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        textStyle = textStyle.copy(color = color),
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
                inner()
            }
        },
    )
}

/**
 * Checklist editing: Enter adds the next item, unchecked items stay on top and finished
 * ones collect under "완료".
 */
@Composable
private fun ChecklistEditor(memo: Memo, vm: MemoEditorViewModel) {
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    val open = memo.checklist.withIndex().filter { !it.value.checked }
    val done = memo.checklist.withIndex().filter { it.value.checked }

    Column {
        for ((index, item) in open) {
            ChecklistRow(
                text = item.text,
                checked = false,
                requestFocus = focusIndex == index,
                onFocusHandled = { focusIndex = null },
                onTextChange = { value ->
                    val newline = value.indexOf('\n')
                    if (newline >= 0) {
                        vm.setItemText(index, value.substring(0, newline))
                        focusIndex = vm.insertItemAfter(index, value.substring(newline + 1).replace("\n", " "))
                    } else {
                        vm.setItemText(index, value)
                    }
                },
                onCheckedChange = { vm.setItemChecked(index, it) },
                onNext = { focusIndex = vm.insertItemAfter(index) },
                onRemove = { vm.removeItem(index) },
            )
        }
        TextButton(onClick = {
            val last = memo.checklist.indexOfLast { !it.checked }
            focusIndex = vm.insertItemAfter(if (last >= 0) last else memo.checklist.size - 1)
        }) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("항목 추가")
        }
        if (done.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "완료 ${done.size}개",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            for ((index, item) in done) {
                ChecklistRow(
                    text = item.text,
                    checked = true,
                    requestFocus = false,
                    onFocusHandled = {},
                    onTextChange = { vm.setItemText(index, it.replace("\n", " ")) },
                    onCheckedChange = { vm.setItemChecked(index, it) },
                    onNext = {},
                    onRemove = { vm.removeItem(index) },
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    text: String,
    checked: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onTextChange: (String) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onRemove: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focus.requestFocus() }
            onFocusHandled()
        }
    }
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (checked) TextDecoration.LineThrough else null,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 8.dp),
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text("할 일", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = onRemove, modifier = Modifier.widthIn(max = 40.dp)) {
            Icon(Icons.Filled.Close, "항목 삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

private val savedFormat = DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)

private fun savedTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(savedFormat)

private fun shareText(memo: Memo): String = buildString {
    if (memo.title.isNotBlank()) appendLine(memo.title)
    memo.date?.let { appendLine(Fmt.fullDate(it)) }
    if (memo.title.isNotBlank() || memo.date != null) appendLine()
    if (memo.isChecklist) {
        memo.checklist.filter { it.text.isNotBlank() }.forEach { appendLine((if (it.checked) "☑ " else "☐ ") + it.text) }
    } else {
        append(memo.body)
    }
}.trimEnd()
