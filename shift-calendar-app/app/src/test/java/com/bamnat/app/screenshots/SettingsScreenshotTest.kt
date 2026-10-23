package com.bamnat.app.screenshots

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.ui.diag.DiagnosticsScreen
import com.bamnat.app.ui.diag.DiagnosticsViewModel
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.consent.ConsentItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind

/** [QA-05] Screenshots: 설정·첫 설정·추천 설정·도움말·프로·알람 점검·백업. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsScreenshotTest : ScreenshotBase() {
    @Test fun foldedSettings() = shoot("4_folded_settings", FOLDED) { tab(4, wide = false) }
    @Test fun unfoldedSettings() = shoot("8_unfolded_settings", UNFOLDED) { tab(4, wide = true) }

    @Test fun foldedDiagnostics() = shoot("22_folded_diagnostics", FOLDED) {
        val c = app.container
        val now = System.currentTimeMillis()
        c.telemetry.event(DiagEvents.APP_OPEN)
        c.telemetry.event(DiagEvents.ALARM_FIRED, EventBook.triggerDetail(now - 3_600_000, "late_s=2"))
        c.telemetry.event(DiagEvents.ALARM_DISMISSED)
        c.telemetry.event(DiagEvents.SCREEN, "stats")
        val vm = DiagnosticsViewModel(c)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }
    // [REL-13] 알람 점검: the checks with their fix buttons and a test alarm waiting.
    @Test fun foldedAlarmChecks() = shoot("350_folded_alarm_checks", FOLDED) {
        val c = app.container
        c.alarmScheduler.scheduleTest()
        val vm = DiagnosticsViewModel(c)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }
    @Test fun unfoldedAlarmChecks() = shoot("351_unfolded_alarm_checks", UNFOLDED) {
        val vm = DiagnosticsViewModel(app.container)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }
    // [OPS-23] 설정 › 앱 정보 › 오픈소스 라이선스.
    @Test fun foldedOpenSourceLicenses() = shoot("352_folded_open_source_licenses", FOLDED) {
        content { com.bamnat.app.ui.settings.OpenSourceLicensesDialog(onDismiss = {}) }
    }

    // [WORK-01] First launch: how do you work?
    @Test fun foldedWorkStylePicker() = shoot("49_folded_work_style", FOLDED) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    @Test fun unfoldedWorkStylePicker() = shoot("50_unfolded_work_style", UNFOLDED) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    // [SETUP-01][SETUP-02] A new install: no pattern, no work style. One question a screen until the summary.
    @Test fun firstLaunchSetupStyle() = shoot("61_folded_setup_style", FOLDED, fresh(null))
    @Test fun firstLaunchSetupTimes() = shoot("61c_folded_setup_times", FOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간")
    })
    @Test fun firstLaunchSetupOrder() = shoot("61d_folded_setup_order", FOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간"); tapText("네, 시간이 맞아요")
    })
    @Test fun firstLaunchSetupToday() = shoot("61e_folded_setup_today", FOLDED, fresh(null) { setupToToday() })
    @Test fun unfoldedSetupToday() = shoot("61f_unfolded_setup_today", UNFOLDED, fresh(null) { setupToToday() })
    @Test fun firstLaunchSetupSummary() = shoot("61g_folded_setup_summary", FOLDED, fresh(null) {
        setupToToday(); tapText("회사 근무표와 같아요"); tapText("다음", exact = true)
    })
    @Test fun firstLaunchSetupRoster() = shoot("61h_folded_setup_roster", FOLDED, fresh(null) {
        tapText("근무표가 매달 나와요"); tapText("건너뛰고 직접 정할게요"); tapText("병원 3교대"); tapText("네, 시간이 맞아요")
        tapText("데이 칠하기")
    })
    @Test fun foldedBackupPassword() = shoot("82_folded_backup_password", FOLDED) {
        content { com.bamnat.app.ui.settings.ExportPasswordDialog(onDismiss = {}, onExport = {}) }
        settle()
    }
    // ---- M6 [SLP-08][QA-01] ----
    @Test fun foldedSettingsHealthSleep() = shoot("89_folded_settings_health_sleep", FOLDED) {
        settingsPage("알림과 잠")
        reveal("워치·휴대폰 수면 기록 함께 보기", exact = true)
    }
    @Test fun foldedNewCalendarSetup() = shoot("217_folded_new_calendar_setup", FOLDED) {
        val c = app.container
        val id = c.calendars.begin("편의점 알바")
        val vm = com.bamnat.app.ui.setup.SetupViewModel(c, calendarId = id)
        content { com.bamnat.app.ui.setup.SetupHost(vm) }
    }
    // ---- M10 [SET-01][SLP-15] Settings in groups ----
    @Test fun foldedSettingsAlarm() = shoot("140_folded_settings_alarm", FOLDED) { settingsPage("알람") }
    @Test fun foldedSettingsRest() = shoot("142_folded_settings_rest", FOLDED) { settingsPage("알림과 잠"); reveal("오늘의 흐름 보기", exact = true) }
    @Test fun phoneSettingsHugeText() = shoot("166_phone_settings_huge_text", PHONE, fontScale = 2f) { tab(4, wide = false) }
    @Test fun foldedSettingsDataUndo() = shoot("144_folded_settings_data_undo", FOLDED) {
        runBlocking { app.container.importUndo.save() }
        settingsPage("데이터")
    }

    @Test fun phoneCustomize() = shoot("355_phone_customize", PHONE) { customize() }
    @Test fun smallPhoneCustomize() = shoot("356_small_phone_customize", SMALL_PHONE) { customize() }
    @Test fun phoneWhatsNew() = shoot("361_phone_whats_new", PHONE) { whatsNew() }
    @Test fun smallPhoneWhatsNew() = shoot("362_small_phone_whats_new", SMALL_PHONE) { whatsNew() }
    @Test fun phoneStatsCustomize() = shoot("363_phone_stats_customize", PHONE) {
        tab(3, wide = false)
        tapText("화면 꾸미기", exact = true, scrolls = 0)
    }
    @Test fun smallPhoneSettings() = shoot("365_small_phone_settings", SMALL_PHONE) { tab(4, wide = false) }
    @Test fun phoneLockCover() = shoot("373_phone_lock_cover", PHONE) { content { com.bamnat.app.ui.LockCover {} } }
    @Test fun phonePrivacyLock() = shoot("374_phone_settings_privacy_lock", PHONE) { settingsPage("개인정보·앱 정보") }

    @Test fun phoneProBeforeLaunch() = shoot("375_phone_pro_before_launch", PHONE, pro())
    @Test fun phoneProLaunched() = shoot("376_phone_pro_launched", PHONE, launched(then = pro()))
    @Test fun phoneProPlans() = shoot("377_phone_pro_plans", PHONE, launched { pro().invoke(this); reveal("구매 복원", exact = true) })
    @Test fun unfoldedProLaunched() = shoot("378_unfolded_pro_launched", UNFOLDED, launched(then = pro()))
    @Test fun phoneTrialQuestion() = shoot("380_phone_trial_question", PHONE, launched {
        val start = java.time.Instant.now().minus(java.time.Duration.ofDays(26))
        app.container.proStore.setSub(com.bamnat.app.tier.SubRecord("bamnat_pro", "monthly", com.bamnat.core.tier.PlaySub.State.ACTIVE,
            java.time.Instant.now().plus(java.time.Duration.ofDays(3)), trial = true, autoRenew = true, purchasedAt = start, verified = false,
            checkedAt = java.time.Instant.now()))
    })
    @Test fun phoneLaunchThanks() = shoot("381_phone_launch_thanks", PHONE, launched(com.bamnat.app.tier.LaunchConfig(billingLive = true,
        launchedAt = java.time.Instant.now().minus(java.time.Duration.ofDays(1)), thanksMonths = 3)))
    @Test fun phoneAdPrivacy() = shoot("382_phone_ad_privacy", PHONE) { settingsPage("개인정보·앱 정보"); tapText("광고 개인정보 선택", exact = true) }
    @Test fun phoneProLargeText() = shoot("384_phone_pro_large_text", PHONE, fontScale = 1.5f, launched(then = pro()))
    @Test fun phoneSettingsProRow() = shoot("383_phone_settings_pro_row", PHONE, launched { tab(4, wide = false); reveal("기능 켜고 끄기", exact = true) })
    @Test fun phoneDataAutoBackup() = shoot("392_phone_data_auto_backup", PHONE) {
        val c = app.container
        c.keyBox = com.bamnat.app.backup.AutoBackupTest.FakeKeyBox()
        val file = java.io.File(app.filesDir, "drive/${com.bamnat.app.backup.AutoBackup.FILE_NAME}").apply { parentFile!!.mkdirs(); delete() }
        runBlocking { c.autoBackup.turnOn(android.net.Uri.fromFile(file), "밤낮1234".toCharArray()) }
        settingsPage("데이터"); reveal("매일 자동 백업", exact = true)
    }
    @Test fun phoneDataPhoneCalendar() = shoot("398_phone_data_phone_calendar", PHONE) {
        settingsPage("데이터"); reveal("휴대폰 캘린더에 근무 쓰기", exact = true)
    }

    @Test fun phoneSureAlarm() = shoot("393_phone_sure_alarm", PHONE) {
        val c = app.container
        val night = runBlocking { c.scheduleRepository.shiftTypes.first() }.firstOrNull { it.name.contains("야") }?.id
        c.settingsRepository.update { it.copy(alarmMission = "math", wakeCheckMinutes = 5,
            shiftMissions = if (night != null) mapOf(night to "shake") else emptyMap()) }
        settingsPage("알람"); reveal("근무마다 다르게", exact = true); reveal("묻는 때", exact = false)
    }
    @Test fun phoneMissionMath() = shoot("394_phone_mission_math", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofSeconds(40)) }
    @Test fun phoneMissionShake() = shoot("395_phone_mission_shake", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.SHAKE, java.time.Duration.ofMinutes(2)) }
    @Test fun phoneMissionSkip() = shoot("396_phone_mission_skip", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofMinutes(6)) }
    @Test fun smallPhoneMissionMath() = shoot("397_small_phone_mission_math", SMALL_PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofSeconds(40)) }

    @Test fun foldedSettingsHubHelp() {
        inquiriesOn(asked = true)
        shoot("327_folded_settings_hub", FOLDED) { tab(4, wide = false) }
    }
    @Test fun foldedSettingsSearch() = shoot("328_folded_settings_search", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람 소리")
    }
    @Test fun foldedSettingsSearchFound() = shoot("329_folded_settings_search_found", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람 소리")
        tapText("알람음", exact = true)
    }
    @Test fun unfoldedSettingsSearchFound() = shoot("330_unfolded_settings_search_found", UNFOLDED) {
        tab(4, wide = true)
        typeInto("설정 찾기", "다크 모드")
        tapText("테마", exact = true)
    }
    @Test fun phoneSettingsSearchHugeText() = shoot("331_phone_settings_search_huge_text", PHONE, fontScale = 2f) {
        tab(4, wide = false)
        typeInto("설정 찾기", "백업")
    }
    @Test fun foldedSettingsSearchNothing() = shoot("333_folded_settings_search_nothing", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "화면이 자꾸 꺼져요")
    }

    @Test fun foldedHelpHome() {
        inquiriesOn()
        shoot("334_folded_help_home", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
        }
    }
    @Test fun foldedHelpSearch() {
        inquiriesOn()
        shoot("335_folded_help_search", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            typeInto("기능과 도움말 찾기", "알람이 안 울려요")
            tapText("알람이 안 울리거나 늦게 울려요", exact = true)
        }
    }
    @Test fun foldedHelpFromCalendar() {
        inquiriesOn()
        shoot("336_folded_help_from_calendar", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "달력", help = true))
        }
    }
    @Test fun unfoldedHelpHome() {
        inquiriesOn()
        shoot("339_unfolded_help_home", UNFOLDED) {
            tab(4, wide = true)
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        }
    }
    @Test fun phoneHelpHugeText() {
        inquiriesOn()
        shoot("340_phone_help_huge_text", PHONE, fontScale = 2f) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        }
    }
    @Test fun foldedHelpDark() {
        inquiriesOn()
        shoot("341_folded_help_dark", "w369dp-h905dp-port-night-420dpi") {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
            tapText("근무표를 동료·가족에게 보내고 싶어요", exact = true)
        }
    }
    @Test fun foldedHelpByMail() {
        inquiriesOn(enabled = false)
        shoot("342_folded_help_mail", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            reveal("메일로 문의하기")
        }
    }
    @Test fun foldedSettingsSearchWithHelp() = shoot("346_folded_settings_search_help", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람이 안 울려요")
    }
    @Test fun phoneSettingsHubNewAnswerHugeText() {
        inquiriesOn(asked = true)
        shoot("347_phone_settings_new_answer_huge_text", PHONE, fontScale = 2f) { tab(4, wide = false) }
    }
    @Test fun foldedSetupStepsLeft() = shoot("279_folded_setup_steps_left", FOLDED, fresh(null) { tapText("순서가 돌아가요") })
    @Test fun unfoldedSetupStepsLeft() = shoot("280_unfolded_setup_steps_left", UNFOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간")
    })
    // [SETUP-12] A first setup left for later, picked up again from 설정 > 내 근무.
    @Test fun foldedSettingsSetupResume() = shoot("280b_folded_settings_setup_resume", FOLDED, fresh(null) {
        tapText("순서가 돌아가요")
        tapText("나중에 할게요", exact = true)
        settingsPage("내 근무")
    })

    @Test fun e487FoldedMemoCustomize() = shoot("487_folded_memo_customize", FOLDED) {
        tab(2, wide = false)
        tapText("화면 꾸미기", exact = true, scrolls = 0)
    }
    /** [BACK-01] 사진도 백업 in 설정 › 데이터, and the 암호 dialog saying how many photos go in. */
    @Test fun e494FoldedSettingsBackupPhotos() = shoot("494_folded_settings_backup_photos", FOLDED) {
        settingsPage("데이터")
        reveal("사진도 백업", exact = true)
    }
    @Test fun e495FoldedBackupPasswordPhotos() = shoot("495_folded_backup_password_photos", FOLDED) {
        com.bamnat.app.ui.money.ReceiptFiles.dir(app).apply { mkdirs() }.resolve("shot_receipt.jpg").writeBytes(ByteArray(420_000))
        runBlocking {
            app.container.moneyRepository.save(com.bamnat.core.money.MoneyEntry(date = today, kind = com.bamnat.core.money.MoneyKind.EXPENSE,
                amount = 14_000, category = "식비", merchant = "김밥천국", receipt = "shot_receipt.jpg"))
        }
        content { com.bamnat.app.ui.settings.ExportPasswordDialog(onDismiss = {}, onExport = {}) }
        settle()
    }

    @Test fun foldedRecommended() = shoot("520_folded_recommended", FOLDED, run {
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        recommended()
    })
    @Test fun unfoldedRecommended() = shoot("521_unfolded_recommended", UNFOLDED, recommended())
    @Test fun phoneRecommendedHugeText() = shoot("522_phone_recommended_huge_text", PHONE, fontScale = 2f, recommended())
    @Test fun recommendedLaterLeadsToTheFirstSetupWithFeaturesOn() = shoot("523_folded_recommended_then_setup", FOLDED,
        recommended(setOf(ConsentItem.CALENDAR)) {
            tapText("허용은 나중에 할게요")
            // The first setup comes next, the screen does not come back, and the recommended features are on.
            tapText("순서가 돌아가요")
            val c = app.container
            assertTrue(c.consentStore.seen.value!!.contains(ConsentItem.CALENDAR))
            assertTrue(c.consentStore.recommendOffered)
            assertEquals(com.bamnat.core.consent.RecommendedPlan.NEW_INSTALL, com.bamnat.app.data.RecommendedSettings.on(c.settingsRepository.current))
        })
    @Test fun foldedRecommendedFromSettings() = shoot("524_folded_recommended_settings", FOLDED) {
        // A phone set up before keeps its own values: two on, two off.
        app.container.settingsRepository.update { it.copy(briefing = true, todayShiftNotice = true, bedtimeNotice = false, commuteNotice = false) }
        com.bamnat.app.ui.settings.SettingsLinks.go(com.bamnat.app.ui.settings.SettingsGo.RECOMMENDED)
        settle()
        tapText("퇴근길 안전")
        tapText("이대로 저장")
        val on = com.bamnat.app.data.RecommendedSettings.on(app.container.settingsRepository.current)
        assertTrue("the switch tapped is saved", com.bamnat.core.consent.RecommendedFeature.COMMUTE in on)
        assertTrue("the one left off stays off", com.bamnat.core.consent.RecommendedFeature.BEDTIME !in on)
    }
    @Test fun phoneWhatsNewRecommends() = shoot("525_phone_whats_new_recommend", PHONE) {
        content { com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases.take(1), {}, {}, {}, recommendOff = 2) }
    }

    // [SET-02] The settings list in seven groups, and the two that took in another.
    @Test fun phoneSettingsSevenGroups() = shoot("528_phone_settings_seven_groups", PHONE) {
        tab(4, wide = false)
        reveal("도움말과 문의", exact = true)
    }
    @Test fun foldedSettingsPrivacyAbout() = shoot("530_folded_settings_privacy_about", FOLDED) {
        settingsPage("개인정보·앱 정보"); reveal("오픈소스 라이선스", exact = true)
    }
    // [SIMPLE-03] The 1.22.0 notice, eight lines grouped by what they give.
    @Test fun phoneWhatsNewShort() = shoot("531_phone_whats_new_1_22", PHONE) {
        content { com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases.take(1), {}, {}, {}) }
    }
}
