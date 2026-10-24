package com.bamnat.app.data

import com.bamnat.app.AppContainer
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.profile.Fact
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactMeta
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * [EASY-03] The profile: every fact the app knows about one calendar, read from the store that has always
 * held it (research/1.23-N-설계.md 1장, the 정본 표), with where it came from. Nothing is moved: this keeps
 * only each fact's [FactMeta] (source, how sure, when, a fingerprint) in `profile/<calendar>.json`, and a value
 * changed on any screen of the app reads as typed because its fingerprint no longer matches ([Profile.read]).
 */
class ProfileStore(private val c: AppContainer, private val dir: File = File(c.appContext.filesDir, "profile")) {
    private val lock = Mutex()

    /** The facts of [calendarId] that fit its kind of work, as they stand now. */
    suspend fun facts(calendarId: String): Map<FactKey, Fact<*>> = withContext(Dispatchers.Default) {
        val scope = c.scopeOf(calendarId)
        val v = values(scope)
        val meta = meta(calendarId)
        FactKey.of(scope.currentSettings().workStyle).associateWith { key ->
            val read = v[key]
            Profile.read(key, read?.value, meta[key.id], read?.default, read?.estimated == true)
        }
    }

    /**
     * Records that [source] gave [key] its value now (an inference, a company file, a question answered). The
     * value itself was already written to its store by whoever wrote it; this reads it back for the fingerprint.
     */
    suspend fun mark(calendarId: String, key: FactKey, source: Source, confidence: Double = 1.0, why: String? = null) {
        val value = values(c.scopeOf(calendarId))[key]?.value
        lock.withLock {
            val all = meta(calendarId).toMutableMap()
            all[key.id] = Profile.written(value, source, confidence, Instant.now(), why)
            writeMeta(calendarId, all)
        }
    }

    /** [EASY-09] 모두 처음부터: where every fact came from is forgotten; the values stay where they are. */
    suspend fun forget(calendarId: String) = lock.withLock { withContext(Dispatchers.IO) { file(calendarId).delete() } }

    /**
     * [EASY-09] 모두 처음부터 for [calendarId]: every value it knows is asked again, one at a time, as if the
     * app had only guessed it ([Profile.RECHECK]), and the questions put off are asked again too. The values
     * themselves, the shifts, the pay and every record stay as they are meanwhile. What it was before is kept
     * [UNDO_DAYS] days for [undoStartOver].
     */
    suspend fun startOver(calendarId: String, asks: AskStore, now: Instant = Instant.now()) {
        val values = values(c.scopeOf(calendarId))
        val known = facts(calendarId).values.filter { it.known }.map { it.key }
        lock.withLock { withContext(Dispatchers.IO) {
            val meta = file(calendarId).takeIf { it.exists() }?.readText()
            val o = JSONObject().put("at", now.toEpochMilli()).putOpt("meta", meta).put("asks", AskStore.encode(asks.of(calendarId)))
            dir.mkdirs()
            reset(calendarId).writeText(o.toString())
        } }
        lock.withLock {
            writeMeta(calendarId, known.associate { k -> k.id to Profile.written(values[k]?.value, Source.INFERRED, Profile.MIN_CONFIDENCE, now, Profile.RECHECK) })
        }
        // Today's count stays, so starting over does not ask more today than any other day.
        asks.set(calendarId, asks.of(calendarId).copy(current = null, snoozedUntil = emptyMap(), snoozes = emptyMap()))
    }

    /** [EASY-09] When 모두 처음부터 can still be taken back, or null. */
    suspend fun startedOver(calendarId: String, now: Instant = Instant.now()): Instant? = withContext(Dispatchers.IO) {
        runCatching {
            val f = reset(calendarId)
            if (!f.exists()) return@runCatching null
            val at = Instant.ofEpochMilli(JSONObject(f.readText()).getLong("at"))
            if (java.time.Duration.between(at, now).toDays() >= UNDO_DAYS) { f.delete(); null } else at
        }.getOrNull()
    }

