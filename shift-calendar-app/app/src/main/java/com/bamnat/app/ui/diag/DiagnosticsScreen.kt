package com.bamnat.app.ui.diag

import com.bamnat.app.diag.CrashLog
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import com.bamnat.app.ui.settings.InquiryStart
import com.bamnat.app.ui.settings.InquiryLinks
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.ui.diag.DiagnosticsViewModel.Companion.clock
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.alarm.AlarmScheduler
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.EventBook
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.bamnat.core.time.Today

/** How an [AlarmCheck] is put right. */
sealed interface Fix {
    data object TurnOn : Fix
    data object Repair : Fix
    data class Open(val intent: Intent) : Fix
    /** [ALRM-01] The first of these the phone has; Samsung's own battery screens, then the system's. */
    data class OpenFirst(val intents: List<Intent>) : Fix
    /** [ALRM-01] The alarm volume put back to 70%. */
    data object AlarmVolume : Fix
}

/** One thing alarms need. [ok] is null for advice the app cannot read, such as Samsung's 절전 앱 list. */
data class AlarmCheck(val title: String, val ok: Boolean?, val why: String, val fixLabel: String, val fix: Fix)

/** A plain-text report of everything on the diagnostics screen, for pasting into a bug report. */
data class DiagReport(val sections: List<Pair<String, List<String>>>) {
    fun asText(): String = sections.joinToString("\n\n") { (title, lines) -> "[$title]\n" + lines.joinToString("\n") }
}

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    val upcoming = container.alarmScheduler.upcoming
    val snoozeAt = container.alarmScheduler.snoozeAt
    val testAt = container.alarmScheduler.testAt
    fun scheduleTest() = container.alarmScheduler.scheduleTest()

    /** [QA-07] 330: 데이터 점검's lines, null before the first run. */
    private val _dataCheck = kotlinx.coroutines.flow.MutableStateFlow<List<com.bamnat.app.diag.DataCheck.Line>?>(null)
    val dataCheck: kotlinx.coroutines.flow.StateFlow<List<com.bamnat.app.diag.DataCheck.Line>?> = _dataCheck
    private val _checking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val checking: kotlinx.coroutines.flow.StateFlow<Boolean> = _checking

    fun runDataCheck() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            _dataCheck.value = runCatching { com.bamnat.app.diag.DataCheck.run(container) }
                .getOrElse { listOf(com.bamnat.app.diag.DataCheck.Line(false, "점검하지 못했어요 (${it.javaClass.simpleName})")) }
            _checking.value = false
        }
    }
    fun cancelTest() = container.alarmScheduler.cancelTest()
    /** [REL-13] Registers the next alarm again when the system lost it. */
    fun repair() = container.verifyAlarms()

    /**
     * [REL-13] The things an alarm needs, each with whether it is fine now and how to fix it.
     * Shown first so a user can put everything right without reading the log.
     */
    fun checks(context: Context): List<AlarmCheck> {
        val settings = container.settingsRepository.current
        val power = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val pkg = Uri.parse("package:${context.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val channel = { id: String -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id) }
        return buildList {
            add(AlarmCheck("알람 사용", settings.alarmsEnabled, "꺼져 있으면 근무 알람이 하나도 울리지 않아요.", "켜기", Fix.TurnOn))
            // [ALRM-01] R11: without it the alarm still rings, but its buttons and 일어났나요? cannot show.
            add(AlarmCheck("알림 허용", Notifications.canPost(context),
                "꺼져 있으면 알람이 울려도 끄기·다시 울림 버튼이 안 보이고, ‘일어났나요?’도 묻지 못해 건너뛰어요.", "알림 설정 열기",
                Fix.Open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))))
            // [ALRM-01] R10: the alarm's channels can be turned off one by one in the system settings.
            if (Notifications.canPost(context)) {
                add(AlarmCheck("알람 알림 켜짐", Notifications.channelOn(context, Notifications.ALARM_CHANNEL_ID),
                    "휴대폰 설정에서 ‘근무 알람’ 알림이 꺼져 있어 알람 화면을 띄우지 못할 수 있어요.", "알람 알림 켜기",
                    Fix.Open(channel(Notifications.ALARM_CHANNEL_ID))))
                add(AlarmCheck("예비 소리 켜짐", Notifications.channelOn(context, Notifications.FALLBACK_CHANNEL_ID),
                    "알람 화면을 못 띄울 때 대신 울리는 ‘예비 소리’가 꺼져 있어요.", "예비 소리 켜기",
                    Fix.Open(channel(Notifications.FALLBACK_CHANNEL_ID))))
            }
            if (audio != null) add(AlarmCheck("알람 소리 크기", audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM) > 0,
                "휴대폰의 알람 소리가 0이라 울려도 들리지 않아요(진동만 와요).", "70%로 올리기", Fix.AlarmVolume))
            if (Build.VERSION.SDK_INT >= 31) add(AlarmCheck("정확한 알람", container.alarmScheduler.canScheduleExact(),
                "허용하지 않으면 알람이 몇 분 늦을 수 있어요.", "허용하러 가기", Fix.Open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))))
            if (Build.VERSION.SDK_INT >= 34) add(AlarmCheck("잠금 화면 알람", Notifications.canUseFullScreen(context),
                "허용하면 화면이 꺼져 있어도 알람 화면이 바로 떠요.", "허용하러 가기", Fix.Open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))))
            // [ALRM-01] R10: asked straight away (Play allows it for alarm apps), the list as a fallback.
            add(AlarmCheck("배터리 최적화 제외", power?.isIgnoringBatteryOptimizations(context.packageName) == true,
                "밤낮을 배터리 최적화에서 빼면 오래 안 써도 알람이 늦지 않아요.", "제외하기",
                Fix.OpenFirst(listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))))
            if (power?.isPowerSaveMode == true) add(AlarmCheck("절전 모드", false,
                "절전 모드가 켜져 있어 휴대폰이 앱을 더 자주 멈춰요. 알람은 울리지만 늦을 수 있어요.", "절전 설정 열기",
                Fix.Open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))))
            // [ALRM-01] M 4-1: the system's standby bucket; RARE and RESTRICTED hold alarms back.
            if (Build.VERSION.SDK_INT >= 28) {
                val bucket = context.getSystemService(android.app.usage.UsageStatsManager::class.java)?.appStandbyBucket
                if (bucket != null) add(AlarmCheck("앱 대기 상태", bucket < android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE,
                    "휴대폰이 밤낮을 ‘드물게 쓰는 앱’으로 분류했어요. 앱을 한 번 열고, 배터리 최적화에서 빼 주세요.", "앱 정보 열기", Fix.Open(details)))
            }
            if (Build.VERSION.SDK_INT >= 28) add(AlarmCheck("백그라운드 제한 없음", am?.isBackgroundRestricted != true,
                "제한되어 있으면 알람이 늦거나 울리지 않을 수 있어요.", "앱 정보 열기", Fix.Open(details)))
            if (settings.alarmsEnabled && container.alarmScheduler.upcoming.value != null) add(AlarmCheck("다음 알람 등록",
                container.alarmScheduler.systemHolds(), "휴대폰이 알람을 잃어버렸으면 다시 걸어요.", "다시 걸기", Fix.Repair))
            // Samsung's 절전 앱 list puts apps to sleep that the system's own setting allows.
            // [ALRM-01] R10: Samsung's own battery screen first, where 절전 예외 앱 is.
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) add(AlarmCheck("삼성 절전 예외", null,
                "설정 › 배터리 › 백그라운드 사용 한도에서 '절전 예외 앱'에 밤낮을 넣고, '사용하지 않는 앱을 절전 상태로 전환'을 꺼 주세요.",
                "배터리 설정 열기", Fix.OpenFirst(listOf(
                    Intent("com.samsung.android.sm.ACTION_BATTERY"),
                    Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    Intent(Settings.ACTION_SETTINGS),
                ))))
        }
    }

    fun turnOnAlarms() = container.settingsRepository.update { it.copy(alarmsEnabled = true) }

    /** [ALRM-01] I24: the test alarm three minutes on, to wait with the screen off as at night. */
    fun scheduleScreenOffTest() = container.alarmScheduler.scheduleTest(minutes = SCREEN_OFF_MINUTES)

    /** [ALRM-01] R10: 알람 소리 크기 put back to 70% of the phone's highest. */
    fun alarmVolume(context: Context) {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, (max * 0.7).toInt().coerceAtLeast(1), 0) }
    }

    // [STAB-01] The log waits for queued writes and reads the whole file, so never on the main thread.
    suspend fun events(): List<DiagEvent> = withContext(Dispatchers.IO) {
        crashLines = CrashLog.reportLines(container.appContext)
        container.telemetry.log.read()
    }

    /** [MEAS-08] Read with the log, off the main thread; [report] shows them. */
    @Volatile private var crashLines: List<String>? = null
    fun clearLog(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.telemetry.log.clear()
                CrashLog.clear(container.appContext)
            }
            then()
        }
    }

    /** [MON-02] Testers preview the screens after the launch here; null shows the real state. */
    val gate = container.featureGate.state
    val previewTier = container.settingsRepository.settings.map { it.previewTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.settingsRepository.current.previewTier)
    fun setPreview(t: Tier?) = container.settingsRepository.update { it.copy(previewTier = t) }

    fun report(context: Context, events: List<DiagEvent>): DiagReport {
        val now = Today.millis()
        val settings = container.settingsRepository.current
        val next = upcoming.value
        val power = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val alarm = buildList {
            add("알람 사용: ${onOff(settings.alarmsEnabled)}")
            add("다음 알람: " + (next?.let { "${stamp(it.triggerAtMillis)} · ${it.plan.shiftName.ifBlank { "이 날 알람" }} · " +
                if (it.exact) "정확" else "근사(정확한 알람 권한 없음)" } ?: "없음"))
            add("다시 울림: " + (snoozeAt.value?.let(::stamp) ?: "없음"))
            val missed = EventBook.possiblyMissed(events, now)
            add("놓쳤을 수 있는 알람: " + if (missed.isEmpty()) "없음" else missed.joinToString { stamp(it) })
            // [ALRM-01] M 4-1: how late alarms were over 30 days, and whether the system holds the next one.
            val lates = events.filter { it.name == com.bamnat.core.diag.DiagEvents.ALARM_FIRED && it.atMillis >= now - 30L * DAY_MILLIS }
                .mapNotNull { EventBook.lateSeconds(it.detail) }
            if (lates.isNotEmpty()) add("최근 30일 울린 때: 1분 안 ${lates.count { it < 60 }} · 1~5분 늦음 ${lates.count { it in 60..299 }} · " +
                "5분 넘게 늦음 ${lates.count { it >= 300 }}")
            if (settings.alarmsEnabled && next != null) add("휴대폰에 걸린 다음 알람: " + if (container.alarmScheduler.systemHolds()) "맞음" else "없거나 다름")
            // A plain check, which lint reads (Android 9+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(android.app.usage.UsageStatsManager::class.java)?.let {
                    add("앱 대기 상태: " + bucketName(it.appStandbyBucket))
                }
            }
        }
        val permissions = buildList {
            add("알림: ${okNo(Notifications.canPost(context))}")
            add("정확한 알람: ${okNo(container.alarmScheduler.canScheduleExact())}")
            add("잠금 화면 알람: ${okNo(Notifications.canUseFullScreen(context))}")
            add("배터리 최적화 제외: ${okNo(power?.isIgnoringBatteryOptimizations(context.packageName) == true)}")
            if (Build.VERSION.SDK_INT >= 28) {
                add("백그라운드 제한: ${if (am?.isBackgroundRestricted == true) "제한됨 (알람이 늦을 수 있음)" else "없음"}")
            }
        }
        val device = listOf(
            "앱: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "기기: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "시간대: ${Today.zone()}",
        )
        val usage = EventBook.counts(events, now - 30L * 24 * 60 * 60 * 1000).map { (name, n) -> "$name: $n" }
            .ifEmpty { listOf("기록 없음") }
        val recent = events.takeLast(RECENT).asReversed().map { "${stamp(it.atMillis)}  ${it.name}  ${readable(it.detail)}" }
            .ifEmpty { listOf("기록 없음") }
        val crashes = crashLines ?: listOf("기록 없음")
        return DiagReport(
            listOf(
                "알람 상태" to alarm,
                "권한" to permissions,
                "기기" to device,
                "최근 30일 사용량" to usage,
                // [MEAS-08] 328: the person sees what stopped before "점검 내용과 함께 문의하기" sends it.
                "앱 멈춤" to crashes,
                "최근 이벤트 ${minOf(events.size, RECENT)}개" to recent,
            ),
        )
    }

    private fun bucketName(b: Int) = when {
        b <= 10 -> "자주 씀"
        b <= 20 -> "가끔 씀"
        b <= 30 -> "드물게(자주 쓰지 않음)"
        b <= 40 -> "아주 드물게 씀 (알람이 늦을 수 있음)"
        else -> "제한됨 (알람이 늦거나 안 울릴 수 있음)"
    }

    private fun onOff(b: Boolean) = if (b) "켜짐" else "꺼짐"
    private fun okNo(b: Boolean) = if (b) "허용" else "허용 안 됨"

    /** "at=<millis>" is for matching; show it as a time. */
    private fun readable(detail: String): String =
        detail.split(' ').joinToString(" ") { part ->
            EventBook.triggerOf(part)?.let { "알람 ${stamp(it)}" } ?: part
        }

    companion object {
        const val RECENT = 100
        const val SCREEN_OFF_MINUTES = 3
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        fun stamp(millis: Long): String = FORMAT.format(Instant.ofEpochMilli(millis).atZone(Today.zone()))
        private val CLOCK = DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)
        /** "오후 3:41", for the test alarm. */
        fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(Today.zone()))
    }
}

