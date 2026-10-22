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
import androidx.compose.material.icons.filled.BarChart
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
 * [ONBOARD-01][ONBOARD-03] What a new phone sees before the first setup. Up to 1.21 this was a page for
 * every item; since 1.22 it is one screen that turns the recommended features on and asks for what they
 * need in turn ([RecommendedScreen]). Every item stays optional and the app works without any of them.
 */
@Composable
fun ConsentPages(container: AppContainer, onDone: () -> Unit) = RecommendedScreen(container, firstStart = true, onDone = onDone)

/** What each item is called and gives, on the first page and in the short prompt. */
internal fun ConsentItem.icon(): ImageVector = when (this) {
    ConsentItem.NOTIFICATIONS -> Icons.Filled.Notifications
    ConsentItem.ALARMS -> Icons.Filled.Alarm
    ConsentItem.SLEEP -> Icons.Filled.Watch
    ConsentItem.CALENDAR -> Icons.Filled.CalendarMonth
    ConsentItem.USAGE_STATS -> Icons.Filled.BarChart
}

internal fun ConsentItem.title(): String = when (this) {
    ConsentItem.NOTIFICATIONS -> "알림"
    ConsentItem.ALARMS -> if (Build.VERSION.SDK_INT >= 34) "잠금 화면에 알람 띄우기" else "정확한 시간에 울리기"
    ConsentItem.SLEEP -> "워치·기기 수면 기록 (헬스 커넥트)"
    ConsentItem.CALENDAR -> "휴대폰 캘린더 읽기"
    ConsentItem.USAGE_STATS -> "사용 통계 보내기"
}

internal fun ConsentItem.why(): String = when (this) {
    ConsentItem.NOTIFICATIONS -> "근무 알람, 잘 시간 안내, 일정으로 넣은 메모를 알려 드려요"
    ConsentItem.ALARMS -> if (Build.VERSION.SDK_INT >= 34) "화면이 꺼져 있어도 알람이 크게 떠서 바로 끌 수 있어요" else "근무 알람이 몇 분 늦지 않고 제시각에 울려요"
    ConsentItem.SLEEP -> "워치가 잰 실제 수면으로 부족한 잠을 계산해요"
    ConsentItem.CALENDAR -> "삼성 캘린더·Google 캘린더의 일정을 근무와 메모로 옮겨요"
    ConsentItem.USAGE_STATS -> "어떤 기능을 하루에 몇 번 썼는지만 보내 앱을 고치는 데 써요. 휴대폰·근무·금액은 보내지 않아요"
}

/** Where it is turned on later, said on every page. */
internal fun ConsentItem.later(): String = when (this) {
    ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS -> "설정 > 개인정보와 권한"
    ConsentItem.SLEEP -> "설정 > 잠과 휴식"
    ConsentItem.CALENDAR -> "설정 > 데이터 > 캘린더에서 가져오기"
    ConsentItem.USAGE_STATS -> "설정 > 개인정보와 권한"
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
