package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import com.bamnat.core.remote.PresetBook

/**
 * Common Korean rotation patterns [MKT-04]. Codes: D = 주간, E = 오후(초번), N = 야간,
 * X = 24시간 당번, B = 비번 (the day after a 당번), O = 휴무 [SETUP-02].
 */
/**
 * [REMOTE-03] [id] keys the preset in `remote/v1/presets.json`; [jobs] are Occupation names shown first for
 * that 직군, [source] where a 직군 pattern comes from.
 */
data class PatternPreset(
    val name: String,
    val description: String,
    val codes: String,
    val id: String = name,
    val jobs: List<String> = emptyList(),
    val source: String = "",
)

object PatternPresets {
    /** [REMOTE-03] From the presets file (built in, or a newer signed download laid over it). */
    val all: List<PatternPreset> get() = PresetBook.current.patterns

    /** The work codes of [preset], e.g. D and N for 주주야야휴휴. */
    fun workCodes(preset: PatternPreset): Set<Char> = preset.codes.filter { it != 'O' && it != 'B' }.toSet()

    /** [SETUP-02] The presets a bundle with exactly these work codes can fill, e.g. 4조 2교대 for D and N. */
    fun forCodes(codes: Set<Char>): List<PatternPreset> = all.filter { workCodes(it) == codes }

    private val nameHints: Map<Char, List<String>> = mapOf(
        'D' to listOf("주간", "주", "D", "데이"),
        'E' to listOf("오후", "초번", "오", "E", "이브닝"),
        'N' to listOf("야간", "야", "N", "나이트"),
        'X' to listOf("당번", "당직", "24시간", "당", "X"),
        'B' to listOf("비번", "비", "B"),
    )

    /**
     * Turns preset codes into shift type ids using the user's types.
     * A code with no matching type becomes null (a plain day off), as does 'O'
     * unless the user has an off type, in which case that type is used so it shows on the calendar.
     * 'B' takes an off type named 비번 when there is one, else the same day off as 'O'.
     */
    fun toSteps(preset: PatternPreset, types: List<ShiftType>): List<Long?> {
        val offTypes = types.filter { it.isOff }
        val offType = offTypes.firstOrNull { o -> nameHints.getValue('B').none { it == o.name } } ?: offTypes.firstOrNull()
        val workTypes = types.filter { !it.isOff }
        val byCode = nameHints.mapValues { (code, hints) ->
            val pool = if (code == 'B') offTypes else workTypes
            hints.firstNotNullOfOrNull { hint ->
                pool.firstOrNull { it.name == hint || it.label.equals(hint, ignoreCase = true) }
            }?.id
        }
        return preset.codes.map { code ->
            when (code) {
                'O' -> offType?.id
                'B' -> byCode['B'] ?: offType?.id
                else -> byCode[code]
            }
        }
    }

    /**
     * [SETUP-02] Preset codes to the ids of a bundle's shifts by the part each plays; 'B' falls back to
     * the day off. A code no shift plays becomes null.
     */
    fun toSteps(preset: PatternPreset, roles: Map<Long, ShiftRole>): List<Long?> {
        val byCode = roles.entries.groupBy({ it.value.code }, { it.key }).mapValues { it.value.first() }
        return preset.codes.map { code -> byCode[code] ?: if (code == 'B') byCode['O'] else null }
    }
}

/** A ready-made set of shift types for a kind of workplace [MKT-03]; [id] and [jobs] as in [PatternPreset]. */
data class ShiftTypeSet(
    val name: String,
    val description: String,
    val types: List<ShiftType>,
    val id: String = name,
    val jobs: List<String> = emptyList(),
)

object ShiftTypeSets {
    /** [REMOTE-03] From the presets file, like [PatternPresets.all]. */
    val all: List<ShiftTypeSet> get() = PresetBook.current.shiftSets

    /** The set's types the user does not already have (by name), ready to save. */
    fun missing(set: ShiftTypeSet, existing: List<ShiftType>): List<ShiftType> =
        set.types.filter { t -> existing.none { it.name == t.name } }
}
