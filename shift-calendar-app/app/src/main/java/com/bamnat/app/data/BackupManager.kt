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
import com.bamnat.app.data.db.MoneyAccountEntity
import com.bamnat.app.data.db.MoneyBudgetEntity
import com.bamnat.app.data.db.MoneyCategoryEntity
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
data class BackupSummary(
    val shiftTypes: Int, val days: Int, val memos: Int, val exportedAtMillis: Long, val moneyLines: Int = 0, val extras: Int = 0,
    /** [SET-01] The rest of what a restore replaces, said before it does. */
    val sleeps: Int = 0, val hasPay: Boolean = false,
    /** [MCAL-05] Added calendars besides the main one. */
    val calendars: Int = 0,
)

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
    /** [MCAL-05] The added calendars belong in the backup too. */
    private val calendars: CalendarBook? = null,
) {
    /** The schedule tables of [source] (shifts, order, changed days, extra work) as a backup holds them. */
    private suspend fun putSchedule(source: AppDatabase, into: JSONObject) {
        val types = JSONArray()
        source.shiftTypeDao().getAll().forEach { t ->
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
        into.put("shiftTypes", types)
        val patterns = JSONArray()
        source.patternDao().getAll().forEach { p ->
            patterns.put(
                JSONObject().put("kind", p.kind).put("start", p.startEpochDay).putOpt("end", p.endEpochDay)
                    .put("steps", p.steps).putOpt("anchor", p.anchorEpochDay),
            )
        }
        into.put("patterns", patterns)
        val days = JSONArray()
        source.dayEntryDao().getAll().forEach { d ->
            days.put(
                JSONObject()
                    .put("day", d.epochDay).put("mode", d.overrideMode).putOpt("type", d.overrideShiftTypeId)
                    .putOpt("start", d.customStartMinute).putOpt("end", d.customEndMinute)
                    .putOpt("break", d.customBreakMinutes).put("skip", d.alarmSkipped)
                    .putOpt("alarms", d.customAlarms).put("leave", d.leave),
            )
        }
        into.put("days", days)
        // [EXTRA-01] Extra work by date (format 2).
        into.put("extras", JSONArray().apply {
            source.dayExtraDao().getAll().forEach { x ->
                put(JSONObject().put("day", x.epochDay).put("kind", x.kindId).putOpt("start", x.startMinute).putOpt("end", x.endMinute)
                    .put("break", x.breakMinutes).putOpt("breakStart", x.breakStartMinute).put("minutes", x.minutes)
                    .putOpt("covered", x.coveredShiftTypeId).put("review", x.needsReview).put("pos", x.position))
            }
        })
    }

    /** One read transaction, so an edit made meanwhile cannot leave days pointing at a missing type [QA-01]. */
    /** [MCAL-06] A calendar's ledger, jobs, sales, fixed costs, learned categories and money owed. */
    private suspend fun putMoney(source: AppDatabase, into: JSONObject) {
        // [WORK-06] Ledger, jobs and sales (version 7). Older apps ignore these keys.
        val money = source.moneyDao()
        into.put("money", JSONArray().apply {
            money.allEntries().forEach { e ->
                put(JSONObject().put("id", e.id).put("day", e.epochDay).put("kind", e.kind).put("amount", e.amount)
                    .put("category", e.category).put("note", e.note).put("source", e.source).put("created", e.createdAt)
                    .put("merchant", e.merchant).putOpt("minute", e.minuteOfDay).put("card", e.cardLast4).put("reviewed", e.reviewed)
                    // [LEDG-08][LEDG-09][LEDG-11] Version 15; only what is set, so a plain line stays short.
                    .putOpt("account", e.accountId).putOpt("toAccount", e.toAccountId).putOpt("fixedId", e.fixedId)
                    .putOpt("memo", e.memo.ifEmpty { null }).putOpt("tags", e.tags.ifEmpty { null })
                    .putOpt("parts", e.parts.ifEmpty { null }).putOpt("installments", e.installments.takeIf { it > 1 }))
            }
        })
        into.put("jobs", JSONArray().apply {
            money.allJobs().forEach { j ->
                put(JSONObject().put("id", j.id).put("day", j.epochDay).put("title", j.title).put("client", j.client)
                    .put("place", j.place).putOpt("start", j.startMinute).putOpt("end", j.endMinute).put("amount", j.amount)
                    .put("deposit", j.deposit).put("depositPaid", j.depositPaid).put("balancePaid", j.balancePaid)
                    .putOpt("remind", j.remindMinutes).put("note", j.note)
                    .putOpt("endDay", j.endEpochDay).put("costs", j.costs).put("withholding", j.withholding))
            }
        })
        into.put("sales", JSONArray().apply {
            money.allSales().forEach { d -> put(JSONObject().put("day", d.epochDay).put("sales", d.sales).put("costs", d.costs).put("note", d.note)) }
        })
        // [LEDG-05][LEDG-04] Fixed costs and learned categories (version 9). Unread notification texts are never backed up.
        into.put("fixed", JSONArray().apply {
            money.allFixed().forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("amount", f.amount).put("day", f.day).put("kind", f.kind)
                    .put("category", f.category).put("remind", f.remind)
                    // [LEDG-09] Version 15.
                    .put("repeat", f.repeat).put("monthOfYear", f.monthOfYear).putOpt("account", f.accountId).put("auto", f.auto)
                    .putOpt("start", f.startEpochDay).putOpt("end", f.endEpochDay))
            }
        })
        // [LEDG-08][LEDG-06][LEDG-14] Accounts, budgets and the user's categories (version 15). Older apps ignore these keys.
        into.put("accounts", JSONArray().apply {
            money.allAccounts().forEach { a ->
                put(JSONObject().put("id", a.id).put("name", a.name).put("type", a.type).put("last4", a.last4)
                    .putOpt("payDay", a.payDay).putOpt("closeDay", a.closeDay).putOpt("payFrom", a.payFromId)
                    .put("opening", a.opening).put("color", a.color).put("order", a.sortOrder).put("hidden", a.hidden).put("remind", a.remind))
            }
        })
        into.put("budgets", JSONArray().apply {
            money.allBudgets().forEach { b -> put(JSONObject().put("category", b.category).put("amount", b.amount)) }
        })
        into.put("categories", JSONArray().apply {
            money.allCategories().forEach { c ->
                put(JSONObject().put("kind", c.kind).put("name", c.name).put("position", c.position).put("hidden", c.hidden))
            }
        })
        into.put("rules", JSONArray().apply {
            money.allRules().forEach { r -> put(JSONObject().put("merchant", r.merchant).put("category", r.category)) }
        })
        // [RECV-01] Money others owe, with what came back (version 13). Older apps ignore these keys.
        val owed = source.receivableDao()
        into.put("owed", JSONArray().apply {
            owed.all().forEach { r ->
                put(JSONObject().put("id", r.id).put("who", r.who).put("title", r.title).put("amount", r.amount).put("day", r.epochDay)
                    .putOpt("due", r.dueEpochDay).put("kind", r.kind).put("note", r.note).put("income", r.countAsIncome)
                    .putOpt("closed", r.closedEpochDay).put("created", r.createdAt))
            }
        })
        into.put("owedPaid", JSONArray().apply {
            owed.allPayments().forEach { p ->
                put(JSONObject().put("id", p.id).put("owed", p.receivableId).put("day", p.epochDay).put("amount", p.amount).putOpt("entry", p.entryId))
            }
        })
    }

    /** [MCAL-06] A calendar's pay setup and closed months. */
    private fun putPay(store: com.bamnat.app.pay.PayStore, into: JSONObject) {
        store.profile.value?.let { into.put("pay", com.bamnat.app.pay.PayCodec.encode(it)) }
        into.put("payClosed", JSONArray(store.closed.value.values.map(com.bamnat.app.pay.SnapshotCodec::encode)))
    }

    suspend fun export(): String = db.withTransaction { exportNow() }

    private suspend fun exportNow(): String {
        val root = JSONObject()
            .put("app", APP_ID)
            .put("format", FORMAT_VERSION)
            .put("exportedAt", clock())
        putSchedule(db, root)
        // [MCAL-05] Each added calendar with its own shifts, order and days, and the list's order.
        calendars?.let { book ->
            root.put("calendars", JSONArray().apply {
                book.added().forEach { cal ->
                    val o = CalendarBook.run { cal.toJson() }
                    book.database(cal.id).let { other -> other.withTransaction { putSchedule(other, o); putMoney(other, o) } }
                    putPay(book.pay(cal.id), o)
                    put(o)
                }
            })
            root.put("calendarOrder", JSONArray(book.order.value))
            book.get(PersonalCalendar.MAIN)?.let { root.put("mainCalendar", CalendarBook.run { it.toJson() }) }
        }
        val memos = JSONArray()
        db.memoDao().getAll().forEach { m ->
            memos.put(
                JSONObject()
                    .put("id", m.id).putOpt("day", m.epochDay).put("title", m.title).put("body", m.body)
                    .put("checklist", m.checklist).put("isChecklist", m.isChecklist).put("color", m.colorIndex)
                    .put("pinned", m.pinned).put("created", m.createdAt).put("updated", m.updatedAt)
                    .putOpt("deleted", m.deletedAt)
                    // [SCHED-01] Version 13. Older apps ignore these keys and keep the memo plain.
                    .putOpt("remindAt", m.remindAt).put("remindRing", m.remindRing).put("remindNotify", m.remindNotify)
                    .putOpt("remindAsked", m.remindAsked)
                    // [NOTE-01] Version 16. Without it a memo shows on every calendar, as before.
                    .putOpt("calendars", m.calendars.ifEmpty { null }),
            )
        }
        root.put("memos", memos)
        putMoney(db, root)
        // [SLP-06] The sleep log goes with the rest of the user's data.
        root.put("sleep", JSONArray().apply {
            db.sleepDao().all().forEach { l -> put(JSONObject().put("start", l.startMillis).putOpt("end", l.endMillis)) }
        })
        payStore?.let { putPay(it, root) }
        val s = settings.current
        root.put(
            "settings",
            JSONObject()
                .put("weekStart", s.weekStart.name).put("weeklyLimit", s.weeklyLimitHours)
                .put("averageLimit", s.averageLimitHours).put("theme", s.themeMode.name)
                .put("dynamicColor", s.dynamicColor).put("cellHours", s.showHoursInCells).put("lunar", s.showLunar)
                .putOpt("sound", s.defaultAlarmSound).put("vibrate", s.vibrate).put("gradual", s.gradualVolume)
                .put("snooze", s.snoozeMinutes).put("ring", s.ringMinutes).put("alarmsOn", s.alarmsEnabled)
                .putOpt("workStyle", s.workStyle?.name).put("rosterMonthly", s.rosterMonthly)
                .put("extraKinds", s.extraKinds.encode()).put("memoFirst", s.memoInCells).put("calendarMemos", s.calendarMemos)
                .put("phoneDayView", s.phoneDayView.name).put("dayGroupOrder", s.dayGroupOrder.joinToString(","))
                .put("restCheck", s.restCheck)
                .put("strictRest", s.strictRest).put("sleepTips", s.sleepTips)
                // 1.10 and before read one switch for all sleep notices.
                .put("sleepReminders", s.briefing || s.commuteNotice)
                .put("briefing", s.briefing).put("commuteNotice", s.commuteNotice).put("bedtimeNotice", s.bedtimeNotice)
                .put("todayShiftNotice", s.todayShiftNotice)
                .put("sleepingNotice", s.sleepingNotice).put("quietWhileAsleep", s.quietWhileAsleep)
                .put("moneyNotices", s.moneyNotices).put("leaveNotice", s.leaveNotice)
                .put("sleepTarget", s.sleepTargetMinutes).put("commute", s.commuteMinutes),
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
                (root.optJSONArray("sales")?.length() ?: 0) + (root.optJSONArray("fixed")?.length() ?: 0) +
                (root.optJSONArray("owed")?.length() ?: 0),
            sleeps = root.optJSONArray("sleep")?.length() ?: 0,
            hasPay = root.has("pay"),
            calendars = root.optJSONArray("calendars")?.length() ?: 0,
        )
    }

    /** [MCAL-05] One calendar's schedule as a backup holds it; throws for one with no shifts. */
    private class ScheduleRows(
        val types: List<ShiftTypeEntity>, val patterns: List<PatternEntity>, val days: List<DayEntryEntity>, val extras: List<DayExtraEntity>?,
    )

    private fun readSchedule(from: JSONObject): ScheduleRows {
        val types = from.getJSONArray("shiftTypes").objects().map { o ->
            ShiftTypeEntity(
                id = o.getLong("id"), name = o.getString("name"), label = o.getString("label"),
                colorArgb = o.getLong("color"), startMinute = o.optIntOrNull("start"), endMinute = o.optIntOrNull("end"),
                breakMinutes = o.optInt("break"), isOff = o.optBoolean("off"), alarmEnabled = o.optBoolean("alarm"),
                alarmOffsetMinutes = o.optInt("alarmOffset", 60), alarmSoundUri = o.optStringOrNull("sound"),
                sortOrder = o.optInt("order"), alarmOffsets = o.optString("alarms", ""), icon = o.optStringOrNull("icon"),
                breakStartMinute = o.optIntOrNull("breakStart"),
            )
        }
        if (types.isEmpty()) throw BackupException("근무 종류가 없는 백업 파일이에요")
        // Older backups held one "pattern" object instead of the "patterns" history.
        val patternObjects = from.optJSONArray("patterns")?.objects()
            ?: listOfNotNull(from.optJSONObject("pattern"))
        val patterns = patternObjects.mapIndexed { i, p ->
            PatternEntity(
                id = i + 1, kind = p.getString("kind"), startEpochDay = p.getLong("start"),
                endEpochDay = if (p.isNull("end")) null else p.optLong("end"), steps = p.getString("steps"),
                anchorEpochDay = if (p.isNull("anchor") || !p.has("anchor")) null else p.getLong("anchor"),
            )
        }
        val days = from.optJSONArray("days").objects().map { o ->
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
        val extras = from.optJSONArray("extras")?.objects()?.map { o ->
            DayExtraEntity(
                epochDay = o.getLong("day"), kindId = o.getLong("kind"), startMinute = o.optIntOrNull("start"),
                endMinute = o.optIntOrNull("end"), breakMinutes = o.optInt("break").coerceIn(0, 24 * 60),
                breakStartMinute = o.optIntOrNull("breakStart"), minutes = o.optInt("minutes").coerceIn(0, 24 * 60),
                coveredShiftTypeId = if (o.has("covered") && !o.isNull("covered")) o.getLong("covered") else null,
                needsReview = o.optBoolean("review"), position = o.optInt("pos"),
            )
        }
        return ScheduleRows(types, patterns, days, extras)
    }

    /** Replaces [target]'s schedule with [rows]; the change log described the days being replaced [MKT-08]. */
    private suspend fun writeSchedule(target: AppDatabase, rows: ScheduleRows) {
        target.dayChangeDao().clear()
        target.dayEntryDao().clear()
        target.dayExtraDao().clear()
        target.patternDao().clear()
        target.shiftTypeDao().clear()
        target.shiftTypeDao().insertAll(rows.types)
        target.patternDao().saveAll(rows.patterns)
        target.dayEntryDao().insertAll(rows.days)
        if (rows.extras != null) target.dayExtraDao().insertAll(rows.extras)
        // [EXTRA-05] A backup from 1.8 or earlier: the same move as the database upgrade.
        else com.bamnat.app.data.db.AppDatabase.moveOldExtraWork(target.openHelper.writableDatabase)
    }

    /** Replaces all data with the backup, in one transaction: a broken file changes nothing. */
    /** [MCAL-06] A calendar's money as a backup has it; null parts are from a backup made before they existed. */
    /** [MCAL-05][MCAL-06] An added calendar as a backup has it. */
    private class AddedRows(
        val cal: PersonalCalendar, val schedule: ScheduleRows, val money: MoneyRows,
        val pay: com.bamnat.core.pay.PayProfile?, val payClosed: List<com.bamnat.core.pay.CalcSnapshot>?,
    )

    private class MoneyRows(
        val entries: List<MoneyEntryEntity>?, val jobs: List<JobEntity>?, val sales: List<DailySalesEntity>?,
        val fixed: List<FixedCostEntity>?, val rules: List<CategoryRuleEntity>?,
        val accounts: List<MoneyAccountEntity>?, val budgets: List<MoneyBudgetEntity>?, val categories: List<MoneyCategoryEntity>?,
        val owed: List<com.bamnat.app.data.db.ReceivableEntity>?, val owedPaid: List<com.bamnat.app.data.db.ReceivablePaymentEntity>?,
    )

    private fun readMoney(from: JSONObject): MoneyRows {
        val entries = from.optJSONArray("money")?.objects()?.map { o ->
            MoneyEntryEntity(
                id = o.getLong("id"), epochDay = o.getLong("day"), kind = o.getString("kind"), amount = o.getLong("amount"),
                category = o.optString("category"), note = o.optString("note"), source = o.optString("source", "MANUAL"),
                createdAt = o.optLong("created"), merchant = o.optString("merchant"), minuteOfDay = o.optIntOrNull("minute"),
                cardLast4 = o.optString("card"), reviewed = o.optBoolean("reviewed", true),
                accountId = o.optLongOrNull("account"), toAccountId = o.optLongOrNull("toAccount"), memo = o.optString("memo"),
                tags = o.optString("tags"), parts = o.optString("parts"), installments = o.optInt("installments", 1).coerceIn(1, 60),
                fixedId = o.optLongOrNull("fixedId"),
            )
        }
        val fixed = from.optJSONArray("fixed")?.objects()?.map { o ->
            FixedCostEntity(
                id = o.getLong("id"), name = o.optString("name"), amount = o.optLong("amount"), day = o.optInt("day", 1),
                kind = o.optString("kind", "EXPENSE"), category = o.optString("category"), remind = o.optBoolean("remind", true),
                repeat = o.optString("repeat", "MONTH"), monthOfYear = o.optInt("monthOfYear", 1), accountId = o.optLongOrNull("account"),
                auto = o.optBoolean("auto"), startEpochDay = o.optLongOrNull("start"), endEpochDay = o.optLongOrNull("end"),
            )
        }
        val accounts = from.optJSONArray("accounts")?.objects()?.map { o ->
            MoneyAccountEntity(
                id = o.getLong("id"), name = o.optString("name"), type = o.optString("type", "OTHER"), last4 = o.optString("last4"),
                payDay = o.optIntOrNull("payDay"), closeDay = o.optIntOrNull("closeDay"), payFromId = o.optLongOrNull("payFrom"),
                opening = o.optLong("opening"), color = o.optInt("color"), sortOrder = o.optInt("order"), hidden = o.optBoolean("hidden"),
                remind = o.optBoolean("remind", true),
            )
        }
        val budgets = from.optJSONArray("budgets")?.objects()?.map { o -> MoneyBudgetEntity(o.optString("category"), o.optLong("amount")) }
        val categories = from.optJSONArray("categories")?.objects()?.map { o ->
            MoneyCategoryEntity(o.optString("kind", "EXPENSE"), o.getString("name"), o.optInt("position"), o.optBoolean("hidden"))
        }
        val rules = from.optJSONArray("rules")?.objects()?.map { o -> CategoryRuleEntity(o.getString("merchant"), o.optString("category")) }
        val jobs = from.optJSONArray("jobs")?.objects()?.map { o ->
            JobEntity(
                id = o.getLong("id"), epochDay = o.getLong("day"), title = o.optString("title"), client = o.optString("client"),
                place = o.optString("place"), startMinute = o.optIntOrNull("start"), endMinute = o.optIntOrNull("end"),
                amount = o.optLong("amount"), deposit = o.optLong("deposit"), depositPaid = o.optBoolean("depositPaid"),
                balancePaid = o.optBoolean("balancePaid"), remindMinutes = o.optIntOrNull("remind"), note = o.optString("note"),
                endEpochDay = if (o.has("endDay") && !o.isNull("endDay")) o.getLong("endDay") else null,
                costs = o.optLong("costs"), withholding = o.optBoolean("withholding"),
            )
        }
        val owed = from.optJSONArray("owed")?.objects()?.map { o ->
            com.bamnat.app.data.db.ReceivableEntity(
                id = o.getLong("id"), who = o.optString("who"), title = o.optString("title"), amount = o.optLong("amount"),
                epochDay = o.getLong("day"), dueEpochDay = if (o.has("due") && !o.isNull("due")) o.getLong("due") else null,
                kind = o.optString("kind", "OTHER"), note = o.optString("note"), countAsIncome = o.optBoolean("income"),
                closedEpochDay = if (o.has("closed") && !o.isNull("closed")) o.getLong("closed") else null, createdAt = o.optLong("created"),
            )
        }
        val owedPaid = from.optJSONArray("owedPaid")?.objects()?.map { o ->
            com.bamnat.app.data.db.ReceivablePaymentEntity(o.getLong("id"), o.getLong("owed"), o.getLong("day"), o.optLong("amount"),
                if (o.has("entry") && !o.isNull("entry")) o.getLong("entry") else null)
        }
        val sales = from.optJSONArray("sales")?.objects()?.map { o ->
            DailySalesEntity(o.getLong("day"), o.optLong("sales"), o.optLong("costs"), o.optString("note"))
        }
        return MoneyRows(entries, jobs, sales, fixed, rules, accounts, budgets, categories, owed, owedPaid)
    }

    /** [MCAL-06] Replaces a calendar's money with a backup's, part by part; call inside its transaction. */
    private suspend fun writeMoney(target: AppDatabase, rows: MoneyRows) {
        // Backups from before version 7 carry no money; what is on the phone then stays.
        val money = target.moneyDao()
        rows.entries?.let { list -> money.clearEntries(); list.forEach { money.restoreEntry(it) } }
        rows.jobs?.let { list -> money.clearJobs(); list.forEach { money.restoreJob(it) } }
        rows.sales?.let { list -> money.clearSales(); list.forEach { money.upsertSales(it) } }
        rows.fixed?.let { list -> money.clearFixed(); list.forEach { money.restoreFixed(it) } }
        rows.rules?.let { list -> money.clearRules(); list.forEach { money.upsertRule(it) } }
        // Backups from before version 15 carry no accounts, budgets or categories; the ones on the phone then stay.
        rows.accounts?.let { list -> money.clearAccounts(); list.forEach { money.restoreAccount(it) } }
        rows.budgets?.let { list -> money.clearBudgets(); list.forEach { money.upsertBudget(it) } }
        rows.categories?.let { list -> money.clearAllCategories(); money.insertCategories(list) }
        // Backups from before version 13 carry no receivables; the ones on the phone then stay.
        rows.owed?.let { list ->
            val dao = target.receivableDao()
            dao.clearPayments(); dao.clear()
            list.forEach { dao.restore(it) }
            rows.owedPaid?.forEach { dao.restorePayment(it) }
        }
    }

    suspend fun restore(json: String) {
        val root = parse(json)
        val schedule = readSchedule(root)
        // [MCAL-05] Added calendars come back only from a backup that has them; read before anything changes.
        val added = if (calendars != null && root.has("calendars")) root.optJSONArray("calendars").objects().mapNotNull { o ->
            CalendarBook.fromJson(o)?.takeIf { !it.isMain }?.let { cal ->
                AddedRows(cal, readSchedule(o), readMoney(o), o.optJSONObject("pay")?.let(com.bamnat.app.pay.PayCodec::decode),
                    o.optJSONArray("payClosed")?.objects()?.map(com.bamnat.app.pay.SnapshotCodec::decode))
            }
        }.distinctBy { it.cal.id } else null
        val addedOrder = root.optJSONArray("calendarOrder")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val mainCalendar = root.optJSONObject("mainCalendar")?.let(CalendarBook::fromJson)
        val memos = root.optJSONArray("memos").objects().map { o ->
            MemoEntity(
                id = o.getLong("id"), epochDay = if (o.isNull("day") || !o.has("day")) null else o.getLong("day"),
                title = o.optString("title"), body = o.optString("body"), checklist = o.optString("checklist"),
                isChecklist = o.optBoolean("isChecklist"), colorIndex = o.optInt("color"), pinned = o.optBoolean("pinned"),
                createdAt = o.optLong("created"), updatedAt = o.optLong("updated"),
                deletedAt = if (o.isNull("deleted") || !o.has("deleted")) null else o.getLong("deleted"),
                remindAt = o.optStringOrNull("remindAt"), remindRing = o.optBoolean("remindRing"),
                remindNotify = o.optBoolean("remindNotify"), remindAsked = o.optStringOrNull("remindAsked"),
                calendars = com.bamnat.core.memo.MemoCalendars.encode(com.bamnat.core.memo.MemoCalendars.decode(o.optStringOrNull("calendars"))),
            )
        }
        val sleep = root.optJSONArray("sleep")?.objects()?.map { o ->
            com.bamnat.app.data.db.SleepLogEntity(startMillis = o.getLong("start"), endMillis = if (o.has("end") && !o.isNull("end")) o.getLong("end") else null)
        }
        val money = readMoney(root)
        // Read before anything changes, so a broken pay part leaves the phone as it was.
        val pay = root.optJSONObject("pay")?.let(com.bamnat.app.pay.PayCodec::decode)
        val payClosed = root.optJSONArray("payClosed")?.objects()?.map(com.bamnat.app.pay.SnapshotCodec::decode)
        db.withTransaction {
            writeMoney(db, money)
            // Backups from before version 11 carry no sleep log; the one on the phone then stays.
            sleep?.let { list -> db.sleepDao().clear(); list.forEach { db.sleepDao().insert(it) } }
            db.memoDao().clear()
            writeSchedule(db, schedule)
            memos.forEach { db.memoDao().restore(it) }
        }
        // [MCAL-05] The added calendars as the backup had them, each in its own database.
        calendars?.let { book ->
            if (added != null) {
                book.replaceAdded(listOfNotNull(mainCalendar) + added.map { it.cal }, addedOrder)
                for (a in added) {
                    book.database(a.cal.id).let { other -> other.withTransaction { writeSchedule(other, a.schedule); writeMoney(other, a.money) } }
                    // [MCAL-06] Its pay setup points at its own shift ids, restored just above.
                    val store = book.pay(a.cal.id)
                    store.save(a.pay, keepUndo = false)
                    store.replaceClosed(a.payClosed.orEmpty().associateBy { it.month })
                }
            } else if (mainCalendar != null) {
                book.rename(PersonalCalendar.MAIN, mainCalendar.name)
            }
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
                    // [SETUP-01] A backup from before the question with a pattern in it is shift work, as on the phone.
                    workStyle = o.optStringOrNull("workStyle")?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } } ?: s.workStyle
                        ?: WorkStyle.SHIFT.takeIf { schedule.patterns.isNotEmpty() },
                    rosterMonthly = o.optBoolean("rosterMonthly", s.rosterMonthly),
                    extraKinds = o.optStringOrNull("extraKinds")?.let { com.bamnat.core.model.ExtraKinds.decode(it) } ?: s.extraKinds,
                    memoInCells = o.optBoolean("memoFirst", s.memoInCells),
                    // [CAL-03][DAY-04] Added in 1.13; older backups keep what this phone has.
                    phoneDayView = runCatching { PhoneDayView.valueOf(o.getString("phoneDayView")) }.getOrDefault(s.phoneDayView),
                    dayGroupOrder = o.optStringOrNull("dayGroupOrder")?.let(::decodeGroupOrder)
                        ?: if (o.optBoolean("memoFirst", false)) listOf("memo") else s.dayGroupOrder,
                    calendarMemos = o.optBoolean("calendarMemos", s.calendarMemos),
                    restCheck = o.optBoolean("restCheck", s.restCheck),
                    strictRest = o.optBoolean("strictRest", s.strictRest),
                    sleepTips = o.optBoolean("sleepTips", s.sleepTips),
                    briefing = o.optBoolean("briefing", o.optBoolean("sleepReminders", s.briefing)),
                    commuteNotice = o.optBoolean("commuteNotice", o.optBoolean("sleepReminders", s.commuteNotice)),
                    bedtimeNotice = o.optBoolean("bedtimeNotice", s.bedtimeNotice),
                    todayShiftNotice = o.optBoolean("todayShiftNotice", s.todayShiftNotice),
                    sleepingNotice = o.optBoolean("sleepingNotice", s.sleepingNotice),
                    quietWhileAsleep = o.optBoolean("quietWhileAsleep", s.quietWhileAsleep),
                    moneyNotices = o.optBoolean("moneyNotices", s.moneyNotices),
                    leaveNotice = o.optBoolean("leaveNotice", s.leaveNotice),
                    sleepTargetMinutes = o.optInt("sleepTarget", s.sleepTargetMinutes).coerceIn(4 * 60, 10 * 60),
                    commuteMinutes = o.optInt("commute", s.commuteMinutes).coerceIn(0, MAX_COMMUTE_MINUTES),
                    // Old records came back with the backup; offer to fix them again.
                    swapCoversAnswered = s.swapCoversAnswered && schedule.extras != null,
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
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    companion object {
        const val APP_ID = "com.bamnat.app"
        /** Backups made before the rename (시프트메이트) restore too [BRAND-03]. */
        val ACCEPTED_APP_IDS = setOf(APP_ID, "com.shiftmate.calendar")
        /** 2: extra work as its own list [EXTRA-01]. 1.8 and earlier refuse it rather than drop it. */
        const val FORMAT_VERSION = 2
        const val MIME_TYPE = "application/json"
        const val SEALED_FORMAT = "bamnat-enc"
        /** [SLP-14] The longest way home a backup may set. */
        const val MAX_COMMUTE_MINUTES = 180
        /** A file asking for more work than this is not ours (and would freeze the phone). */
        private const val MAX_ITERATIONS = 5_000_000
    }
}
