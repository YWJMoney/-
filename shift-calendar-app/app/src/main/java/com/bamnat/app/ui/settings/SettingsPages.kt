package com.bamnat.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ColumnScope
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
    onOpenPage: (SettingsPage) -> Unit,
    onOpenInquiry: () -> Unit = {},
) {
    when (page) {
        SettingsPage.WORK -> WorkPage(vm, settings, onOpenWorkStyle, onOpenPattern, onOpenShiftTypes, onOpenExtraKinds)
        SettingsPage.ALARM -> AlarmPage(vm, settings, onOpenShiftTypes, onOpenDiagnostics)
        SettingsPage.REST -> RestPage(vm, settings)
        SettingsPage.NOTICES -> NoticesPage(vm, settings, refresh, askNotifications, onOpenPage)
        SettingsPage.PAY -> PayPage(vm, files, onOpenPay, company)
        SettingsPage.CALENDAR -> CalendarPage(vm, settings)
        SettingsPage.DATA -> DataPage(vm, files)
        SettingsPage.PRIVACY -> PrivacyPage(vm, settings, refresh, askNotifications, onOpenPage)
        SettingsPage.ABOUT -> AboutPage(vm, onOpenDiagnostics, onOpenInquiry)
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
    SectionCard(title = null) {
        // [WORK-01] Changing the kind of work keeps every record; it only changes what screens show.
        NavRow("일하는 방식", settings.workStyle?.let { "${it.label} · ${it.description}" } ?: "교대·일근·시급·현장·자영업 중에서 골라요", onOpenWorkStyle)
        HorizontalDivider()
        NavRow("근무 주기", when (settings.workStyle) {
            WorkStyle.SHIFT, null -> "주주휴휴야야휴휴처럼 돌아가는 순서와 시작일"
            WorkStyle.DAY -> "일하는 요일과 시작일"
            else -> "되풀이되는 근무가 있으면 정해요"
        }, onOpenPattern)
        HorizontalDivider()
        NavRow("근무 종류와 시간", shiftsLine(snapshot?.shiftTypes.orEmpty()), onOpenShiftTypes)
        HorizontalDivider()
        // [EXTRA-04][EXTRA-07] Each company names, times and pays work on a day off and extra hours its own way.
        NavRow("연장 휴무일 근무 지정", settings.extraKinds.visible().joinToString(" · ") { it.name } + " · 시간·가산 임금", onOpenExtraKinds)
    }
    Note("일하는 방식을 바꿔도 적어 둔 기록은 그대로 남아요.")
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
        )
        Note("근무 종류마다 다른 소리를 정할 수도 있어요.")
        SwitchRow("진동", "소리와 함께 진동해요", settings.vibrate) { v -> vm.update { it.copy(vibrate = v) } }
        SwitchRow("점점 크게", "작은 소리로 시작해 20초 동안 커져요", settings.gradualVolume) { v -> vm.update { it.copy(gradualVolume = v) } }
    }
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
        Note("야간 뒤에는 목표가 짧아도 6시간 이상 이어서 자도록 제안해요. 진단이나 치료가 아닌 일정에 맞춘 참고 정보예요.")
    }
    // [SLP-01][MKT-07][MKT-11]
    SectionCard(title = "휴식 점검") {
        SwitchRow("휴식 점검", "근무 사이 휴식이 11시간보다 짧거나 야간이 4일 이어지면 달력에 표시해요", settings.restCheck) { v ->
            vm.update { it.copy(restCheck = v) }
        }
        if (settings.restCheck) {
            SwitchRow("더 엄격하게", "야간이 3일 이어지거나, 야간 뒤 쉬는 날이 2일보다 적어도 표시해요", settings.strictRest) { v ->
                vm.update { it.copy(strictRest = v) }
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
    onOpenPage: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val canPost = remember(refresh) { Notifications.canPost(context) }
    val off = remember(refresh) {
        listOf(Notifications.ALARM_CHANNEL_ID, Notifications.DAY_CHANNEL_ID, Notifications.COMMUTE_CHANNEL_ID,
            Notifications.MONEY_CHANNEL_ID, Notifications.LEAVE_CHANNEL_ID)
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
        NavRow("알람 설정", "근무 알람 켜고 끄기, 소리, 다시 울림") { onOpenPage(SettingsPage.ALARM) }
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
    }
    SectionCard(title = "돈과 휴가") {
        SwitchRow("나갈 돈·받을 돈", "고정비는 3일 전과 당일에, 못 받은 돈은 다음 날과 7일 뒤에 알려 줘요. 같은 때 온 것은 하나로 묶어요",
            settings.moneyNotices, warning = warning(settings.moneyNotices, Notifications.MONEY_CHANNEL_ID), onFix = fix(Notifications.MONEY_CHANNEL_ID)) { on ->
            set(on) { it.copy(moneyNotices = on) }
        }
        SwitchRow("연차 소멸 안내", "올해 안 쓰면 없어지는 연차를 11월·12월 첫날에 알려 줘요", settings.leaveNotice,
            warning = warning(settings.leaveNotice, Notifications.LEAVE_CHANNEL_ID), onFix = fix(Notifications.LEAVE_CHANNEL_ID)) { on ->
            set(on) { it.copy(leaveNotice = on) }
        }
        Note("일감 시작 전 알림은 일감마다 정해요.")
    }
    SectionCard(title = "자는 동안") {
        SwitchRow("자는 동안 미루기", "자는 동안 생긴 돈·연차 알림은 깨고 30분 뒤에 보내요. 근무 알람과 일감 알림은 그대로 와요",
            settings.quietWhileAsleep) { on -> vm.update { it.copy(quietWhileAsleep = on) } }
    }
}

@Composable
private fun PayPage(vm: SettingsViewModel, files: SettingsFiles, onOpenPay: () -> Unit, company: (@Composable ColumnScope.() -> Unit)?) {
    val profile by vm.hasPayProfile.collectAsStateWithLifecycle()
    SectionCard(title = null) {
        NavRow("급여 계산", if (profile != null) "기본급·수당·회사 규칙·연차·급여일" else "기본급만 넣으면 이번 달 예상 급여가 보여요", onOpenPay)
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
private fun CalendarPage(vm: SettingsViewModel, settings: AppSettings) {
    SectionCard(title = "모양") {
        ChoiceRow("테마", null, listOf(ThemeMode.SYSTEM to "휴대폰 따라", ThemeMode.LIGHT to "밝게", ThemeMode.DARK to "어둡게"), settings.themeMode) { m ->
            vm.update { it.copy(themeMode = m) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow("휴대폰 배경색에 맞추기", "배경화면 색으로 앱의 색을 바꿔요", settings.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
        }
    }
    SectionCard(title = "달력") {
        // [CAL-02] Sunday first, as most Korean calendars are.
        ChoiceRow("한 주의 시작", "주 근무시간도 이 요일부터 세요", listOf(DayOfWeek.SUNDAY to "일요일", DayOfWeek.MONDAY to "월요일"), settings.weekStart) { d ->
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
        // [CAL-03] Unfolded, the day is always beside the calendar; this is for the cover screen and phones.
        ChoiceRow("날짜를 누르면", "접은 화면과 일반 휴대폰에서 " + when (settings.phoneDayView) {
            PhoneDayView.PREVIEW -> "달력 아래에 짧게 보이고, 한 번 더 누르면 크게 열려요"
            PhoneDayView.SHEET -> "하루 화면이 바로 크게 열려요"
            PhoneDayView.WEEK -> "그 주(월~일)만 남고, 아래에 하루 화면이 펼쳐져요"
        }, listOf(PhoneDayView.PREVIEW to "미리보기 먼저", PhoneDayView.SHEET to "바로 크게", PhoneDayView.WEEK to "그 주만 보기"), settings.phoneDayView) { v ->
            vm.update { it.copy(phoneDayView = v) }
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
        Note("근무, 바꾼 날짜, 메모, 돈 기록, 수면 기록, 급여 설정, 앱 설정을 파일 하나로 저장해요. 휴대폰을 바꾸거나 앱을 다시 설치할 때 불러오세요. 암호를 걸면 남이 열어 볼 수 없어요.")
        NavRow("백업 저장", "파일로 저장해요. 암호를 걸 수 있어요", files.saveBackup)
        HorizontalDivider()
        NavRow("파일 불러오기", "백업 파일이나 동료에게 받은 회사 설정 파일을 열어요. 불러오기 전 데이터는 ${com.bamnat.app.data.ImportUndo.KEEP_DAYS}일 동안 남겨 둬요",
            files.openFile)
        if (savedLabel != null) {
            HorizontalDivider()
            NavRow("불러오기 전으로 되돌리기", "$savedLabel 불러오기 전 데이터로 돌아가요") { confirmUndo = true }
        }
    }
    SectionCard(title = "다른 앱으로") {
        // [REL-04]
        NavRow("캘린더 앱으로 내보내기", "구글·삼성 캘린더에 근무를 넣는 파일을 만들어요 (3개월 전부터 1년 뒤까지)", files.exportIcs)
    }
}

@Composable
private fun PrivacyPage(
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    askNotifications: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val pkg = Uri.parse("package:${context.packageName}")
    SectionCard(title = "개인정보") {
        SwitchRow("사용 통계 보내기", "어떤 기능을 얼마나 쓰는지 익명으로 보내 앱 개선에 써요. 메모와 근무 내용은 보내지 않아요",
            settings.shareUsageStats) { v -> vm.update { it.copy(shareUsageStats = v) } }
        NavRow("개인정보 처리방침", "무엇을 휴대폰에 두고 무엇을 보내는지") { openPrivacy(context) }
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
        NavRow("워치·휴대폰 수면 기록", if (settings.healthSleep) "헬스 커넥트에서 읽고 있어요" else "읽지 않아요 · 잠과 휴식에서 켜요") {
            onOpenPage(SettingsPage.REST)
        }
        Note("허용을 바꾸면 휴대폰 설정 화면이 열려요. 결제 알림 읽기는 돈 탭에서 켜고 꺼요.")
    }
}

@Composable
private fun AboutPage(vm: SettingsViewModel, onOpenDiagnostics: (Boolean) -> Unit, onOpenInquiry: () -> Unit) {
    val context = LocalContext.current
    val unread by vm.inquiryUnread.collectAsStateWithLifecycle()
    SectionCard(title = null) {
        NavRow("알람 점검", "알람이 안 울리거나 늦을 때 원인을 찾아요") { onOpenDiagnostics(false) }
        HorizontalDivider()
        // [ADMIN-02] In the app, answered in the app; a build without the company server writes a mail.
        if (vm.inquiryOn) {
            NavRow("문의하기", if (unread > 0) "개발자 답이 ${unread}개 왔어요" else "앱 안에서 보내고, 답도 여기서 받아요") { onOpenInquiry() }
        } else {
            NavRow("문의하기", "메일로 보내요. 휴대폰 기종과 앱 버전이 함께 적혀요") { writeToUs(context) }
        }
        HorizontalDivider()
        NavRow("개인정보 처리방침", null) { openPrivacy(context) }
    }
    Text("밤낮 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// [ADMIN-02] The 밤낮 policy; privacy.html at the site root is another app's.
private const val PRIVACY_URL = "https://ywjmoney.github.io/-/privacy/"

private fun openPrivacy(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A mail to the developer with what helps answer it; nothing from the calendar goes in. */
internal fun writeToUs(context: android.content.Context) {
    val body = "\n\n---\n앱: 밤낮 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n기기: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$REPORT_EMAIL")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "[밤낮 문의]")
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(mail) }.onFailure {
        android.widget.Toast.makeText(context, "메일 앱이 없어요. $REPORT_EMAIL 로 보내 주세요", android.widget.Toast.LENGTH_LONG).show()
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
