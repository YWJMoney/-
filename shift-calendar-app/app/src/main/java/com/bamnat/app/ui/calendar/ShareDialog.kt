package com.bamnat.app.ui.calendar

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bamnat.app.appContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.share.ShareApi
import com.bamnat.app.share.ShareContent
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkPayload
import com.bamnat.app.share.ShareLinkState
import com.bamnat.app.share.ShareLinkStore
import com.bamnat.app.share.ShareOptions
import com.bamnat.app.share.SharedPay
import com.bamnat.app.ui.Fmt
import com.bamnat.core.work.MoneyShare
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [MKT-09] Picture or link. A link shows about three months from the month on screen, read only,
 * and expires by itself; the phone keeps one link and can refresh it with the latest schedule or cut it.
 * [SHR-01] The sharer picks what goes with the shifts (leave kinds, extra work, memos, pay), all off
 * at first, and sees what leaves the phone before the link is made (privacy policy 1.6).
 * [SHR-02] A received link can be opened here too, to keep it beside the user's own calendar.
 * [SHR-11] The money part follows how the user works: 급여 for employees, 일감 수입 and the day's jobs
 * for field work, 매출 for a shop. It can be ticked whenever there is a figure to send, with or
 * without a pay calculation, and the sheet shows the figures before they leave the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDialog(
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
    client: ShareApi = LocalContext.current.appContainer.shareApi,
    store: ShareLinkStore = LocalContext.current.let { c -> remember { ShareLinkStore(c) } },
    preview: ShareLinkState? = null,
    /** [SHR-02] Opens the sheet that takes a received link; null hides that part. */
    onOpenReceived: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(preview ?: store.load()) }
    var title by remember { mutableStateOf(link?.title.orEmpty()) }
    var ttl by remember { mutableIntStateOf(30) }
    var saved by remember { mutableStateOf(store.options()) }
    var options by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val profile by container.payStore.profile.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState()
    val kind = MoneyShare.of(settings.workStyle)
    // Employees' parts: leave kinds and extra work mean nothing to a shop or a freelancer's calendar.
    val employee = settings.workStyle?.usesPayEngine != false
    val field = kind == MoneyShare.JOBS
    // [SHR-11] The figures a link would carry, worked out here so the row can show them before anything is sent.
    val money by produceState<List<SharedPay>?>(null, kind, month, snapshot, profile) {
        value = orElse(emptyList()) { ShareContent.money(container, snapshot, month.atDay(1), kind) }
    }
    val hasPay = money?.isNotEmpty() == true

    fun run(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() } catch (e: Exception) { error = messageFor(e, options) } finally { busy = false }
        }
    }
    val chosen = {
        options.copy(
            pay = options.pay && hasPay, jobs = options.jobs && field,
            leaveKinds = options.leaveKinds && employee, extras = options.extras && employee,
        )
    }
    suspend fun payload() = ShareContent.payload(container, snapshot, month.atDay(1), title, ttl, chosen())
    // [SHR-11] A calendar with no shifts in these months (a shop, field work, hours not yet written) has
    // nothing to show until something is ticked; say so instead of letting the server refuse it.
    val empty by produceState(false, chosen(), month, snapshot, money) {
        value = orElse(false) { !ShareLinkPayload.hasContent(payload()) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls so a folded cover screen with a large font still reaches the buttons.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("근무표 공유", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { onImage(); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("이 달 이미지로 보내기")
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Link, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("링크로 보내기", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "받는 사람은 ${month.monthValue}월부터 3개월 근무표를 브라우저로 봐요. 밤낮 앱이 있으면 자기 달력 옆에 두고 바꿔 가며 보고, 같이 쉬는 날도 바로 알아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A link already made comes first: sending it again is what most people open this for.
            val made = link
            if (made != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text("만든 링크: ${made.url}\n${expiryText(made)}까지 열려요.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
                Button(enabled = !busy, onClick = { send(context, made) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("이 링크 다시 보내기") }
            }

            Text("보여 줄 것", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    PartRow("근무", if (employee) "이름·색·시간, 늘 들어가요" else "근무를 적은 날만, 이름·색·시간", checked = true, enabled = false) {}
                    if (field) {
                        PartRow("일정", "날마다 일감 이름과 시간, ${ShareLinkPayload.JOBS_PER_DAY}개까지. 고객·장소·금액은 빼요", options.jobs) { options = options.copy(jobs = it) }
                    }
                    if (employee) {
                        PartRow("휴가 종류", "끄면 연차·반차 말고는 '휴가'로만 보여요", options.leaveKinds) { options = options.copy(leaveKinds = it) }
                        PartRow("연장·휴무일 근무", "이름과 시간, 날마다 ${ShareLinkPayload.EXTRAS_PER_DAY}개까지. 끄면 대근만 근무로 보여요", options.extras) { options = options.copy(extras = it) }
                    }
                    PartRow("메모", "날짜마다 첫 줄, ${ShareLinkPayload.MEMO_CHARS}자까지", options.memos) { options = options.copy(memos = it) }
                    PartRow(kind.label, moneyHint(kind, money), options.pay && hasPay, enabled = hasPay) { options = options.copy(pay = it) }
                }
            }
            OutlinedTextField(value = title, onValueChange = { if (it.length <= 30) title = it }, singleLine = true,
                label = { Text("이름 (선택, 예: 민지)") }, modifier = Modifier.fillMaxWidth())
            Text("열어 둘 기간", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShareLinkPayload.TTL_DAYS.forEach { d -> FilterChip(selected = ttl == d, onClick = { ttl = d }, label = { Text("${d}일") }) }
            }
            // [MKT-09][OPS-13][SHR-01] Said before the data leaves the phone, in the policy's words, with what was ticked.
            Text(
                "보내는 것: " + (listOf("근무") + chosen().partNames(kind)).joinToString(", ") + "\n" + SHARE_SERVER_NOTICE,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (empty) {
                Text(emptyHint(kind, field), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            val current = link
            if (current == null) {
                Button(enabled = !busy && !empty, onClick = {
                    run {
                        val made = client.create(payload())
                        store.save(made); store.saveOptions(chosen()); saved = chosen(); link = made
                        container.telemetry.event("share_link_create", chosen().shows.joinToString(",").ifEmpty { "shifts" })
                        send(context, made)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("링크 만들어 보내기")
                }
            } else {
                if (chosen() != saved) {
                    Text("고른 항목이 바뀌었어요. ‘지금 근무표로 고치기’를 누르면 링크에 들어가요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(enabled = !busy && !empty, onClick = {
                    run {
                        val updated = client.update(current, payload())
                        store.save(updated); store.saveOptions(chosen()); saved = chosen(); link = updated
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("지금 근무표로 고치기")
                }
                TextButton(enabled = !busy, onClick = {
                    run { client.delete(current); store.clear(); link = null }
                }, modifier = Modifier.fillMaxWidth()) { Text("링크 끊기", color = MaterialTheme.colorScheme.error) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (onOpenReceived != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenReceived).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("받은 근무표가 있나요?", style = MaterialTheme.typography.titleSmall)
                        Text("받은 링크를 넣으면 내 달력과 바꿔 가며 볼 수 있어요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("넣기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun PartRow(label: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled || checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [SHR-01] The ticked parts in the words the sheet uses; [SHR-11] the money in the user's own kind. */
internal fun ShareOptions.partNames(kind: MoneyShare = MoneyShare.PAY): List<String> = listOfNotNull(
    "일정".takeIf { jobs },
    "휴가 종류".takeIf { leaveKinds },
    "연장·휴무일 근무".takeIf { extras },
    "메모".takeIf { memos },
    kind.label.takeIf { pay },
)

/** A figure worked out for the sheet, or [fallback] when the data cannot be read; leaving the sheet still cancels it. */
private suspend fun <T> orElse(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    fallback
}

/** [SHR-11] What the money row would send, month by month, or where the figure comes from when there is none yet. */
internal fun moneyHint(kind: MoneyShare, money: List<SharedPay>?): String = when {
    money == null -> "금액을 확인하는 중이에요"
    money.isNotEmpty() -> money.joinToString(" · ") { "${it.month.monthValue}월 ${Fmt.won(it.amount)}" } + "\n" + when (kind) {
        MoneyShare.PAY -> "달마다 금액 하나만, 내역은 빼요"
        MoneyShare.JOBS -> "달마다 일감 금액 합계(3.3% 뗀 뒤)만, 고객·장소는 빼요"
        MoneyShare.SALES -> "달마다 매출 합계만, 경비와 날마다 금액은 빼요"
    }
    else -> when (kind) {
        MoneyShare.PAY -> "급여 계산을 설정하거나 돈 탭에 받은 급여를 적으면 고를 수 있어요"
        MoneyShare.JOBS -> "이 기간 일감에 금액을 적으면 고를 수 있어요"
        MoneyShare.SALES -> "이 기간 매출을 적으면 고를 수 있어요. 달력에서 날짜를 눌러 적어요"
    }
}

/** [SHR-11] Why the link button waits: these months have nothing to show yet. */
internal fun emptyHint(kind: MoneyShare, field: Boolean): String = when {
    field -> "이 기간에는 근무가 없어요. ‘일정’이나 ‘${kind.label}’을 고르면 링크를 만들 수 있어요."
    kind == MoneyShare.SALES -> "이 기간에는 근무가 없어요. ‘${kind.label}’이나 ‘메모’를 고르면 링크를 만들 수 있어요."
    else -> "이 기간에는 보낼 근무가 없어요. 근무를 적거나 보여 줄 것을 골라 주세요."
}

/** What leaves the phone with a link, where it is kept, and who can open it. Matches privacy policy 1.6, sections 1 and 5. */
internal const val SHARE_SERVER_NOTICE =
    "링크를 만들면 적은 이름과 근무표, 고른 항목이 공유 서버(Cloudflare, 미국)에 저장되고, 주소를 받은 사람은 누구나 볼 수 있어요. 원하지 않으면 이미지로 보내 주세요."

private fun expiryText(s: ShareLinkState): String =
    s.expiresAt.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("M월 d일"))

private fun messageFor(e: Exception, options: ShareOptions): String = when (e.message) {
    "rate_limited" -> "잠시 뒤 다시 해 주세요. 한 시간에 만들 수 있는 링크 수를 넘었어요."
    "not_found", "forbidden" -> "이 링크는 이미 지워졌어요. 링크 끊기를 누른 뒤 새로 만들어 주세요."
    // A server from before 1.10 takes only shifts and leave.
    // [SHR-11] A server from before 1.13 takes no 일정 and no link without shifts.
    "days" -> "이 기간에는 보낼 근무가 없어요. 보여 줄 것을 골라 주세요."
    "version", "shows" -> if (options.any) "지금은 근무와 휴가만 보낼 수 있어요. 고른 항목을 끄고 다시 해 주세요." else "링크를 만들지 못했어요 (${e.message}). 다시 해 주세요."
    else -> if (e is ShareLinkException) "링크를 만들지 못했어요 (${e.message}). 다시 해 주세요."
    else if (e is java.io.IOException) "인터넷에 연결되지 않았어요. 연결을 확인하고 다시 해 주세요." else "링크를 만들지 못했어요. 다시 해 주세요."
}

private fun send(context: Context, s: ShareLinkState) {
    val text = (if (s.title.isNotBlank()) "${s.title}의 근무표" else "근무표") + "\n${s.url}"
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, "근무표 링크 보내기"))
}
