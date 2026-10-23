package com.bamnat.app.share

import android.content.Context
import com.bamnat.app.BuildConfig
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.calendar.ExtraText
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
data class ShareLinkState(
    val id: String,
    val url: String,
    val deleteToken: String,
    val expiresAt: Instant,
    val title: String,
    /** [SHR-12] The period the sharer picked, offered again for 연장; 30 for a link made before 1.14. */
    val ttlDays: Int = 30,
    /** [SHR-12] The first month the link shows; null for a link made before 1.14, which then starts this month. */
    val from: YearMonth? = null,
)

/** [SHR-01] What a link carries besides the shifts; all off until the sharer ticks them. */
data class ShareOptions(
    val leaveKinds: Boolean = false,
    val extras: Boolean = false,
    val memos: Boolean = false,
    /** [SHR-11] The month's money in the user's own kind: pay, job income or sales, see [com.bamnat.core.work.MoneyShare]. */
    val pay: Boolean = false,
    /** [SHR-11] Booked jobs by title and time, for field and freelance work; never the client, place or price. */
    val jobs: Boolean = false,
) {
    /** The server's names for the ticked parts, in its order. */
    val shows: List<String>
        get() = buildList {
            if (leaveKinds) add("leaveKinds")
            if (extras) add("extras")
            if (memos) add("memos")
            if (pay) add("pay")
            if (jobs) add("jobs")
        }
    val any: Boolean get() = shows.isNotEmpty()

    companion object {
        fun of(shows: Collection<String>) =
            ShareOptions("leaveKinds" in shows, "extras" in shows, "memos" in shows, "pay" in shows, "jobs" in shows)
    }
}

/** [SHR-01] One month's expected pay as the sharer's app worked it out; [SHR-11] or their job income or sales. */
data class SharedPay(val month: YearMonth, val amount: Long, val label: String)

/** [SHR-11] A booked job as a link shows it: what and when, nothing about who or how much. */
data class SharedJob(val name: String, val time: String?)

object ShareLinkPayload {
    /** Days a link covers, from the first day of the month shown: about three months. */
    const val DAYS = 92
    val TTL_DAYS = listOf(7, 30, 90, 180, 365)
    /** [PROV-04] Periods past three months are 프로's; a link with one extends by 90 days without it. */
    val PRO_TTL_DAYS = setOf(180, 365)
    const val FREE_TTL_MAX = 90

    /** [PROV-04] "180일", and a year as "1년". */
    fun ttlLabel(days: Int): String = if (days == 365) "1년" else "${days}일"

    /** [PROV-04] The period a link is kept open for when 늘리기 asks for [days]: a 프로 period without 프로 is 90 days. */
    fun allowedTtl(days: Int, pro: Boolean): Int = if (pro || days !in PRO_TTL_DAYS) days else FREE_TTL_MAX

    /**
     * [PROV-04] The time zone a calendar feed's times are in, as the server takes it ("Asia/Seoul"); null
     * for one it would refuse ("UTC", "GMT+09:00"), which then reads as Seoul time.
     */
    fun feedZone(zone: java.time.ZoneId): String? = zone.id.takeIf { ZONE.matches(it) && it.length <= 40 }
    private val ZONE = Regex("""[A-Za-z]+(?:/[A-Za-z0-9_+-]+){1,2}""")

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
    /** [SHR-11] Jobs a link carries for one day, and the characters of each title, as the server takes them. */
    const val JOBS_PER_DAY = 4
    const val JOB_CHARS = 12

