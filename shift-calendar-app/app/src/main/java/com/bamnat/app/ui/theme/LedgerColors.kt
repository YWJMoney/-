package com.bamnat.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme

/**
 * [LEDG-10] Colors for the ledger's categories and accounts on charts and avatars. Twelve hues far
 * enough apart to tell slices of a donut apart, each readable as a chip on white and on the dark
 * surface; the dark set is lighter so it keeps 3:1 against the dark background.
 */
object LedgerColors {
    private val light = listOf(
        0xFFE8590C, 0xFF1C7ED6, 0xFF2B8A3E, 0xFFAE3EC9, 0xFFF59F00, 0xFF0B7285,
        0xFFD6336C, 0xFF5F3DC4, 0xFF5C940D, 0xFF1098AD, 0xFFC92A2A, 0xFF495057,
    ).map(::Color)
    private val dark = listOf(
        0xFFFF922B, 0xFF4DABF7, 0xFF51CF66, 0xFFDA77F2, 0xFFFFD43B, 0xFF3BC9DB,
        0xFFF783AC, 0xFF9775FA, 0xFF94D82D, 0xFF66D9E8, 0xFFFF8787, 0xFFADB5BD,
    ).map(::Color)

    /** "기타" and anything left over are grey, so the named slices stand out. */
    private const val GREY = 11

    /** The palette for the current theme, read from the surface so it follows the app's light or dark choice. */
    @Composable
    @ReadOnlyComposable
    fun palette(): List<Color> = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light

    /** A steady color for [name]: built-in categories keep their place in [order], others a hash of the name. */
    @Composable
    @ReadOnlyComposable
    fun of(name: String, order: List<String>): Color {
        val p = palette()
        if (name == "기타") return p[GREY]
        val i = order.indexOf(name)
        return p[(if (i >= 0) i else (name.hashCode() and 0x7fffffff)) % GREY]
    }

    @Composable
    @ReadOnlyComposable
    fun at(index: Int): Color = palette().let { it[index.mod(GREY)] }

    @Composable
    @ReadOnlyComposable
    fun other(): Color = palette()[GREY]
}
