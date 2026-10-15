package com.bamnat.app.data

import androidx.room.withTransaction
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.DayEntryEntity
import com.bamnat.app.data.db.MemoEntity
import com.bamnat.app.data.db.PatternEntity
import com.bamnat.app.data.db.ShiftTypeEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** What a backup file holds, shown before the user confirms a restore. */
data class BackupSummary(val shiftTypes: Int, val days: Int, val memos: Int, val exportedAtMillis: Long)

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
) {
    suspend fun export(): String {
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
                    .putOpt("break", d.customBreakMinutes).put("overtime", d.overtimeMinutes)
                    .put("holiday", d.holidayWorkMinutes).put("skip", d.alarmSkipped)
                    .putOpt("alarms", d.customAlarms).put("extra", d.extraWork).put("leave", d.leave).putOpt("second", d.secondShiftTypeId),
            )
        }
        root.put("days", days)
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
        val s = settings.current
        root.put(
            "settings",
            JSONObject()
                .put("weekStart", s.weekStart.name).put("weeklyLimit", s.weeklyLimitHours)
                .put("averageLimit", s.averageLimitHours).put("theme", s.themeMode.name)
                .put("dynamicColor", s.dynamicColor).put("cellHours", s.showHoursInCells).put("lunar", s.showLunar)
                .putOpt("sound", s.defaultAlarmSound).put("vibrate", s.vibrate).put("gradual", s.gradualVolume)
                .put("snooze", s.snoozeMinutes).put("ring", s.ringMinutes).put("alarmsOn", s.alarmsEnabled),
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
            exportedAtMillis = root.optLong("exportedAt"),
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
                customBreakMinutes = o.optIntOrNull("break"), overtimeMinutes = o.optInt("overtime"),
                holidayWorkMinutes = o.optInt("holiday"), alarmSkipped = o.optBoolean("skip"),
                customAlarms = o.optStringOrNull("alarms"), extraWork = o.optInt("extra"), leave = o.optInt("leave"),
                secondShiftTypeId = if (o.has("second") && !o.isNull("second")) o.getLong("second") else null,
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
        db.withTransaction {
            db.memoDao().clear()
            db.dayEntryDao().clear()
            db.patternDao().clear()
            db.shiftTypeDao().clear()
            db.shiftTypeDao().insertAll(types)
            db.patternDao().saveAll(patterns)
            db.dayEntryDao().insertAll(days)
            memos.forEach { db.memoDao().restore(it) }
        }
        root.optJSONObject("settings")?.let { o ->
            settings.update { s ->
                s.copy(
                    weekStart = runCatching { DayOfWeek.valueOf(o.getString("weekStart")) }.getOrDefault(s.weekStart),
                    weeklyLimitHours = o.optInt("weeklyLimit", s.weeklyLimitHours),
                    averageLimitHours = o.optInt("averageLimit", s.averageLimitHours),
                    themeMode = runCatching { ThemeMode.valueOf(o.getString("theme")) }.getOrDefault(s.themeMode),
                    dynamicColor = o.optBoolean("dynamicColor", s.dynamicColor),
                    showHoursInCells = o.optBoolean("cellHours", s.showHoursInCells),
                    showLunar = o.optBoolean("lunar", s.showLunar),
                    // Sound files from another phone usually do not exist here.
                    vibrate = o.optBoolean("vibrate", s.vibrate),
                    gradualVolume = o.optBoolean("gradual", s.gradualVolume),
                    snoozeMinutes = o.optInt("snooze", s.snoozeMinutes),
                    ringMinutes = o.optInt("ring", s.ringMinutes),
                    alarmsEnabled = o.optBoolean("alarmsOn", s.alarmsEnabled),
                )
            }
        }
    }

    private fun parse(json: String): JSONObject {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw BackupException("백업 파일을 읽을 수 없어요")
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
        const val FORMAT_VERSION = 1
        const val MIME_TYPE = "application/json"
    }
}
