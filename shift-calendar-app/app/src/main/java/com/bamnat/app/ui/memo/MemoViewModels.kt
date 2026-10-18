package com.bamnat.app.ui.memo

import java.time.YearMonth
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.app.data.ScheduleSnapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoOrdering
import com.bamnat.core.memo.MemoReminder
import com.bamnat.core.memo.MemoTime
import java.time.LocalDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class MemoTab(val label: String) { CALENDAR("달력 메모"), GENERAL("일반 메모") }

enum class CalendarFilter(val label: String) {
    ALL("전체"), UPCOMING("다가오는 일정"), THIS_MONTH("이번 달"), PAST("지난 메모"), CHECKLIST("체크리스트"), PINNED("고정")
}

enum class GeneralFilter(val label: String) { ALL("전체"), PINNED("고정"), CHECKLIST("체크리스트"), TEXT("글 메모") }

enum class MemoSort(val label: String) { UPDATED("최근 수정순"), CREATED("최근 작성순"), TITLE("제목순") }

/** One date in the calendar memo list, with what was worked that day. */
data class MemoDateGroup(val date: LocalDate, val day: ResolvedDay?, val memos: List<Memo>)

data class MemoListState(
    val loaded: Boolean = false,
    val tab: MemoTab = MemoTab.CALENDAR,
    val query: String = "",
    val calendarFilter: CalendarFilter = CalendarFilter.ALL,
    val generalFilter: GeneralFilter = GeneralFilter.ALL,
    val sort: MemoSort = MemoSort.UPDATED,
    val tag: String? = null,
    /** Today and later, nearest first. */
    val upcoming: List<MemoDateGroup> = emptyList(),
    /** Before today, latest first. */
    val past: List<MemoDateGroup> = emptyList(),
    val general: List<Memo> = emptyList(),
    val calendarCount: Int = 0,
    val generalCount: Int = 0,
    /** Tags used in the current tab, most used first. */
    val tags: List<String> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val trashCount: Int = 0,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val shownCount: Int
        get() = if (tab == MemoTab.CALENDAR) upcoming.sumOf { it.memos.size } + past.sumOf { it.memos.size } else general.size
    val filtering: Boolean
        get() = query.isNotBlank() || tag != null ||
            (if (tab == MemoTab.CALENDAR) calendarFilter != CalendarFilter.ALL else generalFilter != GeneralFilter.ALL)
}

private data class MemoQuery(
    val tab: MemoTab = MemoTab.CALENDAR,
    val query: String = "",
    val calendarFilter: CalendarFilter = CalendarFilter.ALL,
    val generalFilter: GeneralFilter = GeneralFilter.ALL,
    val sort: MemoSort = MemoSort.UPDATED,
    val tag: String? = null,
)

class MemoListViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.memoRepository
    private val q = MutableStateFlow(MemoQuery())
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private var visibleIds: Set<Long> = emptySet()

    val state: StateFlow<MemoListState> = combine(
        repo.memos,
        container.scheduleRepository.snapshot,
        q,
        selection,
        repo.trash.map { it.size },
    ) { all, snapshot, query, selected, trashCount ->
        build(all, snapshot, query, selected, trashCount, LocalDate.now())
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoListState())

    private fun build(
        all: List<Memo>,
        snapshot: ScheduleSnapshot,
        query: MemoQuery,
        selected: Set<Long>,
        trashCount: Int,
        today: LocalDate,
    ): MemoListState {
        val (calendar, general) = all.partition { it.isCalendarMemo }
        val inTab = if (query.tab == MemoTab.CALENDAR) calendar else general
        val tags = inTab.flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val tag = query.tag?.takeIf { it in tags }
        val matching = inTab.filter { it.matches(query.query) && (tag == null || tag in it.tags) }

        var upcoming = emptyList<MemoDateGroup>()
        var past = emptyList<MemoDateGroup>()
        var generalList = emptyList<Memo>()
        if (query.tab == MemoTab.CALENDAR) {
            val month = YearMonth.from(today)
            val filtered = matching.filter { m ->
                val d = m.date!!
                when (query.calendarFilter) {
                    CalendarFilter.ALL -> true
                    CalendarFilter.UPCOMING -> !d.isBefore(today)
                    CalendarFilter.PAST -> d.isBefore(today)
                    CalendarFilter.THIS_MONTH -> YearMonth.from(d) == month
                    CalendarFilter.CHECKLIST -> m.isChecklist
                    CalendarFilter.PINNED -> m.pinned
                }
            }
            val groups = filtered.groupBy { it.date!! }.map { (date, memos) ->
                MemoDateGroup(date, snapshot.resolver.resolve(date), memos.sortedWith(MemoOrdering))
            }
            upcoming = groups.filter { !it.date.isBefore(today) }.sortedBy { it.date }
            past = groups.filter { it.date.isBefore(today) }.sortedByDescending { it.date }
            visibleIds = filtered.mapTo(HashSet()) { it.id }
        } else {
            val filtered = matching.filter { m ->
                when (query.generalFilter) {
                    GeneralFilter.ALL -> true
                    GeneralFilter.PINNED -> m.pinned
                    GeneralFilter.CHECKLIST -> m.isChecklist
                    GeneralFilter.TEXT -> !m.isChecklist
                }
            }
            val order = when (query.sort) {
                MemoSort.UPDATED -> MemoOrdering
                MemoSort.CREATED -> compareByDescending<Memo> { it.pinned }.thenByDescending { it.createdAtMillis }
                MemoSort.TITLE -> compareByDescending<Memo> { it.pinned }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title.ifBlank { it.preview } }
            }
            generalList = filtered.sortedWith(order)
            visibleIds = filtered.mapTo(HashSet()) { it.id }
        }
        return MemoListState(
            loaded = true,
            tab = query.tab,
            query = query.query,
            calendarFilter = query.calendarFilter,
            generalFilter = query.generalFilter,
            sort = query.sort,
            tag = tag,
            upcoming = upcoming,
            past = past,
            general = generalList,
            calendarCount = calendar.size,
            generalCount = general.size,
            tags = tags,
            // Memos deleted elsewhere drop out of the selection.
            selection = selected.filterTo(HashSet()) { id -> all.any { it.id == id } },
            trashCount = trashCount,
        )
    }

    fun setTab(tab: MemoTab) {
        q.update { it.copy(tab = tab, tag = null) }
        clearSelection()
    }
    fun setQuery(text: String) = q.update { it.copy(query = text) }
    /** [STAB-01] What the search box shows, apart from the list that is built off the main thread. */
    val query: StateFlow<String> = q.map { it.query }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setCalendarFilter(f: CalendarFilter) = q.update { it.copy(calendarFilter = f) }
    fun setGeneralFilter(f: GeneralFilter) = q.update { it.copy(generalFilter = f) }
    fun setSort(sort: MemoSort) = q.update { it.copy(sort = sort) }
    fun toggleTag(tag: String) = q.update { it.copy(tag = if (it.tag == tag) null else tag) }

    fun toggleSelected(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun selectAllVisible() = selection.update { visibleIds }
    fun clearSelection() {
        selection.value = emptySet()
    }

    private fun selectedMemos(): List<Memo> {
        val ids = state.value.selection
        val s = state.value
        return (s.upcoming + s.past).flatMap { it.memos }.plus(s.general).filter { it.id in ids }
    }

    fun pinSelected() {
        val memos = selectedMemos()
        // Pins all unless every selected memo is already pinned, then unpins.
        val pin = memos.any { !it.pinned }
        viewModelScope.launch { repo.setPinned(memos.map { it.id }, pin) }
        clearSelection()
    }

    fun colorSelected(color: Int) {
        val ids = state.value.selection
        viewModelScope.launch { repo.setColor(ids, color) }
        clearSelection()
    }

    fun deleteSelected() {
        val memos = selectedMemos()
        viewModelScope.launch { repo.delete(memos) }
        clearSelection()
    }

    fun togglePin(memo: Memo) {
        viewModelScope.launch { repo.setPinned(memo, !memo.pinned) }
    }

    fun duplicate(memo: Memo) {
        viewModelScope.launch { repo.duplicate(memo) }
    }

    fun delete(memo: Memo) {
        viewModelScope.launch { repo.delete(memo) }
    }
}

class MemoTrashViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.memoRepository

    val trash: StateFlow<List<Memo>?> = repo.trash.map<List<Memo>, List<Memo>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(memo: Memo) {
        viewModelScope.launch { repo.restore(listOf(memo.id)) }
    }

    fun restoreAll() {
        val ids = trash.value.orEmpty().map { it.id }
        viewModelScope.launch { repo.restore(ids) }
    }

    fun deleteForever(memo: Memo) {
        viewModelScope.launch { repo.deleteForever(listOf(memo.id)) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repo.emptyTrash() }
    }
}

/**
 * Editing one memo with autosave: every change is written shortly after typing stops,
 * and once more when the editor closes, so nothing is ever lost.
 */
class MemoEditorViewModel(
    private val container: AppContainer,
    private val memoId: Long,
    initialDate: LocalDate?,
    startAsChecklist: Boolean,
) : ViewModel() {
    private val repo = container.memoRepository
    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var dirty = false

    private val _memo = MutableStateFlow(
        Memo(
            date = initialDate,
            isChecklist = startAsChecklist,
            checklist = if (startAsChecklist) listOf(ChecklistItem("")) else emptyList(),
        ),
    )
    val memo: StateFlow<Memo> = _memo.asStateFlow()

    private val _loaded = MutableStateFlow(memoId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Time of the last successful save, for the "저장됨" label. */
    private val _savedAt = MutableStateFlow<Long?>(null)
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    private var deleted = false

    init {
        if (memoId != 0L) {
            viewModelScope.launch {
                repo.get(memoId)?.let { stored ->
                    _memo.value = stored
                    _savedAt.value = stored.updatedAtMillis
                }
                _loaded.value = true
            }
        }
    }

    private val undoStack = ArrayDeque<Memo>()
    private val redoStack = ArrayDeque<Memo>()
    private var lastHistoryAt = 0L
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun update(transform: (Memo) -> Memo) = applyChange(record = true, transform = transform)

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_memo.value)
        applyChange(record = false) { cur -> previous.keepIdentityOf(cur) }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_memo.value)
        applyChange(record = false) { cur -> next.keepIdentityOf(cur) }
    }

    private fun Memo.keepIdentityOf(cur: Memo) =
        copy(id = cur.id, createdAtMillis = cur.createdAtMillis, updatedAtMillis = cur.updatedAtMillis)

    private fun applyChange(record: Boolean, transform: (Memo) -> Memo) {
        if (deleted) return
        val before = _memo.value
        val after = transform(before)
        if (record && after == before) return
        if (record) {
            // Typing bursts become one undo step.
            val now = System.nanoTime() / 1_000_000
            if (undoStack.isEmpty() || now - lastHistoryAt > HISTORY_COALESCE_MILLIS) {
                undoStack.addLast(before)
                if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            }
            lastHistoryAt = now
            redoStack.clear()
        }
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _memo.value = after
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            // Only the wait is cancellable. A save that started must finish, or a new memo's id
            // could be lost after the insert and the next save would insert a duplicate.
            withContext(NonCancellable) { save() }
        }
    }

    fun setTitle(title: String) = update { it.copy(title = title) }
    fun setBody(body: String) = update { it.copy(body = body) }
    fun setColor(index: Int) = update { it.copy(colorIndex = index) }
    fun togglePinned() = update { it.copy(pinned = !it.pinned) }
    fun setDate(date: LocalDate?) = update { it.copy(date = date) }

    /** What was worked on the memo's date, shown next to the date. */
    val day: StateFlow<ResolvedDay?> = combine(_memo.map { it.date }.distinctUntilChanged(),
        container.scheduleRepository.snapshot) { date, snapshot ->
        date?.let { snapshot.resolver.resolve(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * [SCHED-01] A time written in the memo that has not been answered yet: offered as a schedule, or,
     * when the memo is one already, as its new time. Null when there is nothing to ask.
     */
    val scheduleOffer: StateFlow<MemoTime.Found?> = _memo.map { m ->
        val found = MemoTime.find(m, LocalDateTime.now()) ?: return@map null
        found.takeIf { m.reminderAsked != it.key && m.reminder?.at != it.at }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [SCHED-01] Makes the memo a schedule (or a plain memo again with null); [answered] is the time this answers. */
    fun setReminder(reminder: MemoReminder?, answered: MemoTime.Found? = null) = update {
        it.copy(reminder = reminder, reminderAsked = answered?.key ?: it.reminderAsked)
    }

    /** [SCHED-01] "메모로 둘게요": the same time is not offered again. */
    fun keepAsMemo(found: MemoTime.Found) = update { it.copy(reminderAsked = found.key) }

    /** Saves a copy of this memo and returns nothing; the copy shows up in the list. */
    fun duplicate() {
        val current = _memo.value
        if (current.isBlank) return
        container.appScope.launch { repo.duplicate(current) }
    }

    /** Switches between text and checklist, carrying the content over line by line. */
    fun toggleChecklist() = update { m ->
        if (m.isChecklist) {
            val text = m.checklist.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
            m.copy(isChecklist = false, body = listOf(m.body, text).filter { it.isNotBlank() }.joinToString("\n"),
                checklist = emptyList())
        } else {
            val items = m.body.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { ChecklistItem(it) }
            m.copy(isChecklist = true, body = "", checklist = items.ifEmpty { listOf(ChecklistItem("")) })
        }
    }

    fun setItemText(index: Int, text: String) = update { m ->
        m.copy(checklist = m.checklist.mapIndexed { i, item -> if (i == index) item.copy(text = text) else item })
    }

    fun setItemChecked(index: Int, checked: Boolean) = update { m ->
        m.copy(checklist = m.checklist.mapIndexed { i, item -> if (i == index) item.copy(checked = checked) else item })
    }

    /** Inserts an empty item after [index] and returns its position. */
    fun insertItemAfter(index: Int, text: String = ""): Int {
        var position = 0
        update { m ->
            val list = m.checklist.toMutableList()
            position = (index + 1).coerceIn(0, list.size)
            list.add(position, ChecklistItem(text))
            m.copy(checklist = list)
        }
        return position
    }

    fun removeItem(index: Int) = update { m ->
        if (index !in m.checklist.indices) m else m.copy(checklist = m.checklist.filterIndexed { i, _ -> i != index })
    }

    fun clearChecked() = update { m -> m.copy(checklist = m.checklist.filter { !it.checked }) }

    fun delete(onDone: () -> Unit) {
        deleted = true
        saveJob?.cancel()
        container.appScope.launch {
            saveMutex.withLock {
                val current = _memo.value
                if (current.id != 0L) repo.delete(current)
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun save() {
        saveMutex.withLock {
            if (deleted || !dirty) return
            val current = _memo.value
            if (current.id == 0L && current.isBlank) return
            val stored = repo.save(current)
            dirty = false
            // Keep the id (for new memos) without overwriting text typed during the save.
            _memo.value = _memo.value.copy(id = stored.id, createdAtMillis = stored.createdAtMillis,
                updatedAtMillis = stored.updatedAtMillis)
            if (_memo.value != current.copy(id = stored.id, createdAtMillis = stored.createdAtMillis,
                    updatedAtMillis = stored.updatedAtMillis)
            ) {
                dirty = true
            }
            _savedAt.value = stored.updatedAtMillis
        }
    }

    /** Final save when the editor closes. Blank memos are removed instead of kept as clutter. */
    override fun onCleared() {
        saveJob?.cancel()
        if (deleted) return
        container.appScope.launch {
            withContext(NonCancellable) {
                save()
                val current = _memo.value
                if (current.id != 0L && current.isBlank) repo.delete(current, offerUndo = false)
            }
        }
    }

    companion object {
        private const val AUTOSAVE_DELAY_MILLIS = 400L
        private const val HISTORY_COALESCE_MILLIS = 1_000L
        private const val MAX_HISTORY = 200
    }
}
