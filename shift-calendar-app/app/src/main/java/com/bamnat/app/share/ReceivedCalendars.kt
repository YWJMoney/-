package com.bamnat.app.share

import com.bamnat.core.model.ShiftType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import com.bamnat.core.time.Today

/** [SHR-02] A shift as someone else's link describes it. */
data class SharedType(val id: Long, val name: String, val short: String, val color: Long, val time: String?, val off: Boolean, val icon: String?) {
    /** The same shift as this app draws it, for the calendar's own badges. */
    fun asShiftType(): ShiftType {
        val parts = time?.split("-")
        fun at(s: String?) = s?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return ShiftType(-id - 1, name, short, color, at(parts?.getOrNull(0)), at(parts?.getOrNull(1)), isOff = off, icon = icon)
    }
}

data class SharedExtra(val name: String, val time: String?, val minutes: Int?)

data class SharedDay(
    val date: LocalDate,
    val type: Long?,
    val second: Long?,
    val leave: String?,
    val memo: String?,
    val extras: List<SharedExtra>,
    /** Whether the day has no work at all as the sharer's app sees it [SHR-04]; null on links made before 1.10. */
    val rest: Boolean?,
) {
    /** Leave for the whole day: 반차, 반반차 and 0.75일 leave part of it for work. */
    val fullLeave: Boolean get() = leave != null && !leave.contains("반") && !leave.startsWith("0.75")
}

