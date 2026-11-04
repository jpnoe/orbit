package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.CheckInUsageItem
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInAnalysisDatasetTest {
    @Test
    fun explicitScoresKeepRawValuesAndLeaveMissingAxesEmpty() {
        val state = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 1_000)),
            axisValues = listOf(
                axis("a", DefaultCheckInAxes.MOOD, 0),
                axis("a", DefaultCheckInAxes.ENERGY, 10),
                axis("a", DefaultCheckInAxes.ANXIETY, 10),
                axis("a", "focus", 3),
            ),
            windows = listOf(window("a", 1_000)),
            categories = noneCategories("a"),
        ).states.single()

        val mood = checkNotNull(state.core.mood)
        val energy = checkNotNull(state.core.energy)
        val anxiety = checkNotNull(state.core.anxiety)
        assertEquals(0, mood.raw)
        assertEquals(0.0, mood.normalized, 0.0)
        assertEquals(10, energy.raw)
        assertEquals(1.0, energy.normalized, 0.0)
        assertNull(state.core.confidence)
        assertEquals(10, anxiety.raw)
        assertEquals(1.0, anxiety.normalized, 0.0)
        assertFalse(state.axes.containsKey(DefaultCheckInAxes.CONFIDENCE))
        assertEquals(3, state.axes.getValue("focus").raw)
        assertEquals(0.3, state.axes.getValue("focus").normalized, 0.0)
        assertEquals(
            listOf(
                DefaultCheckInAxes.MOOD,
                DefaultCheckInAxes.ENERGY,
                DefaultCheckInAxes.CONFIDENCE,
                DefaultCheckInAxes.ANXIETY,
            ),
            state.core.inOrder().map { it.first },
        )
        assertEquals(CheckInAnalysisDatasetV2.CORE_AXIS_IDS, state.core.inOrder().map { it.first })
        assertTrue(state.axes.values.none { it.normalized == 0.5 })
        assertTrue(state.conflictingAxisIds.isEmpty())
    }

    @Test
    fun aRecordedFiveIsPresentAndAMissingAxisIsNotFilledWithIt() {
        val state = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 1_000)),
            axisValues = listOf(axis("a", DefaultCheckInAxes.MOOD, 5)),
            windows = listOf(window("a", 1_000)),
            categories = noneCategories("a"),
        ).states.single()

        val mood = checkNotNull(state.core.mood)
        assertEquals(5, mood.raw)
        assertEquals(0.5, mood.normalized, 0.0)
        assertNull(state.core.energy)
        assertNull(state.core.confidence)
        assertNull(state.core.anxiety)
        assertEquals(setOf(DefaultCheckInAxes.MOOD), state.axes.keys)
    }

    @Test
    fun repeatedIdenticalAxisValueStaysPresentAndIsFlagged() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 1_000)),
            axisValues = listOf(
                axis("a", DefaultCheckInAxes.MOOD, 0),
                axis("a", DefaultCheckInAxes.MOOD, 0),
            ),
            windows = listOf(window("a", 1_000)),
            categories = noneCategories("a"),
        )
        val state = dataset.states.single()
        assertEquals(0, state.core.mood!!.raw)
        assertTrue(state.conflictingAxisIds.isEmpty())
        assertEquals(0, summarizeCheckInAnalysis(dataset).missingCoreAxisCounts.getValue(DefaultCheckInAxes.MOOD))
        val issue = dataset.issues.single()
        assertEquals(AnalysisIssueCode.DUPLICATE_AXIS_VALUE, issue.code)
        assertEquals(DefaultCheckInAxes.MOOD, issue.relatedId)
    }

    @Test
    fun disagreeingAxisValuesAreNotChosenAndAreNotCountedAsMissing() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 1_000)),
            axisValues = listOf(
                axis("a", DefaultCheckInAxes.MOOD, 4),
                axis("a", DefaultCheckInAxes.MOOD, 1),
            ),
            windows = listOf(window("a", 1_000)),
            categories = noneCategories("a"),
        )
        val state = dataset.states.single()
        assertNull(state.core.mood)
        assertFalse(state.axes.containsKey(DefaultCheckInAxes.MOOD))
        assertEquals(listOf(DefaultCheckInAxes.MOOD), state.conflictingAxisIds)
        assertEquals(0, summarizeCheckInAnalysis(dataset).missingCoreAxisCounts.getValue(DefaultCheckInAxes.MOOD))
        assertEquals("Axis mood values: 1, 4", dataset.issues.single().detail)
    }

    @Test
    fun deltasExistOnlyForAxesRecordedAtBothEndpoints() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("end", 2_000), checkIn("start", 1_000)),
            axisValues = listOf(
                axis("start", DefaultCheckInAxes.MOOD, 2),
                axis("start", DefaultCheckInAxes.ENERGY, 4),
                axis("start", DefaultCheckInAxes.ANXIETY, 1),
                axis("start", "focus", 3),
                axis("end", DefaultCheckInAxes.MOOD, 8),
                axis("end", DefaultCheckInAxes.CONFIDENCE, 5),
                axis("end", DefaultCheckInAxes.ANXIETY, 9),
                axis("end", "focus", 7),
            ),
            windows = listOf(
                window("start", 1_000, start = 0),
                window(
                    "end",
                    end = 2_000,
                    start = 1_000,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "start",
                ),
            ),
            categories = noneCategories("start") + noneCategories("end"),
        )
        val transition = dataset.transitions.single()
        assertEquals("start", transition.previous.checkInId)
        assertEquals("end", transition.ending.checkInId)
        assertEquals(setOf(DefaultCheckInAxes.MOOD, DefaultCheckInAxes.ANXIETY, "focus"), transition.deltas.keys)
        val moodDelta = checkNotNull(transition.coreDeltas.mood)
        val anxietyDelta = checkNotNull(transition.coreDeltas.anxiety)
        assertEquals(6, moodDelta.deltaRaw)
        assertEquals(0.6, moodDelta.deltaNormalized, 0.0)
        assertNull(transition.coreDeltas.energy)
        assertNull(transition.coreDeltas.confidence)
        assertEquals(1, anxietyDelta.previousRaw)
        assertEquals(9, anxietyDelta.endingRaw)
        assertEquals(8, anxietyDelta.deltaRaw)
        assertEquals(4, transition.deltas.getValue("focus").deltaRaw)
        assertTrue(dataset.issues.isEmpty())
    }

    @Test
    fun checkInsWithNoAxisValuesStillFormATransitionWithoutDeltas() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("end", 2_000), checkIn("start", 1_000)),
            windows = listOf(
                window("start", 1_000),
                window(
                    "end",
                    end = 2_000,
                    start = 1_000,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "start",
                ),
            ),
            categories = noneCategories("start") + noneCategories("end"),
        )
        assertEquals(listOf("start", "end"), dataset.states.map { it.checkInId })
        assertTrue(dataset.states.all { it.axes.isEmpty() })
        assertTrue(dataset.states.all { !it.hasObservedAxes && it.observedAxisCount == 0 && it.coreAxisCount == 0 })
        val transition = dataset.transitions.single()
        assertTrue(transition.deltas.isEmpty())
        assertFalse(transition.hasMeasuredDelta)
        assertEquals(0, transition.measuredAxisCount)
        assertTrue(transition.sharedAxisIds.isEmpty())
        assertNull(transition.coreDeltas.mood)
        val summary = summarizeCheckInAnalysis(dataset)
        assertEquals(1, summary.linkedTransitionCount)
        assertEquals(0, summary.measuredTransitionCount)
    }

    @Test
    fun wakeAndDayStartIntervalsDoNotInventAPreviousState() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("day", 2_000), checkIn("wake", 1_000)),
            axisValues = listOf(
                axis("wake", DefaultCheckInAxes.MOOD, 4),
                axis("day", DefaultCheckInAxes.MOOD, 6),
            ),
            windows = listOf(
                window("wake", 1_000, start = 100, basis = CheckInContextStartBasis.WAKE_TIME),
                window("day", 2_000, start = 0, basis = CheckInContextStartBasis.DAY_START),
            ),
            categories = noneCategories("wake") + noneCategories("day"),
        )
        assertTrue(dataset.transitions.isEmpty())
        assertEquals(2, dataset.intervals.size)
        assertTrue(dataset.intervals.all { it.previousCheckInId == null })
        assertEquals(1_000L - 100L, dataset.intervals.first { it.endingCheckInId == "wake" }.durationMillis)
        assertEquals(2_000L, dataset.intervals.first { it.endingCheckInId == "day" }.durationMillis)
        assertTrue(dataset.issues.isEmpty())
    }

    @Test
    fun noneIsObservedEmptinessAndUnreviewedStaysUnreviewed() {
        val categories = noneCategories("a").map { row ->
            if (row.category == CheckInContextCategories.ACTIVITY) {
                row.copy(status = CheckInContextStatus.UNREVIEWED)
            } else {
                row
            }
        }
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 2_500)),
            windows = listOf(window("a", end = 2_500, start = 1_000, completedAt = null)),
            categories = categories,
        )
        val interval = dataset.intervals.single()
        assertEquals(1_500L, interval.durationMillis)
        assertEquals(CategoryObservationKindV2.UNREVIEWED, interval.activity.kind)
        assertTrue(interval.activity.activities.isEmpty())
        assertEquals(CategoryObservationKindV2.NONE, interval.workout.kind)
        assertEquals(CategoryObservationKindV2.NONE, interval.diet.kind)
        assertEquals(CategoryObservationKindV2.NONE, interval.usage.kind)
        assertTrue(dataset.issues.isEmpty())
        val counts = summarizeCheckInAnalysis(dataset).categoryCounts
        assertEquals(0, counts.getValue(CheckInContextCategories.ACTIVITY).none)
        assertEquals(1, counts.getValue(CheckInContextCategories.ACTIVITY).unreviewed)
        assertEquals(1, counts.getValue(CheckInContextCategories.DIET).none)
        assertEquals(0, counts.getValue(CheckInContextCategories.DIET).absent)
    }

    @Test
    fun structuredContextKeepsIdsNamesDurationsFoodsAndUnparsedAmounts() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("a", 5_000)),
            windows = listOf(window("a", 5_000, completedAt = 9L)),
            categories = listOf(
                category("a", CheckInContextCategories.ACTIVITY, CheckInContextStatus.ENTRIES, reviewedAt = 9L),
                category("a", CheckInContextCategories.WORKOUT, CheckInContextStatus.ENTRIES, reviewedAt = 9L),
                category("a", CheckInContextCategories.DIET, CheckInContextStatus.ENTRIES, reviewedAt = 9L),
                category("a", CheckInContextCategories.USAGE, CheckInContextStatus.ENTRIES, reviewedAt = 9L),
            ),
            activities = listOf(
                activity("later", "a", "opt-read", "Read", reportedAt = 40),
                activity("earlier", "a", null, "Walk", reportedAt = 20),
            ),
            workouts = listOf(
                CheckInWorkoutItem(
                    id = "run",
                    checkInId = "a",
                    workoutOptionId = "opt-run",
                    workoutName = "Run",
                    durationMinutes = 30,
                    reportedAt = 30,
                ),
            ),
            meals = listOf(
                meal("snack", "a", CheckInMeal.SNACK, sortOrder = 1),
                meal("lunch", "a", CheckInMeal.MEDIUM, sortOrder = 0),
            ),
            foods = listOf(
                food("rice", "lunch", "opt-rice", "Rice", sortOrder = 1),
                food("chicken", "lunch", "opt-chicken", "Chicken", sortOrder = 0),
            ),
            usage = listOf(
                usage("tea", "a", "opt-tea", "Tea", SubstanceIntent.SUPPORTIVE.name, "2 drinks", reportedAt = 15),
                usage("water", "a", null, "Water", SubstanceIntent.COPING.name, "", reportedAt = 5),
            ),
        )
        val interval = dataset.intervals.single()
        assertEquals(
            listOf("earlier" to "Walk", "later" to "Read"),
            interval.activity.activities.map { it.id to it.name },
        )
        assertEquals("opt-read", interval.activity.activities.last().optionId)
        assertNull(interval.activity.activities.first().optionId)
        val workout = interval.workout.workouts.single()
        assertEquals("opt-run", workout.optionId)
        assertEquals("Run", workout.name)
        assertEquals(30, workout.durationMinutes)
        assertEquals(listOf(0, 1), interval.diet.meals.map { it.sortOrder })
        assertEquals(CheckInMeal.MEDIUM, interval.diet.meals.first().size)
        assertEquals(listOf("Chicken", "Rice"), interval.diet.meals.first().foods.map { it.name })
        assertEquals("opt-chicken", interval.diet.meals.first().foods.first().optionId)
        assertTrue(interval.diet.meals.last().foods.isEmpty())
        assertEquals(CheckInMeal.SNACK, interval.diet.meals.last().size)
        assertEquals(listOf("water", "tea"), interval.usage.usage.map { it.id })
        assertEquals("2 drinks", interval.usage.usage.last().amountText)
        assertEquals("", interval.usage.usage.first().amountText)
        assertEquals(SubstanceIntent.SUPPORTIVE.name, interval.usage.usage.last().intent)
        assertEquals(SubstanceIntent.COPING.name, interval.usage.usage.first().intent)
        assertEquals(
            setOf("id", "optionId", "name", "intent", "amountText", "reportedAt"),
            UsageObservationV2::class.java.declaredFields
                .map { it.name }
                .filterNot { it.contains("$") }
                .toSet(),
        )
        assertTrue(dataset.issues.isEmpty())
        assertEquals(CheckInAnalysisDatasetV2.PROTOCOL, dataset.protocol)
        assertEquals(CheckInAnalysisDatasetV2.SOURCE_ROOM_SCHEMA, dataset.sourceRoomSchema)
    }

    @Test
    fun incompleteIntervalsRemainInTheRawProjection() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("open", 1_000), checkIn("saved", 2_000)),
            windows = listOf(
                window("open", 1_000, completedAt = null),
                window("saved", 2_000, completedAt = 50),
            ),
            categories = noneCategories("open") + noneCategories("saved"),
        )
        assertEquals(listOf("open", "saved"), dataset.intervals.map { it.endingCheckInId })
        assertFalse(dataset.intervals.first().isComplete)
        assertNull(dataset.intervals.first().completedAt)
        assertEquals(listOf("saved"), dataset.completedIntervals().map { it.endingCheckInId })
        val summary = summarizeCheckInAnalysis(dataset)
        assertEquals(1, summary.completedIntervalCount)
        assertEquals(1, summary.incompleteIntervalCount)
    }

    @Test
    fun theSameRecordsProjectInTheSameOrder() {
        val forward = structuredRecords()
        val backward = StructuredRecords(
            checkIns = forward.checkIns.reversed(),
            axisValues = forward.axisValues.reversed(),
            windows = forward.windows.reversed(),
            categories = forward.categories.reversed(),
            activities = forward.activities.reversed(),
            workouts = forward.workouts.reversed(),
            meals = forward.meals.reversed(),
            foods = forward.foods.reversed(),
            usage = forward.usage.reversed(),
        )
        assertEquals(project(forward), project(backward))
        assertEquals(listOf("start", "end"), project(backward).states.map { it.checkInId })
    }

    @Test
    fun summaryCountsTheProjectedRecordsWithoutInterpretingThem() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("s2", 2_000), checkIn("s0", 500), checkIn("s1", 1_000)),
            axisValues = listOf(
                axis("s0", DefaultCheckInAxes.MOOD, 8),
                axis("s1", DefaultCheckInAxes.MOOD, 0),
                axis("s1", DefaultCheckInAxes.ENERGY, 10),
                axis("s1", DefaultCheckInAxes.ANXIETY, 0),
                axis("s1", "focus", 3),
                axis("s2", DefaultCheckInAxes.ENERGY, 10),
                axis("s2", DefaultCheckInAxes.CONFIDENCE, 5),
            ),
            windows = listOf(
                window("s2", end = 2_000, start = 1_000, basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN, previous = "s1", completedAt = null),
                window("s0", end = 500, start = 0, basis = CheckInContextStartBasis.DAY_START),
                window("s1", end = 1_000, start = 100, basis = CheckInContextStartBasis.WAKE_TIME),
            ),
            categories = noneCategories("s0") + noneCategories("s1") + noneCategories("s2").map { row ->
                if (row.category == CheckInContextCategories.ACTIVITY) {
                    row.copy(status = CheckInContextStatus.ENTRIES)
                } else {
                    row
                }
            },
            activities = listOf(activity("walk", "s2", "opt-walk", "Walk", reportedAt = 1)),
        )
        val summary = summarizeCheckInAnalysis(dataset)
        assertEquals(3, summary.stateObservationCount)
        assertEquals(2, summary.completedIntervalCount)
        assertEquals(1, summary.incompleteIntervalCount)
        assertEquals(1, summary.linkedTransitionCount)
        assertEquals(1, summary.measuredTransitionCount)
        assertEquals(2, summary.wakeOrDayStartIntervalCount)
        assertEquals(1, summary.missingCoreAxisCounts.getValue(DefaultCheckInAxes.MOOD))
        assertEquals(1, summary.missingCoreAxisCounts.getValue(DefaultCheckInAxes.ENERGY))
        assertEquals(2, summary.missingCoreAxisCounts.getValue(DefaultCheckInAxes.CONFIDENCE))
        assertEquals(2, summary.missingCoreAxisCounts.getValue(DefaultCheckInAxes.ANXIETY))
        assertEquals(2, summary.categoryCounts.getValue(CheckInContextCategories.ACTIVITY).none)
        assertEquals(1, summary.categoryCounts.getValue(CheckInContextCategories.ACTIVITY).entries)
        assertEquals(3, summary.categoryCounts.getValue(CheckInContextCategories.DIET).none)
        assertEquals(0, summary.categoryCounts.getValue(CheckInContextCategories.USAGE).entries)
        assertEquals(0, summary.issueCount)
        val transition = dataset.transitions.single()
        assertEquals("s2", transition.ending.checkInId)
        assertEquals(0, transition.coreDeltas.energy!!.deltaRaw)
        assertNull(transition.coreDeltas.mood)
        assertEquals(listOf("s0", "s1"), dataset.completedIntervals().map { it.endingCheckInId })
    }

    @Test
    fun malformedRelationshipsAreReportedInsteadOfRepaired() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(
                checkIn("conflict", 3_000),
                checkIn("mismatch", 5_000),
                checkIn("lonely", 9_000),
                checkIn("dup", 7_000),
                checkIn("early", 100),
                checkIn("later", 500),
                checkIn("child-dup", 8_000),
            ),
            axisValues = listOf(
                axis("conflict", DefaultCheckInAxes.MOOD, 1),
                axis("conflict", DefaultCheckInAxes.MOOD, 4),
                axis("mismatch", DefaultCheckInAxes.MOOD, 6),
                axis("no-check-in", DefaultCheckInAxes.ENERGY, 2),
                axis("early", DefaultCheckInAxes.MOOD, 4),
                axis("later", DefaultCheckInAxes.MOOD, 6),
            ),
            windows = listOf(
                window("conflict", 3_000),
                window(
                    "mismatch",
                    end = 4_500,
                    start = 2_000,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "gone",
                ),
                window("dup", 7_000, start = 0),
                window("dup", 7_000, start = 10),
                window(
                    "early",
                    end = 100,
                    start = 0,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "later",
                ),
                window("later", 500),
                window("child-dup", 8_000),
                CheckInContextWindow(
                    checkInId = "ghost",
                    previousCheckInId = null,
                    windowStartAt = 0,
                    windowEndAt = 1,
                    startBasis = CheckInContextStartBasis.DAY_START,
                ),
            ),
            categories = noneCategories("conflict").filter { it.category != CheckInContextCategories.ACTIVITY } +
                listOf(
                    category("conflict", CheckInContextCategories.ACTIVITY, CheckInContextStatus.NONE),
                    category("conflict", CheckInContextCategories.ACTIVITY, CheckInContextStatus.ENTRIES),
                ) +
                listOf(
                    category("mismatch", CheckInContextCategories.ACTIVITY, CheckInContextStatus.NONE),
                    category("mismatch", CheckInContextCategories.WORKOUT, CheckInContextStatus.ENTRIES),
                    category("mismatch", CheckInContextCategories.USAGE, CheckInContextStatus.NONE),
                ) +
                listOf(category("lonely", CheckInContextCategories.ACTIVITY, CheckInContextStatus.NONE)) +
                noneCategories("early") +
                noneCategories("later") +
                noneCategories("child-dup").map { row ->
                    if (row.category == CheckInContextCategories.ACTIVITY) {
                        row.copy(status = CheckInContextStatus.ENTRIES)
                    } else {
                        row
                    }
                },
            activities = listOf(
                activity("mismatch-walk", "mismatch", null, "Walk", reportedAt = 1),
                activity("lonely-walk", "lonely", null, "Walk", reportedAt = 1),
                activity("act-1", "child-dup", null, "Walk", reportedAt = 1),
                CheckInActivityItem(
                    id = "act-1",
                    checkInId = "child-dup",
                    activityOptionId = null,
                    activityName = "Run",
                    reportedAt = 2,
                ),
            ),
            foods = listOf(food("loose-food", "no-meal", null, "Rice", sortOrder = 0)),
        )

        assertEquals(
            listOf("early", "later", "conflict", "mismatch", "dup", "child-dup", "lonely"),
            dataset.states.map { it.checkInId },
        )
        assertNull(dataset.states.first { it.checkInId == "conflict" }.core.mood)
        assertEquals(listOf(DefaultCheckInAxes.MOOD), dataset.states.first { it.checkInId == "conflict" }.conflictingAxisIds)
        assertEquals(
            CategoryObservationKindV2.CONFLICTING,
            dataset.intervals.first { it.endingCheckInId == "conflict" }.activity.kind,
        )
        val mismatch = dataset.intervals.first { it.endingCheckInId == "mismatch" }
        assertEquals(4_500L, mismatch.windowEndAt)
        assertEquals(5_000L, dataset.states.first { it.checkInId == "mismatch" }.recordedAt)
        assertEquals(2_500L, mismatch.durationMillis)
        assertEquals(CategoryObservationKindV2.ABSENT, mismatch.diet.kind)
        assertEquals(listOf("Walk"), mismatch.activity.activities.map { it.name })
        assertEquals(CategoryObservationKindV2.NONE, mismatch.activity.kind)
        assertTrue(mismatch.workout.workouts.isEmpty())
        assertEquals(CategoryObservationKindV2.ENTRIES, mismatch.workout.kind)
        assertTrue(dataset.intervals.none { it.endingCheckInId == "dup" || it.endingCheckInId == "lonely" })
        val backward = dataset.transitions.single()
        assertEquals("later", backward.previous.checkInId)
        assertEquals("early", backward.ending.checkInId)
        assertEquals(-2, backward.coreDeltas.mood!!.deltaRaw)
        assertTrue(dataset.intervals.first { it.endingCheckInId == "child-dup" }.activity.activities.isEmpty())

        assertEquals(
            listOf(
                AnalysisIssueCode.CATEGORY_STATUS_DISAGREES_WITH_ITEMS to "mismatch",
                AnalysisIssueCode.CATEGORY_STATUS_DISAGREES_WITH_ITEMS to "mismatch",
                AnalysisIssueCode.CHECK_IN_WITHOUT_CONTEXT_WINDOW to "lonely",
                AnalysisIssueCode.CHILD_WITHOUT_PARENT to "ghost",
                AnalysisIssueCode.CHILD_WITHOUT_PARENT to "lonely",
                AnalysisIssueCode.CHILD_WITHOUT_PARENT to "lonely",
                AnalysisIssueCode.CHILD_WITHOUT_PARENT to "no-check-in",
                AnalysisIssueCode.CHILD_WITHOUT_PARENT to null,
                AnalysisIssueCode.DUPLICATE_AXIS_VALUE to "conflict",
                AnalysisIssueCode.DUPLICATE_CATEGORY_ROW to "conflict",
                AnalysisIssueCode.DUPLICATE_CHILD_ROW to "child-dup",
                AnalysisIssueCode.DUPLICATE_CONTEXT_WINDOW to "dup",
                AnalysisIssueCode.MISSING_CATEGORY_ROW to "mismatch",
                AnalysisIssueCode.MISSING_PREVIOUS_CHECK_IN to "mismatch",
                AnalysisIssueCode.PREVIOUS_CHECK_IN_IS_NOT_EARLIER to "early",
                AnalysisIssueCode.WINDOW_END_DISAGREES_WITH_CHECK_IN to "mismatch",
            ),
            dataset.issues.map { it.code to it.checkInId },
        )
        assertEquals("gone", dataset.issues.first { it.code == AnalysisIssueCode.MISSING_PREVIOUS_CHECK_IN }.relatedId)
        assertEquals(
            CheckInContextCategories.DIET,
            dataset.issues.first { it.code == AnalysisIssueCode.MISSING_CATEGORY_ROW }.relatedId,
        )
        assertEquals("loose-food", dataset.issues.first { it.checkInId == null }.relatedId)
    }

    @Test
    fun anEmotionOnlyCheckInStaysInTheDatasetWithoutAMoodScore() {
        val state = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("only", 1_000, emotion = "Anxious")),
            windows = listOf(window("only", 1_000)),
            categories = noneCategories("only"),
        ).states.single()
        assertEquals("Anxious", state.emotion)
        assertNull(state.core.mood)
        assertFalse(state.hasObservedAxes)
        assertEquals(0, state.coreAxisCount)
    }

    @Test
    fun triggerNoteAndEmotionStayMetadataOutsideTheCoreAxes() {
        val states = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(
                checkIn("a", 1_000, note = "after lunch", emotion = "Calm", trigger = "manual"),
                checkIn("b", 2_000),
            ),
            axisValues = listOf(axis("a", DefaultCheckInAxes.MOOD, 0)),
            windows = listOf(window("a", 1_000), window("b", 2_000)),
            categories = noneCategories("a") + noneCategories("b"),
        ).states
        val state = states.first { it.checkInId == "a" }
        val empty = states.first { it.checkInId == "b" }

        assertEquals("manual", state.trigger)
        assertEquals("after lunch", state.note)
        assertEquals("Calm", state.emotion)
        assertEquals(1, state.coreAxisCount)
        assertEquals(listOf(DefaultCheckInAxes.MOOD), state.core.inOrder().mapNotNull { (id, reading) ->
            reading?.let { id }
        })
        assertEquals("", empty.note)
        assertEquals("", empty.emotion)
        assertFalse(empty.hasObservedAxes)
    }

    @Test
    fun aLinkWithoutSharedAxesIsNotAMeasuredDelta() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(checkIn("end", 2_000), checkIn("start", 1_000)),
            axisValues = listOf(
                axis("start", DefaultCheckInAxes.ENERGY, 4),
                axis("start", DefaultCheckInAxes.MOOD, 2),
                axis("end", DefaultCheckInAxes.MOOD, 8),
                axis("end", DefaultCheckInAxes.ANXIETY, 3),
                axis("end", "focus", 1),
            ),
            windows = listOf(
                window("start", 1_000),
                window(
                    "end",
                    end = 2_000,
                    start = 1_000,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "start",
                ),
            ),
            categories = noneCategories("start") + noneCategories("end"),
        )
        val transition = dataset.transitions.single()
        assertTrue(transition.hasMeasuredDelta)
        assertEquals(listOf(DefaultCheckInAxes.MOOD), transition.sharedAxisIds)
        assertEquals(1, transition.measuredAxisCount)
        val moodDelta = checkNotNull(transition.coreDeltas.mood)
        assertEquals(6, moodDelta.deltaRaw)
        assertNull(transition.coreDeltas.energy)
        assertNull(transition.coreDeltas.anxiety)
        assertFalse(transition.deltas.containsKey("focus"))
        val summary = summarizeCheckInAnalysis(dataset)
        assertEquals(1, summary.linkedTransitionCount)
        assertEquals(1, summary.measuredTransitionCount)
        assertEquals(2, dataset.states.first { it.checkInId == "start" }.coreAxisCount)
        assertEquals(3, dataset.states.first { it.checkInId == "end" }.observedAxisCount)
        assertEquals(2, dataset.states.first { it.checkInId == "end" }.coreAxisCount)
    }

    @Test
    fun unreviewedNoneAndAbsentStayDistinctAndCompletedUnreviewedIsFlagged() {
        val dataset = CheckInAnalysisDatasetBuilder.build(
            checkIns = listOf(
                checkIn("open", 1_000),
                checkIn("empty", 2_000),
                checkIn("gap", 3_000),
                checkIn("saved", 4_000),
            ),
            windows = listOf(
                window("open", 1_000, completedAt = null),
                window("empty", 2_000),
                window("gap", 3_000),
                window("saved", 4_000),
            ),
            categories = noneCategories("open").map { row ->
                if (row.category == CheckInContextCategories.ACTIVITY) {
                    row.copy(status = CheckInContextStatus.UNREVIEWED)
                } else {
                    row
                }
            } + noneCategories("empty") +
                noneCategories("gap").filter { it.category != CheckInContextCategories.DIET } +
                noneCategories("saved").map { row ->
                    if (row.category == CheckInContextCategories.WORKOUT) {
                        row.copy(status = CheckInContextStatus.UNREVIEWED)
                    } else {
                        row
                    }
                },
        )
        val open = dataset.intervals.first { it.endingCheckInId == "open" }
        val empty = dataset.intervals.first { it.endingCheckInId == "empty" }
        val gap = dataset.intervals.first { it.endingCheckInId == "gap" }
        val saved = dataset.intervals.first { it.endingCheckInId == "saved" }
        assertEquals(CategoryObservationKindV2.UNREVIEWED, open.activity.kind)
        assertFalse(open.isComplete)
        assertEquals(CategoryObservationKindV2.NONE, empty.activity.kind)
        assertEquals(CategoryObservationKindV2.ABSENT, gap.diet.kind)
        assertEquals(CategoryObservationKindV2.NONE, gap.activity.kind)
        assertEquals(CategoryObservationKindV2.UNREVIEWED, saved.workout.kind)
        assertTrue(saved.isComplete)
        assertEquals(
            listOf(AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED to "saved"),
            dataset.issues
                .filter { it.code == AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED }
                .map { it.code to it.checkInId },
        )
        assertEquals(
            CheckInContextCategories.WORKOUT,
            dataset.issues.single { it.code == AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED }.relatedId,
        )
        assertTrue(dataset.issues.none { it.checkInId == "open" && it.code == AnalysisIssueCode.COMPLETED_INTERVAL_HAS_UNREVIEWED })
        assertEquals(
            AnalysisIssueCode.MISSING_CATEGORY_ROW,
            dataset.issues.single { it.checkInId == "gap" }.code,
        )
        val counts = summarizeCheckInAnalysis(dataset).categoryCounts
        assertEquals(1, counts.getValue(CheckInContextCategories.ACTIVITY).unreviewed)
        assertEquals(3, counts.getValue(CheckInContextCategories.ACTIVITY).none)
        assertEquals(1, counts.getValue(CheckInContextCategories.DIET).absent)
        assertEquals(3, counts.getValue(CheckInContextCategories.DIET).none)
        assertEquals(0, counts.getValue(CheckInContextCategories.DIET).unreviewed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun axisValuesOutsideZeroToTenCannotBeConstructed() {
        CheckInAxisValue("a", DefaultCheckInAxes.MOOD, 11)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aWindowThatEndsBeforeItStartsCannotBeConstructed() {
        CheckInContextWindow(
            checkInId = "a",
            previousCheckInId = null,
            windowStartAt = 20,
            windowEndAt = 10,
            startBasis = CheckInContextStartBasis.DAY_START,
        )
    }

    private fun structuredRecords(): StructuredRecords {
        return StructuredRecords(
            checkIns = listOf(checkIn("end", 2_000), checkIn("start", 1_000)),
            axisValues = listOf(
                axis("end", DefaultCheckInAxes.MOOD, 8),
                axis("start", DefaultCheckInAxes.MOOD, 2),
                axis("start", "focus", 1),
            ),
            windows = listOf(
                window(
                    "end",
                    end = 2_000,
                    start = 1_000,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    previous = "start",
                ),
                window("start", 1_000, start = 100),
            ),
            categories = noneCategories("end") + noneCategories("start"),
            activities = listOf(
                activity("b", "end", null, "Read", reportedAt = 2),
                activity("a", "end", "opt", "Walk", reportedAt = 1),
            ),
            workouts = listOf(
                CheckInWorkoutItem("w", "end", "opt-w", "Run", 20, 3),
            ),
            meals = listOf(
                meal("m2", "end", CheckInMeal.SNACK, sortOrder = 1),
                meal("m1", "end", CheckInMeal.SMALL, sortOrder = 0),
            ),
            foods = listOf(
                food("f2", "m1", null, "Rice", sortOrder = 1),
                food("f1", "m1", "opt-f", "Eggs", sortOrder = 0),
            ),
            usage = listOf(
                usage("u2", "end", null, "Tea", SubstanceIntent.SUPPORTIVE.name, "2 drinks", reportedAt = 8),
                usage("u1", "end", "opt-u", "Water", SubstanceIntent.COPING.name, "", reportedAt = 4),
            ),
        )
    }

    private fun project(records: StructuredRecords): CheckInAnalysisDatasetV2 {
        return CheckInAnalysisDatasetBuilder.build(
            checkIns = records.checkIns,
            axisValues = records.axisValues,
            windows = records.windows,
            categories = records.categories,
            activities = records.activities,
            workouts = records.workouts,
            meals = records.meals,
            foods = records.foods,
            usage = records.usage,
        )
    }

    private fun checkIn(
        id: String,
        at: Long,
        note: String = "",
        emotion: String = "",
        trigger: String = CheckInRecord.TRIGGER_MANUAL,
    ) = CheckInRecord(
        id = id,
        localDate = DAY,
        recordedAt = at,
        trigger = trigger,
        note = note,
        emotion = emotion,
    )

    private fun window(
        id: String,
        end: Long,
        start: Long = 0L,
        basis: String = CheckInContextStartBasis.WAKE_TIME,
        previous: String? = null,
        completedAt: Long? = 10L,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = if (basis == CheckInContextStartBasis.PREVIOUS_CHECK_IN) {
            requireNotNull(previous)
        } else {
            null
        },
        windowStartAt = start,
        windowEndAt = end,
        startBasis = basis,
        completedAt = completedAt,
    )

    private fun noneCategories(id: String) = CheckInContextCategories.ALL.map { category ->
        category(id, category, CheckInContextStatus.NONE)
    }

    private fun category(
        checkInId: String,
        category: String,
        status: String,
        reviewedAt: Long? = null,
    ) = CheckInContextCategory(
        checkInId = checkInId,
        category = category,
        status = status,
        reviewedAt = reviewedAt,
    )

    private fun axis(checkInId: String, axisId: String, value: Int) =
        CheckInAxisValue(checkInId, axisId, value)

    private fun activity(
        id: String,
        checkInId: String,
        optionId: String?,
        name: String,
        reportedAt: Long,
    ) = CheckInActivityItem(
        id = id,
        checkInId = checkInId,
        activityOptionId = optionId,
        activityName = name,
        reportedAt = reportedAt,
    )

    private fun meal(id: String, checkInId: String, size: String, sortOrder: Int) = CheckInMeal(
        id = id,
        checkInId = checkInId,
        size = size,
        sortOrder = sortOrder,
        reportedAt = 1L,
    )

    private fun food(
        id: String,
        mealId: String,
        optionId: String?,
        name: String,
        sortOrder: Int,
    ) = CheckInMealFoodItem(
        id = id,
        mealId = mealId,
        foodOptionId = optionId,
        foodName = name,
        sortOrder = sortOrder,
    )

    private fun usage(
        id: String,
        checkInId: String,
        optionId: String?,
        name: String,
        intent: String,
        amount: String,
        reportedAt: Long,
    ) = CheckInUsageItem(
        id = id,
        checkInId = checkInId,
        substanceOptionId = optionId,
        name = name,
        intent = intent,
        amount = amount,
        reportedAt = reportedAt,
    )

    private data class StructuredRecords(
        val checkIns: List<CheckInRecord>,
        val axisValues: List<CheckInAxisValue>,
        val windows: List<CheckInContextWindow>,
        val categories: List<CheckInContextCategory>,
        val activities: List<CheckInActivityItem>,
        val workouts: List<CheckInWorkoutItem>,
        val meals: List<CheckInMeal>,
        val foods: List<CheckInMealFoodItem>,
        val usage: List<CheckInUsageItem>,
    )

    private companion object {
        const val DAY = "2026-11-04"
    }
}
