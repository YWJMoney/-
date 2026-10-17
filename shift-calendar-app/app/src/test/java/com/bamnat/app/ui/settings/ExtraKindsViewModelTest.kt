package com.bamnat.app.ui.settings

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.payText
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
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
