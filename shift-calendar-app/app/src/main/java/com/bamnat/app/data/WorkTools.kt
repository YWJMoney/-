package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.money.Quote
import com.bamnat.core.money.QuoteItem
import com.bamnat.core.money.QuoteVat
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.JobOffer
import com.bamnat.core.work.Staff
import com.bamnat.core.work.StaffRoster
import com.bamnat.core.work.StaffShift
import com.bamnat.core.work.Weather
import com.bamnat.core.work.WeatherNote
import com.bamnat.core.work.WorkContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors

/** [HRLY-11] 퇴사 처리 as the user saved it. */
data class QuitRecord(
    val lastWorkDay: LocalDate,
    val lastEmployed: LocalDate,
    val unusedLeave: Int? = null,
    /** The calendar's repeating schedule was stopped after [lastEmployed]. */
    val scheduleStopped: Boolean = false,
    /** 입사일 typed here when the pay setup has none. */
    val hire: LocalDate? = null,
    /** 최근 3개월 받은 돈, typed for 퇴직금; null estimates it from the hours. */
    val threeMonthPay: Long? = null,
)

/**
 * [HRLY-09][HRLY-10][HRLY-11][FLD-11][SHOP-10][SHOP-11] One calendar's work tools: the offers being
 * compared, the 근로계약서, 퇴사 정리, saved 견적서, 날씨 메모 and the staff schedule. Kept apart from the
 * database in a small JSON file of the calendar's own, so no table changes.
 */
data class WorkTools(
    val offers: List<JobOffer> = emptyList(),
    val contract: WorkContract = WorkContract(),
    val quit: QuitRecord? = null,
    val quotes: List<Quote> = emptyList(),
    val weather: Map<LocalDate, WeatherNote> = emptyMap(),
    val roster: StaffRoster = StaffRoster(),
    /** [HRLY-10] The memo that reminds of the contract's end, so it is changed instead of added again. */
    val contractMemo: Long? = null,
)

/**
 * [HRLY-09][HRLY-10][HRLY-11][FLD-11][SHOP-10][SHOP-11] Each calendar's [WorkTools] in
 * files/calendars/<id>/work_tools.json (the main calendar's under "main"), which phone backups carry.
 * Contract photos sit beside it in contract/. A file that cannot be read is an empty set of tools.
 */
