package com.bamnat.app.pay

import android.content.Context
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.pay.CalcSnapshot
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.Tier
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.OffDayWork
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The pay profile, kept as one JSON file in app storage (included in phone backup) [PAY-10].
 * Company pay values never leave the phone.
 */
class PayStore(context: Context) {
    private val file = File(context.filesDir, "pay/profile.json")
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<PayProfile?> = _profile.asStateFlow()

    /** The profile before the last change, for one-step undo of a bulk edit [PAYX-03]. */
    private val _undo = MutableStateFlow<PayProfile?>(null)
    val canUndo: StateFlow<PayProfile?> = _undo.asStateFlow()

    suspend fun save(profile: PayProfile?, keepUndo: Boolean = true) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) {
            if (keepUndo) _undo.value = _profile.value
            if (profile == null) file.delete() else {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "profile.json.tmp")
                tmp.writeText(PayCodec.encode(profile).toString())
                tmp.renameTo(file)
            }
            _profile.value = profile
        }
    }

    suspend fun undo() {
        val previous = _undo.value ?: return
        save(previous, keepUndo = false)
        _undo.value = null
    }

    private fun read(): PayProfile? =
        runCatching { if (file.exists()) PayCodec.decode(JSONObject(file.readText())) else null }.getOrNull()

    // ---- closed months [PAYX-03, PAY-10] ----

    private val closedFile = File(context.filesDir, "pay/closed.json")
    private val _closed = MutableStateFlow(readClosed())
    /** Months the user closed: their saved results stay as they were when rules or rates change. */
    val closed: StateFlow<Map<YearMonth, CalcSnapshot>> = _closed.asStateFlow()

    suspend fun saveClosed(snapshot: CalcSnapshot?, month: YearMonth) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) {
            val next = if (snapshot == null) _closed.value - month else _closed.value + (month to snapshot)
            closedFile.parentFile?.mkdirs()
            val tmp = File(closedFile.parentFile, "closed.json.tmp")
            tmp.writeText(JSONArray(next.values.map(SnapshotCodec::encode)).toString())
            tmp.renameTo(closedFile)
            _closed.value = next
        }
    }

    private fun readClosed(): Map<YearMonth, CalcSnapshot> = runCatching {
        if (!closedFile.exists()) return@runCatching emptyMap()
        val a = JSONArray(closedFile.readText())
        (0 until a.length()).map { SnapshotCodec.decode(a.getJSONObject(it)) }.associateBy { it.month }
    }.getOrDefault(emptyMap())
}

/** JSON for a closed month's [CalcSnapshot]. */
object SnapshotCodec {
    fun encode(s: CalcSnapshot): JSONObject = JSONObject()
        .put("month", s.month.toString())
        .put("total", s.total)
        .put("rates", s.ratesVersion)
        .put("at", s.closedAtMillis)
        .put("lines", JSONArray(s.lines.map { l ->
            JSONObject().put("kind", l.kind.name).put("label", l.label).put("amount", l.amount).put("formula", l.formula)
                .put("minutes", l.minutes).put("hourly", l.hourly).put("multiplier", l.multiplier).put("basis", l.basis)
                .put("estimated", l.estimated)
        }))
        .put("previous", JSONArray(s.previous.map(::encode)))

    fun decode(o: JSONObject): CalcSnapshot {
        val lines = o.optJSONArray("lines")
        val previous = o.optJSONArray("previous")
        return CalcSnapshot(
            month = YearMonth.parse(o.getString("month")),
            total = o.getLong("total"),
            lines = (0 until (lines?.length() ?: 0)).map { i ->
                val l = lines!!.getJSONObject(i)
                PayLine(
                    kind = runCatching { LineKind.valueOf(l.getString("kind")) }.getOrDefault(LineKind.ALLOWANCE),
                    label = l.getString("label"), amount = l.getLong("amount"), formula = l.optString("formula"),
                    minutes = l.optInt("minutes"), hourly = l.optLong("hourly"), multiplier = l.optDouble("multiplier", 0.0),
                    basis = l.optString("basis"), estimated = l.optBoolean("estimated"),
                )
            },
            ratesVersion = o.optString("rates"),
            closedAtMillis = o.optLong("at"),
            previous = (0 until (previous?.length() ?: 0)).map { decode(previous!!.getJSONObject(it)) },
        )
    }
}

