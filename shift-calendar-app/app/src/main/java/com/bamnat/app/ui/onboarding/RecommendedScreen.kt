package com.bamnat.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ConsentChecks
import com.bamnat.app.data.RecommendedSettings
import com.bamnat.app.sleep.HealthSleep
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.markNotificationAsked
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.RecommendedFeature
import com.bamnat.core.consent.RecommendedPlan
import com.bamnat.core.consent.SleepDevice
import kotlinx.coroutines.launch

/**
 * [ONBOARD-03] 추천 기능 한 번에 켜기: one screen in place of the consent pages. The features worth having
 * are already switched on, with why it is fine to leave them on and where to turn them off; one tap saves
 * them and asks the phone, in turn, for what they need ([RecommendedPlan.asks]). Each question can be
 * refused and the next one comes; nothing that goes to a server is here (D42). Background location and
 * notification access are asked on their own screens, right after their own notice (Play policy).
 * [ONBOARD-04] [firstStart] false is the same screen from settings or the 1.22 update notice: the phone's
 * own values, saved as they are.
 */
@Composable
fun RecommendedScreen(container: AppContainer, firstStart: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { ConsentChecks.available(context, container.healthSleep, container.consentStore) }
    val start = remember { container.settingsRepository.current }
    var on by rememberSaveable {
        mutableStateOf((if (firstStart) RecommendedPlan.NEW_INSTALL else RecommendedSettings.on(start)).map { it.key })
    }
    var watch by rememberSaveable { mutableStateOf(!firstStart && start.healthSleep) }

    // Read again when the user comes back from a settings screen or a system question.
    var refresh by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(emptySet<ConsentItem>()) }
    LaunchedEffect(refresh) { granted = ConsentChecks.granted(context, container.healthSleep) }

    // The questions in turn: null while not asking; [asked] is how far it got, kept across a turned phone.
    var queue by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var asked by rememberSaveable { mutableIntStateOf(0) }
    var waitingForSettings by rememberSaveable { mutableStateOf(false) }
    val finish = {
        if (firstStart) container.consentStore.markFirstStartDone(available)
        container.consentStore.markRecommendOffered()
        onDone()
    }
    val advance = { asked++; refresh++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        if (waitingForSettings) { waitingForSettings = false; advance() }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        if (got.containsAll(HealthSleep.PERMISSIONS)) turnOnSleep(container, scope)
        advance()
    }
    val current = queue?.getOrNull(asked)?.let(ConsentItem::ofKey)
    LaunchedEffect(queue, asked) {
        val q = queue ?: return@LaunchedEffect
        if (asked >= q.size) { queue = null; finish(); return@LaunchedEffect }
        when (current) {
            ConsentItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) {
                markNotificationAsked(context)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else { waitingForSettings = true; SystemSettings.appNotifications(context) }
            ConsentItem.ALARMS -> {
                val pkg = Uri.parse("package:${context.packageName}")
                waitingForSettings = true
                if (Build.VERSION.SDK_INT >= 34) SystemSettings.open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                else if (Build.VERSION.SDK_INT >= 31) SystemSettings.open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                else { waitingForSettings = false; advance() }
            }
            ConsentItem.CALENDAR -> calendar.launch(ConsentChecks.READ_CALENDAR)
            ConsentItem.SLEEP -> when (container.healthSleep.sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE ->
                    if (container.healthSleep.granted()) { turnOnSleep(container, scope); advance() }
                    else runCatching { health.launch(HealthSleep.PERMISSIONS) }.onFailure { advance() }
                else -> advance()
            }
            // [STAT-01] An app setting with its own yes, not a system question: nothing to ask here.
            ConsentItem.USAGE_STATS, null -> advance()
        }
    }

    val save: (Boolean) -> Unit = { ask ->
        val chosen = on.mapNotNull(RecommendedFeature::ofKey).toSet()
        container.settingsRepository.update { RecommendedSettings.apply(it, chosen).let { s -> if (watch) s else s.copy(healthSleep = false) } }
        container.consentStore.setSleepDevice(if (watch) SleepDevice.WEARS else SleepDevice.NONE)
        val asks = if (ask) RecommendedPlan.asks(available, granted, watch) else emptyList()
        if (asks.isEmpty()) finish() else { asked = 0; queue = asks.map { it.key } }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!firstStart) {
                            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (firstStart) "밤낮을 제대로 쓰는 설정" else "추천 기능 한 번에 켜기",
                            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    }
                    Text(RecommendedPlan.REASSURE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Section("켜 둘 기능") {
                        RecommendedFeature.entries.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider()
                            SwitchLine(f.title, f.what, f.okay, f.key in on) { v -> on = if (v) on + f.key else on - f.key }
                        }
                        if (ConsentItem.SLEEP in available) {
                            HorizontalDivider()
                            SwitchLine("워치로 잰 잠 함께 보기", "워치·링이 잰 수면으로 부족한 잠을 계산해요",
                                "워치나 링을 차고 잘 때만 켜요. 수면만 읽고 휴대폰 밖으로 보내지 않아요", watch) { watch = it }
                        }
                        Text("이미 켜져 있어요: ${RecommendedPlan.ALREADY_ON}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val asks = RecommendedPlan.shown(available, watch)
                    if (asks.isNotEmpty()) Section("허용할 것") {
                        Text(if (firstStart) "아래 버튼을 누르면 휴대폰이 차례로 물어요. 하나를 거절해도 다음으로 넘어가요."
                            else "꺼진 것만 휴대폰이 차례로 물어요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        asks.forEach { item -> PermissionLine(item, item in granted, asking = item == current) }
                    }

                    Notice(Icons.Filled.Lock, "근무표·메모·급여는 이 휴대폰에만 있어요. 여기서 켜는 것은 서버로 아무것도 보내지 않아요.")
                    Notice(Icons.Outlined.Info, "끄고 싶으면 ${RecommendedPlan.TURN_OFF}에서 언제든 꺼요. 설정 찾기에 ‘끄기’라고 써도 돼요. " +
                        "출퇴근 자동 기록(위치)과 결제 알림 읽기는 쓰는 화면에서 따로 켜요.")
                }
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { save(true) }, enabled = queue == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (firstStart) "추천대로 켜고 시작하기" else "이대로 저장")
                    }
                    if (firstStart) {
                        TextButton(onClick = { save(false) }, enabled = queue == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("허용은 나중에 할게요")
                        }
                    }
                }
            }
        }
    }
}

/** Health Connect allowed: read sleep from now on, as 설정 › 잠과 휴식 does. */
private fun turnOnSleep(container: AppContainer, scope: kotlinx.coroutines.CoroutineScope) {
    container.settingsRepository.update { it.copy(healthSleep = true) }
    scope.launch { container.healthSleep.refresh(true, force = true) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A feature: its name, what it does, why it is fine on, and its switch; the whole row toggles. */
@Composable
private fun SwitchLine(title: String, what: String, okay: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(okay, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** What the phone will be asked, and whether it is allowed already. */
@Composable
private fun PermissionLine(item: ConsentItem, allowed: Boolean, asking: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (allowed) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title(), style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    allowed -> "허용되어 있어요"
                    asking -> "지금 휴대폰이 묻고 있어요"
                    else -> item.why()
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}
