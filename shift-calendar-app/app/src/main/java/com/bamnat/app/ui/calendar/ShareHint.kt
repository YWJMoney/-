package com.bamnat.app.ui.calendar

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * [UX-03] Hints shown once and never again once read: kept in a small file of their own on the phone,
 * apart from the settings a backup carries, since they are about this phone's first days only.
 */
internal class HintStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hints", Context.MODE_PRIVATE)

    fun done(key: String): Boolean = prefs.getBoolean(key, false)

    fun markDone(key: String) {
        prefs.edit { putBoolean(key, true) }
    }

    companion object {
        /** The calendar's 공유 was pointed out, or used. */
        const val SHARE = "share_hint"

        /** [CAL-17] A long press on a date opens 빠른 설정: said once on the first tap, or found already. */
        const val LONG_PRESS = "long_press_hint"
    }
}

/**
 * [UX-03] The first time a calendar has shifts and has never been shared, a quiet card under the bar
 * points up at 공유: people did not find it. Closed with ×, or gone once 공유 is used.
 */
@Composable
internal fun ShareHintCard(onShare: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondaryContainer
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        // A small point under 공유, so the card reads as being about that button.
        Box(Modifier.fillMaxWidth().padding(end = 26.dp), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(width = 16.dp, height = 8.dp).drawBehind {
                drawPath(Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }, color)
            })
        }
        // The whole card opens 공유; × puts the hint away for good.
        Surface(onClick = onShare, shape = MaterialTheme.shapes.large, color = color, contentColor = onColor) {
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Share, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text("‘공유’로 근무표를 보낼 수 있어요", style = MaterialTheme.typography.titleSmall)
                    Text("동료·가족에게 이미지나 링크로 보내요. 링크는 앱이 없어도 열려요.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "공유 안내 닫기") }
            }
        }
    }
}
