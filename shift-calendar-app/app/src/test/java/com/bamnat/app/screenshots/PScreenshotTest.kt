package com.bamnat.app.screenshots

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.imports.DutyRosterViewModel
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ROST-01][ROST-07][ROST-08][SETUP-15] 묶음 P's screenshots, numbers 720~739 (1.24 순번 341~346): 한 달 근무 넣기 on every
 * size the plan asks for (320·360·393·가로·글씨 200%·다크), a pasted roster read onto the calendar with its doubtful
 * cells and notices, the leave question, the quick-input bar's 이번 달 전부 넣기 and 반차, the keypad sheet's 이 순서 반복,
 * and the first setup's 듀티표가 있어요.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PScreenshotTest : ScreenshotBase() {
    private val c get() = app.container

    private companion object {
        const val PHONE_393 = "w393dp-h873dp-port-440dpi"
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
        /** A ward roster as Google Lens copies it: a title with the month, marks, a joined code, 반차 and 병가. */
        val ROSTER = """
            2026년 11월 근무표
            이름 ${(1..30).joinToString(" ")}
            김민지RN 주* 주 야 야 휴 휴 주 반차 야 N-O 휴 주 주 야 야 휴 휴 주 주 병가 야 휴 휴 주 주 야 야 휴 휴 주
            박수진 주 야 야 휴 휴 주 주 야 야 휴 휴 주 주 야 야 휴 휴 주 주 야 야 휴 휴 주 주 야 야 휴 휴 주 주
        """.trimIndent()
    }

    private fun vm() = DutyRosterViewModel(c, PersonalCalendar.MAIN)

    private fun ids() = runBlocking { c.scheduleRepository.shiftTypes.first() }.associate { it.name to DutyCell.Shift(it.id) }

    /** The screen, with a week typed from the 1st when [typed]. */
    private fun monthInput(typed: Boolean = false, then: Screen.(DutyRosterViewModel) -> Unit = {}): Screen.() -> Unit = style(WorkStyle.SHIFT) {
        val vm = vm()
        content { com.bamnat.app.ui.imports.DutyRosterScreen(vm, onBack = {}) }
        settle()
        if (typed) {
            val t = ids()
            vm.tap(1)
            listOf("주간", "주간", "야간", "야간", "휴무", "휴무").forEach { vm.put(t.getValue(it)) }
            settle()
        }
        then(vm)
        settle()
    }

    private fun pasted(then: Screen.(DutyRosterViewModel) -> Unit = {}): Screen.() -> Unit = monthInput { vm ->
        vm.readText(ROSTER)
        settle()
        vm.pick(vm.ui.value.rows!!.indexOfFirst { it.label == "김민지" })
        settle()
        then(vm)
    }

    // ---- 341: 한 달 근무 넣기, every size ----
    @Test fun phoneEmpty() = shoot("720_p_month_input_phone", PHONE, monthInput())
    @Test fun smallPhoneTyped() = shoot("721_p_month_input_small_phone", SMALL_PHONE, monthInput(typed = true))
    @Test fun landscapeTyped() = shoot("722_p_month_input_landscape", PHONE_LANDSCAPE, monthInput(typed = true))
    @Test fun hugeTextTyped() = shoot("723_p_month_input_huge_text", PHONE, fontScale = 2f, monthInput(typed = true))
    @Test fun darkTyped() = shoot("724_p_month_input_dark", FOLDED_NIGHT, monthInput(typed = true))
    @Test fun pastedRoster() = shoot("725_p_month_input_pasted", FOLDED, pasted { vm ->
        val u = vm.ui.value
        assertEquals(2, u.rows!!.size)
        assertEquals("김민지", u.row!!.label)
        assertEquals(11, u.month.monthValue)
        reveal("근무표에 적힌 11월로 넣어요")
    })
    @Test fun leaveQuestion() = shoot("726_p_month_input_leave_question", PHONE_393, pasted { reveal("‘병가’는 어떤 휴가인가요?") })
    @Test fun repeatedToTheEnd() = shoot("727_p_month_input_repeated", PHONE_393, monthInput(typed = true) { vm ->
        assertTrue(vm.repeatOrder())
        assertEquals(vm.ui.value.month.lengthOfMonth(), vm.ui.value.writable.size)
    })

    // ---- 344: 빠른 입력 bar and the keypad sheet ----
    @Test fun quickInputBar() = shoot("728_p_quick_input_month_and_half", FOLDED, style(WorkStyle.SHIFT) {
        tapText("빠른 입력", exact = true)
        assertTrue(shows("이번 달 전부 넣기"))
    })
    @Test fun quickInputHalfDay() = shoot("729_p_quick_input_half_day", PHONE, style(WorkStyle.SHIFT) {
        tapText("빠른 입력", exact = true)
        // The stamps follow the kinds of work on the bar's sideways row.
        swipeRowLeft("야간 칠하기", until = "오전 반차")
        tapText("오전 반차", exact = true)
    })
    @Test fun keypadRepeat() = shoot("730_p_keypad_sheet_repeat", FOLDED, style(WorkStyle.SHIFT) {
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val t = snap.shiftTypes.associateBy { it.name }
        val month = java.time.YearMonth.of(2026, 11)
        val initial = listOf("주간", "주간", "야간", "야간", "휴무", "휴무").mapIndexed { i, n -> month.atDay(i + 1) to ShiftOverride.Shift(t.getValue(n).id) as ShiftOverride }.toMap()
        content { com.bamnat.app.ui.calendar.DutyTypingSheet(snap, month, onDismiss = {}, onSave = {}, initial = initial) }
        settle()
        assertTrue(shows("이 순서 반복"))
    })
    @Test fun keypadHugeText() = shoot("731_p_month_input_keypad_huge_text", SMALL_PHONE, fontScale = 2f, monthInput(typed = true))

    // ---- 345: the first setup's 듀티표가 있어요 ----
    private fun setupRoster(read: Boolean): Screen.() -> Unit = {
        val id = c.calendars.begin("병동")
        val vm = com.bamnat.app.ui.setup.SetupViewModel(c, calendarId = id)
        vm.pickStyle(WorkStyle.SHIFT, roster = true)
        vm.next()
        vm.pickBundle(ShiftBundles.HOSPITAL)
        vm.confirmTimes()
        if (read) vm.readRosterText("11월 근무표\n${(1..10).joinToString(" ")}\n김간호 D D E E N N O O D D")
        content { com.bamnat.app.ui.setup.SetupHost(vm) }
        settle()
    }
    @Test fun setupRosterChips() = shoot("732_p_setup_roster_chips", FOLDED, setupRoster(read = false))
    @Test fun setupRosterRead() = shoot("733_p_setup_roster_read", FOLDED) {
        setupRoster(read = true).invoke(this)
        reveal("칠한 날 10일")
    }
    @Test fun setupRosterSmall() = shoot("734_p_setup_roster_small_phone", SMALL_PHONE, setupRoster(read = false))

    // ---- 346: the help answer ----
    @Test fun helpAnswer() = shoot("737_p_help_roster", FOLDED) {
        content {
            com.bamnat.app.ui.settings.HelpFaq.topics().first { it.id == "roster" }.let { t ->
                androidx.compose.material3.Surface { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                    androidx.compose.material3.Text(t.question, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.Text(t.answer)
                } }
            }
        }
    }
}

