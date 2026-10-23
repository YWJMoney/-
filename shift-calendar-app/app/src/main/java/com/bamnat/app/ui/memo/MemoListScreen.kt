package com.bamnat.app.ui.memo

import kotlinx.coroutines.Dispatchers
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.bamnat.app.appContainer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.IconCountBadge
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.memoBackground
import com.bamnat.app.ui.theme.MemoPaletteLight
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.memo.Memo
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoListScreen(
    vm: MemoListViewModel,
    onOpen: (Long) -> Unit,
    onNew: (checklist: Boolean, date: LocalDate?) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // [STAB-01] Read at once, on the same frame as the key: a text box one frame behind the keyboard
    // doubles or drops half-built Korean letters and moves the cursor.
    val query by vm.query.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    BackHandler(enabled = state.selecting) { vm.clearSelection() }
    var sharing by remember { mutableStateOf(false) }
    if (sharing) {
        MemoCalendarsDialog(
            initial = setOf(state.activeCalendar),
            calendars = state.calendars,
            title = "고른 메모 ${state.selection.size}개를 어느 달력에 보일까요?",
            onDismiss = { sharing = false },
            onSave = { vm.shareSelected(it); sharing = false },
        )
    }
    // [DAILY-15] 화면 꾸미기: which of the rows above the memos show, and in what order.
    val container = androidx.compose.ui.platform.LocalContext.current.appContainer
    val appSettings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val parts = memoParts(appSettings.memoLayout)
    var arranging by rememberSaveable { mutableStateOf(false) }
    // [DAILY-05] Filter chips fold into the search box's icon unless a filter is on or the user keeps them open.
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val filterOn = state.tag != null ||
        (if (state.tab == MemoTab.CALENDAR) state.calendarFilter != CalendarFilter.ALL else state.generalFilter != GeneralFilter.ALL)
    val fold = parts.any { it.part == MemoPart.FOLD && it.shown }
    val today = LocalDate.now()
    val open: (Memo) -> Unit = { memo -> if (state.selecting) vm.toggleSelected(memo.id) else onOpen(memo.id) }
    val actions = CardActions(
        onOpen = open,
        onSelect = { vm.toggleSelected(it.id) },
        onTogglePin = vm::togglePin,
        onDuplicate = vm::duplicate,
        onDelete = vm::delete,
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selecting) {
                SelectionBar(
                    count = state.selection.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAllVisible,
                    onPin = vm::pinSelected,
                    onColor = vm::colorSelected,
                    onDelete = vm::deleteSelected,
                    // [NOTE-02] Several memos onto other calendars at once.
                    onShare = if (state.manyCalendars) ({ sharing = true }) else null,
                )
            } else {
                Header(state, onSort = vm::setSort, onOpenTrash = onOpenTrash, onArrange = { arranging = true })
            }
            for (p in parts) {
                if (!p.shown) continue
                when (p.part) {
                    MemoPart.NOTEBOOKS -> NotebookBar(state, vm::setNotebook)
                    MemoPart.SEARCH -> OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("제목, 내용, #태그 검색") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            Row {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") }
                                }
                                if (fold) IconButton(onClick = { filtersOpen = !filtersOpen }) {
                                    Icon(Icons.Filled.FilterList, if (filtersOpen) "필터 접기" else "필터 펼치기",
                                        tint = if (filterOn) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    MemoPart.FOLD -> Unit
                }
            }
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
                for (tab in MemoTab.entries) {
                    val count = if (tab == MemoTab.CALENDAR) state.calendarCount else state.generalCount
                    Tab(
                        selected = state.tab == tab,
                        onClick = { vm.setTab(tab) },
                        text = { Text("${tab.label} $count", maxLines = 1) },
                    )
                }
            }
            // With the search box hidden there is no icon to unfold them, so they stay open.
            if (!fold || filtersOpen || filterOn || parts.none { it.part == MemoPart.SEARCH && it.shown }) FilterRow(state, vm)
            when {
                state.loaded && state.shownCount == 0 -> EmptyMemos(state.tab, searching = state.filtering)
                state.tab == MemoTab.CALENDAR -> CalendarMemoList(state, today, actions)
                else -> GeneralMemoGrid(state, actions)
            }
        }

        if (!state.selecting) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // On the calendar tab a new memo starts on today so it lands in that list.
                val date = if (state.tab == MemoTab.CALENDAR) today else null
                // [DAILY-05] One button, then 메모 or 체크리스트 [UX-06]: said in words, a pencil alone was read as "edit".
                var choosing by remember { mutableStateOf(false) }
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { choosing = true },
                        icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        text = { Text("새 메모") },
                        modifier = Modifier.semantics { contentDescription = if (date != null) "오늘 날짜로 새 메모 또는 체크리스트" else "새 메모 또는 체크리스트" },
                    )
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(text = { Text("메모") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { choosing = false; onNew(false, date) })
                        DropdownMenuItem(text = { Text("체크리스트") }, leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                            onClick = { choosing = false; onNew(true, date) })
                    }
                }
            }
        }
    }
    if (arranging) MemoEditSheet(appSettings.memoLayout, container.settingsRepository::update, onDismiss = { arranging = false })
}

