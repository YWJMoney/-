package com.shiftmate.calendar.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiftmate.calendar.AppContainer
import com.shiftmate.core.memo.ChecklistItem
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.memo.MemoOrdering
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

enum class MemoFilter(val label: String) {
    ALL("전체"), PINNED("고정"), DATED("날짜 메모"), CHECKLIST("체크리스트"), UPCOMING("다가오는 일정")
}

data class MemoListState(
    val memos: List<Memo> = emptyList(),
    val query: String = "",
    val filter: MemoFilter = MemoFilter.ALL,
    val loaded: Boolean = false,
    val totalCount: Int = 0,
)

class MemoListViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.memoRepository
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(MemoFilter.ALL)

    val state: StateFlow<MemoListState> = combine(repo.memos, query, filter) { all, q, f ->
        val today = LocalDate.now()
        val filtered = all.asSequence()
            .filter { it.matches(q) }
            .filter {
                when (f) {
                    MemoFilter.ALL -> true
                    MemoFilter.PINNED -> it.pinned
                    MemoFilter.DATED -> it.date != null
                    MemoFilter.CHECKLIST -> it.isChecklist
                    MemoFilter.UPCOMING -> it.date != null && !it.date!!.isBefore(today)
                }
            }
            .toList()
            .let { list ->
                if (f == MemoFilter.UPCOMING) list.sortedBy { it.date } else list.sortedWith(MemoOrdering)
            }
        MemoListState(memos = filtered, query = q, filter = f, loaded = true, totalCount = all.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoListState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun setFilter(f: MemoFilter) {
        filter.value = f
    }

    fun togglePin(memo: Memo) {
        viewModelScope.launch { repo.setPinned(memo, !memo.pinned) }
    }

    fun delete(memo: Memo) {
        viewModelScope.launch { repo.delete(memo) }
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
