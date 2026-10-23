package com.bamnat.app.ui.calendar

import com.bamnat.app.data.AppSettings
import com.bamnat.app.ui.components.EditGroup
import com.bamnat.app.ui.components.EditRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.bamnat.core.layout.PageLayout

/**
 * [CUST-01] The calendar page's parts the user can order and turn off, around the month itself. Warnings
 * (알람이 꺼져 있어요, 확인할 기록) are not here: they show whenever they are needed.
 */
enum class CalendarPart(val id: String, val label: String, val what: String, val above: Boolean, val movable: Boolean = true) {
    TODAY_FLOW("todayFlow", "오늘의 흐름", "잘 시간·출근·쉬는 시간을 오늘 하루 줄로", above = true),
    TODAY_WORK("todayWork", "오늘 일감·매출·투자", "현장·가게·투자 달력의 오늘 카드", above = true),
    SUMMARY("summary", "이번 달 요약", "근무·급여(또는 수입·매출) 세 숫자", above = true, movable = false),
    COUNTS("counts", "근무 횟수 줄", "달력 바로 위 근무 종류별 횟수", above = true, movable = false),
    DAY_CARD("dayCard", "하루 카드", "누른 날의 근무 시간·특근·알람 끄기", above = false),
    HOURS("hours", "주 근무시간 줄", "이 주(월~일)와 3개월 주평균", above = false),
    REST("rest", "휴식 점검", "근무 사이 11시간 못 쉰 날", above = false),
    MEMOS("memos", "이번 달 메모", "날짜가 있는 메모 목록", above = false),
    ;

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** [CUST-01] The parts in the user's order, each with whether it shows; three follow their own settings switch. */
internal fun AppSettings.calendarParts(): List<PageLayout.Placed<CalendarPart>> {
    val layout = PageLayout.decode(calendarLayout)
    return layout.arrange(CalendarPart.entries.toList()) { it.id }.map { p ->
        when (p.part) {
            CalendarPart.DAY_CARD -> p.copy(shown = showDayCard)
            CalendarPart.REST -> p.copy(shown = restCheck)
            CalendarPart.MEMOS -> p.copy(shown = calendarMemos)
            else -> p
        }
    }
}

/** [CUST-01] [shown] for [part]: the three with their own switch change that switch, the rest the layout line. */
internal fun AppSettings.withPartShown(part: CalendarPart, shown: Boolean): AppSettings = when (part) {
    CalendarPart.DAY_CARD -> copy(showDayCard = shown)
    CalendarPart.REST -> copy(restCheck = shown)
    CalendarPart.MEMOS -> copy(calendarMemos = shown, memoInCells = memoInCells && shown)
    else -> {
        val placed = calendarParts().map { if (it.part == part) it.copy(shown = shown) else it }
        copy(calendarLayout = PageLayout.of(placed) { it.id }.encode())
    }
}

/** [CUST-01] [part] moved [by] places within its group (above or below the month). */
internal fun AppSettings.withPartMoved(part: CalendarPart, by: Int): AppSettings {
    val placed = calendarParts()
    val group = placed.filter { it.part.above == part.above && it.part.movable }
    val moved = PageLayout.move(group, group.indexOfFirst { it.part == part }, by)
    val (above, below) = placed.partition { it.part.above }
    val fixed = { list: List<PageLayout.Placed<CalendarPart>> -> list.filterNot { it.part.movable } }
    val next = if (part.above) moved + fixed(above) + below else above + moved + fixed(below)
    return copy(calendarLayout = PageLayout.of(next) { it.id }.encode())
}

internal fun calendarEditGroups(placed: List<PageLayout.Placed<CalendarPart>>): List<EditGroup> {
    fun rows(above: Boolean) = placed.filter { it.part.above == above }.sortedBy { if (it.part.movable) 0 else 1 }.map {
        EditRow(it.part.id, it.part.label, it.part.what, it.shown, it.part.movable, it.isNew)
    }
    return listOf(EditGroup("위쪽 (달력 위)", rows(true)), EditGroup("아래쪽 (달력 아래)", rows(false)))
}

/**
 * [CUST-01] 화면 꾸미기 for the calendar: the parts around the month, and the month's own view options
 * (첫 요일, 칸의 시간, 음력, 날짜를 누르면) that used to be only in 설정 › 달력 화면.
 */
@androidx.compose.runtime.Composable
internal fun CalendarEditSheet(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    val placed = settings.calendarParts()
    com.bamnat.app.ui.components.PageEditSheet(
        title = "달력 화면 꾸미기",
        groups = calendarEditGroups(placed),
        middle = "이번 달 달력",
        onShow = { id, shown -> CalendarPart.byId(id)?.let { p -> update { it.withPartShown(p, shown) } } },
        onMove = { id, by -> CalendarPart.byId(id)?.let { p -> update { it.withPartMoved(p, by) } } },
        onReset = { update { it.copy(calendarLayout = "", showDayCard = false, restCheck = AppSettings().restCheck, calendarMemos = AppSettings().calendarMemos) } },
        onAsk = {
            onDismiss()
            com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "화면 꾸미기"))
        },
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.Text("달력 보기", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        OptionChips("한 주의 시작", listOf(java.time.DayOfWeek.SUNDAY to "일요일", java.time.DayOfWeek.MONDAY to "월요일"), settings.weekStart) { d ->
            update { it.copy(weekStart = d) }
        }
        OptionChips("날짜를 누르면", listOf(com.bamnat.app.data.PhoneDayView.SHEET to "하루 화면", com.bamnat.app.data.PhoneDayView.WEEK to "그 주만"),
            settings.phoneDayView) { v -> update { it.copy(phoneDayView = v) } }
        OptionSwitch("날짜 칸에 근무 시간", settings.showHoursInCells) { on -> update { it.copy(showHoursInCells = on) } }
        OptionSwitch("음력 날짜", settings.showLunar) { on -> update { it.copy(showLunar = on) } }
        OptionSwitch("날짜 밑에 메모 첫 글자", settings.memoInCells) { on -> update { it.copy(memoInCells = on, calendarMemos = it.calendarMemos || on) } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
private fun <T> OptionChips(label: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(vertical = 4.dp)) {
        androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, name) ->
                com.bamnat.app.ui.components.FilterChip(selected = v == value, onClick = { onPick(v) }, label = { androidx.compose.material3.Text(name) })
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = androidx.compose.ui.Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}
