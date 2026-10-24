package com.bamnat.app.ui.imports

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.IncomingRoster
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.rules
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.calendar.MonthGrid
import com.bamnat.app.ui.calendar.WeekdayHeader
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimeRange
import com.bamnat.app.ui.components.TimeRangeDialog
import com.bamnat.app.ui.guide.WriteGate
import com.bamnat.core.example.WriteKind
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyRoster
import com.bamnat.core.imports.DutyType
import com.bamnat.core.imports.MonthFill
import com.bamnat.core.imports.RosterRow
import com.bamnat.core.journal.JournalKind
import com.bamnat.core.journal.RosterFrom
import com.bamnat.core.journal.rosterDetail
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.isNight
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
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
import com.bamnat.core.time.Today
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [ROST-01] 한 달 근무 넣기 (1.24 순번 341, 결정 D62): the month's own calendar with a keypad under it. Typing is the first
 * way in: a key puts its kind on the marked day and the mark moves on, a finger dragged over days fills them with the
 * last key, "이 순서 반복" carries the order to the month's end. A photo, a 엑셀·CSV file or pasted text are the other ways,
 * and what they read lands on the same calendar, where any cell is fixed in place. Nothing is written until "n일 넣기",
 * which says first what it overwrites, and can be taken back after.
 * [SHFT-06][IMPORT-03][NURS-04] The photo, file and text reading of 1.18~1.23, and the doubtful cells to look at.
 */
class DutyRosterViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    private val schedule get() = container.calendars.repository(calendarId)

    data class Ui(
        val month: YearMonth,
        val reading: Boolean = false,
        /** Null until something was read; typing alone needs no rows. */
        val rows: List<RosterRow>? = null,
        val picked: Int = 0,
        /** The user's own cells over the picked line's, by day; null takes a read cell away. */
        val edits: Map<Int, DutyCell?> = emptyMap(),
        /** [ROST-06] Codes answered here (by [DutyCodes.codeOf]): 병가 → 유급 휴가. */
        val answers: Map<String, DutyCell> = emptyMap(),
        /** The day the next key goes into. */
        val cursor: Int = 1,
        /** The key pressed last, which a drag over days fills with. */
        val lastKey: DutyCell? = null,
        val error: String? = null,
        val applied: Int? = null,
        val from: RosterFrom = RosterFrom.TYPED,
        /** [ROST-04] The month the roster named, when it did. */
        val named: YearMonth? = null,
        /** [ROST-10] Clipboard text that reads as a roster, offered once. */
        val clip: String? = null,
        val history: List<Map<Int, DutyCell?>> = emptyList(),
    ) {
        val row: RosterRow? get() = rows?.getOrNull(picked)
        private val days: Int get() = month.lengthOfMonth()

        val cells: Map<Int, DutyCell>
            get() {
                val base = row?.cells.orEmpty().mapValues { (_, c) -> resolve(c) }.toMutableMap()
                edits.forEach { (d, c) -> if (c == null) base.remove(d) else base[d] = c }
                return base.filterKeys { it <= days }
            }
        /** Cells that will be written: everything but those still unknown. */
        val writable: Map<Int, DutyCell> get() = cells.filterValues { it !is DutyCell.Unknown }
        /** [NURS-04] Days read with doubt the user has not touched yet, and cells still unknown. */
        val doubtful: Set<Int>
            get() = (row?.doubtful(days).orEmpty() - edits.keys) + cells.filterValues { it is DutyCell.Unknown }.keys
        /** [ROST-03] What the roster said besides the code, for the days it stays on. */
        val notes: Map<Int, String> get() = row?.notes.orEmpty().filterKeys { it <= days && it !in edits && it in writable }
        /** [ROST-04] Days of the roster this month has not (31일 on a 30-day month). */
        val beyond: List<Int> get() = row?.let { DutyRoster.beyond(it, month) }.orEmpty()
        /** [ROST-06] The codes still to answer, leave words first: one question at a time. */
        val asks: List<DutyCell.Unknown>
            get() = cells.values.filterIsInstance<DutyCell.Unknown>().distinctBy { it.raw }.sortedBy { if (it.leave) 0 else 1 }

        fun resolve(c: DutyCell): DutyCell = if (c is DutyCell.Unknown) answers[codeKey(c.raw)] ?: c else c
    }

    private val today = Today.date()
    private val start = container.shownMonth.month.value.let { shown ->
        // After the middle of a month the roster on the wall is usually next month's.
        if (shown == YearMonth.from(today) && today.dayOfMonth >= 15) shown.plusMonths(1) else shown
    }
    private val _ui = MutableStateFlow(Ui(start))
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    val types: StateFlow<List<ShiftType>> = schedule.shiftTypes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** The calendar as it is, under the cells about to go on it. */
    val snapshot: StateFlow<ScheduleSnapshot?> = schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val weekStart get() = container.settingsRepository.current.weekStart
    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    private suspend fun codes(): DutyCodes {
        val list = types.value.ifEmpty { schedule.shiftTypes.first() }
        return DutyCodes(list.map { DutyType(it.id, it.name, it.label, it.isOff, it.start, it.isNight) }, container.dutyCodes.of(calendarId))
    }

    fun month(delta: Long) = _ui.update { it.copy(month = it.month.plusMonths(delta), applied = null, cursor = 1, history = emptyList()) }

    // ---- typing (the first way in) ----

    private fun change(u: Ui, edits: Map<Int, DutyCell?>, cursor: Int = u.cursor, key: DutyCell? = u.lastKey): Ui =
        u.copy(edits = edits, cursor = cursor, lastKey = key, applied = null, history = (u.history + listOf(u.edits)).takeLast(MAX_UNDO))

    /** [ROST-01] A key: [cell] on the marked day, and the mark to the next one still doubtful, else the next day. */
    fun put(cell: DutyCell?) = _ui.update { u ->
        val day = u.cursor
        val doubt = u.doubtful - day
        val next = doubt.filter { it > day }.minOrNull() ?: if (u.row != null && day in u.doubtful) doubt.minOrNull() else null
        change(u, u.edits + (day to cell), (next ?: (day + 1)).coerceAtMost(u.month.lengthOfMonth()), cell ?: u.lastKey)
    }

    /** [ROST-08] 한 칸 비우기: this day stays as the calendar has it, and on to the next. */
    fun skip() = put(null)

    fun tap(day: Int) = _ui.update { it.copy(cursor = day.coerceIn(1, it.month.lengthOfMonth())) }

    fun step(delta: Int) = _ui.update { it.copy(cursor = (it.cursor + delta).coerceIn(1, it.month.lengthOfMonth())) }

    /** [ROST-01] A finger over days: the last key on all of them. */
    fun paint(days: List<Int>) = _ui.update { u ->
        val key = u.lastKey ?: return@update u
        val inMonth = days.filter { it in 1..u.month.lengthOfMonth() }
        if (inMonth.isEmpty()) u else change(u, u.edits + inMonth.associateWith { key }, (inMonth.max() + 1).coerceAtMost(u.month.lengthOfMonth()))
    }

    /** [ROST-08] 이 순서 반복: the order typed up to the mark, to the month's end. */
    fun repeatOrder(): Boolean {
        val u = _ui.value
        val rest = MonthFill.repeat(u.cells, u.cursor, u.month.lengthOfMonth()) ?: return false
        _ui.value = change(u, u.edits + rest, u.month.lengthOfMonth())
        return true
    }

    /** [ROST-08] 지난달과 같게: last month as the calendar ended up, day for day. */
    fun sameAsLastMonth(snap: ScheduleSnapshot) = _ui.update { u ->
        val previous = u.month.minusMonths(1)
        val copy = (1..minOf(u.month.lengthOfMonth(), previous.lengthOfMonth())).mapNotNull { d ->
            val t = snap.resolver.resolve(previous.atDay(d)).shiftType ?: return@mapNotNull null
            d to DutyCell.Shift(t.id)
        }.toMap()
        if (copy.isEmpty()) u else change(u, u.edits + copy, u.month.lengthOfMonth())
    }

    fun undo() = _ui.update { u -> u.history.lastOrNull()?.let { u.copy(edits = it, history = u.history.dropLast(1), applied = null) } ?: u }

    fun canRepeat(u: Ui): Boolean = MonthFill.repeat(u.cells, u.cursor, u.month.lengthOfMonth()) != null

    // ---- reading (photo, file, text) ----

    fun readPhoto(uri: Uri) = read { c -> RosterReader.photo(container.appContext, uri, c, _ui.value.month.atDay(1)) }

    fun readFile(uri: Uri) = read { c -> RosterReader.file(container.appContext, uri, c, _ui.value.month.atDay(1)) }

    fun readText(text: String) = read { c -> RosterReader.text(text, c, today) }

    private fun read(how: suspend (DutyCodes) -> RosterResult) {
        _ui.update { it.copy(reading = true, error = null, applied = null, clip = null) }
        viewModelScope.launch {
            val r = how(codes())
            _ui.update {
                it.copy(
                    reading = false, rows = r.rows.takeIf { rows -> rows.isNotEmpty() } ?: it.rows, picked = 0,
                    edits = if (r.rows.isEmpty()) it.edits else emptyMap(), error = r.error, from = r.from,
                    // [ROST-04] The month the roster names wins over the one on screen (중-6).
                    month = r.month ?: it.month, named = r.month, cursor = 1, history = emptyList(),
                )
            }
        }
    }

    /** [ROST-09] A roster shared to the app from the gallery or a messenger. */
    fun receive(shared: IncomingRoster.Shared) = when (shared) {
        is IncomingRoster.Shared.Photo -> readPhoto(shared.uri)
        is IncomingRoster.Shared.Text -> readText(shared.text)
    }

    /** [ROST-10] Clipboard text that reads as a roster: offered, never kept. */
    fun offerClip(text: String) {
        if (_ui.value.rows != null || _ui.value.reading) return
        viewModelScope.launch { if (RosterReader.looksLikeRoster(text, codes())) _ui.update { it.copy(clip = text) } }
    }

    fun useClip() { _ui.value.clip?.let(::readText) }

    fun declineClip() {
        _ui.value.clip?.let { container.dutyCodes.decline(it.hashCode()) }
        _ui.update { it.copy(clip = null) }
    }

    fun declined(text: String): Boolean = container.dutyCodes.declined(text.hashCode())

    fun pick(i: Int) = _ui.update { it.copy(picked = i, edits = emptyMap(), cursor = 1, history = emptyList()) }

    /** A cell fixed in place. */
    fun edit(day: Int, cell: DutyCell?) = _ui.update { u -> change(u, u.edits + (day to cell)) }

    /** [ROST-06] What a code means, here and on the next roster (the calendar's code book). */
    fun answer(raw: String, cell: DutyCell) {
        val key = codeKey(raw)
        container.dutyCodes.learn(calendarId, key, cell)
        _ui.update { it.copy(answers = it.answers + (key to cell)) }
    }

    /**
     * [ROST-11] A code the calendar has no kind for (M, S, 교): made one, with the name and times the user gives, and
     * learned. The kinds of work are then partly read off a roster: the profile says so ('추정', 리뷰 N4).
     */
    fun newKind(raw: String, name: String, range: TimeRange) {
        viewModelScope.launch {
            val list = types.value
            val label = DutyCodes.norm(raw).take(2).ifEmpty { name.take(1) }
            val id = schedule.saveShiftType(
                ShiftType(
                    id = 0, name = name.trim().ifEmpty { label }, label = label, colorArgb = NEW_COLORS[list.size % NEW_COLORS.size],
                    start = range.start, end = range.end, breakMinutes = range.breakMinutes, sortOrder = (list.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                ),
            )
            answer(raw, DutyCell.Shift(id))
            markShiftsInferred()
        }
    }

    private suspend fun markShiftsInferred() {
        val fact = container.profiles.facts(calendarId)[FactKey.SHIFTS] ?: return
        if (Profile.decide(fact, Source.INFERRED, ROSTER_CONFIDENCE) == Profile.Decision.WRITE) {
            container.profiles.mark(calendarId, FactKey.SHIFTS, Source.INFERRED, ROSTER_CONFIDENCE, "듀티표")
        }
    }

    fun apply() = WriteGate.request(container, WriteKind.SHIFT) { write() }

    private fun write() {
        val u = _ui.value
        val cells = u.writable
        if (cells.isEmpty()) return
        val repo = schedule // an undo stays with the calendar it was made in
        val notes = u.notes
        viewModelScope.launch {
            val dates = cells.keys.sorted().map { u.month.atDay(it) }
            val before = repo.updateDays(dates) { e -> DutyRoster.apply(e, cells.getValue(e.date.dayOfMonth)) }
            // [ROST-03] "D*", "N-O", "11D": what the roster said besides the code, as that day's memo.
            val memos = notes.mapNotNull { (d, n) -> container.memoRepository.addQuick(u.month.atDay(d), "듀티표 표시: $n", setOf(calendarId)) }
            // [ROST-11] 밤낮이 한 일: what came in, never the roster itself.
            container.journal.note(JournalKind.ROSTER, rosterDetail(u.month.monthValue, cells.size, u.from))
            // A roster whose common codes were matched to the user's kinds says something about those kinds.
            if (u.from != RosterFrom.TYPED && u.row?.guessed.orEmpty().any { it in cells && it !in u.edits }) markShiftsInferred()
            _ui.update { it.copy(applied = cells.size, history = emptyList()) }
            _messages.emit(UndoMessage("${Fmt.month(u.month)}에 ${cells.size}일을 넣었어요") {
                before.forEach { (d, e) -> repo.restoreDay(d, e) }
                if (memos.isNotEmpty()) container.memoRepository.delete(memos, offerUndo = false)
                _ui.update { it.copy(applied = null) }
            })
        }
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }

    /** [REST-01] The same 근무표 점검 기준 as the calendar. */
    fun rules(snap: ScheduleSnapshot, month: YearMonth): com.bamnat.core.schedule.RestRules =
        com.bamnat.core.schedule.RotationSpeed.of(com.bamnat.core.schedule.PatternTimeline.current(snap.patterns, month.atDay(1)), snap.shiftTypes)
            .adjust(container.settingsRepository.current.rules)

    companion object {
        private const val MAX_UNDO = 60
        /** A roster's common code matched to the user's kind is likely, not sure. */
        const val ROSTER_CONFIDENCE = 0.6
        private val NEW_COLORS = listOf(0xFF7E57C2, 0xFF26A69A, 0xFFEF6C00, 0xFF5C6BC0, 0xFF8D6E63)
        private val keyCodes = DutyCodes(emptyList())
        fun codeKey(raw: String): String = keyCodes.codeOf(raw)
    }
}

