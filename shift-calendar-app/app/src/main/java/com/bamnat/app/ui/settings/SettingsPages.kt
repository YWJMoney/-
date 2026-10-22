package com.bamnat.app.ui.settings

import com.bamnat.app.data.rules
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.core.work.words
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.bamnat.app.BuildConfig
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.PhoneDayView
import com.bamnat.app.data.ThemeMode
import com.bamnat.app.ui.calendar.DayGroupKey
import com.bamnat.app.ui.calendar.DayGroupOrderDialog
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.SoundPickerRow
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.core.model.ShiftType
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/** [SET-01] What each group holds, each setting in words a first-time user knows. */
@Composable
internal fun ColumnScope.SettingsPageContent(
    page: SettingsPage,
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    files: SettingsFiles,
    askNotifications: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenPattern: () -> Unit,
    onOpenDiagnostics: (Boolean) -> Unit,
    onOpenWorkStyle: () -> Unit,
    onOpenExtraKinds: () -> Unit,
    onOpenPay: () -> Unit,
    company: (@Composable ColumnScope.() -> Unit)?,
    onOpenCalendars: () -> Unit = {},
) {
    when (page) {
        SettingsPage.WORK -> WorkPage(vm, settings, onOpenWorkStyle, onOpenPattern, onOpenShiftTypes, onOpenExtraKinds)
        SettingsPage.ALARM -> AlarmPage(vm, settings, onOpenShiftTypes, onOpenDiagnostics)
        // [SET-02] 알림과 잠: the notices, then sleep and the rest check.
        SettingsPage.NOTICES -> {
            NoticesPage(vm, settings, refresh, askNotifications)
            RestPage(vm, settings)
        }
        SettingsPage.PAY -> PayPage(vm, settings, files, onOpenPay, company)
        SettingsPage.CALENDAR -> CalendarPage(vm, settings, onOpenCalendars)
        SettingsPage.DATA -> DataPage(vm, files)
        // [SET-02] 개인정보·앱 정보: privacy and permissions, then the terms, licences and version.
        SettingsPage.PRIVACY -> {
            PrivacyPage(vm, settings, refresh, askNotifications)
            AboutPage()
        }
    }
}

@Composable
private fun WorkPage(
    vm: SettingsViewModel,
    settings: AppSettings,
    onOpenWorkStyle: () -> Unit,
    onOpenPattern: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenExtraKinds: () -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    // [CALS-02] Each calendar's work is its own: the one picked here is the one these rows set up.
    val work by vm.workSettings.collectAsStateWithLifecycle()
    val words = work.workStyle.words()
    // [SETUP-12] A first setup left for later goes on from here too; nothing shows when none is waiting.
    com.bamnat.app.ui.setup.SetupResumeCard()
    CalendarPick()
    SectionCard(title = null) {
        // [WORK-01] Changing the kind of work keeps every record; it only changes what screens show.
        NavRow("일하는 방식", work.workStyle?.let { "${it.label} · ${it.description}" } ?: "교대·일근·시급·현장·자영업 중에서 골라요", onOpenWorkStyle)
        HorizontalDivider()
        NavRow(words.pattern, words.patternHint, onOpenPattern)
        HorizontalDivider()
        NavRow(words.types, shiftsLine(snapshot?.shiftTypes.orEmpty()), onOpenShiftTypes)
        if (work.workStyle?.usesPayEngine != false) {
            HorizontalDivider()
            // [EXTRA-04][EXTRA-07] Each company names, times and pays work on a day off and extra hours its own way.
            NavRow("연장 휴무일 근무 지정", settings.extraKinds.visible().joinToString(" · ") { it.name } + " · 시간·가산 임금", onOpenExtraKinds)
        }
    }
    Note("일하는 방식을 바꿔도 적어 둔 기록은 그대로 남아요.")
    WorkTools(work.workStyle)
}

/** [SHFT-06][SHFT-07][DAYW-06][DAYW-07] Tools of the calendar's own kind of work, for the calendar picked above. */
@Composable
private fun WorkTools(style: com.bamnat.core.work.WorkStyle?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val commute by remember { com.bamnat.app.data.CommutePrefs.get(context).state }.collectAsStateWithLifecycle()
    fun open(t: com.bamnat.app.NoticeLinks.Target) = com.bamnat.app.NoticeLinks.open(t)
    when (style) {
        null, com.bamnat.core.work.WorkStyle.SHIFT -> SectionCard(title = "교대 근무 도구") {
            NavRow("듀티표 넣기", "듀티표 사진이나 엑셀·CSV 파일을 읽어 한 달 근무를 한 번에 넣어요") { open(com.bamnat.app.NoticeLinks.Target.DUTY_ROSTER) }
            HorizontalDivider()
            NavRow("연간 건강 지표", "야간 비율, 연속 야간, 짧은 휴식, 주말·공휴일 근무") { open(com.bamnat.app.NoticeLinks.Target.HEALTH_REPORT) }
        }
        com.bamnat.core.work.WorkStyle.DAY -> SectionCard(title = "일근 도구") {
            NavRow("근태 엑셀 가져오기", "회사 근태 시스템의 엑셀·CSV로 출퇴근·연차·야근을 넣어요") { open(com.bamnat.app.NoticeLinks.Target.ATTENDANCE_IMPORT) }
            HorizontalDivider()
            NavRow("출퇴근 자동 기록", if (commute.enabled) "켜져 있어요 · 회사 위치에 들어오고 나갈 때 적어요" else "꺼져 있어요 · 회사 위치로 출근·퇴근 시각을 적어요") {
                open(com.bamnat.app.NoticeLinks.Target.COMMUTE)
            }
        }
        else -> Unit
    }
}

