package com.orbit.wellness.data.biometrics

import com.orbit.wellness.data.local.WearableObservation

/**
 * Result of one interval import. Manual and daily syncs keep [BiometricRepository.SyncResult].
 */
sealed interface IntervalCaptureOutcome {
    data class Captured(val observations: List<WearableObservation>) : IntervalCaptureOutcome

    /** Health Connect answered, and this interval had no observations. */
    data object Empty : IntervalCaptureOutcome

    /** Health Connect is missing, or read permission has not been granted. */
    data object Unavailable : IntervalCaptureOutcome

    data class Failed(val message: String) : IntervalCaptureOutcome
}
