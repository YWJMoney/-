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
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.AnnualPay
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.PayslipCheck
import com.bamnat.core.pay.CustomRule
import com.bamnat.core.pay.RuleDayKind
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.RulePieces
import com.bamnat.core.pay.RuleWhen
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
class PayStore(
    context: Context,
    /** [MCAL-06] Where it keeps its files: the main calendar's own folder, or an added calendar's. */
    dir: File = File(context.filesDir, "pay"),
) {
    private val file = File(dir, "profile.json")
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<PayProfile?> = _profile.asStateFlow()

    /** The profile before the last change, for one-step undo of a bulk edit [PAYX-03]. */
    private val _undo = MutableStateFlow<PayProfile?>(null)
    val canUndo: StateFlow<PayProfile?> = _undo.asStateFlow()

    suspend fun save(profile: PayProfile?, keepUndo: Boolean = true) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) { write(profile, keepUndo) }
    }

    /**
     * [STAB-01] Changes the saved profile in one step: two quick taps (two answers, two switches) each start
     * from the other's result, instead of the second one undoing the first.
     */
    suspend fun update(transform: (PayProfile) -> PayProfile) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) { _profile.value?.let { write(transform(it), keepUndo = true) } }
    }

    private fun write(profile: PayProfile?, keepUndo: Boolean) {
        if (keepUndo) _undo.value = _profile.value
        if (profile == null) file.delete() else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "profile.json.tmp")
            tmp.writeText(PayCodec.encode(profile).toString())
            tmp.renameTo(file)
        }
        _profile.value = profile
    }

    suspend fun undo() {
        val previous = _undo.value ?: return
        save(previous, keepUndo = false)
        _undo.value = null
    }

    private fun read(): PayProfile? =
        runCatching { if (file.exists()) PayCodec.decode(JSONObject(file.readText())) else null }.getOrNull()

    // ---- closed months [PAYX-03, PAY-10] ----

    private val closedFile = File(dir, "closed.json")
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
        // [PAYS-03] 일당's hours, the 연봉 behind the 기본급 and the last payslip check; the last two never leave the phone.
        .put("dailyHours", p.dailyHours)
        .putOpt("annual", p.annual?.let(::annual))
        .putOpt("verified", p.verified?.let(::verified))
        // [PAYS-06] Rules in the user's own words; an empty list is left out so older files stay the same.
        .apply { if (p.customRules.isNotEmpty()) put("customRules", JSONArray(p.customRules.map(::customRule))) }

    fun decode(o: JSONObject): PayProfile = PayProfile(
        // [PAYS-03] A job or wage type from a newer version reads as 직접 설정 and 월급제 rather than failing the file.
        occupation = runCatching { Occupation.valueOf(o.getString("occupation")) }.getOrDefault(Occupation.CUSTOM),
        wageType = runCatching { WageType.valueOf(o.getString("wageType")) }.getOrDefault(WageType.MONTHLY),
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
        dailyHours = o.optInt("dailyHours", 8 * 60).coerceIn(30, 8 * 60),
        annual = o.optJSONObject("annual")?.let(::annualFrom),
        verified = o.optJSONObject("verified")?.let(::verifiedFrom),
        customRules = o.optJSONArray("customRules").objects().mapNotNull(::customRuleFrom).take(CustomRule.MAX),
    )

    /** [PAYS-06] One of the user's own rules. */
    private fun customRule(r: CustomRule): JSONObject {
        val w = r.whenever
        val pay = when (val p = r.pay) {
            is RulePay.PerHour -> JSONObject().put("type", "perHour").put("amount", p.amount)
            is RulePay.OfHourly -> JSONObject().put("type", "ofHourly").put("rate", p.rate)
            is RulePay.OfBase -> JSONObject().put("type", "ofBase").put("share", p.share).put("per", p.perMinutes)
            is RulePay.PerTime -> JSONObject().put("type", "perTime").put("amount", p.amount)
        }
        return JSONObject()
            .put("id", r.id).put("name", r.name).put("on", r.on).put("source", r.source)
            .put("dayKind", w.dayKind.name)
            .put("days", JSONArray(w.daysOfWeek.map { it.name }))
            .put("shiftTypes", JSONArray(w.shiftTypeIds.toList()))
            .put("pieces", w.pieces.name)
            .put("extraKinds", JSONArray(w.extraKinds.toList()))
            .put("from", w.from?.toString() ?: JSONObject.NULL).put("to", w.to?.toString() ?: JSONObject.NULL)
            .put("over", w.overMinutes).put("min", w.minMinutes)
            .put("pay", pay)
    }

    /** A rule this version cannot read (a newer kind of pay) is dropped rather than failing the file. */
    private fun customRuleFrom(o: JSONObject): CustomRule? = runCatching {
        val p = o.getJSONObject("pay")
        val pay: RulePay = when (p.getString("type")) {
            "perHour" -> RulePay.PerHour(p.getLong("amount"))
            "ofHourly" -> RulePay.OfHourly(p.getDouble("rate").coerceIn(0.0, Premium.MAX_RATE))
            "ofBase" -> RulePay.OfBase(p.getDouble("share"), p.optInt("per", 480).coerceAtLeast(1))
            "perTime" -> RulePay.PerTime(p.getLong("amount"))
            else -> return null
        }
        fun longs(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet()
        fun time(key: String) = if (!o.has(key) || o.isNull(key)) null else runCatching { LocalTime.parse(o.getString(key)) }.getOrNull()
        CustomRule(
            id = o.getString("id"),
            name = o.optString("name").ifBlank { "회사 규칙" }.take(CustomRule.MAX_NAME),
            whenever = RuleWhen(
                dayKind = RuleDayKind.entries.firstOrNull { it.name == o.optString("dayKind") } ?: RuleDayKind.ANY,
                daysOfWeek = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.name == a.getString(n) } }.toSet() } ?: emptySet(),
                shiftTypeIds = longs("shiftTypes"),
                pieces = RulePieces.entries.firstOrNull { it.name == o.optString("pieces") } ?: RulePieces.ALL,
                extraKinds = longs("extraKinds"),
                from = time("from"), to = time("to"),
                overMinutes = o.optInt("over", 0).coerceAtLeast(0),
                minMinutes = o.optInt("min", 0).coerceAtLeast(0),
            ),
            pay = pay,
            on = o.optBoolean("on", true),
            source = o.optString("source"),
        )
    }.getOrNull()

    /** [PAYS-03] 연봉 as the contract states it; a missing or broken one is dropped, the 기본급 stays. */
    private fun annual(a: AnnualPay): JSONObject = JSONObject()
        .put("yearly", a.yearly).put("split", a.split).put("fixedOvertimeMonthly", a.fixedOvertimeMonthly)
        .put("regularBonusYearly", a.regularBonusYearly).put("otherMonthly", a.otherMonthly)

    private fun annualFrom(o: JSONObject): AnnualPay? = o.optLong("yearly", -1).takeIf { it > 0 }?.let { yearly ->
        AnnualPay(
            yearly = yearly,
            split = if (o.optInt("split", 12) == 13) 13 else 12,
            fixedOvertimeMonthly = o.optLong("fixedOvertimeMonthly", 0).coerceAtLeast(0),
            regularBonusYearly = o.optLong("regularBonusYearly", 0).coerceAtLeast(0),
            otherMonthly = o.optLong("otherMonthly", 0).coerceAtLeast(0),
        )
    }

    /** [PAYS-02] The last month matched against a payslip. */
    private fun verified(v: PayslipCheck): JSONObject = JSONObject()
        .put("month", v.month.toString()).put("gross", v.gross).put("appTotal", v.appTotal).put("atMillis", v.atMillis)

    private fun verifiedFrom(o: JSONObject): PayslipCheck? = runCatching {
        PayslipCheck(YearMonth.parse(o.getString("month")), o.getLong("gross"), o.getLong("appTotal"), o.optLong("atMillis"))
    }.getOrNull()

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
        // [PAYS-03] 감시·단속 승인, 5인 미만 회사가 주는 공휴일, 휴게 처리.
        .put("supervisory", r.supervisoryApproved).put("holidaysU5", r.holidaysUnderFive).put("breakPay", r.breakPay.name)
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
        supervisoryApproved = o.optBoolean("supervisory", false),
        holidaysUnderFive = o.optBoolean("holidaysU5", false),
        breakPay = o.optString("breakPay").let { n -> BreakPay.entries.firstOrNull { it.name == n } } ?: BreakPay.UNPAID,
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
        .put("unit", e.unit.name).put("unitMin", e.unitMinutes).put("adds", premiums(e.premiums))

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
        unit = runCatching { PayUnit.valueOf(e.optString("unit")) }.getOrDefault(PayUnit.ORDINARY),
        unitMinutes = e.optInt("unitMin", 8 * 60).coerceIn(30, 24 * 60),
        premiums = e.optJSONArray("adds")?.let(::premiums).orEmpty(),
    )

    private fun premiums(p: List<Premium>): JSONArray = JSONArray(p.map {
        JSONObject().put("key", it.key).put("name", it.name).put("rate", it.rate).put("on", it.on)
            .put("scope", scope(it.scope)).put("offDays", it.offDaysOnly)
    })

    /**
     * [EXTRA-08] Premiums from a file or a shared company: the four always listed keep the kind of
     * hours they are for, one added by the company whose hours this version cannot read is left out
     * rather than guessed, and names, rates and the count stay within what the editor allows.
     */
    private fun premiums(a: JSONArray): List<Premium> {
        val read = a.objects().mapNotNull { o ->
            val key = o.optString("key").trim().takeIf { it.isNotEmpty() && it.length <= 8 } ?: return@mapNotNull null
            val scope = scopeFrom(o.optString("scope"))
            val rate = o.optDouble("rate", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, Premium.MAX_RATE) ?: 0.0
            val on = o.optBoolean("on", false)
            if (key in Premium.BUILT_IN) {
                val law = Premium.builtIn(key, rate, on)
                // Only 연장 can be counted another way (이 일 N시간 넘는 시간); the others are what they say.
                if (key == Premium.OVERTIME && scope is PremiumScope.Over) law.copy(scope = scope) else law
            } else {
                val name = o.optString("name").trim().take(Premium.MAX_NAME).ifEmpty { return@mapNotNull null }
                Premium(key, name, rate, on, scope ?: return@mapNotNull null, o.optBoolean("offDays", false))
            }
        }.distinctBy { it.key }
        return read.filter { !it.custom } + read.filter { it.custom }.take(Premium.MAX_CUSTOM)
    }

    private fun scope(s: PremiumScope): String = when (s) {
        PremiumScope.All -> "all"
        is PremiumScope.Over -> "over:${s.minutes}"
        PremiumScope.LegalOvertime -> "law"
        PremiumScope.Night -> "night"
        is PremiumScope.Window -> "window:${s.from}-${s.to}"
        PremiumScope.Holiday -> "holiday"
        PremiumScope.RestDay -> "rest"
    }

    private fun scopeFrom(s: String): PremiumScope? = when {
        s == "all" -> PremiumScope.All
        s == "law" -> PremiumScope.LegalOvertime
        s == "night" -> PremiumScope.Night
        s == "holiday" -> PremiumScope.Holiday
        s == "rest" -> PremiumScope.RestDay
        s.startsWith("over:") -> s.removePrefix("over:").toIntOrNull()?.takeIf { it in 0..24 * 60 }?.let { PremiumScope.Over(it) }
        s.startsWith("window:") -> s.removePrefix("window:").split('-').takeIf { it.size == 2 }?.let { (a, b) ->
            runCatching { PremiumScope.Window(LocalTime.parse(a), LocalTime.parse(b)) }.getOrNull()?.takeIf { it.from != it.to }
        }
        else -> null
    }

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
        // [PAYS-03] Only when chosen: without it the allowance keeps its default (연장 for a 고정 연장수당).
        .putOpt("covers", i.covers?.let { c -> JSONArray(PayItem.COVERABLE.filter { it in c }.map { it.name }) })

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
        covers = o.optJSONArray("covers")?.let { a ->
            (0 until a.length()).mapNotNull { n -> PayItem.COVERABLE.firstOrNull { it.name == a.optString(n) } }.toSet()
        },
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

/** [PAYX-09][EXTRA-01] The profile with the names of the user's kinds of extra work, for labels on pay lines. */
fun PayProfile.named(kinds: com.bamnat.core.model.ExtraKinds): PayProfile = copy(rules = rules.copy(extraNames = kinds.names))
