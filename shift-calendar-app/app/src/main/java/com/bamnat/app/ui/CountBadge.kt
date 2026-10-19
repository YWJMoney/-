package com.bamnat.app.ui

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * [UX-06] A count on an icon (new answers on 설정, memos in the bin). At the largest text sizes the
 * number grew over the icon it marks, so it keeps the icon's size; TalkBack reads [description] instead
 * of a bare number.
 */
@Composable
internal fun IconCountBadge(count: Int, description: String, cap: Int = 99) {
    val size = with(LocalDensity.current) { 11.dp.toSp() }
    Badge(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(if (count > cap) "$cap+" else "$count", fontSize = size, lineHeight = size)
    }
}
