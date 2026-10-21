package com.bamnat.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SET-01] Everything as it was before a backup or a company file was put in, kept as one backup in
 * the app's own storage (never in a phone backup), so the import can be taken back. One copy: the
 * next import replaces it, taking it back uses it up, and after [KEEP_DAYS] it is dropped.
 */
class ImportUndo(
    private val file: File,
    private val backups: BackupManager,
    private val clock: () -> Long = System::currentTimeMillis,
    /** [UNDO-03] Where the calendars the import removed wait, with their contract photos. */
    private val recordsTrash: () -> RecordsTrash? = { null },
) {
    private val _savedAt = MutableStateFlow(file.takeIf { it.exists() }?.lastModified()?.takeIf { fresh(it) })
    /** When the copy was taken, or null when there is none to go back to. */
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    init {
        if (_savedAt.value == null) file.delete()
    }

    /** Takes the copy, right before an import changes anything. */
    suspend fun save() {
        val json = backups.export()
        withContext(Dispatchers.IO) {
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(json)
            if (!part.renameTo(file)) {
                file.writeText(json)
                part.delete()
            }
            file.setLastModified(clock())
        }
        _savedAt.value = clock()
    }

    /** Puts the copy back. The copy is gone afterwards, so a second tap cannot undo twice. */
    suspend fun undo() {
        val json = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() } ?: throw BackupException("되돌릴 데이터가 없어요")
        // [UNDO-03][HRLY-10] Calendars the import removed come back with their photos; their trash entries go.
        val ids = runCatching { org.json.JSONObject(json).optJSONArray("calendars") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) } }.orEmpty()
        val trash = recordsTrash()
        val used = if (ids.isEmpty() || trash == null) emptyList() else trash.putPhotosBack(ids)
        backups.restore(json)
        if (used.isNotEmpty()) trash?.forget(used)
        drop()
    }

    fun drop() {
        file.delete()
        _savedAt.value = null
    }

    private fun fresh(at: Long) = clock() - at < KEEP_DAYS * 24 * 60 * 60 * 1000L

    companion object {
        const val KEEP_DAYS = 7L
    }
}
