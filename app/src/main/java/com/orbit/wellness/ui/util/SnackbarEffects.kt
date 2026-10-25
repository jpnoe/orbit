package com.orbit.wellness.ui.util

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Collects one-shot snackbar strings while the host lifecycle is at least [Lifecycle.State.STARTED].
 * Back this with a [kotlinx.coroutines.channels.Channel] (or similar) so confirmation fires
 * immediately on the current screen instead of waiting for a later recomposition.
 */
@Composable
fun CollectSnackbarMessages(
    messages: Flow<String>,
    snackbarHostState: SnackbarHostState,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(messages, snackbarHostState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collect { message ->
                if (message.isNotBlank()) {
                    snackbarHostState.showBriefMessage(message)
                }
            }
        }
    }
}

/**
 * Collects one-shot save confirmations and shows a localized [message].
 */
@Composable
fun CollectSavedFeedback(
    pulses: Flow<Unit>,
    message: String,
    snackbarHostState: SnackbarHostState,
    onPulse: () -> Unit = {},
) {
    val messageState = rememberUpdatedState(message)
    val onPulseState = rememberUpdatedState(onPulse)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pulses, snackbarHostState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            pulses.collect {
                snackbarHostState.showBriefMessage(messageState.value)
                onPulseState.value()
            }
        }
    }
}

suspend fun SnackbarHostState.showBriefMessage(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message = message, duration = SnackbarDuration.Short)
}
