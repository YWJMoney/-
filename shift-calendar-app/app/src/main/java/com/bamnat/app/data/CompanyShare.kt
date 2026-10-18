package com.bamnat.app.data

import com.bamnat.app.pay.PayCodec
import com.bamnat.app.pay.PayStore
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.setup.CompanyTemplate
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** What a company settings file holds, shown before the user applies it [COMM-00]. */
data class CompanySummary(
    val company: String,
    val shiftTypes: List<String>,
    val cycleDays: Int?,
    val hasRules: Boolean,
    val items: List<String>,
    val withAmounts: Boolean,
    /** [EXTRA-01] The kinds of extra work in the file, e.g. 잔업 · 야간 대근. */
    val extraKinds: List<String> = emptyList(),
    /** [SETUP-02] The sender's 조, when they wrote it. */
    val team: String? = null,
)

/**
 * [COMM-00] A company's shift types, rotation and pay rules as one small file a coworker can
 * import, the step before shared company data on a server. Only what is the same for everyone
 * at the company goes in: never base pay, hire date, leave days, alarms or any day of the
 * calendar. Allowance amounts are left out unless the sender chooses to include them.
 * Shift types are referred to by name, since ids differ from phone to phone.
 */
class CompanyShare(
    private val schedule: ScheduleRepository,
    private val payStore: PayStore,
    private val settings: SettingsRepository,
    private val today: () -> LocalDate = LocalDate::now,
) {
    suspend fun export(company: String, withAmounts: Boolean, team: String = ""): String {
        val snapshot = schedule.snapshot.first()
        val names = snapshot.shiftTypes.associate { it.id to it.name }
        val root = JSONObject()
            .put("app", BackupManager.APP_ID).put("format", FORMAT).put("v", VERSION)
            .put("company", company.trim().take(MAX_NAME))
            .put("shiftTypes", JSONArray(snapshot.shiftTypes.map(::typeJson)))
        // [EXTRA-01] Kinds of extra work; the shift a 대근 kind starts from is named, since ids differ.
        val kinds = settings.current.extraKinds
        root.put("extraKinds", ExtraKinds(kinds.kinds.map { k -> if (k.timing is ExtraTiming.AsShift) k.copy(timing = ExtraTiming.AsShift()) else k }).encode())
            .put("extraKindShifts", JSONObject().apply {
                kinds.kinds.forEach { k -> (k.timing as? ExtraTiming.AsShift)?.shiftTypeId?.let(names::get)?.let { put(k.id.toString(), it) } }
            })
            // [EXTRA-07] A 대근 for several shifts (모닝·나이트) lists them all; 1.9.0 reads it as every shift.
            .put("extraKindShiftSets", JSONObject().apply {
                kinds.kinds.forEach { k ->
                    val set = (k.timing as? ExtraTiming.AsShift)?.shiftTypeIds?.takeIf { it.size > 1 } ?: return@forEach
                    put(k.id.toString(), JSONArray(set.mapNotNull(names::get)))
                }
            })
        (PatternTimeline.current(snapshot.patterns, today()) as? WorkPattern.Rotation)?.let { r ->
            root.put("rotation", JSONObject()
                .put("anchor", r.anchorDate.toString())
                .put("steps", JSONArray(r.steps.map { id -> id?.let(names::get) ?: JSONObject.NULL }))
                // [SETUP-02] The sender's 조, so a coworker on the same one is asked only "같은 B조예요?".
                .apply { team.trim().take(MAX_TEAM).takeIf { it.isNotEmpty() }?.let { put("team", it) } })
        }
        payStore.profile.value?.let { p ->
            val rules = p.rules.copy(annualLeaveDays = null, annualLeaveCarry = 0.0, extraNames = emptyMap())
            val rulesJson = PayCodec.rulesJson(rules)
            // Rules per shift type are keyed by name in the file.
            rulesJson.optJSONObject("extraByShift")?.let { by -> rulesJson.put("extraByShift", rekey(by) { names[it.toLongOrNull()] }) }
            root.put("pay", JSONObject()
                .put("fiveOrMore", p.fiveOrMoreEmployees).put("monthlyHours", p.monthlyHours)
                .put("holidayMode", p.holidayPayMode.name)
                .put("dailyLimit", p.dailyLimitMinutes ?: -1).put("weeklyLimit", p.weeklyLimitMinutes ?: -1)
                .put("scheduleIsRegular", p.scheduleIsRegular).putOpt("flexPeriod", p.flexPeriod?.name)
                .put("weekStart", p.weekStart.name)
                .put("period", PayCodec.period(p.period))
                .put("rules", rulesJson)
                .put("withAmounts", withAmounts)
                .put("items", JSONArray(p.items.map { item ->
                    val shared = if (withAmounts) item else item.copy(amount = ValueTimeline())
                    PayCodec.itemJson(shared).put("types", JSONArray(item.shiftTypeIds.mapNotNull(names::get)))
                })))
        }
        return root.toString(1)
    }

    /**
     * [COMM-02] The file as the company server takes it: no company name (the report carries it), no
     * allowance amounts, and the won amounts typed into extra-work rules (1회·시간당 정액, 1회 수당) at 0.
     * The server refuses a file with any amount in it rather than dropping it, so none may slip through.
     */
    suspend fun exportForServer(): JSONObject {
        val root = JSONObject(export("", withAmounts = false))
        root.optJSONObject("pay")?.optJSONObject("rules")?.let { r ->
            r.optJSONObject("extra")?.let(::withoutAmounts)
            r.optJSONObject("extraByShift")?.let { by -> by.keys().forEach { k -> by.optJSONObject(k)?.let(::withoutAmounts) } }
        }
        return root
    }

    private fun withoutAmounts(rules: JSONObject) {
        rules.keys().forEach { k -> rules.optJSONObject(k)?.put("amount", 0)?.put("bonus", 0) }
    }

    /** Reads a file without changing anything; throws [BackupException] for one that is not ours. */
    fun summarize(text: String): CompanySummary {
        val root = parse(text)
        val pay = root.optJSONObject("pay")
        return CompanySummary(
            company = root.optString("company"),
            shiftTypes = root.optJSONArray("shiftTypes").objects().map { it.getString("name") },
            cycleDays = root.optJSONObject("rotation")?.optJSONArray("steps")?.length(),
            hasRules = pay?.has("rules") == true,
            items = pay?.optJSONArray("items").objects().map { it.optString("name") },
            withAmounts = pay?.optBoolean("withAmounts") == true,
            extraKinds = root.optString("extraKinds").takeIf { it.isNotEmpty() }
                ?.let { ExtraKinds.decode(it).visible().map(ExtraKind::name) }.orEmpty(),
            team = root.optJSONObject("rotation")?.optString("team")?.trim()?.take(MAX_TEAM)?.takeIf { it.isNotEmpty() },
        )
    }

    /** [SETUP-01] What a file brings to the first setup, read without changing anything. */
    data class Shared(
        val company: String,
        /** The file's shifts with id 0, in its order. */
        val types: List<ShiftType>,
        /** The rotation by shift name (null a plain day off); empty when the file has none. */
        val order: List<String?>,
        /** The sender's step 0 date, and their 조 when they wrote it. */
        val anchor: LocalDate?,
        val team: String?,
        val hasPay: Boolean,
    )

    /**
     * [SETUP-01] Reads a file for the first setup, checking every part [apply] would use, so a file
     * that cannot be applied is turned down before the user answers anything. Throws [BackupException].
     */
    fun read(text: String): Shared {
        val root = parse(text)
        check(root)
        val rotation = root.optJSONObject("rotation")
        val steps = rotation?.optJSONArray("steps")
        return Shared(
            company = root.optString("company").take(MAX_NAME),
            types = root.optJSONArray("shiftTypes").objects().map(::type).distinctBy { it.name },
            order = if (steps == null) emptyList() else (0 until steps.length()).map { i -> if (steps.isNull(i)) null else steps.getString(i).take(MAX_NAME) },
            anchor = rotation?.let(::anchorOf),
            team = rotation?.optString("team")?.trim()?.take(MAX_TEAM)?.takeIf { it.isNotEmpty() },
            hasPay = root.has("pay"),
        )
    }

    /** Everything is read once before anything is written, so a broken part changes nothing. */
    private fun check(root: JSONObject) {
        runCatching {
            // [COMM-05] A built-in template has an order but no date to start it from.
            root.optJSONObject("rotation")?.let { r -> anchorOf(r); r.getJSONArray("steps") }
            root.optJSONObject("pay")?.let { pay ->
                // Shift types are names in the file; ids are filled in when applied.
                pay.optJSONObject("rules")?.let { PayCodec.rulesFrom(JSONObject(it.toString()).apply { remove("extraByShift") }) }
                pay.optJSONArray("items").objects().forEach { PayCodec.itemFrom(JSONObject(it.toString()).put("types", JSONArray())) }
            }
        }.onFailure { throw BackupException("회사 설정 파일 일부가 망가져 있어요") }
    }

    private fun anchorOf(rotation: JSONObject): LocalDate? =
        rotation.optString("anchor").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)

    /**
     * [COMM-05] A built-in template as a company settings file, so it goes through the first setup like
     * any shared setup. It names no company and no date.
     */
    fun templateText(t: CompanyTemplate): String {
        val root = JSONObject()
            .put("app", BackupManager.APP_ID).put("format", FORMAT).put("v", VERSION)
            .put("company", "").put("template", t.id)
            .put("shiftTypes", JSONArray(t.shifts.map { typeJson(it.type) }))
        if (t.order.isNotEmpty()) root.put("rotation", JSONObject().put("steps", JSONArray(t.orderNames.map { it ?: JSONObject.NULL })))
        return root.toString()
    }

    /**
     * [SETUP-01] The rest of a shared setup once the first setup has saved its shifts and order: the
     * kinds of extra work and the pay rules, as [apply] does them. True when pay waits for the user's
     * own amounts (no pay setup yet).
     */
    suspend fun applyRules(text: String): Boolean {
        val root = parse(text)
        check(root)
        val ids = schedule.snapshot.first().shiftTypes.associate { it.name to it.id }
        return applyRules(root, ids).second
    }

    /** Kinds of extra work, then pay rules; returns (rules applied, pay waiting for amounts). */
    private suspend fun applyRules(root: JSONObject, ids: Map<String, Long>): Pair<Boolean, Boolean> {
        // [EXTRA-01] Kinds merge like shift types: 잔업·특근·대근 by id, the company's own by name.
        var kindIds: Map<Long, Long> = emptyMap()
        root.optString("extraKinds").takeIf { it.isNotEmpty() }?.let { encoded ->
            val (merged, map) = mergeKinds(settings.current.extraKinds, ExtraKinds.decode(encoded), root.optJSONObject("extraKindShifts"),
                root.optJSONObject("extraKindShiftSets"), ids)
            kindIds = map
            settings.update { it.copy(extraKinds = merged) }
        }
        val pay = root.optJSONObject("pay") ?: return false to false
        val profile = payStore.profile.value ?: return false to true
        payStore.save(merge(profile, pay, ids, kindIds))
        return true to false
    }

    /**
     * Applies the file: shift types merge by name (alarms and colors the user set stay), the
     * rotation takes over from today with earlier days kept, and the pay rules replace the
     * company part of the user's pay setup. Returns what was applied, for the message.
     * [todayStep] is the step of the rotation the user works today [COMM-01]: a coworker on another
     * team shares the same order on other days. Without it the file's own start day is kept.
     */
    suspend fun apply(text: String, rotation: Boolean = true, todayStep: Int? = null): Applied {
        val root = parse(text)
        check(root)
        val existing = schedule.snapshot.first().shiftTypes
        val ids = mutableMapOf<String, Long>()
        var added = 0
        for (o in root.optJSONArray("shiftTypes").objects()) {
            val incoming = type(o)
            val mine = existing.firstOrNull { it.name == incoming.name }
            ids[incoming.name] = if (mine != null) {
                schedule.saveShiftType(mine.copy(start = incoming.start, end = incoming.end, breakMinutes = incoming.breakMinutes,
                    breakStart = incoming.breakStart, isOff = incoming.isOff))
            } else {
                added++
                schedule.saveShiftType(incoming)
            }
        }
        existing.forEach { ids.putIfAbsent(it.name, it.id) }
        var cycle: Int? = null
        root.optJSONObject("rotation")?.takeIf { rotation }?.let { r ->
            val a = r.getJSONArray("steps")
            val steps = (0 until a.length()).map { i -> if (a.isNull(i)) null else ids[a.getString(i).take(MAX_NAME)] }
            if (steps.isNotEmpty()) {
                val anchor = todayStep?.let { today().minusDays(Math.floorMod(it, steps.size).toLong()) } ?: anchorOf(r) ?: today()
                val pattern = WorkPattern.Rotation(startDate = today(), steps = steps, anchorDate = anchor)
                if (schedule.snapshot.first().hasPattern) schedule.changePatternFrom(pattern, today())
                else schedule.setFirstPattern(pattern, today())
                cycle = steps.size
            }
        }
        val (rules, pendingPay) = applyRules(root, ids)
        return Applied(added, cycle, rules, pendingPay)
    }

    data class Applied(val addedTypes: Int, val cycleDays: Int?, val rules: Boolean, val pendingPay: Boolean)

    /** [COMM-01] One amount a shared setup leaves to each person: an allowance or a fixed extra-work rule. */
    data class AmountNeed(val key: String, val label: String, val hint: String)

    /** What the user still types after applying [text]: amounts only, everything else came with it. */
    fun amountNeeds(text: String): List<AmountNeed> {
        val root = parse(text)
        val pay = root.optJSONObject("pay") ?: return emptyList()
        val kinds = root.optString("extraKinds").takeIf { it.isNotEmpty() }?.let(ExtraKinds::decode)
        val kindName = { key: String -> kindKey(key)?.let { id -> (kinds ?: ExtraKinds()).name(id) } ?: "추가 근무" }
        val items = pay.optJSONArray("items").objects().mapNotNull { o ->
            val item = runCatching { PayCodec.itemFrom(JSONObject(o.toString()).put("types", JSONArray())) }.getOrNull() ?: return@mapNotNull null
            // A bonus paid as a share of 기본급 needs no amount of its own.
            if (item.cadence == com.bamnat.core.pay.Cadence.BONUS && item.percentOfBase != null) null
            else AmountNeed("item:${item.name}", item.name, item.cadence.label)
        }
        val rules = pay.optJSONObject("rules")?.optJSONObject("extra")?.let { x ->
            x.keys().asSequence().mapNotNull { key ->
                val rule = x.optJSONObject(key) ?: return@mapNotNull null
                val basis = rule.optString("basis")
                val unit = rule.optString("unit")
                val hint = when {
                    basis == "FIXED_PER_TIME" || (basis == "UNIT" && unit == "PER_TIME") -> "1회 금액"
                    basis == "FIXED_PER_HOUR" || (basis == "UNIT" && unit == "PER_HOUR") -> "시간당 금액"
                    else -> return@mapNotNull null
                }
                AmountNeed("rule:$key", kindName(key), hint)
            }.toList()
        }.orEmpty()
        return items + rules
    }

    /**
     * [COMM-01] The first pay setup from a shared setup: its rules and allowances with the user's own
     * base pay and amounts. Amounts left out stay 0 and are marked as guesses until filled in.
     */
    suspend fun createPay(
        text: String, wageType: com.bamnat.core.pay.WageType, base: Long?, since: java.time.YearMonth, amounts: Map<String, Long>,
    ): PayProfile {
        val root = parse(text)
        val pay = root.optJSONObject("pay") ?: throw BackupException("급여 계산 방식이 없는 설정이에요")
        val from = since.atDay(1)
        val start = PayProfile(com.bamnat.core.pay.Occupation.CUSTOM, wageType, ValueTimeline(listOf(com.bamnat.core.pay.DatedValue(from, base ?: 0))))
        val ids = schedule.snapshot.first().shiftTypes.associate { it.name to it.id }
        // Kinds were merged by apply: 잔업·특근·대근 keep their ids, the company's own are found by name.
        val mine = settings.current.extraKinds
        val kindIds = root.optString("extraKinds").takeIf { it.isNotEmpty() }?.let { encoded ->
            ExtraKinds.decode(encoded).kinds.mapNotNull { k ->
                val id = if (k.isBuiltIn) k.id else mine.kinds.firstOrNull { !it.isBuiltIn && it.name == k.name }?.id
                id?.let { k.id to it }
            }.toMap()
        }.orEmpty()
        val merged = merge(start, JSONObject(pay.toString()), ids, kindIds)
        val guessed = mutableSetOf<String>()
        if (base == null) guessed += "base"
        val items = merged.items.map { item ->
            val amount = amounts["item:${item.name}"]
            when {
                amount != null -> item.copy(amount = ValueTimeline(listOf(com.bamnat.core.pay.DatedValue(from, amount))))
                item.cadence == com.bamnat.core.pay.Cadence.BONUS && item.percentOfBase != null -> item
                else -> item.also { guessed += it.id }
            }
        }
        val extra = merged.rules.extra.mapValues { (id, rule) ->
            val key = kindIds.entries.firstOrNull { it.value == id }?.key ?: id
            amounts["rule:$key"]?.let { rule.copy(amount = it) } ?: rule
        }
        val profile = merged.copy(items = items, rules = merged.rules.copy(extra = extra), estimatedFields = guessed)
        payStore.save(profile, keepUndo = false)
        return profile
    }

    private fun merge(p: PayProfile, pay: JSONObject, ids: Map<String, Long>, kindIds: Map<Long, Long>): PayProfile {
        val rulesJson = pay.optJSONObject("rules")
        rulesJson?.optJSONObject("extraByShift")?.let { by -> rulesJson.put("extraByShift", rekeyKinds(rekey(by) { ids[it.take(MAX_NAME)]?.toString() }, kindIds)) }
        rulesJson?.optJSONObject("extra")?.let { x -> rulesJson.put("extra", rekeyKinds(x, kindIds)) }
        rulesJson?.optString("publicHolidayAs")?.let(::kindKey)?.let { k -> rulesJson.put("publicHolidayAs", (kindIds[k] ?: k).toString()) }
        val incoming = rulesJson?.let(PayCodec::rulesFrom)
        // Kinds the file does not have keep the user's own rules.
        val theirs = kindIds.values.toSet() + incoming?.extra?.keys.orEmpty()
        val merged = incoming?.copy(
            extra = p.rules.extra.filterKeys { it !in theirs } + incoming.extra,
            extraByShift = p.rules.extraByShift.filterKeys { it !in theirs } + incoming.extraByShift,
        )
        val withAmounts = pay.optBoolean("withAmounts")
        // Allowances the user does not have yet are added; their own amounts are never overwritten.
        val have = p.items.map { it.name }.toSet()
        val items = pay.optJSONArray("items").objects().mapNotNull { o ->
            val types = o.optJSONArray("types")?.let { a -> (0 until a.length()).mapNotNull { ids[a.getString(it).take(MAX_NAME)] }.toSet() } ?: emptySet()
            val item = PayCodec.itemFrom(o.put("types", JSONArray())).copy(id = "c" + java.util.UUID.randomUUID().toString().take(8), shiftTypeIds = types)
            if (item.name in have) null else if (withAmounts) item else item.copy(amount = ValueTimeline())
        }
        return p.copy(
            fiveOrMoreEmployees = pay.optBoolean("fiveOrMore", p.fiveOrMoreEmployees),
            // Values from someone else's file stay within what the pay setup screens allow.
            monthlyHours = pay.optInt("monthlyHours", p.monthlyHours).takeIf { it in 100..320 } ?: p.monthlyHours,
            holidayPayMode = runCatching { HolidayPayMode.valueOf(pay.getString("holidayMode")) }.getOrDefault(p.holidayPayMode),
            dailyLimitMinutes = if (pay.has("dailyLimit")) pay.getInt("dailyLimit").takeIf { it in 1..24 * 60 } else p.dailyLimitMinutes,
            weeklyLimitMinutes = if (pay.has("weeklyLimit")) pay.getInt("weeklyLimit").takeIf { it in 1..168 * 60 } else p.weeklyLimitMinutes,
            scheduleIsRegular = pay.optBoolean("scheduleIsRegular", p.scheduleIsRegular),
            flexPeriod = pay.optString("flexPeriod").let { n -> FlexPeriod.entries.firstOrNull { it.name == n } },
            weekStart = runCatching { DayOfWeek.valueOf(pay.getString("weekStart")) }.getOrDefault(p.weekStart),
            period = pay.optJSONObject("period")?.let(PayCodec::periodFrom) ?: p.period,
            items = p.items + items,
            // The user's own leave days stay theirs.
            rules = merged?.copy(annualLeaveDays = p.rules.annualLeaveDays, annualLeaveCarry = p.rules.annualLeaveCarry) ?: p.rules,
        )
    }

    private fun typeJson(t: ShiftType): JSONObject = JSONObject()
        .put("name", t.name).put("label", t.label).put("color", t.colorArgb)
        .putOpt("start", t.start?.toString()).putOpt("end", t.end?.toString())
        .put("break", t.breakMinutes).putOpt("breakStart", t.breakStart?.toString())
        .put("off", t.isOff).putOpt("icon", t.icon)

    private fun type(o: JSONObject): ShiftType = ShiftType(
        id = 0,
        name = o.getString("name").take(MAX_NAME),
        label = o.optString("label"),
        colorArgb = o.optLong("color", 0xFF5C6BC0),
        start = o.optTime("start"), end = o.optTime("end"),
        breakMinutes = o.optInt("break"), breakStart = o.optTime("breakStart"),
        isOff = o.optBoolean("off"),
        icon = if (o.has("icon") && !o.isNull("icon")) o.getString("icon") else null,
    )

    private fun rekey(o: JSONObject, key: (String) -> String?): JSONObject = JSONObject().apply {
        o.keys().forEach { kind ->
            if (kindKey(kind) == null) return@forEach
            val byType = o.optJSONObject(kind) ?: return@forEach
            put(kind, JSONObject().apply { byType.keys().forEach { k -> key(k)?.let { put(it, byType.get(k)) } } })
        }
    }

    /** Kind keys of the file to this phone's ids. */
    private fun rekeyKinds(o: JSONObject, kindIds: Map<Long, Long>): JSONObject = JSONObject().apply {
        o.keys().forEach { key -> kindKey(key)?.let { id -> put((kindIds[id] ?: id).toString(), o.get(key)) } }
    }

    /** A kind id in the file, or a name from 1.8 and earlier. */
    private fun kindKey(key: String): Long? = key.toLongOrNull() ?: when (key) {
        "OVERTIME" -> ExtraKind.OVERTIME_ID
        "SPECIAL" -> ExtraKind.SPECIAL_ID
        "COVER" -> ExtraKind.COVER_ID
        else -> null
    }

    /**
     * The file's kinds merged into the user's: 잔업·특근·대근 (ids 1~3) take the company's names and
     * times, a kind of the company's own replaces the user's of the same name or is added. Days
     * already marked keep their kind. Returns the kinds and each file id's id on this phone.
     */
    private fun mergeKinds(
        mine: ExtraKinds, incoming: ExtraKinds, shifts: JSONObject?, sets: JSONObject?, ids: Map<String, Long>,
    ): Pair<ExtraKinds, Map<Long, Long>> {
        var merged = mine
        val map = HashMap<Long, Long>()
        for (k in incoming.kinds) {
            val timing = (k.timing as? ExtraTiming.AsShift)?.let {
                val key = k.id.toString()
                val named = sets?.optJSONArray(key)?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }
                    ?: listOfNotNull(shifts?.optString(key)?.takeIf { it.isNotEmpty() })
                ExtraTiming.AsShift(named.mapNotNull { ids[it.take(MAX_NAME)] }.toSet())
            } ?: k.timing
            val target = if (k.isBuiltIn) merged.byId(k.id) else merged.kinds.firstOrNull { !it.isBuiltIn && it.name == k.name }
            val id = target?.id ?: if (k.isBuiltIn) k.id else merged.nextId()
            merged = merged.put(k.copy(id = id, timing = timing))
            map[k.id] = id
        }
        return merged to map
    }

    private fun parse(text: String): JSONObject {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw BackupException("회사 설정 파일을 읽을 수 없어요")
        if (root.optString("format") != FORMAT) throw BackupException("밤낮 회사 설정 파일이 아니에요")
        if (root.optInt("v") > VERSION) throw BackupException("더 새로운 버전에서 만든 파일이에요. 앱을 업데이트해 주세요")
        if (root.optJSONArray("shiftTypes").objects().isEmpty()) throw BackupException("근무 종류가 없는 파일이에요")
        return root
    }

    private fun JSONObject.optTime(key: String): LocalTime? =
        if (!has(key) || isNull(key)) null else runCatching { LocalTime.parse(getString(key)) }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    companion object {
        const val FORMAT = "bamnat-company"
        /** 2: kinds of extra work by id with their times [EXTRA-01]; 1.8 asks to update instead of dropping them. */
        const val VERSION = 2
        const val MIME_TYPE = "application/json"
        private const val MAX_NAME = 40
        const val MAX_TEAM = 12

        fun isCompanyFile(text: String): Boolean =
            runCatching { JSONObject(text).optString("format") == FORMAT }.getOrDefault(false)
    }
}
