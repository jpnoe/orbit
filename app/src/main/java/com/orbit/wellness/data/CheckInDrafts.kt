package com.orbit.wellness.data

import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.roundToInt

/**
 * Draft check-in values are explicit observations only.
 * A missing or null entry is not set, even when the slider is showing an anchor.
 */
object CheckInDrafts {
    const val NEUTRAL_ANCHOR = 5

    fun visualAnchor(axisId: String, moodAnchor: Float): Int {
        val anchor = if (axisId == DefaultCheckInAxes.MOOD) {
            moodAnchor
        } else {
            NEUTRAL_ANCHOR.toFloat()
        }
        return anchor.roundToInt().coerceIn(0, 10)
    }

    fun toAxisInputs(drafts: Map<String, Int?>): List<CheckInAxisInput> {
        return drafts.mapNotNull { (axisId, value) ->
            value?.let { CheckInAxisInput(axisId = axisId, value = it) }
        }
    }
}