    /** [EASY-09] 되돌리기: the sources and the put-off questions as they were before 모두 처음부터. */
    suspend fun undoStartOver(calendarId: String, asks: AskStore): Boolean = lock.withLock {
        val o = withContext(Dispatchers.IO) { runCatching { JSONObject(reset(calendarId).readText()) }.getOrNull() } ?: return@withLock false
        withContext(Dispatchers.IO) {
            o.optString("meta").takeIf { it.isNotEmpty() }?.let { dir.mkdirs(); file(calendarId).writeText(it) }
            reset(calendarId).delete()
        }
        asks.set(calendarId, AskStore.decode(o.optJSONObject("asks")?.toString()))
        true
    }

    /** [EASY-03] For the backup (결정 N-D4): each calendar's meta as it is kept. */
    fun export(): Map<String, String> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().filter { it.name != "${PersonalCalendar.EXAMPLE}.json" }
            .associate { it.name.removeSuffix(".json") to it.readText() }

    fun import(all: Map<String, String>) {
        dir.mkdirs()
        all.forEach { (id, text) -> if (id != PersonalCalendar.EXAMPLE && runCatching { JSONObject(text) }.isSuccess && SAFE_ID.matches(id)) File(dir, "$id.json").writeText(text) }
    }

    /** The keys that have an adapter, for `ProfileStoreTest`. */
    internal suspend fun adapted(calendarId: String): Set<FactKey> = values(c.scopeOf(calendarId)).keys

    private fun file(calendarId: String) = File(dir, "$calendarId.json")
    /** Not ".json": the backup takes those, and what was forgotten is not carried to another phone. */
    private fun reset(calendarId: String) = File(dir, "$calendarId.reset")