/**
 * [DAILY-15] The rows above the memos the user can order or hide (화면 꾸미기). [FOLD] is a switch, not a row:
 * on, the filter chips fold into the search box's icon ([DAILY-05], on unless turned off).
 */
internal enum class MemoPart(val id: String, val label: String, val what: String, val movable: Boolean = true) {
    NOTEBOOKS("notebooks", "공책", "공책을 나눠 쓸 때 위에 고르는 줄"),
    SEARCH("search", "검색 칸", "제목·내용·#태그 찾기"),
    FOLD("fold", "필터는 검색 칸 아이콘으로 접기", "끄면 필터·태그 칩이 늘 펼쳐져 있어요", movable = false),
}

internal fun memoParts(layout: String) = com.bamnat.core.layout.PageLayout.decode(layout).arrange(MemoPart.entries.toList()) { it.id }

@Composable
internal fun MemoEditSheet(layout: String, update: ((com.bamnat.app.data.AppSettings) -> com.bamnat.app.data.AppSettings) -> Unit, onDismiss: () -> Unit) {
    val placed = memoParts(layout)
    fun write(next: List<com.bamnat.core.layout.PageLayout.Placed<MemoPart>>) =
        update { it.copy(memoLayout = com.bamnat.core.layout.PageLayout.of(next) { p -> p.id }.encode()) }
    val rows = { movable: Boolean ->
        placed.filter { it.part.movable == movable }.map { com.bamnat.app.ui.components.EditRow(it.part.id, it.part.label, it.part.what, it.shown, movable, it.isNew) }
    }
    com.bamnat.app.ui.components.PageEditSheet(
        title = "메모 화면 꾸미기",
        groups = listOf(
            com.bamnat.app.ui.components.EditGroup("위쪽 줄 순서", rows(true)),
            com.bamnat.app.ui.components.EditGroup("위쪽 필터", rows(false)),
        ),
        middle = "달력 메모 · 일반 메모",
        onShow = { id, shown -> write(placed.map { if (it.part.id == id) it.copy(shown = shown) else it }) },
        onMove = { id, by ->
            val fixed = placed.filterNot { it.part.movable }
            val movable = placed.filter { it.part.movable }
            write(com.bamnat.core.layout.PageLayout.move(movable, movable.indexOfFirst { it.part.id == id }, by) + fixed)
        },
        onReset = { update { it.copy(memoLayout = "") } },
        onAsk = {
            onDismiss()
            com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "화면 꾸미기"))
        },
        onDismiss = onDismiss,
    )
}

private class CardActions(
    val onOpen: (Memo) -> Unit,
    val onSelect: (Memo) -> Unit,
    val onTogglePin: (Memo) -> Unit,
    val onDuplicate: (Memo) -> Unit,
    val onDelete: (Memo) -> Unit,
)

