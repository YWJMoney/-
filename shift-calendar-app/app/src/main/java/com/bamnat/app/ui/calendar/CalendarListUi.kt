package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.CalendarKey
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.share.ReceivedCalendar
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkState
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.asColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** [MCAL-04] One row of the calendar list, as the switcher and the list screen draw it. */
sealed interface CalendarRow {
    val key: CalendarKey
    /** What the row is called in the list and the bar above the calendar. */
    val title: String

    /** One of the user's own calendars, with the link made for it if there is one [MCAL-03]. */
    data class Mine(val cal: PersonalCalendar, val link: ShareLinkState?) : CalendarRow {
        override val key: CalendarKey get() = CalendarKey.Mine(cal.id)
        override val title: String get() = cal.name
    }

    data class Got(val cal: ReceivedCalendar) : CalendarRow {
        override val key: CalendarKey get() = CalendarKey.Received(cal.id)
        override val title: String get() = cal.name
    }

    companion object {
        /** [MCAL-04] The user's own calendars and the received ones in one list, in the user's order. */
        fun of(
            order: List<String>,
            mine: List<PersonalCalendar>,
            received: List<ReceivedCalendar>,
            link: (String) -> ShareLinkState? = { null },
        ): List<CalendarRow> {
            val mineById = mine.associateBy { it.id }
            val gotById = received.associateBy { it.id }
            return CalendarKey.arrange(order, mine.map { it.id }, received.map { it.id }).mapNotNull { k ->
                when (k) {
                    is CalendarKey.Mine -> mineById[k.id]?.let { Mine(it, link(it.id)) }
                    is CalendarKey.Received -> gotById[k.id]?.let(::Got)
                }
            }
        }
    }
}

/** [MCAL-01] The user's own calendars are rounded squares; received ones, people, are circles. */
private val MineShape: Shape = RoundedCornerShape(30)

@Composable
internal fun PersonalCalendar.Avatar(size: Dp = 28.dp) =
    if (isMain) MyAvatar(size = size) else CalendarAvatar(initial, color.asColor(), size = size, shape = MineShape)

@Composable
internal fun CalendarRow.Avatar(size: Dp = 28.dp) = when (this) {
    is CalendarRow.Mine -> cal.Avatar(size)
    is CalendarRow.Got -> cal.Avatar(size)
}

/** [MCAL-03] "링크 공유 중 · 12월 1일까지", or nothing when this calendar has no open link. */
internal fun ShareLinkState.openText(now: Instant = Instant.now()): String? =
    if (expiresAt.isAfter(now)) "링크 공유 중 · ${Fmt.monthDay(expiresAt.atZone(ZoneId.systemDefault()).toLocalDate())}까지" else null

private fun CalendarRow.Mine.subtitle(): String {
    val what = if (cal.isMain) "내 근무와 메모" else cal.style?.label ?: "직접 적는 달력"
    return listOfNotNull(what, link?.openText()).joinToString(" · ")
}

/**
 * [SHR-02][MCAL-01] Whose calendar is on screen, like the account on a mail app: a tap on the name
 * opens the switcher, a tap on a face switches at once. There once there is more than one calendar.
 */
@Composable
fun CalendarIdentityBar(
    rows: List<CalendarRow>,
    current: CalendarRow,
    /** The user's own calendar to go back to from a received one. */
    home: PersonalCalendar,
    onOpenSwitcher: () -> Unit,
    onPick: (CalendarRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val currentName = (current as? CalendarRow.Got)?.cal?.fullName ?: current.title
        Surface(
            onClick = onOpenSwitcher,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "달력 바꾸기, 지금 $currentName" },
        ) {
            Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                current.Avatar()
                Spacer(Modifier.width(8.dp))
                Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        if (current is CalendarRow.Mine) {
            // Faces to switch with one tap, in the list's order.
            val faces = rows.filter { it.key != current.key }
            faces.take(3).forEach { row ->
                val name = (row as? CalendarRow.Got)?.cal?.fullName ?: row.title
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onPick(row) }
                        .semantics { contentDescription = "$name 보기" },
                    contentAlignment = Alignment.Center,
                ) { row.Avatar(size = 30.dp) }
            }
            if (faces.size > 3) {
                TextButton(onClick = onOpenSwitcher) { Text("+${faces.size - 3}") }
            }
        } else {
            Surface(onClick = { onPick(rows.firstOrNull { it.key == CalendarKey.Mine(home.id) } ?: CalendarRow.Mine(home, null)) },
                shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    home.Avatar(size = 26.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (home.isMain) "내 달력으로" else "${home.name}${Fmt.ro(home.name)}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp))
                }
            }
        }
    }
}

