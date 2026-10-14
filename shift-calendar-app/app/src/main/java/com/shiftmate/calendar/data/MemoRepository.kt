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

/** Memos that were just moved to the trash, offered for undo until consumed. */
data class DeletedMemos(val memos: List<Memo>) {
    val label: String
        get() = if (memos.size == 1) {
            val m = memos.first()
            m.title.ifBlank { m.preview }.take(20)
        } else {
            ""
        }
}

class MemoRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.memoDao()

    val memos: Flow<List<Memo>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    val trash: Flow<List<Memo>> = dao.observeTrash().map { list -> list.map { it.toModel() } }

    val datesWithMemos: Flow<Set<LocalDate>> =
        dao.observeDatesWithMemos().map { days -> days.mapTo(HashSet()) { LocalDate.ofEpochDay(it) } }

    fun memosOn(date: LocalDate): Flow<List<Memo>> =
        dao.observeOn(date.toEpochDay()).map { list -> list.map { it.toModel() } }

    private val _lastDeleted = MutableStateFlow<DeletedMemos?>(null)
    /** The memos most recently moved to the trash, offered for undo until consumed. */
    val lastDeleted: StateFlow<DeletedMemos?> = _lastDeleted.asStateFlow()

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

    /** Copies a memo (as a new, unpinned memo) and returns the copy. */
    suspend fun duplicate(memo: Memo): Memo = save(memo.copy(id = 0, pinned = false, deletedAtMillis = null))

    suspend fun setPinned(memo: Memo, pinned: Boolean) = setPinned(listOf(memo.id), pinned)

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) {
        if (ids.isNotEmpty()) dao.setPinned(ids.toList(), pinned)
    }

    suspend fun setColor(ids: Collection<Long>, color: Int) {
        if (ids.isNotEmpty()) dao.setColor(ids.toList(), color)
    }

    /** Moves a memo to the trash. It can be restored for [TRASH_DAYS] days. */
    suspend fun delete(memo: Memo, offerUndo: Boolean = true) {
        if (memo.id == 0L) return
        if (!offerUndo) {
            // Blank memos left by the editor are clutter, not something to restore.
            dao.delete(memo.id)
            return
        }
        delete(listOf(memo), offerUndo = true)
    }

    suspend fun delete(memos: Collection<Memo>, offerUndo: Boolean = true) {
        val ids = memos.map { it.id }.filter { it != 0L }
        if (ids.isEmpty()) return
        dao.moveToTrash(ids, clock())
        if (offerUndo) _lastDeleted.value = DeletedMemos(memos.toList())
    }

    suspend fun undoDelete() {
        val deleted = _lastDeleted.value ?: return
        _lastDeleted.value = null
        dao.restoreFromTrash(deleted.memos.map { it.id })
    }

    fun consumeLastDeleted() {
        _lastDeleted.value = null
    }

    suspend fun restore(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.restoreFromTrash(ids.toList())
    }

    suspend fun deleteForever(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.deleteAll(ids.toList())
    }

    suspend fun emptyTrash() = dao.emptyTrash()

    /** Removes memos that have been in the trash longer than [TRASH_DAYS] days. */
    suspend fun purgeOldTrash() = dao.purgeTrash(clock() - TRASH_DAYS * DAY_MILLIS)

    companion object {
        const val TRASH_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
