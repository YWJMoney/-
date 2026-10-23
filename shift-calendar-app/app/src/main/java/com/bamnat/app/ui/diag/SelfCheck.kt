package com.bamnat.app.ui.diag

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.settings.SettingsLinks
import com.bamnat.app.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [ALRM-02] I26 서버 연결 점검: one round trip to each server, when the app last heard from the company
 * server, the features the developer paused, and what waits to be sent. Nothing about the user goes.
 */
object ServerCheck {
    data class Facts(
        val companyMillis: Long?,
        val shareMillis: Long?,
        /** The share server's app-link file names this app: /s/ links open in the app. */
        val shareLinksApp: Boolean?,
        val fetchedAt: Instant?,
        val paused: List<String>,
        val waiting: Int,
        val statsSentDay: LocalDate?,
        val statsOn: Boolean,
    )

    /** The card's lines, worked out from [f] at [now]. */
    fun lines(f: Facts, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        add("회사 서버: " + (f.companyMillis?.let { "연결됨 (${it}ms)" } ?: "연결 안 됨"))
        add("근무표 공유 서버: " + (f.shareMillis?.let { "연결됨 (${it}ms)" } ?: "연결 안 됨") +
            when (f.shareLinksApp) { true -> " · 링크가 앱으로 열려요"; false -> " · 링크가 웹으로 열려요"; null -> "" })
        add("마지막으로 설정을 받은 때: " + (f.fetchedAt?.let { ago(it, now, zone) } ?: "아직 없음"))
        add("잠시 쉬는 기능: " + f.paused.ifEmpty { listOf("없음") }.joinToString(", "))
        add(if (f.waiting == 0) "보내지 못한 것: 없음" else "보내지 못한 것: ${f.waiting}개 · 인터넷이 되면 저절로 보내요")
        if (f.statsOn) add("사용 통계를 마지막으로 보낸 날: " + (f.statsSentDay?.let { "${it.monthValue}월 ${it.dayOfMonth}일" } ?: "아직 없음"))
    }

    private fun ago(at: Instant, now: Instant, zone: ZoneId): String {
        val d = Duration.between(at, now)
        return when {
            d.isNegative || d.toMinutes() < 1 -> "방금"
            d.toHours() < 1 -> "${d.toMinutes()}분 전"
            d.toDays() < 1 -> "${d.toHours()}시간 전"
            else -> at.atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))
        }
    }

    /** Asks both servers once. */
    suspend fun run(c: AppContainer): Facts = withContext(Dispatchers.IO) {
        val companyMillis = timed { c.remoteConfig.refresh(c.tierApi, force = true) && true }
        var linksApp: Boolean? = null
        val shareMillis = if (BuildConfig.SHARE_BASE_URL.isBlank()) null else timed {
            val conn = URL(BuildConfig.SHARE_BASE_URL.trimEnd('/') + "/.well-known/assetlinks.json").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val ok = conn.responseCode == 200
                if (ok) linksApp = conn.inputStream.bufferedReader().use { it.readText() }.contains(c.appContext.packageName)
                ok
            } finally {
                conn.disconnect()
            }
        }
        val config = c.remoteConfig.current
        Facts(
            companyMillis, shareMillis, linksApp, config.fetchedAt,
            com.bamnat.core.ops.ServerSwitch.entries.filter { config.paused(it) }.map { it.label },
            com.bamnat.app.company.OutboxJob.waiting(c),
            c.usageStats.sentDay, c.settingsRepository.current.shareUsageStats,
        )
    }

    private inline fun timed(block: () -> Boolean): Long? {
        val start = System.nanoTime()
        val ok = try { block() } catch (e: Exception) { false }
        return if (ok) (System.nanoTime() - start) / 1_000_000 else null
    }
}

