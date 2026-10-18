package com.bamnat.app.share

import android.content.Context
import com.bamnat.app.BuildConfig
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.calendar.ExtraText
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * Read-only web links to the schedule [MKT-09]. The server (server/share-worker) keeps only what
 * the page shows: dates, shift names, colours and times. Memos, pay, extra work and the kind of
 * leave other than 연차 leave the phone only when the sharer ticks them [SHR-01].
 */
data class ShareLinkState(val id: String, val url: String, val deleteToken: String, val expiresAt: Instant, val title: String)

/** [SHR-01] What a link carries besides the shifts; all off until the sharer ticks them. */
data class ShareOptions(
    val leaveKinds: Boolean = false,
    val extras: Boolean = false,
    val memos: Boolean = false,
    val pay: Boolean = false,
) {
    /** The server's names for the ticked parts, in its order. */
    val shows: List<String>
        get() = buildList {
            if (leaveKinds) add("leaveKinds")
            if (extras) add("extras")
            if (memos) add("memos")
            if (pay) add("pay")
        }
    val any: Boolean get() = shows.isNotEmpty()

    companion object {
        fun of(shows: Collection<String>) = ShareOptions("leaveKinds" in shows, "extras" in shows, "memos" in shows, "pay" in shows)
    }
}

/** [SHR-01] One month's expected pay as the sharer's app worked it out. */
data class SharedPay(val month: YearMonth, val amount: Long, val label: String)

object ShareLinkPayload {
    /** Days a link covers, from the first day of the month shown: about three months. */
    const val DAYS = 92
    val TTL_DAYS = listOf(7, 30, 90)

