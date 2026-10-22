package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern

/**
 * [REST-03] How fast a pattern turns, read off its cycle (순번 105): only nights (고정 야간), the same shift at
 * most 3 days before it changes (빠른 순환, 주주야야비휴), 4 days or more (느린 순환, 주 단위 교대), 24시간 당번,
 * or days only. The roster checks change with it: a fixed night worker gets no 'nights in a row' sign every
 * day, and a 24시간 격일 worker is not told each 당번 needs two days off.
 */
enum class RotationSpeed(val label: String, val note: String) {
    NONE("근무 없음", ""),
    DAY_ONLY("야간 없는 근무", "야간이 없어 휴식 11시간과 연속 근무만 봐요"),
    FIXED_NIGHT("고정 야간", "야간만 하는 근무라 연속·한 달 야간 대신 휴식과 쉬는 날을 봐요"),
    FAST("빠른 순환", "같은 근무가 3일 안에 바뀌는 순서예요. 기준 그대로 봐요"),
    SLOW("느린 순환", "같은 근무가 4일 넘게 이어지는 순서예요. 연속 야간을 특히 봐요"),
    DUTY_24H("24시간 당번", "당번 뒤 하루 쉬는 순서라 당번 뒤 쉬는 날은 1일로 봐요");

    /** [rules] as they fit this speed; the user's own numbers stay for everything the speed does not change. */
    fun adjust(rules: RestRules): RestRules = when (this) {
        FIXED_NIGHT -> rules.copy(maxNightsInRow = 0, maxNightsPerMonth = 0)
        DUTY_24H -> rules.copy(offsAfterNights = minOf(rules.offsAfterNights, 1), maxNightsInRow = 0, maxNightsPerMonth = 0)
        else -> rules
    }

    companion object {
        /**
         * The speed of a cycle written in preset codes (D 주간, E 오후, N 야간, X 24시간 당번, B 비번, O 휴무).
         * The cycle repeats, so a run at the end goes on at the start.
         */
        fun ofCodes(codes: String): RotationSpeed {
            val work = codes.filter { it != 'O' && it != 'B' }
            return when {
                work.isEmpty() -> NONE
                'X' in work -> DUTY_24H
                work.all { it == 'N' } -> FIXED_NIGHT
                'N' !in work && work.toSet().size == 1 -> DAY_ONLY
                else -> if (longestRun(codes) >= 4) SLOW else FAST
            }
        }

        /** [pattern]'s speed with the user's own shifts: off types are O, mostly-night shifts N, 20 hours or more X. */
        fun of(pattern: WorkPattern?, types: List<ShiftType>): RotationSpeed {
            val steps = when (pattern) {
                is WorkPattern.Rotation -> pattern.steps
                is WorkPattern.Weekly -> pattern.steps
                null -> return NONE
            }
            val byId = types.associateBy { it.id }
            return ofCodes(steps.joinToString("") { id -> code(id?.let(byId::get)).toString() })
        }

        /** One shift as a preset code. */
        fun code(t: ShiftType?): Char = when {
            t == null || t.isOff -> 'O'
            t.hasTimes && t.durationMinutes >= 20 * 60 -> 'X'
            mostlyNight(t) -> 'N'
            t.hasTimes && t.start!!.hour in 11..17 -> 'E'
            !t.hasTimes && DutyCheck.isNight(t) -> 'N'
            else -> 'D'
        }

        /** Half or more of the shift inside 22:00~06:00, so an 이브닝 15–23 that only reaches 22:00 is not a night. */
        private fun mostlyNight(t: ShiftType): Boolean {
            if (!t.hasTimes) return false
            val from = java.time.LocalDate.of(2026, 1, 1).atTime(t.start!!)
            val to = from.plusMinutes(t.durationMinutes.toLong())
            return t.durationMinutes > 0 && RestCheck.nightMinutes(from, to) * 2 >= t.durationMinutes
        }

        /** The longest run of one work code, the cycle read round. */
        private fun longestRun(codes: String): Int {
            if (codes.isEmpty()) return 0
            val twice = codes + codes
            var best = 0
            var run = 0
            var prev = ' '
            for (c in twice) {
                run = if (c == prev && c != 'O' && c != 'B') run + 1 else if (c != 'O' && c != 'B') 1 else 0
                prev = c
                best = maxOf(best, minOf(run, codes.length))
            }
            return best
        }
    }
}
