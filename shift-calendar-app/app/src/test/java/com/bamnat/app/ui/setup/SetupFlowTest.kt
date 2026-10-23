package com.bamnat.app.ui.setup

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.SetupDraftStore
import com.bamnat.app.ui.calendar.PaintTool
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.setup.CompanyTemplates
import com.bamnat.core.setup.FollowUp
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.setup.TodayQuestion
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

/**
 * [SETUP-01][SETUP-02][COMM-05] The first setup: one question at a time, nothing on the calendar until
 * the last step saves it all at once, and a save cut short finished on the next start. Companies here
 * are made up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SetupFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val now = LocalDateTime.of(2026, 10, 18, 10, 0)
    private val today = now.toLocalDate()
    private val draft: SetupDraft get() = c.setupDrafts.draft.value!!

    private fun vm(at: LocalDateTime = now) = SetupViewModel(c, now = { at })

    /** Waits for work the app does on its own threads (the save, Room). */
    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Before fun defaults() {
        // The app makes its first shifts on start; the setup always comes after.
        waitFor("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
    }

    /** 2교대 with 주주휴휴야야휴휴, today the second night. */
    private fun twoShiftToSummary(vm: SetupViewModel) {
        vm.pickStyle(WorkStyle.SHIFT)
        assertEquals(SetupStep.COMPANY, draft.step)
        vm.next()
        assertEquals(SetupStep.BUNDLE, draft.step)
        vm.pickBundle(ShiftBundles.TWO)
        assertEquals(SetupStep.TIMES, draft.step)
        vm.confirmTimes()
        assertEquals(SetupStep.ORDER, draft.step)
        vm.pickPreset(vm.presets(draft).first { it.codes == "DDOONNOO" })
        vm.next()
        assertEquals(SetupStep.TODAY, draft.step)
        val night = draft.types.first { it.name == "야간" }.id
        val follow = TodayQuestion.followUp(draft.rotation, night, draft::isOff) as FollowUp.RunDay
        vm.answerToday(today, follow.steps[1])
        vm.confirmToday()
        assertEquals(SetupStep.ALARM, draft.step)
        vm.next()
        assertEquals(SetupStep.SUMMARY, draft.step)
    }

    @Test fun nothingReachesTheCalendarUntilTheLastStepSavesItAll() = runBlocking {
        val vm = vm()
        twoShiftToSummary(vm)
        assertTrue(c.scheduleRepository.patterns.first().isEmpty())
        assertNull(c.settingsRepository.current.workStyle)

        vm.save()
        waitFor("the save") { c.settingsRepository.current.workStyle != null && c.setupDrafts.draft.value == null }
        assertEquals(WorkStyle.SHIFT, c.settingsRepository.current.workStyle)
        assertFalse(c.settingsRepository.current.rosterMonthly)
        val snap = c.scheduleRepository.snapshot.first()
        assertEquals(1, snap.patterns.size)
        val r = snap.resolver
        assertEquals("야간", r.resolve(today.minusDays(1)).shiftType?.name)
        assertEquals("야간", r.resolve(today).shiftType?.name)
        assertFalse(r.resolve(today.plusDays(1)).isWorkDay)
        assertEquals("주간", r.resolve(today.plusDays(3)).shiftType?.name)
        // The bundle's shifts took the place of the first ones, each once, with the hour-before alarm.
        assertEquals(1, snap.shiftTypes.count { it.name == "주간" })
        assertFalse(snap.shiftTypes.any { it.name == "오후" })
        val day = snap.shiftTypes.first { it.name == "주간" }
        assertTrue(day.alarmEnabled)
        assertEquals(listOf(60), day.alarms.map { it.offsetMinutes })
        // "예상 급여도 계산할까요?" is offered once.
        assertEquals(WorkStyle.SHIFT, c.firstSetup.saved.value?.style)
    }

    @Test fun aDraftLeftForLaterComesBackAsItWas() {
        val vm = vm()
        vm.pickStyle(WorkStyle.DAY)
        vm.setCompanyName("가나다화학")
        vm.later()
        c.setupDrafts.flush()
        val again = SetupDraftStore(File(app.noBackupFilesDir, "setup_draft.json")).draft.value!!
        assertEquals(WorkStyle.DAY, again.style)
        assertEquals("가나다화학", again.companyName)
        assertTrue(again.later)
        assertEquals(SetupStep.COMPANY, again.step)
        // [WORK-01] Weekday work starts Monday to Friday.
        assertEquals(5, again.weekly.count { it != null })
        assertNull(c.settingsRepository.current.workStyle)
    }

    @Test fun inTheSmallHoursTheQuestionIsAboutYesterday() {
        val vm = vm(LocalDateTime.of(2026, 10, 18, 3, 0))
        vm.pickStyle(WorkStyle.SHIFT)
        vm.next()
        vm.pickBundle(ShiftBundles.TWO)
        assertTrue(vm.earlyHours(draft))
        assertEquals(today.minusDays(1), vm.questionDate(draft))
        vm.answerForToday(true)
        assertEquals(today, vm.questionDate(draft))
        // An hour after the night shift ends it is about today again.
        val later = vm(LocalDateTime.of(2026, 10, 18, 8, 0))
        assertFalse(later.earlyHours(draft))
    }

    /** [SETUP-14] "잘 모르겠어요" on the today question: the shifts stay and the month is painted instead. */
    @Test fun notKnowingTodayGoesToPaintingWithTheShiftsKept() {
        val vm = vm()
        vm.pickStyle(WorkStyle.SHIFT)
        vm.next()
        vm.pickBundle(ShiftBundles.TWO)
        vm.confirmTimes()
        vm.pickPreset(vm.presets(draft).first { it.codes == "DDOONNOO" })
        vm.next()
        assertEquals(SetupStep.TODAY, draft.step)
        val types = draft.types
        vm.paintInstead()
        assertTrue(draft.rosterMonthly)
        assertEquals(SetupStep.ORDER, draft.step)
        assertEquals(types, draft.types)
        assertTrue(draft.rotation.isEmpty())
        assertNull(draft.anchor)
        assertFalse(SetupStep.TODAY in draft.steps)
    }

    @Test fun aMonthlyRosterIsPaintedAndSavedDayByDay() = runBlocking {
        val vm = vm()
        vm.pickStyle(WorkStyle.SHIFT, roster = true)
        vm.next()
        vm.pickBundle(ShiftBundles.HOSPITAL)
        vm.confirmTimes()
        assertEquals(SetupStep.ORDER, draft.step)
        val dayShift = draft.types.first { it.name == "데이" }
        val off = draft.types.first { it.isOff }
        vm.setPaintTool(PaintTool.Shift(dayShift.id))
        vm.paint(listOf(today, today.plusDays(1)))
        // The same again on one day takes it off.
        vm.paintTap(today.plusDays(1))
        assertEquals(setOf(today), draft.painted.keys)
        vm.setPaintTool(PaintTool.Shift(off.id))
        vm.paint(listOf(today.plusDays(2)))
        vm.undoPaint()
        assertEquals(setOf(today), draft.painted.keys)
        vm.paint(listOf(today.plusDays(2)))
        // No order and no today question for a roster.
        vm.next()
        assertEquals(SetupStep.ALARM, draft.step)
        vm.next()
        vm.save()
        waitFor("the save") { c.settingsRepository.current.workStyle != null && c.setupDrafts.draft.value == null }
        assertTrue(c.settingsRepository.current.rosterMonthly)
        val snap = c.scheduleRepository.snapshot.first()
        assertTrue(snap.patterns.isEmpty())
        assertEquals("데이", snap.resolver.resolve(today).shiftType?.name)
        assertEquals("오프", snap.resolver.resolve(today.plusDays(2)).shiftType?.name)
        assertNull(snap.resolver.resolve(today.plusDays(1)).shiftType)
    }

    @Test fun aTemplateStartsTheShiftsAndTheUsersOwnOrderIsStillAsked() = runBlocking {
        val vm = vm()
        vm.pickStyle(WorkStyle.SHIFT)
        vm.setCompanyName("예시정밀")
        vm.pickTemplate(CompanyTemplates.byId("two12")!!)
        assertEquals(SetupStep.TIMES, draft.step)
        assertEquals(SetupSource.Kind.TEMPLATE, draft.source?.kind)
        val names = draft.types.associate { it.id to it.name }
        assertEquals(listOf("주간", "주간", "야간", "야간", "휴무", "휴무", "휴무", "휴무"), draft.rotation.map { names[it] })
        vm.confirmTimes()
        // A template's order is only the usual one: shown, already chosen, to be confirmed.
        assertEquals(SetupStep.ORDER, draft.step)
        assertEquals(draft.rotation, vm.sourceOrder(draft))
        vm.pickPreset(vm.presets(draft).first { it.codes == "DDOONNOO" })
        vm.next()
        assertEquals(SetupStep.TODAY, draft.step)
        vm.answerToday(today, 0)
        vm.confirmToday()
        vm.next()
        vm.save()
        waitFor("the save") { c.settingsRepository.current.workStyle != null && c.setupDrafts.draft.value == null }
        assertEquals("주간", c.scheduleRepository.snapshot.first().resolver.resolve(today).shiftType?.name)
        // The company typed is kept to invite the user to share the setup later.
        assertEquals("예시정밀", c.companyStore.draft.value?.company)
        assertNull(c.companyStore.applied.value)
    }

    @Test fun aTemplateFileNamesNoCompanyNoAmountAndNoDate() {
        CompanyTemplates.all.forEach { t ->
            val root = JSONObject(c.companyShare.templateText(t))
            assertEquals("", root.getString("company"))
            assertFalse(t.id, root.has("pay"))
            assertFalse(t.id, root.optJSONObject("rotation")?.has("anchor") ?: false)
            val read = c.companyShare.read(root.toString())
            assertEquals(t.id, t.shifts.size, read.types.size)
            assertEquals(t.id, t.orderNames, read.order)
        }
    }

    @Test fun aCoworkersFileLinesUpWhenTheTeamIsTheSame() {
        val vm = vm()
        // 라마바전자's file from a coworker on A조, whose order started two days ago.
        val root = JSONObject(c.companyShare.templateText(CompanyTemplates.byId("two12")!!)).put("company", "라마바전자")
        root.getJSONObject("rotation").put("anchor", today.minusDays(2).toString()).put("team", "A조")
        vm.importCompany(root.toString())
        assertEquals(WorkStyle.SHIFT, draft.style)
        assertEquals(SetupStep.TIMES, draft.step)
        assertEquals("A조", draft.source?.team)
        vm.confirmTimes()
        assertEquals(SetupStep.ORDER, draft.step)
        vm.next()
        assertEquals(SetupStep.TODAY, draft.step)
        // Same 조: the coworker's start date as it is; today is the first night.
        vm.sameTeam()
        assertEquals(today.minusDays(2), draft.anchor)
        assertEquals("오늘(10/18)은 야간 첫째 날이에요. 모레부터 휴무 4일이에요.", todaySentence(draft, today, today))
        // Another 조: asked like any other order.
        vm.clearToday()
        vm.answerToday(today, 0)
        assertEquals(today, draft.anchor)
        vm.confirmToday()
        assertEquals(SetupStep.ALARM, draft.step)
    }

    @Test fun aCoworkersFileInSettingsStartsFromTheSendersRow() = runBlocking {
        // The same file opened from 설정 > 데이터 once the app is set up: today's row is asked next.
        val root = JSONObject(c.companyShare.templateText(CompanyTemplates.byId("two12")!!)).put("company", "라마바전자")
        root.getJSONObject("rotation").put("anchor", today.minusDays(2).toString()).put("team", "A조")
        val text = root.toString()
        assertEquals("A조", c.companyShare.summarize(text).team)
        val rows = com.bamnat.app.ui.company.rotationPhases(c, text, fromSender = true, today = today)!!
        assertEquals(8, rows.cycle)
        assertEquals(8, rows.options.size)
        // A조 works the first 야간 today, two days into the order.
        assertEquals(2, rows.sent)
        assertEquals("A조", rows.team)
        assertEquals(listOf("야간", "야간", "휴무", "휴무"), rows.options.single { it.step == rows.sent }.days.take(4).map { it?.name })
        // A company page's setup has no sender to start from.
        val page = com.bamnat.app.ui.company.rotationPhases(c, text, fromSender = false, today = today)!!
        assertNull(page.sent)
        assertNull(page.team)
    }

    @Test fun aSaveCutShortIsFinishedOnceOnTheNextStart() = runBlocking {
        val vm = vm()
        twoShiftToSummary(vm)
        val d = draft.copy(committing = true)
        // The calendar part was written before the app was closed; the kind of work was not.
        c.scheduleRepository.applySetup(d, today)
        c.setupDrafts.save(d)
        c.setupDrafts.flush()
        c.firstSetup.finishInterrupted()
        assertEquals(WorkStyle.SHIFT, c.settingsRepository.current.workStyle)
        assertNull(c.setupDrafts.draft.value)
        val snap = c.scheduleRepository.snapshot.first()
        assertEquals(1, snap.patterns.size)
        assertEquals(1, snap.shiftTypes.count { it.name == "야간" })
        assertEquals("야간", snap.resolver.resolve(today).shiftType?.name)
    }

    @Test fun fixingAnAnswerGoesBackToTheSummary() {
        val vm = vm()
        twoShiftToSummary(vm)
        vm.fix(SetupStep.ALARM)
        assertEquals(SetupStep.ALARM, draft.step)
        vm.setAlarm(30)
        vm.next()
        assertEquals(SetupStep.SUMMARY, draft.step)
        // A new order needs today again before the summary.
        vm.fix(SetupStep.ORDER)
        vm.pickPreset(vm.presets(draft).first { it.codes == "DDNNOOOO" })
        vm.next()
        assertEquals(SetupStep.TODAY, draft.step)
        vm.answerToday(today, 0)
        vm.confirmToday()
        assertEquals(SetupStep.SUMMARY, draft.step)
        // The same kind of work again while fixing changes nothing.
        vm.fix(SetupStep.STYLE)
        vm.pickStyle(WorkStyle.SHIFT)
        assertEquals(SetupStep.SUMMARY, draft.step)
        assertEquals(30, draft.alarmMinutes)
    }
}
