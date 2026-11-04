package com.orbit.wellness.data

/**
 * Enqueues one Health Connect interval import for a completed check-in.
 * The implementation must not read Health Connect itself.
 */
fun interface WearableCaptureScheduler {
    fun scheduleIntervalCapture(checkInId: String)
}

object NoWearableCapture : WearableCaptureScheduler {
    override fun scheduleIntervalCapture(checkInId: String) = Unit
}

/** Keeps the first request for a check-in and ignores a repeated completion. */
class KeepFirstWearableCapture(
    private val enqueue: (uniqueName: String, checkInId: String) -> Unit = { _, _ -> },
) : WearableCaptureScheduler {
    val requestedIds = linkedSetOf<String>()

    override fun scheduleIntervalCapture(checkInId: String) {
        val uniqueName = intervalCaptureWorkName(checkInId)
        if (requestedIds.add(checkInId)) {
            enqueue(uniqueName, checkInId)
        }
    }
}

fun intervalCaptureWorkName(checkInId: String): String = "wearable_interval_capture:$checkInId"