/** [SHR-02][MCAL-01] The calendar switcher: every calendar in the list's order, then ways to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSwitcherSheet(
    rows: List<CalendarRow>,
    currentKey: CalendarKey,
    onPick: (CalendarRow) -> Unit,
    onAddCalendar: () -> Unit,
    /** Null where links cannot be received (no share server). */
    onAddReceived: (() -> Unit)?,
    onManage: () -> Unit,
    onRemove: (ReceivedCalendar) -> Unit,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<ReceivedCalendar?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("어느 달력을 볼까요?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (rows.size > 1) TextButton(onClick = onManage) {
                    Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("순서·관리")
                }
            }
            rows.forEach { row ->
                when (row) {
                    is CalendarRow.Mine -> SwitchRow(
                        avatar = { row.cal.Avatar(size = 40.dp) },
                        title = row.cal.name,
                        subtitle = row.subtitle(),
                        selected = currentKey == row.key,
                        onClick = { onPick(row) },
                    )
                    is CalendarRow.Got -> SwitchRow(
                        avatar = { row.cal.Avatar(size = 40.dp) },
                        title = row.cal.fullName,
                        subtitle = row.cal.summary(),
                        detail = row.cal.schedule?.showsText()?.let { "함께 받은 것: $it" },
                        selected = currentKey == row.key,
                        dim = row.cal.gone,
                        onClick = { onPick(row) },
                        trailing = {
                            IconButton(onClick = { removing = row.cal }) { Icon(Icons.Outlined.PersonRemove, "${row.cal.fullName} 목록에서 빼기") }
                        },
                    )
                }
            }
            AddRow("새 달력 만들기", onAddCalendar, MineShape, rows.newCalendarBadge())
            onAddReceived?.let { AddRow("받은 링크로 달력 넣기", it, CircleShape) }
            Text(
                "새 달력은 근무와 순서를 따로 갖고, 링크도 따로 공유해요. " +
                    (if (onAddReceived != null) "받은 달력은 읽기 전용이고 보낸 분이 링크를 끊으면 여기서도 지워져요." else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
    removing?.let { cal ->
        ConfirmDialog(
            title = "${cal.fullName}${Fmt.obj(cal.fullName)} 뺄까요?",
            message = "이 휴대폰에서만 빠지고 보낸 분의 링크는 그대로예요. 링크를 다시 넣으면 또 볼 수 있어요.",
            confirmLabel = "빼기",
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = { onRemove(cal); removing = null },
        )
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit, shape: Shape, badge: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(shape).border(1.5.dp, MaterialTheme.colorScheme.outline, shape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        badge?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** [MCAL-07] "프로" beside 새 달력 만들기 once the free added calendars are made. */
internal fun List<CalendarRow>.newCalendarBadge(): String? =
    Tier.PRO.label.takeIf { count { it is CalendarRow.Mine && !it.cal.isMain } >= PersonalCalendar.FREE_ADDED }

/**
 * [MCAL-02] Naming a new calendar before its setup. The name and colour can be changed later in the
 * list; the questions that follow are the first launch's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewCalendarDialog(
    suggested: String, colorIndex: Int, onCreate: (String, Long) -> Unit, onDismiss: () -> Unit,
    /** [MCAL-07] This one would be past the free ones, so a pro calendar. */
    pro: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableLongStateOf(PersonalCalendar.COLORS[colorIndex % PersonalCalendar.COLORS.size]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 달력 만들기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "근무 형태부터 처음 시작할 때처럼 하나씩 물어볼게요. 지금 달력은 그대로예요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pro) Text(proNote(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= PersonalCalendar.MAX_NAME) name = it }, singleLine = true,
                    label = { Text("이름") }, placeholder = { Text(suggested) }, modifier = Modifier.fillMaxWidth(),
                )
                ColorRow(color) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim().ifEmpty { suggested }, color) }) { Text("다음") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** [MCAL-07] What a calendar past the free ones is, before and after paid features open. */
internal fun proNote(): String =
    if (FeatureGate.BILLING_LIVE) "추가 달력 ${PersonalCalendar.FREE_ADDED}개는 무료이고, 이 달력부터는 ${Tier.PRO.label} 기능이에요."
    else "추가 달력 ${PersonalCalendar.FREE_ADDED}개는 무료이고, 그다음부터는 ${Tier.PRO.label} 기능이 될 거예요. 지금은 모든 기능이 무료예요."

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonalCalendar.COLORS.forEachIndexed { i, c ->
            val on = c == selected
            Box(
                Modifier.size(40.dp).clip(MineShape).background(c.asColor())
                    .clickable(role = Role.RadioButton) { onPick(c) }
                    .semantics { this.selected = on; contentDescription = "색 ${i + 1}" },
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(Icons.Filled.Check, null, tint = Color.White) }
        }
    }
}

