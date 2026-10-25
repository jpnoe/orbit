package com.orbit.wellness.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Clears focus and hides the soft keyboard. */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
}
