package com.bamnat.app.ui.diag

import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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

/** A plain-text report of everything on the diagnostics screen, for pasting into a bug report. */
data class DiagReport(val sections: List<Pair<String, List<String>>>) {
    fun asText(): String = sections.joinToString("\n\n") { (title, lines) -> "[$title]\n" + lines.joinToString("\n") }
}

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    val upcoming = container.alarmScheduler.upcoming
    val snoozeAt = container.alarmScheduler.snoozeAt

    // [STAB-01] The log waits for queued writes and reads the whole file, so never on the main thread.
    suspend fun events(): List<DiagEvent> = withContext(Dispatchers.IO) { container.telemetry.log.read() }
    fun clearLog(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.telemetry.log.clear() }
            then()
        }
    }

    /** Testers switch tiers here until billing exists [TIER-01]. */
    val tier = container.featureGate.tier
    fun setTier(t: Tier) = container.settingsRepository.update { it.copy(tier = t) }

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
            "사용 통계 보내기: ${onOff(settings.shareUsageStats)}",
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
    }
}

/**
 * Alarm diagnostics [MEAS-04, OPS-19, SHR-08]: why an alarm may not ring, and a report to copy.
 * [SET-01] Reached from settings › 알람 and 앱 정보; the testers' tier switch shows only when it was
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
                        "기록은 이 휴대폰에만 있고 메모나 근무 이름은 담지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (testing || BuildConfig.DEBUG) item {
                val tier by vm.tier.collectAsStateWithLifecycle()
                SectionCard(title = "테스트용 등급") {
                    Text("결제가 열리기 전까지 등급별 화면을 확인하는 스위치예요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tier.entries.forEach { t ->
                            FilterChip(selected = tier == t, onClick = { vm.setTier(t) }, label = { Text(t.label) })
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
