package com.bamnat.wear

import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import java.time.LocalDate
import java.time.LocalDateTime

/** [SHFT-08] The words the tile, the complication and the app share. */
object WatchText {
    private val week = listOf("월", "화", "수", "목", "금", "토", "일")

    fun dayOfWeek(d: LocalDate) = week[d.dayOfWeek.value - 1]

    /** "10월 20일 (화)". */
    fun date(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일 (${dayOfWeek(d)})"

    fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    /** "알람 06:30 · 주간 근무", "알람 내일 06:30", or null when none is ahead. */
    fun alarm(p: WatchPayload, now: LocalDateTime): String? {
        val at = WatchDays.alarmAhead(p, now) ?: return null
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> ""
            now.toLocalDate().plusDays(1) -> "내일 "
            else -> "${at.monthValue}/${at.dayOfMonth} "
        }
        return "알람 $day${hm(at)}" + p.alarmLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    /** "휴대폰에서 받은 지 3시간 전" style line. */
    fun synced(p: WatchPayload, now: LocalDateTime) = "휴대폰에서 받음 · ${WatchDays.age(p.syncedAt, now)}"

    const val NO_DATA = "휴대폰의 밤낮을 열어 주세요"
    const val NOT_SENT = "근무 정보 없음"
}
