package com.bamnat.app.backup

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Tier
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * [PROV-03] 매일 자동 백업: the file it writes opens with the password like a backup saved by hand and
 * restores the same data, a longer old file is cut to the new one, it runs once a day, says why it
 * failed, and after three days of failing posts a notice.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AutoBackupTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val box = FakeKeyBox()
    private var now = Instant.parse("2026-10-22T18:00:00Z")
    private lateinit var file: File
    private val password = "밤낮1234"

    /** Keeps the key as it is, reversed; [gone] stands for a key store that lost it. */
    class FakeKeyBox : KeyBox {
        var gone = false
        var forgotten = 0
        override fun wrap(secret: ByteArray) = secret.reversedArray()
        override fun unwrap(blob: ByteArray): ByteArray = if (gone) throw KeyBox.KeyGone() else blob.reversedArray()
        override fun forget() { forgotten++ }
    }

    private fun backup() = AutoBackup(c, { box }, clock = { now })

    @Before fun setUp() {
        c.keyBox = box
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        file = File(app.filesDir, "drive/${AutoBackup.FILE_NAME}").apply { parentFile!!.mkdirs(); delete() }
        runBlocking {
            c.scheduleRepository.ensureDefaults()
            val day = c.scheduleRepository.shiftTypes.first().first { !it.isOff }.id
            c.scheduleRepository.setOverride(LocalDate.of(2026, 10, 23), ShiftOverride.Shift(day))
        }
    }

    @After fun tearDown() = runBlocking {
        backup().turnOff()
        c.settingsRepository.update { it.copy(previewTier = null) }
        c.featureGate.state.first { it.allows(Feature.AUTO_BACKUP) }
        Unit
    }

    @Test fun theFileOpensWithThePasswordAndRestoresTheSameData() = runBlocking {
        // A longer file from before: what is left after the new backup is cut.
        file.writeText("x".repeat(400_000))
        val auto = backup()
        val r = auto.turnOn(Uri.fromFile(file), password.toCharArray())
        assertTrue("$r", r is AutoBackup.Result.Ok)
        val text = file.readText()
        assertEquals((r as AutoBackup.Result.Ok).bytes.toLong(), file.length())
        assertTrue(c.backupManager.isSealed(text))
        val json = c.backupManager.unseal(text, password.toCharArray())
        fun body(s: String) = org.json.JSONObject(s).apply { remove("exportedAt") }.toString()
        assertEquals(body(c.backupManager.export()), body(json))
        assertEquals(AutoBackup.FILE_NAME, auto.state.value.name)
        assertEquals(now, auto.state.value.lastOk)

        // The day changes, the backup brings it back.
        val shifted = LocalDate.of(2026, 10, 23)
        c.scheduleRepository.setOverride(shifted, ShiftOverride.Off)
        c.backupManager.restore(json)
        val back = c.scheduleRepository.snapshot.first { s -> s.resolver.resolve(shifted).shiftType?.isOff == false }
        assertFalse(back.resolver.resolve(shifted).isOff)
        // The password itself is kept nowhere.
        val prefs = File(app.applicationInfo.dataDir, "shared_prefs/auto_backup.xml").readText()
        assertFalse(prefs.contains(password))
        assertEquals("a job keeps it going while the app is closed", 1,
            app.getSystemService(JobScheduler::class.java).allPendingJobs.size)
    }

    @Test fun itRunsOnceADay() = runBlocking {
        val auto = backup()
        auto.turnOn(Uri.fromFile(file), password.toCharArray())
        assertNull("just backed up", auto.runIfDue())
        now = now.plus(Duration.ofHours(12))
        assertNull(auto.runIfDue())
        now = now.plus(Duration.ofHours(9))
        assertTrue(auto.runIfDue() is AutoBackup.Result.Ok)
        assertEquals(now, auto.state.value.lastOk)
    }

    @Test fun failuresSayWhyAndThreeDaysBringANotice() = runBlocking {
        val auto = backup()
        auto.turnOn(Uri.fromFile(file), password.toCharArray())
        val ok = auto.state.value.lastOk
        box.gone = true
        now = now.plus(Duration.ofDays(1))
        val r = auto.runNow()
        assertTrue(r is AutoBackup.Result.Failed)
        assertTrue(auto.state.value.error!!.contains("껐다 켜"))
        assertEquals(ok, auto.state.value.lastOk)
        assertNotNull(auto.state.value.failingSince)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertTrue("not yet", nm.allNotifications.none { it.channelId == Notifications.BACKUP_CHANNEL_ID })
        now = now.plus(Duration.ofDays(3))
        auto.runNow()
        assertEquals(1, nm.allNotifications.count { it.channelId == Notifications.BACKUP_CHANNEL_ID })
        // Working again takes the notice and the error away.
        box.gone = false
        now = now.plus(Duration.ofHours(1))
        assertTrue(auto.runNow() is AutoBackup.Result.Ok)
        assertNull(auto.state.value.error)
        assertNull(auto.state.value.failingSince)
        assertTrue(nm.allNotifications.none { it.channelId == Notifications.BACKUP_CHANNEL_ID })
    }

    @Test fun aFileThatIsGoneIsSaid() = runBlocking {
        val auto = backup()
        auto.turnOn(Uri.fromFile(file), password.toCharArray())
        file.parentFile!!.deleteRecursively()
        val r = auto.runNow()
        assertTrue("$r", r is AutoBackup.Result.Failed)
        // Somewhere else, with the same password.
        val other = File(app.filesDir, "other.json")
        assertTrue(auto.moveTo(Uri.fromFile(other)) is AutoBackup.Result.Ok)
        assertTrue(c.backupManager.unseal(other.readText(), password.toCharArray()).isNotEmpty())
        assertEquals("other.json", auto.state.value.name)
    }

    @Test fun withoutProItWaits() = runBlocking {
        val auto = backup()
        auto.turnOn(Uri.fromFile(file), password.toCharArray())
        c.settingsRepository.update { it.copy(previewTier = Tier.FREE) }
        c.featureGate.state.first { it.locked(Feature.AUTO_BACKUP) }
        now = now.plus(Duration.ofDays(2))
        assertEquals(AutoBackup.Result.Locked, auto.runNow())
        assertNull("waiting is not failing", auto.state.value.error)
        assertTrue(auto.on)
    }

    @Test fun turningItOffForgetsTheKey() = runBlocking {
        val auto = backup()
        auto.turnOn(Uri.fromFile(file), password.toCharArray())
        val before = box.forgotten
        auto.turnOff()
        assertFalse(auto.on)
        assertTrue(box.forgotten > before)
        assertEquals(AutoBackup.Result.Off, auto.runNow())
        assertTrue("the file stays", file.exists())
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }
}
