package com.bamnat.app.alarm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.core.alarm.AlarmMission
import com.bamnat.core.alarm.ImageHash
import com.bamnat.core.alarm.Missions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * [ALRM-04] 증명 사진 on the phone: a photo becomes its fingerprint and is deleted, the same place in other light
 * passes and another does not, the references keep two at most, and the mission gives way to sums.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoMissionTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()

    @After fun tearDown() {
        PhotoReferences(app).clear()
        PhotoPrint.clear(app)
    }

    /** A room drawn as a camera would see it: a wall and a few things on it, [light] times as bright. */
    private fun room(seed: Int, light: Float = 1f): Bitmap {
        val r = Random(seed)
        val b = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        fun tone(v: Int): Int { val x = (v * light).toInt().coerceIn(0, 255); return Color.rgb(x, x, (x * 0.9f).toInt()) }
        c.drawColor(tone(r.nextInt(80, 170)))
        val p = Paint()
        repeat(6) {
            p.color = tone(r.nextInt(0, 256))
            val l = r.nextInt(0, 500).toFloat()
            val t = r.nextInt(0, 360).toFloat()
            c.drawRect(l, t, l + r.nextInt(80, 300), t + r.nextInt(60, 240), p)
        }
        return b
    }

    /** [bitmap] written where the camera app writes, then read back as the mission reads it. */
    private fun photo(bitmap: Bitmap): Long? = runBlocking {
        val f = PhotoPrint.file(app)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val hash = PhotoPrint.take(app, Uri.fromFile(f))
        assertFalse("the photo is deleted once read", f.exists())
        hash
    }

    @Test fun aPhotoBecomesItsFingerprintAndIsDeleted() {
        val mirror = photo(room(1))
        assertNotNull(mirror)
        assertTrue("dimmer, the same mirror", ImageHash.samePlace(photo(room(1, light = 0.65f))!!, listOf(mirror!!)))
        assertTrue("brighter, the same mirror", ImageHash.samePlace(photo(room(1, light = 1.35f))!!, listOf(mirror)))
        assertFalse("the front door is not the mirror", ImageHash.samePlace(photo(room(2))!!, listOf(mirror)))
        // A file that is no picture gives no fingerprint, and is deleted too.
        PhotoPrint.file(app).writeText("not a picture")
        assertNull(runBlocking { PhotoPrint.take(app, Uri.fromFile(PhotoPrint.file(app))) })
        assertFalse(PhotoPrint.file(app).exists())
    }

    @Test fun theReferencesKeepTwoAndStartOverWhenTakenAgain() {
        val refs = PhotoReferences(app).apply { clear() }
        refs.add(1L, "욕실 거울", at = 10)
        refs.add(2L, "", at = 20)
        assertEquals(listOf(1L, 2L), refs.hashes)
        assertEquals("an empty name keeps the place's", "욕실 거울", refs.all.value[1].place)
        refs.add(3L, "현관", at = 30)
        assertEquals("past two, the oldest goes", listOf(2L, 3L), refs.hashes)
        assertEquals(listOf(2L, 3L), PhotoReferences(app).hashes)
        refs.add(-1L, "현관 신발장", replace = true)
        assertEquals("다시 찍기 starts over; a negative 64-bit value survives", listOf(-1L), PhotoReferences(app).hashes)
        refs.remove(0)
        assertTrue(PhotoReferences.hashesOrEmpty(app).isEmpty())
    }

    @Test fun threeWrongPhotosOrNoCameraBecomeSums() {
        val ref = 0L
        val attempts = PhotoAttempts(listOf(ref))
        assertEquals(PhotoAttempts.Outcome.Again(2), attempts.taken(-1L))
        assertEquals(PhotoAttempts.Outcome.Again(1), attempts.taken(-1L))
        assertEquals(PhotoAttempts.Outcome.Sums, attempts.taken(-1L))
        assertEquals(AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, true, attempts.wrong, attempts.failed, true))

        assertEquals("the place itself", PhotoAttempts.Outcome.Done, PhotoAttempts(listOf(ref)).taken(0b111L))
        val noPhoto = PhotoAttempts(listOf(ref))
        assertEquals("the camera came back empty", PhotoAttempts.Outcome.Sums, noPhoto.taken(null))
        assertEquals(AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, true, noPhoto.wrong, noPhoto.failed, true))
        assertEquals("no reference on this phone", AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, false, 0, false, true))
    }
}
