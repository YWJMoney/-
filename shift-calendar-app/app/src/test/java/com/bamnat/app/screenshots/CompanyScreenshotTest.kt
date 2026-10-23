package com.bamnat.app.screenshots

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.core.consent.ConsentItem
import com.bamnat.app.ui.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.glance.appwidget.compose
import java.time.LocalDate
import com.bamnat.core.work.WorkStyle

/** [QA-05] Screenshots: 회사·광장·문의·공유 링크·받은 달력·조. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompanyScreenshotTest : ScreenshotBase() {
    @Test fun foldedShareLink() = shoot("48_folded_share_link", FOLDED) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content {
            com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {},
                preview = com.bamnat.app.share.ShareLinkState("Ab3dE", "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv", "t",
                    java.time.Instant.now().plus(java.time.Duration.ofDays(30)), "민지"))
        }
    }
    @Test fun foldedShareLinkBusiness() = shoot("200_folded_share_link_business", FOLDED, shareSheet(WorkStyle.BUSINESS))
    @Test fun foldedShareLinkField() = shoot("201_folded_share_link_field", FOLDED, shareSheet(WorkStyle.FIELD))
    @Test fun foldedShareLinkBusinessLargeText() = shoot("202_folded_share_link_business_large_text", FOLDED, 1.5f, shareSheet(WorkStyle.BUSINESS))
    @Test fun firstLaunchSetupCompany() = shoot("61b_folded_setup_company", FOLDED, fresh(null) { tapText("순서가 돌아가요") })
    @Test fun foldedCompanyShare() = shoot("83_folded_company_share", FOLDED) {
        content { com.bamnat.app.ui.settings.CompanyShareDialog(hasPay = true, hasRotation = true, onDismiss = {}, onShare = { _, _, _ -> }) }
        settle()
    }
    @Test fun foldedCompanyImport() = shoot("84_folded_company_import", FOLDED) {
        content {
            com.bamnat.app.ui.settings.CompanyImportDialog(
                com.bamnat.app.data.CompanySummary("1공장", listOf("주간", "야간", "휴무"), 5, true, listOf("야간수당", "명절상여"), false, team = "A조"),
                hasPay = true, onDismiss = {}, onApply = {},
            )
        }
        settle()
    }

    @Test fun foldedCompanyFind() = shoot("114_folded_company_find", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn()).apply { query.value = "예시" }
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
        waitFor(400)
    }
    @Test fun foldedCompanyFirstLook() = shoot("115_folded_company_first_look", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
    }
    @Test fun foldedCompanyNotFound() = shoot("116_folded_company_not_found", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn()).apply { query.value = "가나다화학" }
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
        waitFor(400)
    }
    @Test fun foldedCompanyDetail() = shoot("117_folded_company_detail", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}) }
        tapText("급여 계산 방식 보기", prefix = true)
    }
    @Test fun foldedCompanyToday() = shoot("118_folded_company_today", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, openPhasesFor = "s1") }
    }
    @Test fun foldedCompanyReport() = shoot("120_folded_company_report", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학", "품질팀"))
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
        scrollUp(600)
    }
    @Test fun foldedCompanyReportEnd() = shoot("130_folded_company_report_end", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학", "품질팀"))
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
        reveal("보내기", exact = true)
    }
    @Test fun foldedCompanySent() = shoot("121_folded_company_sent", FOLDED) {
        val c = companyOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        vm.send("가나다화학", "품질팀", true, "")
        settle()
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
    }
    @Test fun foldedCompanyMine() = shoot("122_folded_company_mine", FOLDED) {
        val c = companyOn()
        // Each send goes on top, so the oldest goes in first.
        company.mine.reversed().forEach { c.companyStore.addSent(com.bamnat.app.company.SentReport(it.id, it.company, it.dept, it.ask, it.createdAt)) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanySentScreen(vm, onBack = {}, onShare = {}) }
    }
    @Test fun foldedCompanyVote() = shoot("123_folded_company_vote", FOLDED, votePay())
    @Test fun foldedCompanyWrong() = shoot("124_folded_company_wrong", FOLDED) {
        votePay().invoke(this)
        tapText("달라요", exact = true)
        tapText("급여 계산", exact = true)
    }
    @Test fun foldedCompanyPlea() = shoot("125_folded_company_plea", FOLDED) {
        votePay().invoke(this)
        tapText("달라요", exact = true)
        tapText("급여 계산", exact = true)
        tapText("알려 주기", exact = true)
    }
    @Test fun foldedCompanyInvite() = shoot("126_folded_company_invite", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학"))
        val nudges = com.bamnat.app.ui.company.CompanyNudgeViewModel(c)
        payScreen(Tier.PRO, withProfile = true) { vm ->
            PayScreen(vm, onBack = {}, top = { com.bamnat.app.ui.company.CompanyNudgeCards(nudges, onShare = {}, onFix = {}) })
            com.bamnat.app.ui.company.InviteSheet(nudges, onShare = {}, onDismiss = {})
        }.invoke(this)
    }
    @Test fun foldedSettingsCompany() = shoot("127_folded_settings_company", FOLDED) {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, java.time.Instant.now(), vote = true))
        settingsPage("급여와 연차")
        reveal("같은 회사 사람들과", exact = true)
    }
    @Test fun unfoldedCompanyDetail() = shoot("129_unfolded_company_detail", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
    }

    // [COMM-06][COMM-07] 회사 규칙 광장: every company's rule sets to vote on, the talk on one, and 맞았어요·달라요 on the company screen.
    @Test fun foldedCompanyBoard() = shoot("190_folded_company_board", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
    }
    @Test fun foldedCompanyBoardAdopted() = shoot("191_folded_company_board_adopted", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        tapText("채택됨", exact = true)
    }
    @Test fun unfoldedCompanyBoard() = shoot("192_unfolded_company_board", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
    }
    @Test fun foldedCompanyBoardSearch() = shoot("195_folded_company_board_search", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        tapText("채택됨", exact = true)
        vm.query.value = "예시"
        // Past the pause the board waits for typing to stop.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        settle()
    }
    @Test fun foldedCompanyTalk() = shoot("193_folded_company_talk", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        vm.talk.show("c-v1", "s-v1", "가나다화학 · 품질팀")
        settle()
    }
    // [PLZ-01][PLZ-05][PLZ-09] 맞아요·달라요 on each post, answers under the post, and a proposal in fields.
    @Test fun foldedPlazaTalkProposal() = shoot("355_folded_plaza_talk_proposal", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.talk.show("c-v1", "s-v1", "가나다화학 · 품질팀")
        settle()
        tapText("고치자는 제안", exact = true)
    }
    // [PLZ-12][PLZ-05] The job plazas, 시·도 and order as chips on the board.
    @Test fun phonePlazaBoard() = shoot("356_phone_plaza_board", PHONE) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
    }
    // [PLZ-14][PLZ-15] 임금표: 광장 시세 and the rates people sent.
    @Test fun foldedPlazaRates() = shoot("357_folded_plaza_rates", FOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
    }
    @Test fun phonePlazaRatesHugeText() = shoot("358_phone_plaza_rates_huge_text", PHONE, fontScale = 2f) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
    }
    // [PLZ-08] 내 활동 with the plaza name.
    @Test fun foldedPlazaActivity() = shoot("359_folded_plaza_activity", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.activity.show()
        settle()
    }
    // [PLZ-04] How a rule grew, version by version.
    @Test fun foldedCompanyHistory() = shoot("360_folded_company_history", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
        settle()
        tapText("바뀐 기록", exact = true)
    }
    @Test fun foldedCompanyVoteAnySetup() = shoot("194_folded_company_vote_any", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
        reveal("다니는 회사라면 이 규칙이 맞는지 알려 주세요")
    }

    @Test fun foldedShareSheet() = shoot("131_folded_share_sheet", FOLDED) {
        val c = receivedOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false) }
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares, onOpenReceived = {}) }
        tapText("연장·휴무일 근무", exact = true)
        tapText("메모", exact = true)
    }
    @Test fun foldedShareSheetEnd() = shoot("131b_folded_share_sheet_end", FOLDED) {
        val c = receivedOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false) }
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares, onOpenReceived = {}) }
        tapText("메모", exact = true)
        tapText("급여", exact = true)
        reveal("동료 근무표도 받아 볼까요?")
    }
    @Test fun foldedIdentityBar() = shoot("132_folded_identity_bar", FOLDED) { receivedOn() }
    @Test fun foldedCalendarSwitcher() = shoot("133_folded_calendar_switcher", FOLDED) {
        receivedOn()
        settle()
        tapText("달력 바꾸기", prefix = true)
    }
    @Test fun foldedReceivedCalendar() = shoot("134_folded_received_calendar", FOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
    }
    @Test fun foldedReceivedDay() = shoot("135_folded_received_day", FOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(restTogether()), prefix = true, scrolls = 0)
        reveal("둘 다 쉬는 날이에요")
    }
    @Test fun unfoldedReceivedCalendar() = shoot("136_unfolded_received_calendar", UNFOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(restTogether()), prefix = true, scrolls = 0)
    }
    @Test fun foldedReceivedLink() = shoot("137_folded_received_link", FOLDED) {
        // A link tapped in a message opens the app with it [SHR-02].
        receivedOn(minji = false).received.open(minjiId)
        waitFor(300)
    }
    @Test fun foldedReceivedGone() = shoot("138_folded_received_gone", FOLDED) {
        val c = receivedOn()
        c.received.markGone(junhoId)
        settle()
        tapText("달력 바꾸기", prefix = true)
        tapText("준호의 근무표", exact = true)
    }
    @Test fun foldedReceivedDark() = shoot("139_folded_received_dark", "w369dp-h905dp-port-night-420dpi") {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
    }

    @Test fun foldedMyCalendarsSwitcher() = shoot("210_folded_my_calendars_switcher", FOLDED) {
        receivedOn()
        addedCalendar()
        settle()
        tapText("달력 바꾸기", prefix = true)
    }
    @Test fun foldedAddedCalendarShare() = shoot("218_folded_added_calendar_share", FOLDED) {
        val c = receivedOn()
        addedCalendar(show = true)
        val snap = runBlocking { c.calendars.activeSnapshot.first() }
        content {
            com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares,
                calendar = c.calendars.current(), onOpenReceived = {})
        }
    }

    @Test fun foldedSettingsNotices() = shoot("141_folded_settings_notices", FOLDED) { settingsPage("알림과 잠") }
    @Test fun unfoldedSettingsNotices() = shoot("143_unfolded_settings_notices", UNFOLDED) { settingsPage("알림과 잠", wide = true) }
    @Test fun calReceivedJobs() = shoot("311_folded_received_jobs", FOLDED) {
        jobsLinkOn()
        settle()
        tapText("준호의 근무표 보기", exact = true)
    }
    @Test fun calReceivedJobsDay() = shoot("312_unfolded_received_jobs", UNFOLDED) {
        jobsLinkOn()
        settle()
        tapText("준호의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(today), prefix = true, scrolls = 0)
    }

    @Test fun phoneTeams() = shoot("350_phone_teams", PHONE) { teams() }
    @Test fun smallPhoneTeams() = shoot("351_small_phone_teams", SMALL_PHONE) { teams() }
    @Test fun foldedTeams() = shoot("352_folded_teams", FOLDED) { teams() }
    @Test fun unfoldedTeams() = shoot("353_unfolded_teams", UNFOLDED) { teams() }
    @Test fun phoneTeamsHugeText() = shoot("354_phone_teams_huge_text", PHONE, fontScale = 2f) { teams() }
    @Test fun phoneCoverAsk() = shoot("357_phone_cover_ask", PHONE) { coverAsk() }
    @Test fun smallPhoneCoverAsk() = shoot("358_small_phone_cover_ask", SMALL_PHONE) { coverAsk() }
    @Test fun phoneInquiryProgress() {
        inquiriesOn(asked = true)
        shoot("359_phone_inquiry_progress", PHONE) { inquiryThread() }
    }
    @Test fun smallPhoneInquiryProgress() {
        inquiriesOn(asked = true)
        shoot("360_small_phone_inquiry_progress", SMALL_PHONE) { inquiryThread() }
    }
    @Test fun phoneShareFeed() = shoot("389_phone_share_feed", PHONE) { shareWithFeed { reveal("구독 주소 복사", exact = true) } }
    @Test fun phoneShareSecondLink() = shoot("390_phone_share_second_link", PHONE) { shareWithFeed { tapText("+ 새 링크", exact = true, scrolls = 0) } }
    @Test fun phoneShareRosterPdf() = shoot("391_phone_share_roster_pdf", PHONE) { shareWithFeed { reveal("${java.time.YearMonth.now().year}년 PDF", exact = true) } }

    @Test fun phoneNoticeLine() = shoot("410_phone_notice_line", PHONE) { opsOn() }
    @Test fun phoneNoticeOpen() = shoot("411_phone_notice_open", PHONE) { opsOn(); settle(); tapText("오늘 밤 2시 30분 동안 점검해요", exact = true, scrolls = 0) }
    @Test fun phonePlazaRules() = shoot("412_phone_plaza_rules", PHONE) {
        app.container.settingsRepository.update { it.copy(plazaRulesAgreed = 0) }
        content {
            val gate = com.bamnat.app.ui.company.rememberPlazaRules()
            androidx.compose.runtime.LaunchedEffect(Unit) { gate.ensure {} }
        }
    }
    @Test fun phoneHideWriter() = shoot("413_phone_hide_writer", PHONE) {
        content { com.bamnat.app.ui.company.HideWriterDialog(writer(3, "야간전담"), onDismiss = {}, onHide = {}) }
    }
    @Test fun phonePlazaActivityHidden() = shoot("414_phone_plaza_activity_hidden", PHONE) {
        val at = System.currentTimeMillis()
        app.container.settingsRepository.update {
            it.copy(plazaHidden = com.bamnat.core.plaza.HideList().hide("a".repeat(64), "야간전담", at - 86_400_000L).hide("b".repeat(64), "참여자 7", at).encode())
        }
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.activity.show()
        settle()
        reveal("가린 사람")
    }
    @Test fun phonePlazaWritePaused() = shoot("415_phone_plaza_write_paused", PHONE) {
        opsOn(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE)
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.talk.show("c-v1", "s-v1", "가나다화학 · 품질팀")
        settle()
    }
    @Test fun phoneUsageStatsRow() = shoot("416_phone_usage_stats_row", PHONE) {
        app.container.settingsRepository.update { it.copy(shareUsageStats = true) }
        settingsPage("개인정보·앱 정보"); reveal("사용 통계 보내기", exact = true)
    }
    @Test fun phoneUsageStatsPreview() = shoot("417_phone_usage_stats_preview", PHONE) {
        val day = LocalDate.now().toEpochDay() - 1
        app.getSharedPreferences("usage_stats", android.content.Context.MODE_PRIVATE).edit().putString("book",
            com.bamnat.core.stats.UsageBook().record(com.bamnat.core.stats.UsageEvent.OPEN, day).record(com.bamnat.core.stats.UsageEvent.OPEN, day)
                .record(com.bamnat.core.stats.UsageEvent.PAY_VIEW, day).record(com.bamnat.core.stats.UsageEvent.SHARE_MADE, day).encode()).commit()
        app.container.settingsRepository.update { it.copy(shareUsageStats = true) }
        settingsPage("개인정보·앱 정보"); reveal("보낼 내용 보기", exact = true); tapText("보낼 내용 보기", exact = true, scrolls = 0)
    }
    @Test fun phonePlazaNewsSwitch() = shoot("418_phone_plaza_news_switch", PHONE) {
        settingsPage("알림과 잠"); reveal("광장 소식", exact = true)
    }
    @Test fun foldedReceivedReport() = shoot("419_folded_received_report", FOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
        tapText("이 근무표 신고하기", exact = true)
    }
    @Test fun phoneInquiryPhotos() {
        inquiriesOn(asked = true)
        shoot("420_phone_inquiry_photos", PHONE) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "달력"))
            tapText("궁금해요", exact = true)
            reveal("사진 붙이기", exact = true)
        }
    }
    @Test fun smallPhoneNoticeLine() = shoot("421_small_phone_notice_line", SMALL_PHONE) { opsOn() }

    @Test fun store2Teams() = shoot("store_2_teams", PHONE, clean { teams() })
    @Test fun store4CoverAsk() = shoot("store_4_cover", PHONE, clean { coverAsk() })
    @Test fun store8Inquiry() {
        inquiriesOn(asked = true)
        shoot("store_8_inquiry", PHONE, clean { inquiryThread() })
    }
    @Test fun foldedCalendarShare() = shoot("320_folded_calendar_share", FOLDED)
    @Test fun unfoldedCalendarShare() = shoot("321_unfolded_calendar_share", UNFOLDED)
    @Test fun phoneCalendarShare() = shoot("322_phone_calendar_share", PHONE)
    @Test fun phoneCalendarShareHugeText() = shoot("323_phone_calendar_share_huge_text", PHONE, fontScale = 2f)
    @Test fun foldedSwitcherMineOnly() {
        app.container.shareApi = shares
        shoot("324_folded_switcher_mine_only", FOLDED) { tapText("달력 바꾸기", prefix = true) }
    }
    @Test fun foldedReceiveNoLinkYet() {
        app.container.shareApi = shares
        shoot("325_folded_receive_no_link", FOLDED) {
            tapText("달력 바꾸기", prefix = true)
            tapText("동료 근무표 받기", exact = true)
            reveal("링크 부탁하는 메시지 보내기")
        }
    }
    @Test fun foldedIdentityBarWithFaces() = shoot("326_folded_identity_bar_faces", FOLDED) { receivedOn() }

    @Test fun foldedSettingsCalendarShareRows() {
        app.container.shareApi = shares
        shoot("332_folded_settings_calendar_share", FOLDED) { settingsPage("달력 화면") }
    }
    @Test fun foldedInquiryFormTips() {
        inquiriesOn()
        shoot("337_folded_inquiry_form_tips", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "근무표 공유", kind = com.bamnat.app.company.InquiryKind.QUESTION))
            typeInto("[근무표 공유] ", "[근무표 공유] 동료에게 근무표 링크를 보내고 싶어요")
            reveal("혹시 이런 내용인가요?")
        }
    }
    @Test fun foldedInquiryMineUnread() {
        inquiriesOn(asked = true)
        shoot("338_folded_inquiry_mine_unread", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
        }
    }
    @Test fun foldedInquiryByMailForm() {
        inquiriesOn(enabled = false)
        shoot("343_folded_inquiry_mail_form", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "달력"))
            tapText("궁금해요", exact = true)
            reveal("메일에 함께 적는 정보")
        }
    }
    // [SETUP-10] 맞았어요 said once, and the button out of the way at the end of the list.
    @Test fun foldedCompanyBoardEnd() = shoot("271b_folded_company_board_end", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        settle()
        reveal("예시정밀 2공장", exact = true)
        scrollUp(800)
        settle()
    }
    // [ONBOARD-05] 사용 통계 at the end of the first-start screen: unticked; only a tick turns it on (D42).
    @Test fun foldedRecommendedUsageStats() = shoot("526_folded_recommended_usage_stats", FOLDED,
        recommended(setOf(ConsentItem.CALENDAR)) {
            reveal("개발자에게 사용 통계 보내기 (선택)", exact = true)
            assertFalse(app.container.settingsRepository.current.shareUsageStats)
        })
    @Test fun recommendedUsageStatsOnlyWithATick() = shoot("527_folded_recommended_stats_then_setup", FOLDED,
        recommended(setOf(ConsentItem.CALENDAR)) {
            tapText("개발자에게 사용 통계 보내기 (선택)", exact = true)
            tapText("허용은 나중에 할게요")
            assertTrue("ticked, it is on", app.container.settingsRepository.current.shareUsageStats)
        })
    @Test fun foldedSettingsNoticesAndSleep() = shoot("529_folded_settings_notices_sleep", FOLDED) {
        settingsPage("알림과 잠"); reveal("휴식 점검", exact = true)
    }
    // [PAYR-06] 임금표: 보통인부 일당 next to the 대한건설협회 시중노임단가.
    @Test fun foldedPlazaRatesGov() = shoot("402_folded_plaza_rates_gov", FOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }
    @Test fun phonePlazaRatesGovHugeText() = shoot("403_phone_plaza_rates_gov_huge_text", PHONE, fontScale = 2f) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }
    @Test fun unfoldedPlazaRatesGov() = shoot("404_unfolded_plaza_rates_gov", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }

    // [NURS-06] A received roster read with the user's own rules.
    @Test fun foldedReceivedDutyCheck() = shoot("436_folded_received_duty_check", FOLDED) {
        receivedOn()
        // 민지's 주주휴휴야야휴휴 is quiet under 기본 and 더 엄격하게; a ward that wants 3 days off after nights sees it.
        app.container.settingsRepository.update { it.copy(restRules = com.bamnat.core.schedule.RestRules(offsAfterNights = 3).encode()) }
        settle()
        tapText("민지의 근무표 보기", exact = true)
        reveal("님 근무표 점검")
    }
}