/**
 * [CALS-02] Which calendar the rows below set up, once there is more than one: the one on the calendar
 * tab, switched here too, so a calendar made later is set up from here like the first one.
 */
@Composable
private fun CalendarPick() {
    val c = androidx.compose.ui.platform.LocalContext.current.appContainer
    val list by c.calendars.calendars.collectAsStateWithLifecycle()
    if (list.size < 2) return
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("설정할 달력", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.bamnat.app.ui.calendar.CalendarScopeChips(c)
    }
}

/** "주간 07:00–19:00 · 야간 19:00–07:00 외 2개". */
private fun shiftsLine(types: List<ShiftType>): String {
    val work = types.filter { !it.isOff && it.start != null && it.end != null }
    if (work.isEmpty()) return "이름, 시간, 색, 근무 전 알람"
    val shown = work.take(2).joinToString(" · ") { "${it.name} ${Fmt.time(it.start)}–${Fmt.time(it.end)}" }
    return if (work.size > 2) "$shown 외 ${work.size - 2}개" else shown
}

@Composable
private fun AlarmPage(vm: SettingsViewModel, settings: AppSettings, onOpenShiftTypes: () -> Unit, onOpenDiagnostics: (Boolean) -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var confirmOff by remember { mutableStateOf(false) }
    if (confirmOff) {
        ConfirmDialog(
            title = "알람을 모두 끌까요?",
            message = "근무 알람도 이 날 알람도 울리지 않아요. 알람 설정은 그대로 남아서 다시 켜면 돌아와요.",
            confirmLabel = "모두 끄기",
            destructive = true,
            onDismiss = { confirmOff = false },
            onConfirm = { confirmOff = false; vm.setAlarmsEnabled(false) },
        )
    }
    SectionCard(title = null) {
        SwitchRow(
            "알람 사용",
            if (settings.alarmsEnabled) "근무 알람과 이 날 알람이 울려요" else "모든 알람이 꺼져 있어요. 알람 설정은 그대로 남아요",
            settings.alarmsEnabled,
        ) { on -> if (on) vm.setAlarmsEnabled(true) else confirmOff = true }
    }
    SectionCard(title = "근무별 알람") {
        val ringing = snapshot?.shiftTypes.orEmpty().filter { it.alarmEnabled && !it.isOff && it.start != null }
        if (ringing.isEmpty()) {
            Note("근무 전 알람이 켜진 근무가 없어요.")
            NavRow("근무 종류에서 알람 켜기", "근무마다 몇 분 전에 울릴지 정해요", onOpenShiftTypes)
        }
        for ((i, t) in ringing.withIndex()) {
            if (i > 0) HorizontalDivider()
            val times = t.alarms.filter { it.enabled }.sortedByDescending { it.offsetMinutes }
                .joinToString(" · ") { Fmt.time(t.start!!.minusMinutes(it.offsetMinutes.toLong())) }
            NavRow(t.name, "${Fmt.time(t.start)} 시작 · " + if (times.isEmpty()) "켜진 알람 없음" else "알람 $times", onOpenShiftTypes)
        }
    }
    SectionCard(title = "소리") {
        SoundPickerRow(
            sound = settings.defaultAlarmSound,
            defaultLabel = "휴대폰 기본 알람음",
            onPicked = { s -> vm.update { it.copy(defaultAlarmSound = s) } },
            modifier = Modifier.focusMark("알람음"),
        )
        // [UX-04] Said as a way there, not only as a sentence.
        NavRow("근무마다 다른 소리", "근무 종류에서 근무마다 알람음을 따로 정해요", onOpenShiftTypes)
        SwitchRow("진동", "소리와 함께 진동해요", settings.vibrate) { v -> vm.update { it.copy(vibrate = v) } }
        SwitchRow("점점 크게", "작은 소리로 시작해 20초 동안 커져요", settings.gradualVolume) { v -> vm.update { it.copy(gradualVolume = v) } }
    }
    // [PROV-06][PROV-07] 확실하게 깨우기 (프로).
    SureAlarmCard(vm, settings, snapshot?.shiftTypes.orEmpty().filter { it.alarmEnabled && !it.isOff && it.start != null })
    SectionCard(title = "울리는 방식") {
        ChoiceRow("다시 울림 간격", "알람에서 ‘다시 울림’을 누르면 이만큼 뒤에 다시 울려요",
            listOf(3, 5, 10, 15, 20).map { it to "${it}분" }, settings.snoozeMinutes) { m -> vm.update { it.copy(snoozeMinutes = m) } }
        ChoiceRow("자동으로 끄기", "끄지 않으면 이만큼 울리고 멈춰요",
            listOf(1, 3, 5, 10, 20, 30).map { it to "${it}분 뒤" }, settings.ringMinutes) { m -> vm.update { it.copy(ringMinutes = m) } }
    }
    SectionCard(title = null) {
        NavRow("알람 점검", "알람이 안 울리거나 늦을 때 원인을 찾아요") { onOpenDiagnostics(false) }
    }
}

