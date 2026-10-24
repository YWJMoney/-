package com.bamnat.app.ops

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.appContainer
import com.bamnat.app.company.CompanyException
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.diag.UsageStats
import com.bamnat.app.tier.LaunchConfig
import com.bamnat.core.ops.ServerSwitch
import com.bamnat.core.plaza.HideList
import com.bamnat.core.stats.UsageBook
import com.bamnat.core.stats.UsageEvent
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [SRV-04][STAT-01][COMM-08][COMM-09][COMM-10] 1.22.0 묶음 B on the phone: the server's notices and
 * switches read strictly, 사용 통계 sent once a day and only with a yes, and the plaza settings kept in a backup.
 */
@RunWith(AndroidJUnit4::class)
class OperationsAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val now = Instant.parse("2026-11-02T00:00:00Z")

    @After fun close() {
        db.close()
        context.getSharedPreferences("usage_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val notice = """{"id":"nAbc123456","title":"점검 안내","body":"11월 3일 새벽 2시부터 30분 동안 광장이 쉬어요","level":"important",
        "from":"2026-11-01T00:00:00Z","until":"2026-11-04T00:00:00Z","minApp":33,"maxApp":null}"""

    @Test fun noticesAndSwitchesAreReadStrictlyAndKept() {
        val c = LaunchConfig.parse(JSONObject("""{"billingLive":false,"notices":[$notice],"off":["PLAZA_WRITE","FROM_A_NEWER_SERVER"]}"""), now)
        assertEquals(1, c.notices.size)
        val n = c.notices.single()
        assertTrue(n.important)
        assertEquals(33, n.minApp)
        assertNull(n.maxApp)
        assertEquals(setOf(ServerSwitch.PLAZA_WRITE), c.off)
        assertTrue(c.paused(ServerSwitch.PLAZA_WRITE))
        assertFalse(c.paused(ServerSwitch.SHARE_CREATE))
        // Saved and read back the same, so the last good answer survives a restart.
        assertEquals(c, LaunchConfig.fromSaved(c.toJson().toString()))
        // An old server says nothing of either: nothing is shown, nothing is paused.
        val old = LaunchConfig.parse(JSONObject("""{"billingLive":false}"""), now)
        assertTrue(old.notices.isEmpty() && old.off.isEmpty())
        // A wrong notice fails the whole answer, so the last good one stays.
        try {
            LaunchConfig.parse(JSONObject("""{"billingLive":false,"notices":[${notice.replace("nAbc123456", "../x")}]}"""), now)
            fail("a bad id was read")
        } catch (e: JSONException) {
        }
    }

    @Test fun usageStatsAreSentOnceADayOnlyWithAYes() = runBlocking {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.now(zone).toEpochDay()
        val prefs = context.getSharedPreferences("usage_stats", Context.MODE_PRIVATE)
        prefs.edit().putString("book", UsageBook().record(UsageEvent.OPEN, today - 1).record(UsageEvent.SHARE_MADE, today - 1).encode()).commit()
        val sent = mutableListOf<JSONObject>()
        val stats = UsageStats(context, baseUrl = "https://company.example", zone = { zone }, post = { _, body -> sent += body })

        // What would go is shown first: names and counts, nothing else.
        val preview = JSONObject(stats.preview()!!)
        assertEquals(setOf("v", "app", "days"), preview.keys().asSequence().toSet())
        val day = preview.getJSONArray("days").getJSONObject(0)
        assertEquals(1, day.getJSONObject("events").getInt("open"))

        // Without a yes nothing goes.
        assertFalse(stats.sendIfDue(allowed = false))
        assertTrue(sent.isEmpty())
        // With one it goes once, and not again the same day.
        assertTrue(stats.sendIfDue(allowed = true))
        assertFalse(stats.sendIfDue(allowed = true))
        assertEquals(1, sent.size)
        assertEquals(LocalDate.ofEpochDay(today - 1).toString(), sent.single().getJSONArray("days").getJSONObject(0).getString("day"))

        // Offline keeps the day for tomorrow; turning it off forgets it.
        prefs.edit().clear().putString("book", UsageBook().record(UsageEvent.OPEN, today - 1).encode()).commit()
        val offline = UsageStats(context, baseUrl = "https://company.example", zone = { zone }, post = { _, _ -> throw CompanyException("offline") })
        assertFalse(offline.sendIfDue(allowed = true))
        assertTrue(offline.preview() != null)
        offline.clear()
        assertNull(offline.preview())

        // A no kept from elsewhere (a restored backup) is honored at the next start; a yes keeps the days.
        prefs.edit().putString("book", UsageBook().record(UsageEvent.OPEN, today - 1).encode()).commit()
        offline.forgetUnless(on = true)
        assertTrue(offline.preview() != null)
        offline.forgetUnless(on = false)
        assertNull(offline.preview())
    }

    @Test fun turningUsageStatsOffForgetsWhatWaitedRightThen() {
        val c = context.appContainer
        c.setUsageStats(true)
        c.usageStats.record(UsageEvent.OPEN)
        assertTrue(context.getSharedPreferences("usage_stats", Context.MODE_PRIVATE).contains("book"))
        c.setUsageStats(false)
        assertFalse(c.settingsRepository.current.shareUsageStats)
        assertFalse(context.getSharedPreferences("usage_stats", Context.MODE_PRIVATE).contains("book"))
    }

    @Test fun thePlazaSettingsGoWithTheBackup() = runBlocking {
        com.bamnat.app.data.ScheduleRepository(db).ensureDefaults()
        val settings = SettingsRepository(context)
        val mark = "a".repeat(64)
        settings.update {
            it.copy(shareUsageStats = true, plazaNews = false, plazaRulesAgreed = 1,
                plazaHidden = HideList().hide(mark, "간호사 · 3월 2일", 1_800_000_000_000L).encode())
        }
        val file = BackupManager(db, settings).export()
        settings.update { it.copy(shareUsageStats = false, plazaNews = true, plazaRulesAgreed = 0, plazaHidden = "") }
        BackupManager(db, settings).restore(file)
        val s = settings.current
        assertTrue(s.shareUsageStats)
        assertFalse(s.plazaNews)
        assertEquals(1, s.plazaRulesAgreed)
        assertEquals(listOf(mark), HideList.decode(s.plazaHidden).writers.map { it.mark })
    }
}
