package com.shiftmate.calendar.data

import com.shiftmate.calendar.data.db.AppDatabase
import com.shiftmate.calendar.data.db.toEntity
import com.shiftmate.calendar.data.db.toModel
import com.shiftmate.core.memo.Memo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class MemoRepository(db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.memoDao()

    val memos: Flow<List<Memo>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    val datesWithMemos: Flow<Set<LocalDate>> =
        dao.observeDatesWithMemos().map { days -> days.mapTo(HashSet()) { LocalDate.ofEpochDay(it) } }

    fun memosOn(date: LocalDate): Flow<List<Memo>> =
        dao.observeOn(date.toEpochDay()).map { list -> list.map { it.toModel() } }

    private val _lastDeleted = MutableStateFlow<Memo?>(null)
    /** The most recently deleted memo, offered for undo until consumed. */
    val lastDeleted: StateFlow<Memo?> = _lastDeleted.asStateFlow()

    suspend fun get(id: Long): Memo? = dao.get(id)?.toModel()

    /** Inserts or updates; returns the memo as stored (with id and timestamps). */
    suspend fun save(memo: Memo): Memo {
        val now = clock()
        return if (memo.id == 0L) {
            val toInsert = memo.copy(createdAtMillis = now, updatedAtMillis = now)
            toInsert.copy(id = dao.insert(toInsert.toEntity()))
        } else {
            val toUpdate = memo.copy(updatedAtMillis = now, createdAtMillis = memo.createdAtMillis.takeIf { it > 0 } ?: now)
            dao.update(toUpdate.toEntity())
            toUpdate
        }
    }

    /** Quick memo from the calendar: one line becomes the body. */
    suspend fun addQuick(date: LocalDate?, text: String): Memo? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return save(Memo(date = date, body = trimmed))
    }

    suspend fun setPinned(memo: Memo, pinned: Boolean) {
        dao.get(memo.id)?.let { dao.update(it.copy(pinned = pinned)) }
    }

    suspend fun delete(memo: Memo, offerUndo: Boolean = true) {
        if (memo.id == 0L) return
        val stored = dao.get(memo.id)?.toModel() ?: return
        dao.delete(memo.id)
        if (offerUndo) _lastDeleted.value = stored
    }

    suspend fun undoDelete() {
        val memo = _lastDeleted.value ?: return
        _lastDeleted.value = null
        dao.restore(memo.toEntity())
    }

    fun consumeLastDeleted() {
        _lastDeleted.value = null
    }
}