@Composable
private fun RestPage(vm: SettingsViewModel, settings: AppSettings) {
    // [SLP-03][SLP-04][SLP-06][SLP-07][SLP-14] Sleep that fits the schedule. Suggestions, not treatment.
    SectionCard(title = "오늘의 흐름") {
        SwitchRow("오늘의 흐름 보기", "달력 맨 위에 지금 할 일 하나와 잘 시간·다음 근무를 보여 줘요. 날짜를 누르면 그날의 수면 제안도 보여요",
            settings.sleepTips) { v -> vm.update { it.copy(sleepTips = v) } }
        ChoiceRow("목표 수면 시간", null, listOf(360, 390, 420, 450, 480, 540).map { it to sleepHours(it) }, settings.sleepTargetMinutes) { m ->
            vm.update { it.copy(sleepTargetMinutes = m) }
        }
        ChoiceRow("퇴근 후 잠들기까지", "집에 와서 씻는 시간까지예요. 퇴근 뒤 잘 시간을 이만큼 늦춰 잡아요",
            listOf(0, 30, 60, 90, 120).map { it to if (it == 0) "바로" else if (it < 60) "${it}분" else sleepHours(it) }, settings.commuteMinutes) { m ->
            vm.update { it.copy(commuteMinutes = m) }
        }
        HealthSleepRow(vm, settings.healthSleep)
        // [SLP-16] Where the taps go, and what they say about the user's own sleep.
        NavRow("수면 기록 보기", "근무별 평균, 내 기록에서 보이는 것, 기록 고치기·추가") {
            com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.SLEEP)
        }
        Note("야간 뒤에는 목표가 짧아도 6시간 이상 이어서 자도록 제안해요. 진단이나 치료가 아닌 일정에 맞춘 참고 정보예요.")
    }
    // [SLP-01][MKT-07][MKT-11]
    SectionCard(title = "휴식 점검") {
        SwitchRow("휴식 점검", "근무 사이 휴식이 짧거나 나이트가 이어지면 달력에 알려 줘요. 병원·회사 기준에 맞출 수 있어요", settings.restCheck) { v ->
            vm.update { it.copy(restCheck = v) }
        }
        if (settings.restCheck) {
            // [REST-01] 1.21's '더 엄격하게' is a preset of the same 근무표 점검 기준 the calendar card opens.
            var rulesOpen by remember { mutableStateOf(false) }
            val r = settings.rules
            NavRow("근무표 점검 기준", "${r.preset?.label ?: "직접 정함"} · 휴식 ${r.minRestHours}시간, 연속 나이트 ${r.maxNightsInRow}일, 한 달 나이트 ${r.maxNightsPerMonth}개") {
                rulesOpen = true
            }
            if (rulesOpen) com.bamnat.app.ui.calendar.RestRulesSheet(r, com.bamnat.core.schedule.RotationSpeed.NONE, onDismiss = { rulesOpen = false }) { n ->
                vm.update { it.copy(restRules = n.copy(on = true).encode(), restCheck = n.on, strictRest = false) }
                rulesOpen = false
            }
        }
    }
    SectionCard(title = "근무시간 경고") {
        LimitRow("한 주 근무 경고", "한 주가 이 시간을 넘으면 빨갛게 표시해요", settings.weeklyLimitHours) { h -> vm.update { it.copy(weeklyLimitHours = h) } }
        LimitRow("최근 3개월 한 주 평균 경고", "최근 3개월의 한 주 평균이 이 시간을 넘으면 표시해요", settings.averageLimitHours) { h ->
            vm.update { it.copy(averageLimitHours = h) }
        }
        TextButton(onClick = {
            vm.update { it.copy(weeklyLimitHours = AppSettings().weeklyLimitHours, averageLimitHours = AppSettings().averageLimitHours) }
        }) { Text("기본값으로 (주 64시간 · 평균 52시간)") }
    }
}

/**
 * [SLP-15] Every notice in one place: the day's notices, money and 연차, and what waits while the
 * user sleeps. A switch says when the phone's settings keep its notices away, and turning one on
 * while notifications are off asks for them.
 */
