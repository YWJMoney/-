package com.bamnat.app.data

import androidx.room.withTransaction
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.DailySalesEntity
import com.bamnat.app.data.db.DayEntryEntity
import com.bamnat.app.data.db.DayExtraEntity
import com.bamnat.app.data.db.JobEntity
import com.bamnat.app.data.db.MoneyEntryEntity
import com.bamnat.app.data.db.FixedCostEntity
import com.bamnat.app.data.db.CategoryRuleEntity
import com.bamnat.app.data.db.MemoEntity
import com.bamnat.app.data.db.PatternEntity
import com.bamnat.app.data.db.ShiftTypeEntity
import com.bamnat.core.work.WorkStyle
import org.json.JSONArray
import org.json.JSONObject
import com.bamnat.core.backup.BackupCrypto
import java.time.DayOfWeek
import java.util.Base64

/** What a backup file holds, shown before the user confirms a restore. */
data class BackupSummary(val shiftTypes: Int, val days: Int, val memos: Int, val exportedAtMillis: Long, val moneyLines: Int = 0, val extras: Int = 0)

class BackupException(message: String) : Exception(message)

/**
 * Writes everything the user entered (shift types, pattern, changed days, memos including the
 * trash, settings) to one JSON document and reads it back. Used to move to a new phone or to
 * keep a copy before reinstalling.
 */