@Composable
private fun Header(state: MemoListState, onSort: (MemoSort) -> Unit, onOpenTrash: () -> Unit, onArrange: () -> Unit = {}) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("메모", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (state.tab == MemoTab.GENERAL) {
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "정렬: ${state.sort.label}")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for (s in MemoSort.entries) {
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            trailingIcon = { if (s == state.sort) Icon(Icons.Outlined.Check, null) },
                            onClick = {
                                sortMenu = false
                                onSort(s)
                            },
                        )
                    }
                }
            }
        }
        // [DAILY-15] 화면 꾸미기, as on 달력 and 근무·급여.
        IconButton(onClick = onArrange) { Icon(Icons.Outlined.Dashboard, contentDescription = "화면 꾸미기") }
        IconButton(onClick = onOpenTrash) {
            BadgedBox(badge = { if (state.trashCount > 0) IconCountBadge(state.trashCount, "메모 ${state.trashCount}개") }) {
                Icon(Icons.Outlined.Delete, contentDescription = "휴지통")
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onColor: (Int) -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    var palette by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "선택 해제") }
            Text("${count}개 선택", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "모두 선택") }
            IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, "고정 / 해제") }
            Box {
                IconButton(onClick = { palette = true }) { Icon(Icons.Outlined.Palette, "색 바꾸기") }
                DropdownMenu(expanded = palette, onDismissRequest = { palette = false }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in MemoPaletteLight.indices) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(memoBackground(i))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable {
                                        palette = false
                                        onColor(i)
                                    },
                            )
                        }
                    }
                }
            }
            onShare?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Groups, "보일 달력 정하기") } }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "휴지통으로 옮기기") }
        }
    }
}

@Composable
private fun FilterRow(state: MemoListState, vm: MemoListViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.tab == MemoTab.CALENDAR) {
            for (f in CalendarFilter.entries) {
                FilterChip(selected = state.calendarFilter == f, onClick = { vm.setCalendarFilter(f) }, label = { Text(f.label) })
            }
        } else {
            for (f in GeneralFilter.entries) {
                FilterChip(selected = state.generalFilter == f, onClick = { vm.setGeneralFilter(f) }, label = { Text(f.label) })
            }
        }
        for (tag in state.tags.take(MAX_TAG_CHIPS)) {
            FilterChip(selected = state.tag == tag, onClick = { vm.toggleTag(tag) }, label = { Text("#$tag") })
        }
    }
}

@Composable
private fun CalendarMemoList(state: MemoListState, today: LocalDate, actions: CardActions) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fun section(label: String, groups: List<MemoDateGroup>) {
            if (groups.isEmpty()) return
            item(key = "label-$label") { SectionLabel(label) }
            for (group in groups) {
                item(key = "date-${group.date}") { DateHeader(group.date, group.day, today) }
                items(group.memos, key = { it.id }) { memo ->
                    MemoCard(memo, state.query, selected = memo.id in state.selection, selecting = state.selecting,
                        showDate = false, actions = actions)
                }
            }
        }
        section("오늘과 다가오는 날", state.upcoming)
        section("지난 날", state.past)
    }
}

@Composable
private fun GeneralMemoGrid(state: MemoListState, actions: CardActions) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        items(state.general, key = { it.id }) { memo ->
            MemoCard(memo, state.query, selected = memo.id in state.selection, selecting = state.selecting,
                showDate = true, actions = actions)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun DateHeader(date: LocalDate, day: ResolvedDay?, today: LocalDate) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.dayTitle(date), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val days = ChronoUnit.DAYS.between(today, date)
        val badge = when {
            days == 0L -> "오늘"
            days == 1L -> "내일"
            days in 2..99 -> "D-$days"
            else -> null
        }
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Pill(badge, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.weight(1f))
        ShiftLabel(day)
    }
}

