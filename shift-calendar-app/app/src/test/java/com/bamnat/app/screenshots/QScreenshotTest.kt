package com.bamnat.app.screenshots

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.components.DayOfMonthPicker
import com.bamnat.app.ui.components.QuickDecimalChips
import com.bamnat.app.ui.components.RuleBuilder
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.TimeField
import com.bamnat.app.ui.components.dayOfMonthLabel
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.money.FieldRules
import com.bamnat.core.work.WorkStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/**
 * [INPUT-01..04][PAYX-47] Bundle Q's screenshots, numbers 740~759 (계획/1.24-직접입력-전면개선-통합계획.md 1장 7번):
 * the shared direct inputs at 320·360·369·780 wide, 200% text and dark (740~744), the rule builder (745), the 호봉표
 * editor (746~748), the screens whose typed numbers became taps (749~753), the three company-way places (754~756)
 * and the clipboard line on pasted things (757~759).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QScreenshotTest : ScreenshotBase() {

    @Test fun inputsFolded() = shoot("740_q_inputs_folded", FOLDED) { content { Inputs() } }
    @Test fun inputsSmall() = shoot("741_q_inputs_320", SMALL_PHONE) { content { Inputs() } }
    @Test fun inputsBigFont() = shoot("742_q_inputs_200", PHONE, fontScale = 2f) { content { Inputs() } }
    @Test fun inputsDark() = shoot("743_q_inputs_dark", FOLDED_NIGHT) { content { Inputs() } }
    @Test fun inputsLandscape() = shoot("744_q_inputs_landscape", PHONE_LANDSCAPE) { content { Inputs() } }

    @Test fun ruleBuilder() = shoot("745_q_rule_builder", FOLDED) {
        content { Builder() }
        tapText("50%", exact = true)
        tapText("이 규칙 담기", exact = true)
        check(sees("대근은 8시간에 기본급의 10%", exact = false)) { "담은 규칙이 보이지 않아요" }
    }

    @Test fun payScaleFolded() = shoot("746_q_pay_scale_folded", FOLDED, payScale {
        typeInto("1호봉 기본급", "2400000")
        typeInto("2호봉 기본급", "2480000")
        tapText("3호봉 더하기")
        check(sees("2560000", exact = false) || sees("2,560,000", exact = false)) { "더한 호봉이 위 호봉만큼 오르지 않았어요" }
        tapText("2호봉", exact = true)
    })
    @Test fun payScaleBigFont() = shoot("747_q_pay_scale_200", PHONE, 2f, payScale {
        typeInto("1호봉 기본급", "2400000")
        typeInto("2호봉 기본급", "2480000")
        tapText("3호봉 더하기")
    })
    @Test fun payScaleOther() = shoot("748_q_pay_scale_photo_words", UNFOLDED, payScale { tapText("사진·글로 넣기", exact = true) })

    @Test fun investMonthly() = shoot("749_q_invest_monthly_alarm", FOLDED, investAlarm { reveal("매달 10일", exact = true); tapText("매달 10일", exact = true) })
    @Test fun investMonthlySmallBig() = shoot("750_q_invest_monthly_320_200", SMALL_PHONE, 2f, investAlarm { reveal("매달 25일", exact = true) })

    @Test fun dayRate() = shoot("751_q_day_rate", FOLDED) {
        content { com.bamnat.app.ui.calendar.DayRateDialog(today, 1.0, FieldRules(dayRate = 230_000, lastClient = "OO건설"), null, {}, { _, _ -> }) }
        tapText("공수 0.25공 늘리기", exact = true, scrolls = 0)
    }

    @Test fun payslipHours() = shoot("752_q_payslip_hours", FOLDED, payslipMatch().then {
        tapText("네, 같았어요", exact = true)
        typeInto("지급 총액", "4300000")
        reveal("더 넣으면 더 정확해요 (선택)", exact = true)
        tapText("더 넣으면 더 정확해요 (선택)", exact = true)
        reveal("연장근로수당 시간 30분 늘리기", exact = true)
    })

    @Test fun monthlyHours() = shoot("753_q_monthly_hours", FOLDED, rulesEditor { companyRules }.then { reveal("월 소정근로시간 늘리기", exact = true) })

    @Test fun askDeveloper() = shoot("754_q_ask_developer_builder", FOLDED) {
        companyOn()
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("회사 방식 알려 주기", exact = true)
        tapText("이 규칙 담기", exact = true)
    }
    @Test fun reportDark() = shoot("755_q_report_builder_dark", FOLDED_NIGHT) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("회사 방식 알려 주기", exact = true)
    }
    @Test fun ruleSentences() = shoot("756_q_rule_sentences_builder", FOLDED, rulesEditor { companyRules }.then {
        reveal("근로시간 제도 (주 52시간 예외)", exact = true)
        reveal("회사 규칙 골라 담기", exact = true)
        tapText("회사 규칙 골라 담기", exact = true, scrolls = 0)
        reveal("토요일 근무", exact = true)
        tapText("토요일 근무", exact = true)
        tapText("이 규칙 담기", exact = true)
        reveal("읽기", exact = true)
    })

    @Test fun payslipClipboard() = shoot("757_q_payslip_clipboard", FOLDED) {
        copy("기본급 2,508,000\n연장근로수당 288,000\n식대 200,000\n지급합계 2,996,000")
        content { com.bamnat.app.ui.pay.PayslipFillDialog("명세서로 한 번에 채우기", onDismiss = {}, onRead = {}) }
        check(sees("복사한 글이 있어요", exact = true)) { "복사한 명세서를 알려 주지 않았어요" }
    }
    @Test fun paymentClipboard() = shoot("758_q_payment_clipboard", FOLDED) {
        copy("[Web발신]\n신한카드(1234)승인 홍*동 4,500원(일시불)10/14 08:10 스타벅스")
        content { com.bamnat.app.ui.money.PasteDialog(emptyList(), emptyMap(), onSave = {}, onDismiss = {}) }
        tapText("붙여 넣기", exact = true, scrolls = 0)
        check(sees("스타벅스", exact = false)) { "붙여 넣은 문자가 읽히지 않았어요" }
    }
    @Test fun codeClipboard() = shoot("759_q_group_code_clipboard", FOLDED) {
        copy("우리 조 모임 코드예요: ABC 123")
        content {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                var code by remember { mutableStateOf("") }
                SectionCard("받은 코드로 들어가기") {
                    if (code.isBlank()) com.bamnat.app.ui.components.ClipboardHint("모임 코드", fits = { com.bamnat.app.share.ShareGroup.code(it) != null }, onPaste = { code = it })
                    androidx.compose.material3.Text(code.ifBlank { "코드 칸" })
                }
            }
        }
    }

    // ---- helpers ----

    private fun (Screen.() -> Unit).then(next: Screen.() -> Unit): Screen.() -> Unit = { this@then(this); next() }

    private fun copy(text: String) =
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("밤낮 시험", text))

    private fun Screen.sees(text: String, exact: Boolean): Boolean = runCatching { reveal(text, exact = exact); true }.getOrDefault(false)

    private fun payScale(then: Screen.() -> Unit): Screen.() -> Unit = {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            vm.profile.collectAsState().value?.let { p -> com.bamnat.app.ui.pay.PayScaleDialog(vm, p, onDismiss = {}) }
        }.invoke(this)
        then()
    }

    private fun investAlarm(then: Screen.() -> Unit): Screen.() -> Unit = style(WorkStyle.INVEST) {
        val vm = com.bamnat.app.ui.invest.InvestViewModel(app.container, com.bamnat.app.data.PersonalCalendar.MAIN)
        content {
            val state by vm.state.collectAsState()
            com.bamnat.app.ui.invest.AlarmSheet(vm, state, AlarmKind.PLAN, null, onDismiss = {})
        }
        settle()
        then()
    }

    /** Every shared direct input of 347 on one page. */
    @Composable
    private fun Inputs() {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("매달 며칠") {
                var day by remember { mutableIntStateOf(25) }
                androidx.compose.material3.Text(dayOfMonthLabel(day), style = MaterialTheme.typography.labelLarge)
                DayOfMonthPicker(day, { day = it })
            }
            SectionCard("시각 (누르면 시계)") {
                var at by remember { mutableStateOf<LocalTime?>(LocalTime.of(9, 5)) }
                TimeField("매매 시각", at, { at = it }, clearable = true)
                TimeField("알람 시각", null, {}, modifier = Modifier.padding(top = 8.dp))
            }
            SectionCard("값 칩과 스테퍼") {
                var units by remember { mutableStateOf(1.5) }
                QuickDecimalChips(units, { units = it }, name = "공수", quick = listOf(0.5, 1.0, 1.5, 2.0), step = 0.25, range = 0.25..5.0, unit = "공")
                var fee by remember { mutableStateOf<Double?>(null) }
                QuickDecimalChips(fee, { fee = it }, name = "인상률", quick = listOf(2.0, 3.0, 3.5, 4.0, 5.0), step = 0.1, range = 0.1..30.0, unit = "%",
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    private fun Builder() {
        var added by remember { mutableStateOf(emptyList<String>()) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
            RuleBuilder(added, { added = it })
        }
    }

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
    }
}
