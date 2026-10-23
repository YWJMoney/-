package com.bamnat.app.ui.guide

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.components.FormSheet
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.journal.Journal
import com.bamnat.core.journal.JournalEntry
import com.bamnat.core.journal.JournalKind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [OBS-02] 설정 › 밤낮이 한 일: what the app did by itself, by day, newest first, each line with why and the place
 * to look closer or take it back. On this phone only, never in a backup; kinds and small numbers, never an amount.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    val c = LocalContext.current.appContainer
    val entries by c.journal.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { c.journal.load() } }
    val zone = ZoneId.systemDefault()
    val days = remember(entries) { Journal.byDay(entries.orEmpty(), zone) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("밤낮이 한 일") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("밤낮이 알아서 한 일을 이유와 함께 남겨요. 이 휴대폰에만 ${Journal.KEEP_DAYS}일 동안 있고, 금액·이름·메모 글은 적지 않아요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entries != null && days.isEmpty()) {
                    Text("아직 한 일이 없어요. 알람을 다시 잡거나 백업을 하면 여기에 적혀요.", style = MaterialTheme.typography.bodyLarge)
                }
                days.forEach { (day, list) ->
                    SectionCard(title = dayTitle(day, today)) {
                        list.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            JournalLine(e, zone)
                        }
                    }
                }
                if (days.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                        Text("기록 지우기")
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (confirmClear) {
        FormSheet(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 지울까요?") },
            text = { Text("밤낮이 한 일의 기록만 지워요. 알람·백업·급여는 그대로예요.") },
            confirmButton = {
                Button(onClick = { confirmClear = false; scope.launch { c.journal.clear() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("그만두기") } },
        )
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN)

private fun dayTitle(day: LocalDate, today: LocalDate) = when (day) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> day.format(DAY)
}

/** One line: what, at what time, why, and where to look closer (되돌리기 where that place can take it back). */
@Composable
private fun JournalLine(e: JournalEntry, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Journal.text(e), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { heading() })
            Text("${e.at.atZone(zone).format(TIME)} · 왜: ${e.kind.why}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.kind.route?.let { route ->
            TextButton(onClick = { FeatureLinks.open(route) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionOf(e.kind)) }
        }
    }
}

/** Where a kind's place can take it back, the button says so. */
private fun actionOf(kind: JournalKind) = when (kind) {
    JournalKind.MONEY_CAPTURED -> "되돌리기"
    JournalKind.BACKUP_FAILED -> "고치기"
    else -> "자세히"
}
