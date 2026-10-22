package com.bamnat.app.ui.memo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bamnat.app.ui.appViewModel
import java.time.LocalDate

/**
 * [DAILY-15] The memo open in the right pane of an unfolded screen: its id (0 for a new one until the first
 * save), how a new one starts, and [n], which changes each time another memo opens so its editor starts afresh.
 */
data class MemoPane(val id: Long, val checklist: Boolean = false, val date: LocalDate? = null, val n: Int = 0) {
    companion object {
        /** Kept across a rotation or a fold as one line: id, checklist, date, n. */
        val Saver = androidx.compose.runtime.saveable.Saver<MemoPane?, String>(
            save = { p -> p?.let { "${it.id}|${it.checklist}|${it.date ?: ""}|${it.n}" } ?: "" },
            restore = { s ->
                s.split('|').takeIf { it.size == 4 }?.let { (id, checklist, date, n) ->
                    MemoPane(id.toLong(), checklist.toBoolean(), date.takeIf { it.isNotEmpty() }?.let(LocalDate::parse), n.toInt())
                }
            },
        )
    }
}

/**
 * [DAILY-15] 메모 on an unfolded screen: the list on the left 40%, the open memo on the right 60%, so moving
 * between memos needs no back and forth. [list] is the usual list screen; [open] is null while none is open.
 */
@Composable
fun MemoPanes(open: MemoPane?, onOpenChange: (MemoPane?) -> Unit, onNew: () -> Unit, list: @Composable () -> Unit) {
    BackHandler(enabled = open != null) { onOpenChange(null) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.4f).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(0.6f).fillMaxHeight()) {
            if (open == null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("왼쪽에서 메모를 고르면 여기에서 열려요", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = onNew) { Text("새 메모") }
                }
            } else key(open.n) {
                PaneEditor(open, onSaved = { id -> onOpenChange(open.copy(id = id)) }, onClose = { onOpenChange(null) })
            }
        }
    }
}

/**
 * The editor with a ViewModel of its own: closing the pane or opening another memo clears it, and clearing
 * saves what was typed (as leaving the full-screen editor does).
 */
@Composable
private fun PaneEditor(pane: MemoPane, onSaved: (Long) -> Unit, onClose: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm = appViewModel(key = "memo-pane") { MemoEditorViewModel(it, pane.id, pane.date, pane.checklist, true) }
        val memo by vm.memo.collectAsStateWithLifecycle()
        // A new memo gets its id on the first save; a rotation then reopens that memo, not a blank one.
        LaunchedEffect(memo.id) { if (memo.id != 0L && memo.id != pane.id) onSaved(memo.id) }
        MemoEditorScreen(vm = vm, onBack = onClose)
    }
}