/** [MCAL-01][MCAL-04] The calendar list: order, names, colours, alarms, and taking one out. */
class CalendarsViewModel(private val c: AppContainer) : ViewModel() {
    private val links = MutableStateFlow(0)

    val rows: StateFlow<List<CalendarRow>> =
        combine(c.calendars.order, c.calendars.calendars, c.received.calendars, links) { order, mine, got, _ ->
            CalendarRow.of(order, mine, if (c.shareApi.enabled) got else emptyList()) { id -> c.shareLinksOf(id).link.value }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Reads the links again, e.g. after coming back from the share sheet. */
    fun refreshLinks() { links.value++ }

    /** [MCAL-04] Moves row [index] one place up ([by] = -1) or down. */
    fun move(index: Int, by: Int) {
        val list = rows.value.map { it.key }
        val to = index + by
        if (index !in list.indices || to !in list.indices) return
        c.calendars.arrange(list.toMutableList().apply { add(to, removeAt(index)) })
        c.telemetry.event("calendar_order")
    }

    fun rename(id: String, name: String) = c.calendars.rename(id, name)
    fun recolor(id: String, color: Long) = c.calendars.recolor(id, color)
    fun setAlarms(id: String, on: Boolean) = c.calendars.setAlarms(id, on)
    fun removeReceived(id: String) = c.received.remove(id)

    /**
     * [MCAL-01][MCAL-03] Removes an added calendar. Its open link is cut first, so what it showed stops
     * showing; without internet that fails and the calendar stays, unless [keepLink] says to go anyway
     * and let the link close on its own date.
     */
    fun delete(id: String, keepLink: Boolean = false, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val link = c.shareLinksOf(id).link.value
            val cut = link == null || keepLink || try {
                c.shareApi.delete(link)
                true
            } catch (e: ShareLinkException) {
                // Already gone on the server is as good as cut.
                e.message == "not_found"
            } catch (e: IOException) {
                false
            }
            if (cut) {
                c.forgetShareLinks(id)
                c.calendars.delete(id)
                c.telemetry.event("calendar_delete", if (link != null) "with_link" else "")
                onDone()
            } else {
                _message.value = LINK_NOT_CUT
            }
            _busy.value = false
        }
    }

    companion object {
        const val LINK_NOT_CUT = "링크를 끊지 못했어요"
    }
}

