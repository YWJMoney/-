package com.bamnat.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.data.ConsentChecks
import com.bamnat.app.sleep.HealthSleep
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.markNotificationAsked
import com.bamnat.app.ui.setup.ChoiceCard
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.ConsentPlan
import com.bamnat.core.consent.SleepDevice
import kotlinx.coroutines.launch

/**
 * [ONBOARD-01] The pages a new phone sees before the first setup. Like the access notice Korean apps
 * show on first start, the first page lists what the app may ask for, says every item is optional and
 * can be turned on later; then one page per item says what it gives before the phone's own question
 * comes, so the user agrees knowing why. "나중에 할게요" is always there and never asks twice.
 * [ONBOARD-02] Sleep asks first whether the user sleeps with a watch or another device; only then
 * comes Health Connect. Bedtime suggestions from the schedule need none of this and say so.
 */
@Composable
fun ConsentPages(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val available = remember { ConsentChecks.available(context, container.healthSleep, container.consentStore) }
    var sleepDevice by rememberSaveable { mutableStateOf<SleepDevice?>(null) }
    val pages = remember(available, sleepDevice) { pagesFor(available, sleepDevice) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val finish = {
        container.consentStore.markFirstStartDone(available)
        onDone()
    }
    val next: () -> Unit = { if (index >= pages.lastIndex) finish() else index++ }
    BackHandler(enabled = index > 0) { index-- }

    // Read again when the user comes back from a settings screen or a system question.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(targetState = page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "consent") { p ->
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 24.dp)) {
                    if (p != Page.INTRO) {
                        Text(
                            "${pages.indexOf(p)}/${pages.size - 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    when (p) {
                        Page.INTRO -> IntroPage(available, onStart = next, onLater = finish)
                        Page.NOTIFICATIONS -> NotificationsPage(refresh, next)
                        Page.ALARMS -> AlarmsPage(refresh, next)
                        Page.SLEEP_ASK -> SleepAskPage(sleepDevice) { answer ->
                            sleepDevice = answer
                            container.consentStore.setSleepDevice(answer)
                            index++
                        }
                        Page.SLEEP_HEALTH -> SleepHealthPage(container, refresh, next)
                        Page.SLEEP_NONE -> SleepNonePage(next)
                        Page.CALENDAR -> CalendarPage(refresh, next)
                    }
                }
            }
        }
    }
}

internal enum class Page { INTRO, NOTIFICATIONS, ALARMS, SLEEP_ASK, SLEEP_HEALTH, SLEEP_NONE, CALENDAR }

/** The pages in order; sleep's second page follows the answer about a watch. */
internal fun pagesFor(available: Set<ConsentItem>, sleep: SleepDevice?): List<Page> = buildList {
    add(Page.INTRO)
    for (item in ConsentPlan.firstStart(available)) when (item) {
        ConsentItem.NOTIFICATIONS -> add(Page.NOTIFICATIONS)
        ConsentItem.ALARMS -> add(Page.ALARMS)
        ConsentItem.SLEEP -> {
            add(Page.SLEEP_ASK)
            add(if (sleep == SleepDevice.NONE) Page.SLEEP_NONE else Page.SLEEP_HEALTH)
        }
        ConsentItem.CALENDAR -> add(Page.CALENDAR)
    }
}

/** What each item is called and gives, on the first page and in the short prompt. */
internal fun ConsentItem.icon(): ImageVector = when (this) {
    ConsentItem.NOTIFICATIONS -> Icons.Filled.Notifications
    ConsentItem.ALARMS -> Icons.Filled.Alarm
    ConsentItem.SLEEP -> Icons.Filled.Watch
    ConsentItem.CALENDAR -> Icons.Filled.CalendarMonth
}

internal fun ConsentItem.title(): String = when (this) {
    ConsentItem.NOTIFICATIONS -> "알림"
    ConsentItem.ALARMS -> if (Build.VERSION.SDK_INT >= 34) "잠금 화면에 알람 띄우기" else "정확한 시간에 울리기"
    ConsentItem.SLEEP -> "워치·기기 수면 기록 (헬스 커넥트)"
    ConsentItem.CALENDAR -> "휴대폰 캘린더 읽기"
}

