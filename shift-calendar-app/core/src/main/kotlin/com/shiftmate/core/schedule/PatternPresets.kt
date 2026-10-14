package com.shiftmate.core.schedule

import com.shiftmate.core.model.ShiftType

/**
 * Common Korean rotation patterns. Codes: D = 주간, E = 오후(초번), N = 야간, O = 휴무/비번.
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
    )

    private val nameHints: Map<Char, List<String>> = mapOf(
        'D' to listOf("주간", "주", "D", "데이"),
        'E' to listOf("오후", "초번", "오", "E", "이브닝"),
        'N' to listOf("야간", "야", "N", "나이트"),
    )

    /**
     * Turns preset codes into shift type ids using the user's types.
     * A code with no matching type becomes null (a plain day off), as does 'O'
     * unless the user has an off type, in which case that type is used so it shows on the calendar.
     */
    fun toSteps(preset: PatternPreset, types: List<ShiftType>): List<Long?> {
        val offType = types.firstOrNull { it.isOff }
        val workTypes = types.filter { !it.isOff }
        val byCode = nameHints.mapValues { (_, hints) ->
            hints.firstNotNullOfOrNull { hint ->
                workTypes.firstOrNull { it.name == hint || it.label.equals(hint, ignoreCase = true) }
            }?.id
        }
        return preset.codes.map { code ->
            when (code) {
                'O' -> offType?.id
                else -> byCode[code]
            }
        }
    }
}
