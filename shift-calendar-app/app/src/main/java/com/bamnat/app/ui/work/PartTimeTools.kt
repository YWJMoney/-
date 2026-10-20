package com.bamnat.app.ui.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.QuitRecord
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.WorkToolsStore
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.app.ui.pay.label
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoReminder
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.HourlyWork
import com.bamnat.core.work.JobOffer
import com.bamnat.core.work.OfferCompare
import com.bamnat.core.work.QuitCheck
import com.bamnat.core.work.WorkContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.roundToLong

/**
 * [HRLY-09][HRLY-10][HRLY-11] 알바 도구 on a part-time calendar's 통계: 두 알바 비교, 근로계약서 and
 * 그만두는 날 정리, with a reminder when the contract is about to end.
 */
@Composable
fun HourlyToolsCard(calendarId: String, profile: PayProfile?, snapshot: ScheduleSnapshot, today: LocalDate = LocalDate.now()) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // [GUIDE-01][GUIDE-04] Opened from the guide or the help search; opening a tool counts as using it.
    com.bamnat.app.ui.guide.FocusedTool("offers", "hourly.offers") { open = "offers" }
    com.bamnat.app.ui.guide.FocusedTool("contract", "hourly.contract") { open = "contract" }
    com.bamnat.app.ui.guide.FocusedTool("quit", "hourly.quit") { open = "quit" }
    LaunchedEffect(open) { open?.let { com.bamnat.app.data.GuideStore.get(context).used("hourly.$it") } }
    val contract = tools.contract
    contract.daysLeft(today)?.takeIf { contract.endingSoon(today) }?.let { left ->
        WarningBanner(
            if (left >= 0) "근로계약이 ${if (left == 0L) "오늘" else "${left}일 뒤"} 끝나요" else "근로계약이 끝났어요",
            "${Fmt.fullDate(contract.end!!)}까지예요. 계속 일한다면 새 계약서를 받아 두세요.",
            action = "계약서", onAction = { open = "contract" },
        )
    }
    SectionCard(title = "알바 도구") {
        ToolLink("두 알바 비교하기", "시급·주휴·교통비까지 따져 시간당 실제로 남는 돈", { open = "offers" })
        HorizontalDivider()
        ToolLink(
            "근로계약서",
            when {
                contract.isEmpty -> "사진과 시급·근무 요일·계약 기간을 보관해요"
                contract.end != null -> "사진 ${contract.photos.size}장 · ${Fmt.shortFullDate(contract.end!!)}까지"
                else -> "사진 ${contract.photos.size}장" + if (contract.hourlyWage > 0) " · 시급 ${Fmt.won(contract.hourlyWage)}" else ""
            },
            { open = "contract" },
            accent = if (contract.wageDiffers(HourlyWork.hourlyRate(profile, today))) LocalCalendarColors.current.warning else null,
        )
        HorizontalDivider()
        ToolLink(
            "그만두는 날 정리",
            tools.quit?.let { "${Fmt.monthDay(it.lastWorkDay)}까지 일함 · 받을 돈 다시 보기" } ?: "마지막 주 주휴·연차수당·퇴직금 확인하고 근무 멈추기",
            { open = "quit" },
        )
    }
    when (open) {
        "offers" -> OfferCompareScreen(calendarId, profile, snapshot, today) { open = null }
        "contract" -> ContractScreen(calendarId, profile, today) { open = null }
        "quit" -> QuitScreen(calendarId, profile, snapshot, today) { open = null }
    }
}

/** Average paid hours a week over the four 주휴 weeks up to the one holding [until]. */
internal fun averageWeeklyHours(snapshot: ScheduleSnapshot, profile: PayProfile?, until: LocalDate): Double {
    val weekStart = profile?.holidayWeekFrom ?: DayOfWeek.MONDAY
    val resolve = snapshot.copy(legalBreaks = profile?.legalBreaks == true).resolver::resolve
    val last = until.with(TemporalAdjusters.previousOrSame(weekStart))
    return (0L..3L).sumOf { HourlyWork.week(last.minusWeeks(it), resolve).minutes } / 4.0 / 60.0
}

private fun Double.oneDecimal(): Double = (this * 10).roundToLong() / 10.0

