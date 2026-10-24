package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth
import androidx.compose.ui.semantics.getOrNull

/**
 * [CAL-50][CAL-51][VIS-04][ALRM-05][CAL-52][VIS-05][STAT-07][A11Y-05] 묶음 R's screenshots, numbers 760~779: the calendar's first
 * screen on a small phone, a common one and one on its side (760~765), large text (766~770), the month's title and the
 * day cells' text (771~776), the same counts everywhere (777~778) and the sheets' handles (779). Each also checks the
 * screen as drawn: no text cut or broken into single letters, and enough of the month above the bottom bar.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RScreenshotTest : ScreenshotBase() {

    internal companion object {
        const val PHONE_640 = "w360dp-h640dp-port-xhdpi"
        const val PHONE_393 = "w393dp-h873dp-port-440dpi"
        const val PHONE_393_NIGHT = "w393dp-h873dp-port-night-440dpi"
        const val PHONE_412 = "w412dp-h915dp-port-420dpi"
        const val TABLET = "w800dp-h1280dp-port-xhdpi"
        /** Fewer nodes than this is the bottom bar alone: the screen's own semantics are not in yet. */
        const val MIN_NODES = 60

        /** 1.23 점검 보고서's 13 sizes: name, qualifiers, text scale. */
        val SIZES = listOf(
            Triple("320", SMALL_PHONE, 1f), Triple("360x640", PHONE_640, 1f), Triple("360x780", PHONE, 1f),
            Triple("393", PHONE_393, 1f), Triple("412", PHONE_412, 1f), Triple("fold_cover", FOLDED, 1f),
            Triple("fold_open", UNFOLDED, 1f), Triple("tablet", TABLET, 1f), Triple("landscape", PHONE_LANDSCAPE, 1f),
            Triple("393_130", PHONE_393, 1.3f), Triple("393_200", PHONE_393, 2f), Triple("320_200", SMALL_PHONE, 2f),
            Triple("393_dark", PHONE_393_NIGHT, 1f),
        )
    }

    /** What the screen shows as drawn: the text cut on it and the month's whole weeks above the bottom bar. */
    private fun Screen.look(name: String): Pair<List<LayoutCheck.Cut>, Int> {
        val m = app.resources.displayMetrics
        // The first screen of a fork can be drawn before its semantics are: wait until the month is in them.
        repeat(10) { if (nodes(merged = false).size < MIN_NODES) settle() }
        val all = nodes(merged = false)
        val cuts = LayoutCheck.cuts(all, m.widthPixels, m.heightPixels)
        LayoutCheck.report(name, cuts)
        val merged = nodes(merged = true)
        val weeks = LayoutCheck.weeksShown(merged, YearMonth.from(today), LayoutCheck.contentBottom(merged, m.heightPixels), m.widthPixels)
        return cuts to weeks
    }

    // ---- 13 sizes: the calendar as it opens (점검 보고서 방식, written to build/screenshots/r-audit/) ----
    private fun audit(i: Int) {
        val (name, q, scale) = SIZES[i]
        shoot("r_audit_calendar_$name", q, scale) {
            val (cuts, weeks) = look("calendar_$name")
            println("R audit calendar_$name: weeks=$weeks cuts=${cuts.size} $cuts")
        }
    }
    @Test fun audit00() = audit(0)
    @Test fun audit01() = audit(1)
    @Test fun audit02() = audit(2)
    @Test fun audit03() = audit(3)
    @Test fun audit04() = audit(4)
    @Test fun audit05() = audit(5)
    @Test fun audit06() = audit(6)
    @Test fun audit07() = audit(7)
    @Test fun audit08() = audit(8)
    @Test fun audit09() = audit(9)
    @Test fun audit10() = audit(10)
    @Test fun audit11() = audit(11)
    @Test fun audit12() = audit(12)

    // ---- Other screens the 1.23 보고서 named, on the sizes where they broke (audit only, build/screenshots/r-audit/) ----
    private val auditSizes = listOf(0, 3, 6, 7, 8, 10, 11)
    private fun auditScreen(screen: String, wideMatters: Boolean = false, then: Screen.(wide: Boolean) -> Unit) {
        for (i in auditSizes) {
            val (name, q, scale) = SIZES[i]
            val wide = wideMatters && (name == "fold_open" || name == "tablet" || name == "landscape")
            shoot("r_audit_${screen}_$name", q, scale) {
                then(wide)
                val (cuts, _) = look("${screen}_$name")
                println("R audit ${screen}_$name: cuts=${cuts.size} $cuts")
            }
        }
    }
    @Test fun auditDaySheet() = auditScreen("day") { openDayBelow(dayCell) }
    @Test fun auditPay() = auditScreen("pay") { payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true)(this) }
    @Test fun auditStats() = auditScreen("stats", wideMatters = true) { w -> withPay()(this); tab(3, w) }
    @Test fun auditSettings() = auditScreen("settings", wideMatters = true) { w -> tab(4, w) }
    @Test fun auditHelp() = auditScreen("help") { askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true)) }
    @Test fun auditMission() = auditScreen("mission") { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofMinutes(1)) }
    @Test fun auditDiag() = auditScreen("diag") { content { com.bamnat.app.ui.diag.DiagnosticsScreen(com.bamnat.app.ui.diag.DiagnosticsViewModel(app.container), onBack = {}) } }
    @Test fun auditYear() = auditScreen("year") { year() }
}