/** [ROST-01] The month as it will be: the calendar's days with the cells about to go on them. */
internal fun overlay(snap: ScheduleSnapshot, month: YearMonth, cells: Map<Int, DutyCell>): ScheduleSnapshot {
    if (cells.isEmpty()) return snap
    val byDate = snap.entries.associateBy { it.date }.toMutableMap()
    cells.forEach { (d, c) ->
        val date = month.atDay(d)
        byDate[date] = DutyRoster.apply(byDate[date] ?: com.bamnat.core.model.DayEntry(date), c)
    }
    return snap.copy(entries = byDate.values.toList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DutyRosterScreen(vm: DutyRosterViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pasting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var newKindFor by remember { mutableStateOf<String?>(null) }
    var others by rememberSaveable { mutableStateOf(false) }
    // The system photo picker: no storage permission, only the photo the user picks is shared with the app.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::readPhoto) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::readFile) }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    // [ROST-09] A roster shared from the gallery or a messenger.
    val shared by IncomingRoster.shared.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) IncomingRoster.take()?.let(vm::receive) }
    // [ROST-10] Read while the screen is in front (Android 10+ allows nothing else), offered once, never kept.
    LaunchedEffect(Unit) {
        if (shared == null) clipText(context)?.takeIf { !vm.declined(it) }?.let(vm::offerClip)
    }
    val byId = types.associateBy { it.id }
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = conf.screenWidthDp >= 600 && conf.screenWidthDp > conf.screenHeightDp

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("한 달 근무 넣기", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (types.isNotEmpty() && !wide) Keypad(ui, types, snap, vm)
        },
    ) { padding ->
      // Lying down, the keypad stands beside the month instead of under it, so the month keeps the screen's height.
      Row(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.month(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                Text(Fmt.month(ui.month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                IconButton(onClick = { vm.month(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
            }
            // The other ways in, one row: a photo or a file reads the roster onto this month.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !ui.reading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)); Text(" 사진으로", maxLines = 1)
                }
                OutlinedButton(onClick = { files.launch(SheetFiles.TYPES) }, enabled = !ui.reading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Description, null, Modifier.size(18.dp)); Text(" 엑셀·CSV", maxLines = 1)
                }
                TextButton(onClick = { others = !others }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (others) "다른 방법 접기" else "다른 방법", maxLines = 1) }
            }
            if (others) {
                OutlinedButton(onClick = { pasting = true }, enabled = !ui.reading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Text(" 글자로 붙여 넣기")
                }
                Text("구글 렌즈나 메신저에서 복사한 근무표 글을 붙여 넣어도 돼요. 사진·파일·글은 휴대폰 밖으로 나가지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.clip?.let { ClipCard(onUse = vm::useClip, onNo = vm::declineClip) }
            if (ui.reading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error?.let { e -> Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            val rows = ui.rows.orEmpty()
            if (rows.size > 1) {
                Text("내 줄을 골라 주세요", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEachIndexed { i, r ->
                        FilterChip(selected = ui.picked == i, onClick = { vm.pick(i) },
                            label = { Text(r.label.ifBlank { "${i + 1}번째 줄" } + " · ${r.cells.size}일", maxLines = 1) })
                    }
                }
            }
            ui.asks.firstOrNull()?.let { ask -> AskCard(ask, types, onAnswer = { vm.answer(ask.raw, it) }, onNewKind = { newKindFor = ask.raw }) }
            Notices(ui, snap)
            val s = snap
            if (s != null) {
                val shown = remember(s, ui.month, ui.cells) { overlay(s, ui.month, ui.cells) }
                val calculator = remember(shown) { WorkHoursCalculator(shown.resolver) }
                WeekdayHeader(vm.weekStart, Modifier.padding(start = com.bamnat.app.ui.calendar.WEEK_BAR_SPACE))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cellHeight = (maxWidth / 7 * 1.15f).coerceIn(52.dp, 84.dp)
                    MonthGrid(
                        month = ui.month,
                        snapshot = shown,
                        calculator = calculator,
                        weekStart = vm.weekStart,
                        selected = ui.month.atDay(ui.cursor.coerceAtMost(ui.month.lengthOfMonth())),
                        today = Today.date(),
                        memoDates = emptySet(),
                        showHours = false,
                        cellHeight = cellHeight,
                        onDayClick = { d -> if (java.time.YearMonth.from(d) == ui.month) { if (d.dayOfMonth == ui.cursor) editing = d.dayOfMonth else vm.tap(d.dayOfMonth) } },
                        onQuickAction = { _, _ -> },
                        onPaint = if (ui.lastKey != null) ({ ds -> vm.paint(ds.filter { YearMonth.from(it) == ui.month }.map { it.dayOfMonth }) }) else null,
                        quickMenu = false,
                        marked = ui.doubtful.map { ui.month.atDay(it) }.toSet(),
                        modifier = Modifier.heightIn(min = cellHeight * 5),
                    )
                }
                Text("날짜를 누르면 그 날부터 넣고, 한 번 더 누르면 그 칸을 골라 고쳐요. 키를 하나 누른 뒤 손가락으로 쓸면 그 근무로 칠해요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (wide && types.isNotEmpty()) Keypad(ui, types, snap, vm, Modifier.width(360.dp).fillMaxHeight(), wide = true)
      }
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }) { pasting = false; vm.readText(it) }
    editing?.let { day ->
        CellDialog(ui.month.atDay(day), types, onDismiss = { editing = null }) { cell -> vm.edit(day, cell); editing = null }
    }
    newKindFor?.let { raw ->
        NewKindDialog(raw, onDismiss = { newKindFor = null }) { name, range -> vm.newKind(raw, name, range); newKindFor = null }
    }
}

/** [ROST-10] The clipboard's text, or null; read only while the screen is in front. */
private fun clipText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
private fun ClipCard(onUse: () -> Unit, onNo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("복사해 둔 근무표가 있어요. 붙여 넣은 글로 채울까요?", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNo) { Text("아니요") }
                TextButton(onClick = onUse) { Text("채우기") }
            }
        }
    }
}

/** [ROST-02][ROST-04][NURS-04] What to know before putting the days in, a line each. */
@Composable
private fun Notices(ui: DutyRosterViewModel.Ui, snap: ScheduleSnapshot?) {
    val lines = buildList {
        if (ui.named != null && ui.row != null) add("근무표에 적힌 ${ui.named.monthValue}월로 넣어요")
        if (ui.beyond.isNotEmpty()) add("${ui.beyond.joinToString(",")}일 칸이 있는데 ${ui.month.monthValue}월은 ${ui.month.lengthOfMonth()}일까지라 그 칸은 빼요")
        if (ui.doubtful.isNotEmpty()) add("노란 칸 ${ui.doubtful.size}개는 확실하지 않아요. 누르고 키로 맞춰 주세요")
        if (ui.notes.isNotEmpty()) add("표시가 붙은 ${ui.notes.size}칸은 그 날 메모로 남겨요")
        if (snap != null) {
            val over = DutyRoster.overwrites(ui.writable.mapKeys { ui.month.atDay(it.key) }, snap.entries.associateBy { it.date })
            if (over.isNotEmpty()) add("이미 있던 연차·바꾼 근무 ${over.size}일을 덮어써요")
            // Leave only counts on a day with work to take off (FEAT-10): say so before it quietly counts nothing.
            val idle = ui.writable.filterValues { it is DutyCell.Leave }.keys.sorted()
                .filter { !snap.resolver.resolve(ui.month.atDay(it)).isWorkDay }
            if (idle.isNotEmpty()) add("${idle.joinToString(",")}일은 근무가 없는 날이라 휴가 일수로 세지 않아요")
        }
    }
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** [ROST-06] One code to answer: a leave word asks its kind, anything else which of the user's kinds it is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskCard(ask: DutyCell.Unknown, types: List<ShiftType>, onAnswer: (DutyCell) -> Unit, onNewKind: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (ask.leave) "‘${ask.raw}’는 어떤 휴가인가요?" else "‘${ask.raw}’는 어떤 근무인가요?", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(if (ask.leave) "회사마다 달라요. 한 번 고르면 다음 근무표부터는 묻지 않아요." else "한 번 고르면 다음 근무표부터는 바로 들어가요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ask.leave) {
                    LeaveKind.entries.forEach { k ->
                        FilterChip(selected = false, onClick = { onAnswer(DutyCell.Leave(k)) }, label = { Text(k.label) },
                            leadingIcon = { LeaveMark(Leave(k), size = 18.dp) })
                    }
                } else {
                    types.forEach { t ->
                        FilterChip(selected = false, onClick = { onAnswer(DutyCell.Shift(t.id)) }, label = { Text(t.name) },
                            leadingIcon = { ShiftBadge(t, size = 18.dp, outlined = t.isOff) })
                    }
                    FilterChip(selected = false, onClick = { onAnswer(DutyCell.Leave()) }, label = { Text("연차") })
                    FilterChip(selected = false, onClick = onNewKind, label = { Text("새 근무로 만들기") })
                }
            }
        }
    }
}

