package com.bamnat.core.remote

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ShiftType
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.OccupationPreset
import com.bamnat.core.pay.PresetItem
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.PatternPreset
import com.bamnat.core.schedule.ShiftTypeSet
import java.time.LocalTime

/**
 * [REMOTE-03] The presets as data (`remote/v1/presets.json`, 순번 41): rotation orders, ready-made shift type
 * sets and the 직군 pay starting points. The app ships the file and a newer signed copy from the site is laid
 * over it, so a new preset (e.g. 경찰·소방 근무, 묶음 C 271) reaches phones without an app update.
 *
 * `data.patterns[]`: `id`, `name`, `description`, `codes` (D 주간 · E 오후 · N 야간 · X 당번 · B 비번 · O 휴무),
 * optional `jobs` (Occupation names shown first for that 직군) and `source`.
 * `data.shiftSets[]`: `id`, `name`, `description`, `jobs`, `types[]` of `name`, `label`, `color` (#RRGGBB),
 * `start`/`end` (HH:mm), `break` and `alarmBefore` in minutes.
 * `data.pay[]`: `occupation`, `wageType`, `baseHint`, `items[]` (`id`, `name`, `cadence`, `amount`, `ordinary`,
 * `checked`, `hint`), `dailyLimitMinutes` (null = none, default 480), `note`.
 *
 * In a downloaded file an entry with the id (or occupation) of a built-in one replaces it, a new id is added
 * at the end, and `"removed": true` hides one. An entry this app cannot use (a code, cadence or occupation of
 * a newer app) is skipped; a missing required field makes the whole file wrong.
 */
data class PresetData(
    val patterns: List<PatternPreset>,
    val shiftSets: List<ShiftTypeSet>,
    val pay: List<OccupationPreset>,
    /** Ids (and occupation names) a downloaded file hides. */
    val removed: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = PresetData(emptyList(), emptyList(), emptyList())
    }
}

object PresetFile {
    const val CODES = "DENXBO"

    fun read(f: RemoteFile): PresetData {
        if (f.kind != RemoteFiles.PRESETS) throw RemoteFileException("kind ${f.kind}")
        val removed = mutableSetOf<String>()
        fun gone(o: JsonObj, key: String): Boolean = o.boolOr("removed", false).also { if (it) removed += key }
        val patterns = f.data.objs("patterns").mapNotNull { o ->
            val id = o.str("id")
            if (gone(o, id)) return@mapNotNull null
            val codes = o.str("codes")
            if (codes.isEmpty() || codes.any { it !in CODES } || codes.all { it == 'O' || it == 'B' }) return@mapNotNull null
            PatternPreset(o.str("name"), o.strOr("description", ""), codes, id = id, jobs = o.strings("jobs"), source = o.strOr("source", ""))
        }
        val sets = f.data.objs("shiftSets").mapNotNull { o ->
            val id = o.str("id")
            if (gone(o, id)) return@mapNotNull null
            val types = o.objs("types").map { t ->
                ShiftType(
                    0, t.str("name"), t.str("label"), color(t.str("color")), time(t.str("start")), time(t.str("end")),
                    breakMinutes = t.intOr("break", 0), alarmEnabled = true, alarms = listOf(AlarmSpec(t.intOr("alarmBefore", 60))),
                )
            }
            if (types.isEmpty()) null else ShiftTypeSet(o.str("name"), o.strOr("description", ""), types, id = id, jobs = o.strings("jobs"))
        }
        val pay = f.data.objs("pay").mapNotNull { o ->
            val occ = Occupation.entries.firstOrNull { it.name == o.str("occupation") } ?: return@mapNotNull null
            if (gone(o, occ.name)) return@mapNotNull null
            val wage = WageType.entries.firstOrNull { it.name == o.str("wageType") } ?: return@mapNotNull null
            val items = o.objs("items").mapNotNull { i ->
                val cadence = Cadence.entries.firstOrNull { it.name == i.str("cadence") } ?: return@mapNotNull null
                PresetItem(i.str("id"), i.str("name"), cadence, i.long("amount"), i.boolOr("ordinary", false), i.boolOr("checked", true), i.strOr("hint", ""))
            }
            val limit = if (o.map.containsKey("dailyLimitMinutes") && o.map["dailyLimitMinutes"] == null) null else o.intOr("dailyLimitMinutes", 8 * 60)
            OccupationPreset(occ, wage, o.long("baseHint"), items, limit, o.strOr("note", ""))
        }
        return PresetData(patterns, sets, pay, removed)
    }

    /** [base] with [over] laid on top (same id replaced in place, new ones after, removed ones gone). */
    fun merge(base: PresetData, over: PresetData): PresetData {
        fun <T> lay(a: List<T>, b: List<T>, key: (T) -> String): List<T> {
            val byKey = b.associateBy(key)
            val kept = a.filter { key(it) !in over.removed }.map { byKey[key(it)] ?: it }
            val known = a.map(key).toSet()
            return kept + b.filter { key(it) !in known }
        }
        return PresetData(
            lay(base.patterns, over.patterns) { it.id },
            lay(base.shiftSets, over.shiftSets) { it.id },
            lay(base.pay, over.pay) { it.occupation.name },
        )
    }

    private fun color(s: String): Long {
        if (s.length != 7 || s[0] != '#') throw RemoteFileException("color $s")
        return 0xFF000000L or (s.substring(1).toLongOrNull(16) ?: throw RemoteFileException("color $s"))
    }

    private fun time(s: String): LocalTime = try { LocalTime.parse(s) } catch (e: java.time.format.DateTimeParseException) {
        throw RemoteFileException("time $s")
    }
}

/**
 * [REMOTE-03] The presets the app shows: the built-in file, with the last good downloaded one laid over it
 * ([use]). Reading the built-in file can only fail if packaging lost it; then the lists are empty, never a crash.
 */
object PresetBook {
    val bundled: PresetData by lazy {
        RemoteFiles.bundled(RemoteFiles.PRESETS)?.let { runCatching { PresetFile.read(it) }.getOrNull() } ?: PresetData.EMPTY
    }
    val bundledVersion: Long by lazy { RemoteFiles.bundled(RemoteFiles.PRESETS)?.version ?: 0L }

    @Volatile private var merged: PresetData? = null

    val current: PresetData get() = merged ?: bundled

    /** Lays a downloaded presets file over the built-in one; null goes back to the built-in list. */
    fun use(file: RemoteFile?) {
        merged = file?.takeIf { it.version > bundledVersion }?.let { PresetFile.merge(bundled, PresetFile.read(it)) }
    }
}
