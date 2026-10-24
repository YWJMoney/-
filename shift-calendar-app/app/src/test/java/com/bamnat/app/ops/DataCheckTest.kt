package com.bamnat.app.ops

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.diag.DataCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [QA-07] 330: 데이터 점검 says all is well on healthy data and points at a day whose kind of work is gone. */
@RunWith(AndroidJUnit4::class)
class DataCheckTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()

    @Test fun healthyDataPassesEveryCheck() = runBlocking {
        app.container.scheduleRepository.ensureDefaults()
        val lines = DataCheck.run(app.container)
        assertTrue(lines.toString(), lines.all { it.ok })
        assertTrue(lines.toString(), lines.any { it.text.startsWith("백업 만들기·읽기") })
    }

    @Test fun aDayPointingAtAGoneKindIsFound() = runBlocking {
        app.container.scheduleRepository.ensureDefaults()
        app.container.database.dayEntryDao().upsert(
            com.bamnat.app.data.db.DayEntryEntity(
                epochDay = 20_000, overrideMode = 2, overrideShiftTypeId = 987_654,
                customStartMinute = null, customEndMinute = null, customBreakMinutes = null, alarmSkipped = false,
            ),
        )
        val lines = DataCheck.run(app.container)
        assertFalse(lines.toString(), lines.first { it.text.contains("바뀐 날") }.ok)
    }
}
