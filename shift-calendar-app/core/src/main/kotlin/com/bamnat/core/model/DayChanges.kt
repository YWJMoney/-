package com.bamnat.core.model

import java.time.LocalTime

/**
 * [MKT-08] What changed on a day, in words, so short-notice changes can be traced later
 * ("근무 주간 → 야간, 잔업 2시간"). Null when nothing a person would notice changed.
 */
object DayChanges {
    fun describe(
        before: DayEntry?,
        after: DayEntry,
        typeName: (Long) -> String,
        extraName: (Long) -> String = { ExtraKinds().name(it) },
    ): String? {
        val b = before ?: DayEntry(after.date)
        // Clearing a day says so; taking off its only extra work says which.
        if (!b.isEmpty && after.isEmpty && !b.copy(extras = emptyList()).isEmpty) return "바꾼 내용 모두 지움"
        val parts = mutableListOf<String>()
        if (b.override != after.override) parts += when (val o = after.override) {
            ShiftOverride.None -> "패턴대로 되돌림"
            ShiftOverride.Off -> "쉬는 날로"
            is ShiftOverride.Shift -> "근무 → ${typeName(o.shiftTypeId)}"
        }
        if (b.customStart != after.customStart || b.customEnd != after.customEnd || b.customBreakMinutes != after.customBreakMinutes) {
            val s = after.customStart
            val e = after.customEnd
            parts += if (s != null && e != null) "시간 ${t(s)}–${t(e)}" + (after.customBreakMinutes?.let { " (휴게 ${it}분)" } ?: "")
            else "시간 원래대로"
        }
        // [EXTRA-01] Pieces of extra work added or changed, then kinds taken off the day.
        if (b.extras != after.extras) {
            val gone = b.extras.toMutableList()
            val added = after.extras.filter { !gone.remove(it) }
            added.forEach { parts += "${extraName(it.kindId)} ${span(it)}" }
            gone.filter { g -> added.none { it.kindId == g.kindId } }.map { extraName(it.kindId) }.distinct().forEach { parts += "$it 지움" }
        }
        if (b.leave != after.leave) parts += after.leave?.label ?: "${b.leave?.label ?: "휴가"} 취소"
        // [HRLY-01] 지각·조퇴·결근 kept with the day's other changes.
        if (b.attendance != after.attendance) parts += after.attendance?.label ?: "${b.attendance?.label} 취소"
        if (b.alarmSkipped != after.alarmSkipped) parts += if (after.alarmSkipped) "알람 끔" else "알람 켬"
        if (b.customAlarms != after.customAlarms) parts += if (after.customAlarms == null) "알람 원래대로" else "알람 바꿈"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun span(e: ExtraEntry) = if (e.hasTimes) "${t(e.start!!)}–${t(e.end!!)}" else hours(e.paidMinutes)

    private fun t(x: LocalTime) = "%02d:%02d".format(x.hour, x.minute)
    private fun hours(m: Int) = if (m % 60 == 0) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
}
