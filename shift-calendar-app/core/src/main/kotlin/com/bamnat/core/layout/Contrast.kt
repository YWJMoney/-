package com.bamnat.core.layout

import kotlin.math.pow

/**
 * [WEAR-02] One contrast rule for the phone, its widgets and the watch (I 리뷰 P24): which ink reads on a colour,
 * and how a shift's colour is lifted to read on the watch's black. Before, the phone ([contentColorOn]), the watch
 * screen (`inkOn`) and the tile (`readable`) each had their own formula and only the phone's was tested.
 * WCAG 2.x relative luminance and contrast ratio (https://www.w3.org/TR/WCAG21/#dfn-relative-luminance).
 */
object Contrast {
    /** The app's and the watch's dark ink, used where white would read worse (오후 #F2994A, 대근 #0E9F6E). */
    const val DARK_INK = 0xFF151927L
    const val BLACK = 0xFF000000L
    const val WHITE = 0xFFFFFFFFL

    fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** White or [dark], whichever stands out more on [background]. */
    fun inkOn(background: Long, dark: Long = DARK_INK): Long =
        if (ratio(background, dark) > ratio(background, WHITE)) dark else WHITE

    /**
     * A shift's colour as text on black: kept when it already reads at 4.5:1 or better, else mixed toward white
     * a step at a time until it does (a dark purple 야간 becomes a light lilac, still the same hue).
     */
    fun onBlack(argb: Long, min: Double = 4.5): Long {
        var c = argb or 0xFF000000L
        var step = 0
        while (ratio(c, BLACK) < min && step < 20) {
            c = mix(c, WHITE, 0.1)
            step++
        }
        return c
    }

    private fun mix(a: Long, b: Long, t: Double): Long {
        fun ch(v: Long, s: Int) = ((v shr s) and 0xFF).toInt()
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