/**
 * [HRLY-09] 두 알바 비교: two to four offers side by side, each worked out by [OfferCompare]. The first
 * starts from this calendar's own wage, hours and tax, so "지금 알바 vs 새 알바" is one field away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferCompareScreen(calendarId: String, profile: PayProfile?, snapshot: ScheduleSnapshot, today: LocalDate, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val offers = remember {
        mutableStateListOf<JobOffer>().apply {
            val saved = store.current(calendarId).offers
            if (saved.size >= 2) addAll(saved) else {
                add(JobOffer(
                    name = "지금 알바", hourlyWage = HourlyWork.hourlyRate(profile, today) ?: 0,
                    hoursPerWeek = averageWeeklyHours(snapshot, profile, today.minusWeeks(1)).oneDecimal(),
                    fiveOrMore = profile?.fiveOrMoreEmployees ?: true, taxMode = profile?.taxMode ?: TaxMode.NONE,
                ))
                add(JobOffer(name = "새 알바"))
            }
        }
    }
    LaunchedEffect(offers.toList()) { store.update(calendarId) { it.copy(offers = offers.toList()) } }
    val minimum = RateTable.on(today).minimumHourlyWage.toLong()
    val results = offers.map { OfferCompare.of(it, minimum) }
    val best = OfferCompare.best(results)
    ToolScreen("두 알바 비교", onClose, action = if (offers.size < 4) "더하기" else null, onAction = { offers.add(JobOffer(name = "알바 ${offers.size + 1}")) }) {
        Hint("시급만 보면 놓치기 쉬운 주휴수당·가산·세금·교통비를 같이 따져요. 한 달은 ${"%.2f".format(com.bamnat.core.work.WEEKS_PER_MONTH)}주로 셌어요.")
        // Results side by side first, so the answer is on top.
        SectionCard(title = "한 달에 남는 돈") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(92.dp)) {
                    listOf("", "세전", "실수령", "교통비", "남는 돈", "일한 시간당", "실제 시간당").forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp), maxLines = 1)
                    }
                }
                results.forEachIndexed { i, r ->
                    val top = i == best
                    Column(Modifier.width(112.dp)) {
                        Text(r.offer.name.ifBlank { "알바 ${i + 1}" } + if (top) " ★" else "", style = MaterialTheme.typography.labelLarge,
                            color = if (top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp), maxLines = 1)
                        listOf(r.monthlyGross, r.monthlyNet, -r.monthlyCommuteCost, r.monthlyKept, r.perWorkHour, r.realPerHour).forEachIndexed { k, v ->
                            Text(if (k == 2 && v < 0) "−${Fmt.won(-v)}" else Fmt.won(v),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (k == 5) FontWeight.Bold else FontWeight.Normal),
                                color = if (k == 5 && top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 3.dp), maxLines = 1)
                        }
                    }
                }
            }
            best?.let {
                val b = results[it]
                val others = results.filterIndexed { i, _ -> i != it }.maxOfOrNull { r -> r.realPerHour } ?: 0
                Hint("${b.offer.name.ifBlank { "알바 ${it + 1}" }}${Fmt.iga(b.offer.name.ifBlank { "알바" })} 출퇴근 시간까지 쳐서 1시간에 ${Fmt.won(b.realPerHour - others)} 더 남아요.",
                    Modifier.padding(top = 8.dp))
            }
            Hint("실제 시간당 = (실수령 − 교통비) ÷ (일한 시간 + 오가는 시간). 세금은 참고용 대략값이에요.", Modifier.padding(top = 4.dp))
        }
        offers.forEachIndexed { i, offer ->
            // Typed fields start again from the numbers when the list changes shape.
            androidx.compose.runtime.key(i, offers.size) { OfferEditor(offer, results[i], minimum, onChange = { offers[i] = it }, onRemove = if (offers.size > 2) ({ offers.removeAt(i) }) else null) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferEditor(offer: JobOffer, result: com.bamnat.core.work.OfferResult, minimum: Long, onChange: (JobOffer) -> Unit, onRemove: (() -> Unit)?) {
    SectionCard(title = offer.name.ifBlank { "알바" }, trailing = onRemove?.let { { IconButton(onClick = it) { Icon(Icons.Outlined.Delete, "이 알바 빼기") } } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(offer.name, { onChange(offer.copy(name = it.take(16))) }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField("시급", amountDigits(offer.hourlyWage), { onChange(offer.copy(hourlyWage = it.toAmount())) })
            if (result.belowMinimum) Text("최저임금 ${Fmt.won(minimum)}보다 적어요", style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var hours by remember { mutableStateOf(offer.hoursPerWeek.asField()) }
                NumberField("한 주 시간", hours, { hours = it; onChange(offer.copy(hoursPerWeek = it.toNumber())) }, Modifier.weight(1f), "시간", decimal = true)
                NumberField("한 주 며칠", offer.daysPerWeek.takeIf { it > 0 }?.toString().orEmpty(),
                    { onChange(offer.copy(daysPerWeek = (it.toIntOrNull() ?: 0).coerceAtMost(7))) }, Modifier.weight(1f), "일", maxLength = 1)
            }
            SwitchLine("주휴수당 줘요", offer.holidayPay, { onChange(offer.copy(holidayPay = it)) },
                if (result.holidayEligible) "한 주 ${Fmt.won(result.weeklyHolidayPay)}" else if (offer.hoursPerWeek < 15) "주 15시간이 안 되면 없어요" else "안 주는 곳으로 셌어요")
            SwitchLine("5명 이상 일하는 곳", offer.fiveOrMore, { onChange(offer.copy(fiveOrMore = it)) }, "야간·휴일·주 40시간 넘는 시간에 50% 더 받아요")
            if (offer.fiveOrMore) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var night by remember { mutableStateOf(offer.nightHoursPerWeek.asField()) }
                var rest by remember { mutableStateOf(offer.holidayHoursPerWeek.asField()) }
                NumberField("밤 10시~6시 (주)", night, { night = it; onChange(offer.copy(nightHoursPerWeek = it.toNumber())) }, Modifier.weight(1f), "시간", decimal = true)
                NumberField("휴일 근무 (주)", rest, { rest = it; onChange(offer.copy(holidayHoursPerWeek = it.toNumber())) }, Modifier.weight(1f), "시간", decimal = true)
            }
            Text("세금", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaxMode.entries.forEach { m -> FilterChip(selected = offer.taxMode == m, onClick = { onChange(offer.copy(taxMode = m)) }, label = { Text(m.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("오가는 시간 (왕복)", offer.commuteMinutes.takeIf { it > 0 }?.toString().orEmpty(),
                    { onChange(offer.copy(commuteMinutes = it.toIntOrNull() ?: 0)) }, Modifier.weight(1f), "분", maxLength = 3)
                AmountField("교통비 (왕복)", amountDigits(offer.commuteCost), { onChange(offer.copy(commuteCost = it.toAmount())) }, Modifier.weight(1f))
            }
        }
    }
}

/** [HRLY-10] Contract photos in the calendar's own folder, picked with the photo picker (no permission). */
private object ContractPhotos {
    private const val MAX_SIDE = 2000

