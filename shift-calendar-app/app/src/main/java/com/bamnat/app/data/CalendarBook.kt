package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [MCAL-01] One of the user's own calendars. [MAIN] is the calendar the app always had: its shifts,
 * order and days stay in the app's database, and the alarms, widgets, notices and pay follow it. Each
 * calendar added later keeps its own shifts, order and days in a database file of its own.
 */
data class PersonalCalendar(
    val id: String,
    val name: String,
    val color: Long,
    /** How this calendar's work goes, asked when it was added; null for [MAIN], which follows settings. */
    val style: WorkStyle? = null,
    /** [MCAL-01] Its shifts ring like the main calendar's; the main calendar follows the master switch only. */
    val alarms: Boolean = true,
    /** False while its first setup is still being answered; such a calendar is not listed yet. */
    val ready: Boolean = true,
    val createdAt: Long = 0,
) {
    val isMain: Boolean get() = id == MAIN
    /** The letter in its avatar. */
    val initial: String get() = if (isMain) "나" else name.trim().take(1).ifEmpty { "?" }

    companion object {
        const val MAIN = "main"
        const val MAIN_NAME = "내 달력"
        /** Avatar colours for added calendars, apart from the received ones' so the two kinds read apart. */
        val COLORS = listOf(0xFF2F80ED, 0xFF27AE60, 0xFFB5651D, 0xFF8E44AD, 0xFFD6457A, 0xFF00A88F, 0xFFE0A100, 0xFF5D6D7E)
        const val MAX_NAME = 20
    }
}

/** [MCAL-04] A row of the calendar list: one of the user's own calendars, or one someone shared. */
sealed interface CalendarKey {
    val key: String

    data class Mine(val id: String) : CalendarKey {
        override val key: String get() = "mine:$id"
    }

    data class Received(val id: String) : CalendarKey {
        override val key: String get() = "got:$id"
    }

    companion object {
        fun parse(key: String): CalendarKey? = when {
            key.startsWith("mine:") -> Mine(key.removePrefix("mine:")).takeIf { it.id.isNotEmpty() }
            key.startsWith("got:") -> Received(key.removePrefix("got:")).takeIf { it.id.isNotEmpty() }
            else -> null
        }

        /**
         * [MCAL-04] The list in the user's order: rows they placed, then any not placed yet (a calendar
         * just added or a link just received) at the end, the user's own before received ones.
         */
        fun arrange(order: List<String>, mine: List<String>, received: List<String>): List<CalendarKey> {
            val all = mine.map(::Mine) + received.map(::Received)
            val known = all.associateBy { it.key }
            val placed = order.distinct().mapNotNull(known::get)
            return placed + all.filter { it !in placed }
        }
    }
}

/** Where added calendars keep their databases; tests use in-memory ones. */
interface CalendarStorage {
    fun open(id: String): AppDatabase
    fun delete(id: String, db: AppDatabase?)

    class Files(private val context: android.content.Context) : CalendarStorage {
        override fun open(id: String): AppDatabase = AppDatabase.create(context, fileName(id))
        override fun delete(id: String, db: AppDatabase?) {
            db?.close()
            context.deleteDatabase(fileName(id))
        }
    }

    companion object {
        /** In the databases folder beside the main one, so backups and phone transfers carry it [REL-04]. */
        fun fileName(id: String) = "calendar_$id.db"
    }
}

