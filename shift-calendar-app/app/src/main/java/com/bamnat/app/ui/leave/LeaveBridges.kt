package com.bamnat.app.ui.leave

import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.work.DayWork
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate

/**
 * [LEAVE-04] 징검다리 for every kind of work: a 일근 calendar's 공휴일 next to its weekends ([DayWork.bridges]),
 * and for 교대·알바·현장 the roster's own days off, which 공휴일 do not move ([DayWork.restBridges]).
 */
internal object LeaveBridges {
    fun of(snap: ScheduleSnapshot, style: WorkStyle?, today: LocalDate): List<DayWork.Bridge> {
        val work = { d: LocalDate -> snap.resolver.resolve(d).let { it.isWorkDay && it.leave == null } }
        return when (style) {
            WorkStyle.DAY -> DayWork.bridges(today, isWorkDay = work)
            WorkStyle.BUSINESS, WorkStyle.INVEST -> emptyList()
            else -> DayWork.restBridges(today, isWorkDay = work)
        }
    }

    fun caption(style: WorkStyle?): String = when (style) {
        WorkStyle.DAY -> "앞으로 12개월의 공휴일과 쉬는 요일로 찾았어요."
        else -> "앞으로 12개월 근무표의 쉬는 날 사이에 낀 하루이틀을 찾았어요. 달마다 가장 긴 두 곳이에요."
    }
}
