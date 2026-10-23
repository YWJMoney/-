package com.bamnat.core

import com.bamnat.core.alarm.AlarmMission
import com.bamnat.core.alarm.ImageHash
import com.bamnat.core.alarm.Missions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** [ALRM-04] 증명 사진: the fingerprint keeps a place through light and a slightly tilted hand, and tells places apart. */
class ImageHashTest {
    private val n = ImageHash.SIZE

    /** A made-up room, drawn large and shrunk the way the app shrinks a photo: a wall's light falling off, and blocks (a mirror, a door, a shelf). */
    private fun room(seed: Int, size: Int = 128): IntArray {
        val r = Random(seed)
        val base = r.nextInt(60, 160)
        val slope = r.nextDouble(-1.0, 1.0)
        val img = IntArray(size * size) { i -> (base + slope * (i % size - size / 2) * 0.6 + (i / size) * 0.2).roundToInt() }
        repeat(6) {
            val w = r.nextInt(size / 6, size / 2)
            val h = r.nextInt(size / 6, size / 2)
            val x0 = r.nextInt(0, size - w)
            val y0 = r.nextInt(0, size - h)
            val v = r.nextInt(0, 256)
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) img[y * size + x] = v
        }
        return img
    }

    /** [img] seen at [degrees] off and moved [dx] pixels, then shrunk to 32×32, as a photo taken a second time is. */
    private fun shot(img: IntArray, size: Int = 128, degrees: Double = 0.0, dx: Int = 0, light: Double = 1.0, noise: Int = 0, seed: Int = 1): IntArray {
        val a = Math.toRadians(degrees)
        val c = size / 2.0
        val r = Random(seed)
        val turned = IntArray(size * size) { i ->
            val x = i % size - c
            val y = i / size - c
            val sx = (cos(a) * x + sin(a) * y + c + dx).roundToInt().coerceIn(0, size - 1)
            val sy = (-sin(a) * x + cos(a) * y + c).roundToInt().coerceIn(0, size - 1)
            (img[sy * size + sx] * light + (if (noise > 0) r.nextInt(-noise, noise + 1) else 0)).roundToInt().coerceIn(0, 255)
        }
        val k = size / n
        return IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            var s = 0
            for (yy in 0 until k) for (xx in 0 until k) s += turned[(y * k + yy) * size + x * k + xx]
            s / (k * k)
        }
    }

    @Test fun theSamePictureIsZeroAndDistanceIsSymmetric() {
        val a = ImageHash.of(shot(room(1)))
        val b = ImageHash.of(shot(room(2)))
        assertEquals(0, ImageHash.distance(a, a))
        assertEquals(ImageHash.distance(a, b), ImageHash.distance(b, a))
        assertEquals(64, ImageHash.distance(0L, -1L))
    }

    /** A second photo of the same room: dimmer, brighter, a little turned and moved, with sensor noise. */
    private fun retakes(seed: Int): List<IntArray> {
        val place = room(seed)
        return listOf(
            shot(place, light = 0.6), shot(place, light = 1.4), shot(place, degrees = 3.0, dx = 2, noise = 8, seed = seed),
            shot(place, degrees = -3.0, dx = -2, light = 0.7, noise = 8, seed = seed + 50),
        )
    }

    @Test fun lightAndASlightlyDifferentHandKeepThePlace() {
        var tries = 0
        var passed = 0
        for (seed in 1..60) {
            val ref = ImageHash.of(shot(room(seed)))
            for (again in retakes(seed)) {
                tries++
                if (ImageHash.samePlace(ImageHash.of(again), listOf(ref))) passed++
            }
            // Light alone never fails: dHash compares neighbours, not brightness.
            assertTrue(ImageHash.distance(ref, ImageHash.of(shot(room(seed), light = 0.6))) <= ImageHash.SAME_PLACE)
        }
        // A rare miss is a retake, and the mission gives [Missions.PHOTO_TRIES] of them.
        assertTrue("$passed of $tries retakes pass", passed * 100 >= tries * 97)
    }

    @Test fun anotherPlaceIsFarther() {
        var near = 0
        for (seed in 1..60) {
            val ref = ImageHash.of(shot(room(seed)))
            val other = ImageHash.of(shot(room(seed + 100)))
            if (ImageHash.distance(ref, other) <= ImageHash.SAME_PLACE) near++
        }
        assertEquals("another room passes for this one", 0, near)
    }

    @Test fun theNearestOfTwoReferencesCounts() {
        val day = ImageHash.of(shot(room(7)))
        val night = ImageHash.of(shot(room(8)))
        val photo = ImageHash.of(shot(room(8), light = 0.6))
        assertTrue(ImageHash.samePlace(photo, listOf(day, night)))
        assertFalse(ImageHash.samePlace(photo, listOf(day)))
        assertNull(ImageHash.nearest(photo, emptyList()))
        assertFalse("no reference, no match", ImageHash.samePlace(photo, emptyList()))
        assertEquals(128, ImageHash.grey(0xFF808080.toInt()))
    }

    @Test fun aPhotoMissionNeverLocksAnyoneIn() {
        assertEquals(AlarmMission.PHOTO, AlarmMission.of("photo"))
        assertEquals(AlarmMission.PHOTO, Missions.shown(AlarmMission.PHOTO, hasReference = true, wrongPhotos = 2, cameraFailed = false, motionSensor = true))
        assertEquals(AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, true, Missions.PHOTO_TRIES, false, true))
        assertEquals(AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, true, 0, cameraFailed = true, motionSensor = true))
        assertEquals("no reference photo", AlarmMission.MATH, Missions.shown(AlarmMission.PHOTO, false, 0, false, true))
        assertEquals(AlarmMission.MATH, Missions.shown(AlarmMission.SHAKE, true, 0, false, motionSensor = false))
        assertEquals(AlarmMission.SHAKE, Missions.shown(AlarmMission.SHAKE, false, 0, false, true))
    }
}
