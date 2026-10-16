package com.bamnat.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [LEDG-02] Payment notifications the app could not finish, kept for the user to complete. They
 * live in the app's no-backup folder, so neither Google backup nor the backup file ever holds
 * notification text, and they are dropped after 30 days.
 */
class CaptureStore(private val file: File?) {
    private val lock = Mutex()
    private val state = MutableStateFlow(load())
    val captures: StateFlow<List<Capture>> = state.asStateFlow()

    private fun load(): List<Capture> = runCatching {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Capture(o.getLong("id"), o.getLong("at"), o.optString("app"), o.optString("text"), o.optString("reason"))
        }
    }.getOrDefault(emptyList())

    private suspend fun write(list: List<Capture>) {
        state.value = list
        val f = file ?: return
        withContext(Dispatchers.IO) {
            f.parentFile?.mkdirs()
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.postedAt).put("app", it.app).put("text", it.text).put("reason", it.reason)) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        }
    }

    suspend fun has(text: String): Boolean = state.value.any { it.text == text }

    suspend fun add(postedAt: Long, app: String, text: String, reason: String) = lock.withLock {
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        write((listOf(Capture(id, postedAt, app, text, reason)) + state.value).take(MAX))
    }

    suspend fun delete(id: Long) = lock.withLock { write(state.value.filterNot { it.id == id }) }

    suspend fun purge(before: Long) = lock.withLock {
        if (state.value.any { it.postedAt < before }) write(state.value.filter { it.postedAt >= before })
    }

    suspend fun clear() = lock.withLock { write(emptyList()) }

    companion object {
        /** A flood of unreadable texts never grows without end. */
        const val MAX = 200
    }
}