    /**
     * [SHR-12] What a payload says about the schedule, without the period: the app sends a link again
     * only when this changed since it last sent it.
     */
    fun fingerprint(payload: JSONObject): String {
        val copy = JSONObject(payload.toString()).apply { remove("ttlDays") }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(copy.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * [SHR-12] The month a link sent now starts at: the month it was made from, or this month once that
     * one is over, so the three months move along with time and never show only the past.
     */
    fun startMonth(link: ShareLinkState, today: LocalDate): YearMonth =
        maxOf(link.from ?: YearMonth.from(today), YearMonth.from(today))

    /** [SHR-11] Whether a payload has anything to show: a server refuses a link with no days and no money. */
    fun hasContent(payload: JSONObject): Boolean =
        (payload.optJSONArray("days")?.length() ?: 0) > 0 || (payload.optJSONArray("pay")?.length() ?: 0) > 0

    fun build(
        snapshot: ScheduleSnapshot,
        from: LocalDate,
        title: String,
        /** [SHR-12] Null keeps the link's end date: what the app sends by itself after a change. */
        ttlDays: Int?,
        options: ShareOptions = ShareOptions(),
        /** [SHR-01] Each day's memo line, see [memoLine]; sent only with [ShareOptions.memos]. */
        memos: Map<LocalDate, String> = emptyMap(),
        /** [SHR-01] Sent only with [ShareOptions.pay]. */
        pay: List<SharedPay> = emptyList(),
        /** [SHR-11] What [pay] is: the server and the receiving app name it by this. */
        payKind: com.bamnat.core.work.MoneyShare = com.bamnat.core.work.MoneyShare.PAY,
        /** [SHR-11] Each day's jobs; sent only with [ShareOptions.jobs]. */
        jobs: Map<LocalDate, List<SharedJob>> = emptyMap(),
        /** [FEAT-15] The calendar feed (…/s/<id>.ics) is on, with its times in this zone; null keeps it off. */
        feed: java.time.ZoneId? = null,
    ): JSONObject {
        val dates = (0 until DAYS).map { from.plusDays(it.toLong()) }
        val days = JSONArray()
        val used = LinkedHashMap<Long, com.bamnat.core.model.ShiftType>()
        for (d in dates) {
            val day = snapshot.resolver.resolve(d)
            val type = day.shiftType
            val memo = memos[d]?.takeIf { options.memos }
            val extras = if (options.extras) day.extras.take(EXTRAS_PER_DAY) else emptyList()
            val booked = if (options.jobs) jobs[d].orEmpty().take(JOBS_PER_DAY) else emptyList()
            // [EXTRA-01] A 대근 of another shift shows as the day's second shift; other extra work stays private unless ticked.
            val second = day.coveredShift
            // A day with no shift goes in only with something on it: a 대근, or a memo, extra work or job that was ticked.
            if (type == null && second == null && memo == null && extras.isEmpty() && booked.isEmpty()) continue
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
                // [SHR-11] A booked job is work too, when the sharer shows jobs.
                put("rest", (day.isOff || day.leave?.isFullDay == true) && (if (options.extras) day.extras.isEmpty() else second == null) && booked.isEmpty())
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
                if (booked.isNotEmpty()) put("jobs", JSONArray().apply {
                    booked.forEach { j ->
                        put(JSONObject().apply {
                            put("name", j.name.trim().take(JOB_CHARS).ifEmpty { "일감" })
                            j.time?.let { put("time", it) }
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
            ttlDays?.let { put("ttlDays", it) }
            // [FEAT-15] Without "ics" the server serves no feed for the link; an older server ignores both.
            feed?.let { z ->
                put("ics", true)
                feedZone(z)?.let { put("tz", it) }
            }
            put("types", types)
            put("days", days)
            if (options.any) put("shows", JSONArray(options.shows))
            if (options.pay && pay.isNotEmpty()) put("payKind", payKind.key)
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

/**
 * [PROV-04] Which of a calendar's links: 1 is the one every calendar has, named by the calendar's id
 * alone as links always were; 프로 adds up to [MAX] in all, "<calendar>~2" to "<calendar>~5", each sent to
 * whom and for as long as the sharer likes. The key is what the store, the automatic update and the
 * expiry notice go by.
 */
object ShareLinkKey {
    const val MAX = 5
    /** Links a calendar has without 프로. */
    const val FREE = 1
    private const val SEP = '~'

    fun of(calendarId: String, slot: Int): String = if (slot <= 1) calendarId else "$calendarId$SEP$slot"
    fun calendar(key: String): String = key.substringBefore(SEP)
    fun slot(key: String): Int = key.substringAfter(SEP, "1").toIntOrNull()?.takeIf { it in 1..MAX } ?: 1
    /** Every link [calendarId] can have, the first first. */
    fun all(calendarId: String): List<String> = (1..MAX).map { of(calendarId, it) }
}

/**
 * The link this phone made for one calendar, kept so it can be updated or cut later. [SHR-12] One
 * instance per link in the app ([com.bamnat.app.AppContainer.shareLinksOf]), so the sheet, the
 * automatic update and the expiry notice all see the same link as it changes. [MCAL-03] Each of the
 * user's calendars has its own link; the main calendar keeps the file links were always kept in.
 * [PROV-04] [key] says which calendar and which of its links, see [ShareLinkKey].
 */
class ShareLinkStore(
    context: Context,
    val key: String = com.bamnat.app.data.PersonalCalendar.MAIN,
    private val clock: () -> Instant = Instant::now,
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName(key), Context.MODE_PRIVATE)
    /**
     * [QA-03] Set once the calendar's links were forgotten ([retire]): a send still on its way with this
     * store must not write the link back into a file that was just deleted.
     */
    @Volatile private var retired = false
    /** The calendar this link shows. */
    val calendarId: String get() = ShareLinkKey.calendar(key)
    /** [PROV-04] Which of the calendar's links, from 1. */
    val slot: Int get() = ShareLinkKey.slot(key)

    companion object {
        fun prefsName(key: String) =
            if (key == com.bamnat.app.data.PersonalCalendar.MAIN) "share_link" else "share_link_$key"

        /** [MCAL-03] A removed calendar's link details go with it; [PROV-04] all of its links. */
        fun forget(context: Context, calendarId: String) {
            for (key in ShareLinkKey.all(calendarId)) {
                if (key == com.bamnat.app.data.PersonalCalendar.MAIN) continue
                context.applicationContext.getSharedPreferences(prefsName(key), Context.MODE_PRIVATE).edit().clear().commit()
                context.applicationContext.deleteSharedPreferences(prefsName(key))
            }
        }
    }

    private val _link = MutableStateFlow(load())
    /** The link, or null when there is none or it ran out. */
    val link: StateFlow<ShareLinkState?> = _link.asStateFlow()

    fun load(): ShareLinkState? {
        val id = prefs.getString("id", null) ?: return null
        val state = ShareLinkState(
            id = id,
            url = prefs.getString("url", null) ?: return null,
            deleteToken = prefs.getString("token", null) ?: return null,
            expiresAt = runCatching { Instant.parse(prefs.getString("expires", "")) }.getOrNull() ?: return null,
            title = prefs.getString("title", "") ?: "",
            ttlDays = prefs.getInt("ttl", 30),
            from = prefs.getString("from", null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
        )
        return state.takeIf { it.expiresAt.isAfter(clock()) }
    }

    /** [SHR-12] Saves the link as the server now has it; [sent] is the [ShareLinkPayload.fingerprint] of what was sent. */
    fun save(s: ShareLinkState, sent: String? = null) {
        if (retired) return
        prefs.edit().putString("id", s.id).putString("url", s.url).putString("token", s.deleteToken)
            .putString("expires", s.expiresAt.toString()).putString("title", s.title).putInt("ttl", s.ttlDays)
            .apply { if (s.from != null) putString("from", s.from.toString()) else remove("from") }
            .apply { if (sent != null) putString("sent", sent) }
            .apply()
        _link.value = s
    }

    /** [PROV-04] A link from a backup, kept like one made here and sent again at the next update. */
    fun restore(s: ShareLinkState, auto: Boolean, feed: Boolean, options: ShareOptions) {
        if (retired) return
        prefs.edit().remove("sent").remove("noticed").apply()
        save(s)
        this.auto = auto
        this.feed = feed
        saveOptions(options)
    }

    /** Forgets the link; what the sharer ticked stays for the next one. */
    fun clear() {
        prefs.edit().remove("id").remove("url").remove("token").remove("expires").remove("title").remove("ttl").remove("from")
            .remove("sent").remove("noticed").apply()
        _link.value = null
    }

    /** [MCAL-03][QA-03] The calendar went: the link is forgotten, and nothing written with this store from now on is kept. */
    fun retire() {
        retired = true
        clear()
    }

    /** [SHR-02] Drops a link that ran out while the app stayed open. */
    fun expire() {
        if (_link.value?.expiresAt?.isAfter(clock()) == false) _link.value = null
    }

    /** [SHR-12] The fingerprint of what the server last got, null when unknown (a link from before 1.14). */
    val sent: String? get() = prefs.getString("sent", null)

    /** [SHR-12] Whether a change on the calendar goes to the link by itself; on unless the sharer turned it off. */
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) { if (!retired) prefs.edit().putBoolean("auto", value).apply() }

    /** [SHR-12] The end date the expiry notice was last posted for, so it comes once per period. */
    var noticedFor: Instant?
        get() = prefs.getString("noticed", null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        set(value) { if (!retired) prefs.edit().apply { if (value == null) remove("noticed") else putString("noticed", value.toString()) }.apply() }

    /** [SHR-01] What the sharer ticked last time, used again for the next link and for 고치기. */
    fun options(): ShareOptions = ShareOptions.of(prefs.getStringSet("shows", emptySet()).orEmpty())

    fun saveOptions(o: ShareOptions) { if (!retired) prefs.edit().putStringSet("shows", o.shows.toSet()).apply() }

    /**
     * [FEAT-15] Whether the link also serves a calendar feed (…/s/<id>.ics) to subscribe to; off until the
     * sharer turns it on. It goes out only with 프로 ([com.bamnat.app.tier.Feature.ICS_FEED]).
     */
    var feed: Boolean
        get() = prefs.getBoolean("ics", false)
        set(value) { if (!retired) prefs.edit().putBoolean("ics", value).apply() }
}

/** [FEAT-15] A link's calendar feed address: the page's address with .ics, which the server serves while the feed is on. */
fun ShareLinkState.feedUrl(): String = url.substringBefore('?').trimEnd('/') + ".ics"

/** [SHR-02] A link as the app reads it for 받은 달력: the schedule JSON as the server keeps it. */
data class ReceivedShare(
    val schedule: JSONObject,
    val expiresAt: Instant,
    val updatedAt: Instant?,
    /** [SHR-12] The server's tag for this version, asked with next time so an unchanged link is not downloaded again. */
    val etag: String? = null,
)

/** [MKT-09][SHR-02] The link server; tests and screenshots use a fake. */
interface ShareApi {
    val enabled: Boolean
    suspend fun create(payload: JSONObject): ShareLinkState
    suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState
    suspend fun delete(state: ShareLinkState)
    /** Throws [ShareLinkException] "not_found" once the link was cut or ran out. */
    suspend fun read(id: String): ReceivedShare

    /**
     * [SHR-12] Reads the link only if it changed since the version tagged [etag]: null when it did not.
     * A server or fake without tags always reads it.
     */
    suspend fun readIfChanged(id: String, etag: String?): ReceivedShare? = read(id)

    /** [SRV-05] Reports a received link to the developer ([ShareReport] reasons); nobody learns who sent it. */
    suspend fun report(id: String, reason: String) {}
}

/** [SRV-05] Why a link is reported: the share server's codes and the words for them. */
enum class ShareReport(val code: String, val label: String) {
    PERSONAL("personal", "다른 사람의 개인정보가 있어요"),
    ABUSE("abuse", "욕설·괴롭힘이 있어요"),
    SPAM("spam", "광고·스팸이에요"),
    ILLEGAL("illegal", "불법 정보예요"),
    OTHER("other", "그 밖의 문제"),
}

/** [SHR-02] The link id in a shared message: "…/s/<id>" from the web page, or bamnat://s/<id> from its app button. */
object ShareLinkId {
    private val pattern = Regex("""(?:https?://[^\s/]+|bamnat:/)/s/([A-Za-z0-9]{16,32})(?![A-Za-z0-9])""")

    fun find(text: String?): String? = text?.let { pattern.find(it)?.groupValues?.get(1) }
}

class ShareLinkException(
    message: String,
    /** [SHR-13] Why the developer took the link down, when the share server says (410 removed). */
    val removedReason: String? = null,
) : IOException(message)

class ShareLinkClient(private val baseUrl: String = BuildConfig.SHARE_BASE_URL) : ShareApi {
    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun create(payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("POST", "/api/share", payload, null).body
        readable {
            ShareLinkState(o.getString("id"), o.getString("url"), o.getString("deleteToken"),
                Instant.parse(o.getString("expiresAt")), payload.optString("title"), ttlDays = payload.optInt("ttlDays", 30))
        }
    }

    override suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState = withContext(Dispatchers.IO) {
        val o = request("PUT", "/api/share/${state.id}", payload, state.deleteToken).body
        readable {
            state.copy(url = o.getString("url"), expiresAt = Instant.parse(o.getString("expiresAt")), title = payload.optString("title"),
                ttlDays = if (payload.has("ttlDays")) payload.optInt("ttlDays", state.ttlDays) else state.ttlDays)
        }
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

    override suspend fun read(id: String): ReceivedShare = readIfChanged(id, null)!!

    override suspend fun readIfChanged(id: String, etag: String?): ReceivedShare? = withContext(Dispatchers.IO) {
        val reply = request("GET", "/api/share/$id", null, null, etag)
        if (reply.code == HttpURLConnection.HTTP_NOT_MODIFIED) return@withContext null
        val o = reply.body
        readable {
            ReceivedShare(o.getJSONObject("schedule"), Instant.parse(o.getString("expiresAt")),
                o.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() },
                reply.etag)
        }
    }

    override suspend fun report(id: String, reason: String) = withContext(Dispatchers.IO) {
        request("POST", "/api/share/$id/report", JSONObject().put("reason", reason), null)
        Unit
    }

    private class Reply(val code: Int, val body: JSONObject, val etag: String?)

    private fun request(method: String, path: String, body: JSONObject?, token: String?, etag: String? = null): Reply {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            token?.let { conn.setRequestProperty("X-Delete-Token", it) }
            etag?.let { conn.setRequestProperty("If-None-Match", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return Reply(code, JSONObject(), etag)
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readCapped() }.orEmpty()
            // [SRV-05] A link the developer took down (410 removed) is gone like one its sharer cut;
            // [SHR-13] the reason the developer gave comes with it.
            if (code >= 400) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                val word = err?.optString("error")?.takeIf { it.isNotEmpty() } ?: "http_$code"
                throw ShareLinkException(if (word == "removed") "not_found" else word,
                    err?.optString("reason")?.takeIf { word == "removed" && it.isNotBlank() }?.take(200))
            }
            return Reply(code, if (text.isBlank()) JSONObject() else readable { JSONObject(text) }, conn.getHeaderField("ETag"))
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
