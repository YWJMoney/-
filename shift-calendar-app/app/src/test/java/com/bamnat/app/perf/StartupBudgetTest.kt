package com.bamnat.app.perf

import android.Manifest
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.AppContainer
import com.bamnat.app.BamnatApp
import com.bamnat.app.MainActivity
import com.bamnat.app.StartupTrace
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * [PERF-01] The startup budget, checked without a phone: what a cold start builds and runs before the
 * calendar's month is on screen. Times here are the JVM's, many times a phone's, so their limits are
 * loose and only catch a step that grew badly; the counts are exact and catch new work added to the start.
 * A real phone's cold start is measured by hand (사용자 할 일), or by a Macrobenchmark once there is a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StartupBudgetTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()

    /** A shift worker who finished the first setup: the app opens on the calendar. */
    private fun settledUser(c: AppContainer) = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        c.consentStore.markSeen(ConsentItem.entries)
        c.scheduleRepository.ensureDefaults()
        val types = c.scheduleRepository.shiftTypes.first().associateBy { it.name }
        val ids = listOf("주간", "주간", "야간", "야간", "휴무").map { types.getValue(it).id }
        c.scheduleRepository.savePattern(WorkPattern.Rotation(LocalDate.now().minusDays(10), ids))
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
    }

    /** Runs the main thread until the calendar has its month, or [limitMillis] of waiting passed. */
    private fun untilCalendar(limitMillis: Long) {
        val end = System.currentTimeMillis() + limitMillis
        while (StartupTrace.millis(StartupTrace.Step.CALENDAR) == null && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun lazyBuilt(c: AppContainer, name: String): Boolean {
        val field = AppContainer::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        return (field.get(c) as Lazy<*>).isInitialized()
    }

    @Test fun theCalendarOpensWithinItsBudget() {
        val c = app.container
        val built = StartupTrace.millis(StartupTrace.Step.CONTAINER)
        assertNotNull("the container is built while the app starts", built)
        assertTrue("container and its start took $built ms (budget $CONTAINER_BUDGET_MS)", built!! <= CONTAINER_BUDGET_MS)
        settledUser(c)

        ActivityScenario.launch(MainActivity::class.java).use {
            untilCalendar(limitMillis = 30_000)
            val activity = StartupTrace.millis(StartupTrace.Step.ACTIVITY)
            val calendar = StartupTrace.millis(StartupTrace.Step.CALENDAR)
            assertNotNull("the activity started", activity)
            assertNotNull("the calendar showed its month", calendar)
            val toMonth = calendar!! - activity!!
            // Kept in the test report (system-out) so a slower start shows before it fails.
            println("[PERF-01] container ${built} ms, activity to month ${toMonth} ms")
            assertTrue("activity to month took $toMonth ms (budget $FIRST_MONTH_BUDGET_MS)", toMonth <= FIRST_MONTH_BUDGET_MS)
        }
    }

    @Test fun theStartLeavesWhatItDoesNotNeedForLater() {
        val c = app.container
        settledUser(c)
        ActivityScenario.launch(MainActivity::class.java).use {
            untilCalendar(limitMillis = 30_000)
            // Before the 정식 출시 switch nothing is bought: Play's billing client is not even built.
            assertFalse("Play billing waits until it is used", lazyBuilt(c, "playBilling"))
            // Tools opened from settings or a menu. (The phone calendar importer is asked at every start whether
            // the user chose calendars to watch, by design: IMPORT-02; 최근 지운 기록 drops what is past 7 days: UNDO-01; an added calendar's setup left unsaved is dropped: MCAL-02.)
            for (name in listOf("importUndo", "companyShare")) {
                assertFalse("$name waits until it is used", lazyBuilt(c, name))
            }
        }
    }

    @Test fun theStartRunsOnlyItsBudgetOfJobs() {
        val c = app.container
        // The jobs that follow the data for as long as the app runs (alarms, notices, widgets, the watch …), counted
        // by name: one-off jobs of the start (a Pro check, the alarm check) end at their own pace and are not counted.
        val jobs = c.kept.toList()
        assertEquals("each job is kept once", jobs.size, jobs.toSet().size)
        assertTrue("${jobs.size} jobs at start (budget $JOBS_BUDGET): a new one belongs in this budget on purpose: $jobs", jobs.size <= JOBS_BUDGET)
        assertTrue("the start does follow the data", jobs.size >= 10)
    }

    @Test fun eachStepIsNotedOnce() {
        StartupTrace.begin()
        StartupTrace.mark(StartupTrace.Step.ACTIVITY)
        val first = StartupTrace.millis(StartupTrace.Step.ACTIVITY)
        Thread.sleep(5)
        StartupTrace.mark(StartupTrace.Step.ACTIVITY)
        assertEquals(first, StartupTrace.millis(StartupTrace.Step.ACTIVITY))
        assertEquals(null, StartupTrace.millis(StartupTrace.Step.CALENDAR))
    }

    private companion object {
        /**
         * Measured on the build machine (Robolectric, cold JVM) on 2026-10-22, with room for a slower CI:
         * see 계획/1.22-F-품질-성능-접근성-개발계획.md for the numbers these came from.
         */
        const val CONTAINER_BUDGET_MS = 4_000L
        const val FIRST_MONTH_BUDGET_MS = 8_000L
        /**
         * The jobs [AppContainer.start] keeps running today (17 after 1.22 묶음 B); raising it is a decision,
         * not an accident.
         */
        const val JOBS_BUDGET = 17
    }
}
