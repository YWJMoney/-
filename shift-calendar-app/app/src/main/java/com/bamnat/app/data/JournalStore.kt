package com.bamnat.app.data

import com.bamnat.core.journal.Journal
import com.bamnat.core.journal.JournalEntry
import com.bamnat.core.journal.JournalKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [OBS-01] '밤낮이 한 일': one line for each thing the app did by itself, on this phone only. The file is in
 * no-backup storage (결정 N-D4): it says what this phone did, and holds kinds and small numbers only, never
 * an amount or a name ([Journal.safeDetail]). Writing never waits on the caller: a line goes on [scope]
 * off the main thread, and a line that cannot be written is dropped rather than failing what wrote it.
 */
class JournalStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<JournalEntry>?>(null)
    /** Null until first read; the settings list and the home line read it. */
    val entries: StateFlow<List<JournalEntry>?> = _entries.asStateFlow()

    /** Writes [kind] with [detail] ("06:30", a version); fire and forget. */
    fun note(kind: JournalKind, detail: String? = null, count: Int = 1) {
        if (count <= 0) return
        scope.launch(Dispatchers.IO) { runCatching { add(JournalEntry(clock(), kind, count, detail)) } }
    }

    /**
     * [kind] with [detail] only when its last line says something else (the next alarm moved): the alarm is
     * planned again on every edit and every start, and only a different alarm is worth a line.
     */
    fun noteIfChanged(kind: JournalKind, detail: String?) {
        scope.launch(Dispatchers.IO) { runCatching { addIfChanged(kind, detail) } }
    }

    /** [noteIfChanged], waited for: true when it wrote a line. */
    suspend fun addIfChanged(kind: JournalKind, detail: String?): Boolean = lock.withLock {
        val now = loaded()
        val last = now.lastOrNull { it.kind == kind }
        if (last != null && last.detail == detail?.let(Journal::safeDetail)) return@withLock false
        val next = Journal.add(now, JournalEntry(clock(), kind, 1, detail), zone(), today())
        write(next)
        _entries.value = next
        true
    }

    suspend fun add(entry: JournalEntry) = lock.withLock {
        val now = loaded()
        val next = Journal.add(now, entry, zone(), today())
        write(next)
        _entries.value = next
    }

    suspend fun load(): List<JournalEntry> = lock.withLock { loaded() }

    /**
     * Empties the log (설정 › 밤낮이 한 일 › 기록 지우기). [EASY-09] 모두 처음부터 does not call it: the log is
     * what the app did, not what the user told it.
     */
    suspend fun clear() = lock.withLock {
        file.delete()
        _entries.value = emptyList()
    }

    private fun today(): LocalDate = clock().atZone(zone()).toLocalDate()

    private fun loaded(): List<JournalEntry> {
        _entries.value?.let { return it }
        val read = runCatching {
            if (!file.exists()) emptyList() else file.readLines().mapNotNull(::decode)
        }.getOrDefault(emptyList())
        val trimmed = Journal.trim(read, zone(), today())
        _entries.value = trimmed
        return trimmed
    }

    private fun write(entries: List<JournalEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.joinToString("\n") { encode(it).toString() })
        tmp.renameTo(file)
    }

    companion object {
        fun encode(e: JournalEntry): JSONObject = JSONObject()
            .put("at", e.at.toEpochMilli()).put("kind", e.kind.name).put("n", e.count).putOpt("d", e.detail)

        /** A line this version cannot read (a kind from a later one) is skipped. */
        fun decode(line: String): JournalEntry? = runCatching {
            val o = JSONObject(line)
            JournalEntry(Instant.ofEpochMilli(o.getLong("at")), JournalKind.valueOf(o.getString("kind")), o.optInt("n", 1),
                o.optString("d").takeIf { it.isNotEmpty() }?.let(Journal::safeDetail))
        }.getOrNull()
    }
}