class WorkToolsStore internal constructor(private val dirOf: (String) -> File) {
    private val flows = HashMap<String, MutableStateFlow<WorkTools>>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "work-tools").apply { isDaemon = true } }

    fun dir(calendarId: String): File = dirOf(calendarId)
    private fun file(calendarId: String) = File(dirOf(calendarId), FILE)

    @Synchronized
    fun of(calendarId: String): StateFlow<WorkTools> = flows.getOrPut(calendarId) {
        MutableStateFlow(runCatching { decode(JSONObject(file(calendarId).readText())) }.getOrDefault(WorkTools()))
    }.asStateFlow()

    fun current(calendarId: String): WorkTools = of(calendarId).value

    @Synchronized
    fun update(calendarId: String, change: (WorkTools) -> WorkTools) {
        of(calendarId)
        val flow = flows.getValue(calendarId)
        val next = change(flow.value)
        if (next == flow.value) return
        flow.value = next
        val text = encode(next).toString()
        writer.execute { write(calendarId, text) }
    }

    private fun write(calendarId: String, text: String) {
        runCatching {
            val dir = dirOf(calendarId).apply { mkdirs() }
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(calendarId))) { file(calendarId).writeText(text); tmp.delete() }
        }
    }

    /** [HRLY-10] A contract photo's file, or null for a name that is not one of ours. */
    fun photo(calendarId: String, name: String): File? =
        name.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }?.let { File(File(dirOf(calendarId), PHOTOS), it) }

    fun photoDir(calendarId: String): File = File(dirOf(calendarId), PHOTOS)

    /** The tools of [calendarId] for a backup; null when there is nothing in them. */
    fun export(calendarId: String): JSONObject? = current(calendarId).takeIf { it != WorkTools() }?.let(::encode)

    /** Puts back what a backup held; photos are not in backups, so their names are kept only if the files are here. */
    fun restore(calendarId: String, o: JSONObject?) {
        val tools = o?.let { runCatching { decode(it) }.getOrNull() } ?: return
        val photos = tools.contract.photos.filter { photo(calendarId, it)?.exists() == true }
        update(calendarId) { tools.copy(contract = tools.contract.copy(photos = photos)) }
    }

    companion object {
        const val FILE = "work_tools.json"
        const val PHOTOS = "contract"

        @Volatile private var instance: WorkToolsStore? = null

        fun get(context: Context): WorkToolsStore = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: WorkToolsStore { id -> CalendarStorage.filesOf(app, id) }.also { instance = it }
        }

        internal fun encode(t: WorkTools): JSONObject = JSONObject()
            .put("v", 1)
            .putOpt("contractMemo", t.contractMemo)
            .put("offers", JSONArray().apply { t.offers.forEach { put(offerJson(it)) } })
            .putOpt("contract", t.contract.takeIf { !it.isEmpty }?.let(::contractJson))
            .putOpt("quit", t.quit?.let { q ->
                JSONObject().put("last", q.lastWorkDay.toString()).put("employed", q.lastEmployed.toString())
                    .putOpt("leave", q.unusedLeave).put("stopped", q.scheduleStopped)
                    .putOpt("hire", q.hire?.toString()).putOpt("pay3", q.threeMonthPay)
            })
            .put("quotes", JSONArray().apply { t.quotes.forEach { put(quoteJson(it)) } })
            .put("weather", JSONArray().apply {
                t.weather.values.sortedBy { it.date }.forEach { w ->
                    put(JSONObject().put("d", w.date.toString()).put("w", w.weather.name).putOpt("t", w.temperature))
                }
            })
            .put("roster", JSONObject().put("breaks", t.roster.legalBreaks).put("staff", JSONArray().apply {
                t.roster.staff.forEach { s ->
                    put(JSONObject().put("id", s.id).put("name", s.name).put("wage", s.hourlyWage).put("holiday", s.holidayPay)
                        .put("insured", s.insured).put("shifts", JSONObject().apply {
                            s.shifts.forEach { (day, sh) -> put(day.name, "${sh.start}-${sh.end}") }
                        }))
                }
            }))

        internal fun decode(o: JSONObject): WorkTools = WorkTools(
            offers = o.optJSONArray("offers").objects().map(::offerOf),
            contract = o.optJSONObject("contract")?.let(::contractOf) ?: WorkContract(),
            quit = o.optJSONObject("quit")?.let { q ->
                val last = date(q.optString("last")) ?: return@let null
                QuitRecord(last, date(q.optString("employed")) ?: last, if (q.has("leave")) q.optInt("leave") else null, q.optBoolean("stopped"),
                    date(q.optString("hire")), if (q.has("pay3")) q.optLong("pay3") else null)
            },
            quotes = o.optJSONArray("quotes").objects().mapNotNull(::quoteOf),
            weather = o.optJSONArray("weather").objects().mapNotNull { w ->
                val d = date(w.optString("d")) ?: return@mapNotNull null
                val weather = Weather.entries.firstOrNull { it.name == w.optString("w") } ?: return@mapNotNull null
                WeatherNote(d, weather, if (w.has("t")) w.optInt("t") else null)
            }.associateBy { it.date },
            roster = o.optJSONObject("roster")?.let { r ->
                StaffRoster(
                    staff = r.optJSONArray("staff").objects().map { s ->
                        val shifts = s.optJSONObject("shifts")
                        Staff(
                            id = s.optString("id"), name = s.optString("name"), hourlyWage = s.optLong("wage", 10_320),
                            holidayPay = s.optBoolean("holiday", true), insured = s.optBoolean("insured"),
                            shifts = DayOfWeek.entries.mapNotNull { day ->
                                val parts = shifts?.optString(day.name).orEmpty().split('-')
                                val a = parts.getOrNull(0)?.let(::time)
                                val b = parts.getOrNull(1)?.let(::time)
                                if (a != null && b != null && a != b) day to StaffShift(a, b) else null
                            }.toMap(),
                        )
                    },
                    legalBreaks = r.optBoolean("breaks", true),
                )
            } ?: StaffRoster(),
            contractMemo = if (o.has("contractMemo")) o.optLong("contractMemo") else null,
        )

        private fun offerJson(f: JobOffer) = JSONObject()
            .put("name", f.name).put("wage", f.hourlyWage).put("hours", f.hoursPerWeek).put("days", f.daysPerWeek)
            .put("holiday", f.holidayPay).put("night", f.nightHoursPerWeek).put("rest", f.holidayHoursPerWeek)
            .put("five", f.fiveOrMore).put("tax", f.taxMode.name).put("commute", f.commuteMinutes).put("fare", f.commuteCost)

        private fun offerOf(o: JSONObject) = JobOffer(
            name = o.optString("name"), hourlyWage = o.optLong("wage"), hoursPerWeek = o.optDouble("hours", 0.0),
            daysPerWeek = o.optInt("days", 5), holidayPay = o.optBoolean("holiday", true), nightHoursPerWeek = o.optDouble("night", 0.0),
            holidayHoursPerWeek = o.optDouble("rest", 0.0), fiveOrMore = o.optBoolean("five", true),
            taxMode = runCatching { TaxMode.valueOf(o.optString("tax")) }.getOrDefault(TaxMode.NONE),
            commuteMinutes = o.optInt("commute"), commuteCost = o.optLong("fare"),
        )

        private fun contractJson(c: WorkContract) = JSONObject()
            .put("photos", JSONArray(c.photos)).put("wage", c.hourlyWage)
            .put("days", JSONArray(c.workDays.sorted().map { it.name }))
            .putOpt("start", c.start?.toString()).putOpt("end", c.end?.toString()).put("note", c.note)

        private fun contractOf(o: JSONObject) = WorkContract(
            photos = o.optJSONArray("photos").strings(), hourlyWage = o.optLong("wage"),
            workDays = o.optJSONArray("days").strings().mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.name == n } }.toSet(),
            start = date(o.optString("start")), end = date(o.optString("end")), note = o.optString("note"),
        )

        private fun quoteJson(q: Quote) = JSONObject()
            .put("id", q.id).put("date", q.date.toString()).put("client", q.client).put("title", q.title)
            .put("vat", q.vat.name).put("valid", q.validDays).put("note", q.note).putOpt("job", q.jobId)
            .put("items", JSONArray().apply {
                q.items.forEach { put(JSONObject().put("name", it.name).put("qty", it.quantity).put("unit", it.unit).put("price", it.unitPrice)) }
            })

        private fun quoteOf(o: JSONObject): Quote? {
            val date = date(o.optString("date")) ?: return null
            return Quote(
                id = o.optLong("id"), date = date, client = o.optString("client"), title = o.optString("title"),
                vat = runCatching { QuoteVat.valueOf(o.optString("vat")) }.getOrDefault(QuoteVat.NONE),
                validDays = o.optInt("valid", 30), note = o.optString("note"),
                jobId = if (o.has("job") && !o.isNull("job")) o.optLong("job") else null,
                items = o.optJSONArray("items").objects().map {
                    QuoteItem(it.optString("name"), it.optDouble("qty", 1.0), it.optString("unit", "식"), it.optLong("price"))
                },
            )
        }

        private fun date(s: String?): LocalDate? = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        private fun time(s: String): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
