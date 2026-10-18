package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType

/**
 * Common Korean rotation patterns [MKT-04]. Codes: D = 주간, E = 오후(초번), N = 야간,
 * X = 24시간 당번, B = 비번 (the day after a 당번), O = 휴무 [SETUP-02].
 */
data class PatternPreset(val name: String, val description: String, val codes: String)

object PatternPresets {
    val all: List<PatternPreset> = listOf(
        PatternPreset("3조 2교대", "주주야야비휴", "DDNNOO"),
        PatternPreset("4조 2교대", "주야비휴", "DNOO"),
        PatternPreset("4조 3교대", "주주오오야야휴휴", "DDEENNOO"),
        PatternPreset("3교대 주 단위", "주간 5일 · 오후 5일 · 야간 5일, 사이 2일 휴무", "DDDDDOOEEEEEOONNNNNOO"),
        PatternPreset("격일 근무", "근무 · 휴무 반복", "DO"),
        PatternPreset("주 5일 주간", "시작일을 월요일로 두면 월–금 근무", "DDDDDOO"),
        PatternPreset("3조 2교대 9일", "주주주야야야휴휴휴", "DDDNNNOOO"),
        PatternPreset("4조 3교대 12일", "주주주오오오야야야휴휴휴", "DDDEEENNNOOO"),
        PatternPreset("5조 3교대", "주주오오야야휴휴휴휴", "DDEENNOOOO"),
        PatternPreset("4조 2교대 8일", "주주야야휴휴휴휴", "DDNNOOOO"),
        PatternPreset("4조 2교대 8일 (주휴야휴)", "주주휴휴야야휴휴", "DDOONNOO"),
        PatternPreset("24시간 격일 맞교대", "당번 · 비번 반복", "XB"),
        PatternPreset("24시간 3조 맞교대", "당번 · 비번 · 휴무", "XBO"),
        PatternPreset("24시간 4조 맞교대", "당번 · 비번 · 당번 · 비번 · 휴무 · 휴무 · 휴무 · 휴무", "XBXBOOOO"),
    )

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

/** A ready-made set of shift types for a kind of workplace [MKT-03]. */
data class ShiftTypeSet(val name: String, val description: String, val types: List<ShiftType>)

object ShiftTypeSets {
    private fun t(name: String, label: String, color: Long, start: Int, end: Int, breakMin: Int, alarmMin: Int) =
        ShiftType(0, name, label, color, java.time.LocalTime.of(start, 0), java.time.LocalTime.of(end, 0), breakMinutes = breakMin,
            alarmEnabled = true, alarms = listOf(com.bamnat.core.model.AlarmSpec(alarmMin)))

    val all: List<ShiftTypeSet> = listOf(
        ShiftTypeSet("병원 3교대 (D/E/N)", "데이 07–15 · 이브닝 15–23 · 나이트 23–07", listOf(
            t("데이", "D", 0xFF2F80ED, 7, 15, 0, 90),
            t("이브닝", "E", 0xFFF2994A, 15, 23, 0, 60),
            t("나이트", "N", 0xFF6C5CE7, 23, 7, 0, 60),
        )),
        ShiftTypeSet("공장 3교대", "주간 06–14 · 오후 14–22 · 야간 22–06", listOf(
            t("주간", "주", 0xFF2F80ED, 6, 14, 30, 90),
            t("오후", "오", 0xFFF2994A, 14, 22, 30, 60),
            t("야간", "야", 0xFF6C5CE7, 22, 6, 30, 60),
        )),
        ShiftTypeSet("12시간 2교대", "주간 07–19 · 야간 19–07", listOf(
            t("주간", "주", 0xFF2F80ED, 7, 19, 60, 90),
            t("야간", "야", 0xFF6C5CE7, 19, 7, 60, 60),
        )),
        ShiftTypeSet("24시간 맞교대", "당번 09–다음날 09 (휴게 4시간)", listOf(
            t("당번", "당", 0xFFEB5757, 9, 9, 240, 90),
        )),
    )

    /** The set's types the user does not already have (by name), ready to save. */
    fun missing(set: ShiftTypeSet, existing: List<ShiftType>): List<ShiftType> =
        set.types.filter { t -> existing.none { it.name == t.name } }
}
