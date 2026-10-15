package com.bamnat.app.share

import android.content.Context
import com.bamnat.app.BuildConfig
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.model.LeaveKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/**
 * Read-only web links to the schedule [MKT-09]. The server (server/share-worker) keeps only what
 * the page shows: dates, shift names, colours and times. Memos, pay and the kind of leave other
 * than 연차 never leave the phone.
 */
data class ShareLinkState(val id: String, val url: String, val deleteToken: String, val expiresAt: Instant, val title: String)

object ShareLinkPayload {
    /** Days a link covers, from the first day of the month shown: about three months. */
    const val DAYS = 92
    val TTL_DAYS = listOf(7, 30, 90)

    fun build(snapshot: ScheduleSnapshot, from: LocalDate, title: String, ttlDays: Int): JSONObject {
        val dates = (0 until DAYS).map { from.plusDays(it.toLong()) }
        val days = JSONArray()
        val used = LinkedHashMap<Long, com.bamnat.core.model.ShiftType>()
        for (d in dates) {
            val day = snapshot.resolver.resolve(d)
            val type = day.shiftType ?: continue
            used[type.id] = type
            day.second?.type?.let { used[it.id] = it }
            days.put(JSONObject().apply {
                put("date", d.toString())
                put("type", type.id)
                day.second?.let { put("second", it.type.id) }
                // The kind of leave (병가, 경조사 …) is private; family only needs to know it is a day off.
                day.leave?.let { put("leave", if (it.kind == LeaveKind.ANNUAL) it.label.take(6) else "휴가") }
            })
        }
        val types = JSONArray()
        used.values.forEach { t ->
            types.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name.take(20))
                put("short", t.label.take(4).ifEmpty { t.name.take(1) })
                put("color", "#%06X".format(t.colorArgb and 0xFFFFFF))
                if (t.start != null && t.end != null && !t.isOff) put("time", "${t.start}-${t.end}")
                if (t.isOff) put("off", true)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("title", title.trim().take(30))
            put("ttlDays", ttlDays)
            put("types", types)
            put("days", days)
        }
    }
}

/** The one link this phone made, kept so it can be updated or cut later. */
class ShareLinkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("share_link", Context.MODE_PRIVATE)

    fun load(): ShareLinkState? {
        val id = prefs.getString("id", null) ?: return null
        val state = ShareLinkState(
            id = id,
            url = prefs.getString("url", null) ?: return null,
            deleteToken = prefs.getString("token", null) ?: return null,
            expiresAt = runCatching { Instant.parse(prefs.getString("expires", "")) }.getOrNull() ?: return null,
            title = prefs.getString("title", "") ?: "",
        )
        return state.takeIf { it.expiresAt.isAfter(Instant.now()) }
    }

    fun save(s: ShareLinkState) {
        prefs.edit().putString("id", s.id).putString("url", s.url).putString("token", s.deleteToken)
            .putString("expires", s.expiresAt.toString()).putString("title", s.title).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

class ShareLinkException(message: String) : IOException(message)

class ShareLinkClient(private val baseUrl: String = BuildConfig.SHARE_BASE_URL) {
    val enabled: Boolean get() = baseUrl.isNotBlank()

    suspend fun create(payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("POST", "/api/share", payload, null)
        ShareLinkState(o.getString("id"), o.getString("url"), o.getString("deleteToken"),
            Instant.parse(o.getString("expiresAt")), payload.optString("title"))
    }

    suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("PUT", "/api/share/${state.id}", payload, state.deleteToken)
        state.copy(url = o.getString("url"), expiresAt = Instant.parse(o.getString("expiresAt")), title = payload.optString("title"))
    }

    /** Cuts the link. A link the server no longer has counts as cut. */
    suspend fun delete(state: ShareLinkState) = withContext(Dispatchers.IO) {
        try {
            request("DELETE", "/api/share/${state.id}", null, state.deleteToken)
        } catch (e: ShareLinkException) {
            if (e.message != "not_found") throw e
        }
        Unit
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            token?.let { conn.setRequestProperty("X-Delete-Token", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw ShareLinkException(runCatching { JSONObject(text).getString("error") }.getOrDefault("http_$code"))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
