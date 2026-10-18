package com.bamnat.app.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.DurationDialog
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.app.ui.components.rememberNotificationAsk
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ShiftType
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.work.WorkStyle

// ---- 7. alarms ----

@Composable
internal fun AlarmStep(vm: SetupViewModel, d: SetupDraft) {
    StepHeader("출근 몇 분 전에 깨워 드릴까요?", "근무가 있는 날에만 울려요. 쉬는 날은 조용해요.")
    val perShift = d.alarmByType.isNotEmpty()
    if (!perShift) AlarmChoices(d.alarmMinutes, "알람", onPick = vm::setAlarm)
    if (d.workTypes.size > 1) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = perShift, role = Role.Switch, onValueChange = vm::alarmPerShift)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("근무마다 다르게", style = MaterialTheme.typography.bodyLarge)
                Text("야간만 일찍 깨우는 것처럼 따로 정해요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = perShift, onCheckedChange = null)
        }
    }
    d.workTypes.forEach { t ->
        val minutes = d.alarmFor(t)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.bamnat.app.ui.components.ShiftBadge(t, size = 24.dp)
                    Text(t.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text(ringLine(t, minutes), style = MaterialTheme.typography.bodyMedium,
                        color = if (minutes != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (perShift) AlarmChoices(minutes, "${t.name} 알람", onPick = { vm.setAlarmFor(t.id, it) })
            }
        }
    }
    // [SLP-15] Asked where it is needed: without notifications nothing on the lock screen stops an alarm.
    val anyAlarm = d.workTypes.any { d.alarmFor(it) != null }
    val context = LocalContext.current
    var canPost by remember { mutableStateOf(Notifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = Notifications.canPost(context) }
    val ask = rememberNotificationAsk()
    if (anyAlarm && !canPost) {
        WarningBanner("알림을 허용해 주세요", "허용해야 잠금 화면에서 알람을 끌 수 있어요", action = "허용", onAction = ask)
    }
}

/** 안 써요, 30분 … 2시간 전, or minutes typed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmChoices(minutes: Int?, title: String, onPick: (Int?) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = minutes == null, onClick = { onPick(null) }, label = { Text("안 써요") })
        SetupDraft.ALARM_CHOICES.forEach { m ->
            FilterChip(selected = minutes == m, onClick = { onPick(m) }, label = { Text(Fmt.offset(m)) })
        }
        val own = minutes != null && minutes !in SetupDraft.ALARM_CHOICES
        FilterChip(selected = own, onClick = { typing = true }, label = { Text(if (own) Fmt.offset(minutes!!) else "직접") })
    }
    if (typing) {
        DurationDialog(
            title = "$title · 출근 몇 분 전",
            initialMinutes = minutes ?: SetupDraft.DEFAULT_ALARM_MINUTES,
            maxMinutes = 12 * 60,
            onDismiss = { typing = false },
            onConfirm = {
                typing = false
                onPick(it)
            },
        )
    }
}

/** "06:00에 울려요", "전날 23:30에 울려요", "알람 없음". */
private fun ringLine(t: ShiftType, minutes: Int?): String {
    val start = t.start ?: return "알람 없음"
    if (minutes == null) return "알람 없음"
    val dayBefore = minutes > start.hour * 60 + start.minute
    return (if (dayBefore) "전날 " else "") + "${Fmt.time(start.minusMinutes(minutes.toLong()))}에 울려요"
}

// ---- 8. the summary ----

@Composable
internal fun SummaryStep(vm: SetupViewModel, d: SetupDraft) {
    StepHeader("이대로 저장할까요?", "저장하면 달력이 바로 채워져요. 고칠 곳은 ‘고치기’를 누르세요.")
    val types = remember(d.types) { d.types.associateBy { it.id } }
    SummaryCard("근무 형태", styleTitle(d), onFix = { vm.fix(SetupStep.STYLE) })
    val source = d.source
    if (source != null || d.companyName.isNotBlank()) {
        SummaryCard("회사", companyLine(d, source), onFix = { vm.fix(SetupStep.COMPANY) })
    }
    SummaryCard(
        "근무 시간",
        d.workTypes.joinToString("\n") { t ->
            "${t.name} ${hoursOf(t)}" + if (t.breakMinutes > 0) " · 쉬는 시간 ${HoursFormat.korean(t.breakMinutes)}" else ""
        },
        onFix = { vm.fix(SetupStep.TIMES) },
    )
    when {
        d.usesRotation -> SummaryCard("순서", orderLine(d.rotation, types), onFix = { vm.fix(SetupStep.ORDER) }) {
            BadgeStrip(d.rotation, types, Modifier.padding(top = 6.dp), size = 20.dp)
        }
        d.style == WorkStyle.SHIFT -> SummaryCard(
            "근무표",
            if (d.painted.isEmpty()) "아직 안 칠했어요. 달력에서 언제든 칠할 수 있어요" else "칠한 날 ${d.painted.size}일",
            onFix = { vm.fix(SetupStep.ORDER) },
        )
        d.style == WorkStyle.HOURLY && d.noFixedDays -> SummaryCard("요일", "그때그때 달라요. 일한 날 달력에 적어요", onFix = { vm.fix(SetupStep.ORDER) })
        else -> SummaryCard("요일", weekLine(d), onFix = { vm.fix(SetupStep.ORDER) })
    }
    if (d.usesRotation) {
        val date = vm.questionDate(d)
        todaySentence(d, date, vm.today)?.let { SummaryCard("오늘", it, onFix = { vm.fix(SetupStep.TODAY) }) }
    }
    val first = remember(d) { vm.firstAlarm(d) }
    SummaryCard(
        "알람",
        buildString {
            append(alarmLine(d))
            first?.let { a ->
                append("\n첫 알람: ${relativeDay(a.triggerAt.toLocalDate(), vm.today)} ${Fmt.time(a.triggerAt.toLocalTime())}")
                if (a.shiftName.isNotEmpty()) append(" · ${a.shiftName}")
            }
        },
        onFix = { vm.fix(SetupStep.ALARM) },
    )
}

@Composable
private fun SummaryCard(title: String, text: String, onFix: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onFix) { Text("고치기") }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
            extra?.invoke()
        }
    }
}