@Composable
internal fun ServerCheckCard() {
    val c = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<ServerCheck.Facts?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionCard(title = "서버 연결") {
        val f = facts
        if (f == null) {
            val waiting = remember { com.bamnat.app.company.OutboxJob.waiting(c) }
            Text(if (waiting == 0) "회사 서버와 근무표 공유 서버에 한 번씩 연결해 봐요. 내 정보는 보내지 않아요."
                else "보내지 못한 것이 ${waiting}개 있어요. 연결해 보고 바로 보낼 수 있어요.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            ServerCheck.lines(f, Instant.now()).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { facts = ServerCheck.run(c); busy = false }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (f == null) "연결해 보기" else "다시 해 보기") }
            if (f != null && f.waiting > 0) OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { com.bamnat.app.company.OutboxJob.send(c); facts = ServerCheck.run(c); busy = false }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지금 보내기") }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * [ALRM-02] I27 동의·권한 요약 한 장: everything the user said yes or no to, and every permission, each
 * with a 바꾸기 to where it changes.
 */
object ConsentSummary {
    enum class Change { USAGE_STATS, NEWS, ADS, PRO, NOTIFICATIONS, FULL_SCREEN, EXACT, BATTERY, CALENDAR, LOCATION, PAYMENT_READ, HEALTH, LOCK }

    data class Line(val title: String, val state: String, val on: Boolean, val change: Change)

    data class Facts(
        val usageStats: Boolean,
        val news: Boolean,
        val trialAnswer: String?,
        val notifications: Boolean,
        val fullScreen: Boolean?,
        val exact: Boolean?,
        val battery: Boolean,
        val calendar: Boolean,
        val location: Boolean,
        val paymentRead: Boolean,
        val health: Boolean,
        val lock: Boolean,
    )

    fun lines(f: Facts): List<Line> = listOfNotNull(
        Line("사용 통계 보내기", if (f.usageStats) "보내요 · 기능 이름과 하루 횟수만" else "보내지 않아요", f.usageStats, Change.USAGE_STATS),
        Line("밤낮 소식 알림", if (f.news) "받아요 · 답장·광장·공지를 하루 한 번" else "받지 않아요", f.news, Change.NEWS),
        Line("광고 개인정보", "광고 ID 사용은 휴대폰 설정에서 골라요", true, Change.ADS),
        f.trialAnswer?.let { Line("무료 체험 뒤 결제", if (it == "keep") "이어서 쓰기로 했어요" else "끝나면 멈추기로 했어요", it == "keep", Change.PRO) },
        Line("알림", if (f.notifications) "허용됨" else "꺼짐 · 알람 화면과 안내가 안 보일 수 있어요", f.notifications, Change.NOTIFICATIONS),
        f.fullScreen?.let { Line("잠금 화면에 알람 띄우기", if (it) "허용됨" else "꺼짐", it, Change.FULL_SCREEN) },
        f.exact?.let { Line("정확한 시간에 울리기", if (it) "허용됨" else "꺼짐 · 알람이 늦을 수 있어요", it, Change.EXACT) },
        Line("배터리 최적화 제외", if (f.battery) "제외됨" else "최적화 중 · 오래 안 쓰면 알람이 늦을 수 있어요", f.battery, Change.BATTERY),
        Line("휴대폰 캘린더", if (f.calendar) "허용됨" else "허용 안 함", f.calendar, Change.CALENDAR),
        Line("위치(출퇴근 자동 기록)", if (f.location) "허용됨" else "허용 안 함", f.location, Change.LOCATION),
        Line("결제 알림 읽기", if (f.paymentRead) "읽어요 · 카드·은행 알림만" else "읽지 않아요", f.paymentRead, Change.PAYMENT_READ),
        Line("워치·휴대폰 수면 기록", if (f.health) "읽어요" else "읽지 않아요", f.health, Change.HEALTH),
        Line("앱 잠금", if (f.lock) "켜짐" else "꺼짐", f.lock, Change.LOCK),
    )

    fun facts(context: Context, c: AppContainer): Facts {
        val s = c.settingsRepository.current
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return Facts(
            usageStats = s.shareUsageStats,
            news = s.plazaNews,
            trialAnswer = c.proStore.current.trialAnswer,
            notifications = Notifications.canPost(context),
            fullScreen = if (Build.VERSION.SDK_INT >= 34) Notifications.canUseFullScreen(context) else null,
            exact = if (Build.VERSION.SDK_INT >= 31) c.alarmScheduler.canScheduleExact() else null,
            battery = context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            calendar = granted(Manifest.permission.READ_CALENDAR),
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            paymentRead = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            health = s.healthSleep,
            lock = s.appLock,
        )
    }

    fun change(context: Context, change: Change) {
        val pkg = Uri.parse("package:${context.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        when (change) {
            Change.USAGE_STATS -> SettingsLinks.open(SettingsPage.PRIVACY, "사용 통계 보내기")
            Change.NEWS -> SettingsLinks.open(SettingsPage.NOTICES, "밤낮 소식")
            Change.ADS -> SettingsLinks.open(SettingsPage.PRIVACY, "광고 개인정보 선택")
            Change.PRO -> com.bamnat.app.ui.pro.openPro()
            Change.NOTIFICATIONS -> SystemSettings.appNotifications(context)
            Change.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) SystemSettings.open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            Change.EXACT -> if (Build.VERSION.SDK_INT >= 31) SystemSettings.open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            Change.BATTERY -> openFirst(context, listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
            Change.CALENDAR, Change.LOCATION -> SystemSettings.open(context, details)
            Change.PAYMENT_READ -> com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.AUTO_RECORD)
            Change.HEALTH -> SettingsLinks.open(SettingsPage.NOTICES, "워치·휴대폰 수면 기록 함께 보기")
            Change.LOCK -> SettingsLinks.open(SettingsPage.PRIVACY, "앱 잠금")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSummarySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val lines = remember { ConsentSummary.lines(ConsentSummary.facts(context, c)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("보내는 것과 권한 한눈에", style = MaterialTheme.typography.titleLarge)
            Text("내가 고른 것과 휴대폰이 허용한 것을 한 장에 모았어요. ‘바꾸기’를 누르면 바꾸는 곳이 열려요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            lines.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.title, style = MaterialTheme.typography.titleSmall)
                        Text(line.state, style = MaterialTheme.typography.bodySmall,
                            color = if (line.on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onDismiss(); ConsentSummary.change(context, line.change) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("바꾸기")
                    }
                }
            }
        }
    }
}
