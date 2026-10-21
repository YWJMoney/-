package com.bamnat.app.ui.settings

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.calendar.ExtraText
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.payText
import com.bamnat.app.ui.pay.scopeText
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.OffDayTreatment
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** [EXTRA-01][EXTRA-03][EXTRA-04] A kind and how it is paid are saved in one step, and one tap undoes both. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExtraKindsViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val scope = MainScope()
    private val messages = mutableListOf<UndoMessage>()
    private lateinit var vm: ExtraKindsViewModel

    /** 대근 1회 기본급의 10% for 8 hours, the 연장·야간 가산 on top: the company way from the thread. */
    private val tenPercent = ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = 0.1, coverMinutes = 480, legalPremiums = true)

    /** Lets the ViewModel's coroutines and the stores' background writes finish. */
    private fun settle() = repeat(20) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before fun setUp() = runBlocking {
        c.scheduleRepository.ensureDefaults()
        c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false)
        vm = ExtraKindsViewModel(c)
        scope.launch { vm.messages.collect { messages += it } }
        settle()
    }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { c.payStore.save(null, keepUndo = false) }
    }

    private fun rules() = c.payStore.profile.value!!.rules
    private fun kinds() = c.settingsRepository.settings.value.extraKinds

    private fun undoLast() {
        vm.runUndo(messages.last())
        settle()
    }

    @Test fun aNewKindIsSavedWithItsPayAndOneUndoTakesBothBack() {
        vm.openNew(KindTemplate("조출", "근무 시작 전 1시간", ExtraKind(0, "조출 ", " 조", 0xFFE8900C, ExtraTiming.BeforeShift(60))))
        vm.edit { it.copy(rule = ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 20_000)) }
        vm.saveEditing()
        settle()

        val kind = kinds().kinds.single { it.name == "조출" }
        assertEquals("조", kind.short)
        assertTrue(kind.id >= ExtraKind.FIRST_CUSTOM_ID)
        assertEquals(ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 20_000), rules().extra[kind.id])
        assertNull(vm.editing.value)
        assertEquals("‘조출’을 추가했어요", messages.single().message)

        undoLast()
        assertTrue(kinds().kinds.none { it.name == "조출" })
        assertNull(rules().extra[kind.id])
    }

    /** By law with nothing on top is no rule at all, so the kind falls back to the law and per-shift law entries go too. */
    @Test fun choosingTheLawRemovesTheCompanyRule() {
        runBlocking { c.payStore.save(c.payStore.profile.value!!.copy(rules = CompanyRules(extra = mapOf(ExtraKind.COVER_ID to tenPercent))), keepUndo = false) }
        settle()
        val night = runBlocking { c.scheduleRepository.shiftTypes.first().first { it.name == "야간" }.id }

        vm.open(kinds().byId(ExtraKind.COVER_ID)!!)
        assertEquals(tenPercent, vm.editing.value!!.draft.rule)
        vm.edit { it.copy(rule = ExtraRule(), byShift = mapOf(night to ExtraRule())) }
        vm.saveEditing()
        settle()

        assertFalse(ExtraKind.COVER_ID in rules().extra)
        assertFalse(ExtraKind.COVER_ID in rules().extraByShift)
        assertEquals("법대로", rules().payText(ExtraKind.COVER_ID))
        assertEquals("‘대근’을 저장했어요", messages.single().message)

        undoLast()
        assertEquals(tenPercent, rules().extra[ExtraKind.COVER_ID])
    }

    /** [MCAL-06] Picked from an added calendar, the kind is priced in that calendar's pay; the main pay stays. */
    @Test fun anAddedCalendarsPayIsTheOneEdited() {
        val id = c.calendars.begin("투잡").also { c.calendars.finishSetup(it, com.bamnat.core.work.WorkStyle.SHIFT) }
        val pay = c.calendars.pay(id)
        runBlocking { pay.save(PayViewModel(c).sampleProfile(), keepUndo = false) }
        val other = ExtraKindsViewModel(c, id)
        val otherMessages = mutableListOf<UndoMessage>()
        scope.launch { other.messages.collect { otherMessages += it } }
        settle()
        val mainBefore = rules()
        val ruleBefore = pay.profile.value!!.rules.extra[ExtraKind.COVER_ID]

        other.open(kinds().byId(ExtraKind.COVER_ID)!!)
        other.edit { it.copy(rule = tenPercent) }
        other.saveEditing()
        settle()

        assertEquals(tenPercent, pay.profile.value!!.rules.extra[ExtraKind.COVER_ID])
        assertEquals(mainBefore, rules())
        other.runUndo(otherMessages.last())
        settle()
        assertEquals(ruleBefore, pay.profile.value!!.rules.extra[ExtraKind.COVER_ID])
        c.calendars.delete(id)
    }

    @Test fun nothingChangedSavesNothing() {
        vm.open(kinds().byId(ExtraKind.OVERTIME_ID)!!)
        assertFalse(vm.editing.value!!.canSave)
        vm.saveEditing()
        settle()
        assertTrue(messages.isEmpty())
    }

    /** A custom kind goes with its rules and the 공휴일 setting that pointed at it; undo brings all of it back. */
    @Test fun deletingAKindTakesItsRulesAndHolidaySetting() {
        vm.openNew(KindTemplate("야간 대근", "", ExtraKind(0, "야간 대근", "야대", 0xFF7048E8,
            ExtraTiming.Band(LocalTime.of(19, 0), LocalTime.of(7, 0), 60))))
        vm.edit { it.copy(rule = tenPercent) }
        vm.saveEditing()
        settle()
        val kind = kinds().kinds.single { it.name == "야간 대근" }
        runBlocking { c.payStore.save(c.payStore.profile.value!!.let { it.copy(rules = it.rules.copy(publicHolidayAs = kind.id)) }, keepUndo = false) }
        settle()

        vm.open(kind)
        vm.deleteEditing()
        settle()
        assertTrue(kinds().kinds.none { it.id == kind.id })
        assertNull(rules().extra[kind.id])
        assertNull(rules().publicHolidayAs)
        assertEquals("‘야간 대근’을 지웠어요", messages.last().message)

        undoLast()
        assertNotNull(kinds().kinds.singleOrNull { it.id == kind.id })
        assertEquals(tenPercent, rules().extra[kind.id])
        assertEquals(kind.id, rules().publicHolidayAs)
    }

    @Test fun builtInKindsCannotBeDeleted() {
        vm.open(kinds().byId(ExtraKind.COVER_ID)!!)
        vm.deleteEditing()
        settle()
        assertNotNull(kinds().byId(ExtraKind.COVER_ID))
        assertTrue(messages.isEmpty())
    }

    /** [EXTRA-04] One 대근 on the next day off of the roster, priced the company's way and by law. */
    @Test fun previewPricesOnePieceAgainstTheLaw() = runBlocking {
        val repo = c.scheduleRepository
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = types.getValue("주간").id
        val off = types.getValue("휴무").id
        val start = LocalDate.of(2026, 10, 1)
        repo.savePattern(WorkPattern.Rotation(start, listOf(day, day, off, off)))
        settle()
        val snapshot = repo.snapshot.first()
        val profile = c.payStore.profile.value!!
        val cover = kinds().byId(ExtraKind.COVER_ID)!!
        val today = LocalDate.of(2026, 10, 17)

        val p = assertNotNull(vm.preview(KindDraft(cover, tenPercent), kinds(), profile, snapshot, today, shiftId = day))
        assertEquals("쉬는 날", p.day)
        assertEquals(off, snapshot.resolver.resolve(p.date).shiftType?.id)
        assertFalse(p.date.isBefore(today))
        assertTrue(p.piece, p.piece.startsWith("주간 대근"))
        val legal = assertNotNull(p.legal)
        assertTrue(p.amount > 0 && legal > 0)
        assertNotEquals(p.amount, legal)
        assertTrue(p.lines.isNotEmpty())

        // By law there is nothing to compare with.
        assertNull(vm.preview(KindDraft(cover), kinds(), profile, snapshot, today, shiftId = day)!!.legal)
    }

    @Test fun payTextCountsRulesOfTheirOwnPerShift() {
        val rules = CompanyRules(
            extra = mapOf(ExtraKind.COVER_ID to tenPercent),
            extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(7L to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 100_000), 8L to ExtraRule())),
        )
        assertEquals("${tenPercent.describe()} · 근무별 1개", rules.payText(ExtraKind.COVER_ID))
        assertEquals("법대로", rules.payText(ExtraKind.OVERTIME_ID))
    }

    /** [EXTRA-07] One 대근 for 주간 and 야간: a day offers each but the shift it works, and turning a day off into one picks it. */
    @Test fun aCoverForSeveralShiftsOffersEachOfThem() = runBlocking {
        val repo = c.scheduleRepository
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = types.getValue("주간")
        val evening = types.getValue("오후")
        val night = types.getValue("야간")
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 10, 1), listOf(day.id, day.id, off, off)))
        settle()
        val both = ExtraKind(101, "교대 대근", "교", 0xFF123456, ExtraTiming.AsShift(setOf(day.id, night.id)))
        val snapshot = repo.snapshot.first().let { it.copy(kinds = it.kinds.put(both)) }
        val all = snapshot.shiftTypes
        assertEquals("주간·야간 근무 시간 그대로", timingText(both, snapshot.typesById))

        // A day off offers both shifts of the kind, the plain 대근 every shift.
        val rest = snapshot.resolver.resolve(LocalDate.of(2026, 10, 3))
        val restChoices = ExtraText.choices(snapshot.kinds, all, rest)
        assertEquals(setOf(day.id, night.id), restChoices.filter { it.kind.id == 101L }.map { it.entry.coveredShiftId }.toSet())
        assertEquals(3, restChoices.count { it.kind.id == ExtraKind.COVER_ID })
        // A 주간 day offers only the other one.
        val work = snapshot.resolver.resolve(LocalDate.of(2026, 10, 1))
        assertEquals(listOf(night.id), ExtraText.choices(snapshot.kinds, all, work).filter { it.kind.id == 101L }.map { it.entry.coveredShiftId })

        // A day off turned into 야간 is a 대근 of the kind made for the fewest shifts with it.
        assertEquals(101L, ExtraText.coverKind(snapshot.kinds, all, night).id)
        assertEquals(ExtraKind.COVER_ID, ExtraText.coverKind(snapshot.kinds, all, evening).id)
        val nightOnly = ExtraKind(102, "야간 대근", "야대", 0xFF654321, ExtraTiming.AsShift(night.id))
        assertEquals(102L, ExtraText.coverKind(snapshot.kinds.put(nightOnly), all, night).id)
        // Its shifts deleted, the kind stands for every shift again.
        val gone = both.copy(timing = ExtraTiming.AsShift(setOf(990L, 991L)))
        assertEquals(3, ExtraText.choices(snapshot.kinds.put(gone), all, rest).count { it.kind.id == 101L })

        // The preview takes the shift asked for when the kind covers it, else its first.
        val profile = c.payStore.profile.value!!
        val today = LocalDate.of(2026, 10, 17)
        assertTrue(vm.preview(KindDraft(both), snapshot.kinds, profile, snapshot, today, shiftId = night.id)!!.piece.startsWith("야간"))
        assertTrue(vm.preview(KindDraft(both), snapshot.kinds, profile, snapshot, today, shiftId = evening.id)!!.piece.startsWith("주간"))
    }

    /** [EXTRA-08] The boxes open as the law pays: 연장·야간·휴일 ticked, 휴무일 only for a day off paid as a holiday. */
    @Test fun theBoxesOpenAsTheLawPays() {
        val profile = c.payStore.profile.value!!
        val cover = kinds().byId(ExtraKind.COVER_ID)!!
        val today = LocalDate.of(2026, 10, 17)
        val law = lawStart(cover, profile, today)
        assertEquals(ExtraBasis.UNIT, law.basis)
        assertEquals(listOf(true, true, true, false), Premium.BUILT_IN.map { law.premium(it)!!.on })
        assertTrue(lawStart(cover.copy(offDay = OffDayTreatment.HOLIDAY), profile, today).premium(Premium.REST)!!.on)
        // Under 5 employees nothing is ticked, each box ready at 50%.
        val small = lawStart(cover, profile.copy(fiveOrMoreEmployees = false), today)
        assertTrue(small.ticked.isEmpty())
        assertEquals(0.5, small.premium(Premium.OVERTIME)!!.rate, 1e-9)

        // The way from the thread, saved and read back in a line.
        val company = law.copy(unit = PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480,
            premiums = law.premiums.map { if (it.key == Premium.OVERTIME) it.copy(scope = PremiumScope.Over(480)) else it })
        assertEquals("8시간에 기본급의 10% + 연장 50% + 야간 50% + 휴일 50%",
            CompanyRules(extra = mapOf(ExtraKind.COVER_ID to company)).payText(ExtraKind.COVER_ID))
        assertEquals("이 일 8시간 넘는 시간", scopeText(PremiumScope.Over(480), "22~06시"))
        assertEquals("19~23시", scopeText(PremiumScope.Window(LocalTime.of(19, 0), LocalTime.of(23, 0)), "22~06시"))
    }

    @Test fun timingReadsLikeTheClock() {
        val night = ExtraKind(101, "야간 대근", colorArgb = 0, timing = ExtraTiming.Band(LocalTime.of(19, 0), LocalTime.of(7, 0), 60))
        val text = timingText(night, emptyMap())
        assertTrue(text, text.contains("(다음날)"))
        assertTrue(text, text.endsWith("휴게 1시간 · 11시간"))
        assertEquals("근무 끝나고 2시간", timingText(ExtraKind(1, "잔업", colorArgb = 0, timing = ExtraTiming.AfterShift(120)), emptyMap()))
    }
}

private fun <T : Any> assertNotNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}