    private suspend fun meta(calendarId: String): Map<String, FactMeta> = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(calendarId)
            if (!f.exists()) return@runCatching emptyMap()
            val o = JSONObject(f.readText())
            o.keys().asSequence().mapNotNull { k -> decode(o.optJSONObject(k))?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeMeta(calendarId: String, all: Map<String, FactMeta>) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val o = JSONObject()
        all.forEach { (k, m) -> o.put(k, encode(m)) }
        val tmp = File(dir, "$calendarId.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(calendarId))
    }

    /** A fact's value in its store, the store's own starting value, and the store's own '추정' mark. */
    private data class Read(val value: Any?, val default: Any? = null, val estimated: Boolean = false)

    /** [EASY-03] The adapters: one line per [FactKey], reading the 정본 of 1장. `ProfileStoreTest` checks every key has one. */
    private suspend fun values(scope: CalendarScope): Map<FactKey, Read> {
        val settings = scope.currentSettings()
        val cal = scope.calendar
        val pay: PayProfile? = scope.pay.profile.value
        val est = pay?.estimatedFields.orEmpty()
        val work = scope.schedule.shiftTypes.first().filter { it.hasTimes }
        val patterns = scope.schedule.patterns.first()
        val prefs = WorkTypePrefs.get(c.appContext).of(scope.id)
        val defaults = PayProfile(occupation = com.bamnat.core.pay.Occupation.entries.first(), wageType = com.bamnat.core.pay.WageType.MONTHLY,
            base = com.bamnat.core.pay.ValueTimeline())
        // [EASY-06] A pay setup that is saved was answered by the user, even where an answer is also the law's
        // starting value: only its own 모르겠어요 marks read as guesses ([default] is kept for the record of 1장).
        @Suppress("UNUSED_PARAMETER")
        fun p(value: (PayProfile) -> Any?, default: Any? = null, mark: String? = null) =
            Read(pay?.let(value), null, mark != null && mark in est)
        return mapOf(
            FactKey.WORK_STYLE to Read(settings.workStyle),
            FactKey.SHIFTS to Read(work.takeIf { it.isNotEmpty() }?.map { listOf(it.name, it.start, it.end, it.breakMinutes) }),
            FactKey.ROTATION to Read(patterns.takeIf { it.isNotEmpty() } ?: "monthly".takeIf { settings.rosterMonthly }),
            FactKey.EXTRA_KINDS to Read(settings.extraKinds),
            FactKey.REST_RULES to Read(settings.restRules, ""),
            FactKey.TEAM to Read(c.teams.get(scope.id)),
            FactKey.OVERTIME_CAP to Read(prefs.overtimeCapHours),
            FactKey.ALARM_LEAD to Read(work.firstOrNull { it.alarmEnabled }?.alarms?.firstOrNull { it.enabled }?.offsetMinutes, 60),
            FactKey.WAGE_TYPE to p({ it.wageType }),
            FactKey.BASE_PAY to p({ pr -> pr.base.entries.maxByOrNull { it.from }?.value?.takeIf { it > 0 } }, mark = Unknowns.BASE),
            FactKey.PAY_ITEMS to p({ it.items }),
            FactKey.PAY_DAY to p({ it.period.payDay }),
            FactKey.MONTHLY_HOURS to p({ it.monthlyHours }, defaults.monthlyHours, Unknowns.MONTHLY_HOURS),
            FactKey.DAILY_HOURS to p({ it.dailyHours }, defaults.dailyHours),
            FactKey.HOLIDAY_PAY to p({ it.holidayPayMode }, HolidayPayMode.FULL, Unknowns.HOLIDAY_PAY_MODE),
            FactKey.NIGHT_OT to p({ listOf(it.rules.overtimePremium, it.rules.nightPremium, it.rules.nightStart, it.rules.nightEnd) },
                CompanyRules().let { r -> listOf(r.overtimePremium, r.nightPremium, r.nightStart, r.nightEnd) }),
            FactKey.PUBLIC_HOLIDAYS to p({ listOf(it.rules.publicHolidaysPaid, it.rules.publicHolidayAs) },
                CompanyRules().let { r -> listOf(r.publicHolidaysPaid, r.publicHolidayAs) }, Unknowns.PUBLIC_HOLIDAYS),
            FactKey.FIVE_OR_MORE to p({ it.fiveOrMoreEmployees }, true, Unknowns.FIVE_OR_MORE),
            FactKey.HIRE_DATE to p({ it.hireDate }),
            // The pay setup's own when there is one; the 통계 card's choice otherwise (결정: 정본 표 1-2).
            FactKey.TAX_MODE to (if (pay != null) Read(pay.taxMode) else Read(prefs.taxMode, WorkTypeChoices().taxMode)),
            FactKey.TAX_FAMILY to p({ it.taxFamily to it.taxChildren }, defaults.taxFamily to defaults.taxChildren),
            FactKey.PREVIOUS_YEAR_PAY to p({ it.previousYearPay }),
            FactKey.SLEEP_TARGET to Read(settings.sleepTargetMinutes, AppSettings().sleepTargetMinutes),
            FactKey.COMMUTE to Read(settings.commuteMinutes, AppSettings().commuteMinutes),
            FactKey.COMPANY to Read(if (scope.isMain) c.companyStore.applied.value?.companyId ?: c.companyStore.draft.value?.company else null),
            FactKey.SHOP_RULES to Read(cal?.shop),
            FactKey.SHOP_MONEY to Read(cal?.shopMoney),
            FactKey.FIELD_RULES to Read(cal?.field),
        )
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        /** [EASY-09] As long as 최근 지운 기록 keeps what was cleared. */
        const val UNDO_DAYS = 7L

        fun encode(m: FactMeta): JSONObject = JSONObject()
            .put("s", m.source.name).put("c", m.confidence)
            .putOpt("at", m.checkedAt?.toEpochMilli()).putOpt("f", m.fingerprint).putOpt("w", m.why)

        fun decode(o: JSONObject?): FactMeta? = o?.let {
            runCatching {
                FactMeta(Source.valueOf(it.getString("s")), it.optDouble("c", 1.0),
                    it.optLong("at", 0).takeIf { t -> t > 0 }?.let(Instant::ofEpochMilli),
                    it.optString("f").takeIf { f -> f.isNotEmpty() }, it.optString("w").takeIf { w -> w.isNotEmpty() })
            }.getOrNull()
        }
    }
}