@Composable
private fun NoticesPage(
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    askNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val canPost = remember(refresh) { Notifications.canPost(context) }
    val off = remember(refresh) {
        listOf(Notifications.ALARM_CHANNEL_ID, Notifications.DAY_CHANNEL_ID, Notifications.COMMUTE_CHANNEL_ID,
            Notifications.MONEY_CHANNEL_ID, Notifications.LEAVE_CHANNEL_ID, Notifications.TODAY_CHANNEL_ID)
            .filter { canPost && !Notifications.channelOn(context, it) }.toSet()
    }
    fun warning(on: Boolean, channel: String) = if (on && channel in off) "휴대폰 설정에서 꺼져 있어 오지 않아요" else null
    fun fix(channel: String): () -> Unit = { SystemSettings.channel(context, channel) }
    fun set(on: Boolean, change: (AppSettings) -> AppSettings) {
        vm.update(change)
        if (on && !canPost) askNotifications()
    }
    if (!canPost) {
        WarningBanner(
            title = "알림이 꺼져 있어 아무 알림도 오지 않아요",
            message = "근무 알람은 소리가 나도 잠금 화면에서 끌 수 없어요.",
            action = "허용",
            onAction = askNotifications,
        )
    }
    SectionCard(title = "근무 알람") {
        PermissionRow(ok = canPost && Notifications.ALARM_CHANNEL_ID !in off, title = "울릴 때 잠금 화면에 크게 떠요", fixLabel = "켜기") {
            if (!canPost) askNotifications() else SystemSettings.channel(context, Notifications.ALARM_CHANNEL_ID)
        }
        // [SET-02] The alarm's own switches are in 설정 › 알람 only.
        Note("근무 알람을 켜고 끄는 것과 소리는 설정 › 알람에서 정해요.")
    }
    val planned = settings.workStyle?.usesPayEngine != false
    SectionCard(title = "하루 안내") {
        if (!planned) Note("교대·일근·시급 근무에서 근무 시간에 맞춰 와요.")
        SwitchRow("하루 브리핑", "근무 전에 커피·낮잠·잘 시간을 소리 없이 한 번 알려 줘요", settings.briefing, enabled = planned,
            warning = warning(settings.briefing, Notifications.DAY_CHANNEL_ID), onFix = fix(Notifications.DAY_CHANNEL_ID)) { on ->
            set(on) { it.copy(briefing = on) }
        }
        SwitchRow("퇴근길 안전", "야간이 끝날 때 한 번 소리로 알려 줘요. 10분 넘게 늦어지면 운전 중일 수 있어 보내지 않아요",
            settings.commuteNotice, enabled = planned,
            warning = warning(settings.commuteNotice, Notifications.COMMUTE_CHANNEL_ID), onFix = fix(Notifications.COMMUTE_CHANNEL_ID)) { on ->
            set(on) { it.copy(commuteNotice = on) }
        }
        SwitchRow("잘 준비", "근무 전날 밤, 잘 시간 30분 전에 소리 없이 알려 줘요", settings.bedtimeNotice, enabled = planned,
            warning = warning(settings.bedtimeNotice, Notifications.DAY_CHANNEL_ID), onFix = fix(Notifications.DAY_CHANNEL_ID)) { on ->
            set(on) { it.copy(bedtimeNotice = on) }
        }
        SwitchRow("자는 중 표시", "‘잠들었어요’를 누르면 알림창에 잔 시간과 다음 알람이 보이고, ‘깼어요’로 끝내요", settings.sleepingNotice,
            warning = warning(settings.sleepingNotice, Notifications.DAY_CHANNEL_ID), onFix = fix(Notifications.DAY_CHANNEL_ID)) { on ->
            set(on) { it.copy(sleepingNotice = on) }
        }
        // [FEAT-18] Phones have no lock-screen widgets; this quiet notice stands in for one.
        SwitchRow("잠금 화면 오늘 근무", "오늘과 내일 근무를 잠금 화면과 알림창에 늘 보여 줘요. 소리는 나지 않아요", settings.todayShiftNotice,
            warning = warning(settings.todayShiftNotice, Notifications.TODAY_CHANNEL_ID), onFix = fix(Notifications.TODAY_CHANNEL_ID)) { on ->
            set(on) { it.copy(todayShiftNotice = on) }
        }
    }
    SectionCard(title = "돈과 휴가") {
        SwitchRow("나갈 돈·받을 돈", "고정비는 3일 전과 당일에, 못 받은 돈은 다음 날과 7일 뒤에 알려 줘요. 같은 때 온 것은 하나로 묶어요",
            settings.moneyNotices, warning = warning(settings.moneyNotices, Notifications.MONEY_CHANNEL_ID), onFix = fix(Notifications.MONEY_CHANNEL_ID)) { on ->
            set(on) { it.copy(moneyNotices = on) }
        }
        SwitchRow("연차 사용 촉진 알림", "연차가 없어지기 6개월 전·2개월 전에 남는 날을 알려 줘요(연차 화면에서도 끌 수 있어요)", settings.leaveNotice,
            warning = warning(settings.leaveNotice, Notifications.LEAVE_CHANNEL_ID), onFix = fix(Notifications.LEAVE_CHANNEL_ID)) { on ->
            set(on) { it.copy(leaveNotice = on) }
        }
        Note("일감 시작 전 알림은 일감마다 정해요.")
    }
    // [COMM-10] The plaza's news once a day, only for someone who uses a company's rules or sent something.
    SectionCard(title = "회사 광장") {
        SwitchRow("광장 소식", "쓰는 회사 규칙이 바뀌거나, 보낸 규칙·제안·임금표가 채택되면 하루 한 번 묶어서 소리 없이 알려 줘요", settings.plazaNews,
            warning = warning(settings.plazaNews, Notifications.PLAZA_CHANNEL_ID), onFix = fix(Notifications.PLAZA_CHANNEL_ID)) { on ->
            set(on) { it.copy(plazaNews = on) }
        }
    }
    SectionCard(title = "자는 동안") {
        SwitchRow("자는 동안 미루기", "자는 동안 생긴 돈·연차 알림은 깨고 30분 뒤에 보내요. 근무 알람과 일감 알림은 그대로 와요",
            settings.quietWhileAsleep) { on -> vm.update { it.copy(quietWhileAsleep = on) } }
    }
}

@Composable
private fun PayPage(vm: SettingsViewModel, settings: AppSettings, files: SettingsFiles, onOpenPay: () -> Unit, company: (@Composable ColumnScope.() -> Unit)?) {
    val profile by vm.hasPayProfile.collectAsStateWithLifecycle()
    // [CALS-02] Each calendar has its own pay setup.
    CalendarPick()
    SectionCard(title = null) {
        NavRow("급여 계산", if (profile != null) "기본급·수당·회사 규칙·연차·급여일" else "기본급만 넣으면 이번 달 예상 급여가 보여요", onOpenPay)
        // [DAILY-16] 결정 D36: compared on the phone only; on by default, off here.
        SwitchRow("광장 시세와 내 단가 견주기", if (settings.rateHint) "광장에서 본 내 직군·내 지역 시세와 내 일당·시급·월급이 10% 넘게 다르면 급여 화면에 카드로 보여요. 내 급여는 어디로도 보내지 않아요"
            else "급여 화면에 시세 비교 카드를 보이지 않아요", settings.rateHint) { on -> vm.update { it.copy(rateHint = on) } }
        if (settings.rateHint) RateRegionRow()
    }
    // [COMM-01][COMM-02]
    if (company != null) SectionCard(title = "같은 회사 사람들과", content = company)
    // [COMM-00] Coworkers share one setup instead of each typing it in.
    SectionCard(title = "회사 설정 파일") {
        Note("근무 종류·주기·급여 규칙을 파일로 동료에게 보내요. 기본급, 입사일, 연차, 달력에 적은 내용은 들어가지 않아요.")
        NavRow("회사 설정 보내기", "카카오톡·메일 등으로 파일을 보내요", files.shareCompany)
        HorizontalDivider()
        NavRow("회사 설정 불러오기", "동료에게 받은 파일을 열어 넣어요", files.openFile)
    }
}

