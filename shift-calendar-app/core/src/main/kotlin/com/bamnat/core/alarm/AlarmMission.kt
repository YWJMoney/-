package com.bamnat.core.alarm

import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * [PROV-06] What turning an alarm off asks for (프로): nothing, a few sums, shaking the phone or, [ALRM-04], a photo of a
 * place set beforehand. A
 * mission never makes an alarm impossible to stop: after [GIVE_UP_AFTER] of ringing, 끄기 needs none.
 */
enum class AlarmMission(val key: String, val label: String, val hint: String) {
    NONE("none", "미션 없음", "누르면 바로 꺼져요"),
    MATH("math", "계산 문제", "두 자리 덧셈·뺄셈 ${Missions.PROBLEMS}개를 풀어야 꺼져요"),
    SHAKE("shake", "흔들기", "휴대폰을 ${Missions.SHAKES}번 흔들어야 꺼져요"),
    /** [ALRM-04] The place photographed in settings, photographed again; see [ImageHash]. */
    PHOTO("photo", "증명 사진", "정해 둔 곳을 찍어야 꺼져요");

    companion object {
        fun of(key: String?): AlarmMission = entries.firstOrNull { it.key == key } ?: NONE

        /**
         * The mission an alarm asks for: its shift's own choice, else the default. A memo schedule
         * ([shiftTypeId] 0 and a label) and a day's hand-set alarm take the default.
         */
        fun forAlarm(default: AlarmMission, byShift: Map<Long, AlarmMission>, shiftTypeId: Long): AlarmMission =
            byShift[shiftTypeId] ?: default
    }
}

/** One sum, its answer a positive number of at most three digits. */
data class MathProblem(val a: Int, val b: Int, val plus: Boolean) {
    val answer: Int get() = if (plus) a + b else a - b
    val text: String get() = "$a ${if (plus) "+" else "−"} $b"
}

object Missions {
    const val PROBLEMS = 3
    const val SHAKES = 20
    /** Ringing this long without the mission done, 끄기 needs none. */
    val GIVE_UP_AFTER: Duration = Duration.ofMinutes(5)

    fun problem(random: Random = Random.Default): MathProblem {
        val a = random.nextInt(21, 99)
        val b = random.nextInt(12, 89)
        // A subtraction keeps the bigger number first.
        return if (random.nextBoolean()) MathProblem(a, b, true) else MathProblem(maxOf(a, b), minOf(a, b), false)
    }

    /** Whether 끄기 may skip the mission: it has rung [GIVE_UP_AFTER] since [since]. */
    fun canSkip(since: Instant, now: Instant): Boolean = !now.isBefore(since.plus(GIVE_UP_AFTER))

    /** [ALRM-04] Photos of the wrong place before 증명 사진 gives way to sums. */
    const val PHOTO_TRIES = 3

    /**
     * [ALRM-04] The mission actually asked: 증명 사진 without a reference photo, after [PHOTO_TRIES] wrong photos
     * or when the camera app cannot be opened, and 흔들기 without a motion sensor, become sums, so nobody is
     * ever stuck with an alarm they cannot stop.
     */
    fun shown(mission: AlarmMission, hasReference: Boolean, wrongPhotos: Int, cameraFailed: Boolean, motionSensor: Boolean): AlarmMission = when {
        mission == AlarmMission.PHOTO && (!hasReference || cameraFailed || wrongPhotos >= PHOTO_TRIES) -> AlarmMission.MATH
        mission == AlarmMission.SHAKE && !motionSensor -> AlarmMission.MATH
        else -> mission
    }
}

/**
 * [PROV-06] Counts shakes from the phone's motion without gravity (m/s²): one each time it goes past
 * [threshold], and not again within [gapMillis], so one swing is one shake.
 */
class ShakeCounter(private val threshold: Float = 12f, private val gapMillis: Long = 500) {
    var count: Int = 0
        private set
    private var last = Long.MIN_VALUE / 2

    /** True when this reading counted as a shake. */
    fun add(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        val strength = sqrt(x * x + y * y + z * z)
        if (strength < threshold || atMillis - last < gapMillis) return false
        last = atMillis
        count++
        return true
    }
}
