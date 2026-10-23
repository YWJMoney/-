package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * [REL-15] 327: the backup file of format 3. Photos go in as files with a sum for every part, a changed or cut file
 * is turned away before anything changes, a password seals the whole zip, files of format 2 still open, and a
 * restore that fails halfway leaves the phone's data as it was.
 */
@RunWith(AndroidJUnit4::class)
class BackupFormat3Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val root = File(context.cacheDir, "format3-test")
    private val receipts = File(root, "receipts")
    private val contracts = File(root, "contracts")
    private val settings = SettingsRepository(context)
    private val day = LocalDate.of(2026, 10, 20)

    private fun manager(shareLinks: com.bamnat.app.share.ShareLinkBackup? = null) =
        BackupManager(db, settings, photos = BackupPhotos(receipts) { File(contracts, it) }, staging = File(root, "staging"), shareLinks = shareLinks)

    @Before fun setUp() = runBlocking<Unit> {
        root.deleteRecursively()
        receipts.mkdirs()
        File(receipts, "r1.jpg").writeBytes(ByteArray(3000) { (it % 251).toByte() })
        ScheduleRepository(db).ensureDefaults()
        MoneyRepository(db).save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 5_000, category = "식비", receipt = "r1.jpg"))
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun file(b: BackupManager, password: CharArray? = null): ByteArray =
        ByteArrayOutputStream().also { runBlocking { b.exportFile(it, password) } }.toByteArray()

    @Test fun aZipWithTheTextThePhotosAndTheirSums() {
        val bytes = file(manager())
        val names = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertEquals(listOf(BackupArchive.JSON, "photos/receipts/r1.jpg", BackupArchive.SUMS), names)
        val json = manager().read(bytes.inputStream())
        assertFalse("the photo is not text inside the JSON any more", JSONObject(json).has("photos"))
    }

    @Test fun thePhotosComeBackAsTheyWere() = runBlocking {
        val b = manager()
        val bytes = file(b)
        val photo = File(receipts, "r1.jpg").readBytes()
        File(receipts, "r1.jpg").delete()
        val json = b.read(bytes.inputStream())
        assertEquals(1, b.summarize(json).photos)
        b.restore(json)
        assertTrue(File(receipts, "r1.jpg").readBytes().contentEquals(photo))
        assertFalse("nothing waits after the restore", File(root, "staging").exists())
    }

    /** [ALRM-04] 증명 사진's fingerprints belong to this phone and this home: neither backup carries them. */
    @Test fun thePhotoMissionFingerprintsStayOnThePhone() = runBlocking {
        val refs = com.bamnat.app.alarm.PhotoReferences(context).apply { clear(); add(0x0123456789ABCDEFL, "욕실 거울") }
        settings.update { it.copy(alarmMission = "photo") }
        val json = manager().export()
        assertTrue("the choice goes", json.contains("\"alarmMission\": \"photo\""))
        assertFalse(json.contains("욕실 거울") || json.contains("123456789abcdef"))
        // Android's own backup and phone-to-phone copy take settings.xml alone, not photo_mission.xml.
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File("src/main/res/xml/$rules").readText()
            assertFalse(rules, xml.contains(com.bamnat.app.alarm.PhotoReferences.FILE))
            assertFalse(rules, Regex("""domain="sharedpref" path="\."""").containsMatchIn(xml))
        }
        refs.clear()
    }

    @Test fun aChangedFileIsTurnedAway() {
        val bytes = file(manager())
        // One byte of the photo (stored as it is) changed: its sum no longer matches.
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("photos/receipts/r1.jpg") + "photos/receipts/r1.jpg".length + 100
        bytes[at] = (bytes[at] + 1).toByte()
        try {
            manager().read(bytes.inputStream())
            fail("a changed file opened")
        } catch (e: BackupException) {
            assertEquals("백업 파일이 손상됐어요", e.message)
        }
    }

    @Test fun aCutFileIsTurnedAway() {
        val bytes = file(manager())
        try {
            manager().read(bytes.copyOf(bytes.size / 2).inputStream())
            fail("half a file opened")
        } catch (e: BackupException) {
            assertEquals("백업 파일이 손상됐어요", e.message)
        }
    }

    @Test fun aPasswordSealsTheWholeFile() = runBlocking {
        val b = manager()
        val bytes = file(b, "secret12".toCharArray())
        assertFalse("no photo name readable", String(bytes, Charsets.ISO_8859_1).contains("r1.jpg"))
        val locked = b.read(bytes.inputStream())
        assertTrue(b.isSealed(locked))
        try {
            b.unseal(locked, "wrong".toCharArray())
            fail("opened with a wrong password")
        } catch (e: BackupException) {
            assertEquals("암호가 맞지 않아요", e.message)
        }
        File(receipts, "r1.jpg").delete()
        val json = b.unseal(locked, "secret12".toCharArray())
        assertEquals(1, b.summarize(json).photos)
        b.restore(json)
        assertTrue(File(receipts, "r1.jpg").isFile)
    }

    @Test fun aFileOfFormat2StillOpens() = runBlocking {
        val b = manager()
        val old = JSONObject(b.export()).put("photos", JSONObject().put("receipts", JSONObject().put("r1.jpg", Base64.getEncoder().encodeToString(byteArrayOf(7, 7)))))
            .toString(1)
        File(receipts, "r1.jpg").delete()
        val json = b.read(old.toByteArray().inputStream())
        assertEquals(old, json)
        assertEquals(1, b.summarize(json).photos)
        b.restore(json)
        assertTrue(File(receipts, "r1.jpg").readBytes().contentEquals(byteArrayOf(7, 7)))
    }

    @Test fun aRestoreThatFailsHalfwayLeavesThePhoneAsItWas() = runBlocking {
        var calls = 0
        // The third look at the calendars fails, as a full disk would: the first two are this test's export and the
        // copy the restore keeps before it changes anything; the third comes after the database was written.
        val links = com.bamnat.app.share.ShareLinkBackup({ if (++calls == 3) throw java.io.IOException("disk full") else emptyList() }, { error("no store") })
        val schedule = ScheduleRepository(db)
        val json = manager(links).export()
        schedule.setOverride(day, ShiftOverride.Off)
        try {
            manager(links).restore(json)
            fail("the restore went through")
        } catch (e: BackupException) {
            assertEquals("백업을 불러오지 못했어요. 지금 데이터는 그대로예요", e.message)
        }
        val now = schedule.snapshot.first()
        assertTrue("the change made after the backup is still here", now.resolver.resolve(day).isOff)
    }
}
