package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import java.time.LocalTime

/**
 * [SETUP-02] The part a shift plays in a rotation, matching the preset codes: D 주간, E 오후,
 * N 야간, X 24시간 당번, B 비번 (the day after a 당번), O 휴무.
 */
enum class ShiftRole(val code: Char) {
    DAY('D'), EVENING('E'), NIGHT('N'), DUTY('X'), AFTER_DUTY('B'), OFF('O');

    val isOff: Boolean get() = this == AFTER_DUTY || this == OFF

    companion object {
        fun of(code: Char): ShiftRole? = entries.firstOrNull { it.code == code }

        /** A role for a shift the user made, from its times (so presets can still be offered). */
        fun guess(type: ShiftType): ShiftRole {
            if (type.isOff) return if (type.name.startsWith("비")) AFTER_DUTY else OFF
            val start = type.start ?: return DAY
            if (type.durationMinutes >= 20 * 60) return DUTY
            return when (start.hour) {
                in 4..11 -> DAY
                in 12..17 -> EVENING
                else -> NIGHT
            }
        }
    }
}

/** One shift of a bundle and the part it plays in the order. */
data class BundleShift(val role: ShiftRole, val type: ShiftType)

/**
 * [SETUP-02] The shifts one kind of workplace works, asked as "하루 근무가 몇 가지예요?". The times
 * are the usual ones and are confirmed one by one in the next step; alarms are chosen later.
 */
data class ShiftBundle(val key: String, val name: String, val description: String, val shifts: List<BundleShift>) {
    val workCodes: Set<Char> get() = shifts.filterNot { it.role.isOff }.map { it.role.code }.toSet()

    /** The rotations these shifts can fill, e.g. 4조 2교대 for 주간·야간. */
    val presets: List<PatternPreset> get() = PatternPresets.forCodes(workCodes)
}

object ShiftBundles {
    const val TWO = "two"
    const val THREE = "three"
    const val HOSPITAL = "hospital"
    const val DUTY = "duty24"

    private const val BLUE = 0xFF2F80ED
    private const val ORANGE = 0xFFF2994A
    private const val PURPLE = 0xFF6C5CE7
    private const val RED = 0xFFEB5757
    private const val GREY = 0xFF8E9AAF
    private const val SAND = 0xFFB59F84

    private fun work(role: ShiftRole, name: String, label: String, color: Long, start: Int, end: Int, breakMinutes: Int) =
        BundleShift(role, ShiftType(0, name, label, color, LocalTime.of(start, 0), LocalTime.of(end, 0), breakMinutes = breakMinutes))

    private fun off(role: ShiftRole, name: String, label: String, color: Long = GREY) =
        BundleShift(role, ShiftType(0, name, label, color, null, null, isOff = true))

    val all: List<ShiftBundle> = listOf(
        ShiftBundle(TWO, "2교대", "주간 07~19 · 야간 19~07", listOf(
            work(ShiftRole.DAY, "주간", "주", BLUE, 7, 19, 60),
            work(ShiftRole.NIGHT, "야간", "야", PURPLE, 19, 7, 60),
            off(ShiftRole.OFF, "휴무", "휴"),
        )),
        ShiftBundle(THREE, "3교대", "주간 06~14 · 오후 14~22 · 야간 22~06", listOf(
            work(ShiftRole.DAY, "주간", "주", BLUE, 6, 14, 30),
            work(ShiftRole.EVENING, "오후", "오", ORANGE, 14, 22, 30),
            work(ShiftRole.NIGHT, "야간", "야", PURPLE, 22, 6, 30),
            off(ShiftRole.OFF, "휴무", "휴"),
        )),
        ShiftBundle(HOSPITAL, "병원 3교대", "데이 07~15 · 이브닝 15~23 · 나이트 23~07", listOf(
            work(ShiftRole.DAY, "데이", "D", BLUE, 7, 15, 0),
            work(ShiftRole.EVENING, "이브닝", "E", ORANGE, 15, 23, 0),
            work(ShiftRole.NIGHT, "나이트", "N", PURPLE, 23, 7, 0),
            off(ShiftRole.OFF, "오프", "O"),
        )),
        ShiftBundle(DUTY, "24시간 당번", "당번 09~다음 날 09 · 비번 · 휴무", listOf(
            work(ShiftRole.DUTY, "당번", "당", RED, 9, 9, 240),
            off(ShiftRole.AFTER_DUTY, "비번", "비", SAND),
            off(ShiftRole.OFF, "휴무", "휴"),
        )),
    )

    fun byKey(key: String?): ShiftBundle? = all.firstOrNull { it.key == key }

    /** [WORK-01] Weekday work: 주간 09~18 with an hour's break. */
    val weekday: List<BundleShift> = listOf(work(ShiftRole.DAY, "주간", "주", BLUE, 9, 18, 60), off(ShiftRole.OFF, "휴무", "휴"))

    /** [WORK-01] Part-time blocks; under 4 hours of work needs no break, 4 hours or more gets 30 minutes. */
    val hourly: List<BundleShift> = listOf(
        work(ShiftRole.DAY, "오전 알바", "전", 0xFF00A88F, 9, 13, 0),
        work(ShiftRole.EVENING, "오후 알바", "후", 0xFFE0A100, 13, 18, 30),
        work(ShiftRole.NIGHT, "저녁 알바", "저", 0xFFD6457A, 18, 23, 30),
    )

    /** Start of 직접 만들기: one shift to fill in and a day off. */
    val custom: List<BundleShift> = listOf(work(ShiftRole.DAY, "주간", "주", BLUE, 9, 18, 60), off(ShiftRole.OFF, "휴무", "휴"))

    /** Average weekly work of a rotation, e.g. 38시간 30분 for 주주야야휴휴휴휴 of 11-hour shifts. */
    fun weeklyMinutes(steps: List<Long?>, types: Map<Long, ShiftType>): Int =
        if (steps.isEmpty()) 0 else Math.round(steps.sumOf { id -> id?.let(types::get)?.workMinutes ?: 0 } * 7.0 / steps.size).toInt()
}
