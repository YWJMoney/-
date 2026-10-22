package com.bamnat.app.a11y

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.MainActivity
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * [A11Y-02] What TalkBack and a thumb meet on the main screens, read from the screen's semantics:
 *  - everything that can be tapped has a touch area of at least 48×48dp (Android's touch target guideline,
 *    https://developer.android.com/guide/topics/ui/accessibility/apps#large-controls), measured as TalkBack and
 *    Accessibility Scanner do (the touch area Compose widens to 48dp), and is drawn at least 24dp (WCAG 2.2, 2.5.8),
 *  - everything that can be tapped says what it is (a text, or a content description for an icon), so
 *    TalkBack never reads only "버튼".
 * Screens: the calendar folded and unfolded, a day, 돈, 메모, 근무·급여, 설정, the first-start pages and the
 * ringing alarm. Each run writes what it found to build/a11y/<screen>.txt and pictures to build/screenshots/51x_*.png.
 * Hand checks with TalkBack on a phone stay a user task (사용자 할 일).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AccessibilityCheckTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val today = LocalDate.now()

    /** A shift worker a few weeks in: a pattern, a changed day, extra work, memos and money. */
    private fun settledUser() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val c = app.container
        c.consentStore.markSeen(ConsentItem.entries)
        com.bamnat.app.ui.calendar.HintStore(app).markDone(com.bamnat.app.ui.calendar.HintStore.LONG_PRESS)
        com.bamnat.app.data.GuideStore.get(app).let { g ->
            listOf("guide.checklist", "guide.tips").forEach { id -> g.setOn(com.bamnat.core.guide.FeatureRegistry.get(id)!!, "main", false) }
        }
        val repo = c.scheduleRepository
        repo.ensureDefaults()
        val types = repo.shiftTypes.first().associateBy { it.name }
        val ids = listOf("주간", "주간", "야간", "야간", "휴무").map { types.getValue(it).id }
        repo.savePattern(WorkPattern.Rotation(today.minusDays(10), ids))
        repo.addExtra(today, ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))
        c.memoRepository.save(Memo(date = today, title = "인수인계", body = "3번 라인 센서 점검"))
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
    }

    /** One screen's findings. */
    private class Found(val screen: String) {
        val small = mutableListOf<String>()
        val unnamed = mutableListOf<String>()
        val all get() = small.map { "$screen: 작은 터치 영역 $it" } + unnamed.map { "$screen: 이름 없는 버튼 $it" }
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(40)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun shownDialogs() = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()

    private fun rootOf(v: View): ViewRootForTest? {
        if (v is ViewRootForTest) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) rootOf(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text, config.getOrNull(SemanticsProperties.StateDescription))

    /** The nodes of the topmost window (a dialog or sheet when one is open), merged as TalkBack reads them. */
    private fun nodes(activity: MainActivity): Pair<List<SemanticsNode>, View> {
        val top = shownDialogs().lastOrNull()?.window?.decorView ?: activity.window.decorView
        val root = rootOf(top) ?: return emptyList<SemanticsNode>() to top
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) to top
    }

    /** Checks what is on screen now, writes it to build/a11y and a picture to build/screenshots. */
    private fun audit(scenario: ActivityScenario<MainActivity>, screen: String): Found {
        settle()
        val found = Found(screen)
        val lines = mutableListOf<String>()
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val (list, top) = nodes(activity)
            for (n in list) {
                val b = n.boundsInWindow
                // Off screen or cut by a scrolling edge: checked where it shows whole (a scroll away), not here.
                if (b.width < n.size.width - 1f || b.height < n.size.height - 1f || b.left < 0f || b.top < 0f ||
                    b.right > top.width + 1f || b.bottom > top.height + 1f) continue
                val click = n.config.getOrNull(SemanticsActions.OnClick) ?: continue
                if (n.config.contains(SemanticsProperties.Disabled)) continue
                // What TalkBack and Accessibility Scanner measure: the touch area, which Compose widens to 48dp
                // around anything smaller (ViewConfiguration.minimumTouchTargetSize). The drawn size is kept too.
                val touch = n.touchBoundsInRoot
                val tw = touch.width / density
                val th = touch.height / density
                val w = n.size.width / density
                val h = n.size.height / density
                val name = (n.words() + listOfNotNull(click.label)).joinToString(" ").replace('\n', ' ').trim()
                val at = "(${(b.left / density).roundToInt()},${(b.top / density).roundToInt()})dp"
                lines += "터치 ${tw.roundToInt()}x${th.roundToInt()}dp, 보이는 ${w.roundToInt()}x${h.roundToInt()}dp $at '$name'"
                if (tw < MIN_DP - TOLERANCE || th < MIN_DP - TOLERANCE || w < MIN_DRAWN_DP || h < MIN_DRAWN_DP) {
                    found.small += "'$name' 터치 ${tw.roundToInt()}x${th.roundToInt()}dp, 보이는 ${w.roundToInt()}x${h.roundToInt()}dp $at"
                }
                if (name.isBlank()) found.unnamed += at
            }
            val view = activity.window.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            view.draw(canvas)
            for (dialog in shownDialogs()) {
                canvas.drawColor(0x66000000)
                val dv = dialog.window!!.decorView
                canvas.save()
                canvas.translate((view.width - dv.width) / 2f, (view.height - dv.height) / 2f)
                dv.draw(canvas)
                canvas.restore()
            }
            File("build/screenshots/$screen.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File("build/a11y/$screen.txt").apply { parentFile!!.mkdirs() }
            .writeText((listOf("# 누를 수 있는 것 ${lines.size}개") + lines + listOf("", "# 걸린 것") + found.all).joinToString("\n"))
        return found
    }

    /** Taps what shows [text] through its click action, as TalkBack's double tap does. */
    private fun tap(scenario: ActivityScenario<MainActivity>, text: String, prefix: Boolean = false) {
        var done = false
        var seen = emptyList<String>()
        scenario.onActivity { activity ->
            val all = nodes(activity).first
            seen = all.filter { it.config.contains(SemanticsActions.OnClick) }.map { it.words().joinToString("/") }
            val node = all.firstOrNull { n ->
                n.config.contains(SemanticsActions.OnClick) && n.words().any { if (prefix) it.startsWith(text) else it == text }
            } ?: return@onActivity
            done = node.config[SemanticsActions.OnClick].action?.invoke() != false
        }
        check(done) { "‘$text’를 누르지 못했어요. 누를 수 있는 것: " + seen.joinToString(" | ").take(2000) }
        settle()
    }

    private fun launch(qualifiers: String, body: (ActivityScenario<MainActivity>) -> List<Found>): List<Found> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.mainClock.autoAdvance = false
        return ActivityScenario.launch(MainActivity::class.java).use { scenario -> settle(); body(scenario) }
    }

    private fun assertClean(found: List<Found>) {
        val problems = found.flatMap { it.all }.filter { p -> ALLOWED.none { it in p } }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test fun theFoldedTabsAndADay() {
        settledUser()
        assertClean(launch(FOLDED) { s ->
            listOf(
                audit(s, "510_a11y_folded_calendar"),
                // A tap on a date opens its sheet (DAY-01).
                run { tap(s, Fmt.dayTitle(today), prefix = true); audit(s, "511_a11y_folded_day") },
            )
        } + launch(FOLDED) { s ->
            listOf(
                run { tap(s, "돈"); audit(s, "512_a11y_folded_money") },
                run { tap(s, "메모"); audit(s, "513_a11y_folded_memos") },
                run { tap(s, "근무·급여"); audit(s, "514_a11y_folded_stats") },
                run { tap(s, "설정"); audit(s, "515_a11y_folded_settings") },
            )
        })
    }

    @Test fun theUnfoldedCalendar() {
        settledUser()
        assertClean(launch(UNFOLDED) { s -> listOf(audit(s, "516_a11y_unfolded_calendar")) })
    }

    @Test fun theFirstStart() {
        // A new phone: the consent pages, then the first question.
        assertClean(launch(FOLDED) { s ->
            val pages = audit(s, "517_a11y_first_consent")
            app.container.consentStore.markSeen(ConsentItem.entries)
            listOf(pages, audit(s, "518_a11y_first_setup"))
        })
    }

    @Test fun theRingingAlarm() {
        settledUser()
        assertClean(launch(FOLDED) { s ->
            s.onActivity { a ->
                a.setContent {
                    BamnatTheme {
                        com.bamnat.app.alarm.RingingScreen(title = "주간 근무", subtitle = "08:00 출근", snoozeMinutes = 5, onSnooze = {}, onDismiss = {})
                    }
                }
            }
            listOf(audit(s, "519_a11y_ringing_alarm"))
        })
    }

    private companion object {
        const val MIN_DP = 48f
        /**
         * Drawn smaller than this is too small to hit even with the wider touch area: WCAG 2.2's minimum target
         * size, 24 CSS px (https://www.w3.org/TR/WCAG22/#target-size-minimum).
         */
        const val MIN_DRAWN_DP = 24f
        /** Layout rounds to whole pixels: 47.6dp at 420dpi is a 48dp target. */
        const val TOLERANCE = 0.6f
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"

        /**
         * Found and kept on purpose, each with its reason (A11Y-02). Matched as part of the finding.
         */
        val ALLOWED: List<String> = listOf()
    }
}
