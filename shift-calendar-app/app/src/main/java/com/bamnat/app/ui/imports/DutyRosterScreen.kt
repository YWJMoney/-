package com.bamnat.app.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.rules
import com.bamnat.app.imports.RosterScanner
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyRoster
import com.bamnat.core.imports.DutyType
import com.bamnat.core.imports.RosterRow
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.isNight
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * [SHFT-06] 듀티표 사진으로 넣기: a photo of next month's roster is read on the phone, the user picks their own
 * line and fixes any cell, and the days go on the calendar on screen as changes for those days (the pattern
 * stays). Text copied from Google Lens or a messenger works the same way.
 */
class DutyRosterViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    private val schedule get() = container.calendars.repository(calendarId)

    data class Ui(
        val month: YearMonth,
        val reading: Boolean = false,
        /** Null until something was read. */
        val rows: List<RosterRow>? = null,
        val picked: Int = 0,
        /** The user's own cells over the picked line's, by day; null removes a cell. */
        val edits: Map<Int, DutyCell?> = emptyMap(),
        val error: String? = null,
        val applied: Int? = null,
    ) {
        val row: RosterRow? get() = rows?.getOrNull(picked)
        val cells: Map<Int, DutyCell>
            get() {
                val base = row?.cells.orEmpty().toMutableMap()
                edits.forEach { (d, c) -> if (c == null) base.remove(d) else base[d] = c }
                return base.filterKeys { it <= month.lengthOfMonth() }
            }
        /** Cells that will be written: everything but the unknown ones. */
        val writable: Map<Int, DutyCell> get() = cells.filterValues { it !is DutyCell.Unknown }
        /** [NURS-04] Days read with doubt the user has not touched yet. */
        val doubtful: Set<Int> get() = row?.doubtful(month.lengthOfMonth())?.minus(edits.keys).orEmpty()
    }

    private val start = container.shownMonth.month.value.let { shown ->
        // After the middle of a month the roster on the wall is usually next month's.
        if (shown == YearMonth.now() && LocalDate.now().dayOfMonth >= 15) shown.plusMonths(1) else shown
    }
    private val _ui = MutableStateFlow(Ui(start))
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    val types: StateFlow<List<ShiftType>> = schedule.shiftTypes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** [NURS-04] The calendar for the keypad sheet, which shows the month as it will be. */
    val snapshot: StateFlow<com.bamnat.app.data.ScheduleSnapshot?> = schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** [REST-01] The same 근무표 점검 기준 as the calendar, as the month's pattern turns. */
    fun rules(snap: com.bamnat.app.data.ScheduleSnapshot, month: YearMonth): com.bamnat.core.schedule.RestRules =
        com.bamnat.core.schedule.RotationSpeed.of(com.bamnat.core.schedule.PatternTimeline.current(snap.patterns, month.atDay(1)), snap.shiftTypes)
            .adjust(container.settingsRepository.current.rules)

    /** [NURS-04] The roster as the keypad sheet types it: kinds of day and days off (leave stays as read). */
    fun typing(): Map<LocalDate, com.bamnat.core.model.ShiftOverride> = _ui.value.let { u ->
        u.cells.mapNotNull { (d, c) ->
            when (c) {
                is DutyCell.Shift -> u.month.atDay(d) to com.bamnat.core.model.ShiftOverride.Shift(c.shiftTypeId)
                DutyCell.Off -> u.month.atDay(d) to com.bamnat.core.model.ShiftOverride.Off
                else -> null
            }
        }.toMap()
    }

    /** [NURS-04] What the keypad sheet saved, back as the user's own cells; 'pattern' leaves the day as it is. */
    fun fromTyping(changes: Map<LocalDate, com.bamnat.core.model.ShiftOverride>, left: Set<LocalDate> = emptySet()) = _ui.update { u ->
        val read = u.cells
        val edits = changes.filterKeys { YearMonth.from(it) == u.month }.mapNotNull { (date, o) ->
            val cell: DutyCell? = when (o) {
                is com.bamnat.core.model.ShiftOverride.Shift -> DutyCell.Shift(o.shiftTypeId)
                com.bamnat.core.model.ShiftOverride.Off -> DutyCell.Off
                com.bamnat.core.model.ShiftOverride.None -> null
            }
            // A doubtful day typed over is the user's answer even when it matches what was read.
            (date.dayOfMonth to cell).takeIf { read[date.dayOfMonth] != cell || date.dayOfMonth in u.doubtful && date !in left }
        }.toMap()
        u.copy(edits = u.edits + edits)
    }
    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    private suspend fun codes(): DutyCodes {
        val list = types.value.ifEmpty { schedule.shiftTypes.first() }
        return DutyCodes(list.map { DutyType(it.id, it.name, it.label, it.isOff, it.start, it.isNight) })
    }

    fun month(delta: Long) = _ui.update { it.copy(month = it.month.plusMonths(delta), applied = null) }

    fun readPhoto(uri: Uri) {
        _ui.update { it.copy(reading = true, error = null, applied = null) }
        viewModelScope.launch {
            val result = runCatching { DutyRoster.read(RosterScanner.read(container.appContext, uri), codes()) }
            show(result.getOrNull(), if (result.isFailure) "사진을 읽지 못했어요. 다른 사진으로 해 보세요." else null)
        }
    }

    fun readText(text: String) {
        _ui.update { it.copy(reading = true, error = null, applied = null) }
        viewModelScope.launch { show(DutyRoster.readText(text, codes()), null) }
    }

    private fun show(rows: List<RosterRow>?, error: String?) = _ui.update {
        it.copy(
            reading = false, rows = rows, picked = 0, edits = emptyMap(),
            error = error ?: if (rows != null && rows.isEmpty()) "날짜 줄(1 2 3 …)과 근무 줄을 찾지 못했어요. 표 전체가 반듯하게 나오게 다시 찍어 보세요." else null,
        )
    }

    fun pick(i: Int) = _ui.update { it.copy(picked = i, edits = emptyMap()) }
    fun edit(day: Int, cell: DutyCell?) = _ui.update { it.copy(edits = it.edits + (day to cell)) }

    fun apply() {
        val u = _ui.value
        val cells = u.writable
        if (cells.isEmpty()) return
        val repo = schedule // an undo stays with the calendar it was made in
        viewModelScope.launch {
            val dates = cells.keys.sorted().map { u.month.atDay(it) }
            val before = repo.updateDays(dates) { e -> DutyRoster.apply(e, cells.getValue(e.date.dayOfMonth)) }
            _ui.update { it.copy(applied = cells.size) }
            _messages.emit(UndoMessage("${Fmt.month(u.month)}에 ${cells.size}일을 넣었어요") {
                before.forEach { (d, e) -> repo.restoreDay(d, e) }
                _ui.update { it.copy(applied = null) }
            })
        }
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DutyRosterScreen(vm: DutyRosterViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pasting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    // [NURS-04] The month in the keypad sheet with the doubtful cells marked.
    var typing by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(emptySet<LocalDate>()) }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    // The system photo picker: no storage permission, only the photo the user picks is shared with the app.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::readPhoto) }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val byId = types.associateBy { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("듀티표 사진으로 넣기") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ui.row != null) Surface(tonalElevation = 3.dp) {
                val n = ui.writable.size
                Button(onClick = vm::apply, enabled = n > 0 && ui.applied == null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 52.dp)) {
                    Text(if (ui.applied != null) "넣었어요" else "${ui.month.monthValue}월에 ${n}일 넣기")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "한 달 듀티표를 한 번에") {
                    Text("듀티표 사진을 고르면 휴대폰 안에서 글자를 읽어 날짜마다 근무를 찾아요. 사진과 글자는 휴대폰 밖으로 나가지 않아요. " +
                        "넣기 전에 칸을 눌러 고칠 수 있고, 넣은 뒤에도 되돌릴 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("넣을 달", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.month(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                        Text(Fmt.month(ui.month), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { vm.month(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
                    }
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !ui.reading, modifier = Modifier.fillMaxWidth()) { Text("듀티표 사진 고르기") }
                    OutlinedButton(onClick = { pasting = true }, enabled = !ui.reading, modifier = Modifier.fillMaxWidth()) { Text("글자로 붙여 넣기") }
                    Text("표가 화면에 꽉 차고 반듯하게 나온 사진이 잘 읽혀요. 구글 렌즈 등에서 복사한 글자를 붙여 넣어도 돼요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.reading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            ui.error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
            val rows = ui.rows.orEmpty()
            if (rows.size > 1) item {
                SectionCard(title = "내 줄을 골라 주세요") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rows.forEachIndexed { i, r ->
                            FilterChip(selected = ui.picked == i, onClick = { vm.pick(i) },
                                label = { Text(r.label.ifBlank { "${i + 1}번째 줄" } + " · ${r.cells.size}일") })
                        }
                    }
                }
            }
            if (ui.row != null) item {
                SectionCard(title = "${ui.month.monthValue}월 미리 보기") {
                    val unknown = ui.cells.values.count { it is DutyCell.Unknown }
                    Text(
                        (if (unknown > 0) "?는 알아보지 못한 칸이에요. 눌러서 골라 주세요. 고르지 않은 칸과 빈칸은 그대로 둬요."
                        else "칸을 누르면 바꿀 수 있어요. 빈칸인 날은 그대로 둬요.") +
                            if (ui.doubtful.isNotEmpty()) " 노란 칸은 사진에서 확실하지 않은 칸이에요." else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    RosterGrid(ui.month, ui.cells, byId, ui.doubtful) { editing = it }
                    if (snap != null && types.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { typing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (ui.doubtful.isEmpty()) "키패드로 고치기" else "확실하지 않은 ${ui.doubtful.size}칸 키패드로 고치기")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }) { pasting = false; vm.readText(it) }
    val s = snap
    if (typing && s != null) {
        com.bamnat.app.ui.calendar.DutyTypingSheet(
            s, ui.month, onDismiss = { typing = false },
            onSave = { changes -> vm.fromTyping(changes, left); typing = false },
            onDoubtLeft = { left = it },
            rules = vm.rules(s, ui.month),
            initial = vm.typing(),
            doubtful = ui.doubtful.map { ui.month.atDay(it) }.toSet(),
        )
    }
    editing?.let { day ->
        CellDialog(ui.month.atDay(day), types, onDismiss = { editing = null }) { cell -> vm.edit(day, cell); editing = null }
    }
}

/** The month as a calendar, Monday first, each day with what the roster says. */
@Composable
private fun RosterGrid(month: YearMonth, cells: Map<Int, DutyCell>, byId: Map<Long, ShiftType>, doubtful: Set<Int>, onTap: (Int) -> Unit) {
    val lead = month.atDay(1).dayOfWeek.value - 1
    val slots = List(lead) { null } + (1..month.lengthOfMonth()).toList()
    Row { listOf("월", "화", "수", "목", "금", "토", "일").forEach {
        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
    slots.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                val day = week.getOrNull(i)
                Box(Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp)
                    .then(if (day != null && day in doubtful) Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small) else Modifier)
                    .then(if (day != null) Modifier.clickable { onTap(day) } else Modifier), contentAlignment = Alignment.TopCenter) {
                    if (day != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$day", style = MaterialTheme.typography.labelSmall)
                        when (val c = cells[day]) {
                            is DutyCell.Shift -> ShiftBadge(byId[c.shiftTypeId], size = 22.dp, outlined = byId[c.shiftTypeId]?.isOff == true)
                            DutyCell.Off -> ShiftBadge(null, text = "휴", size = 22.dp, outlined = true)
                            DutyCell.Leave -> ShiftBadge(null, text = "연", size = 22.dp)
                            is DutyCell.Unknown -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                                Text("?", Modifier.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            null -> Text("·", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellDialog(date: LocalDate, types: List<ShiftType>, onDismiss: () -> Unit, onPick: (DutyCell?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Fmt.monthDay(date)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t -> FilterChip(selected = false, onClick = { onPick(DutyCell.Shift(t.id)) }, label = { Text(t.name) },
                    leadingIcon = { ShiftBadge(t, size = 18.dp, outlined = t.isOff) }) }
                if (types.none { it.isOff }) FilterChip(selected = false, onClick = { onPick(DutyCell.Off) }, label = { Text("쉬는 날") })
                FilterChip(selected = false, onClick = { onPick(DutyCell.Leave) }, label = { Text("연차") })
                FilterChip(selected = false, onClick = { onPick(null) }, label = { Text("그대로 두기") })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("글자로 붙여 넣기") },
        text = {
            Column {
                Text("날짜 줄과 근무 줄을 한 줄씩 붙여 넣어 주세요.\n예) 1 2 3 4 5\n김민지 D E N O O",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text("여기에 붙여 넣기") })
            }
        },
        confirmButton = { TextButton(onClick = { onRead(text) }, enabled = text.isNotBlank()) { Text("읽기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
