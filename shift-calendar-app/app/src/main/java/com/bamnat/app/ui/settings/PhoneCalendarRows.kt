package com.bamnat.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.calendar.PhoneCalendar
import com.bamnat.app.calendar.PhoneCalendarSync
import com.bamnat.app.tier.Feature
import com.bamnat.app.ui.pro.openPro
import kotlinx.coroutines.launch

/** [ALRM-01] The row the permission notice opens. */
const val PHONE_CALENDAR_ROW = "휴대폰 캘린더에 근무 쓰기"

/**
 * [FEAT-07] 휴대폰 캘린더에 근무 쓰기 (프로): a switch for each of the user's calendars. The calendar
 * permission is asked when one is turned on, not before; turning it off deletes the phone's copy.
 */
@Composable
internal fun PhoneCalendarRows(vm: SettingsViewModel) {
    val context = LocalContext.current
    val sync = context.appContainer.phoneCalendarSync
    val settings by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val open = gate.allows(Feature.DEVICE_CALENDAR)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    fun set(id: String, on: Boolean) {
        vm.update { s -> s.copy(phoneCalendars = if (on) (s.phoneCalendars + id).distinct() else s.phoneCalendars - id) }
        scope.launch { sync.sync() }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val id = pending
        pending = null
        refused = granted.values.any { !it }
        if (id != null && !refused) set(id, true)
    }
    val list = calendars.filter { it.ready }
    list.forEachIndexed { i, cal ->
        if (i > 0) HorizontalDivider()
        val on = cal.id in settings.phoneCalendars
        SwitchRow(
            title = (if (list.size == 1) PHONE_CALENDAR_ROW else "${cal.name} → 휴대폰 캘린더") + if (open) "" else " (프로)",
            subtitle = "'${if (cal.isMain) "밤낮 근무" else "밤낮 · ${cal.name}"}' 캘린더를 휴대폰에 만들어 구글·삼성 캘린더에 근무가 보여요. " +
                "근무를 바꾸면 따라 바뀌고, 끄면 그 캘린더를 지워요. 휴대폰 밖으로 보내지 않아요",
            checked = on && open,
        ) { want ->
            when {
                !open -> openPro()
                want && !PhoneCalendar(context).allowed() -> {
                    pending = cal.id
                    ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
                else -> set(cal.id, want)
            }
        }
    }
    val line = if (refused) "캘린더 권한을 허용해야 쓸 수 있어요. 휴대폰 설정 › 애플리케이션 › 밤낮 › 권한에서 바꿀 수 있어요." else when (val s = status) {
        is PhoneCalendarSync.Status.Written -> "일정 ${s.events}개를 썼어요. 캘린더 앱에 안 보이면 그 앱의 캘린더 목록에서 '${PhoneCalendar.ACCOUNT}'을 켜 주세요."
        // [ALRM-01] R9: the phone's calendar may still show old shifts, which this app cannot remove without the permission.
        PhoneCalendarSync.Status.NeedsPermission -> "캘린더 권한이 꺼져 멈췄어요. 휴대폰 캘린더의 근무가 옛것일 수 있어요. 스위치를 껐다 켜면 다시 물어요."
        is PhoneCalendarSync.Status.Failed -> "휴대폰 캘린더에 쓰지 못했어요. 잠시 뒤 다시 해 볼게요."
        PhoneCalendarSync.Status.Off -> null
    }
    if (line != null && (refused || settings.phoneCalendars.isNotEmpty())) {
        val warn = refused || status == PhoneCalendarSync.Status.NeedsPermission
        Text(line, style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