/** The day's shift as a colored badge plus its name and hours, e.g. "주간 08:00–20:00". */
@Composable
fun ShiftLabel(day: ResolvedDay?, modifier: Modifier = Modifier) {
    val type = day?.shiftType
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (type == null) {
            Text("근무 없음", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }
        ShiftBadge(type, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        val start = day.start
        val end = day.end
        val text = if (day.isWorkDay && start != null && end != null) {
            "${type.name} ${Fmt.time(start)}–${Fmt.time(end)}"
        } else {
            type.name
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (day.isWorkDay && day.scheduledMinutes > 0) {
            Text(" · ${HoursFormat.short(day.scheduledMinutes)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoCard(
    memo: Memo,
    query: String,
    selected: Boolean,
    selecting: Boolean,
    showDate: Boolean,
    actions: CardActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = memoBackground(memo.colorIndex),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = { actions.onOpen(memo) }, onLongClick = { actions.onSelect(memo) }),
        ) {
            Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (selected) "선택됨" else "선택 안 됨",
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        highlighted(memo.title.ifBlank { memo.preview }, query),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (memo.pinned) {
                        Icon(Icons.Filled.PushPin, "고정됨", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!selecting) {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.MoreVert, "메모 메뉴", Modifier.size(18.dp))
                        }
                    } else {
                        Spacer(Modifier.width(10.dp))
                    }
                }
                Column(Modifier.padding(end = 10.dp)) {
                    MemoContent(memo, query)
                    Footer(memo, showDate)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (memo.pinned) "고정 해제" else "맨 위에 고정") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; actions.onTogglePin(memo) },
            )
            DropdownMenuItem(
                text = { Text("복제") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; actions.onDuplicate(memo) },
            )
            DropdownMenuItem(
                text = { Text("여러 개 선택") },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, null) },
                onClick = { menu = false; actions.onSelect(memo) },
            )
            DropdownMenuItem(
                text = { Text("휴지통으로") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menu = false; actions.onDelete(memo) },
            )
        }
    }
}

@Composable
private fun MemoContent(memo: Memo, query: String) {
    // The title row already shows the preview line when there is no title.
    if (memo.isChecklist) {
        val items = memo.checklist.filter { it.text.isNotBlank() }
        for (item in items.take(6)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(
                    if (item.checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    highlighted(item.text, query),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (items.size > 6) {
            Text("외 ${items.size - 6}개", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    } else if (memo.title.isNotBlank() && memo.body.isNotBlank()) {
        Text(
            highlighted(memo.body.trim(), query),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Footer(memo: Memo, showDate: Boolean) {
    val date = memo.date?.takeIf { showDate }
    val tags = memo.tags
    val progress = memo.isChecklist && memo.itemCount > 0
    val reminder = memo.reminder
    if (date == null && tags.isEmpty() && !progress && reminder == null) return
    Row(
        Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pillColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        if (date != null) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).background(pillColor).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Event, null, Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(Fmt.dayTitle(date), style = MaterialTheme.typography.labelSmall)
            }
        }
        // [SCHED-01] A memo that is a schedule says when it rings or notifies.
        if (reminder != null) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).background(pillColor).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (reminder.ring) Icons.Filled.Alarm else Icons.Filled.EventAvailable, null, Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(scheduleText(reminder.at), style = MaterialTheme.typography.labelSmall)
            }
        }
        for (tag in tags.take(3)) {
            Pill("#$tag", pillColor, MaterialTheme.colorScheme.primary)
        }
        if (progress) {
            Text(
                "${memo.checkedCount}/${memo.itemCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier.clip(MaterialTheme.shapes.small).background(background).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyMemos(tab: MemoTab, searching: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (tab == MemoTab.CALENDAR) Icons.Filled.Event else Icons.AutoMirrored.Outlined.StickyNote2,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                searching -> "찾는 메모가 없어요"
                tab == MemoTab.CALENDAR -> "아직 달력 메모가 없어요"
                else -> "아직 일반 메모가 없어요"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                searching -> "검색어나 필터를 바꿔 보세요"
                tab == MemoTab.CALENDAR -> "달력에서 날짜를 누르거나 아래 버튼으로 오늘 메모를 남겨 보세요. 그날 근무가 함께 보여요."
                else -> "인수인계, 준비물, 교대 약속처럼 날짜와 상관없는 메모를 적어 두세요. #태그로 묶을 수 있어요."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Highlights every case-insensitive match of each word in [query]. */
@Composable
internal fun highlighted(text: String, query: String): AnnotatedString {
    val words = query.trim().split(Regex("\\s+")).map { it.removePrefix("#") }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, fontWeight = FontWeight.Bold)
    val marks = BooleanArray(text.length)
    for (w in words) {
        var i = text.indexOf(w, ignoreCase = true)
        while (i >= 0) {
            for (k in i until i + w.length) marks[k] = true
            i = text.indexOf(w, i + w.length, ignoreCase = true)
        }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val on = marks[i]
            var j = i
            while (j < text.length && marks[j] == on) j++
            if (on) withStyle(style) { append(text.substring(i, j)) } else append(text.substring(i, j))
            i = j
        }
    }
}

private const val MAX_TAG_CHIPS = 12
