package com.bamnat.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.core.work.ShopDays
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [CALS-01][CALS-02] One calendar's own page: everything about it is changed here, for it alone —
 * its name and colour, its kind of work, its order or shop days, its day kinds, its pay or money,
 * its alarms, whether payment notifications go to it, its link, and removing it.
 */
class CalendarEditViewModel(private val c: AppContainer, val calendarId: String) : ViewModel() {
    data class State(
        val calendar: PersonalCalendar?,
        val style: WorkStyle?,
        val shiftKinds: Int,
        val hasPay: Boolean,
        val notifications: Boolean,
        val many: Boolean,
    )

    val state: StateFlow<State> = combine(
        c.calendars.calendars,
        c.settingsRepository.settings,
        c.calendars.repository(calendarId).shiftTypes,
        c.calendars.pay(calendarId).profile,
        c.autoRecordPrefs.state,
    ) { list, settings, types, pay, prefs ->
        val cal = list.firstOrNull { it.id == calendarId }
        State(
            calendar = cal,
            style = if (cal?.isMain != false) settings.workStyle else cal.style ?: settings.workStyle,
            shiftKinds = types.count { !it.isOff },
            hasPay = pay != null,
            notifications = prefs.calendar == calendarId || (calendarId == PersonalCalendar.MAIN && list.none { it.id == prefs.calendar }),
            many = list.size > 1,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(c.calendars.get(calendarId), null, 0, false, false, false))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == c.calendars.get(calendarId)?.name) return
        c.calendars.rename(calendarId, clean)
        val shown = clean.take(PersonalCalendar.MAX_NAME)
        _message.value = "이름을 ‘$shown’${com.bamnat.app.ui.Fmt.ro(shown)} 바꿨어요"
    }

    fun recolor(color: Long) = c.calendars.recolor(calendarId, color)
    fun setAlarms(on: Boolean) = c.calendars.setAlarms(calendarId, on)

    /** [CALS-04] Payment notifications that no card, account or app sends elsewhere go here. */
    fun takeNotifications() = c.autoRecordPrefs.setCalendar(calendarId)

    /** [UNDO-02] What 기록 지우기 would take, said in its question. */
    suspend fun records(): com.bamnat.app.data.RecordsTrash.Records = c.recordsTrash.records(calendarId)

    /** [UNDO-02] Clears this calendar's records; 되돌리기 follows, and 최근 지운 기록 keeps them 7 days. */
    fun clearRecords() {
        c.appScope.launch { c.recordsTrash.clearRecords(calendarId) }
    }

    fun delete(keepLink: Boolean, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            if (removeCalendar(c, calendarId, keepLink)) onDone() else _message.value = CalendarsViewModel.LINK_NOT_CUT
            _busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEditScreen(
    vm: CalendarEditViewModel,
    onBack: () -> Unit,
    onWorkStyle: () -> Unit,
    onPattern: () -> Unit,
    onShiftTypes: () -> Unit,
    onPay: () -> Unit,
    onMoney: () -> Unit,
    onShare: () -> Unit,
    onDeleted: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cal = state.calendar
    LaunchedEffect(cal == null) { if (cal == null) onDeleted() }
    if (cal == null) return
    var name by remember(cal.id) { mutableStateOf(cal.name) }
    var deleting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<com.bamnat.app.data.RecordsTrash.Records?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val words = state.style.words()
    val save = { vm.rename(name); focus.clearFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (cal.isMain) "내 달력 고치기" else "달력 고치기") },
                navigationIcon = { IconButton(onClick = { vm.rename(name); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            cal.Avatar(size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("이름과 색", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= PersonalCalendar.MAX_NAME) name = it },
                            singleLine = true,
                            label = { Text("달력 이름") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            supportingText = { Text("${name.length}/${PersonalCalendar.MAX_NAME}") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (name.trim() != cal.name && name.isNotBlank()) {
                            TextButton(onClick = save, modifier = Modifier.align(Alignment.End)) { Text("이름 저장") }
                        }
                        if (!cal.isMain) ColorRow(cal.color, vm::recolor)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("이 달력의 일", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        EditRow("근무 형태", state.style?.let { "${it.label} · ${it.description}" } ?: "교대·일근·시급·현장·자영업 중에서 골라요", onWorkStyle)
                        HorizontalDivider()
                        EditRow(
                            words.pattern,
                            if (state.style == WorkStyle.BUSINESS) cal.shop?.let(ShopDays::describe) ?: words.patternHint else words.patternHint,
                            onPattern,
                        )
                        HorizontalDivider()
                        EditRow(words.types, if (state.shiftKinds > 0) "${state.shiftKinds}가지 · 이름·시간·색·알람" else "이름·시간·색·알람", onShiftTypes)
                        HorizontalDivider()
                        val moneyFirst = state.style == WorkStyle.FIELD || state.style == WorkStyle.BUSINESS
                        EditRow(
                            words.income,
                            if (moneyFirst || state.hasPay) words.incomeHint else "아직 정하지 않았어요 · ${words.incomeHint}",
                            if (moneyFirst) onMoney else onPay,
                        )
                        if (state.style == WorkStyle.FIELD) {
                            HorizontalDivider()
                            FieldModeSetting(cal)
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        if (!cal.isMain) {
                            ToggleRow("근무 알람 울리기", "이 달력의 근무·영업 알람도 울려요", cal.alarms, onChange = vm::setAlarms)
                            HorizontalDivider()
                        }
                        if (state.many) {
                            ToggleRow(
                                "결제 알림을 이 달력에 쌓기",
                                // Only one calendar takes them: on here, it goes off by turning it on in another.
                                if (state.notifications) "카드·통장을 따로 정하지 않은 결제 알림이 여기에 쌓여요. 다른 달력에서 켜면 옮겨져요" else "켜면 다른 달력 대신 이 달력에 쌓여요",
                                state.notifications,
                                enabled = !state.notifications,
                            ) { if (it) vm.takeNotifications() }
                        }
                    }
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("이 달력 근무표 공유")
                }
                // [UNDO-02] Starting this calendar over without removing it, with 7 days to take it back.
                TextButton(onClick = { scope.launch { clearing = vm.records() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("이 달력 기록 지우기", color = MaterialTheme.colorScheme.error)
                }
                if (!cal.isMain) {
                    TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("달력 지우기", color = MaterialTheme.colorScheme.error)
                    }
                }
                message?.takeIf { it != CalendarsViewModel.LINK_NOT_CUT }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    LaunchedEffect(it) { kotlinx.coroutines.delay(2500); vm.consumeMessage() }
                }
            }
        }
    }
    clearing?.let { records ->
        if (records.isEmpty) {
            ConfirmDialog(
                title = "지울 기록이 없어요",
                message = "‘${cal.name}’에는 아직 지울 근무 기록이나 돈 기록이 없어요.",
                confirmLabel = "확인",
                onDismiss = { clearing = null },
                onConfirm = { clearing = null },
            )
        } else {
            ConfirmDialog(
                title = "‘${cal.name}’ 기록을 지울까요?",
                message = "${records.text}\n\n이 기록을 지우고 처음부터 시작해요. 근무 형태, 근무 종류(이름·시간·알람)와 가계부는 그대로예요. " +
                    "7일 안에는 설정 › 데이터 › 최근 지운 기록에서 되돌릴 수 있어요.",
                confirmLabel = "지우기",
                destructive = true,
                onDismiss = { clearing = null },
                onConfirm = { clearing = null; vm.clearRecords() },
            )
        }
    }
    if (deleting) {
        if (message == CalendarsViewModel.LINK_NOT_CUT) {
            ConfirmDialog(
                title = "링크를 끊지 못했어요",
                message = "인터넷에 연결되면 다시 해 주세요. 그래도 지우면 이 휴대폰에서는 지워지고, 보낸 링크는 열어 둔 기간이 끝날 때 닫혀요.",
                confirmLabel = "그래도 지우기",
                destructive = true,
                onDismiss = { vm.consumeMessage(); deleting = false },
                onConfirm = { vm.consumeMessage(); vm.delete(keepLink = true) { deleting = false } },
            )
        } else {
            ConfirmDialog(
                title = "${cal.name} 달력을 지울까요?",
                // [UNDO-03] Kept for 7 days.
                message = "이 달력의 근무와 순서, 고친 날, 돈 기록이 모두 지워져요. 7일 안에는 설정 › 데이터 › 최근 지운 기록에서 되돌릴 수 있어요. " +
                    "공유한 링크는 끊기고, 되돌려도 링크는 다시 만들어야 해요. 이 달력에만 있던 메모는 내 달력으로 옮겨요.",
                confirmLabel = if (busy) "지우는 중…" else "지우기",
                destructive = true,
                onDismiss = { if (!busy) deleting = false },
                onConfirm = { vm.delete(keepLink = false) { deleting = false } },
            )
        }
    }
}

@Composable
private fun EditRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}
