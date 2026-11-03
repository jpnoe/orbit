package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus

data class PriorCheckIn(
    val id: String,
    val localDate: String,
    val recordedAt: Long,
)

data class ResolvedContextWindow(
    val previousCheckInId: String?,
    val windowStartAt: Long,
    val windowEndAt: Long,
    val startBasis: String,
)

/**
 * Chooses the reporting interval for one new check-in.
 * Callers persist the result and do not run it again for that check-in.
 */
object CheckInContextBoundary {
    fun resolve(
        checkInId: String,
        localDate: String,
        recordedAt: Long,
        dayStartAt: Long,
        sameDayCheckIns: List<PriorCheckIn>,
        wakeTimeAt: Long?,
    ): ResolvedContextWindow {
        val previous = sameDayCheckIns
            .asSequence()
            .filter { candidate ->
                candidate.id != checkInId &&
                    candidate.localDate == localDate &&
                    candidate.recordedAt < recordedAt
            }
            .maxWithOrNull(compareBy<PriorCheckIn> { it.recordedAt }.thenByDescending { it.id })
        val (startAt, previousId, basis) = when {
            previous != null -> Triple(
                previous.recordedAt,
                previous.id,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            )
            wakeTimeAt != null && wakeTimeAt <= recordedAt -> Triple(
                wakeTimeAt,
                null,
                CheckInContextStartBasis.WAKE_TIME,
            )
            else -> Triple(
                dayStartAt,
                null,
                CheckInContextStartBasis.DAY_START,
            )
        }
        require(startAt <= recordedAt) {
            "Context window start must not be later than the check-in"
        }
        return ResolvedContextWindow(
            previousCheckInId = previousId,
            windowStartAt = startAt,
            windowEndAt = recordedAt,
            startBasis = basis,
        )
    }

    fun defaultCategories(checkInId: String): List<CheckInContextCategory> {
        return CheckInContextCategories.ALL.map { category ->
            CheckInContextCategory(
                checkInId = checkInId,
                category = category,
                status = CheckInContextStatus.NONE,
                reviewedAt = null,
            )
        }
    }
}