private enum class CellLine { NONE, HOURS, LUNAR }
private enum class MemoView { PLAIN, BELOW, CELLS }

@Composable
private fun CalendarPage(vm: SettingsViewModel, settings: AppSettings, onOpenCalendars: () -> Unit) {
    // [MCAL-01][MCAL-04] The user's calendars: adding one, the list's order, names and taking one out.
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    SectionCard(title = "내 달력") {
        NavRow("달력 목록", if (calendars.size > 1) "${calendars.size}개 · " + calendars.joinToString(", ") { it.name }
            else "새 달력을 만들어 근무를 따로 적고 따로 공유해요", onOpenCalendars)
        HorizontalDivider()
        // [MEMO-02] Memos stay with their own calendar unless calendars are grouped here.
        val memoSharing by vm.memoSharing.collectAsStateWithLifecycle()
        var sharingMemos by remember { mutableStateOf(false) }
        NavRow("메모 공유", com.bamnat.app.ui.memo.memoSharingText(memoSharing, calendars)) { if (calendars.size > 1) sharingMemos = true }
        if (sharingMemos) {
            com.bamnat.app.ui.memo.MemoSharingDialog(memoSharing, calendars, onDismiss = { sharingMemos = false },
                onSave = { vm.setMemoSharing(it); sharingMemos = false })
        }
        // [SET-02] 근무표 공유 and 동료 근무표 받기 live in the calendar's 도구 only; settings search still finds them.
    }
    SectionCard(title = "모양") {
        // [SHFT-05] 야간 근무 중 어둡게: dark during a night shift on the calendar being viewed.
        ChoiceRow("테마", if (settings.themeMode == ThemeMode.NIGHT_SHIFT) "22~06시에 걸친 근무 중에는 어둡게, 그 밖에는 휴대폰 따라" else null,
            listOf(ThemeMode.SYSTEM to "휴대폰 따라", ThemeMode.LIGHT to "밝게", ThemeMode.DARK to "어둡게", ThemeMode.NIGHT_SHIFT to "야간 근무 중 어둡게"),
            settings.themeMode) { m ->
            vm.update { it.copy(themeMode = m) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow("휴대폰 배경색에 맞추기", "배경화면 색으로 앱의 색을 바꿔요", settings.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
        }
    }
    SectionCard(title = "달력") {
        // [CAL-02] Sunday first, as most Korean calendars are.
        ChoiceRow("한 주의 시작", "달력 첫 칸의 요일이에요. 주 근무시간은 늘 월~일로 세요", listOf(DayOfWeek.SUNDAY to "일요일", DayOfWeek.MONDAY to "월요일"), settings.weekStart) { d ->
            vm.update { it.copy(weekStart = d) }
        }
        // One choice: before, two switches quietly took each other's place.
        val line = when { settings.showLunar -> CellLine.LUNAR; settings.showHoursInCells -> CellLine.HOURS; else -> CellLine.NONE }
        ChoiceRow("날짜 칸 아래", "날짜 밑에 작게 보여 줄 것을 골라요",
            listOf(CellLine.NONE to "비움", CellLine.HOURS to "근무시간 (8h)", CellLine.LUNAR to "음력 (8.24)"), line) { l ->
            vm.update { it.copy(showHoursInCells = l == CellLine.HOURS, showLunar = l == CellLine.LUNAR) }
        }
        // [FEAT-17][DAY-04] For people who keep their days in notes. Where 메모 sits in the day is the order below.
        val memo = when { settings.memoInCells -> MemoView.CELLS; settings.calendarMemos -> MemoView.BELOW; else -> MemoView.PLAIN }
        ChoiceRow("메모 보기", when (memo) {
            MemoView.PLAIN -> "메모는 날짜를 누르면 보여요"
            MemoView.BELOW -> "이번 달 메모를 달력 아래에 날짜순으로 짧게 보여 줘요"
            MemoView.CELLS -> "날짜 밑에 그날 메모 첫 글자가 보이고, 이번 달 메모도 달력 아래에 모여요"
        }, listOf(MemoView.PLAIN to "기본", MemoView.BELOW to "달력 아래 목록", MemoView.CELLS to "날짜 밑 메모"), memo) { m ->
            vm.update { it.copy(calendarMemos = m != MemoView.PLAIN, memoInCells = m == MemoView.CELLS) }
        }
        // [DAILY-01] On by default; a home screen is seen by others, so it turns off here.
        val widgetContext = LocalContext.current
        SwitchRow("위젯에 메모 보이기", if (settings.widgetMemo) "오늘·내일 위젯에 그날 메모 첫 줄이 보여요. 홈 화면을 남이 볼 수 있으면 끄세요"
            else "위젯에는 근무만 보여요", settings.widgetMemo) { on ->
            vm.update { it.copy(widgetMemo = on) }
            widgetContext.appContainer.appScope.launch { com.bamnat.app.widget.WidgetRefresher.refresh(widgetContext) }
        }
        // [CAL-03] Unfolded, the day is always beside the calendar; this is for the cover screen and phones.
        ChoiceRow("날짜를 누르면", "접은 화면과 일반 휴대폰에서 " + when (settings.phoneDayView) {
            PhoneDayView.WEEK -> "그 주(월~일)만 남고, 아래에 하루 화면이 펼쳐져요"
            else -> if (settings.showDayCard) "달력 아래 카드에 먼저 보이고, 한 번 더 누르면 크게 열려요" else "하루 화면이 바로 크게 열려요"
        }, listOf(PhoneDayView.SHEET to "하루 화면 열기", PhoneDayView.WEEK to "그 주만 보기"), settings.phoneDayView) { v ->
            vm.update { it.copy(phoneDayView = v) }
        }
        // [CAL-20] Off by default; the day sheet holds the same.
        SwitchRow("달력 아래 하루 카드", "누른 날의 근무 시간·특근·알람 끄기를 달력 아래 작은 카드로 보여 줘요", settings.showDayCard) { on ->
            vm.update { it.copy(showDayCard = on) }
        }
        // [DAY-04] The day's groups in the user's order, beside the unfolded calendar and in the sheet alike.
        var ordering by remember { mutableStateOf(false) }
        val moneyFirst = settings.workStyle?.usesPayEngine == false
        NavRow("하루 화면 순서", DayGroupKey.order(settings.dayGroupOrder, moneyFirst).joinToString(" · ") { DayGroupKey.label(it) }) { ordering = true }
        if (ordering) {
            DayGroupOrderDialog(
                custom = settings.dayGroupOrder,
                moneyFirst = moneyFirst,
                onChange = { o -> vm.update { it.copy(dayGroupOrder = o) } },
                onDismiss = { ordering = false },
            )
        }
    }
}

/** [BACK-01] 사진도 백업, with what the photos add to the file now. */
@Composable
private fun BackupPhotosRow(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val size by androidx.compose.runtime.produceState<com.bamnat.app.data.BackupPhotos.Size?>(null, settings.backupPhotos) {
        value = runCatching { context.appContainer.backupManager.photoSize() }.getOrNull()
    }
    val about = size?.takeIf { !it.isEmpty }?.let { "지금 ${it.count}장 · ${photoMegabytes(it.bytes)}. " }.orEmpty()
    SwitchRow(
        "사진도 백업",
        about + if (settings.backupPhotos) "영수증·계약서 사진이 백업 파일에 들어가 새 휴대폰에서도 보여요. 끄면 사진은 이 휴대폰에만 남아요"
        else "꺼져 있어요. 사진은 이 휴대폰에만 있어서 휴대폰을 바꾸면 옮겨지지 않아요",
        settings.backupPhotos,
    ) { on -> vm.update { it.copy(backupPhotos = on) } }
}

@Composable
private fun DataPage(vm: SettingsViewModel, files: SettingsFiles) {
    val savedAt by vm.importSavedAt.collectAsStateWithLifecycle()
    var confirmUndo by remember { mutableStateOf(false) }
    val savedLabel = savedAt?.let { AlarmScheduler.millisToLocal(it) }?.let { "${Fmt.monthDay(it.toLocalDate())} ${Fmt.time(it.toLocalTime())}" }
    if (confirmUndo && savedLabel != null) {
        ConfirmDialog(
            title = "불러오기 전으로 되돌릴까요?",
            message = "$savedLabel, 파일을 불러오기 바로 전의 데이터로 돌아가요. 그 뒤에 바꾼 내용은 없어져요.",
            confirmLabel = "되돌리기",
            destructive = true,
            onDismiss = { confirmUndo = false },
            onConfirm = { confirmUndo = false; vm.undoImport() },
        )
    }
    SectionCard(title = "백업") {
        Note("근무, 바꾼 날짜, 메모, 돈 기록, 수면 기록, 급여 설정, 앱 설정, 내가 만든 근무표 링크를 파일 하나로 저장해요. 휴대폰을 바꾸거나 앱을 다시 설치할 때 불러오세요. 암호를 걸면 남이 열어 볼 수 없어요.")
        NavRow("백업 저장", "파일로 저장해요. 암호를 걸 수 있어요", files.saveBackup)
        HorizontalDivider()
        // [BACK-01] 결정 D13: photos go in unless the user keeps them on this phone only.
        BackupPhotosRow(vm)
        HorizontalDivider()
        // [PROV-03] The same file every day by itself (프로).
        AutoBackupRows(vm)
        HorizontalDivider()
        NavRow("파일 불러오기", "백업 파일이나 동료에게 받은 회사 설정 파일을 열어요. 불러오기 전 데이터는 ${com.bamnat.app.data.ImportUndo.KEEP_DAYS}일 동안 남겨 둬요",
            files.openFile)
        if (savedLabel != null) {
            HorizontalDivider()
            NavRow("불러오기 전으로 되돌리기", "$savedLabel 불러오기 전 데이터로 돌아가요") { confirmUndo = true }
        }
        // [UNDO-01][UNDO-02][UNDO-03] A changed kind of work, cleared records or a removed calendar, for 7 days.
        val cleared by vm.recentlyCleared.collectAsStateWithLifecycle()
        var showCleared by remember { mutableStateOf(false) }
        HorizontalDivider()
        NavRow("최근 지운 기록", if (cleared.isEmpty()) "근무 형태를 바꾸거나 기록·달력을 지우면 7일 동안 여기서 되돌릴 수 있어요"
            else "${cleared.size}개 · 7일 안에 되돌릴 수 있어요") { showCleared = true }
        if (showCleared) RecordsTrashDialog(cleared, onRestore = vm::restoreCleared, onDismiss = { showCleared = false })
    }
    SectionCard(title = "다른 캘린더 앱과") {
        // [IMPORT-01]
        NavRow("캘린더에서 가져오기", "삼성·구글 캘린더 일정을 읽어 근무와 메모로 옮겨요") {
            com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.CALENDAR_IMPORT)
        }
        HorizontalDivider()
        // [SET-02] 듀티표 넣기 and 근태 엑셀 가져오기 are in 내 근무 only (its tools for the kind of work).
        // [REL-04]
        NavRow("캘린더 앱으로 내보내기", "구글·삼성 캘린더에 근무를 넣는 파일을 만들어요 (3개월 전부터 1년 뒤까지)", files.exportIcs)
        HorizontalDivider()
        // [FEAT-07] The same, kept up to date by itself (프로).
        PhoneCalendarRows(vm)
    }
}

/**
 * [STAT-01] 사용 통계 보내기 with what exactly goes: event names and daily counts, the app version, never
 * the phone, the schedule, amounts or text. "보낼 내용 보기" shows the waiting bundle as it will be sent.
 */
@Composable
internal fun UsageStatsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalContext.current.appContainer
    var show by remember { mutableStateOf(false) }
    SwitchRow(
        "사용 통계 보내기",
        "어떤 기능을 하루에 몇 번 썼는지만 보내 앱을 고치는 데 써요. 휴대폰·근무·금액·글은 보내지 않고, 꺼도 앱은 똑같이 돼요",
        on,
        onChange = onChange,
    )
    if (on) TextButton(onClick = { show = true }) { Text("보낼 내용 보기") }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("보낼 내용") },
            text = { Text(c.usageStats.preview() ?: "아직 보낼 내용이 없어요. 하루가 지나면 그날 쓴 기능 이름과 횟수가 여기 보여요.") },
            confirmButton = { TextButton(onClick = { show = false }) { Text("확인") } },
        )
    }
}

