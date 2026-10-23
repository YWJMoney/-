package com.bamnat.core.model

/**
 * [SET-05] Every shift's alarms moved at once (notes/1.22-285/G.md P21): [minutes] earlier when positive, later
 * when negative. An alarm never goes past the shift's start (0분 전) nor more than [MAX_BEFORE] before it, and two
 * alarms that land on the same minute become one. Off days and shifts without alarms stay as they are.
 */
object AlarmShift {
    const val MAX_BEFORE = 12 * 60

    fun move(type: ShiftType, minutes: Int): ShiftType {
        if (type.isOff || type.alarms.isEmpty() || minutes == 0) return type
        val moved = type.alarms
            .map { it.copy(offsetMinutes = (it.offsetMinutes + minutes).coerceIn(0, MAX_BEFORE)) }
            .distinctBy { it.offsetMinutes }
        return type.copy(alarms = moved)
    }

    /** The shifts [move] would change, so the screen can say how many and save only those. */
    fun moveAll(types: List<ShiftType>, minutes: Int): List<ShiftType> =
        types.map { move(it, minutes) }.filterIndexed { i, t -> t != types[i] }
}