    /** [SHR-01] The months a link made from [from] covers, for its pay. */
    fun months(from: LocalDate): List<YearMonth> =
        generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.atDay(1).isAfter(from.plusDays(DAYS - 1L)) }.toList()

    /** [SHR-01] A day's memos as one line of at most 80 characters, the way the page shows them: each memo's first line only. */
    fun memoLine(memos: List<Memo>): String? {
        val line = memos.map { m -> m.firstLine().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }.joinToString(" · ")
        if (line.isEmpty()) return null
        return if (line.length <= MEMO_CHARS) line else line.take(MEMO_CHARS - 1).trimEnd() + "…"
    }

    /** The title, else the body's first line or a checklist's first item, never the rest of it. */
    private fun Memo.firstLine(): String {
        fun first(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return first(title) ?: if (isChecklist) checklist.firstOrNull { it.text.isNotBlank() }?.text.orEmpty() else first(body).orEmpty()
    }

    const val MEMO_CHARS = 80
    /** Up to "0.75일 무급 휴가", as the server takes it. */
    const val LEAVE_CHARS = 12

    /**
     * [SHR-01] A day's leave as a link shows it: 연차 as it is, another kind by name only when 휴가 종류
     * is ticked, else only how much of the day is off.
     */
    fun leaveLabel(leave: Leave, kinds: Boolean): String = (
        if (kinds || leave.kind == LeaveKind.ANNUAL) leave.label
        else when (leave.quarters) { 4 -> "휴가"; 2 -> "반차"; 1 -> "반반차"; else -> "0.75일 휴가" }
    ).take(LEAVE_CHARS)

    /** [SHR-01] Extra work a link carries for one day, as the server takes it. */
    const val EXTRAS_PER_DAY = 4

    fun build(
        snapshot: ScheduleSnapshot,
        from: LocalDate,
        title: String,
        ttlDays: Int,
        options: ShareOptions = ShareOptions(),
        /** [SHR-01] Each day's memo line, see [memoLine]; sent only with [ShareOptions.memos]. */
        memos: Map<LocalDate, String> = emptyMap(),
        /** [SHR-01] Sent only with [ShareOptions.pay]. */
        pay: List<SharedPay> = emptyList(),
    ): JSONObject {
        val dates = (0 until DAYS).map { from.plusDays(it.toLong()) }
        val days = JSONArray()
        val used = LinkedHashMap<Long, com.bamnat.core.model.ShiftType>()
        for (d in dates) {
            val day = snapshot.resolver.resolve(d)
            val type = day.shiftType
            val memo = memos[d]?.takeIf { options.memos }
            val extras = if (options.extras) day.extras.take(EXTRAS_PER_DAY) else emptyList()
            // [EXTRA-01] A 대근 of another shift shows as the day's second shift; other extra work stays private unless ticked.
            val second = day.coveredShift
            // A day with no shift goes in only with something on it: a 대근, or a memo or extra work that was ticked.
            if (type == null && second == null && memo == null && extras.isEmpty()) continue
            type?.let { used[it.id] = it }
            second?.let { used[it.id] = it }
            days.put(JSONObject().apply {
                put("date", d.toString())
                if (type != null) put("type", type.id)
                second?.let { put("second", it.id) }
                // The kind of leave (유급·무급 휴가) is private unless ticked; family only needs to know how much of the day is off.
                day.leave?.let { put("leave", leaveLabel(it, options.leaveKinds)) }
                // [SHR-04] No work at all as far as the link shows, for the receiver's 같이 쉬는 날: extra work
                // the sharer did not tick stays out of it too, only a 대근 counts.
                put("rest", (day.isOff || day.leave?.isFullDay == true) && (if (options.extras) day.extras.isEmpty() else second == null))
                memo?.let { put("memo", it) }
                if (extras.isNotEmpty()) put("extras", JSONArray().apply {
                    extras.forEach { e ->
                        put(JSONObject().apply {
                            put("name", (e.covered?.let { c -> ExtraText.coverName(e.kind, c) } ?: e.kind.name).take(10))
                            if (e.entry.hasTimes && e.start != null && e.end != null) put("time", "${e.start}-${e.end}")
                            if (e.minutes in 1..1440) put("minutes", e.minutes)
                        })
                    }
                })
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
                t.icon?.let { put("icon", it) }
            })
        }
        return JSONObject().apply {
            // [SHR-01] Nothing ticked stays v1, which every server version takes.
            put("v", if (options.any) 2 else 1)
            put("title", title.trim().take(30))
            put("ttlDays", ttlDays)
            put("types", types)
            put("days", days)
            if (options.any) put("shows", JSONArray(options.shows))
            if (options.pay && pay.isNotEmpty()) put("pay", JSONArray().apply {
                pay.take(6).forEach { p ->
                    put(JSONObject().apply {
                        put("month", p.month.toString())
                        put("amount", p.amount.coerceIn(0, 10_000_000_000))
                        put("label", p.label.take(10))
                    })
                }
            })
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

    /** Forgets the link; what the sharer ticked stays for the next one. */
    fun clear() = prefs.edit().remove("id").remove("url").remove("token").remove("expires").remove("title").apply()

    /** [SHR-01] What the sharer ticked last time, used again for the next link and for 고치기. */
    fun options(): ShareOptions = ShareOptions.of(prefs.getStringSet("shows", emptySet()).orEmpty())

    fun saveOptions(o: ShareOptions) = prefs.edit().putStringSet("shows", o.shows.toSet()).apply()
}

/** [SHR-02] A link as the app reads it for 받은 달력: the schedule JSON as the server keeps it. */
data class ReceivedShare(val schedule: JSONObject, val expiresAt: Instant, val updatedAt: Instant?)

/** [MKT-09][SHR-02] The link server; tests and screenshots use a fake. */
interface ShareApi {
    val enabled: Boolean
    suspend fun create(payload: JSONObject): ShareLinkState
    suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState
    suspend fun delete(state: ShareLinkState)
    /** Throws [ShareLinkException] "not_found" once the link was cut or ran out. */
    suspend fun read(id: String): ReceivedShare
}

/** [SHR-02] The link id in a shared message: "…/s/<id>" from the web page, or bamnat://s/<id> from its app button. */
object ShareLinkId {
    private val pattern = Regex("""(?:https?://[^\s/]+|bamnat:/)/s/([A-Za-z0-9]{16,32})(?![A-Za-z0-9])""")

    fun find(text: String?): String? = text?.let { pattern.find(it)?.groupValues?.get(1) }
}

class ShareLinkException(message: String) : IOException(message)

class ShareLinkClient(private val baseUrl: String = BuildConfig.SHARE_BASE_URL) : ShareApi {
    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun create(payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("POST", "/api/share", payload, null)
        readable {
            ShareLinkState(o.getString("id"), o.getString("url"), o.getString("deleteToken"),
                Instant.parse(o.getString("expiresAt")), payload.optString("title"))
        }
    }

    override suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("PUT", "/api/share/${state.id}", payload, state.deleteToken)
        readable { state.copy(url = o.getString("url"), expiresAt = Instant.parse(o.getString("expiresAt")), title = payload.optString("title")) }
    }

    /** Cuts the link. A link the server no longer has counts as cut. */
    override suspend fun delete(state: ShareLinkState) = withContext(Dispatchers.IO) {
        try {
            request("DELETE", "/api/share/${state.id}", null, state.deleteToken)
        } catch (e: ShareLinkException) {
            if (e.message != "not_found") throw e
        }
        Unit
    }

    override suspend fun read(id: String): ReceivedShare = withContext(Dispatchers.IO) {
        val o = request("GET", "/api/share/$id", null, null)
        readable {
            ReceivedShare(o.getJSONObject("schedule"), Instant.parse(o.getString("expiresAt")),
                o.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() })
        }
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
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readCapped() }.orEmpty()
            if (code >= 400) throw ShareLinkException(runCatching { JSONObject(text).getString("error") }.getOrDefault("http_$code"))
            return if (text.isBlank()) JSONObject() else readable { JSONObject(text) }
        } finally {
            conn.disconnect()
        }
    }

    /** A reply the app cannot read (a page from a wrong address, a login portal) fails like a lost connection, never crashes. */
    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw ShareLinkException("bad_response")
    } catch (e: DateTimeParseException) {
        throw ShareLinkException("bad_response")
    }

    /** The server answers in a few kilobytes and takes 64 KB at most; anything far larger is not it. */
    private fun InputStream.readCapped(): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_REPLY_BYTES) throw ShareLinkException("too_large")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_REPLY_BYTES = 256 * 1024
    }
}
