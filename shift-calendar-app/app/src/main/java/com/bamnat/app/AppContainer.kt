package com.bamnat.app

import kotlinx.coroutines.sync.withLock
import android.content.Context
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.data.MoneyRepository
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.diag.EventLog
import com.bamnat.app.money.JobReminderScheduler
import com.bamnat.app.pay.LeaveReminder
import com.bamnat.app.pay.PayStore
import com.bamnat.app.sleep.DayNoticeScheduler
import com.bamnat.app.sleep.QuietHold
import com.bamnat.app.sleep.SleepingNotice
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.diag.Telemetry
import com.bamnat.app.widget.WidgetRefresher
import com.bamnat.core.sleep.SleepDebt
import kotlinx.coroutines.CoroutineScope
import com.bamnat.core.diag.DiagEvents
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import com.bamnat.core.time.Today

/** Manual dependency container, created once in [BamnatApp]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    /** [QA-08] What day and time it is: the system clock on a phone, a fixed one in screenshot tests (core Today). */
    val clock: java.time.Clock get() = Today.clock

    /**
     * Work that must outlive any screen, e.g. saving a memo while leaving the editor. [STAB-01] An error
     * in one job is written to the event log instead of closing the whole app (a full disk, a locked database).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        runCatching { telemetry.event(DiagEvents.JOB_FAILED, "scope ${e.javaClass.simpleName}") }
    })

    val database: AppDatabase = AppDatabase.create(context)
    val settingsRepository = SettingsRepository(context)
    val scheduleRepository = ScheduleRepository(database, kinds = settingsRepository.settings.map { it.extraKinds },
        extraName = { settingsRepository.current.extraKinds.name(it) })
    /** [MCAL-01] The user's calendars; the main one is [scheduleRepository]. */
    val calendars = com.bamnat.app.data.CalendarBook(
        java.io.File(appContext.filesDir, "calendars.json"), scheduleRepository, com.bamnat.app.data.CalendarStorage.Files(appContext),
        repositoryOf = { db ->
            ScheduleRepository(db, kinds = settingsRepository.settings.map { it.extraKinds }, extraName = { settingsRepository.current.extraKinds.name(it) })
        },
        mainMoney = { moneyRepository },
        mainPay = { payStore },
        // [CALS-04] One shelf of unfinished payment texts, whichever calendar they were meant for.
        moneyOf = { id, db ->
            MoneyRepository(db, captureStore, style = kotlinx.coroutines.flow.combine(calendarsFlow, settingsRepository.settings) { list, s ->
                // [CALS-02] An added calendar without a kind of work of its own follows the main one, as its tabs do.
                list.firstOrNull { it.id == id }?.takeIf { !it.isMain }?.style ?: s.workStyle
            }, confirmFirst = autoRecordPrefs.state.map { it.holdFrom },
                marks = com.bamnat.app.data.RecurringMarks(java.io.File(com.bamnat.app.data.CalendarStorage.filesOf(appContext, id), "recurring_marks.json")))
        },
        payOf = { id -> PayStore(appContext, java.io.File(com.bamnat.app.data.CalendarStorage.filesOf(appContext, id), "pay")) },
        mainLeave = { leaveRepository },
    )
    /** [MCAL-06] Calendar [id]'s days, money and pay as a screen sees them. */
    fun scopeOf(id: String) = com.bamnat.app.data.CalendarScope(calendars, settingsRepository, id)
    /** [MCAL-02] The setup of a calendar being added, kept apart from the app's first setup. */
    val calendarSetupDrafts by lazy { com.bamnat.app.data.SetupDraftStore(java.io.File(appContext.noBackupFilesDir, "calendar_setup_draft.json")) }
    /** [MEMO-02] 설정 › 메모 공유; 달력마다 따로 until the user groups calendars. */
    val memoSharing = com.bamnat.app.data.MemoSharingStore(java.io.File(appContext.filesDir, "memo_sharing.json"))
    /** [CREW-01] Each calendar's teams for 모든 조 보기. */
    val teams by lazy { com.bamnat.app.data.TeamStore(appContext) }
    /** [MEMO-01] Memos stay with the calendar they were written on, unless calendars share them. */
    val memoRepository = MemoRepository(database, sharing = kotlinx.coroutines.flow.combine(memoSharing.state, calendars.calendars) { s, list ->
        s.cleaned(list.map { it.id })
    })
    val sleepRepository = com.bamnat.app.data.SleepRepository(database)
    /** [ONBOARD-01] What this phone was told about on the first-start consent pages. */
    val consentStore by lazy { com.bamnat.app.data.ConsentStore(appContext) }
    /** [ALRM-04] 증명 사진's reference fingerprints. */
    val photoReferences by lazy { com.bamnat.app.alarm.PhotoReferences(appContext) }
    /** [SLP-08] Sleep measured by a watch or the phone, read from Health Connect. */
    val healthSleep = com.bamnat.app.sleep.HealthSleep(context)
    /** [LEDG-22][LEDG-24] How 결제 알림으로 적기 was tuned, and repeats the user turned down. */
    val autoRecordPrefs by lazy { com.bamnat.app.data.AutoRecordPrefs(appContext) }
    private val captureStore by lazy { com.bamnat.app.data.CaptureStore(java.io.File(appContext.noBackupFilesDir, "captures.json")) }
    val moneyRepository = MoneyRepository(database, captureStore, style = settingsRepository.settings.map { it.workStyle },
        confirmFirst = autoRecordPrefs.state.map { it.holdFrom },
        marks = com.bamnat.app.data.RecurringMarks(java.io.File(appContext.filesDir, "recurring_marks.json")))
    /** The calendar list as a flow, for repositories made before [calendars] is there. */
    private val calendarsFlow: kotlinx.coroutines.flow.Flow<List<com.bamnat.app.data.PersonalCalendar>>
        get() = calendars.calendars

    /**
     * [CALS-04] The calendar a payment notification belongs to: by its card's last four digits, the
     * issuer a calendar's account is named after, the app it came from, or the chosen calendar.
     */
    private val captureLock = kotlinx.coroutines.sync.Mutex()

    /**
     * [CALS-04] A payment notification into the calendar it belongs to, one at a time across calendars
     * and checked against every calendar's lines, so one payment is written once. If picking the
     * calendar fails, it goes to the main one rather than being lost.
     */
    suspend fun capture(text: String, pkg: String, app: String, postedAt: Long, fromMessenger: Boolean): MoneyRepository.Outcome =
        captureInto(text, pkg, app, postedAt, fromMessenger).also {
            // [OBS-01] 기록 지점 9: how many, never what or how much.
            if (it == MoneyRepository.Outcome.SAVED) journal.note(com.bamnat.core.journal.JournalKind.MONEY_CAPTURED)
        }

    private suspend fun captureInto(text: String, pkg: String, app: String, postedAt: Long, fromMessenger: Boolean): MoneyRepository.Outcome =
        captureLock.withLock {
            // [INV-30] A broker's 체결 notification goes to the investor calendar, not the ledger.
            runCatching { com.bamnat.app.invest.TradeCapture.take(this, text, pkg, app, postedAt, fromMessenger) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(false).let { if (it) return@withLock MoneyRepository.Outcome.SAVED }
            // [SHOP-08] A card terminal's or delivery app's notice goes to the shop calendar.
            val target = com.bamnat.app.money.ShopNotices.route(this, pkg, app) ?: runCatching { captureCalendar(text, pkg, postedAt) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(com.bamnat.app.data.PersonalCalendar.MAIN)
            // [SHOP-08] On a shop calendar its sales and card deposits are taken as sales, not ledger lines.
            runCatching { com.bamnat.app.money.ShopNotices.take(this, target, text, pkg, app, postedAt) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let { return@withLock it }
            val others = calendars.calendars.value.map { it.id }.filter { it != target }
            calendars.money(target).capture(text, app, postedAt, fromMessenger) { day ->
                others.flatMap { id -> runCatching { calendars.money(id).recent(day) }.getOrDefault(emptyList()) }
            }
        }

    suspend fun captureCalendar(text: String, pkg: String, postedAt: Long): String {
        val list = calendars.calendars.value
        val prefs = autoRecordPrefs.current
        if (list.size < 2) return com.bamnat.app.data.PersonalCalendar.MAIN
        val arrived = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(postedAt), Today.zone())
        val parsed = (com.bamnat.core.money.PaymentParser.parse(text, arrived) as? com.bamnat.core.money.PaymentParser.Result.Ok)?.parsed
        val places = list.map { com.bamnat.core.money.CaptureRoute.Place(it.id, calendars.money(it.id).allAccounts()) }
        return com.bamnat.core.money.CaptureRoute.pick(parsed?.last4, parsed?.issuer, pkg, places, prefs.appCalendars, prefs.calendar)
    }
    /** [STAT-01] 사용 통계 보내기: hears events only while the user said yes. */
    val usageStats = com.bamnat.app.diag.UsageStats(context.applicationContext)
    val telemetry = Telemetry(EventLog(context.applicationContext), settingsRepository, listOf(usageStats))
    val alarmScheduler = AlarmScheduler(context.applicationContext, telemetry) { ringSettings() }
    /** How alarms ring now; [PROV-06][PROV-07] the mission and the wake check only with 프로. */
    fun ringSettings(): com.bamnat.app.alarm.BootAlarms.RingSettings =
        com.bamnat.app.alarm.BootAlarms.RingSettings.of(settingsRepository.current, sureAlarm())
    // Read while the container is still being built would find no gate yet: then nothing is locked, as before the launch.
    @Suppress("SENSELESS_COMPARISON")
    private fun sureAlarm(): Boolean = featureGate == null || featureGate.allows(com.bamnat.app.tier.Feature.SURE_ALARM)
    val payStore = PayStore(appContext)
    /** [LEAVE-01] The main calendar's 연차 원장. */
    val leaveRepository by lazy { com.bamnat.app.leave.LeaveRepository(database, scheduleRepository, payStore) }
    val backupManager = BackupManager(database, settingsRepository, payStore = payStore, calendars = calendars, memoSharing = memoSharing,
        workTools = com.bamnat.app.data.WorkToolsStore.get(appContext), guide = com.bamnat.app.data.GuideStore.get(appContext), recordsTrash = { recordsTrash },
        teams = teams, pro = { proStore },
        shareLinks = com.bamnat.app.share.ShareLinkBackup({ calendars.calendars.value.filterNot { it.isExample }.map { it.id } }, { shareLinksOf(it) }),
        photos = com.bamnat.app.data.BackupPhotos(com.bamnat.app.ui.money.ReceiptFiles.dir(appContext)) { id -> com.bamnat.app.data.WorkToolsStore.get(appContext).photoDir(id) },
        staging = java.io.File(appContext.cacheDir, "backup-in"), profiles = { profiles })
    /** [UNDO-01][UNDO-02][UNDO-03] 최근 지운 기록: a calendar's records as they were before a change, for 7 days. */
    val recordsTrash by lazy {
        com.bamnat.app.data.RecordsTrash(java.io.File(appContext.noBackupFilesDir, "records_trash"), calendars, database, settingsRepository,
            filesOf = { id -> com.bamnat.app.data.CalendarStorage.filesOf(appContext, id) }, workTools = com.bamnat.app.data.WorkToolsStore.get(appContext),
            autoRecord = autoRecordPrefs, commute = com.bamnat.app.data.CommutePrefs.get(appContext))
    }
    /** [SET-01] The data as it was before the last import, to take the import back. */
    val importUndo by lazy { com.bamnat.app.data.ImportUndo(java.io.File(appContext.noBackupFilesDir, "before_import.json"), backupManager, recordsTrash = { recordsTrash }) }
    /** [COMM-00] Company settings as a file for coworkers. */
    val companyShare by lazy { com.bamnat.app.data.CompanyShare(scheduleRepository, payStore, settingsRepository,
        shared = { keys -> keys.forEach { runCatching { profiles.mark(com.bamnat.app.data.PersonalCalendar.MAIN, it, com.bamnat.core.profile.Source.SHARED, why = "회사 공유 설정") } } }) }
    /** [COMM-04] The company rules server; screenshots and tests put a fake in its place. */
    var companyApi: com.bamnat.app.company.CompanyApi = com.bamnat.app.company.CompanyClient(
        hidden = { com.bamnat.core.plaza.HideList.decode(settingsRepository.current.plazaHidden).header() })
    val companyStore by lazy { com.bamnat.app.company.CompanyStore(appContext) }
    /** [DAILY-16] The last region's 광장 시세, for the pay screen's comparison on the phone. */
    val rateHints by lazy { com.bamnat.app.company.RateHintStore(appContext) }
    /** [ADMIN-02] 설정 > 문의하기 through the company server; screenshots and tests put a fake in its place. */
    var inquiryApi: com.bamnat.app.company.InquiryApi = com.bamnat.app.company.InquiryClient(
        hidden = { com.bamnat.core.plaza.HideList.decode(settingsRepository.current.plazaHidden).header() })
    /** [ADMIN-02] The pay check 계산이 이상해요 hands to 문의하기; the next 문의하기 screen takes it once. */
    var pendingPayCheck: String? = null
    val inquiryStore by lazy { com.bamnat.app.company.InquiryStore(appContext) }
    /** [SETUP-01] The first setup's answers until it saves, on this phone only. */
    val setupDrafts by lazy { com.bamnat.app.data.SetupDraftStore(java.io.File(appContext.noBackupFilesDir, "setup_draft.json")) }
    val firstSetup by lazy {
        com.bamnat.app.data.FirstSetup(scheduleRepository, settingsRepository, setupDrafts, { companyShare }, { companyStore })
    }
    /** [MKT-09][SHR-02] The link server; screenshots and tests put a fake in its place. */
    var shareApi: com.bamnat.app.share.ShareApi = com.bamnat.app.share.ShareLinkClient()
    /** [MKT-09][SHR-12] The one link this phone made, shared by the sheet, the automatic update and the expiry notice. */
    /** [MCAL-02] When this run began: a calendar begun since is being made, not left over. */
    private val startedAt = Today.millis()
    val shareLinks by lazy { com.bamnat.app.share.ShareLinkStore(appContext) }
    private val addedShareLinks = java.util.concurrent.ConcurrentHashMap<String, com.bamnat.app.share.ShareLinkStore>()
    /**
     * [MCAL-03] The link of one of the user's calendars; the main one's first is [shareLinks].
     * [PROV-04] [key] is a calendar's id for its first link, or a [com.bamnat.app.share.ShareLinkKey] for another.
     */
    fun shareLinksOf(key: String): com.bamnat.app.share.ShareLinkStore =
        if (key == PersonalCalendar.MAIN) shareLinks
        else addedShareLinks.getOrPut(key) { com.bamnat.app.share.ShareLinkStore(appContext, key) }
    /** [PROV-04] Whether any of [calendarId]'s links is open. */
    fun hasShareLink(calendarId: String): Boolean =
        com.bamnat.app.share.ShareLinkKey.all(calendarId).any { shareLinksOf(it).link.value != null }
    /** [MCAL-03] A removed calendar's links go with it, their expiry notices too. */
    fun forgetShareLinks(calendarId: String) {
        if (calendarId == PersonalCalendar.MAIN) return
        for (key in com.bamnat.app.share.ShareLinkKey.all(calendarId)) {
            addedShareLinks.remove(key)?.retire()
            com.bamnat.app.share.ShareExpiryReminder.reschedule(appContext, key, null, null)
        }
        com.bamnat.app.share.ShareLinkStore.forget(appContext, calendarId)
    }
    /** [SHR-12] Sends the link again when the calendar changes. */
    val shareSync by lazy { com.bamnat.app.share.ShareSync(this) }
    /** [PROV-03] Where 자동 백업 keeps its key; tests put an in-memory one here. */
    var keyBox: com.bamnat.app.backup.KeyBox = com.bamnat.app.backup.KeystoreKeyBox()
    /** [PROV-03] 매일 자동 백업. */
    val autoBackup by lazy { com.bamnat.app.backup.AutoBackup(this, { keyBox }) }
    /** [FEAT-07] Writes the chosen calendars to the phone's calendar store. */
    val phoneCalendarSync by lazy { com.bamnat.app.calendar.PhoneCalendarSync(this) }
    /** [SHR-02] Schedules others shared, kept beside the user's own and never backed up. */
    val received by lazy { com.bamnat.app.share.ReceivedCalendarStore(java.io.File(appContext.noBackupFilesDir, "received_calendars.json")) }
    /** [SHR-14] 우리 조 모아 보기: the code this phone is in. */
    val shareGroup by lazy { com.bamnat.app.share.ShareGroupStore(appContext) }
    /** [MON-07][MON-10] When this app was first installed: the testers' thank-you and the first days without ads. */
    val installedAt: java.time.Instant? by lazy {
        runCatching { java.time.Instant.ofEpochMilli(appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime) }.getOrNull()
    }
    /** [MON-02] The 정식 출시 switch and what goes with it, from the company server once a day. */
    val remoteConfig = com.bamnat.app.tier.RemoteConfigStore(appContext)
    /**
     * [REMOTE-02] Signed 요율·간이세액표·노임단가·프리셋 files from GitHub Pages; the last good ones are laid over the
     * built-in data as the app starts.
     */
    val remoteFiles = com.bamnat.app.remote.RemoteFilesStore(
        java.io.File(appContext.noBackupFilesDir, "remote"),
        com.bamnat.app.remote.HttpRemoteFetcher(BuildConfig.REMOTE_BASE_URL),
        BuildConfig.VERSION_CODE,
    ).also { runCatching { it.applySaved() } }
    /** [SRV-04] The notices the user closed. */
    val closedNotices by lazy { com.bamnat.app.ui.calendar.ClosedNotices(appContext) }
    /** [MON-02] Everything on this phone that can give it 프로. */
    val proStore = com.bamnat.app.tier.ProStore(appContext)
    /** [MON-02] 프로 on the company server; screenshots and tests put a fake in its place. */
    var tierApi: com.bamnat.app.tier.TierApi = com.bamnat.app.tier.TierClient()
    val featureGate = FeatureGate(settingsRepository, remoteConfig, proStore, appScope, installedAt = { installedAt })
    /**
     * [MON-03] Play billing; screenshots and tests put a fake in its place. [PERF-01] Play's billing client is
     * built the first time it is used (after the 정식 출시 switch, or for a known subscription), not while
     * the app starts.
     */
    var billing: com.bamnat.app.billing.Billing
        get() = billingSet ?: playBilling
        set(value) { billingSet = value }
    private var billingSet: com.bamnat.app.billing.Billing? = null
    private val playBilling by lazy {
        com.bamnat.app.billing.PlayBilling(appContext, proStore, api = { tierApi },
            device = { companyStore.device }, scope = appScope, log = { runCatching { telemetry.event("billing", it) } },
            onBought = { trial -> telemetry.event(if (trial) com.bamnat.core.stats.UsageEvent.TRIAL_START.key else com.bamnat.core.stats.UsageEvent.SUBSCRIBE.key) })
    }
    /** [MON-07] Google's consent form and the ads SDK. */
    val ads = com.bamnat.app.ads.Ads(appScope)

    /**
     * [MON-02][MON-08][MON-09] On coming to the front: the 정식 출시 switch (once a day), this phone's
     * passes and admin link when it has any reason to, and Play's purchases; then the gate again.
     */
    fun syncPro(force: Boolean = false) = appScope.launch {
        runCatching { remoteConfig.refresh(tierApi, force = force) }
        runCatching { remoteFiles.refresh() }.getOrNull()?.let(::noteRemote)
        // [STAT-01] Yesterday's counts, once a day, only with the user's yes and the developer's switch on.
        runCatching {
            usageStats.sendIfDue(settingsRepository.current.shareUsageStats && !remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.USAGE_STATS))
        }
        runCatching { syncPasses(force) }
        runCatching { sendConsent() }
        // Play is asked only once paying is possible, or for a subscription this phone already knew.
        if (remoteConfig.current.billingLive || proStore.current.sub != null) runCatching { billing.refresh() }
        featureGate.refresh()
        // [STAT-04] A day with a paid subscription counts toward the G2 gate (구독 사용자 비율).
        if (featureGate.state.value.access.reason == com.bamnat.core.tier.ProReason.SUBSCRIPTION) {
            telemetry.event(com.bamnat.core.stats.UsageEvent.PRO_SUB.key)
        }
    }

    /** [OBS-01] 기록 지점 2·3·4: new files, and what they changed (the holidays, the pay worked out again). */
    private fun noteRemote(kinds: List<String>) {
        if (kinds.isEmpty()) return
        val f = com.bamnat.core.remote.RemoteFiles
        journal.note(com.bamnat.core.journal.JournalKind.REMOTE_FILE, "${kinds.size}개")
        if (f.HOLIDAYS in kinds) journal.note(com.bamnat.core.journal.JournalKind.HOLIDAYS)
        if (kinds.any { it in setOf(f.RATES, f.WAGES, f.TAXTABLE, f.PUBLICPAY, f.TAXES) }) journal.note(com.bamnat.core.journal.JournalKind.PAY_RECALC)
    }

    /**
     * [SRV-07] Sends the trial answer waiting in [ProStore]; true once the server has it. A refusal for the
     * answer itself (not the connection) is dropped, as sending it again would not change it.
     */
    suspend fun sendConsent(): Boolean {
        val q = proStore.current.pendingConsent ?: return true
        if (q.stale(Today.instant())) {
            proStore.consentSent(q)
            return true
        }
        if (!tierApi.enabled) return false
        return try {
            tierApi.consent(companyStore.device, q.answer, q.product, q.price, q.trialEnd, q.shown, q.answeredAt)
            proStore.consentSent(q)
            true
        } catch (e: com.bamnat.app.company.CompanyException) {
            if (!e.retryable) {
                runCatching { telemetry.event("consent", "dropped ${e.code}") }
                proStore.consentSent(q)
            } else com.bamnat.app.company.OutboxJob.schedule(appContext)
            false
        }
    }

    /** [MON-08] Only a phone that sent a report or a question, holds passes or brought a code asks the server. */
    suspend fun syncPasses(force: Boolean = false) {
        if (!tierApi.enabled) return
        val s = proStore.current
        val device = companyStore.device
        s.pendingClaim?.let { code ->
            try {
                proStore.setPasses(tierApi.claim(device, code))
                proStore.claimLater(null)
            } catch (e: com.bamnat.app.company.CompanyException) {
                // A spent or unknown code is not tried again; no connection is.
                if (e.code != "offline" && e.code != "rate_limited") proStore.claimLater(null)
            }
        }
        val reason = s.passes != null || companyStore.sent.value.isNotEmpty() || inquiryStore.asked || s.code != null
        val last = proStore.current.passes?.checkedAt
        if (!reason || (!force && last != null && java.time.Duration.between(last, Today.instant()) < PASSES_EVERY)) return
        proStore.setPasses(tierApi.passes(device))
    }
    /** [IMPORT-01] Samsung Calendar and the phone's other calendars, brought in on request. */
    val calendarImporter by lazy {
        com.bamnat.app.imports.CalendarImporter(appContext, com.bamnat.app.imports.DeviceCalendars(appContext), scheduleRepository,
            memoRepository, database.importedEventDao())
    }
    /** [NAV-01] The month the calendar, 돈 and 근무·급여 show together. */
    val shownMonth = com.bamnat.app.ui.ShownMonth()
    /** [NOTE-02] The memo tab's notebook, kept while the app runs so coming back finds the same list. */
    val memoNotebook = kotlinx.coroutines.flow.MutableStateFlow(com.bamnat.core.memo.MemoNotebook.CALENDAR)

    // [EASY-01..05][OBS-01] 1.23 묶음 N: the example of 우선 둘러볼게요, the profile over the existing stores, the
    // question card's memory and '밤낮이 한 일'. All built on first use; none starts a job of its own.
    val browse by lazy { com.bamnat.app.data.BrowseStore(appContext) }
    val example by lazy { com.bamnat.app.data.ExampleCalendar(this) }
    val profiles by lazy { com.bamnat.app.data.ProfileStore(this) }
    val answers by lazy { com.bamnat.app.data.ProfileAnswers(this) }
    val asks by lazy { com.bamnat.app.data.AskStore(appContext) }
    val journal by lazy { com.bamnat.app.data.JournalStore(java.io.File(appContext.noBackupFilesDir, "journal.jsonl"), appScope) }

    private companion object {
        /** A shorter sleep (a tap and an undo, a doze) gets no "잤어요" line. */
        const val WOKE_SUMMARY_MINUTES = 20L
        const val RETRY_FIRST_MILLIS = 2_000L
        const val RETRY_MAX_MILLIS = 60_000L
        /** [PROV-03] After the app has opened and settled, not while the first screen draws. */
        const val AUTO_BACKUP_DELAY_MILLIS = 20_000L
        /** [PERF-03] When no calendar month showed by then, the waiting jobs start anyway. */
        const val LATER_FALLBACK_MILLIS = 3_000L
        /** [MON-09] Often enough for the admin link's weekly check, rare enough to stay quiet. */
        val PASSES_EVERY: java.time.Duration = java.time.Duration.ofHours(6)
        /** [OBS-01] The next alarm in '밤낮이 한 일': "10/24 06:30". */
        val JOURNAL_ALARM: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")
    }

    suspend fun rescheduleAlarms(after: LocalDateTime = Today.dateTime()) {
        alarmScheduler.reschedule(calendars.ringing.first(), settingsRepository.current.alarmsEnabled, after)
    }

    /** [REL-13] Checks, each time the app comes to the front, that the system still holds the next alarm. */
    fun verifyAlarms() = appScope.launch {
        runCatching { alarmScheduler.verify(calendars.ringing.first(), settingsRepository.current.alarmsEnabled) }
    }

    /** [PERF-01] The names of the jobs [keep] started, for the startup budget test. */
    internal val kept: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * [STAB-01] A job that follows the data for as long as the app runs. When it fails it is logged and
     * started again a little later, so one bad moment does not leave alarms or notices unplanned until
     * the next launch.
     */
    private fun keep(name: String, job: suspend CoroutineScope.() -> Unit) = appScope.also { kept += name }.launch {
        var failures = 0
        while (isActive) {
            try {
                coroutineScope { job() }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { telemetry.event(DiagEvents.JOB_FAILED, "$name ${e.javaClass.simpleName}") }
                failures++
                delay(minOf(RETRY_MAX_MILLIS, RETRY_FIRST_MILLIS shl minOf(failures - 1, 5)))
            }
        }
    }

    /** [STAT-01] 사용 통계 보내기 on or off; off forgets what was waiting to go, right then. */
    fun setUsageStats(on: Boolean) {
        settingsRepository.update { it.copy(shareUsageStats = on) }
        if (!on) usageStats.clear()
    }

    @OptIn(FlowPreview::class)
    fun start() {
        // [STAT-01] A no that came another way (a restored backup) forgets what waited, without a job of its own.
        usageStats.forgetUnless(settingsRepository.current.shareUsageStats)
        keep("schedule") {
            scheduleRepository.ensureDefaults()
            // [SETUP-01] A first setup the app was closed in the middle of saving is finished first.
            runCatching { firstSetup.finishInterrupted() }
            memoRepository.purgeOldTrash()
            // [UNDO-01] What was cleared or removed more than 7 days ago goes for good.
            runCatching { recordsTrash.purge() }
            moneyRepository.purgeCaptures()
            // [MCAL-02] A calendar whose setup was left before saving is not kept.
            if (calendarSetupDrafts.draft.value == null) calendars.dropUnfinished(before = startedAt)
            // [EASY-01] An example the app no longer shows (a restored list without its database) goes, with its memos.
            runCatching { example.sweep() }
            // Any edit to shift types, the pattern, a day or the master switch re-plans the next alarm.
            // [MCAL-01] So does one in an added calendar whose alarms ring.
            val enabled = settingsRepository.settings.map { it.alarmsEnabled }.distinctUntilChanged()
            combine(calendars.ringing, enabled) { ringing, on -> ringing to on }
                .debounce(300)
                .collect { (ringing, on) ->
                    alarmScheduler.reschedule(ringing, on)
                    // [OBS-01] 기록 지점 1: a line only when the next alarm moved.
                    alarmScheduler.upcoming.value?.let { journal.noteIfChanged(com.bamnat.core.journal.JournalKind.ALARMS_PLANNED, it.plan.triggerAt.format(JOURNAL_ALARM)) }
                    WidgetRefresher.refresh(appContext)
                }
        }
        // [SLP-15] 하루 브리핑, 퇴근길 and 잘 준비 follow the schedule, the alarms and their settings.
        keep("notices") {
            val reads = settingsRepository.settings.map {
                listOf(it.briefing, it.commuteNotice, it.bedtimeNotice, it.sleepTargetMinutes, it.commuteMinutes, it.alarmsEnabled, it.workStyle)
            }.distinctUntilChanged()
            combine(scheduleRepository.snapshot, reads) { snapshot, _ -> snapshot }
                .debounce(500)
                .collect { DayNoticeScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [PROV-03] A day's backup owed when the app opens goes now; the job keeps it going while closed.
        keep("autobackup") {
            if (autoBackup.on) {
                com.bamnat.app.backup.AutoBackupJob.schedule(appContext)
                delay(AUTO_BACKUP_DELAY_MILLIS)
                autoBackup.runIfDue()
            }
        }
        // [SLP-15] 자는 중 follows the sleep log, and a sleep that just ended says how long it was.
        // What waited while the user slept is planned again from the log.
        keep("sleeping") {
            var openId: Long? = null
            val on = settingsRepository.settings.map { it.sleepingNotice }.distinctUntilChanged()
            combine(sleepRepository.recent(days = 2), on, alarmScheduler.upcoming) { log, show, next -> Triple(log, show, next) }
                .collect { (log, show, next) ->
                    val now = Today.dateTime()
                    val open = log.firstOrNull {
                        it.end == null && !it.imported && Duration.between(it.start, now).toMinutes() < SleepDebt.MAX_SLEEP_MINUTES
                    }
                    when {
                        open != null && show ->
                            SleepingNotice.show(appContext, open, next?.let { AlarmScheduler.millisToLocal(it.triggerAtMillis) }, now)
                        openId != null -> {
                            // The sleep shown a moment ago ended (or was deleted).
                            val ended = log.firstOrNull { it.id == openId && it.end != null }
                            if (show && ended != null && (ended.minutes ?: 0) >= WOKE_SUMMARY_MINUTES) {
                                SleepingNotice.woke(appContext, ended, SleepingNotice.nextWork(scheduleRepository.snapshot.first().resolver, now))
                            } else {
                                SleepingNotice.cancel(appContext)
                            }
                        }
                        !show -> SleepingNotice.cancel(appContext)
                    }
                    openId = open?.id
                    QuietHold.release(appContext)
                }
        }
        // [REL-10] The locked-boot copy carries the ringing settings too, so it follows their changes.
        keep("bootcopy") {
            combine(settingsRepository.settings, featureGate.state.map { it.allows(com.bamnat.app.tier.Feature.SURE_ALARM) }) { s, sure ->
                com.bamnat.app.alarm.BootAlarms.RingSettings.of(s, sure)
            }.distinctUntilChanged().drop(1).collect { alarmScheduler.saveBootCopy() }
        }
        // [PERF-03] The rest waits for the calendar's first month on screen ([afterFirstFrame]), or a few seconds
        // when the app was started without it (an alarm, a widget, the first setup): the boot receiver plans
        // every notice by itself, so these follow later changes.
        appScope.launch {
            delay(LATER_FALLBACK_MILLIS)
            afterFirstFrame()
        }
    }

    private val laterStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * [PERF-03] Starts the jobs that can wait until the calendar has drawn its first month: widgets, the watch,
     * links, the phone calendar, the plaza and the notices planned days ahead. Called by the calendar once its
     * month is there and by [start]'s fallback; only the first call does anything.
     */
    fun afterFirstFrame() {
        if (!laterStarted.compareAndSet(false, true)) return
        keptBeforeLater = kept.toList()
        startLater()
    }

    /** [PERF-03] What [start] had running when the waiting jobs started, for the startup budget test. */
    @Volatile internal var keptBeforeLater: List<String>? = null
        private set

    @OptIn(FlowPreview::class)
    private fun startLater() {
        // [WORK-02] Any change to the booked jobs re-plans the next job reminder.
        // [SLP-15] So does the 나갈 돈·받을 돈 switch.
        keep("money") {
            val on = settingsRepository.settings.map { it.moneyNotices }.distinctUntilChanged()
            // [MCAL-06] A job booked on any calendar is told about.
            combine(calendars.everyMoney, on) { book, _ -> book }.debounce(500)
                .collect { JobReminderScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [SCHED-01] A memo saved, moved to the trash, restored or brought back from a backup re-plans the next schedule.
        keep("memoschedule") {
            memoRepository.memos.map { list -> list.filter { it.reminder != null } }
                .distinctUntilChanged { a, b -> a.map { it.id to it.reminder } == b.map { it.id to it.reminder } }
                .debounce(300)
                .collect { com.bamnat.app.memo.MemoScheduleAlarm.reschedule(appContext, it) }
        }
        // [INV-08] An investor alarm or date saved, changed or restored re-plans the next investor notice.
        keep("invest") { com.bamnat.app.invest.InvestNotices.follow(this@AppContainer) }
        // [INV-34] 월말 요약's next notice while there is an investor calendar; it reads them when it comes.
        keep("investmonth") {
            com.bamnat.app.invest.InvestNotices.plans(this@AppContainer).map { it.isNotEmpty() }.distinctUntilChanged()
                .collect { com.bamnat.app.invest.InvestMonthNotice.schedule(appContext, it) }
        }
        // [FEAT-18] Turning 잠금 화면 오늘 근무 on or off shows or removes it now, not at midnight.
        keep("today notice") {
            settingsRepository.settings.map { it.todayShiftNotice }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [LEAVE-03] 연차 사용 촉진 notices follow the ledger's lots (제61조 dates) and their switch.
        keep("leave") {
            val lots = leaveRepository.summary.map { s -> s.lots.map { it.lot } }.distinctUntilChanged()
            val on = settingsRepository.settings.map { it.leaveNotice }.distinctUntilChanged()
            combine(lots, on) { l, o -> l to o }
                .collect { (l, o) -> LeaveReminder.reschedule(appContext, l, o) }
        }
        // [COMM-10] 광장 소식, [SRV-10] since 1.23 밤낮 소식: the one daily job runs while it has something to do here.
        keep("plaza news") {
            combine(
                settingsRepository.settings.map { listOf(it.plazaNews, it.plazaRulesAgreed, it.phoneCalendars, it.alarmsEnabled) },
                companyStore.sent, companyStore.applied, received.calendars.map { it.isEmpty() },
            ) { a, _, _, _ -> a }
                .distinctUntilChanged()
                .collect {
                    if (com.bamnat.app.company.PlazaNewsJob.wanted(this@AppContainer)) com.bamnat.app.company.PlazaNewsJob.schedule(appContext)
                    else com.bamnat.app.company.PlazaNewsJob.cancel(appContext)
                }
        }
        // [MON-06] The trial's notice follows the subscription and the user's answer.
        keep("trialnotice") {
            proStore.state.map { Triple(it.sub, it.consentAt, it.trialNoticeFor) }.distinctUntilChanged()
                .collect { com.bamnat.app.tier.TrialNotice.reschedule(appContext, proStore.current) }
        }
        // [SHR-12] The link this phone made follows the calendar, and its expiry notice follows the link.
        keep("share") { if (shareApi.enabled) shareSync.follow() }
        // [FEAT-07] The phone's 밤낮 calendars follow the calendars written there.
        keep("phonecal") { phoneCalendarSync.follow() }
        // [MCAL-03] Each of the user's calendars has its own link and so its own notice; [PROV-04] each of its links.
        keep("shareexpiry") {
            calendars.calendars.map { list -> list.map { it.id } }.distinctUntilChanged().collectLatest { ids ->
                coroutineScope {
                    ids.flatMap { com.bamnat.app.share.ShareLinkKey.all(it) }.forEach { key ->
                        launch {
                            val store = shareLinksOf(key)
                            store.link.collect { com.bamnat.app.share.ShareExpiryReminder.reschedule(appContext, key, it, store.noticedFor) }
                        }
                    }
                }
            }
        }
        // Widgets lay out weeks from the user's first day of the week.
        keep("widgetweek") {
            settingsRepository.settings.map { it.weekStart }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [SLP-14] The today widget's line of 오늘의 흐름 follows the sleep log and the settings it reads.
        keep("widgetflow") {
            val reads = settingsRepository.settings.map {
                listOf(it.sleepTips, it.sleepTargetMinutes, it.commuteMinutes, it.restCheck, it.strictRest, it.restRules, it.workStyle)
            }.distinctUntilChanged()
            combine(sleepRepository.recent(days = 2), reads) { log, r -> log to r }
                .drop(1).debounce(500)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [DAILY-01] The today widget's memo lines follow 내 달력's memos for today and tomorrow, and their switch.
        keep("widgetmemo") {
            val on = settingsRepository.settings.map { it.widgetMemo }.distinctUntilChanged()
            combine(memoRepository.memos, on) { list, show ->
                if (!show) emptyMap() else {
                    val today = Today.date()
                    listOf(today, today.plusDays(1)).associateWith { d -> list.filter { it.date == d }.map { it.title + it.preview } }
                }
            }.distinctUntilChanged().drop(1).debounce(500)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [SHFT-08][REL-09] The watch follows the main calendar and its next alarm, from app start on.
        keep("watch") {
            combine(scheduleRepository.snapshot, alarmScheduler.upcoming) { snap, next -> snap to next }
                .debounce(1_000)
                .collect { (snap, next) -> com.bamnat.app.watch.WatchSync.push(appContext, snap, next) }
        }
        // [DAYW-07] The workplace geofence goes in if it is not there yet (the same circle is left alone),
        // location coming back on is listened for while the feature is on, and the periodic check is set.
        if (com.bamnat.app.data.CommutePrefs.get(appContext).current.enabled) {
            keep("commute") { runCatching { com.bamnat.app.commute.CommuteRecorder.catchUp(appContext) } }
        }
    }
}