@Composable
private fun PrivacyPage(
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    askNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val pkg = Uri.parse("package:${context.packageName}")
    SectionCard(title = "개인정보") {
        NavRow("개인정보 처리방침", "무엇을 휴대폰에 두고 무엇을 보내는지") { openPrivacy(context) }
        HorizontalDivider()
        // [STAT-01] Back with a real place to send to (UsageStats, 처리방침 2.8). Its own yes, so it starts off;
        // the app works the same either way.
        UsageStatsRow(settings.shareUsageStats) { on -> context.appContainer.setUsageStats(on) }
        // [MON-07] Google's consent form again where the law gives one (EEA, UK and the like); elsewhere, what there is.
        val adPrivacy by vm.adPrivacy.collectAsStateWithLifecycle()
        var adWords by remember { mutableStateOf(false) }
        NavRow("광고 개인정보 선택", if (adPrivacy) "광고에 쓰는 정보 동의를 다시 골라요" else "광고가 무엇을 쓰는지와 끄는 방법") {
            val activity = with(com.bamnat.app.review.InAppReview) { context.findActivity() }
            if (adPrivacy && activity != null) vm.showAdPrivacy(activity) else adWords = true
        }
        if (adWords) {
            AlertDialog(
                onDismissRequest = { adWords = false },
                title = { Text("광고 개인정보") },
                text = { Text(AD_PRIVACY_WORDS) },
                confirmButton = { TextButton(onClick = { adWords = false }) { Text("확인") } },
            )
        }
        // [LOCK-01] Pay and memos behind the phone's own lock.
        val lockable = remember(refresh) { com.bamnat.app.AppLock.available(context) }
        SwitchRow(
            "앱 잠금",
            if (lockable) "열 때와 1분 넘게 다른 앱을 쓰다 돌아올 때 지문·얼굴·PIN을 물어요. 알람은 그대로 울려요"
            else "휴대폰에 잠금(PIN·패턴·지문)을 먼저 설정하면 켤 수 있어요",
            settings.appLock && lockable,
            enabled = lockable,
        ) { on ->
            vm.update { it.copy(appLock = on) }
            if (!on) com.bamnat.app.AppLock.release()
        }
    }
    SectionCard(title = "권한") {
        val notificationsOk = remember(refresh) { Notifications.canPost(context) }
        val fullScreenOk = remember(refresh) { Notifications.canUseFullScreen(context) }
        val exactOk = remember(refresh) { vm.canScheduleExact() }
        PermissionRow(ok = notificationsOk, title = "알림", fixLabel = "허용", onFix = askNotifications)
        PermissionRow(ok = fullScreenOk, title = "잠금 화면에 알람 띄우기", fixLabel = "허용") {
            if (Build.VERSION.SDK_INT >= 34) SystemSettings.open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        }
        // On Android 13 and later this one is always given to an alarm app: shown only when it is missing.
        if (!exactOk) {
            PermissionRow(ok = false, title = "정확한 시간에 울리기", fixLabel = "허용") {
                if (Build.VERSION.SDK_INT >= 31) SystemSettings.open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
        }
        // Reading sleep is a choice, not something missing: it says where it is turned on.
        HorizontalDivider()
        NavRow("워치·휴대폰 수면 기록", if (settings.healthSleep) "헬스 커넥트에서 읽고 있어요" else "읽지 않아요 · 알림과 잠에서 켜요") {
            SettingsLinks.open(SettingsPage.NOTICES, "워치·휴대폰 수면 기록 함께 보기")
        }
        // [UX-04] Where 결제 알림 읽기 is turned on, as a way there.
        // [LEDG-16] Straight to the money tab's 자동 기록 sheet.
        NavRow("결제 알림 읽기", "카드·은행 알림으로 돈 기록을 채우는 것은 돈 탭 › ⋮ › 자동 기록에서 켜고 꺼요") {
            com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.AUTO_RECORD)
        }
        Note("허용을 바꾸면 휴대폰 설정 화면이 열려요.")
    }
}