    suspend fun import(context: Context, store: WorkToolsStore, calendarId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val name = UUID.randomUUID().toString() + ".jpg"
            File(store.photoDir(calendarId).apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            name
        }.getOrNull()
    }

    suspend fun load(file: File?, side: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file?.takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > side * 2) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * [HRLY-10] 근로계약서: photos (add, look, replace, delete) and the key fields typed from it — 시급, 근무
 * 요일, 계약 기간 and a note — with a reminder 7 days before the end as a memo notification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContractScreen(calendarId: String, profile: PayProfile?, today: LocalDate, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(tools.contract) }
    var remind by remember { mutableStateOf(tools.contractMemo != null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var replacing by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val name = ContractPhotos.import(context, store, calendarId, uri) ?: return@launch
            val old = replacing
            draft = draft.copy(photos = if (old != null) draft.photos.map { if (it == old) name else it } else draft.photos + name)
            replacing = null
        }
    }
    fun launchPick(replace: String?) {
        replacing = replace
        runCatching { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    fun save() {
        val saved = tools.contract
        // Photos taken off are deleted from the folder once saved.
        (saved.photos - draft.photos.toSet()).forEach { store.photo(calendarId, it)?.delete() }
        val end = draft.end
        val memoId = tools.contractMemo
        store.update(calendarId) { it.copy(contract = draft) }
        c.appScope.launch {
            val memos = c.memoRepository
            val existing = memoId?.let { memos.get(it) }
            if (remind && end != null && !end.minusDays(7).isBefore(today)) {
                val memo = (existing ?: Memo()).copy(
                    date = end, title = "근로계약 끝나는 날",
                    body = "${Fmt.fullDate(end)}에 근로계약이 끝나요. 계속 일한다면 새 계약서를 받아 두세요.",
                    calendars = setOf(calendarId), deletedAtMillis = null,
                    reminder = MemoReminder(end.minusDays(7).atTime(LocalTime.of(10, 0)), ring = false, notify = true),
                )
                val id = memos.save(memo).id
                store.update(calendarId) { it.copy(contractMemo = id) }
            } else {
                if (existing != null) memos.delete(existing, offerUndo = false)
                store.update(calendarId) { it.copy(contractMemo = null) }
            }
        }
        onClose()
    }

    // Photos picked but not saved are left behind when closing without 저장.
    val close = {
        (draft.photos - tools.contract.photos.toSet()).forEach { store.photo(calendarId, it)?.delete() }
        onClose()
    }
    ToolScreen("근로계약서", close, action = "저장", onAction = ::save) {
        SectionCard(title = "사진") {
            if (draft.photos.isEmpty()) Hint("계약서를 찍은 사진을 넣어 두면 시급이나 근무 시간이 헷갈릴 때 바로 꺼내 볼 수 있어요. 이 휴대폰에만 있어요.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.photos.forEach { name ->
                    val thumb by produceState<ImageBitmap?>(null, name) { value = ContractPhotos.load(store.photo(calendarId, name), 200) }
                    Box(Modifier.size(96.dp).clip(MaterialTheme.shapes.small).clickable { viewing = name }) {
                        thumb?.let { Image(it, "계약서 사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
            if (draft.photos.size < WorkContract.MAX_PHOTOS) {
                AssistChip(onClick = { launchPick(null) }, label = { Text("사진 넣기") },
                    leadingIcon = { Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp)) })
            }
        }
        SectionCard(title = "계약 내용") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("시급", amountDigits(draft.hourlyWage), { draft = draft.copy(hourlyWage = it.toAmount()) })
                val payWage = HourlyWork.hourlyRate(profile, today)
                if (draft.wageDiffers(payWage)) {
                    Text("급여 설정의 시급은 ${Fmt.won(payWage!!)}이에요. 어느 쪽이 맞는지 확인해 보세요.",
                        style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
                }
                Text("근무 요일", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(selected = d in draft.workDays, label = { Text(Fmt.dayOfWeekShort(d)) },
                            onClick = { draft = draft.copy(workDays = if (d in draft.workDays) draft.workDays - d else draft.workDays + d) })
                    }
                }
                DateField("계약 시작일", draft.start, { draft = draft.copy(start = it) })
                DateField("계약 종료일", draft.end, { draft = draft.copy(end = it) })
                if (draft.end != null) {
                    SwitchLine("끝나기 7일 전에 알려 주기", remind, { remind = it }, "메모로 남기고 알림을 보내요")
                }
                OutlinedTextField(draft.note, { draft = draft.copy(note = it.take(300)) }, label = { Text("메모 (휴게·수습·특약 등)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
        Hint("근로계약서는 일을 시작할 때 꼭 받아야 해요(근로기준법 제17조). 못 받았다면 사장님께 달라고 하거나 고용노동부 1350에 물어볼 수 있어요.")
    }
    viewing?.let { name ->
        PhotoViewer(store.photo(calendarId, name), onClose = { viewing = null },
            onReplace = { viewing = null; launchPick(name) },
            onDelete = { viewing = null; draft = draft.copy(photos = draft.photos - name) })
    }
}

@Composable
private fun PhotoViewer(file: File?, onClose: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, file) { value = ContractPhotos.load(file, 1400) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("닫기") }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onReplace) { Text("바꾸기") }
                    TextButton(onClick = onDelete) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    image?.let { Image(it, "계약서 사진", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        ?: Text("사진을 열 수 없어요", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * [HRLY-11] 그만두는 날 정리: from the last day worked and the last day employed, what is still owed —
 * the last week's 주휴 (2021년 고용노동부 해석: 그 주 개근 + 그 주 끝까지 재직이면 다음 주 근무가 없어도 발생),
 * 미사용 연차수당 at a place of 5 or more, 퇴직금 (1년 이상·주 15시간 이상) and the day it must all be paid
 * by — then, after asking, the calendar's repeating schedule stops after that day.
 */
@Composable
private fun QuitScreen(calendarId: String, profile: PayProfile?, snapshot: ScheduleSnapshot, today: LocalDate, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val store = remember { WorkToolsStore.get(context) }
    val saved = remember { store.current(calendarId).quit }
    var lastWork by remember { mutableStateOf(saved?.lastWorkDay ?: today) }
    var employedPick by remember { mutableStateOf(saved?.lastEmployed ?: saved?.lastWorkDay ?: today) }
    var hire by remember { mutableStateOf(profile?.hireDate ?: saved?.hire) }
    var pay3 by remember { mutableStateOf(amountDigits(saved?.threeMonthPay ?: 0)) }
    var stopped by remember { mutableStateOf(saved?.scheduleStopped == true) }
    var confirmStop by remember { mutableStateOf(false) }
    // The last day employed is never before the last day worked.
    val lastEmployed = maxOf(employedPick, lastWork)

    val wage = HourlyWork.hourlyRate(profile, lastWork) ?: store.current(calendarId).contract.hourlyWage
    val weekStart = profile?.holidayWeekFrom ?: DayOfWeek.MONDAY
    val resolve = snapshot.copy(legalBreaks = profile?.legalBreaks == true).resolver::resolve
    val week = remember(snapshot, lastWork, weekStart) {
        HourlyWork.week(lastWork.with(TemporalAdjusters.previousOrSame(weekStart)), resolve).let { w ->
            // Days after the last day worked are not worked, whatever the schedule says.
            val cut = (0L..6L).map { w.start.plusDays(it) }.filter { it.isAfter(lastWork) }.sumOf { resolve(it).totalMinutes }
            w.copy(minutes = w.minutes - cut)
        }
    }
    val last = QuitCheck.lastWeek(week, lastEmployed, wage)
    val weekly = remember(snapshot, lastWork) { averageWeeklyHours(snapshot, profile, lastWork).oneDecimal() }
    val five = profile?.fiveOrMoreEmployees ?: true
    val leaveMade = QuitCheck.leaveDays(hire, lastEmployed, weekly)
    var leaveText by remember { mutableStateOf((saved?.unusedLeave ?: leaveMade).toString()) }
    val leaveDays = leaveText.toIntOrNull() ?: 0
    val leavePay = if (five) leaveDays * QuitCheck.leaveDayPay(weekly, wage) else 0
    val sev = QuitCheck.severance(hire, lastEmployed, weekly, wage, pay3.toAmount().takeIf { it > 0 })
    val total = last.pay + leavePay + sev.amount

    fun record() = QuitRecord(lastWork, lastEmployed, leaveText.toIntOrNull(), stopped, hire.takeIf { profile?.hireDate == null }, pay3.toAmount().takeIf { it > 0 })

    ToolScreen("그만두는 날 정리", onClose, action = "저장", onAction = {
        store.update(calendarId) { it.copy(quit = record()) }
        onClose()
    }) {
        SectionCard(title = "날짜") {
            DateField("마지막으로 일한 날", lastWork, { if (it != null) lastWork = it }, clearable = false)
            DateField("회사에 다닌 마지막 날 (사직서에 적은 날)", lastEmployed, { if (it != null) employedPick = it }, clearable = false)
            if (profile?.hireDate == null) DateField("입사일", hire, { hire = it })
            else Hint("입사일 ${Fmt.fullDate(profile.hireDate!!)} (급여 설정)")
            Hint("최근 4주 평균 한 주 ${weekly}시간 · 시급 ${Fmt.won(wage)}로 셌어요.")
        }
        SectionCard(title = "마지막 주 주휴수당") {
            Text("${Fmt.shortDate(last.weekStart)}~${Fmt.shortDate(last.weekEnd)} · ${"%.1f".format(last.minutes / 60.0)}시간" +
                if (last.absent > 0) " · 결근 ${last.absent}일" else "", style = MaterialTheme.typography.bodyMedium)
            ValueLine("받을 돈", if (last.owed) Fmt.won(last.pay) else "없어요", strong = true)
            Hint(when {
                last.owed -> "그 주를 다 나왔고 주가 끝나는 ${Fmt.monthDay(last.weekEnd)}까지 회사에 다녀서 주휴수당이 생겨요."
                !last.enoughHours -> "그 주 일한 시간이 15시간이 안 돼서 주휴수당이 없어요."
                last.absent > 0 -> "그 주에 결근한 날이 있어서 주휴수당이 없어요. 지각·조퇴는 괜찮아요."
                else -> "${Fmt.monthDay(last.weekEnd)}(주가 끝나는 날)까지 다니면 생겨요. 지금은 그 전에 그만두는 걸로 셌어요."
            })
            Hint("고용노동부는 2021년 8월부터 '다음 주 근무가 없어도 그 주를 다 나오고 그 주 끝까지 다녔으면 주휴수당을 준다'고 봐요. 앱은 이 해석으로 셌어요.")
            if (!last.employedThroughWeek && last.enoughHours && last.absent == 0) {
                OutlinedButton(onClick = { employedPick = last.weekEnd }) { Text("${Fmt.monthDay(last.weekEnd)}까지 다니는 걸로 보기") }
            }
        }
        SectionCard(title = "남은 연차수당") {
            if (!five) Hint("5명 미만 사업장은 연차휴가가 법으로 정해져 있지 않아요.")
            else {
                NumberField("쓰지 않은 연차", leaveText, { leaveText = it }, suffix = "일", maxLength = 2)
                Hint(if (leaveMade > 0) "이 기간에 생긴 연차는 약 ${leaveMade}일이에요. 쓴 날을 빼고 적어 주세요." else "주 15시간이 안 되거나 한 달을 못 채우면 연차가 없어요.")
                ValueLine("받을 돈", Fmt.won(leavePay), strong = true)
                Hint("하루치 = 하루 일하는 시간(한 주 시간 ÷ 5, 최대 8시간) × 시급으로 셌어요.")
            }
        }
        SectionCard(title = "퇴직금") {
            Text(sev.reason, style = MaterialTheme.typography.bodyMedium)
            if (sev.eligible) {
                AmountField("최근 3개월 받은 돈 (선택)", pay3, { pay3 = it })
                ValueLine("1일 평균임금", Fmt.won(sev.averageDaily))
                ValueLine("받을 돈", Fmt.won(sev.amount), strong = true)
                Hint("1일 평균임금 × 30일 × 다닌 날 ÷ 365로 셌어요. 3개월 받은 돈을 적지 않으면 근무 시간으로 대략 셌어요.")
            } else Hint("1년 이상, 4주 평균 주 15시간 이상 일해야 생겨요(근로자퇴직급여 보장법).")
        }
        SectionCard(title = "모두 합쳐") {
            ValueLine("받아야 할 돈 (참고)", Fmt.won(total), strong = true, color = MaterialTheme.colorScheme.primary)
            Hint("마지막 달 월급과 함께 ${Fmt.fullDate(QuitCheck.payBy(lastEmployed))}까지 받아야 해요(그만두고 14일 안). 늦어지면 고용노동부 1350에 물어볼 수 있어요.")
        }
        SectionCard(title = "근무표") {
            if (stopped) Hint("${Fmt.monthDay(lastEmployed)} 뒤 반복 근무를 멈췄어요. 다시 일하게 되면 근무 형태·주기 화면에서 이어 가요.")
            else {
                Hint("${Fmt.monthDay(lastEmployed)} 뒤로는 달력에 근무가 나오지 않게 해요. 손으로 적은 날은 그대로 둬요.")
                OutlinedButton(onClick = { confirmStop = true }) { Text("이날 뒤 근무 멈추기") }
            }
        }
    }
    if (confirmStop) {
        ConfirmDialog(
            title = "근무를 멈출까요?",
            message = "${Fmt.fullDate(lastEmployed)} 뒤 반복 근무가 달력에서 빠지고 알람도 울리지 않아요. 그 전 날들은 그대로예요.",
            confirmLabel = "멈추기",
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                stopped = true
                val end = lastEmployed
                store.update(calendarId) { it.copy(quit = record().copy(scheduleStopped = true)) }
                c.appScope.launch { c.scopeOf(calendarId).schedule.endPatternsAfter(end) }
            },
            destructive = true,
        )
    }
}
