package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.domain.checkin.AnalysisIssue
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.CategoryObservationKindV2
import com.orbit.wellness.domain.checkin.TemporalWindowProjector
import java.time.ZoneId

enum class InsightsPhase {
    EMPTY,
    ONE_OBSERVATION,
    COLLECTING,
}

data class CoreAxisCoverage(
    val axisId: String,
    val recordedCount: Int,
    val missingCount: Int,
    val conflictingCount: Int,
)

/**
 * Counts the Insights screen can show. This describes the projection.
 * It does not score the person or decide that the data is good enough.
 */
data class InsightsContent(
    val phase: InsightsPhase,
    val stateObservationCount: Int,
    val completedIntervalCount: Int,
    val linkedTransitionCount: Int,
    val measuredTransitionCount: Int,
    val earliestRecordedAt: Long?,
    val latestRecordedAt: Long?,
    val coreCoverage: List<CoreAxisCoverage>,
    /** Distinct non-blank emotion names, in the order states were recorded. */
    val recordedEmotions: List<String>,
    val observationsWithoutEmotion: Int,
    val intervalsContainingActivity: Int,
    val intervalsContainingWorkout: Int,
    val intervalsContainingMeals: Int,
    val intervalsContainingUsage: Int,
    val momentObservations: Int,
    val calendarDaysRepresented: Int,
    val fullyContainedIntervals: Int,
    val boundaryOverlappingIntervals: Int,
    val issues: List<AnalysisIssue>,
)

fun CheckInAnalysisSnapshot.toInsightsContent(
    zone: ZoneId = ZoneId.systemDefault(),
): InsightsContent {
    val states = dataset.states
    val calendarDays = TemporalWindowProjector.calendarDayCoverage(dataset, zone)
    val phase = when (states.size) {
        0 -> InsightsPhase.EMPTY
        1 -> InsightsPhase.ONE_OBSERVATION
        else -> InsightsPhase.COLLECTING
    }
    val emotions = linkedSetOf<String>()
    var withoutEmotion = 0
    for (state in states) {
        if (state.emotion.isBlank()) {
            withoutEmotion += 1
        } else {
            emotions += state.emotion
        }
    }
    return InsightsContent(
        phase = phase,
        stateObservationCount = summary.stateObservationCount,
        completedIntervalCount = summary.completedIntervalCount,
        linkedTransitionCount = summary.linkedTransitionCount,
        measuredTransitionCount = summary.measuredTransitionCount,
        earliestRecordedAt = states.minOfOrNull { it.recordedAt },
        latestRecordedAt = states.maxOfOrNull { it.recordedAt },
        coreCoverage = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            CoreAxisCoverage(
                axisId = axisId,
                recordedCount = states.count { axisId in it.axes },
                missingCount = states.count {
                    axisId !in it.axes && axisId !in it.conflictingAxisIds
                },
                conflictingCount = states.count { axisId in it.conflictingAxisIds },
            )
        },
        recordedEmotions = emotions.toList(),
        observationsWithoutEmotion = withoutEmotion,
        intervalsContainingActivity = dataset.intervals.count {
            it.activity.kind == CategoryObservationKindV2.ENTRIES && it.activity.activities.isNotEmpty()
        },
        intervalsContainingWorkout = dataset.intervals.count {
            it.workout.kind == CategoryObservationKindV2.ENTRIES && it.workout.workouts.isNotEmpty()
        },
        intervalsContainingMeals = dataset.intervals.count {
            it.diet.kind == CategoryObservationKindV2.ENTRIES && it.diet.meals.isNotEmpty()
        },
        intervalsContainingUsage = dataset.intervals.count {
            it.usage.kind == CategoryObservationKindV2.ENTRIES && it.usage.usage.isNotEmpty()
        },
        momentObservations = calendarDays.momentObservations,
        calendarDaysRepresented = calendarDays.calendarDaysRepresented,
        fullyContainedIntervals = calendarDays.fullyContainedIntervals,
        boundaryOverlappingIntervals = calendarDays.boundaryOverlappingIntervals,
        issues = issues,
    )
}
