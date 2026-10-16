package com.bamnat.app.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.UpcomingAlarm
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ThemeMode
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.schedule.IcsWriter
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.SoundPickerRow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import android.widget.Toast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewModelScope
import com.bamnat.app.data.BackupException
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.BackupSummary
import com.bamnat.app.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val upcoming: StateFlow<UpcomingAlarm?> = container.alarmScheduler.upcoming
    val snoozeAt: StateFlow<Long?> = container.alarmScheduler.snoozeAt
    fun cancelSnooze() = container.alarmScheduler.cancelSnooze()
    fun update(transform: (AppSettings) -> AppSettings) = container.settingsRepository.update(transform)
    fun canScheduleExact() = container.alarmScheduler.canScheduleExact()

    fun setAlarmsEnabled(on: Boolean) {
        update { it.copy(alarmsEnabled = on) }
        if (!on) container.alarmScheduler.cancelSnooze()
    }

    private val _backupMessage = MutableStateFlow<String?>(null)
    /** Result of the last backup or restore, shown once. */
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()
    fun consumeBackupMessage() {
        _backupMessage.value = null
    }

    /** A backup read and checked, waiting for the user to confirm the restore. */
    private val _pendingRestore = MutableStateFlow<Pair<String, BackupSummary>?>(null)
    val pendingRestore: StateFlow<Pair<String, BackupSummary>?> = _pendingRestore.asStateFlow()

    fun exportTo(context: Context, uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = runCatching {
                val json = container.backupManager.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
                container.telemetry.event(DiagEvents.BACKUP_EXPORT)
                "백업 파일을 저장했어요"
            }.getOrElse { "백업을 저장하지 못했어요" }
        }
    }

    /** Writes work days from three months ago to a year ahead as a calendar file [REL-04]. */
    fun exportIcs(context: Context, uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = runCatching {
                val snapshot = container.scheduleRepository.snapshot.first()
                val (from, to) = IcsWriter.defaultRange(LocalDate.now())
                val ics = withContext(Dispatchers.Default) {
                    IcsWriter.write(snapshot.resolver.resolveRange(from, to), ZoneId.systemDefault())
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ics.toByteArray()) }
                }
                container.telemetry.event(DiagEvents.ICS_EXPORT)
                "캘린더 파일을 저장했어요. 구글·삼성 캘린더에서 가져오기로 불러오세요"
            }.getOrElse { "캘린더 파일을 저장하지 못했어요" }
        }
    }

    fun readBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                _pendingRestore.value = json to container.backupManager.summarize(json)
            }.onFailure { e ->
                _backupMessage.value = (e as? BackupException)?.message ?: "백업 파일을 읽을 수 없어요"
            }
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val json = _pendingRestore.value?.first ?: return
        _pendingRestore.value = null
        container.appScope.launch {
            _backupMessage.value = runCatching {
                container.backupManager.restore(json)
                container.telemetry.event(DiagEvents.BACKUP_RESTORE)
                "백업을 불러왔어요"
            }.getOrElse { e -> (e as? BackupException)?.message ?: "백업을 불러오지 못했어요. 기존 데이터는 그대로예요" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenShiftTypes: () -> Unit,
    onOpenPattern: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorkStyle: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val snoozeAt by vm.snoozeAt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission states can change in system settings; re-check whenever we come back.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationsOk = remember(refresh) { Notifications.canPost(context) }
    val exactOk = remember(refresh) { vm.canScheduleExact() }
    val fullScreenOk = remember(refresh) { Notifications.canUseFullScreen(context) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri -> if (uri != null) vm.exportTo(context, uri) }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.exportIcs(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readBackup(context, uri)
    }
    val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeBackupMessage()
        }
    }
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    pendingRestore?.let { (_, summary) ->
        ConfirmDialog(
            title = "백업을 불러올까요?",
            message = "들어 있는 것: 근무 유형 ${summary.shiftTypes}개, 바꾼 날짜 ${summary.days}일, 메모 ${summary.memos}개" +
                (if (summary.moneyLines > 0) ", 돈 기록 ${summary.moneyLines}건" else "") +
                (if (summary.exportedAtMillis > 0) " (${Fmt.dayTitle(AlarmScheduler.millisToLocal(summary.exportedAtMillis).toLocalDate())} 저장)" else "") +
                ".\n지금 앱에 있는 데이터는 이 백업으로 바뀌어요.",
            confirmLabel = "불러오기",
            destructive = true,
            onDismiss = vm::cancelRestore,
            onConfirm = vm::confirmRestore,
        )
    }

    // After two denials Android stops showing the dialog, so a refusal opens the app's settings instead.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openAppNotificationSettings(context)
        refresh++
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "근무") {
            // [WORK-01] Changing the kind of work keeps every record; it only changes what screens show.
            NavRow("근무 형태", settings.workStyle?.label ?: "정하지 않음", onOpenWorkStyle)
            HorizontalDivider()
            NavRow("근무 패턴", "교대 순서, 요일별 근무, 시작일", onOpenPattern)
            HorizontalDivider()
            NavRow("근무 유형", "이름, 시간, 색상, 근무별 알람", onOpenShiftTypes)
        }

        SectionCard(title = "알람") {
            SwitchRow(
                "알람 사용",
                if (settings.alarmsEnabled) "근무 알람과 날짜별로 정한 알람이 울려요"
                else "모든 알람이 꺼져 있어요. 알람 설정은 그대로 남아요",
                settings.alarmsEnabled,
            ) { on -> vm.setAlarmsEnabled(on) }
            val next = upcoming
            Text(
                when {
                    !settings.alarmsEnabled -> "울릴 알람이 없어요"
                    next == null -> "예정된 알람이 없어요"
                    else -> "다음 알람: ${Fmt.dayTitle(AlarmScheduler.millisToLocal(next.triggerAtMillis).toLocalDate())} " +
                        "${Fmt.time(AlarmScheduler.millisToLocal(next.triggerAtMillis).toLocalTime())} · " +
                        next.plan.shiftName.ifBlank { "직접 정한 알람" }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.alarmsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snoozeAt?.let { at ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "다시 알림 ${Fmt.time(AlarmScheduler.millisToLocal(at).toLocalTime())}에 울려요",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::cancelSnooze) { Text("취소") }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            PermissionRow(
                ok = notificationsOk,
                title = "알림 허용",
                fixLabel = "허용",
                onFix = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                },
            )
            PermissionRow(
                ok = exactOk,
                title = "정확한 시간에 울리기",
                fixLabel = "설정",
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        launchSafely(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")))
                    }
                },
            )
            PermissionRow(
                ok = fullScreenOk,
                title = "잠금 화면에 알람 띄우기",
                fixLabel = "설정",
                onFix = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        launchSafely(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}")))
                    }
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SoundPickerRow(
                sound = settings.defaultAlarmSound,
                defaultLabel = "휴대폰 기본 알람음",
                onPicked = { s -> vm.update { it.copy(defaultAlarmSound = s) } },
            )
            Text(
                "근무 유형마다 다른 소리를 정할 수도 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            SwitchRow("진동", "소리와 함께 진동", settings.vibrate) { v -> vm.update { it.copy(vibrate = v) } }
            SwitchRow("점점 크게", "작은 소리로 시작해 20초 동안 커져요", settings.gradualVolume) { v ->
                vm.update { it.copy(gradualVolume = v) }
            }
            Text("다시 알림 간격", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(3, 5, 10, 15, 20)) {
                    FilterChip(
                        selected = settings.snoozeMinutes == m,
                        onClick = { vm.update { it.copy(snoozeMinutes = m) } },
                        label = { Text("${m}분") },
                    )
                }
            }
            Text("자동으로 끄기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(1, 3, 5, 10, 20, 30)) {
                    FilterChip(
                        selected = settings.ringMinutes == m,
                        onClick = { vm.update { it.copy(ringMinutes = m) } },
                        label = { Text("${m}분 후") },
                    )
                }
            }
            SwitchRow(
                "날짜 알람 저장 안내",
                "달력에서 날짜 알람을 저장할 때 어떤 알람이 울리는지 알려 줘요",
                !settings.hideDayAlarmNotice,
            ) { on -> vm.update { it.copy(hideDayAlarmNotice = !on) } }
        }

        SectionCard(title = "백업") {
            Text(
                "근무 유형, 패턴, 바꾼 날짜, 메모, 설정을 파일 하나로 저장해요. 휴대폰을 바꾸거나 앱을 다시 설치할 때 불러오세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    exportLauncher.launch("밤낮-백업-${LocalDate.now()}.json")
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Upload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("백업 저장")
                }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf(BackupManager.MIME_TYPE, "text/plain", "application/octet-stream"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text("불러오기")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavRow(
                "캘린더 파일로 내보내기",
                "근무를 .ics 파일로 저장해 구글·삼성 캘린더에 넣어요 (3개월 전부터 1년 뒤까지)",
            ) { icsLauncher.launch("밤낮-근무-${LocalDate.now()}.ics") }
        }

        SectionCard(title = "근무시간 계산") {
            Text("한 주의 시작", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.weekStart == DayOfWeek.MONDAY,
                    onClick = { vm.update { it.copy(weekStart = DayOfWeek.MONDAY) } },
                    label = { Text("월요일") },
                )
                FilterChip(
                    selected = settings.weekStart == DayOfWeek.SUNDAY,
                    onClick = { vm.update { it.copy(weekStart = DayOfWeek.SUNDAY) } },
                    label = { Text("일요일") },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LimitRow(
                title = "주 근무 경고",
                subtitle = "한 주가 이 시간을 넘으면 빨갛게 표시",
                hours = settings.weeklyLimitHours,
            ) { h -> vm.update { it.copy(weeklyLimitHours = h) } }
            LimitRow(
                title = "3개월 평균 경고",
                subtitle = "3개월 주평균이 이 시간을 넘으면 경고",
                hours = settings.averageLimitHours,
            ) { h -> vm.update { it.copy(averageLimitHours = h) } }
            TextButton(onClick = {
                vm.update { it.copy(weeklyLimitHours = AppSettings().weeklyLimitHours, averageLimitHours = AppSettings().averageLimitHours) }
            }) { Text("기본값 (주 64시간 · 평균 52시간)") }
        }

        SectionCard(title = "화면") {
            Text("테마", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label) in listOf(ThemeMode.SYSTEM to "시스템", ThemeMode.LIGHT to "밝게", ThemeMode.DARK to "어둡게")) {
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { vm.update { it.copy(themeMode = mode) } },
                        label = { Text(label) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("배경화면 색 사용", "Material You 색상", settings.dynamicColor) { v ->
                    vm.update { it.copy(dynamicColor = v) }
                }
            }
            SwitchRow("달력에 근무시간 표시", "날짜 칸 아래에 8h처럼 표시", settings.showHoursInCells) { v ->
                vm.update { it.copy(showHoursInCells = v) }
            }
            SwitchRow("음력 날짜 표시", "날짜 칸 아래에 음력(8.24)을 근무시간 대신 보여줘요", settings.showLunar) { v ->
                vm.update { it.copy(showLunar = v) }
            }
        }

        SectionCard(title = "개인정보") {
            SwitchRow(
                "사용 통계 보내기",
                "어떤 기능을 얼마나 쓰는지 익명으로 보내 앱 개선에 써요. 메모와 근무 내용은 보내지 않아요",
                settings.shareUsageStats,
            ) { v -> vm.update { it.copy(shareUsageStats = v) } }
        }

        // Seven taps on the version open the hidden alarm diagnostics [MEAS-04].
        var versionTaps by remember { mutableIntStateOf(0) }
        Text(
            "밤낮 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable {
                    versionTaps++
                    if (versionTaps >= 7) {
                        versionTaps = 0
                        onOpenDiagnostics()
                    }
                }
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(ok: Boolean, title: String, fixLabel: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (ok) "허용됨" else "허용 안 됨",
            tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text(fixLabel) }
    }
}

@Composable
private fun LimitRow(title: String, subtitle: String, hours: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MinutesStepper(
            minutes = hours * 60,
            onChange = { m -> onChange((m / 60).coerceIn(1, 168)) },
            step = 60,
            max = 168 * 60,
            dialogTitle = title,
            modifier = Modifier.widthIn(min = 0.dp),
        )
    }
}

private fun openAppNotificationSettings(context: Context) {
    launchSafely(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}

private fun launchSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