/**
 * [MCAL-01][MCAL-04] The user's calendars: which there are, their order in the list (received
 * calendars included), and which one the calendar tab shows and edits. Kept in a small file that
 * backups and phone transfers carry; the main calendar needs nothing in it to exist, so a phone
 * from before this file opens exactly as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarBook(
    private val file: File,
    private val main: ScheduleRepository,
    private val storage: CalendarStorage,
    /** A repository over an added calendar's database, with the same kinds of extra work as the main one. */
    private val repositoryOf: (AppDatabase) -> ScheduleRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val calendars: List<PersonalCalendar>, val order: List<String>, val active: String)

    private val state = MutableStateFlow(load())

    /** Every calendar including ones still being set up, main first, then in the order they were made. */
    private val all: List<PersonalCalendar> get() = state.value.calendars

    private val _calendars = MutableStateFlow(listed(state.value))
    /** [MCAL-01] The user's calendars that are ready, in the list's order. */
    val calendars: StateFlow<List<PersonalCalendar>> = _calendars.asStateFlow()

    private val _order = MutableStateFlow(state.value.order)
    /** [MCAL-04] Stored keys of the list's rows as the user placed them; see [CalendarKey.arrange]. */
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private val _active = MutableStateFlow(state.value.active)
    /** [MCAL-01] The calendar the calendar tab shows and edits. */
    val active: StateFlow<String> = _active.asStateFlow()

    val activeCalendar: Flow<PersonalCalendar> = combine(_calendars, _active) { list, id -> list.firstOrNull { it.id == id } ?: mainOf(list) }

    fun get(id: String): PersonalCalendar? = all.firstOrNull { it.id == id }

    fun current(): PersonalCalendar = get(_active.value) ?: mainOf(all)

    private val open = HashMap<String, Pair<AppDatabase, ScheduleRepository>>()

    /** The schedule of calendar [id]; the main one for an id that is not (or no longer) there. */
    @Synchronized
    fun repository(id: String): ScheduleRepository {
        if (id == PersonalCalendar.MAIN || get(id) == null) return main
        return open.getOrPut(id) { storage.open(id).let { db -> db to repositoryOf(db) } }.second
    }

    /** [MCAL-05] An added calendar's database, for the backup. */
    @Synchronized
    fun database(id: String): AppDatabase {
        require(id != PersonalCalendar.MAIN && get(id) != null) { "Not an added calendar: $id" }
        return open.getOrPut(id) { storage.open(id).let { db -> db to repositoryOf(db) } }.first
    }

    /** [MCAL-01] What the calendar tab works on now; a write started here stays with this calendar. */
    fun activeRepository(): ScheduleRepository = repository(_active.value)

    /** [MCAL-01] The schedule on the calendar tab, following each switch. */
    val activeSnapshot: Flow<ScheduleSnapshot> = _active.flatMapLatest { repository(it).snapshot }

    /**
     * [MCAL-01] Every calendar whose alarms ring, with the name an alarm from it is told by; the main
     * calendar has none, so its alarms read as before.
     */
    val ringing: Flow<List<Pair<String?, ScheduleSnapshot>>> = _calendars.map { list -> list.filter { it.isMain || it.alarms } }
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { cal -> repository(cal.id).snapshot.map { s -> (if (cal.isMain) null else cal.name) to s } }) { it.toList() }
        }

    fun setActive(id: String) {
        val target = get(id)?.takeIf { it.ready }?.id ?: PersonalCalendar.MAIN
        if (target == _active.value) return
        write(state.value.copy(active = target))
    }

    /**
     * [MCAL-02] Starts a calendar: it has a database at once, so its first setup saves into it, but it
     * is listed only after [finishSetup]. Returns its id.
     */
    fun begin(name: String, color: Long? = null): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val made = all.count { !it.isMain }
        val cal = PersonalCalendar(
            id = id,
            name = cleanName(name, made + 2),
            color = color ?: PersonalCalendar.COLORS[made % PersonalCalendar.COLORS.size],
            ready = false,
            createdAt = clock(),
        )
        write(state.value.copy(calendars = all + cal))
        return id
    }

    /** [MCAL-02] Its first setup is saved: listed from now on, and shown. */
    fun finishSetup(id: String, style: WorkStyle) {
        if (get(id) == null) return
        write(state.value.copy(calendars = all.map { if (it.id == id) it.copy(style = style, ready = true) else it }, active = id))
    }

    /** [MCAL-02] A calendar whose first setup was started and not saved yet, to go on with. */
    fun unfinished(): PersonalCalendar? = all.lastOrNull { !it.ready }

    /** Calendars whose first setup was never saved, other than [keep]: they and their databases go. */
    fun dropUnfinished(keep: String? = null) {
        all.filter { !it.ready && it.id != keep }.forEach { delete(it.id) }
    }

    fun rename(id: String, name: String) {
        val cal = get(id) ?: return
        val clean = name.trim().take(PersonalCalendar.MAX_NAME)
        if (clean.isEmpty() || clean == cal.name) return
        update(id) { it.copy(name = clean) }
    }

    fun recolor(id: String, color: Long) = update(id) { it.copy(color = color) }

    fun setAlarms(id: String, on: Boolean) {
        if (id != PersonalCalendar.MAIN) update(id) { it.copy(alarms = on) }
    }

    /** [MCAL-01] Removes an added calendar and everything in it. The main calendar cannot go. */
    fun delete(id: String) {
        if (id == PersonalCalendar.MAIN || get(id) == null) return
        val db = synchronized(this) { open.remove(id)?.first }
        val s = state.value
        write(s.copy(
            calendars = s.calendars.filter { it.id != id },
            order = s.order.filter { it != CalendarKey.Mine(id).key },
            active = if (s.active == id) PersonalCalendar.MAIN else s.active,
        ))
        runCatching { storage.delete(id, db) }
    }

    /** [MCAL-04] Stores the list exactly as the user placed it. */
    fun arrange(keys: List<CalendarKey>) {
        write(state.value.copy(order = keys.map { it.key }.distinct()))
    }

    /**
     * [MCAL-05] What a backup carries besides the main calendar: each added calendar's details and the
     * list's order. The schedules themselves come from [repository].
     */
    fun added(): List<PersonalCalendar> = all.filter { !it.isMain && it.ready }

    /** [MCAL-05] A restore replaces the added calendars with the backup's, ids and order kept. */
    fun replaceAdded(calendars: List<PersonalCalendar>, order: List<String>?) {
        added().forEach { delete(it.id) }
        val s = state.value
        val main = s.calendars.firstOrNull { it.isMain } ?: defaultMain()
        val restoredMain = calendars.firstOrNull { it.isMain }
        val list = listOf(restoredMain?.let { main.copy(name = it.name, color = it.color) } ?: main) +
            calendars.filter { !it.isMain }.map { it.copy(ready = true) }
        write(State(list, order ?: s.order, PersonalCalendar.MAIN))
    }

    private fun update(id: String, transform: (PersonalCalendar) -> PersonalCalendar) {
        if (get(id) == null) return
        write(state.value.copy(calendars = all.map { if (it.id == id) transform(it) else it }))
    }

    private fun cleanName(name: String, n: Int) = name.trim().take(PersonalCalendar.MAX_NAME).ifEmpty { "달력 $n" }

    private fun listed(s: State): List<PersonalCalendar> {
        val ready = s.calendars.filter { it.ready }
        val rank = s.order.withIndex().associate { (i, k) -> k to i }
        // Unplaced calendars keep the order they were made in, after the placed ones.
        return ready.withIndex().sortedWith(compareBy({ rank[CalendarKey.Mine(it.value.id).key] ?: (Int.MAX_VALUE / 2 + it.index) })).map { it.value }
    }

    private fun mainOf(list: List<PersonalCalendar>) = list.firstOrNull { it.isMain } ?: defaultMain()

    @Synchronized
    private fun write(next: State) {
        val fixed = next.copy(active = next.active.takeIf { a -> next.calendars.any { it.id == a && it.ready } } ?: PersonalCalendar.MAIN)
        state.value = fixed
        _calendars.value = listed(fixed)
        _order.value = fixed.order
        _active.value = fixed.active
        save(fixed)
    }

    private fun save(s: State) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject()
                .put("v", 1)
                .put("calendars", JSONArray().apply { s.calendars.forEach { put(it.toJson()) } })
                .put("order", JSONArray(s.order))
                .put("active", s.active)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): State {
        val read = runCatching {
            if (!file.exists()) return@runCatching null
            val o = JSONObject(file.readText())
            val list = o.optJSONArray("calendars")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) } }.orEmpty()
            val order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
            State(list, order, o.optString("active", PersonalCalendar.MAIN))
        }.getOrNull()
        // [MCAL-01] The calendar the app always had becomes the first of the list as it is: nothing moves.
        val list = read?.calendars.orEmpty().distinctBy { it.id }
        val withMain = if (list.any { it.isMain }) list.sortedBy { if (it.isMain) 0 else 1 } else listOf(defaultMain()) + list
        val active = read?.active?.takeIf { a -> withMain.any { it.id == a && it.ready } } ?: PersonalCalendar.MAIN
        return State(withMain, read?.order.orEmpty(), active)
    }

    private fun defaultMain() = PersonalCalendar(PersonalCalendar.MAIN, PersonalCalendar.MAIN_NAME, MAIN_COLOR)

    companion object {
        /** The main calendar's avatar follows the theme's primary colour; this is only what is stored. */
        const val MAIN_COLOR = 0xFF2F80ED

        fun PersonalCalendar.toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("color", color).putOpt("style", style?.name)
            .put("alarms", alarms).put("ready", ready).put("created", createdAt)

        fun fromJson(o: JSONObject): PersonalCalendar? {
            val id = o.optString("id").takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() } } ?: return null
            return PersonalCalendar(
                id = id,
                name = o.optString("name").trim().take(PersonalCalendar.MAX_NAME).ifEmpty { if (id == PersonalCalendar.MAIN) PersonalCalendar.MAIN_NAME else "달력" },
                color = o.optLong("color", MAIN_COLOR),
                style = o.optString("style").takeIf { it.isNotEmpty() }?.let { s -> runCatching { WorkStyle.valueOf(s) }.getOrNull() },
                alarms = o.optBoolean("alarms", true),
                ready = o.optBoolean("ready", true),
                createdAt = o.optLong("created"),
            )
        }
    }
}
