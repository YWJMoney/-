package com.shiftmate.calendar.ui.memo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.memoBackground
import com.shiftmate.core.memo.Memo

@Composable
fun MemoListScreen(
    vm: MemoListViewModel,
    onOpen: (Long) -> Unit,
    onNew: (checklist: Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "메모",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("제목, 내용, 체크 항목 검색") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in MemoFilter.entries) {
                    FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                }
            }
            if (state.loaded && state.memos.isEmpty()) {
                EmptyMemos(searching = state.query.isNotBlank() || state.filter != MemoFilter.ALL)
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    items(state.memos, key = { it.id }) { memo ->
                        MemoCard(
                            memo = memo,
                            query = state.query,
                            onClick = { onOpen(memo.id) },
                            onTogglePin = { vm.togglePin(memo) },
                            onDelete = { vm.delete(memo) },
                        )
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = { onNew(true) }) {
                Icon(Icons.Filled.Checklist, contentDescription = "새 체크리스트")
            }
            FloatingActionButton(onClick = { onNew(false) }) {
                Icon(Icons.Outlined.Edit, contentDescription = "새 메모")
            }
        }
    }
}

@Composable
private fun EmptyMemos(searching: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.StickyNote2,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (searching) "찾는 메모가 없어요" else "아직 메모가 없어요",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (searching) "검색어나 필터를 바꿔 보세요" else "인수인계, 준비물, 근무 교대 약속을 적어 두세요. 달력의 날짜에도 붙일 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoCard(
    memo: Memo,
    query: String,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, fontWeight = FontWeight.Bold)
    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = memoBackground(memo.colorIndex),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    if (memo.title.isNotBlank()) {
                        Text(
                            highlighted(memo.title, query, highlight),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (memo.pinned) {
                        Icon(Icons.Filled.PushPin, "고정됨", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
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
                                highlighted(item.text, query, highlight),
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
                } else if (memo.body.isNotBlank()) {
                    Text(
                        highlighted(memo.body.trim(), query, highlight),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (memo.title.isNotBlank()) 6.dp else 0.dp),
                    )
                }
                val date = memo.date
                if (date != null || (memo.isChecklist && memo.itemCount > 0)) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (date != null) {
                            Row(
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Event, null, Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(Fmt.dayTitle(date), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (memo.isChecklist && memo.itemCount > 0) {
                            Text(
                                "${memo.checkedCount}/${memo.itemCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (memo.pinned) "고정 해제" else "맨 위에 고정") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = {
                    menu = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text("삭제") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** Highlights every case-insensitive match of [query]. */
private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(q, startIndex = i, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, hit))
            withStyle(style) { append(text.substring(hit, hit + q.length)) }
            i = hit + q.length
        }
    }
}
