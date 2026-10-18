package com.bamnat.app.data

import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.ShiftRole
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * [SETUP-01] The first setup in progress, kept as a small file outside backups so the user can stop
 * at any step and go on later. Each answer is written whole (a new file renamed over the old), so a
 * closed app never leaves half an answer. Answers show at once; the file follows on its own thread,
 * always with the latest answer.
 */
class SetupDraftStore(private val file: File) {
    private val _draft = MutableStateFlow(read())
    val draft: StateFlow<SetupDraft?> = _draft.asStateFlow()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "setup-draft").apply { isDaemon = true } }

    fun save(draft: SetupDraft) {
        _draft.value = draft
        writer.execute(::write)
    }

    fun clear() {
        _draft.value = null
        writer.execute(::write)
    }

    /** Waits until the file holds what [draft] shows, e.g. before the save that must survive a crash. */
    fun flush() {
        writer.submit(::write).get()
    }

    private fun write() {
        val d = _draft.value
        runCatching {
            if (d == null) {
                file.delete()
                return@runCatching
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(SetupDraftCodec.encode(d).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun read(): SetupDraft? =
        runCatching { if (file.exists()) SetupDraftCodec.decode(JSONObject(file.readText())) else null }.getOrNull()
}

/** [SETUP-01] The draft as JSON. Anything unreadable is dropped and asked again rather than guessed. */
object SetupDraftCodec {
    fun encode(d: SetupDraft): JSONObject = JSONObject()
        .putOpt("style", d.style?.name).put("roster", d.rosterMonthly).put("step", d.step.name)
        .putOpt("bundle", d.bundle).put("company", d.companyName)
        .put("types", JSONArray(d.types.map(::typeJson)))
        .put("roles", JSONObject().apply { d.roles.forEach { (id, r) -> put(id.toString(), r.name) } })
        .put("timesOk", d.timesConfirmed)
        .put("rotation", ids(d.rotation)).put("weekly", ids(d.weekly)).put("noFixedDays", d.noFixedDays)
        .put("painted", JSONObject().apply { d.painted.forEach { (date, id) -> put(date.toString(), id ?: JSONObject.NULL) } })
        .putOpt("anchor", d.anchor?.toString()).put("todayOk", d.todayConfirmed)
        .put("alarm", d.alarmMinutes ?: -1)
        .put("alarmByType", JSONObject().apply { d.alarmByType.forEach { (id, m) -> put(id.toString(), m ?: -1) } })
        .putOpt("source", d.source?.let(::sourceJson))
        .put("later", d.later).put("committing", d.committing)

    fun decode(o: JSONObject): SetupDraft {
        val d = SetupDraft()
        return SetupDraft(
            style = o.optString("style").let { n -> WorkStyle.entries.firstOrNull { it.name == n } },
            rosterMonthly = o.optBoolean("roster"),
            step = o.optString("step").let { n -> SetupStep.entries.firstOrNull { it.name == n } } ?: SetupStep.STYLE,
            bundle = o.optStringOrNull("bundle"),
            companyName = o.optString("company"),
            types = o.optJSONArray("types")?.let { a -> (0 until a.length()).map { type(a.getJSONObject(it)) } }.orEmpty(),
            roles = o.optJSONObject("roles")?.let { r ->
                r.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> ShiftRole.entries.firstOrNull { it.name == r.optString(k) }?.let { id to it } } }.toMap()
            }.orEmpty(),
            timesConfirmed = o.optBoolean("timesOk"),
            rotation = ids(o.optJSONArray("rotation")),
            weekly = ids(o.optJSONArray("weekly")).takeIf { it.size == 7 } ?: d.weekly,
            noFixedDays = o.optBoolean("noFixedDays"),
            painted = o.optJSONObject("painted")?.let { p ->
                p.keys().asSequence().mapNotNull { k ->
                    runCatching { LocalDate.parse(k) }.getOrNull()?.let { date -> date to (if (p.isNull(k)) null else p.getLong(k)) }
                }.toMap()
            }.orEmpty(),
            anchor = o.optStringOrNull("anchor")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            todayConfirmed = o.optBoolean("todayOk"),
            alarmMinutes = o.optInt("alarm", SetupDraft.DEFAULT_ALARM_MINUTES).takeIf { it >= 0 },
            alarmByType = o.optJSONObject("alarmByType")?.let { a ->
                a.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to a.getInt(k).takeIf { m -> m >= 0 } } }.toMap()
            }.orEmpty(),
            source = o.optJSONObject("source")?.let(::source),
            later = o.optBoolean("later"),
            committing = o.optBoolean("committing"),
        )
    }

    private fun ids(list: List<Long?>) = JSONArray(list.map { it ?: JSONObject.NULL })

    private fun ids(a: JSONArray?): List<Long?> =
        if (a == null) emptyList() else (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) }

    private fun typeJson(t: ShiftType): JSONObject = JSONObject()
        .put("id", t.id).put("name", t.name).put("label", t.label).put("color", t.colorArgb)
        .putOpt("start", t.start?.toString()).putOpt("end", t.end?.toString())
        .put("break", t.breakMinutes).putOpt("breakStart", t.breakStart?.toString())
        .put("off", t.isOff).putOpt("icon", t.icon).put("order", t.sortOrder)

    private fun type(o: JSONObject): ShiftType = ShiftType(
        id = o.getLong("id"),
        name = o.getString("name"),
        label = o.optString("label"),
        colorArgb = o.optLong("color", 0xFF5C6BC0),
        start = o.optTime("start"), end = o.optTime("end"),
        breakMinutes = o.optInt("break"), breakStart = o.optTime("breakStart"),
        isOff = o.optBoolean("off"),
        icon = o.optStringOrNull("icon"),
        sortOrder = o.optInt("order"),
    )

    private fun sourceJson(s: SetupSource): JSONObject = JSONObject()
        .put("kind", s.kind.name).put("text", s.text).putOpt("id", s.id).putOpt("setupId", s.setupId)
        .put("name", s.name).put("dept", s.dept).putOpt("version", s.version).put("adopted", s.adopted)
        .putOpt("team", s.team).putOpt("anchor", s.anchor?.toString())

    private fun source(o: JSONObject): SetupSource? {
        val kind = SetupSource.Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
        return SetupSource(
            kind = kind, text = o.optString("text"), id = o.optStringOrNull("id"), setupId = o.optStringOrNull("setupId"),
            name = o.optString("name"), dept = o.optString("dept"),
            version = if (o.has("version") && !o.isNull("version")) o.getInt("version") else null,
            adopted = o.optBoolean("adopted"), team = o.optStringOrNull("team"),
            anchor = o.optStringOrNull("anchor")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optTime(key: String): LocalTime? =
        optStringOrNull(key)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
}
