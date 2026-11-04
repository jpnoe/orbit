package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStartBasis

/**
 * Counts over a [CheckInAnalysisDatasetV2].
 * These describe the projected records. They are not effects, causes, or quality scores.
 */
data class CheckInAnalysisSummary(
    val stateObservationCount: Int,
    val completedIntervalCount: Int,
    val incompleteIntervalCount: Int,
    /** Previous check-in resolved, including links with no shared axis. */
    val linkedTransitionCount: Int,
    /** Links with at least one axis recorded at both endpoints. */
    val measuredTransitionCount: Int,
    val wakeOrDayStartIntervalCount: Int,
    /** States in which that core axis was not recorded. Conflicts are excluded. */
    val missingCoreAxisCounts: Map<String, Int>,
    /**
     * Category rows on projected intervals.
     * [CategoryStatusCounts.none] is observed emptiness, not a missing row.
     */
    val categoryCounts: Map<String, CategoryStatusCounts>,
    val issueCount: Int,
)

data class CategoryStatusCounts(
    val none: Int,
    val entries: Int,
    val unreviewed: Int,
    val absent: Int,
    val conflicting: Int,
)

fun summarizeCheckInAnalysis(dataset: CheckInAnalysisDatasetV2): CheckInAnalysisSummary {
    val missing = linkedMapOf<String, Int>()
    for (axisId in CheckInAnalysisDatasetV2.CORE_AXIS_IDS) {
        missing[axisId] = dataset.states.count { state ->
            axisId !in state.axes && axisId !in state.conflictingAxisIds
        }
    }
    val counts = linkedMapOf<String, CategoryAccumulator>()
    for (category in CheckInContextCategories.ALL) {
        counts[category] = CategoryAccumulator()
    }
    for (interval in dataset.intervals) {
        for (category in interval.categoriesInOrder()) {
            counts.getValue(category.category).add(category.kind)
        }
    }
    return CheckInAnalysisSummary(
        stateObservationCount = dataset.states.size,
        completedIntervalCount = dataset.completedIntervals().size,
        incompleteIntervalCount = dataset.intervals.count { !it.isComplete },
        linkedTransitionCount = dataset.transitions.size,
        measuredTransitionCount = dataset.transitions.count { it.hasMeasuredDelta },
        wakeOrDayStartIntervalCount = dataset.intervals.count { interval ->
            interval.startBasis == CheckInContextStartBasis.WAKE_TIME ||
                interval.startBasis == CheckInContextStartBasis.DAY_START
        },
        missingCoreAxisCounts = missing,
        categoryCounts = counts.mapValues { (_, accumulator) -> accumulator.toCounts() },
        issueCount = dataset.issues.size,
    )
}

private class CategoryAccumulator {
    var none: Int = 0
    var entries: Int = 0
    var unreviewed: Int = 0
    var absent: Int = 0
    var conflicting: Int = 0

    fun add(kind: CategoryObservationKindV2) {
        when (kind) {
            CategoryObservationKindV2.NONE -> none += 1
            CategoryObservationKindV2.ENTRIES -> entries += 1
            CategoryObservationKindV2.UNREVIEWED -> unreviewed += 1
            CategoryObservationKindV2.ABSENT -> absent += 1
            CategoryObservationKindV2.CONFLICTING -> conflicting += 1
        }
    }

    fun toCounts(): CategoryStatusCounts = CategoryStatusCounts(
        none = none,
        entries = entries,
        unreviewed = unreviewed,
        absent = absent,
        conflicting = conflicting,
    )
}