/** [MCAL-01][MCAL-04] 설정 > 달력 목록, and 순서·관리 from the switcher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(vm: CalendarsViewModel, onBack: () -> Unit, onAddCalendar: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<PersonalCalendar?>(null) }
    var removing by remember { mutableStateOf<ReceivedCalendar?>(null) }
    val haptics = LocalHapticFeedback.current
    val move: (Int, Int) -> Unit = { i, by ->
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        vm.move(i, by)
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshLinks() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("달력 목록") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = { TextButton(onClick = onAddCalendar) { Text("새 달력") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(
                    "달력 바꾸기 목록과 달력 위 얼굴 순서예요. 화살표로 옮겨요. 달력마다 근무와 순서, 공유 링크가 따로예요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    OrderedRow(
                        row = row,
                        index = i,
                        count = rows.size,
                        onUp = { move(i, -1) },
                        onDown = { move(i, 1) },
                        onOpen = {
                            when (row) {
                                is CalendarRow.Mine -> editing = row.cal.id
                                is CalendarRow.Got -> removing = row.cal
                            }
                        },
                    )
                }
                Spacer(Modifier.padding(4.dp))
                AddRow("새 달력 만들기", onAddCalendar, MineShape, rows.newCalendarBadge())
            }
        }
    }
    val editRow = rows.firstOrNull { it is CalendarRow.Mine && it.cal.id == editing } as? CalendarRow.Mine
    editRow?.let { row ->
        CalendarEditDialog(
            row = row,
            onRename = { vm.rename(row.cal.id, it) },
            onColor = { vm.recolor(row.cal.id, it) },
            onAlarms = { vm.setAlarms(row.cal.id, it) },
            onDelete = { editing = null; deleting = row.cal },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { cal ->
        if (message == CalendarsViewModel.LINK_NOT_CUT) {
            ConfirmDialog(
                title = "링크를 끊지 못했어요",
                message = "인터넷에 연결되면 다시 해 주세요. 그래도 지우면 이 휴대폰에서는 지워지고, 보낸 링크는 열어 둔 기간이 끝날 때 닫혀요.",
                confirmLabel = "그래도 지우기",
                destructive = true,
                onDismiss = { vm.consumeMessage(); deleting = null },
                onConfirm = { vm.consumeMessage(); vm.delete(cal.id, keepLink = true) { deleting = null } },
            )
        } else {
            ConfirmDialog(
                title = "${cal.name} 달력을 지울까요?",
                message = "이 달력의 근무와 순서, 고친 날이 모두 지워지고 되돌릴 수 없어요. 공유한 링크도 끊어요. 메모와 다른 달력은 그대로예요.",
                confirmLabel = if (busy) "지우는 중…" else "지우기",
                destructive = true,
                onDismiss = { if (!busy) deleting = null },
                onConfirm = { vm.delete(cal.id) { deleting = null } },
            )
        }
    }
    removing?.let { cal ->
        ConfirmDialog(
            title = "${cal.fullName}${Fmt.obj(cal.fullName)} 뺄까요?",
            message = "이 휴대폰에서만 빠지고 보낸 분의 링크는 그대로예요. 링크를 다시 넣으면 또 볼 수 있어요.",
            confirmLabel = "빼기",
            destructive = true,
            onDismiss = { removing = null },
            onConfirm = { vm.removeReceived(cal.id); removing = null },
        )
    }
}

@Composable
private fun OrderedRow(row: CalendarRow, index: Int, count: Int, onUp: () -> Unit, onDown: () -> Unit, onOpen: () -> Unit) {
    val first = index == 0
    val last = index == count - 1
    val name = (row as? CalendarRow.Got)?.cal?.fullName ?: row.title
    val subtitle = when (row) {
        is CalendarRow.Mine -> row.subtitle() + if (!row.cal.isMain && !row.cal.alarms) " · 알람 끔" else ""
        is CalendarRow.Got -> "받은 달력 · ${row.cal.summary()}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = if (row is CalendarRow.Mine) "고치기" else "빼기", onClick = onOpen)
            // One stop for TalkBack: "2번째, 알바", with 위로·아래로 as actions.
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    CustomAccessibilityAction("위로") { onUp(); true }.takeIf { !first },
                    CustomAccessibilityAction("아래로") { onDown(); true }.takeIf { !last },
                )
            }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.Avatar(size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "$name 위로") }
        IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "$name 아래로") }
    }
}

/** [MCAL-01] One of the user's calendars: its name, colour, alarms and, for an added one, removing it. */
@Composable
private fun CalendarEditDialog(
    row: CalendarRow.Mine,
    onRename: (String) -> Unit,
    onColor: (Long) -> Unit,
    onAlarms: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cal = row.cal
    var name by remember(cal.id) { mutableStateOf(cal.name) }
    val save = { if (name.isNotBlank()) onRename(name) }
    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        title = { Text(if (cal.isMain) "내 달력" else "달력 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= PersonalCalendar.MAX_NAME) name = it }, singleLine = true,
                    label = { Text("이름") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, modifier = Modifier.fillMaxWidth(),
                )
                if (!cal.isMain) {
                    ColorRow(cal.color, onColor)
                    ListItem(
                        headlineContent = { Text("근무 알람 울리기") },
                        supportingContent = { Text("이 달력 근무의 알람도 내 달력 알람처럼 울려요") },
                        trailingContent = { Switch(checked = cal.alarms, onCheckedChange = onAlarms) },
                        modifier = Modifier.clickable(role = Role.Switch) { onAlarms(!cal.alarms) },
                    )
                } else {
                    Text("알람, 위젯, 알림, 급여 계산은 내 달력을 따라요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.link?.openText()?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("$it. 링크는 이 달력을 보면서 공유 버튼에서 고치거나 끊어요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text("완료") } },
        dismissButton = {
            if (!cal.isMain) TextButton(onClick = onDelete) { Text("달력 지우기", color = MaterialTheme.colorScheme.error) }
        },
    )
}

/**
 * [MCAL-02] "새 달력 만들기" from anywhere: a calendar left half set up is gone on with; otherwise
 * the name comes first, then the setup. Returns what to call to start.
 */
@Composable
fun rememberAddCalendar(c: AppContainer, onSetup: (String) -> Unit): () -> Unit {
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val setup by androidx.compose.runtime.rememberUpdatedState(onSetup)
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val tier by c.featureGate.tier.collectAsStateWithLifecycle()
    val made = calendars.count { !it.isMain }
    val pro = made >= PersonalCalendar.FREE_ADDED
    if (naming && pro && !FeatureGate.allows(tier, Feature.MORE_CALENDARS)) {
        // [MCAL-07] Past the free calendars once paid features are on: said, not made.
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("달력을 더 만들려면 ${Tier.PRO.label}가 필요해요") },
            text = { Text("내 달력과 추가 달력 ${PersonalCalendar.FREE_ADDED}개는 무료예요. ${Tier.PRO.label}에서 달력을 더 만들 수 있어요.") },
            confirmButton = { TextButton(onClick = { naming = false }) { Text("확인") } },
        )
    } else if (naming) {
        NewCalendarDialog(
            pro = pro,
            suggested = "달력 ${made + 2}",
            colorIndex = made,
            onDismiss = { naming = false },
            onCreate = { name, color ->
                naming = false
                scope.launch {
                    c.calendarSetupDrafts.clear()
                    c.calendars.dropUnfinished()
                    val id = c.calendars.begin(name, color)
                    c.telemetry.event("calendar_new")
                    setup(id)
                }
            },
        )
    }
    return remember(c) {
        {
            val left = c.calendars.unfinished()
            if (left != null && c.calendarSetupDrafts.draft.value != null) setup(left.id) else naming = true
        }
    }
}

/**
 * [MCAL-06] Which of the user's calendars 돈, 근무·급여 and 받을 돈 show, once there is more than one:
 * the one on the calendar tab, switched here too. Nothing shows with only the main calendar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScopeChips(c: AppContainer, modifier: Modifier = Modifier) {
    val list by c.calendars.calendars.collectAsStateWithLifecycle()
    val active by c.calendars.active.collectAsStateWithLifecycle()
    if (list.size < 2) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { cal ->
            androidx.compose.material3.FilterChip(
                selected = cal.id == active,
                onClick = {
                    if (cal.id != active) c.telemetry.event("calendar_switch", if (cal.isMain) "main" else "added")
                    c.calendars.setActive(cal.id)
                },
                label = { Text(cal.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(MineShape).background(cal.color.asColor())) },
            )
        }
    }
}
