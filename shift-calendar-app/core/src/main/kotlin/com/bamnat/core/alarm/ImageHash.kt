package com.bamnat.core.alarm

/**
 * [ALRM-04] 증명 사진's fingerprint: a picture's difference hash (dHash), 64 bits that say for each of 8×8 spots
 * whether it is brighter than the one to its right. Brightness differences rather than brightness, so a
 * lamp on or off moves few bits; 64 bits cannot be turned back into the picture. The app shrinks the photo
 * to [SIZE]×[SIZE] grey values (0~255) and this works on that alone, so it needs no Android.
 */
object ImageHash {
    /** The side of the grey picture the app hands in. */
    const val SIZE = 32

    /**
     * At most this many of the 64 bits may differ for two photos to count as the same place. Plan started at 14;
     * ImageHashTest's retakes (light ±40%, 3° and 2 px off, noise) reach 16 now and then and other rooms start at 24, so 18.
     */
    const val SAME_PLACE = 18

    /** Up to this many reference photos: one in daylight, one at night. */
    const val MAX_REFERENCES = 2

    /** [grey] is [width]×[height] values, row by row. */
    fun of(grey: IntArray, width: Int = SIZE, height: Int = SIZE): Long {
        require(width >= 9 && height >= 8 && grey.size == width * height) { "picture too small" }
        // Area averages on a 9×8 grid: each cell is the mean of the pixels that fall in it.
        val cells = DoubleArray(9 * 8)
        for (cy in 0 until 8) {
            val y0 = cy * height / 8
            val y1 = (cy + 1) * height / 8
            for (cx in 0 until 9) {
                val x0 = cx * width / 9
                val x1 = (cx + 1) * width / 9
                var sum = 0L
                for (y in y0 until y1) for (x in x0 until x1) sum += grey[y * width + x].coerceIn(0, 255)
                cells[cy * 9 + cx] = sum.toDouble() / ((y1 - y0) * (x1 - x0))
            }
        }
        var hash = 0L
        for (cy in 0 until 8) for (cx in 0 until 8) {
            hash = hash shl 1
            if (cells[cy * 9 + cx] > cells[cy * 9 + cx + 1]) hash = hash or 1L
        }
        return hash
    }

    /** How many of the 64 bits differ. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** The nearest of [references] to [photo], or null without any. */
    fun nearest(photo: Long, references: Collection<Long>): Int? = references.minOfOrNull { distance(photo, it) }

    /** Whether [photo] shows the place one of [references] shows. */
    fun samePlace(photo: Long, references: Collection<Long>, threshold: Int = SAME_PLACE): Boolean =
        (nearest(photo, references) ?: Int.MAX_VALUE) <= threshold

    /** The ITU-R BT.601 grey of one ARGB pixel, as the app reads it from the shrunk photo. */
    fun grey(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
