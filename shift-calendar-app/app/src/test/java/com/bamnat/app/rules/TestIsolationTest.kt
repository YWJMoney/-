package com.bamnat.app.rules

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config

/** [QA-04] An app's background jobs end with its test (BamnatApp.onTerminate), so none acts on the next test's data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TestIsolationTest {
    @Test fun a_theAppFollowsItsData() {
        val jobs = ApplicationProvider.getApplicationContext<BamnatApp>().container.appScope.coroutineContext[Job]!!
        assertTrue(jobs.children.any { it.isActive })
        earlier = jobs
    }

    @Test fun b_theEarlierTestsJobsHaveStopped() {
        val jobs = earlier ?: return
        assertTrue("the earlier test's app still runs its jobs", jobs.isCancelled)
    }

    private companion object {
        var earlier: Job? = null
    }
}