class BackupManager(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** [PAY-10] The pay setup and closed months live in their own file, but belong in the backup. */
    private val payStore: com.bamnat.app.pay.PayStore? = null,
) {
    /** One read transaction, so an edit made meanwhile cannot leave days pointing at a missing type [QA-01]. */
    suspend fun export(): String = db.withTransaction { exportNow() }

    private suspend fun exportNow(): String {
        val root = JSONObject()
            .put("app", APP_ID)
            .put("format", FORMAT_VERSION)
            .put("exportedAt", clock())
        val types = JSONArray()
        db.shiftTypeDao().getAll().forEach { t ->
            types.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name).put("label", t.label).put("color", t.colorArgb)
                    .putOpt("start", t.startMinute).putOpt("end", t.endMinute)
                    .put("break", t.breakMinutes).put("off", t.isOff)
                    .put("alarm", t.alarmEnabled).put("alarmOffset", t.alarmOffsetMinutes)
                    .putOpt("sound", t.alarmSoundUri).put("order", t.sortOrder).put("alarms", t.alarmOffsets).putOpt("icon", t.icon)
                    .putOpt("breakStart", t.breakStartMinute),
            )
        }
        root.put("shiftTypes", types)
        val patterns = JSONArray()
        db.patternDao().getAll().forEach { p ->
            patterns.put(
                JSONObject().put("kind", p.kind).put("start", p.startEpochDay).putOpt("end", p.endEpochDay)
                    .put("steps", p.steps).putOpt("anchor", p.anchorEpochDay),
            )
        }
        root.put("patterns", patterns)
        val days = JSONArray()
        db.dayEntryDao().getAll().forEach { d ->
            days.put(
                JSONObject()
                    .put("day", d.epochDay).put("mode", d.overrideMode).putOpt("type", d.overrideShiftTypeId)
                    .putOpt("start", d.customStartMinute).putOpt("end", d.customEndMinute)
                    .putOpt("break", d.customBreakMinutes).put("skip", d.alarmSkipped)
                    .putOpt("alarms", d.customAlarms).put("leave", d.leave),
            )
        }
        root.put("days", days)
        // [EXTRA-01] Extra work by date (format 2).
        root.put("extras", JSONArray().apply {
            db.dayExtraDao().getAll().forEach { x ->
                put(JSONObject().put("day", x.epochDay).put("kind", x.kindId).putOpt("start", x.startMinute).putOpt("end", x.endMinute)
                    .put("break", x.breakMinutes).putOpt("breakStart", x.breakStartMinute).put("minutes", x.minutes)
                    .putOpt("covered", x.coveredShiftTypeId).put("review", x.needsReview).put("pos", x.position))
            }
        })
        val memos = JSONArray()
        db.memoDao().getAll().forEach { m ->
            memos.put(
                JSONObject()
                    .put("id", m.id).putOpt("day", m.epochDay).put("title", m.title).put("body", m.body)
                    .put("checklist", m.checklist).put("isChecklist", m.isChecklist).put("color", m.colorIndex)
                    .put("pinned", m.pinned).put("created", m.createdAt).put("updated", m.updatedAt)
                    .putOpt("deleted", m.deletedAt),
            )
        }
        root.put("memos", memos)
        // [WORK-06] Ledger, jobs and sales (version 7). Older apps ignore these keys.
        val money = db.moneyDao()
        root.put("money", JSONArray().apply {
            money.allEntries().forEach { e ->
                put(JSONObject().put("id", e.id).put("day", e.epochDay).put("kind", e.kind).put("amount", e.amount)
                    .put("category", e.category).put("note", e.note).put("source", e.source).put("created", e.createdAt)
                    .put("merchant", e.merchant).putOpt("minute", e.minuteOfDay).put("card", e.cardLast4).put("reviewed", e.reviewed))
            }
        })
        root.put("jobs", JSONArray().apply {
            money.allJobs().forEach { j ->
                put(JSONObject().put("id", j.id).put("day", j.epochDay).put("title", j.title).put("client", j.client)
                    .put("place", j.place).putOpt("start", j.startMinute).putOpt("end", j.endMinute).put("amount", j.amount)
                    .put("deposit", j.deposit).put("depositPaid", j.depositPaid).put("balancePaid", j.balancePaid)
                    .putOpt("remind", j.remindMinutes).put("note", j.note)
                    .putOpt("endDay", j.endEpochDay).put("costs", j.costs).put("withholding", j.withholding))
            }
        })
        root.put("sales", JSONArray().apply {
            money.allSales().forEach { d -> put(JSONObject().put("day", d.epochDay).put("sales", d.sales).put("costs", d.costs).put("note", d.note)) }
        })
        // [LEDG-05][LEDG-04] Fixed costs and learned categories (version 9). Unread notification texts are never backed up.
        root.put("fixed", JSONArray().apply {
            money.allFixed().forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("amount", f.amount).put("day", f.day).put("kind", f.kind)
                    .put("category", f.category).put("remind", f.remind))
            }
        })
        root.put("rules", JSONArray().apply {
            money.allRules().forEach { r -> put(JSONObject().put("merchant", r.merchant).put("category", r.category)) }
        })
        // [SLP-06] The sleep log goes with the rest of the user's data.
        root.put("sleep", JSONArray().apply {
            db.sleepDao().all().forEach { l -> put(JSONObject().put("start", l.startMillis).putOpt("end", l.endMillis)) }
        })
        payStore?.let { store ->
            store.profile.value?.let { root.put("pay", com.bamnat.app.pay.PayCodec.encode(it)) }
            root.put("payClosed", JSONArray(store.closed.value.values.map(com.bamnat.app.pay.SnapshotCodec::encode)))
        }
        val s = settings.current
        root.put(
            "settings",
            JSONObject()
                .put("weekStart", s.weekStart.name).put("weeklyLimit", s.weeklyLimitHours)
                .put("averageLimit", s.averageLimitHours).put("theme", s.themeMode.name)
                .put("dynamicColor", s.dynamicColor).put("cellHours", s.showHoursInCells).put("lunar", s.showLunar)
                .putOpt("sound", s.defaultAlarmSound).put("vibrate", s.vibrate).put("gradual", s.gradualVolume)
                .put("snooze", s.snoozeMinutes).put("ring", s.ringMinutes).put("alarmsOn", s.alarmsEnabled)
                .putOpt("workStyle", s.workStyle?.name)
                .put("extraKinds", s.extraKinds.encode()).put("memoFirst", s.memoFirst).put("calendarMemos", s.calendarMemos)
                .put("restCheck", s.restCheck)
                .put("strictRest", s.strictRest).put("sleepTips", s.sleepTips).put("sleepReminders", s.sleepReminders)
                .put("sleepTarget", s.sleepTargetMinutes),
        )
        return root.toString(1)
    }

    /** Reads a backup without changing anything. Throws [BackupException] for a file that is not ours. */
    fun summarize(json: String): BackupSummary {
        val root = parse(json)
        return BackupSummary(
            shiftTypes = root.optJSONArray("shiftTypes")?.length() ?: 0,
            days = root.optJSONArray("days")?.length() ?: 0,
            memos = root.optJSONArray("memos")?.length() ?: 0,
            extras = root.optJSONArray("extras")?.length() ?: 0,
            exportedAtMillis = root.optLong("exportedAt"),
            moneyLines = (root.optJSONArray("money")?.length() ?: 0) + (root.optJSONArray("jobs")?.length() ?: 0) +
                (root.optJSONArray("sales")?.length() ?: 0) + (root.optJSONArray("fixed")?.length() ?: 0),
        )
    }

    /** Replaces all data with the backup, in one transaction: a broken file changes nothing. */
    suspend fun restore(json: String) {
        val root = parse(json)
        val types = root.getJSONArray("shiftTypes").objects().map { o ->
            ShiftTypeEntity(
                id = o.getLong("id"), name = o.getString("name"), label = o.getString("label"),
                colorArgb = o.getLong("color"), startMinute = o.optIntOrNull("start"), endMinute = o.optIntOrNull("end"),
                breakMinutes = o.optInt("break"), isOff = o.optBoolean("off"), alarmEnabled = o.optBoolean("alarm"),
                alarmOffsetMinutes = o.optInt("alarmOffset", 60), alarmSoundUri = o.optStringOrNull("sound"),
                sortOrder = o.optInt("order"), alarmOffsets = o.optString("alarms", ""), icon = o.optStringOrNull("icon"),
                breakStartMinute = o.optIntOrNull("breakStart"),
            )
        }
        if (types.isEmpty()) throw BackupException("근무 유형이 없는 백업 파일이에요")
        // Older backups held one "pattern" object instead of the "patterns" history.
        val patternObjects = root.optJSONArray("patterns")?.objects()
            ?: listOfNotNull(root.optJSONObject("pattern"))
        val patterns = patternObjects.mapIndexed { i, p ->
            PatternEntity(
                id = i + 1, kind = p.getString("kind"), startEpochDay = p.getLong("start"),
                endEpochDay = if (p.isNull("end")) null else p.optLong("end"), steps = p.getString("steps"),
                anchorEpochDay = if (p.isNull("anchor") || !p.has("anchor")) null else p.getLong("anchor"),
            )
        }
        val days = root.optJSONArray("days").objects().map { o ->
            DayEntryEntity(
                epochDay = o.getLong("day"), overrideMode = o.optInt("mode"),
                overrideShiftTypeId = if (o.isNull("type") || !o.has("type")) null else o.getLong("type"),
                customStartMinute = o.optIntOrNull("start"), customEndMinute = o.optIntOrNull("end"),
                customBreakMinutes = o.optIntOrNull("break"),
                // Format 1 (1.8 and earlier) kept extra work on the day; it moves to day_extras below.
                overtimeMinutes = o.optInt("overtime").coerceIn(0, 24 * 60),
                holidayWorkMinutes = o.optInt("holiday").coerceIn(0, 24 * 60), alarmSkipped = o.optBoolean("skip"),
                customAlarms = o.optStringOrNull("alarms"), extraWork = o.optInt("extra"), leave = o.optInt("leave"),
                secondShiftTypeId = if (o.has("second") && !o.isNull("second")) o.getLong("second") else null,
            )
        }
        val extras = root.optJSONArray("extras")?.objects()?.map { o ->
            DayExtraEntity(
                epochDay = o.getLong("day"), kindId = o.getLong("kind"), startMinute = o.optIntOrNull("start"),
                endMinute = o.optIntOrNull("end"), breakMinutes = o.optInt("break").coerceIn(0, 24 * 60),
                breakStartMinute = o.optIntOrNull("breakStart"), minutes = o.optInt("minutes").coerceIn(0, 24 * 60),
                coveredShiftTypeId = if (o.has("covered") && !o.isNull("covered")) o.getLong("covered") else null,
                needsReview = o.optBoolean("review"), position = o.optInt("pos"),
            )
        }
        val memos = root.optJSONArray("memos").objects().map { o ->
            MemoEntity(
                id = o.getLong("id"), epochDay = if (o.isNull("day") || !o.has("day")) null else o.getLong("day"),
                title = o.optString("title"), body = o.optString("body"), checklist = o.optString("checklist"),
                isChecklist = o.optBoolean("isChecklist"), colorIndex = o.optInt("color"), pinned = o.optBoolean("pinned"),
                createdAt = o.optLong("created"), updatedAt = o.optLong("updated"),
                deletedAt = if (o.isNull("deleted") || !o.has("deleted")) null else o.getLong("deleted"),
            )
        }
        val entries = root.optJSONArray("money")?.objects()?.map { o ->
            MoneyEntryEntity(
                id = o.getLong("id"), epochDay = o.getLong("day"), kind = o.getString("kind"), amount = o.getLong("amount"),
                category = o.optString("category"), note = o.optString("note"), source = o.optString("source", "MANUAL"),
                createdAt = o.optLong("created"), merchant = o.optString("merchant"), minuteOfDay = o.optIntOrNull("minute"),
                cardLast4 = o.optString("card"), reviewed = o.optBoolean("reviewed", true),
            )
        }
        val fixed = root.optJSONArray("fixed")?.objects()?.map { o ->
            FixedCostEntity(o.getLong("id"), o.optString("name"), o.optLong("amount"), o.optInt("day", 1), o.optString("kind", "EXPENSE"),
                o.optString("category"), o.optBoolean("remind", true))
        }
        val rules = root.optJSONArray("rules")?.objects()?.map { o -> CategoryRuleEntity(o.getString("merchant"), o.optString("category")) }
        val sleep = root.optJSONArray("sleep")?.objects()?.map { o ->
            com.bamnat.app.data.db.SleepLogEntity(startMillis = o.getLong("start"), endMillis = if (o.has("end") && !o.isNull("end")) o.getLong("end") else null)
        }
        val jobs = root.optJSONArray("jobs")?.objects()?.map { o ->
            JobEntity(
                id = o.getLong("id"), epochDay = o.getLong("day"), title = o.optString("title"), client = o.optString("client"),
                place = o.optString("place"), startMinute = o.optIntOrNull("start"), endMinute = o.optIntOrNull("end"),
                amount = o.optLong("amount"), deposit = o.optLong("deposit"), depositPaid = o.optBoolean("depositPaid"),
                balancePaid = o.optBoolean("balancePaid"), remindMinutes = o.optIntOrNull("remind"), note = o.optString("note"),
                endEpochDay = if (o.has("endDay") && !o.isNull("endDay")) o.getLong("endDay") else null,
                costs = o.optLong("costs"), withholding = o.optBoolean("withholding"),
            )
        }
        val sales = root.optJSONArray("sales")?.objects()?.map { o ->
            DailySalesEntity(o.getLong("day"), o.optLong("sales"), o.optLong("costs"), o.optString("note"))
        }
        // Read before anything changes, so a broken pay part leaves the phone as it was.
        val pay = root.optJSONObject("pay")?.let(com.bamnat.app.pay.PayCodec::decode)
        val payClosed = root.optJSONArray("payClosed")?.objects()?.map(com.bamnat.app.pay.SnapshotCodec::decode)
        db.withTransaction {
            // Backups from before version 7 carry no money; what is on the phone then stays.
            val money = db.moneyDao()
            entries?.let { list -> money.clearEntries(); list.forEach { money.restoreEntry(it) } }
            jobs?.let { list -> money.clearJobs(); list.forEach { money.restoreJob(it) } }
            sales?.let { list -> money.clearSales(); list.forEach { money.upsertSales(it) } }
            fixed?.let { list -> money.clearFixed(); list.forEach { money.restoreFixed(it) } }
            rules?.let { list -> money.clearRules(); list.forEach { money.upsertRule(it) } }
            // Backups from before version 11 carry no sleep log; the one on the phone then stays.
            sleep?.let { list -> db.sleepDao().clear(); list.forEach { db.sleepDao().insert(it) } }
            db.memoDao().clear()
            db.dayChangeDao().clear() // the log described the days being replaced [MKT-08]
            db.dayEntryDao().clear()
            db.dayExtraDao().clear()
            db.patternDao().clear()
            db.shiftTypeDao().clear()
            db.shiftTypeDao().insertAll(types)
            db.patternDao().saveAll(patterns)
            db.dayEntryDao().insertAll(days)
            if (extras != null) db.dayExtraDao().insertAll(extras)
            // [EXTRA-05] A backup from 1.8 or earlier: the same move as the database upgrade.
            else com.bamnat.app.data.db.AppDatabase.moveOldExtraWork(db.openHelper.writableDatabase)
            memos.forEach { db.memoDao().restore(it) }
        }
        // The pay setup points at shift types by id, and those ids just changed to the backup's: a
        // backup with a pay setup brings it along. Older backups keep the phone's own.
        payStore?.let { store ->
            if (pay != null) store.save(pay, keepUndo = false)
            if (payClosed != null) store.replaceClosed(payClosed.associateBy { it.month })
        }
        root.optJSONObject("settings")?.let { o ->
            settings.update { s ->
                s.copy(
                    weekStart = runCatching { DayOfWeek.valueOf(o.getString("weekStart")) }.getOrDefault(s.weekStart),
                    weeklyLimitHours = o.optInt("weeklyLimit", s.weeklyLimitHours).coerceIn(1, 168),
                    averageLimitHours = o.optInt("averageLimit", s.averageLimitHours).coerceIn(1, 168),
                    themeMode = runCatching { ThemeMode.valueOf(o.getString("theme")) }.getOrDefault(s.themeMode),
                    dynamicColor = o.optBoolean("dynamicColor", s.dynamicColor),
                    showHoursInCells = o.optBoolean("cellHours", s.showHoursInCells),
                    showLunar = o.optBoolean("lunar", s.showLunar),
                    // Sound files from another phone usually do not exist here.
                    vibrate = o.optBoolean("vibrate", s.vibrate),
                    gradualVolume = o.optBoolean("gradual", s.gradualVolume),
                    snoozeMinutes = o.optInt("snooze", s.snoozeMinutes).coerceIn(1, 60),
                    ringMinutes = o.optInt("ring", s.ringMinutes).coerceIn(1, 60),
                    alarmsEnabled = o.optBoolean("alarmsOn", s.alarmsEnabled),
                    workStyle = o.optStringOrNull("workStyle")?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } } ?: s.workStyle,
                    extraKinds = o.optStringOrNull("extraKinds")?.let { com.bamnat.core.model.ExtraKinds.decode(it) } ?: s.extraKinds,
                    memoFirst = o.optBoolean("memoFirst", s.memoFirst),
                    calendarMemos = o.optBoolean("calendarMemos", s.calendarMemos),
                    restCheck = o.optBoolean("restCheck", s.restCheck),
                    strictRest = o.optBoolean("strictRest", s.strictRest),
                    sleepTips = o.optBoolean("sleepTips", s.sleepTips),
                    sleepReminders = o.optBoolean("sleepReminders", s.sleepReminders),
                    sleepTargetMinutes = o.optInt("sleepTarget", s.sleepTargetMinutes).coerceIn(4 * 60, 10 * 60),
                    // Old records came back with the backup; offer to fix them again.
                    swapCoversAnswered = s.swapCoversAnswered && extras != null,
                )
            }
        }
    }

    /** True for a password-protected backup [FEAT-08]. */
    fun isSealed(text: String): Boolean =
        runCatching { JSONObject(text).optString("format") == SEALED_FORMAT }.getOrDefault(false)

    /**
     * [FEAT-08] Wraps a backup with a password: AES-256-GCM, key from PBKDF2 (see [BackupCrypto]).
     * Slow on purpose (about a second), so call it off the main thread.
     */
    fun seal(json: String, password: CharArray, iterations: Int = BackupCrypto.ITERATIONS): String {
        val sealed = BackupCrypto.seal(json.toByteArray(), password, iterations)
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("app", APP_ID).put("format", SEALED_FORMAT).put("v", 1)
            .put("salt", b64.encodeToString(sealed.salt)).put("iv", b64.encodeToString(sealed.iv))
            .put("iter", sealed.iterations).put("data", b64.encodeToString(sealed.data))
            .toString()
    }

    /** The backup inside a sealed file; throws [BackupException] for a wrong password or a broken file. */
    fun unseal(text: String, password: CharArray): String {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw BackupException("백업 파일을 읽을 수 없어요")
        if (o.optInt("v") > 1) throw BackupException("더 새로운 버전에서 만든 백업이에요. 앱을 업데이트해 주세요")
        val sealed = runCatching {
            val b64 = Base64.getDecoder()
            BackupCrypto.Sealed(b64.decode(o.getString("salt")), b64.decode(o.getString("iv")), o.getInt("iter"), b64.decode(o.getString("data")))
        }.getOrNull() ?: throw BackupException("백업 파일이 손상됐어요")
        if (sealed.iterations !in 1..MAX_ITERATIONS) throw BackupException("백업 파일이 손상됐어요")
        return try {
            BackupCrypto.open(sealed, password).decodeToString()
        } catch (e: BackupCrypto.WrongPassword) {
            throw BackupException("암호가 맞지 않아요")
        }
    }

    private fun parse(json: String): JSONObject {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw BackupException("백업 파일을 읽을 수 없어요")
        if (root.optString("format") == SEALED_FORMAT) throw BackupException("암호가 걸린 백업이에요")
        if (root.optString("app") !in ACCEPTED_APP_IDS) throw BackupException("밤낮 백업 파일이 아니에요")
        if (root.optInt("format") > FORMAT_VERSION) throw BackupException("더 새로운 버전에서 만든 백업이에요. 앱을 업데이트해 주세요")
        return root
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    companion object {
        const val APP_ID = "com.bamnat.app"
        /** Backups made before the rename (시프트메이트) restore too [BRAND-03]. */
        val ACCEPTED_APP_IDS = setOf(APP_ID, "com.shiftmate.calendar")
        /** 2: extra work as its own list [EXTRA-01]. 1.8 and earlier refuse it rather than drop it. */
        const val FORMAT_VERSION = 2
        const val MIME_TYPE = "application/json"
        const val SEALED_FORMAT = "bamnat-enc"
        /** A file asking for more work than this is not ours (and would freeze the phone). */
        private const val MAX_ITERATIONS = 5_000_000
    }
}