internal fun ConsentItem.why(): String = when (this) {
    ConsentItem.NOTIFICATIONS -> "근무 알람, 잘 시간 안내, 일정으로 넣은 메모를 알려 드려요"
    ConsentItem.ALARMS -> if (Build.VERSION.SDK_INT >= 34) "화면이 꺼져 있어도 알람이 크게 떠서 바로 끌 수 있어요" else "근무 알람이 몇 분 늦지 않고 제시각에 울려요"
    ConsentItem.SLEEP -> "워치가 잰 실제 수면으로 부족한 잠을 계산해요"
    ConsentItem.CALENDAR -> "삼성 캘린더·Google 캘린더의 일정을 근무와 메모로 옮겨요"
}

/** Where it is turned on later, said on every page. */
internal fun ConsentItem.later(): String = when (this) {
    ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS -> "설정 > 개인정보와 권한"
    ConsentItem.SLEEP -> "설정 > 잠과 휴식"
    ConsentItem.CALENDAR -> "설정 > 데이터 > 캘린더에서 가져오기"
}

@Composable
private fun IntroPage(available: Set<ConsentItem>, onStart: () -> Unit, onLater: () -> Unit) {
    PageFrame(
        primary = "하나씩 알아보기",
        onPrimary = onStart,
        secondary = "나중에 할게요",
        onSecondary = onLater,
    ) {
        Text("밤낮을 더 편하게 쓰는 방법", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            "몇 가지를 허용하면 알람과 안내가 제때 와요. 왜 필요한지 하나씩 보여 드릴게요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text("선택 접근 권한", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ConsentPlan.firstStart(available).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Badge(item.icon())
                Column(Modifier.weight(1f)) {
                    Text(item.title(), style = MaterialTheme.typography.titleMedium)
                    Text(item.why(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("필수 접근 권한은 없어요", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp))
        Notice(
            Icons.Filled.Lock,
            "모두 허용하지 않아도 달력, 메모, 급여 계산은 그대로 쓸 수 있어요. 근무표와 메모는 이 휴대폰에 저장돼요. " +
                "언제든 설정 > 개인정보와 권한에서 바꿀 수 있어요.",
        )
    }
}

@Composable
private fun NotificationsPage(refresh: Int, next: () -> Unit) {
    val context = LocalContext.current
    val on = remember(refresh) { Notifications.canPost(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    ItemPage(
        item = ConsentItem.NOTIFICATIONS,
        allowed = on,
        title = "놓치면 안 되는 것만 알려 드릴게요",
        gains = listOf(
            "근무 알람이 울릴 때 알림창에서도 끄고 미룰 수 있어요",
            "다음 날 근무에 맞춰 잘 시간과 출근 준비를 미리 알려 드려요",
            "시간을 적은 메모를 일정으로 넣으면 그 시각에 알려 드려요",
        ),
        without = "허용하지 않아도 달력과 메모는 그대로 써요. 알림창에 안내만 오지 않아요.",
        onAllow = {
            if (Build.VERSION.SDK_INT >= 33) {
                markNotificationAsked(context)
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemSettings.appNotifications(context)
            }
        },
        next = next,
    )
}

@Composable
private fun AlarmsPage(refresh: Int, next: () -> Unit) {
    val context = LocalContext.current
    val on = remember(refresh) { ConsentChecks.alarmsAllowed(context) }
    val lockScreen = Build.VERSION.SDK_INT >= 34
    ItemPage(
        item = ConsentItem.ALARMS,
        allowed = on,
        title = if (lockScreen) "잠금 화면에서도 알람이 바로 뜨게 할까요?" else "알람이 제시각에 울리게 할까요?",
        gains = if (lockScreen) listOf(
            "화면이 꺼져 있어도 근무 알람이 화면 가득 떠요",
            "잠금을 풀지 않고 바로 끄거나 다시 울림을 누를 수 있어요",
        ) else listOf(
            "절전 중에도 근무 알람이 정해진 시각에 울려요",
            "야간 근무 전 낮잠 알람도 늦지 않아요",
        ),
        without = if (lockScreen) "허용하지 않으면 잠금 화면에서는 알림으로만 울려요. 소리와 진동은 그대로예요."
        else "허용하지 않으면 알람이 몇 분 늦게 울릴 수 있어요.",
        settingsNote = "휴대폰 설정 화면이 열려요. 밤낮을 켜고 뒤로 오면 돼요.",
        onAllow = {
            val pkg = Uri.parse("package:${context.packageName}")
            if (Build.VERSION.SDK_INT >= 34) SystemSettings.open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            else if (Build.VERSION.SDK_INT >= 31) SystemSettings.open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        },
        next = next,
    )
}

@Composable
private fun SleepAskPage(answer: SleepDevice?, onAnswer: (SleepDevice) -> Unit) {
    PageFrame {
        Badge(Icons.Filled.Bedtime, large = true)
        Text("워치나 잠을 재는 기기를 차고 주무세요?", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text(
            "갤럭시 워치, 갤럭시 링, 핏빗처럼 잠을 재는 기기가 있으면 실제로 잔 시간을 함께 볼 수 있어요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceCard(
            title = "네, 차고 자요",
            subtitle = "기기가 잰 수면을 밤낮에서 함께 봐요",
            icon = Icons.Filled.Watch,
            selected = answer == SleepDevice.WEARS,
            onClick = { onAnswer(SleepDevice.WEARS) },
        )
        ChoiceCard(
            title = "아니요, 없어요",
            subtitle = "기기 없이도 잘 시간 안내는 그대로 받아요",
            icon = Icons.Outlined.DoNotDisturbOn,
            selected = answer == SleepDevice.NONE,
            onClick = { onAnswer(SleepDevice.NONE) },
        )
    }
}

@Composable
private fun SleepHealthPage(container: AppContainer, refresh: Int, next: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { on = container.healthSleep.granted() }
    val turnOn: (Boolean) -> Unit = { granted ->
        if (granted) {
            container.settingsRepository.update { it.copy(healthSleep = true) }
            scope.launch { container.healthSleep.refresh(true, force = true) }
        }
    }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val ok = granted.containsAll(HealthSleep.PERMISSIONS)
        turnOn(ok)
        if (ok) next() else message = "허용하지 않았어요. 나중에 설정 > 잠과 휴식에서 켤 수 있어요."
    }
    ItemPage(
        item = ConsentItem.SLEEP,
        allowed = on,
        title = "기기가 잰 수면을 함께 볼까요?",
        gains = listOf(
            "실제로 잔 시간으로 목표보다 부족한 잠을 계산해요",
            "근무별로 얼마나 잤는지 비교해 드려요",
            "잠들었어요·깼어요를 따로 누르지 않아도 돼요",
        ),
        without = "허용하지 않아도 잠들었어요·깼어요 버튼으로 직접 기록할 수 있어요. 근무표에 맞춘 잘 시간 안내도 그대로예요.",
        trust = "헬스 커넥트에서 수면만 읽어요. 고치거나 휴대폰 밖으로 보내지 않아요.",
        message = message,
        onAllow = {
            message = null
            when (container.healthSleep.sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> scope.launch {
                    if (container.healthSleep.granted()) { turnOn(true); next() }
                    else runCatching { ask.launch(HealthSleep.PERMISSIONS) }.onFailure { message = "헬스 커넥트 허용 화면을 열지 못했어요." }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    message = "헬스 커넥트를 설치하거나 업데이트한 뒤 설정 > 잠과 휴식에서 켜 주세요."
                    runCatching { context.startActivity(HealthSleep.installIntent()) }
                }
                else -> message = "이 휴대폰에서는 헬스 커넥트를 쓸 수 없어요. 잠들었어요·깼어요 버튼으로 기록해 주세요."
            }
        },
        next = next,
    )
}

@Composable
private fun SleepNonePage(next: () -> Unit) {
    PageFrame(primary = "다음", onPrimary = next) {
        Badge(Icons.Filled.Bedtime, large = true)
        Text("괜찮아요, 기기 없이도 돼요", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Gain("근무표를 보고 다음 날 일정에 맞춰 언제 자면 좋을지 알려 드려요. 허용 없이 바로 돼요.")
        Gain("잠들었어요·깼어요 버튼으로 잔 시간을 직접 남길 수도 있어요.")
        Notice(Icons.Filled.Watch, "나중에 워치를 쓰게 되면 ${ConsentItem.SLEEP.later()}에서 켜면 돼요.")
    }
}

@Composable
private fun CalendarPage(refresh: Int, next: () -> Unit) {
    val context = LocalContext.current
    val on = remember(refresh) { ConsentChecks.calendarAllowed(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    ItemPage(
        item = ConsentItem.CALENDAR,
        allowed = on,
        title = "휴대폰 캘린더의 일정을 가져올까요?",
        gains = listOf(
            "삼성 캘린더·Google 캘린더에 적어 둔 근무를 밤낮 달력으로 옮겨요",
            "근무가 아닌 일정은 그날 메모로 넣어요",
        ),
        without = "허용하지 않아도 근무표는 직접 넣으면 돼요.",
        trust = "캘린더는 읽기만 하고 고치지 않아요. 읽은 일정은 이 휴대폰 밖으로 나가지 않아요.",
        onAllow = { ask.launch(ConsentChecks.READ_CALENDAR) },
        next = next,
    )
}

/** One item: what it gives, what happens without it, where it is turned on later, and the two buttons. */
@Composable
private fun ItemPage(
    item: ConsentItem,
    allowed: Boolean,
    title: String,
    gains: List<String>,
    without: String,
    onAllow: () -> Unit,
    next: () -> Unit,
    trust: String? = null,
    settingsNote: String? = null,
    message: String? = null,
) {
    PageFrame(
        primary = if (allowed) "다음" else "허용하기",
        onPrimary = if (allowed) next else onAllow,
        secondary = if (allowed) null else "나중에 할게요",
        onSecondary = next,
    ) {
        Badge(item.icon(), large = true)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (allowed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text("허용되어 있어요", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("허용하면 좋은 점", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        gains.forEach { Gain(it) }
        trust?.let { Notice(Icons.Filled.Lock, it) }
        Text(without, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("나중에 ${item.later()}에서 언제든 켜고 끌 수 있어요.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed && settingsNote != null) {
            Text(settingsNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

/** A page that scrolls, with its buttons kept at the bottom for the thumb. */
@Composable
private fun PageFrame(
    primary: String? = null,
    onPrimary: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (primary != null) {
                Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(primary) }
            }
            if (secondary != null) {
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(secondary) }
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, large: Boolean = false) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(if (large) 64.dp else 44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(if (large) 32.dp else 24.dp))
        }
    }
}

@Composable
private fun Gain(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * [ONBOARD-01] For a phone set up before: only items added since, in one short dialog, never the
 * whole pages. "나중에" or a tap on 허용하기 counts as told, so it does not come back.
 */
@Composable
fun NewConsentPrompt(container: AppContainer) {
    val context = LocalContext.current
    val seen by container.consentStore.seen.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<ConsentItem>?>(null) }
    LaunchedEffect(seen) {
        val available = ConsentChecks.available(context, container.healthSleep, container.consentStore)
        items = ConsentPlan.newSince(seen, available, ConsentChecks.granted(context, container.healthSleep))
    }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ask = items?.takeIf { it.isNotEmpty() } ?: return
    val done = { container.consentStore.markSeen(ask) }
    AlertDialog(
        onDismissRequest = done,
        title = { Text("새로 생긴 기능이 있어요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ask.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Badge(item.icon())
                        Column(Modifier.weight(1f)) {
                            Text(item.title(), style = MaterialTheme.typography.titleMedium)
                            Text(item.why(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("선택이에요. 나중에 ${ask.first().later()}에서 켜도 돼요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Today only the calendar can be new; a later item adds its own question here.
                if (ConsentItem.CALENDAR in ask) calendar.launch(ConsentChecks.READ_CALENDAR)
                done()
            }) { Text("허용하기") }
        },
        dismissButton = { TextButton(onClick = done) { Text("나중에") } },
    )
}
