package com.bamnat.app.data

import com.bamnat.core.memo.MemoSharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * [MEMO-02] 설정 › 메모 공유: which calendars see each other's memos. Nothing stored is 달력마다 따로,
 * the default. Kept in app storage beside calendars.json, so phone backups and transfers carry it, and
 * in the backup file too ([BackupManager]).
 */
class MemoSharingStore(private val file: File) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<MemoSharing> = _state.asStateFlow()

    @Synchronized
    fun set(sharing: MemoSharing) {
        val next = MemoSharing(sharing.groups.filter { it.size > 1 })
        if (next == _state.value) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(toJson(next).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _state.value = next
    }

    private fun read(): MemoSharing = runCatching { if (file.exists()) fromJson(JSONArray(file.readText())) else MemoSharing.SEPARATE }
        .getOrDefault(MemoSharing.SEPARATE)

    companion object {
        /** A list of groups, each a list of calendar ids. */
        fun toJson(s: MemoSharing): JSONArray = JSONArray(s.groups.map { JSONArray(it.sorted()) })

        fun fromJson(a: JSONArray): MemoSharing = MemoSharing(
            (0 until a.length()).mapNotNull { i ->
                a.optJSONArray(i)?.let { g -> (0 until g.length()).map { g.optString(it) }.filter { it.isNotEmpty() }.toSet() }
            }.filter { it.size > 1 },
        )
    }
}
