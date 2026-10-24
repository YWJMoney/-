package com.bamnat.app.ui.memo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.rememberNotificationAsk
import com.bamnat.core.memo.MemoReminder
import com.bamnat.core.memo.MemoTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import com.bamnat.core.time.Today

/** [SCHED-01] "10월 20일 (화) 오후 3:00" */
internal fun scheduleText(at: LocalDateTime): String {
    val h = at.hour % 12
    val time = "${if (at.hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}:%02d".format(at.minute)
    return "${Fmt.dayTitle(at.toLocalDate())} $time"
}

/** [SCHED-01] How a schedule is brought up, in a few words: "알람·알림", "알람", "알림". */
internal fun scheduleWays(r: MemoReminder): String = when {
    r.ring && r.notify -> "울리는 알람·알림 메시지"
    r.ring -> "울리는 알람"
    else -> "알림 메시지"
}

/** [SCHED-01] The schedule in the editor's chip row: tap to change, ✕ to make it a plain memo again. */
@Composable
internal fun ScheduleChip(reminder: MemoReminder?, onOpen: () -> Unit, onClear: () -> Unit) {
    AssistChip(
        onClick = onOpen,
        label = { Text(reminder?.let { "${scheduleText(it.at)} · ${if (it.ring) "알람" else "알림"}" } ?: "알람 맞추기") },
        leadingIcon = {
            Icon(if (reminder?.ring == true) Icons.Filled.Alarm else Icons.Filled.EventAvailable, null, Modifier.size(18.dp))
        },
        trailingIcon = if (reminder != null) {
            { Icon(Icons.Filled.Close, "일정 빼기", Modifier.size(18.dp).clickable(role = Role.Button) { onClear() }) }
        } else {
            null
        },
    )
}

/**
 * [SCHED-01] Under the memo when it names a time: "이 메모, 일정으로 넣을까요?". It asks once per time;
 * when the memo is a schedule already and the written time changed, it offers the new time.
 */
@Composable
internal fun ScheduleOffer(found: MemoTime.Found, changing: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.EventAvailable, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    if (changing) "일정 시각을 ‘${found.phrase}’로 바꿀까요?" else "‘${found.phrase}’, 일정으로 넣을까요?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                if (changing) "${scheduleText(found.at)}에 알려 드릴게요."
                else "${scheduleText(found.at)}에 알람을 울리거나 알림 메시지로 알려 드릴 수 있어요. 메모는 그대로 남아요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDecline) { Text(if (changing) "그대로 둘게요" else "메모로 둘게요") }
                FilledTonalButton(onClick = onAccept) { Text(if (changing) "바꾸기" else "일정으로 넣기") }
            }
        }
    }
}

/**
 * [SCHED-01] When, and how to be told: 울리는 알람 (rings like the shift alarm, over the lock screen)
 * and/or 알림 메시지 (a message in the notification shade). Says what is turned off on the phone that
 * the chosen way needs, with the button to turn it on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDialog(
    initial: LocalDateTime,
    current: MemoReminder?,
    onDismiss: () -> Unit,
    onSave: (MemoReminder) -> Unit,
    now: () -> LocalDateTime = Today::dateTime,
) {
    val context = LocalContext.current
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate()) }
    var time by rememberSaveable { mutableStateOf(initial.toLocalTime().withSecond(0).withNano(0)) }
    var ring by rememberSaveable { mutableStateOf(current?.ring ?: false) }
    var notify by rememberSaveable { mutableStateOf(current?.notify ?: true) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    // Read again when the user comes back from the phone's settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationsOk = remember(refresh) { Notifications.canPost(context) }
    val fullScreenOk = remember(refresh) { Notifications.canUseFullScreen(context) }
    val askNotifications = rememberNotificationAsk()
    val at = date.atTime(time)
    val ahead = at.isAfter(now())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.EventAvailable, null) },
        title = { Text(if (current == null) "일정으로 넣기" else "일정 바꾸기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(Fmt.dayTitle(date)) }
                    OutlinedButton(onClick = { pickTime = true }) { Text(scheduleText(at).substringAfter(") ")) }
                }
                if (!ahead) {
                    Text("지난 시각이에요. 날짜나 시각을 바꿔 주세요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Text("어떻게 알려 드릴까요?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                WayRow(
                    checked = ring,
                    icon = { Icon(Icons.Filled.Alarm, null) },
                    title = "울리는 알람",
                    text = "근무 알람처럼 소리와 진동으로 울려요. 끄거나 다시 울림을 누를 때까지 울려요",
                ) { ring = it }
                WayRow(
                    checked = notify,
                    icon = { Icon(Icons.Filled.NotificationsActive, null) },
                    title = "알림 메시지",
                    text = "휴대폰 알림창에 메시지로 띄워요. 소리는 휴대폰 알림 설정을 따라요",
                ) { notify = it }
                if (!ring && !notify) {
                    Text("하나는 골라 주세요. 둘 다 고를 수도 있어요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if ((notify || ring) && !notificationsOk) {
                    Fix("알림이 꺼져 있어서 지금은 알려 드릴 수 없어요.", "알림 허용하기", askNotifications)
                } else if (ring && !fullScreenOk) {
                    Fix("‘잠금 화면에 알람 띄우기’가 꺼져 있어서 잠금 화면에서는 알림으로만 울려요.", "허용하기") {
                        if (Build.VERSION.SDK_INT >= 34) SystemSettings.open(
                            context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(MemoReminder(at, ring, notify)) }, enabled = ahead && (ring || notify)) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("취소") } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        TimePickerDialog(title = "일정 시각", initial = time, onDismiss = { pickTime = false }) {
            time = it
            pickTime = false
        }
    }
}

@Composable
private fun WayRow(checked: Boolean, icon: @Composable () -> Unit, title: String, text: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fix(text: String, label: String, onFix: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onFix) { Text(label) }
    }
}

/** [SCHED-01] Where the dialog starts: the found time, the schedule already set, or the next round hour. */
internal fun scheduleStart(found: MemoTime.Found?, current: MemoReminder?, date: LocalDate?, now: LocalDateTime): LocalDateTime =
    found?.at ?: current?.at ?: run {
        val nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        if (date != null && date.isAfter(now.toLocalDate())) date.atTime(LocalTime.of(9, 0)) else nextHour
    }
