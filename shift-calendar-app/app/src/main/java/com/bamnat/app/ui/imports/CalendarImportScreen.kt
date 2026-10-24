package com.bamnat.app.ui.imports

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.imports.CalendarImporter
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.imports.CalendarImport
import com.bamnat.core.imports.CalendarImport.As
import com.bamnat.core.imports.DeviceCalendar
import com.bamnat.core.model.ShiftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.bamnat.core.time.Today

/** [IMPORT-01] The span of days to read. */
enum class ImportRange(val label: String, val back: Long, val ahead: Long) {
    MONTHS_2("이번 달·다음 달", 0, 0),
    AHEAD_3("앞으로 3개월", 0, 90),
    AROUND("지난 3개월~앞으로 3개월", 90, 90),
    ;

    fun from(today: LocalDate): LocalDate = if (this == MONTHS_2) today.withDayOfMonth(1) else today.minusDays(back)
    fun to(today: LocalDate): LocalDate = if (this == MONTHS_2) today.plusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) } else today.plusDays(ahead)
}

class CalendarImportViewModel(private val container: AppContainer) : ViewModel() {
    private val importer: CalendarImporter = container.calendarImporter

    data class Ui(
        val allowed: Boolean = false,
        val calendars: List<DeviceCalendar> = emptyList(),
        val chosen: Set<Long> = emptySet(),
        val range: ImportRange = ImportRange.MONTHS_2,
        val loading: Boolean = false,
        /** Null until the events are read. */
        val proposals: List<CalendarImport.Proposal>? = null,
        /** Events the user chose to leave out, by key. */
        val left: Set<String> = emptySet(),
        val autoCheck: Boolean = false,
        val done: CalendarImporter.Done? = null,
    ) {
        /** What the button brings in: not skipped and not left out. */
        val todo: List<CalendarImport.Proposal> get() = proposals.orEmpty().filter { it.skip == null && it.event.key !in left }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    val types: StateFlow<List<ShiftType>> = container.scheduleRepository.snapshot.map { it.shiftTypes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    /** Reads the permission and the calendars again: on open, and after the permission screen. */
    fun refresh() {
        viewModelScope.launch {
            val allowed = importer.allowed()
            val calendars = if (allowed) withContext(Dispatchers.IO) { importer.calendars() } else emptyList()
            val chosen = importer.chosen?.filter { id -> calendars.any { it.id == id } }?.toSet() ?: importer.defaultChoice(calendars)
            _ui.update { it.copy(allowed = allowed, calendars = calendars, chosen = chosen, autoCheck = importer.autoCheck) }
        }
    }

    fun toggle(id: Long) = _ui.update { u -> u.copy(chosen = if (id in u.chosen) u.chosen - id else u.chosen + id, proposals = null) }
    fun range(r: ImportRange) = _ui.update { it.copy(range = r, proposals = null) }

    fun read() {
        val u = _ui.value
        if (u.chosen.isEmpty()) return
        _ui.update { it.copy(loading = true, done = null) }
        viewModelScope.launch {
            val today = Today.date()
            val list = runCatching { importer.propose(u.calendars, u.chosen, u.range.from(today), u.range.to(today)) }.getOrDefault(emptyList())
            importer.chosen = u.chosen
            _ui.update { it.copy(loading = false, proposals = list, left = emptySet()) }
        }
    }

    /** The user's own call for one event: another shift, a day off, a memo, or leave it out. */
    fun change(p: CalendarImport.Proposal, kind: As?, shiftTypeId: Long?) = _ui.update { u ->
        val key = p.event.key
        if (kind == null) return@update u.copy(left = u.left + key)
        u.copy(
            left = u.left - key,
            proposals = u.proposals?.map {
                if (it.event.key == key) it.copy(kind = kind, shiftTypeId = shiftTypeId, reason = "직접 골랐어요", skip = null) else it
            },
        )
    }

    fun setAuto(on: Boolean) {
        importer.autoCheck = on
        if (on && importer.chosen == null) importer.chosen = _ui.value.chosen
        _ui.update { it.copy(autoCheck = on) }
    }

    fun bringIn() {
        val todo = _ui.value.todo
        if (todo.isEmpty()) return
        viewModelScope.launch {
            val done = importer.apply(todo)
            val keys = done.keys.toSet()
            _ui.update { u ->
                u.copy(done = done, proposals = u.proposals?.map { if (it.event.key in keys) it.copy(skip = CalendarImport.Skip.IMPORTED) else it })
            }
            _messages.emit(UndoMessage("근무 ${done.shifts}일, 메모 ${done.memos}개를 가져왔어요") {
                importer.undo(done)
                _ui.update { u -> u.copy(done = null, proposals = null) }
            })
        }
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarImportScreen(vm: CalendarImportViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var asked by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<CalendarImport.Proposal?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true; vm.refresh() }
    LaunchedEffect(vm) {
        vm.refresh()
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val byId = types.associateBy { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캘린더에서 가져오기") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                // [UX-05] Help about this screen, with the question already about it.
                actions = {
                    IconButton(onClick = {
                        com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "캘린더에서 가져오기", help = true))
                    }) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "가져오기 도움말") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val todo = ui.todo
            if (ui.proposals != null) Surface(tonalElevation = 3.dp) {
                val c = CalendarImport.count(todo)
                Button(onClick = vm::bringIn, enabled = todo.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 52.dp)) {
                    Text(if (todo.isEmpty()) "가져올 새 일정이 없어요"
                    else listOfNotNull(
                        c.shifts.takeIf { it > 0 }?.let { "근무 ${it}일" }, c.offs.takeIf { it > 0 }?.let { "쉬는 날 ${it}일" },
                        c.memos.takeIf { it > 0 }?.let { "메모 ${it}개" },
                    ).joinToString(" · ") + " 가져오기")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "삼성 캘린더 일정을 밤낮으로") {
                    Text("휴대폰 캘린더(삼성 캘린더, Google 캘린더 등)의 일정을 읽어서, 근무 이름이나 근무 시간과 맞는 일정은 그날 근무로, " +
                        "나머지는 그날 메모로 옮겨요. 캘린더는 읽기만 하고 고치지 않아요. 읽은 일정은 이 휴대폰 밖으로 나가지 않아요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!ui.allowed) {
                        Spacer(Modifier.height(10.dp))
                        if (asked) {
                            Text("허용하지 않았어요. 휴대폰 설정 > 애플리케이션 > 밤낮 > 권한에서 캘린더를 허용할 수 있어요.",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }) { Text("권한 설정 열기") }
                        }
                        Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.fillMaxWidth()) {
                            Text("캘린더 읽기 허용하기")
                        }
                    }
                }
            }
            if (ui.allowed) {
                item {
                    SectionCard(title = "가져올 캘린더") {
                        if (ui.calendars.isEmpty()) {
                            Text("이 휴대폰에 캘린더가 없어요.", style = MaterialTheme.typography.bodyMedium)
                        }
                        ui.calendars.forEach { c ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.toggle(c.id) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = c.id in ui.chosen, onCheckedChange = { vm.toggle(c.id) })
                                Column(Modifier.weight(1f)) {
                                    Text(c.name.ifBlank { c.account }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(c.source + if (c.generated) " · 공휴일·생일은 밤낮에 이미 있어요" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("기간", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImportRange.entries.forEach { r -> FilterChip(selected = ui.range == r, onClick = { vm.range(r) }, label = { Text(r.label) }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::read, enabled = ui.chosen.isNotEmpty() && !ui.loading, modifier = Modifier.fillMaxWidth()) {
                            Text(if (ui.proposals == null) "일정 읽기" else "다시 읽기")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("새 일정 알려 주기", style = MaterialTheme.typography.bodyLarge)
                                Text("앱을 열 때 고른 캘린더에 새 일정이 있으면 알려 줘요. 가져오기는 직접 눌러야 해요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = ui.autoCheck, onCheckedChange = vm::setAuto)
                        }
                    }
                }
            }
            if (ui.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            ui.proposals?.let { list ->
                if (list.isEmpty()) item {
                    Text("이 기간에 일정이 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    item {
                        Text("눌러서 근무·메모를 바꾸거나 빼요. 같은 일정은 두 번 가져오지 않아요.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(list, key = { it.event.key }) { p ->
                        ProposalRow(p, left = p.event.key in ui.left, type = p.shiftTypeId?.let(byId::get)) { changing = p }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    changing?.let { p ->
        ChangeDialog(p, types, onDismiss = { changing = null }) { kind, id -> vm.change(p, kind, id); changing = null }
    }
}

@Composable
private fun ProposalRow(p: CalendarImport.Proposal, left: Boolean, type: ShiftType?, onClick: () -> Unit) {
    val muted = left || p.skip != null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(64.dp)) {
                Text(Fmt.shortDate(p.date), style = MaterialTheme.typography.titleSmall)
                Text(Fmt.dayOfWeekShort(p.date.dayOfWeek), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(p.event.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(
                    if (left) "가져오지 않아요" else p.skip?.label ?: p.reason,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                muted -> Text("건너뜀", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.kind == As.MEMO -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("메모", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                p.kind == As.OFF && type == null -> Text("쉬는 날", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    ShiftBadge(type, size = 26.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeDialog(p: CalendarImport.Proposal, types: List<ShiftType>, onDismiss: () -> Unit, onPick: (As?, Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Fmt.dayTitle(p.date)} · ${p.event.title.ifBlank { "일정" }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("근무로", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(selected = p.shiftTypeId == t.id, onClick = { onPick(if (t.isOff) As.OFF else As.SHIFT, t.id) },
                            label = { Text(t.name) }, leadingIcon = { ShiftBadge(t, size = 18.dp) })
                    }
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPick(As.MEMO, null) }, modifier = Modifier.weight(1f)) { Text("메모로") }
                    OutlinedButton(onClick = { onPick(null, null) }, modifier = Modifier.weight(1f)) { Text("빼기") }
                }
                Text(CalendarImport.memoText(p.event), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
