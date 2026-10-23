package com.bamnat.app.ui.diag

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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

/** How an [AlarmCheck] is put right. */
sealed interface Fix {
    data object TurnOn : Fix
    data object Repair : Fix
    data class Open(val intent: Intent) : Fix
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
        return buildList {
            add(AlarmCheck("알람 사용", settings.alarmsEnabled, "꺼져 있으면 근무 알람이 하나도 울리지 않아요.", "켜기", Fix.TurnOn))
            add(AlarmCheck("알림 허용", Notifications.canPost(context), "알람이 울릴 때 끄기·다시 울림 버튼이 이 알림에 있어요.", "알림 설정 열기",
                Fix.Open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))))
            if (Build.VERSION.SDK_INT >= 31) add(AlarmCheck("정확한 알람", container.alarmScheduler.canScheduleExact(),
                "허용하지 않으면 알람이 몇 분 늦을 수 있어요.", "허용하러 가기", Fix.Open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))))
            if (Build.VERSION.SDK_INT >= 34) add(AlarmCheck("잠금 화면 알람", Notifications.canUseFullScreen(context),
                "허용하면 화면이 꺼져 있어도 알람 화면이 바로 떠요.", "허용하러 가기", Fix.Open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))))
            add(AlarmCheck("배터리 최적화 제외", power?.isIgnoringBatteryOptimizations(context.packageName) == true,
                "목록에서 밤낮을 '제한 없음'으로 두면 오래 안 써도 알람이 늦지 않아요.", "배터리 설정 열기",
                Fix.Open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))))
            if (Build.VERSION.SDK_INT >= 28) add(AlarmCheck("백그라운드 제한 없음", am?.isBackgroundRestricted != true,
                "제한되어 있으면 알람이 늦거나 울리지 않을 수 있어요.", "앱 정보 열기", Fix.Open(details)))
            if (settings.alarmsEnabled && container.alarmScheduler.upcoming.value != null) add(AlarmCheck("다음 알람 등록",
                container.alarmScheduler.systemHolds(), "휴대폰이 알람을 잃어버렸으면 다시 걸어요.", "다시 걸기", Fix.Repair))
            // Samsung's 절전 앱 list puts apps to sleep that the system's own setting allows.
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) add(AlarmCheck("삼성 절전 예외", null,
                "설정 › 배터리 › 백그라운드 사용 한도에서 '절전 예외 앱'에 밤낮을 넣고, '사용하지 않는 앱을 절전 상태로 전환'을 꺼 주세요.",
                "설정 열기", Fix.Open(Intent(Settings.ACTION_SETTINGS))))
        }
    }

    fun turnOnAlarms() = container.settingsRepository.update { it.copy(alarmsEnabled = true) }

    // [STAB-01] The log waits for queued writes and reads the whole file, so never on the main thread.
    suspend fun events(): List<DiagEvent> = withContext(Dispatchers.IO) { container.telemetry.log.read() }
    fun clearLog(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.telemetry.log.clear() }
            then()
        }
    }

    /** [MON-02] Testers preview the screens after the launch here; null shows the real state. */
    val gate = container.featureGate.state
    val previewTier = container.settingsRepository.settings.map { it.previewTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.settingsRepository.current.previewTier)
    fun setPreview(t: Tier?) = container.settingsRepository.update { it.copy(previewTier = t) }

    fun report(context: Context, events: List<DiagEvent>): DiagReport {
        val now = System.currentTimeMillis()
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
            "시간대: ${ZoneId.systemDefault()}",
        )
        val usage = EventBook.counts(events, now - 30L * 24 * 60 * 60 * 1000).map { (name, n) -> "$name: $n" }
            .ifEmpty { listOf("기록 없음") }
        val recent = events.takeLast(RECENT).asReversed().map { "${stamp(it.atMillis)}  ${it.name}  ${readable(it.detail)}" }
            .ifEmpty { listOf("기록 없음") }
        return DiagReport(
            listOf(
                "알람 상태" to alarm,
                "권한" to permissions,
                "기기" to device,
                "최근 30일 사용량" to usage,
                "최근 이벤트 ${minOf(events.size, RECENT)}개" to recent,
            ),
        )
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
        private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        fun stamp(millis: Long): String = FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        private val CLOCK = DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)
        /** "오후 3:41", for the test alarm. */
        fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
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
    val testAt = remember(testSet, refresh) { testSet?.takeIf { it + 2 * 60_000L > System.currentTimeMillis() } }
    val checks = remember(refresh, upcoming) { vm.checks(context) }
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
                        if (testAt == null) Button(onClick = { vm.scheduleTest() }) { Text("1분 뒤 울려 보기") }
                        else OutlinedButton(onClick = { vm.cancelTest() }) { Text("시험 알람 취소") }
                    }
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
