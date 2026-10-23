package com.bamnat.app.share

import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.DutyCheck
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.RotationSpeed
import java.time.LocalDate
import java.time.YearMonth

/**
 * [NURS-06] 받은 근무표에도 근무표 점검 (순번 282): a family member's or colleague's shared roster read with the user's
 * own 근무표 점검 기준, as the sharer's shifts say, e.g. "이번 주 나이트 3일 연속". Only on this phone, nothing is sent.
 */
object ReceivedDutyCheck {
    fun warnings(s: SharedSchedule, month: YearMonth, rules: RestRules): List<DutyCheck.Warning> {
        if (!rules.on || !s.covers(month)) return emptyList()
        // The days around the month count too; days the link does not cover are left out, not taken as rest.
        val days = (-3L..month.lengthOfMonth() + 2L).map { month.atDay(1).plusDays(it - 1) }
            .filter { s.covers(it) }
            .map { it to shiftOn(s, it) }
        val speed = RotationSpeed.ofCodes(days.filter { YearMonth.from(it.first) == month }.joinToString("") { RotationSpeed.code(it.second).toString() })
        return DutyCheck.check(days, speed.adjust(rules)).filter { YearMonth.from(it.date) == month }
    }

    /** What the sharer works on [date]: their shift, or nothing on a day of leave or with no shift. */
    fun shiftOn(s: SharedSchedule, date: LocalDate): ShiftType? {
        val day = s.days[date] ?: return null
        if (day.fullLeave) return null
        return s.type(day.type)?.asShiftType()
    }
}

/**
 * [SHR-13] What changed in someone's shared schedule over the next [days] days, as short lines such as
 * "10/25 주간 → 야간"; empty when nothing did, or when the old copy did not cover those days.
 */
object ReceivedChanges {
    fun nextDays(old: SharedSchedule?, new: SharedSchedule, today: java.time.LocalDate, days: Int = 7): List<String> {
        if (old == null) return emptyList()
        return (0 until days).mapNotNull { i ->
            val date = today.plusDays(i.toLong())
            if (!old.covers(date) || !new.covers(date)) return@mapNotNull null
            val was = label(old, date)
            val now = label(new, date)
            if (was == now) null else "${date.monthValue}/${date.dayOfMonth} $was → $now"
        }
    }

    private fun label(s: SharedSchedule, date: java.time.LocalDate): String {
        val d = s.days[date] ?: return "쉬는 날"
        d.leave?.let { return it }
        val names = listOfNotNull(s.type(d.type)?.name, s.type(d.second)?.name)
        return names.joinToString("+").ifEmpty { "쉬는 날" }
    }
}
