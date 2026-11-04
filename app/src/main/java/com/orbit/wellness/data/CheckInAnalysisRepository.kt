package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInDao
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.domain.checkin.AnalysisIssue
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetBuilder
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.CheckInAnalysisSummary
import com.orbit.wellness.domain.checkin.summarizeCheckInAnalysis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Read-only live projection of schema 22 check-ins.
 *
 * Child tables already have table-wide observers, and those are what this reads.
 * [CheckInDao.observeRecent] is capped and [CheckInDao.observeCheckInsBetween]
 * is the history date window, so check-ins use [CheckInDao.observeAll] instead.
 * The builder stays a pure function. Nothing here is written back to Room,
 * read from the ten-axis snapshot tables, or sent off the device.
 */
class CheckInAnalysisRepository(
    private val checkInDao: CheckInDao,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeSnapshot(): Flow<CheckInAnalysisSnapshot> {
        return observeSources().toAnalysisSnapshots(computation)
    }

    private fun observeSources(): Flow<CheckInAnalysisSources> {
        val states = combine(
            checkInDao.observeAll(),
            checkInDao.observeAxisValues(),
            checkInDao.observeContextWindows(),
            checkInDao.observeContextCategories(),
        ) { checkIns, axisValues, windows, categories ->
            StateRows(checkIns, axisValues, windows, categories)
        }
        val context = combine(
            checkInDao.observeActivityItems(),
            checkInDao.observeWorkoutItems(),
            checkInDao.observeMeals(),
            checkInDao.observeMealFoods(),
            checkInDao.observeUsageItems(),
        ) { activities, workouts, meals, foods, usage ->
            ContextRows(activities, workouts, meals, foods, usage)
        }
        return combine(states, context) { state, rows ->
            CheckInAnalysisSources(
                checkIns = state.checkIns,
                axisValues = state.axisValues,
                windows = state.windows,
                categories = state.categories,
                activities = rows.activities,
                workouts = rows.workouts,
                meals = rows.meals,
                foods = rows.foods,
                usage = rows.usage,
            )
        }
    }
}

data class CheckInAnalysisSnapshot(
    val dataset: CheckInAnalysisDatasetV2,
    val summary: CheckInAnalysisSummary,
) {
    val issues: List<AnalysisIssue>
        get() = dataset.issues
}

internal data class CheckInAnalysisSources(
    val checkIns: List<CheckInRecord> = emptyList(),
    val axisValues: List<CheckInAxisValue> = emptyList(),
    val windows: List<CheckInContextWindow> = emptyList(),
    val categories: List<CheckInContextCategory> = emptyList(),
    val activities: List<CheckInActivityItem> = emptyList(),
    val workouts: List<CheckInWorkoutItem> = emptyList(),
    val meals: List<CheckInMeal> = emptyList(),
    val foods: List<CheckInMealFoodItem> = emptyList(),
    val usage: List<CheckInUsageItem> = emptyList(),
) {
    fun canonical(): CheckInAnalysisSources {
        return CheckInAnalysisSources(
            checkIns = checkIns.sortedWith(compareBy({ it.recordedAt }, { it.id })),
            axisValues = axisValues.sortedWith(compareBy({ it.checkInId }, { it.axisId }, { it.value })),
            windows = windows.sortedBy { it.checkInId },
            categories = categories.sortedWith(compareBy({ it.checkInId }, { it.category })),
            activities = activities.sortedWith(compareBy({ it.checkInId }, { it.id })),
            workouts = workouts.sortedWith(compareBy({ it.checkInId }, { it.id })),
            meals = meals.sortedWith(compareBy({ it.checkInId }, { it.sortOrder }, { it.id })),
            foods = foods.sortedWith(compareBy({ it.mealId }, { it.sortOrder }, { it.id })),
            usage = usage.sortedWith(compareBy({ it.checkInId }, { it.id })),
        )
    }
}

internal fun projectCheckInAnalysis(sources: CheckInAnalysisSources): CheckInAnalysisSnapshot {
    val dataset = CheckInAnalysisDatasetBuilder.build(
        checkIns = sources.checkIns,
        axisValues = sources.axisValues,
        windows = sources.windows,
        categories = sources.categories,
        activities = sources.activities,
        workouts = sources.workouts,
        meals = sources.meals,
        foods = sources.foods,
        usage = sources.usage,
    )
    return CheckInAnalysisSnapshot(
        dataset = dataset,
        summary = summarizeCheckInAnalysis(dataset),
    )
}

internal fun Flow<CheckInAnalysisSources>.toAnalysisSnapshots(
    computation: CoroutineDispatcher,
): Flow<CheckInAnalysisSnapshot> {
    return map { it.canonical() }
        .distinctUntilChanged()
        .map { projectCheckInAnalysis(it) }
        .distinctUntilChanged()
        .flowOn(computation)
}

private data class StateRows(
    val checkIns: List<CheckInRecord>,
    val axisValues: List<CheckInAxisValue>,
    val windows: List<CheckInContextWindow>,
    val categories: List<CheckInContextCategory>,
)

private data class ContextRows(
    val activities: List<CheckInActivityItem>,
    val workouts: List<CheckInWorkoutItem>,
    val meals: List<CheckInMeal>,
    val foods: List<CheckInMealFoodItem>,
    val usage: List<CheckInUsageItem>,
)