/** JSON for [PayProfile]; also used by the full backup file. */
object PayCodec {
    fun encode(p: PayProfile): JSONObject = JSONObject()
        .put("v", 1)
        .put("occupation", p.occupation.name)
        .put("wageType", p.wageType.name)
        .put("base", timeline(p.base))
        .put("items", JSONArray(p.items.map(::item)))
        .put("hireDate", p.hireDate?.toString())
        .put("fiveOrMore", p.fiveOrMoreEmployees)
        .put("monthlyHours", p.monthlyHours)
        .put("holidayMode", p.holidayPayMode.name)
        .put("dailyLimit", p.dailyLimitMinutes ?: -1)
        .put("weeklyLimit", p.weeklyLimitMinutes ?: -1)
        .put("weekStart", p.weekStart.name)
        .put("estimated", JSONArray(p.estimatedFields.toList()))
        .put("rules", rules(p.rules))

    fun decode(o: JSONObject): PayProfile = PayProfile(
        occupation = Occupation.valueOf(o.getString("occupation")),
        wageType = WageType.valueOf(o.getString("wageType")),
        base = timeline(o.getJSONObject("base")),
        items = o.optJSONArray("items").objects().map(::item),
        hireDate = o.optString("hireDate").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse),
        fiveOrMoreEmployees = o.optBoolean("fiveOrMore", true),
        monthlyHours = o.optInt("monthlyHours", 209),
        holidayPayMode = runCatching { HolidayPayMode.valueOf(o.getString("holidayMode")) }.getOrDefault(HolidayPayMode.FULL),
        dailyLimitMinutes = o.optInt("dailyLimit", 480).takeIf { it >= 0 },
        weeklyLimitMinutes = o.optInt("weeklyLimit", 2400).takeIf { it >= 0 },
        weekStart = runCatching { DayOfWeek.valueOf(o.getString("weekStart")) }.getOrDefault(DayOfWeek.MONDAY),
        estimatedFields = o.optJSONArray("estimated")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
        rules = o.optJSONObject("rules")?.let(::rules) ?: CompanyRules(),
    )

    private fun JSONObject.putRate(key: String, v: Double?) = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.rate(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)

    private fun rules(r: CompanyRules): JSONObject = JSONObject()
        .putRate("ot", r.overtimePremium).putRate("night", r.nightPremium)
        .putRate("hw", r.holidayPremiumWithin8h).putRate("ho", r.holidayPremiumOver8h)
        .put("offDay", r.offDayWork.name)
        .put("publicHolidays", r.publicHolidaysPaid)
        .put("roundUnit", r.overtimeRounding.unit).put("round", r.overtimeRounding.rounding.name)
        .put("weeklyHoliday", r.weeklyHolidayPay)
        .putRate("annualLeave", r.annualLeaveDays)
        .putOpt("publicHolidayAs", r.publicHolidayAs?.name)
        .put("holidayLeaveFree", r.holidayLeaveFree)
        .put("extra", JSONObject().apply {
            r.extra.forEach { (kind, e) ->
                put(kind.name, JSONObject().put("basis", e.basis.name).put("multiplier", e.multiplier).put("amount", e.amount)
                    .put("min", e.minMinutes).put("night", e.nightPremium)
                    .put("dayTiers", tiers(e.dayTiers)).put("nightTiers", tiers(e.nightTiers)))
            }
        })

    private fun rules(o: JSONObject): CompanyRules = CompanyRules(
        overtimePremium = o.rate("ot"),
        nightPremium = o.rate("night"),
        holidayPremiumWithin8h = o.rate("hw"),
        holidayPremiumOver8h = o.rate("ho"),
        offDayWork = runCatching { OffDayWork.valueOf(o.getString("offDay")) }.getOrDefault(OffDayWork.HOLIDAY),
        publicHolidaysPaid = o.optBoolean("publicHolidays", true),
        overtimeRounding = MinuteRounding(
            o.optInt("roundUnit", 1),
            runCatching { Rounding.valueOf(o.getString("round")) }.getOrDefault(Rounding.DOWN),
        ),
        weeklyHolidayPay = o.optBoolean("weeklyHoliday", true),
        annualLeaveDays = o.rate("annualLeave"),
        publicHolidayAs = o.optString("publicHolidayAs").takeIf { it.isNotEmpty() }?.let { runCatching { ExtraWork.valueOf(it) }.getOrNull() },
        holidayLeaveFree = o.optBoolean("holidayLeaveFree", false),
        extra = o.optJSONObject("extra")?.let { x ->
            x.keys().asSequence().mapNotNull { key ->
                val kind = runCatching { ExtraWork.valueOf(key) }.getOrNull() ?: return@mapNotNull null
                val e = x.getJSONObject(key)
                kind to ExtraRule(
                    basis = runCatching { ExtraBasis.valueOf(e.getString("basis")) }.getOrDefault(ExtraBasis.LEGAL),
                    multiplier = e.optDouble("multiplier", 1.5),
                    amount = e.optLong("amount", 0),
                    minMinutes = e.optInt("min", 0),
                    nightPremium = e.optBoolean("night", true),
                    dayTiers = e.optJSONArray("dayTiers")?.let(::tiers) ?: ExtraRule.DEFAULT_DAY_TIERS,
                    nightTiers = e.optJSONArray("nightTiers")?.let(::tiers) ?: ExtraRule.DEFAULT_NIGHT_TIERS,
                )
            }.toMap()
        } ?: emptyMap(),
    )

    private fun tiers(t: List<Tier>): JSONArray = JSONArray(t.map { JSONObject().put("min", it.minutes).put("premium", it.premium) })
    private fun tiers(a: JSONArray): List<Tier> = (0 until a.length()).map { a.getJSONObject(it) }.map { Tier(it.getInt("min"), it.getDouble("premium")) }

    private fun timeline(t: ValueTimeline): JSONObject = JSONObject()
        .put("entries", JSONArray(t.entries.map { JSONObject().put("from", it.from.toString()).put("value", it.value) }))
        .put("raise", t.raise?.let { r ->
            JSONObject().put("month", r.month).put("percent", r.percent ?: JSONObject.NULL)
                .put("amount", r.amount ?: JSONObject.NULL).put("unit", r.unit).put("rounding", r.rounding.name)
        } ?: JSONObject.NULL)

    private fun timeline(o: JSONObject): ValueTimeline = ValueTimeline(
        entries = o.optJSONArray("entries").objects().map { DatedValue(LocalDate.parse(it.getString("from")), it.getLong("value")) },
        raise = o.optJSONObject("raise")?.let { r ->
            RaiseRule(
                month = r.getInt("month"),
                percent = if (r.isNull("percent")) null else r.getDouble("percent"),
                amount = if (r.isNull("amount")) null else r.getLong("amount"),
                unit = r.optLong("unit", 10),
                rounding = runCatching { Rounding.valueOf(r.getString("rounding")) }.getOrDefault(Rounding.UP),
            )
        },
    )

    private fun item(i: PayItem): JSONObject = JSONObject()
        .put("id", i.id).put("name", i.name).put("amount", timeline(i.amount)).put("cadence", i.cadence.name)
        .put("types", JSONArray(i.shiftTypeIds.toList())).put("ordinary", i.ordinary).put("taxable", i.taxable)
        .put("months", JSONArray(i.months.sorted())).putOpt("percentOfBase", i.percentOfBase)
        .put("fixedOvertime", i.fixedOvertimeMinutes)

    private fun item(o: JSONObject): PayItem = PayItem(
        id = o.getString("id"),
        name = o.getString("name"),
        amount = timeline(o.getJSONObject("amount")),
        cadence = runCatching { Cadence.valueOf(o.getString("cadence")) }.getOrDefault(Cadence.MONTHLY),
        shiftTypeIds = o.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet(),
        ordinary = o.optBoolean("ordinary", false),
        taxable = o.optBoolean("taxable", true),
        months = o.optJSONArray("months")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
        percentOfBase = if (o.has("percentOfBase")) o.getDouble("percentOfBase") else null,
        fixedOvertimeMinutes = o.optInt("fixedOvertime", 0),
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
