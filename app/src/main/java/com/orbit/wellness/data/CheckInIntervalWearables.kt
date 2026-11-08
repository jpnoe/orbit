package com.orbit.wellness.data

import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableUnit
import com.orbit.wellness.domain.checkin.IntervalWearableSelection

/**
 * Wearable rows a check-in history card can show.
 * Membership is [IntervalWearableSelection], shared with contextual evidence.
 * Values are not written onto the check-in.
 */
object CheckInIntervalWearables {
    val METRICS: List<String> = IntervalWearableSelection.METRICS

    fun forInterval(
        windowStartAt: Long?,
        windowEndAt: Long,
        observations: List<WearableObservation>,
    ): List<WearableObservation> {
        val start = windowStartAt ?: return emptyList()
        return IntervalWearableSelection.select(start, windowEndAt, observations)
    }
}

/** Count stays a number. Other units stay visible, such as "62 bpm". */
fun historyWearableAmount(observation: WearableObservation): String {
    val number = if (observation.value % 1.0 == 0.0) {
        observation.value.toLong().toString()
    } else {
        observation.value.toString()
    }
    return if (observation.unit == WearableUnit.COUNT) number else "$number ${observation.unit}"
}
