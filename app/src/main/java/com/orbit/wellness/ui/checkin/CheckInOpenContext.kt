package com.orbit.wellness.ui.checkin

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One saved check-in id is delivered to a single collector.
 * A later collector, such as a recomposed screen, does not receive it again.
 */
class CheckInOpenContextEvents {
    private val channel = Channel<String>(Channel.BUFFERED)
    val checkInIds: Flow<String> = channel.receiveAsFlow()

    fun onSaved(checkInId: String) {
        channel.trySend(checkInId)
    }
}

/**
 * Navigation happens only after the Room submission succeeds.
 * That submission includes the optional legacy mood sample when mood was set.
 */
fun checkInContextNavigationId(
    validationFailed: Boolean,
    savedCheckInId: String?,
    persistenceFailed: Boolean,
): String? {
    if (validationFailed || persistenceFailed || savedCheckInId.isNullOrBlank()) return null
    return savedCheckInId
}
