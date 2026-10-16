package com.bamnat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bamnat.app.AppContainer
import com.bamnat.app.appContainer

/** Creates (or reuses) a ViewModel for the current navigation entry with access to [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** A snackbar message with an optional undo action. */
data class UndoMessage(val message: String, val undo: (suspend () -> Unit)? = null)
