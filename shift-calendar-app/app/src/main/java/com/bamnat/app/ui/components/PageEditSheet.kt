package com.bamnat.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * [CUST-01] One row of 화면 꾸미기: a part of the page, whether it shows, and whether it can move within
 * its group. [isNew] marks a part an update added, so the user sees it is there.
 */
data class EditRow(
    val id: String,
    val label: String,
    val what: String,
    val shown: Boolean,
    val movable: Boolean = true,
    val isNew: Boolean = false,
)

/** [CUST-01] A group of rows that move among themselves, with where they sit on the page. */
data class EditGroup(val title: String, val rows: List<EditRow>)

/**
 * [CUST-01] 화면 꾸미기 for one page: a small picture of the page as it will look, then each part with a
 * switch and up and down buttons, then the page's own view options. Every change is saved and shown on
 * the page at once, so closing the sheet is all there is to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditSheet(
    title: String,
    groups: List<EditGroup>,
    /** The fixed middle of the page (the month, the tiles) the groups sit around, for the picture. */
    middle: String,
    onShow: (id: String, shown: Boolean) -> Unit,
    onMove: (id: String, by: Int) -> Unit,
    onReset: () -> Unit,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
    options: @Composable ColumnScope.() -> Unit = {},
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) { Text("처음대로") }
            }
            Text("바꾸는 대로 저장되고 화면에 바로 보여요. 아래 미리보기에서 순서를 확인하세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LayoutPreview(groups, middle)
            groups.forEach { group ->
                Text(group.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                group.rows.forEachIndexed { i, row ->
                    EditRowLine(row, first = i == 0, last = i == group.rows.lastIndex, onShow = { onShow(row.id, it) }, onMove = { onMove(row.id, it) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            options()
            TextButton(onClick = onAsk, modifier = Modifier.heightIn(min = 48.dp)) { Text("원하는 배치나 기능이 없나요? 바로 말해 주세요") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EditRowLine(row: EditRow, first: Boolean, last: Boolean, onShow: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.isNew) {
                    Spacer(Modifier.width(6.dp))
                    Text("새 기능", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(row.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (row.movable) {
            IconButton(onClick = { onMove(-1) }, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, "${row.label} 위로") }
            IconButton(onClick = { onMove(1) }, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, "${row.label} 아래로") }
        }
        Switch(checked = row.shown, onCheckedChange = onShow, modifier = Modifier.padding(start = 4.dp))
    }
}

/** [CUST-01] The page in small: the shown parts in order around its fixed middle, hidden ones left out. */
@Composable
private fun LayoutPreview(groups: List<EditGroup>, middle: String) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(176.dp)
                .border(2.dp, scheme.outline, RoundedCornerShape(18.dp))
                .padding(8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val (above, below) = groups.partition { it.title.startsWith("위") }
            above.flatMap { it.rows }.filter { it.shown }.forEach { Block(it.label, scheme.secondaryContainer) }
            Block(middle, scheme.primaryContainer, tall = true)
            below.flatMap { it.rows }.filter { it.shown }.forEach { Block(it.label, scheme.secondaryContainer) }
        }
    }
}

@Composable
private fun Block(label: String, color: Color, tall: Boolean = false) {
    Box(Modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 22.dp).background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (tall) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
