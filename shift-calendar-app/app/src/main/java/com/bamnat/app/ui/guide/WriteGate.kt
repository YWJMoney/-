package com.bamnat.app.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.components.FormSheet
import com.bamnat.core.example.Browse
import com.bamnat.core.example.BrowseChoice
import com.bamnat.core.example.WriteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * [EASY-02] The one place a write on the example calendar is caught (research/1.23-N-설계.md 3장): every screen
 * that writes calls [request] with what it is about to do. Off the example it goes ahead at once; on it, the
 * card asks "내 정보로 시작할까요?" and [proceed] runs only for 계속 둘러보기.
 */
object WriteGate {
    data class Pending(val kind: WriteKind, val proceed: () -> Unit)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun request(c: AppContainer, kind: WriteKind, today: LocalDate = LocalDate.now(), proceed: () -> Unit) {
        val onExample = c.calendars.active.value == PersonalCalendar.EXAMPLE
        if (!Browse.asks(c.browse.current, onExample, kind, today)) proceed() else _pending.value = Pending(kind, proceed)
    }

    /** The card's answer; [start] opens the first setup (with the example's kind of work) for the first two. */
    fun answer(c: AppContainer, choice: BrowseChoice?, start: (keepExample: Boolean) -> Unit, today: LocalDate = LocalDate.now()) {
        val p = _pending.value ?: return
        _pending.value = null
        when (choice) {
            null -> Unit
            BrowseChoice.KEEP_BROWSING -> {
                c.browse.set(Browse.after(c.browse.current, choice, today))
                p.proceed()
            }
            BrowseChoice.START_MINE -> start(false)
            BrowseChoice.KEEP_EXAMPLE -> start(true)
        }
    }
}

/** [EASY-02] The card itself: what was about to happen, why it asks, and the three ways on. */
@Composable
fun WriteGateCard(c: AppContainer, start: (keepExample: Boolean) -> Unit) {
    val pending by WriteGate.pending.collectAsStateWithLifecycle()
    val p = pending ?: return
    FormSheet(
        onDismissRequest = { WriteGate.answer(c, null, start) },
        title = { Text(Browse.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Browse.body(p.kind), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { WriteGate.answer(c, BrowseChoice.START_MINE, start) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(BrowseChoice.START_MINE.label)
                }
                OutlinedButton(onClick = { WriteGate.answer(c, BrowseChoice.KEEP_EXAMPLE, start) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(BrowseChoice.KEEP_EXAMPLE.label)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { WriteGate.answer(c, BrowseChoice.KEEP_BROWSING, start) }) { Text(BrowseChoice.KEEP_BROWSING.label) }
        },
    )
}
