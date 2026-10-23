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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
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
import com.bamnat.app.share.ShareExpiryReminder
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkPayload
import com.bamnat.app.share.ShareLinkKey
import com.bamnat.app.share.ShareLinkState
import com.bamnat.app.share.feedUrl
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
 * [SHR-12] A link follows the calendar by itself ([com.bamnat.app.share.ShareSync]) unless the sharer
 * turns that off, keeps its end date when it does, and can be kept open longer from here or the notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDialog(
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
    client: ShareApi = LocalContext.current.appContainer.shareApi,
    /** [MCAL-03] The calendar on screen; each of the user's calendars has a link of its own. */
    calendar: com.bamnat.app.data.PersonalCalendar? = null,
    /** [PROV-04] Which of the calendar's links the sheet opens on, from 1. */
    startSlot: Int = 1,
    preview: ShareLinkState? = null,
    /** [SHR-02] Opens the sheet that takes a received link; null hides that part. */
    onOpenReceived: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    // [MCAL-03][MCAL-06] An added calendar shares as its own kind of work, with its own pay, jobs or sales.
    val calendarId = calendar?.id ?: com.bamnat.app.data.PersonalCalendar.MAIN
    val added = calendarId != com.bamnat.app.data.PersonalCalendar.MAIN
    // [PROV-04] 프로 keeps up to five links per calendar, each to whom and for as long as the sharer likes.
    val gate by container.featureGate.state.collectAsState()
    val moreLinks = gate.allows(com.bamnat.app.tier.Feature.MORE_LINKS)
    val feedOpen = gate.allows(com.bamnat.app.tier.Feature.ICS_FEED)
    val links = ShareLinkKey.all(calendarId).map { container.shareLinksOf(it).link.collectAsState().value }
    var slot by remember { mutableIntStateOf(startSlot.coerceIn(1, ShareLinkKey.MAX)) }
    val store = remember(slot) { container.shareLinksOf(ShareLinkKey.of(calendarId, slot)) }
    val stored by store.link.collectAsState()
    val unsent by store.unsent.collectAsState()
    val link = preview ?: stored
    // A second link starts with the name the first one went out with.
    var title by remember(store) { mutableStateOf((link ?: links.firstNotNullOfOrNull { it })?.title.orEmpty()) }
    var ttl by remember(store) { mutableIntStateOf(link?.ttlDays?.let { ShareLinkPayload.allowedTtl(it, moreLinks) } ?: 30) }
    var auto by remember(store) { mutableStateOf(store.auto) }
    var saved by remember(store) { mutableStateOf(store.options()) }
    var options by remember(store) { mutableStateOf(saved) }
    var feed by remember(store) { mutableStateOf(store.feed) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    // [PROV-04] Without 프로 a second link is kept until its end date but not kept open longer.
    val extraLocked = slot > ShareLinkKey.FREE && !moreLinks
    val profile by container.calendars.pay(calendarId).profile.collectAsState()
    val settings by remember(calendarId) { container.scopeOf(calendarId).settings }.collectAsState(container.scopeOf(calendarId).currentSettings())
    val style = settings.workStyle
    val kind = MoneyShare.of(style)
    // Employees' parts: leave kinds and extra work mean nothing to a shop or a freelancer's calendar.
    val employee = style?.usesPayEngine != false
    val field = kind == MoneyShare.JOBS
    // [SHR-11] The figures a link would carry, worked out here so the row can show them before anything is sent.
    val money by produceState<List<SharedPay>?>(null, kind, month, snapshot, profile) {
        value = orElse(emptyList()) { ShareContent.money(container, snapshot, month.atDay(1), kind, calendarId) }
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
    suspend fun payload(ttlDays: Int? = ttl) = ShareContent.payload(container, snapshot, month.atDay(1), title, ttlDays, chosen(), calendarId, feed)
    /** [SHR-12] Keeps what was sent, so the automatic update sends only what changes after it. */
    fun keep(made: ShareLinkState, sent: org.json.JSONObject) {
        store.saveOptions(chosen()); saved = chosen()
        store.save(made.copy(from = month), ShareLinkPayload.fingerprint(sent))
        store.sendResult(java.time.Instant.now())
    }
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
            if (added && calendar != null) {
                // [MCAL-03] Which calendar this link is for, and that the others keep their own.
                Text("${calendar.name} 달력만 보내요. 다른 달력은 각자 링크를 따로 만들어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { onImage(); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("이 달 이미지로 보내기")
            }
            // [PROV-05] 근무표 PDF (프로): the month on screen or its year, on A4 to print or keep.
            val pdfOpen = container.featureGate.state.collectAsState().value.allows(com.bamnat.app.tier.Feature.ROSTER_PDF)
            fun rosterPdf(year: Boolean) {
                if (!pdfOpen) { com.bamnat.app.ui.pro.openPro(); return }
                val name = when {
                    title.isNotBlank() -> "${title.trim()}의 근무표"
                    added && calendar != null -> "${calendar.name} 근무표"
                    else -> "근무표"
                }
                scope.launch {
                    runCatching {
                        val today = java.time.LocalDate.now()
                        val doc = if (year) com.bamnat.core.report.RosterReport.year(month.year, snapshot.resolver::resolve, name, today)
                        else com.bamnat.core.report.RosterReport.month(month, snapshot.resolver::resolve, name, today)
                        com.bamnat.app.report.ReportShare.pdf(context, doc,
                            if (year) com.bamnat.core.report.RosterReport.fileName(month.year) else com.bamnat.core.report.RosterReport.fileName(month))
                        container.telemetry.event("roster_pdf", if (year) "year" else "month")
                    }.onFailure { error = "PDF를 만들지 못했어요" }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { rosterPdf(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${month.monthValue}월 PDF")
                }
                OutlinedButton(onClick = { rosterPdf(true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${month.year}년 PDF")
                }
                if (!pdfOpen) com.bamnat.app.ui.money.ProPill()
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
            // [PROV-04] The calendar's links side by side once there is one, with a new one for 프로.
            if (preview == null && links.any { it != null }) {
                LinkChips(links, slot, moreLinks, onPick = { slot = it }, onNew = {
                    if (moreLinks) links.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { slot = it + 1 }
                    else com.bamnat.app.ui.pro.openPro()
                })
            }
            // A link already made comes first: sending it again is what most people open this for.
            val made = link
            if (made != null) {
                // [SHR-12] Closing soon is said in the accent colour, with the way to keep it open below.
                val soon = ShareExpiryReminder.closingSoon(made)
                Surface(shape = RoundedCornerShape(12.dp),
                    color = if (soon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text("만든 링크: ${made.url}\n${expiryText(made)}까지 열려요." + if (soon) " 곧 닫혀요. 아래 ‘기간 늘리기’로 더 열어 둘 수 있어요." else "",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
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
            // [SHR-12] With a link made, the period is what 기간 늘리기 adds from today.
            Text(if (link == null) "열어 둘 기간" else "기간 늘리기", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                ShareLinkPayload.TTL_DAYS.forEach { d ->
                    // [PROV-04] 180일 and 1년 are 프로's.
                    val locked = d in ShareLinkPayload.PRO_TTL_DAYS && !moreLinks
                    FilterChip(selected = ttl == d, onClick = { if (locked) com.bamnat.app.ui.pro.openPro() else ttl = d },
                        label = { Text(ShareLinkPayload.ttlLabel(d)) })
                }
                if (!moreLinks) com.bamnat.app.ui.money.ProPill(Modifier.align(Alignment.CenterVertically))
            }
            // [SHR-12] On by default: whoever got the link sees the calendar as it is now.
            PartRow("고치면 링크도 바로 바꾸기", (if (added) "이 달력을" else "내 달력을") + " 고치면 몇 초 뒤 링크에도 들어가요. 받은 사람 앱은 다음에 열 때 새로 받아요", auto) {
                auto = it; store.auto = it
                if (it) scope.launch { container.shareSync.sync(store.calendarId) }
            }
            // [FEAT-15] The same days as a calendar feed, for Google, iPhone or Outlook calendars on a computer.
            FeedRow(
                on = feed && feedOpen, open = feedOpen, busy = busy, url = link?.takeIf { feed && feedOpen }?.feedUrl(),
                onChange = { on ->
                    if (!feedOpen) { com.bamnat.app.ui.pro.openPro(); return@FeedRow }
                    feed = on; store.feed = on
                    // A link already made takes it at once; a new one goes out with it.
                    if (link != null && preview == null) run {
                        when (container.shareSync.resend(store.key)) {
                            com.bamnat.app.share.ShareSync.Result.Offline -> {
                                feed = !on; store.feed = !on
                                error = "인터넷 연결을 확인하고 다시 해 주세요"
                            }
                            else -> container.telemetry.event("share_feed", if (on) "on" else "off")
                        }
                    }
                },
                onCopy = { url -> copy(context, url) },
            )
            // [MKT-09][OPS-13][SHR-01] Said before the data leaves the phone, in the policy's words, with what was ticked.
            Text(
                // [FEAT-15] The feed also sends the phone's time zone name (처리방침 2.6, 1항).
                "보내는 것: " + (listOf("근무") + chosen().partNames(kind) + listOfNotNull("휴대폰 시간대 이름".takeIf { feed && feedOpen }))
                    .joinToString(", ") + "\n" + SHARE_SERVER_NOTICE,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (empty) {
                Text(emptyHint(kind, field), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            val current = link
            // [SRV-04] The developer can pause new links for a while; links already made keep working.
            val createPaused = current == null && container.remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.SHARE_CREATE)
            if (createPaused) {
                Text(com.bamnat.core.ops.ServerSwitch.pausedText(com.bamnat.core.ops.ServerSwitch.SHARE_CREATE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (current == null) {
                Button(enabled = !busy && !empty && !createPaused, onClick = {
                    if (extraLocked) { com.bamnat.app.ui.pro.openPro(); return@Button }
                    run {
                        val sent = payload()
                        val made = client.create(sent)
                        keep(made, sent)
                        container.telemetry.event("share_link_create", chosen().shows.joinToString(",").ifEmpty { "shifts" } + if (slot > 1) ",slot$slot" else "")
                        send(context, made)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("링크 만들어 보내기")
                }
            } else {
                // [SHR-13] When the link last got the calendar, and a change still waiting for the internet.
                SentLine(store.sentAt, unsent, busy) { run { container.shareSync.resend(store.key) } }
                if (chosen() != saved || title.trim() != current.title.trim()) {
                    Text("고른 항목이나 이름이 바뀌었어요. ‘지금 근무표로 고치기’를 누르면 링크에 들어가요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                // [SHR-12] Sent the way the automatic update sends it: the end date stays.
                if (!auto || chosen() != saved || title.trim() != current.title.trim()) {
                    OutlinedButton(enabled = !busy && !empty, onClick = {
                        run {
                            val sent = payload(null)
                            keep(client.update(current, sent), sent)
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("지금 근무표로 고치기")
                    }
                }
                OutlinedButton(enabled = !busy && !empty, onClick = {
                    if (extraLocked) { com.bamnat.app.ui.pro.openPro(); return@OutlinedButton }
                    run {
                        val sent = payload(ttl)
                        keep(client.update(current, sent), sent)
                        container.telemetry.event("share_link_extend", "sheet")
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("오늘부터 ${ShareLinkPayload.ttlLabel(ttl)} 열어 두기")
                    if (extraLocked) { Spacer(Modifier.width(8.dp)); com.bamnat.app.ui.money.ProPill() }
                }
                TextButton(enabled = !busy, onClick = {
                    run { client.delete(current); store.clear() }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (slot > 1) "이 링크 끊기" else "링크 끊기", color = MaterialTheme.colorScheme.error) }
            }
            error?.let { why ->
                Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                // [UX-05] An error that does not go away is one tap from a question, with what the sheet said.
                TextButton(onClick = {
                    onDismiss()
                    com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(
                        about = "근무표 공유", kind = com.bamnat.app.company.InquiryKind.BUG, text = "‘$why’라고 나와요. "))
                }) { Text("계속 안 되면 문의하기") }
            }
            if (onOpenReceived != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // [UX-03] The other way round: a coworker's schedule beside the user's own.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "받기", onClick = onOpenReceived)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("동료 근무표도 받아 볼까요?", style = MaterialTheme.typography.titleSmall)
                        Text("받은 링크를 넣으면 내 달력과 바꿔 가며 보고, 같이 쉬는 날도 보여요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("받기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** [SHR-13] 마지막으로 보낸 때, and 보내지 못한 변경 with a button that sends it now. */
@Composable
internal fun SentLine(sentAt: java.time.Instant?, unsent: Boolean, busy: Boolean, onSend: () -> Unit) {
    if (unsent) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("보내지 못한 변경이 있어요. 인터넷이 되면 저절로 보내요.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onSend, modifier = Modifier.heightIn(min = 48.dp)) { Text("지금 보내기") }
            }
        }
    }
    sentAt?.let {
        Text("마지막으로 보낸 때 " + it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [PROV-04] 링크 1…5 for the links made, and 새 링크 while one is free; 새 링크 is 프로's. */
@Composable
private fun LinkChips(links: List<ShareLinkState?>, slot: Int, open: Boolean, onPick: (Int) -> Unit, onNew: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            links.forEachIndexed { i, l ->
                val n = i + 1
                // An empty slot shows only while it is the one being made.
                if (l != null || n == slot) FilterChip(selected = n == slot, onClick = { onPick(n) }, label = { Text("링크 $n") })
            }
            val free = links.withIndex().any { (i, l) -> l == null && i + 1 != slot }
            if (free) FilterChip(selected = false, onClick = onNew, label = { Text("+ 새 링크") },
                trailingIcon = if (open) null else ({ com.bamnat.app.ui.money.ProPill() }))
        }
        Text(
            if (open) "보낼 사람마다 링크를 따로 만들면 보여 줄 것과 기간을 따로 정하고, 하나만 끊을 수도 있어요. 달력마다 ${ShareLinkKey.MAX}개까지예요."
            else "링크를 사람마다 따로 두는 건 프로에서 돼요(달력마다 ${ShareLinkKey.MAX}개까지).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [FEAT-15] 캘린더 구독 주소 (프로): the link's days as an .ics feed. Google Calendar takes one only on a
 * computer (URL로 추가) and reads it again every few hours to a day, so the row says both.
 */
@Composable
private fun FeedRow(on: Boolean, open: Boolean, busy: Boolean, url: String?, onChange: (Boolean) -> Unit, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = on, enabled = !busy, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("캘린더 구독 주소", style = MaterialTheme.typography.bodyLarge)
                    if (!open) { Spacer(Modifier.width(6.dp)); com.bamnat.app.ui.money.ProPill() }
                }
                Text("구글·아이폰·아웃룩 캘린더에 구독으로 넣으면 이 링크의 근무가 따라 바뀌어요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = null, enabled = !busy)
        }
        if (url != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onCopy(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("구독 주소 복사") }
                    Text(FEED_GUIDE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (on) {
            Text("링크를 만들면 구독 주소가 여기 나와요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [FEAT-15] Where the address goes, as Google's help says (2026-10-22), and how soon a change shows. */
internal const val FEED_GUIDE =
    "구글 캘린더: 컴퓨터에서 calendar.google.com › 다른 캘린더 + › URL로 추가에 붙여 넣어요. 휴대폰 구글 캘린더 앱에서는 추가할 수 없어요. " +
        "바뀐 근무는 구글이 다시 읽을 때(몇 시간~하루) 보여요.\n" +
        "아이폰·맥: 설정 › 캘린더 › 계정 › 구독 캘린더 추가. 아웃룩: 캘린더 추가 › 웹에서 구독.\n" +
        "주소를 아는 사람은 누구나 볼 수 있어요. 링크를 끊으면 구독도 끊겨요."

private fun copy(context: Context, text: String) {
    runCatching {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("캘린더 구독 주소", text))
    }
    // Android 13 and later say it themselves.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, "구독 주소를 복사했어요", android.widget.Toast.LENGTH_SHORT).show()
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