/** [SHR-02] Someone's shared schedule: the shifts, and what else they ticked [SHR-01]. */
data class SharedSchedule(
    val title: String,
    val types: List<SharedType>,
    val days: Map<LocalDate, SharedDay>,
    val pay: List<SharedPay>,
    val shows: Set<String>,
    /** [SHR-11] What [pay] is: 급여, 일감 수입 or 매출. */
    val payKind: com.bamnat.core.work.MoneyShare = com.bamnat.core.work.MoneyShare.PAY,
) {
    private val byId = types.associateBy { it.id }
    fun type(id: Long?): SharedType? = id?.let(byId::get)

    val first: LocalDate? = days.keys.minOrNull()
    val last: LocalDate? = days.keys.maxOrNull()
    fun covers(date: LocalDate): Boolean = first != null && last != null && !date.isBefore(first) && !date.isAfter(last)
    fun covers(month: YearMonth): Boolean = first != null && last != null &&
        !month.isBefore(YearMonth.from(first)) && !month.isAfter(YearMonth.from(last))

    /**
     * [SHR-04] Whether they have the day off: what their app said, else no shift, an off shift or
     * leave that is not a half day, with nothing covered. Null outside the dates the link covers.
     */
    fun isRest(date: LocalDate): Boolean? {
        if (!covers(date)) return null
        val d = days[date] ?: return true
        d.rest?.let { return it }
        if (d.second != null || d.extras.isNotEmpty()) return false
        if (d.fullLeave) return true
        return d.type == null || type(d.type)?.off == true
    }

    companion object {
        /** Reads the server's schedule JSON, skipping anything it does not know. */
        fun parse(o: JSONObject): SharedSchedule {
            val types = o.optJSONArray("types").objects().mapNotNull { t ->
                val id = t.optLong("id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return@mapNotNull null
                val color = t.optString("color").removePrefix("#").toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF9E9E9E
                SharedType(id, t.optString("name"), t.optString("short").ifEmpty { t.optString("name").take(1) }, color,
                    t.str("time"), t.optBoolean("off"), t.str("icon"))
            }
            val days = o.optJSONArray("days").objects().mapNotNull { d ->
                val date = runCatching { LocalDate.parse(d.optString("date")) }.getOrNull() ?: return@mapNotNull null
                SharedDay(
                    date = date,
                    type = d.long("type"),
                    second = d.long("second"),
                    leave = d.str("leave"),
                    memo = d.str("memo"),
                    // [SHR-11] A freelancer's jobs show where extra work does: a name and a time on the day.
                    extras = d.optJSONArray("extras").objects().map { e ->
                        SharedExtra(e.optString("name"), e.str("time"), e.optInt("minutes", 0).takeIf { it > 0 })
                    } + d.optJSONArray("jobs").objects().map { j -> SharedExtra(j.optString("name"), j.str("time"), null) },
                    rest = if (d.has("rest")) d.optBoolean("rest") else null,
                )
            }.associateBy { it.date }
            val pay = o.optJSONArray("pay").objects().mapNotNull { p ->
                val month = runCatching { YearMonth.parse(p.optString("month")) }.getOrNull() ?: return@mapNotNull null
                SharedPay(month, p.optLong("amount"), p.optString("label"))
            }
            val shows = o.optJSONArray("shows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
            return SharedSchedule(o.optString("title"), types, days, pay, shows, com.bamnat.core.work.MoneyShare.ofKey(o.str("payKind")))
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
        private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    }
}

/**
 * [SHR-02] A received calendar kept on this phone beside the user's own. [schedule] is null once the
 * link was cut or ran out: what the sharer took back does not stay here.
 */
data class ReceivedCalendar(
    val id: String,
    val title: String,
    val color: Long,
    val addedAt: Instant,
    val fetchedAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant?,
    val raw: JSONObject?,
    /** [SHR-12] The server's tag for [raw], so the next look downloads it only when the sharer changed it. */
    val etag: String? = null,
    /** [SHR-13] Why the developer took the link down, when it was taken down rather than cut by the sharer. */
    val removedReason: String? = null,
) {
    val schedule: SharedSchedule? by lazy { raw?.let { runCatching { SharedSchedule.parse(it) }.getOrNull() } }
    val gone: Boolean get() = raw == null
    /** Like an account name: what the sharer typed, e.g. 민지. */
    val name: String get() = title.trim().ifEmpty { "이름 없음" }
    /** 민지의 근무표, as the shared page says. */
    val fullName: String get() = title.trim().takeIf { it.isNotEmpty() }?.let { "${it}의 근무표" } ?: "이름 없는 근무표"
    /** The letter in its avatar. */
    val initial: String get() = title.trim().take(1).ifEmpty { "?" }

    companion object {
        /** Avatar colours, one per calendar in the order they were added. */
        val COLORS = listOf(0xFFE8833A, 0xFF2E9D8F, 0xFF7C5CC4, 0xFFD45087, 0xFF3D7DD8, 0xFF8A9A2B)
    }
}

/**
 * [SHR-02] Received calendars in one file under no-backup storage: someone else's schedule is not
 * the user's to back up. Which calendar is on screen lives only in memory, so the app always opens
 * on the user's own.
 */
class ReceivedCalendarStore(
    private val file: File,
    private val clock: () -> Instant = Today::instant,
    /** [SHR-02] Where the file is written: off the main thread, one write at a time, in order. */
    private val writer: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "received-calendars").apply { isDaemon = true }
    },
) {
    private val _calendars = MutableStateFlow(load())
    val calendars: StateFlow<List<ReceivedCalendar>> = _calendars.asStateFlow()

    private val _viewing = MutableStateFlow<String?>(null)
    /** The received calendar on screen; null is the user's own. */
    val viewing: StateFlow<String?> = _viewing.asStateFlow()

    private val _opening = MutableStateFlow<String?>(null)
    /** A link id the app was opened with, waiting for the calendar to offer it. */
    val opening: StateFlow<String?> = _opening.asStateFlow()

    fun get(id: String): ReceivedCalendar? = _calendars.value.firstOrNull { it.id == id }

    fun view(id: String?) {
        _viewing.value = id?.takeIf { i -> _calendars.value.any { it.id == i } }
    }

    fun open(id: String?) {
        _opening.value = id
    }

    /** Brings a kept calendar up to date; null when it was taken out meanwhile, which it stays. */
    fun update(id: String, share: ReceivedShare): ReceivedCalendar? = if (get(id) == null) null else put(id, share)

    /** Adds the link, or brings an existing one up to date, and returns it. */
    fun put(id: String, share: ReceivedShare): ReceivedCalendar {
        val now = clock()
        val old = get(id)
        val title = share.schedule.optString("title").trim()
        val color = old?.color ?: ReceivedCalendar.COLORS[_calendars.value.size % ReceivedCalendar.COLORS.size]
        val cal = ReceivedCalendar(id, title, color, old?.addedAt ?: now, now, share.updatedAt, share.expiresAt, share.schedule, share.etag)
        write(if (old == null) _calendars.value + cal else _calendars.value.map { if (it.id == id) cal else it })
        return cal
    }

    /** [SHR-12] Asked and nothing changed: the copy here is current as of now. */
    fun touch(id: String) {
        val now = clock()
        if (get(id)?.gone == false) write(_calendars.value.map { if (it.id == id) it.copy(fetchedAt = now) else it })
    }

    /** The sharer cut the link or it ran out: the schedule goes, the entry stays to say so; [reason] the developer's, if they took it down. */
    fun markGone(id: String, reason: String? = null) {
        val now = clock()
        write(_calendars.value.map { if (it.id == id) it.copy(raw = null, fetchedAt = now, etag = null, removedReason = reason) else it })
    }

    /** Clears what ran out by the phone's own clock, even with no internet to ask. */
    fun expire(now: Instant = clock()) {
        if (_calendars.value.any { !it.gone && it.expiresAt?.isBefore(now) == true }) {
            write(_calendars.value.map { if (!it.gone && it.expiresAt?.isBefore(now) == true) it.copy(raw = null, etag = null) else it })
        }
    }

    fun remove(id: String) {
        write(_calendars.value.filter { it.id != id })
        if (_viewing.value == id) _viewing.value = null
    }

    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Updates the list at once and saves it in the background: up to ten schedules serialised on the
     * main thread froze the calendar while it opened or refreshed. Writes queued close together
     * collapse into one that saves the latest list.
     */
    private fun write(list: List<ReceivedCalendar>) {
        _calendars.value = list
        if (pending.compareAndSet(false, true)) writer.execute {
            pending.set(false)
            save(_calendars.value)
        }
    }

    private fun save(list: List<ReceivedCalendar>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): List<ReceivedCalendar> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
    }.getOrDefault(emptyList())

    private fun ReceivedCalendar.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("color", color)
        put("addedAt", addedAt.toString())
        fetchedAt?.let { put("fetchedAt", it.toString()) }
        updatedAt?.let { put("updatedAt", it.toString()) }
        expiresAt?.let { put("expiresAt", it.toString()) }
        raw?.let { put("schedule", it) }
        etag?.let { put("etag", it) }
        removedReason?.let { put("removedReason", it) }
    }

    private fun fromJson(o: JSONObject): ReceivedCalendar? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        fun time(key: String) = o.optString(key).takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return ReceivedCalendar(id, o.optString("title"), o.optLong("color", ReceivedCalendar.COLORS[0]),
            time("addedAt") ?: Instant.EPOCH, time("fetchedAt"), time("updatedAt"), time("expiresAt"), o.optJSONObject("schedule"),
            o.optString("etag").takeIf { it.isNotEmpty() }, o.optString("removedReason").takeIf { it.isNotEmpty() })
    }

    companion object {
        const val MAX = 10
    }
}