/**
 * Alarm diagnostics [MEAS-04, OPS-19, SHR-08]: why an alarm may not ring, and a report to copy.
 * [SET-01] Reached from settings › 알람 and the version line; the testers' tier switch shows only when it was
 * opened by tapping the version seven times ([testing]), or in a debug build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel, onBack: () -> Unit, testing: Boolean = false) {
    val context = LocalContext.current
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val snoozeAt by vm.snoozeAt.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val events by produceState<List<DiagEvent>?>(null, refresh) { value = vm.events() }
    val report = remember(events, upcoming, snoozeAt) { vm.report(context, events.orEmpty()) }
    val testSet by vm.testAt.collectAsStateWithLifecycle()
    // [REL-13] A test time long past is over (the alarm was lost, say by a reboot): it can be tried again.
    val testAt = remember(testSet, refresh) { testSet?.takeIf { it + 2 * 60_000L > Today.millis() } }
    // [ALRM-01] R11: a missing notification permission goes first, in red.
    val checks = remember(refresh, upcoming) { vm.checks(context).sortedBy { if (it.title == "알림 허용" && it.ok == false) 0 else 1 } }
    val week = remember(events) { events?.let { EventBook.week(it, Today.millis()) } }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("알람 점검") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("밤낮 알람 점검", report.asText()))
                        // Android 13+ shows its own confirmation.
                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.ContentCopy, "보고서 복사") }
                    // [SRV-10] N6: the same report to any app (메신저, 메일) as text.
                    IconButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "밤낮 알람 점검")
                            .putExtra(android.content.Intent.EXTRA_TEXT, report.asText())
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "알람 점검 보내기")) }
                    }) { Icon(Icons.Filled.Share, "보고서 보내기") }
                    TextButton(onClick = { confirmClear = true }) { Text("기록 지우기") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "알람이 안 울리거나 늦게 울릴 때 원인을 찾는 화면이에요. 오른쪽 위 복사 버튼으로 내용을 복사해 문의에 붙여 주세요. " +
                        "기록은 이 휴대폰에만 있고 메모와 금액은 담지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // [ALRM-01] I21: the last seven days first, so how alarms have been doing is the first thing read.
            week?.takeIf { it.planned > 0 }?.let { w ->
                item { AlarmWeekCard(w, events.orEmpty()) }
            }
            // [ALRM-02] I26: whether the servers answer, and what waits to be sent.
            item { ServerCheckCard() }
            item {
                SectionCard(title = "점검") {
                    val bad = checks.count { it.ok == false }
                    Text(
                        if (bad == 0) "알람이 울리는 데 필요한 것이 모두 준비됐어요." else "고칠 것이 ${bad}개 있어요. 버튼을 누르면 바로 그 화면이 열려요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (bad == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    for (check in checks) {
                        CheckRow(check) {
                            when (val f = check.fix) {
                                Fix.TurnOn -> vm.turnOnAlarms()
                                Fix.Repair -> vm.repair()
                                is Fix.Open -> SystemSettings.open(context, f.intent)
                                is Fix.OpenFirst -> openFirst(context, f.intents)
                                Fix.AlarmVolume -> vm.alarmVolume(context)
                            }
                            refresh++
                        }
                    }
                }
            }
            item {
                SectionCard(title = "시험 알람") {
                    Text(
                        testAt?.let { "${clock(it)}에 '${AlarmScheduler.TEST_NAME}'이 울려요. 화면을 끄고 기다려 보세요." }
                            ?: "1분 뒤에 근무 알람과 똑같이 울려요. 화면을 끄고 기다리면 잠금 화면에서도 울리는지 알 수 있어요. 근무 알람은 그대로예요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (testAt == null) {
                            Button(onClick = { vm.scheduleTest() }) { Text("1분 뒤 울려 보기") }
                            // [ALRM-01] I24: long enough for Samsung's power saving to act, as it would at night.
                            OutlinedButton(onClick = { vm.scheduleScreenOffTest() }) { Text("화면 끄고 3분 기다리기") }
                        } else OutlinedButton(onClick = { vm.cancelTest() }) { Text("시험 알람 취소") }
                    }
                    if (testAt == null) Text("‘화면 끄고 3분 기다리기’를 누른 뒤 전원 버튼으로 화면을 끄고 기다려 보세요. 울리지 않으면 위 점검의 빨간 줄을 고쳐 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (testing || BuildConfig.DEBUG) item {
                val preview by vm.previewTier.collectAsStateWithLifecycle()
                val gate by vm.gate.collectAsStateWithLifecycle()
                SectionCard(title = "출시 뒤 화면 미리 보기") {
                    // [MON-02] After the 정식 출시 switch only debug builds can preview; a release build shows the real state.
                    Text(if (gate.live && !gate.preview) "정식 출시를 해서 실제 등급으로 보여요. 미리 보기는 개발용 빌드에서만 돼요."
                        else "정식 출시 뒤 무료와 프로 화면이 어떻게 보이는지 미리 볼 수 있어요. 결제는 되지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("지금: ${gate.access.reason.words}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = preview == null, onClick = { vm.setPreview(null) }, label = { Text("실제") })
                        listOf(Tier.FREE, Tier.PRO).forEach { t ->
                            FilterChip(selected = preview == t, onClick = { vm.setPreview(t) }, label = { Text(t.label) })
                        }
                    }
                }
            }
            items(report.sections, key = { it.first }) { (title, lines) ->
                SectionCard(title = title) {
                    for (line in lines) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (title.startsWith("최근 이벤트")) FontFamily.Monospace else null,
                            )
                        }
                    }
                }
            }
            // [QA-07] 330: the person checks their own data, as the release list does before a phone move.
            item {
                val lines by vm.dataCheck.collectAsStateWithLifecycle()
                val checking by vm.checking.collectAsStateWithLifecycle()
                SectionCard(title = "데이터 점검") {
                    Text("근무·기록 데이터가 온전한지, 백업이 제대로 만들어지는지 휴대폰 안에서 살펴봐요. 아무것도 바꾸거나 보내지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lines?.forEach { line ->
                        Text((if (line.ok) "✓ " else "! ") + line.text, style = MaterialTheme.typography.bodySmall,
                            color = if (line.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { vm.runDataCheck() }, enabled = !checking) {
                        Text(if (checking) "점검하는 중…" else if (lines == null) "데이터 점검" else "다시 점검")
                    }
                }
            }
            // [UX-05] When the checks don't solve it, ask with this phone's alarm check attached.
            item {
                SectionCard(title = "그래도 안 울리면") {
                    Text("알람 점검 내용을 붙여 개발자에게 물어볼 수 있어요. 메모와 금액은 담지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        InquiryLinks.ask(InquiryStart(about = "알람", kind = InquiryKind.BUG, withCheck = true))
                    }) { Text("점검 내용과 함께 문의하기") }
                }
            }
            item { Spacer(Modifier.width(1.dp).padding(bottom = 24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 지울까요?") },
            text = { Text("이 화면의 이벤트 기록과 사용량 집계가 지워져요. 알람과 설정은 그대로예요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearLog { refresh++ }
                }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

/** [ALRM-01] Opens the first screen of [intents] this phone has; a phone with none of them gets the app's own page. */
internal fun openFirst(context: Context, intents: List<Intent>) {
    for (i in intents) {
        val ok = runCatching { context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
    SystemSettings.open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

/** [ALRM-01] I21 지난 7일 알람 성적표, with what to do about an alarm that may have been missed. */
@Composable
internal fun AlarmWeekCard(w: com.bamnat.core.diag.AlarmWeek, events: List<DiagEvent>) {
    SectionCard(title = "지난 7일 알람") {
        Text(
            "울릴 알람 ${w.planned}개 중 ${w.rang}개가 울렸어요." +
                (if (w.late > 0) " 늦은 것 ${w.late}개(평균 ${w.averageLateSeconds / 60}분 ${w.averageLateSeconds % 60}초)." else " 늦은 것은 없어요."),
            style = MaterialTheme.typography.bodyMedium,
            color = if (w.allRang && w.late == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        for (t in w.missed.take(3)) {
            // [ALRM-01] I22: the daily alive line tells a phone that was off from an app that was stopped.
            val why = if (EventBook.aliveBefore(events, t)) "앱이 멈춰 있었던 것 같아요. 배터리 설정을 봐 주세요."
                else "휴대폰이 꺼져 있었거나 오래 쓰지 않았던 것 같아요."
            Text("${DiagnosticsViewModel.stamp(t)} 알람이 울렸는지 기록이 없어요. $why",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (w.missed.isNotEmpty()) OutlinedButton(onClick = {
            InquiryLinks.ask(InquiryStart(about = "알람", kind = InquiryKind.BUG, withCheck = true))
        }) { Text("놓친 알람 문의로 보내기") }
    }
}

@Composable
private fun CheckRow(check: AlarmCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            when (check.ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.Filled.Info },
            contentDescription = when (check.ok) { true -> "됨"; false -> "고칠 것"; null -> "안내" },
            tint = when (check.ok) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp, end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.titleSmall)
            if (check.ok != true) {
                Text(check.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onFix, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(check.fixLabel) }
            }
        }
    }
}
