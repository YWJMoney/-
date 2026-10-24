package com.bamnat.app.ui.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.memoBackground
import com.bamnat.core.memo.Memo
import com.bamnat.core.time.Today

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoTrashScreen(vm: MemoTrashViewModel, onBack: () -> Unit, now: () -> Long = Today::millis) {
    val trash by vm.trash.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf<Memo?>(null) }
    val list = trash.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("휴지통") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    if (list.isNotEmpty()) {
                        TextButton(onClick = vm::restoreAll) { Text("모두 되살리기") }
                        TextButton(onClick = { confirmEmpty = true }) { Text("비우기") }
                    }
                },
            )
        },
    ) { padding ->
        if (trash != null && list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(top = 64.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("휴지통이 비어 있어요", style = MaterialTheme.typography.titleMedium)
                Text(
                    "지운 메모는 ${MemoRepository.TRASH_DAYS}일 동안 여기에 남아 있다가 자동으로 사라져요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "지운 메모는 ${MemoRepository.TRASH_DAYS}일이 지나면 완전히 삭제돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.id }) { memo ->
                TrashRow(
                    memo = memo,
                    daysLeft = daysLeft(memo, now()),
                    onRestore = { vm.restore(memo) },
                    onDeleteForever = { confirmForever = memo },
                )
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("휴지통을 비울까요?") },
            text = { Text("메모 ${list.size}개가 완전히 삭제되고 되살릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; vm.emptyTrash() }) { Text("비우기") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("취소") } },
        )
    }
    confirmForever?.let { memo ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            title = { Text("완전히 삭제할까요?") },
            text = { Text("이 메모는 되살릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = { confirmForever = null; vm.deleteForever(memo) }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("취소") } },
        )
    }
}

private fun daysLeft(memo: Memo, now: Long): Int {
    val total = MemoRepository.TRASH_DAYS.toInt()
    val deleted = memo.deletedAtMillis ?: return total
    val passed = ((now - deleted) / 86_400_000L).toInt()
    return (total - passed).coerceAtLeast(0)
}

@Composable
private fun TrashRow(memo: Memo, daysLeft: Int, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = memoBackground(memo.colorIndex), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memo.title.ifBlank { memo.preview }.ifBlank { "(빈 메모)" }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = memo.date?.let { "${Fmt.dayTitle(it)} · " }.orEmpty()
                Text("${date}${daysLeft}일 뒤 삭제", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, "되살리기") }
            IconButton(onClick = onDeleteForever) { Icon(Icons.Outlined.DeleteForever, "완전히 삭제") }
        }
    }
}