private fun companyLine(d: SetupDraft, source: SetupSource?): String = buildList {
    if (d.companyName.isNotBlank()) add(d.companyName.trim())
    when (source?.kind) {
        SetupSource.Kind.TEMPLATE -> add("${source.name}${Fmt.ro(source.name)} 시작 (업종 일반형)")
        SetupSource.Kind.SERVER -> add("같은 회사 설정: ${source.name}${source.dept.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
        SetupSource.Kind.FILE -> add("동료가 보낸 설정" + source.name.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
        null -> Unit
    }
}.joinToString("\n")

/** "월·화·수·목·금 주간" or "월 오전 알바 · 수 저녁 알바". */
private fun weekLine(d: SetupDraft): String {
    val days = java.time.DayOfWeek.entries.mapIndexedNotNull { i, day ->
        d.weekly.getOrNull(i)?.takeIf { !d.isOff(it) }?.let { day to d.type(it)!! }
    }
    if (days.isEmpty()) return "일하는 요일이 없어요"
    val one = days.map { it.second.id }.distinct().size == 1
    return if (one) days.joinToString("·") { Fmt.dayOfWeekShort(it.first) } + " " + days.first().second.name
    else days.joinToString(" · ") { "${Fmt.dayOfWeekShort(it.first)} ${it.second.name}" }
}

private fun alarmLine(d: SetupDraft): String {
    val work = d.workTypes
    if (d.alarmByType.isEmpty()) return d.alarmMinutes?.let { "근무 ${Fmt.offset(it)}에 울려요" } ?: "알람은 안 써요"
    return work.joinToString(" · ") { t -> "${t.name} " + (d.alarmFor(t)?.let { Fmt.offset(it) } ?: "안 씀") }
}

/** [SETUP-01] Once, right after the first setup: the expected pay is offered next, never forced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPaySheet(companyPay: Boolean, onNow: () -> Unit, onLater: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onLater, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Payments, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text("달력을 채웠어요. 예상 급여도 볼까요?", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (companyPay) "회사 급여 방식은 함께 받아 왔어요. 내 기본급이나 시급만 넣으면 이번 달 예상 급여를 보여 드려요."
                else "기본급이나 시급만 알면 이번 달 예상 급여를 보여 드려요. 나머지는 나중에 채워도 돼요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onNow, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("지금 할게요") }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text("나중에") }
        }
    }
}