/**
 * [SET-02] What was 앱 정보, under privacy: the terms and licences and the version. 알람 점검 is in 설정 › 알람,
 * 도움말과 문의 on the settings list and 처리방침 just above, each in one place.
 */
@Composable
private fun AboutPage() {
    val context = LocalContext.current
    SectionCard(title = "앱 정보") {
        // [MON-05] What paying for 프로 agrees to.
        NavRow("이용약관", "프로 구독·체험·해지·환불") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.bamnat.app.ui.pro.TERMS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        HorizontalDivider()
        // [OPS-23] The libraries the app ships with.
        var licenses by remember { mutableStateOf(false) }
        NavRow("오픈소스 라이선스", null) { licenses = true }
        if (licenses) OpenSourceLicensesDialog { licenses = false }
    }
    Text("밤낮 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.focusMark("버전"))
}

/** [MON-07] Said where Google gives no consent form of its own to open again. */
internal const val AD_PRIVACY_WORDS =
    "정식 출시 뒤 무료로 쓸 때만 근무·급여 탭과 돈 탭 아래에 광고 한 줄이 나와요. 프로를 쓰면 광고가 아예 없어요.\n\n" +
        "광고는 Google AdMob이 보여 주고, 휴대폰의 광고 ID와 기기 정보를 써요. 근무표·급여·메모·돈 기록은 광고에 쓰지 않아요.\n\n" +
        "맞춤 광고를 끄려면 휴대폰 설정 › 개인정보 보호(또는 Google) › 광고에서 광고 ID를 지우면 돼요. " +
        "유럽처럼 법이 따로 묻는 지역에서는 여기서 Google의 동의 화면이 다시 열려요."

// [ADMIN-02] The 밤낮 policy; privacy.html at the site root is another app's.
private const val PRIVACY_URL = "https://ywjmoney.github.io/-/privacy/"

private fun openPrivacy(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * A mail to the developer with what helps answer it; nothing from the calendar goes in. [text] is what
 * the user already wrote (a question that could not be sent, say), so it is not typed twice. [UX-05]
 */
internal fun writeToUs(context: android.content.Context, subject: String = "[밤낮 문의]", text: String = "") {
    val body = text + "\n\n---\n앱: 밤낮 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n기기: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$REPORT_EMAIL")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(mail) }.onFailure {
        // No mail app: any app that sends text, with the address in the text.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, "받는 곳: $REPORT_EMAIL\n\n$body")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "문의 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            android.widget.Toast.makeText(context, "메일 앱이 없어요. $REPORT_EMAIL 로 보내 주세요", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * [SLP-08] Sleep a watch or the phone measured, through Health Connect. The switch stays off until
 * "수면 읽기" is allowed; on phones without Health Connect it says so instead of asking.
 */
@Composable
private fun HealthSleepRow(vm: SettingsViewModel, on: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val ok = granted.containsAll(com.bamnat.app.sleep.HealthSleep.PERMISSIONS)
        vm.setHealthSleep(ok)
        message = if (ok) null else "수면 읽기를 허용하지 않아 꺼 두었어요. 다시 켜면 또 물어봐요."
    }
    SwitchRow(
        "워치·휴대폰 수면 기록 함께 보기",
        "헬스 커넥트에 모인 수면(갤럭시 워치, 삼성 헬스 등)을 읽어 수면 부족 계산에 써요. 읽기만 하고 밖으로 보내지 않아요",
        on,
    ) { v ->
        message = null
        if (!v) vm.setHealthSleep(false)
        else when (vm.healthSdkStatus()) {
            androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE -> scope.launch {
                if (vm.healthGranted()) vm.setHealthSleep(true)
                else runCatching { ask.launch(com.bamnat.app.sleep.HealthSleep.PERMISSIONS) }
                    .onFailure { message = "헬스 커넥트 허용 화면을 열지 못했어요." }
            }
            androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                message = "헬스 커넥트를 설치하거나 업데이트한 뒤 다시 켜 주세요."
                runCatching { context.startActivity(com.bamnat.app.sleep.HealthSleep.installIntent()) }
            }
            else -> message = "이 휴대폰에서는 헬스 커넥트를 쓸 수 없어요. 잠들었어요·깼어요 버튼으로 기록해 주세요."
        }
    }
    message?.let { Note(it) }
}

/**
 * [DAILY-16] The 시·도 the 광장 시세 card compares in. Without it (and without a rate the user shared), no card
 * shows: a 시세 of another region says nothing about the user's pay.
 */
@Composable
private fun RateRegionRow() {
    val store = LocalContext.current.appContainer.rateHints
    val own by store.own.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val region = own.picked.ifBlank { own.region }
    NavRow("견줄 내 지역", if (region.isBlank()) "고르지 않았어요. 고르면 같은 지역 시세와만 견줘요"
        else com.bamnat.app.company.Plaza.region(region) + (if (own.picked.isBlank()) " (내가 나눈 시세의 지역)" else "")) { picking = true }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("견줄 내 지역") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(com.bamnat.app.company.Plaza.REGIONS.entries.toList(), key = { it.key }) { (code, name) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clickable { store.pick(code); picking = false }.padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = region == code, onClick = null)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("닫기") } },
            dismissButton = if (own.picked.isNotBlank()) ({ TextButton(onClick = { store.pick(""); picking = false }) { Text("고르지 않기") } }) else null,
        )
    }
}