/**
 * [ROST-01][ROST-08] The keypad under the month: a key per kind of day (and 연차·반차), then moving the mark, 비우기,
 * 이 순서 반복, 지난달과 같게 and 되돌리기, then "n일 넣기". Rows scroll sideways, so a key never shrinks under a big font.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keypad(
    ui: DutyRosterViewModel.Ui, types: List<ShiftType>, snap: ScheduleSnapshot?, vm: DutyRosterViewModel,
    modifier: Modifier = Modifier, wide: Boolean = false,
) {
    // Under the month the keys scroll sideways in one row each; beside it (lying down) they wrap, all in sight.
    @Composable fun Keys(content: @Composable () -> Unit) {
        if (wide) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
    Surface(modifier, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().then(if (wide) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${ui.month.monthValue}월 ${ui.cursor}일에 넣을 차례",
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Keys {
                types.forEach { t ->
                    FilledTonalButton(onClick = { vm.put(DutyCell.Shift(t.id)) }, contentPadding = KEY_PADDING,
                        modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "${t.name} 넣기" }) {
                        ShiftBadge(t, size = 22.dp, outlined = t.isOff)
                        Text(" ${t.name}", maxLines = 1)
                    }
                }
                if (types.none { it.isOff }) FilledTonalButton(onClick = { vm.put(DutyCell.Off) }, contentPadding = KEY_PADDING,
                    modifier = Modifier.heightIn(min = 52.dp)) { Text("쉬는 날", maxLines = 1) }
                LEAVE_KEYS.forEach { (name, cell) ->
                    FilledTonalButton(onClick = { vm.put(cell) }, contentPadding = KEY_PADDING,
                        modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "$name 넣기" }) {
                        LeaveMark(cell.leave, size = 20.dp)
                        Text(" $name", maxLines = 1)
                    }
                }
            }
            Keys {
                IconButton(onClick = { vm.step(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "앞 날") }
                IconButton(onClick = { vm.step(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날") }
                // A wrong key is taken back first, so 되돌리기 comes before the rest.
                TextButton(onClick = vm::undo, enabled = ui.history.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Text(" 되돌리기", maxLines = 1)
                }
                TextButton(onClick = vm::skip) { Icon(Icons.Outlined.Backspace, null, Modifier.size(18.dp)); Text(" 비우기", maxLines = 1) }
                TextButton(onClick = { vm.repeatOrder() }, enabled = vm.canRepeat(ui)) {
                    Icon(Icons.Filled.Repeat, null, Modifier.size(18.dp)); Text(" 이 순서 반복", maxLines = 1)
                }
                if (snap != null) TextButton(onClick = { vm.sameAsLastMonth(snap) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text(" 지난달과 같게", maxLines = 1)
                }
            }
            HorizontalDivider()
            val n = ui.writable.size
            Button(onClick = vm::apply, enabled = n > 0 && ui.applied == null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp)) {
                Text(if (ui.applied != null) "넣었어요" else if (n == 0) "키를 눌러 하루씩 넣어요" else "${ui.month.monthValue}월에 ${n}일 넣기", maxLines = 1)
            }
        }
    }
}

private val KEY_PADDING = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/** [ROST-07] 연차, and 반차 off the start (오전) or the end (오후) of the day. */
private val LEAVE_KEYS = listOf(
    "연차" to DutyCell.Leave(),
    "오전 반차" to DutyCell.Leave(quarters = 2, fromStart = true),
    "오후 반차" to DutyCell.Leave(quarters = 2),
)

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
                LEAVE_KEYS.forEach { (name, cell) -> FilterChip(selected = false, onClick = { onPick(cell) }, label = { Text(name) }) }
                FilterChip(selected = false, onClick = { onPick(null) }, label = { Text("그대로 두기") })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** [ROST-11] A code the calendar has no kind for, made one: its name (the code by default) and its times. */
@Composable
private fun NewKindDialog(raw: String, onDismiss: () -> Unit, onSave: (String, TimeRange) -> Unit) {
    var name by rememberSaveable { mutableStateOf(raw) }
    var times by remember { mutableStateOf(false) }
    if (times) {
        TimeRangeDialog(title = "‘$name’ 근무 시간", initial = TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0), 60),
            onDismiss = { times = false }, onConfirm = { onSave(name, it) }, confirmLabel = "만들기")
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 근무로 만들기") },
        text = {
            Column {
                Text("근무표의 ‘$raw’를 내 근무 종류로 만들어요. 이름을 확인하고 다음에서 시간을 골라 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                // A name is words by nature (규칙 13).
                OutlinedTextField(name, { name = it.take(12) }, singleLine = true, label = { Text("이름") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { times = true }, enabled = name.isNotBlank()) { Text("다음: 시간") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
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
                // Pasted text is the point here; this is the fallback way in (결정 D62).
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text("여기에 붙여 넣기") })
            }
        },
        confirmButton = { TextButton(onClick = { onRead(text) }, enabled = text.isNotBlank()) { Text("읽기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
