package com.bamnat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * [UX-08] A long form (many fields, a keyboard) in a sheet from the bottom instead of a dialog in the middle:
 * the whole width, the fields above the keyboard, the buttons where the thumb is (notes/1.22-285/E.md 5장 2번).
 * The same slots as AlertDialog, so a form moves over by its first word; [text] scrolls itself as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            if (title != null) {
                Box(Modifier.padding(bottom = 12.dp).semantics { heading() }) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                }
            }
            if (text != null) {
                Box(Modifier.weight(1f, fill = false)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
