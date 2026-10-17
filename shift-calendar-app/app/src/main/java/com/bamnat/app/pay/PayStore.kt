package com.bamnat.app.pay

import android.content.Context
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.CalcSnapshot
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.Tier
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.WeeklyHoliday
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
import java.time.LocalTime
import com.bamnat.core.pay.Festival
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

    /** All closed months at once, from a restored backup. */
    suspend fun replaceClosed(next: Map<YearMonth, CalcSnapshot>) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) {
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
        .put("v", 2)
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
        .put("scheduleIsRegular", p.scheduleIsRegular)
        .put("flexPeriod", p.flexPeriod?.name)
        .put("weekStart", p.weekStart.name)
        .put("estimated", JSONArray(p.estimatedFields.toList()))
        .put("period", period(p.period))
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
        scheduleIsRegular = o.optBoolean("scheduleIsRegular", false),
        flexPeriod = o.optString("flexPeriod").let { n -> FlexPeriod.entries.firstOrNull { it.name == n } },
        weekStart = runCatching { DayOfWeek.valueOf(o.getString("weekStart")) }.getOrDefault(DayOfWeek.MONDAY),
        estimatedFields = o.optJSONArray("estimated")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
        rules = o.optJSONObject("rules")?.let(::rules) ?: CompanyRules(),
        period = o.optJSONObject("period")?.let(::periodFrom) ?: com.bamnat.core.pay.PayPeriod(),
    )

    /** [PAYX-15] Pay period and pay day; values out of range fall back to the calendar month. */
    fun period(p: com.bamnat.core.pay.PayPeriod): JSONObject = JSONObject()
        .put("endDay", p.endDay ?: -1).put("payDay", p.payDay ?: -1).put("payNextMonth", p.payNextMonth)

    fun periodFrom(o: JSONObject): com.bamnat.core.pay.PayPeriod = com.bamnat.core.pay.PayPeriod(
        endDay = o.optInt("endDay", -1).takeIf { it in 1..30 },
        payDay = o.optInt("payDay", -1).takeIf { it in 1..31 },
        payNextMonth = o.optBoolean("payNextMonth", false),
    )

    private fun JSONObject.putRate(key: String, v: Double?) = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.rate(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)

    private fun rules(r: CompanyRules): JSONObject = JSONObject()
        .putRate("ot", r.overtimePremium).putRate("night", r.nightPremium)
        .putRate("hw", r.holidayPremiumWithin8h).putRate("ho", r.holidayPremiumOver8h)
        .put("restDay", r.restDayWork.name)
        // Read by 1.8 and earlier, which knew only these two.
        .put("offDay", if (r.restDayWork == RestDayWork.OVERTIME) "OVERTIME" else "HOLIDAY")
        .put("weeklyHolidayDay", weeklyHoliday(r.weeklyHoliday))
        .put("publicHolidays", r.publicHolidaysPaid)
        .put("roundUnit", r.overtimeRounding.unit).put("round", r.overtimeRounding.rounding.name)
        .put("weeklyHoliday", r.weeklyHolidayPay)
        .putRate("annualLeave", r.annualLeaveDays)
        .put("leaveCarry", r.annualLeaveCarry)
        .putOpt("publicHolidayAs", r.publicHolidayAs?.toString())
        .put("holidayLeaveFree", r.holidayLeaveFree)
        .put("holidayByStart", r.holidayByShiftStart)
        .putOpt("nightStart", r.nightStart?.toString()).putOpt("nightEnd", r.nightEnd?.toString())
        // [EXTRA-03] Keyed by kind id; 1.8 and earlier used OVERTIME / SPECIAL / COVER.
        .put("extraByShift", JSONObject().apply {
            r.extraByShift.forEach { (kind, byType) ->
                put(kind.toString(), JSONObject().apply { byType.forEach { (id, e) -> put(id.toString(), extraRule(e)) } })
            }
        })
        .put("extra", JSONObject().apply {
            r.extra.forEach { (kind, e) ->
                put(kind.toString(), extraRule(e))
            }
        })

    private fun rules(o: JSONObject): CompanyRules = CompanyRules(
        overtimePremium = o.rate("ot"),
        nightPremium = o.rate("night"),
        holidayPremiumWithin8h = o.rate("hw"),
        holidayPremiumOver8h = o.rate("ho"),
        restDayWork = restDayWork(o),
        weeklyHoliday = weeklyHolidayFrom(o.optString("weeklyHolidayDay")),
        publicHolidaysPaid = o.optBoolean("publicHolidays", true),
        overtimeRounding = MinuteRounding(
            o.optInt("roundUnit", 1),
            runCatching { Rounding.valueOf(o.getString("round")) }.getOrDefault(Rounding.DOWN),
        ),
        weeklyHolidayPay = o.optBoolean("weeklyHoliday", true),
        annualLeaveDays = o.rate("annualLeave"),
        annualLeaveCarry = o.optDouble("leaveCarry", 0.0),
        publicHolidayAs = o.optString("publicHolidayAs").takeIf { it.isNotEmpty() && it != "null" }?.let(::kindId),
        holidayLeaveFree = o.optBoolean("holidayLeaveFree", false),
        holidayByShiftStart = o.optBoolean("holidayByStart", false),
        extra = o.optJSONObject("extra")?.let { x ->
            x.keys().asSequence().mapNotNull { key ->
                val kind = kindId(key) ?: return@mapNotNull null
                kind to extraRule(x.getJSONObject(key))
            }.toMap()
        } ?: emptyMap(),
        nightStart = o.optString("nightStart").takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        nightEnd = o.optString("nightEnd").takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        extraByShift = o.optJSONObject("extraByShift")?.let { x ->
            x.keys().asSequence().mapNotNull { key ->
                val kind = kindId(key) ?: return@mapNotNull null
                val byType = x.getJSONObject(key)
                kind to byType.keys().asSequence().mapNotNull { id -> id.toLongOrNull()?.let { it to extraRule(byType.getJSONObject(id)) } }.toMap()
            }.toMap()
        } ?: emptyMap(),
    )

    private fun extraRule(e: ExtraRule): JSONObject = JSONObject().put("basis", e.basis.name).put("multiplier", e.multiplier).put("amount", e.amount)
        .put("min", e.minMinutes).put("night", e.nightPremium)
        .put("percent", e.percentOfBase).put("cover", e.coverMinutes ?: -1).put("premiums", e.legalPremiums).put("bonus", e.bonus)
        .put("dayTiers", tiers(e.dayTiers)).put("nightTiers", tiers(e.nightTiers))

    private fun extraRule(e: JSONObject): ExtraRule = ExtraRule(
        // A basis this version does not know (a newer file) is paid by law rather than guessed.
        basis = runCatching { ExtraBasis.valueOf(e.getString("basis")) }.getOrDefault(ExtraBasis.LEGAL),
        multiplier = e.optDouble("multiplier", 1.5),
        amount = e.optLong("amount", 0),
        minMinutes = e.optInt("min", 0),
        nightPremium = e.optBoolean("night", true),
        percentOfBase = e.optDouble("percent", 0.1).takeIf { it.isFinite() && it in 0.0..10.0 } ?: 0.1,
        coverMinutes = e.optInt("cover", -1).takeIf { it > 0 },
        legalPremiums = e.optBoolean("premiums", false),
        bonus = e.optLong("bonus", 0).coerceAtLeast(0),
        dayTiers = e.optJSONArray("dayTiers")?.let(::tiers) ?: ExtraRule.DEFAULT_DAY_TIERS,
        nightTiers = e.optJSONArray("nightTiers")?.let(::tiers) ?: ExtraRule.DEFAULT_NIGHT_TIERS,
    )

    /** [EXTRA-03] A kind id, or a name from 1.8 and earlier. */
    private fun kindId(key: String): Long? = key.toLongOrNull() ?: when (key) {
        "OVERTIME" -> ExtraKind.OVERTIME_ID
        "SPECIAL" -> ExtraKind.SPECIAL_ID
        "COVER" -> ExtraKind.COVER_ID
        else -> null
    }

    /**
     * [EXTRA-02] Rules saved by 1.8 and earlier knew only 휴일근로 (the default) and 연장근로 for work on
     * a day off; they keep what they had, so nobody's pay changes without them choosing.
     */
    private fun restDayWork(o: JSONObject): RestDayWork =
        o.optString("restDay").takeIf { it.isNotEmpty() }?.let { n -> RestDayWork.entries.firstOrNull { it.name == n } }
            ?: if (o.optString("offDay") == "OVERTIME") RestDayWork.OVERTIME else RestDayWork.HOLIDAY

    private fun weeklyHoliday(w: WeeklyHoliday): String = when (w) {
        is WeeklyHoliday.Fixed -> w.day.name
        WeeklyHoliday.FirstRestDay -> "FIRST_REST"
        WeeklyHoliday.Unknown -> "UNKNOWN"
    }

    private fun weeklyHolidayFrom(s: String): WeeklyHoliday = when (s) {
        "FIRST_REST" -> WeeklyHoliday.FirstRestDay
        else -> DayOfWeek.entries.firstOrNull { it.name == s }?.let { WeeklyHoliday.Fixed(it) } ?: WeeklyHoliday.Unknown
    }

    private fun tiers(t: List<Tier>): JSONArray = JSONArray(t.map { JSONObject().put("min", it.minutes).put("premium", it.premium) })
    private fun tiers(a: JSONArray): List<Tier> = (0 until a.length()).map { a.getJSONObject(it) }.map { Tier(it.getInt("min"), it.getDouble("premium")) }

    /** [COMM-00] The same JSON, for the company settings file. */
    fun rulesJson(r: CompanyRules): JSONObject = rules(r)
    fun rulesFrom(o: JSONObject): CompanyRules = rules(o)
    fun itemJson(i: PayItem): JSONObject = item(i)
    fun itemFrom(o: JSONObject): PayItem = item(o)

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
        .put("festivals", JSONArray(i.festivals.map { it.name })).put("festivalBefore", i.festivalMonthBefore)

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
        festivals = o.optJSONArray("festivals")?.let { a -> (0 until a.length()).mapNotNull { n -> Festival.entries.firstOrNull { it.name == a.getString(n) } }.toSet() } ?: emptySet(),
        festivalMonthBefore = o.optBoolean("festivalBefore", false),
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

/** [PAYX-09][EXTRA-01] The profile with the names of the user's kinds of extra work, for labels on pay lines. */
fun PayProfile.named(kinds: com.bamnat.core.model.ExtraKinds): PayProfile = copy(rules = rules.copy(extraNames = kinds.names))
