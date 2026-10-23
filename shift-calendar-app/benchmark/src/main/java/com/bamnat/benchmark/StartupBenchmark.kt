package com.bamnat.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.bamnat.app"

/** [PERF-03] 324-5: a cold start to the first month, without and with the Baseline Profile, five times each. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartWithoutProfile() = start(CompilationMode.None())

    @Test fun coldStartWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // The month is on screen: the app reports itself drawn (ReportDrawnWhen in the calendar).
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    }
}

/**
 * [PERF-03] 324-5 (검토 A4): the Baseline Profile from a real start and a walk through the main tabs, to replace
 * the hand-written app/src/main/baseline-prof.txt (which covers whole packages). See README.md.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        for (tab in listOf("메모", "통계", "설정", "달력")) {
            device.findObject(By.text(tab))?.click()
            device.waitForIdle()
        }
    }
}
