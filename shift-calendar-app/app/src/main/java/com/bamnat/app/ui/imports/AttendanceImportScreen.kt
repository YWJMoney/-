package com.bamnat.app.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.imports.AttendanceField
import com.bamnat.core.imports.AttendanceImport
import com.bamnat.core.imports.AttendanceRow
import com.bamnat.core.imports.ColumnMap
import com.bamnat.core.imports.Sheets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * [DAYW-06] 근태 엑셀·CSV 가져오기: a file exported from the company's 근태 system (.xlsx or .csv) is read on the
 * phone, its columns are guessed and can be changed, and after a look at the days the times, leave and
 * overtime go on the calendar on screen. Taken back in one tap. The file is not kept.
 */
class AttendanceImportViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    private val schedule get() = container.calendars.repository(calendarId)

    data class Ui(
        val reading: Boolean = false,
        val fileName: String = "",
        val rows: List<List<String>> = emptyList(),
        val map: ColumnMap? = null,
        val error: String? = null,
        val applied: Boolean = false,
    ) {
        val width: Int get() = rows.maxOfOrNull { it.size } ?: 0
        val days: List<AttendanceRow>
            get() = map?.takeIf { it.ready || AttendanceField.DATE in it.columns }?.let { AttendanceImport.read(rows, it, LocalDate.now().year) }.orEmpty()
        val todo: List<AttendanceRow> get() = days.filter { !it.isEmpty }
        /** What a column is called: its header, or its letter. */
        fun columnName(c: Int): String {
            val head = map?.headerRow?.takeIf { it >= 0 }?.let { rows.getOrNull(it)?.getOrNull(c) }.orEmpty()
            val letter = if (c < 26) ('A' + c).toString() else "${'A' + c / 26 - 1}${'A' + c % 26}"
            return if (head.isNotBlank()) "$letter · $head" else "${letter}열"
        }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun open(uri: Uri) {
        _ui.update { Ui(reading = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = container.appContext.contentResolver
                    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }.orEmpty()
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > Sheets.MAX_BYTES) throw Sheets.SheetException("파일이 너무 커요 (8MB까지)")
                        }
                        out.toByteArray()
                    } ?: throw Sheets.SheetException("파일을 열지 못했어요")
                    name to Sheets.read(bytes)
                }
            }
            result.onSuccess { (name, rows) ->
                val map = AttendanceImport.guess(rows)
                _ui.update {
                    Ui(fileName = name, rows = rows, map = map ?: ColumnMap(0, emptyMap()),
                        error = when {
                            rows.isEmpty() -> "파일에 표가 없어요"
                            map == null -> "날짜 열을 찾지 못했어요. 아래에서 열을 골라 주세요."
                            else -> null
                        })
                }
            }.onFailure { e ->
                _ui.update { Ui(error = (e as? Sheets.SheetException)?.message ?: "이 파일은 읽지 못했어요. 엑셀(.xlsx)이나 CSV로 저장한 파일을 골라 주세요.") }
            }
        }
    }

    fun setColumn(field: AttendanceField, column: Int?) = _ui.update { u ->
        val m = u.map ?: ColumnMap(0, emptyMap())
        val cols = m.columns.filterValues { it != column }.toMutableMap()
        if (column == null) cols.remove(field) else cols[field] = column
        u.copy(map = m.copy(columns = cols), applied = false)
    }

    fun setHeaderRow(row: Int) = _ui.update { u -> u.copy(map = (u.map ?: ColumnMap(0, emptyMap())).copy(headerRow = row), applied = false) }

    fun apply() {
        val todo = _ui.value.todo.associateBy { it.date }
        if (todo.isEmpty()) return
        val repo = schedule // an undo stays with the calendar it was made in
        viewModelScope.launch {
            val resolver = repo.snapshot.first().resolver
            val before = repo.updateDays(todo.keys.sorted()) { e ->
                AttendanceImport.apply(resolver.resolve(e.date), e, todo.getValue(e.date))
            }
            _ui.update { it.copy(applied = true) }
            _messages.emit(UndoMessage("근태 ${todo.size}일을 달력에 넣었어요") {
                before.forEach { (d, e) -> repo.restoreDay(d, e) }
                _ui.update { it.copy(applied = false) }
            })
        }
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceImportScreen(vm: AttendanceImportViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var choosing by remember { mutableStateOf<AttendanceField?>(null) }
    var choosingHeader by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("근태 엑셀 가져오기") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ui.rows.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                val n = ui.todo.size
                Button(onClick = vm::apply, enabled = n > 0 && !ui.applied,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 52.dp)) {
                    Text(when {
                        ui.applied -> "넣었어요"
                        n == 0 -> "넣을 날이 없어요"
                        else -> "${n}일 달력에 넣기"
                    })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "회사 근태 기록을 달력으로") {
                    Text("회사 근태 시스템에서 내려받은 엑셀(.xlsx)이나 CSV 파일을 골라요. 날짜·출근·퇴근·연장·휴가 칸을 읽어서 " +
                        "늦은 출근, 이른 퇴근, 야근(잔업), 쉬는 날 근무(특근), 연차·반차를 그날에 적어요. 파일은 휴대폰 안에서만 읽고 따로 저장하지 않아요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        picker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values",
                            "text/plain", "application/vnd.ms-excel", "application/octet-stream",
                        ))
                    }, enabled = !ui.reading, modifier = Modifier.fillMaxWidth()) { Text(if (ui.rows.isEmpty()) "파일 고르기" else "다른 파일 고르기") }
                    Text("옛 엑셀 형식(.xls)은 엑셀이나 구글 시트에서 .xlsx나 CSV로 다시 저장해 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.reading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            ui.error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
            if (ui.rows.isNotEmpty()) item {
                SectionCard(title = "어느 열이 무엇인가요?") {
                    Text(ui.fileName.ifBlank { "고른 파일" } + " · ${ui.rows.size}줄", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MapRow("제목 줄", ui.map?.headerRow?.let { if (it < 0) "없음" else "${it + 1}번째 줄" } ?: "-") { choosingHeader = true }
                    AttendanceField.entries.forEach { f ->
                        HorizontalDivider()
                        MapRow(f.label, ui.map?.columns?.get(f)?.let(ui::columnName) ?: "안 씀") { choosing = f }
                    }
                }
            }
            val days = ui.days
            if (ui.rows.isNotEmpty() && days.isNotEmpty()) {
                item {
                    Text("미리 보기 · ${days.size}일 중 ${ui.todo.size}일을 적어요", style = MaterialTheme.typography.titleSmall)
                }
                items(days.take(62), key = { it.date.toEpochDay() }) { d -> DayLine(d) }
                if (days.size > 62) item { Text("외 ${days.size - 62}일", style = MaterialTheme.typography.bodySmall) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    choosing?.let { f ->
        PickDialog(title = f.label, options = listOf(null) + (0 until ui.width).toList(), label = { it?.let(ui::columnName) ?: "안 씀" },
            sample = { c -> c?.let { col -> ui.rows.drop((ui.map?.headerRow ?: -1) + 1).firstOrNull { it.getOrNull(col).orEmpty().isNotBlank() }?.getOrNull(col) } },
            onDismiss = { choosing = null }) { vm.setColumn(f, it); choosing = null }
    }
    if (choosingHeader) {
        PickDialog(title = "제목 줄", options = listOf(-1) + (0 until minOf(ui.rows.size, 10)).toList(),
            label = { if (it < 0) "제목 줄 없음" else "${it + 1}번째 줄" },
            sample = { r -> ui.rows.getOrNull(r)?.filter { it.isNotBlank() }?.joinToString(" · ") },
            onDismiss = { choosingHeader = false }) { vm.setHeaderRow(it); choosingHeader = false }
    }
}

@Composable
private fun MapRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DayLine(d: AttendanceRow) {
    val parts = listOfNotNull(
        if (d.hasTimes) "${Fmt.time(d.start)}–${Fmt.time(d.end)}" else null,
        d.leave?.label,
        if (d.absent) "결근" else null,
        d.overtimeMinutes?.takeIf { it > 0 }?.let { "연장 ${HoursFormat.korean(it)}" },
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.shortDate(d.date), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), modifier = Modifier.width(56.dp))
        Text(parts.joinToString(" · ").ifEmpty { "적을 것 없음" + d.kind.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() },
            style = MaterialTheme.typography.bodyMedium,
            color = if (d.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun <T> PickDialog(title: String, options: List<T>, label: (T) -> String, sample: (T) -> String?, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { o ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 10.dp)) {
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                        sample(o)?.takeIf { it.isNotBlank() }?.let {
                            Text("예) $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
