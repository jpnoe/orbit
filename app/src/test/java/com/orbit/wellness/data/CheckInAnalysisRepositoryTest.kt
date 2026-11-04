package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInActivityItem
import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.domain.checkin.AnalysisIssueCode
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.directedSteps
import com.orbit.wellness.domain.checkin.intervalRecordedWith
import com.orbit.wellness.domain.checkin.outgoingInterval
import com.orbit.wellness.ui.insights.InsightsPhase
import com.orbit.wellness.ui.insights.toInsightsContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInAnalysisRepositoryTest {
    @Test
    fun noCheckInsProduceTheEmptyInsightsState() {
        val content = projectCheckInAnalysis(CheckInAnalysisSources()).toInsightsContent()
        assertEquals(InsightsPhase.EMPTY, content.phase)
        assertEquals(0, content.stateObservationCount)
        assertEquals(0, content.completedIntervalCount)
        assertEquals(0, content.linkedTransitionCount)
        assertEquals(0, content.measuredTransitionCount)
        assertTrue(content.issues.isEmpty())
        assertTrue(content.recordedEmotions.isEmpty())
    }

    @Test
    fun oneCompletedCheckInProducesOneStateAndNoLinkedTransition() {
        val content = insights(oneState())
        assertEquals(InsightsPhase.ONE_OBSERVATION, content.phase)
        assertEquals(1, content.stateObservationCount)
        assertEquals(1, content.completedIntervalCount)
        assertEquals(0, content.linkedTransitionCount)
        assertEquals(0, content.measuredTransitionCount)
        assertTrue(projectCheckInAnalysis(oneState()).dataset.directedSteps().isEmpty())
    }

    @Test
    fun wakeAndDayStartIntervalsDoNotInventATransition() {
        val wake = insights(oneState(basis = CheckInContextStartBasis.WAKE_TIME))
        val day = insights(oneState(basis = CheckInContextStartBasis.DAY_START))
        assertEquals(0, wake.linkedTransitionCount)
        assertEquals(0, day.linkedTransitionCount)
        assertEquals(1, wake.completedIntervalCount)
        assertEquals(1, day.completedIntervalCount)
        assertTrue(projectCheckInAnalysis(oneState()).dataset.transitions.isEmpty())
    }

    @Test
    fun twoLinkedCheckInsProduceOneLinkedTransition() {
        val snapshot = projectCheckInAnalysis(linkedPair(sharedMood = true))
        val content = snapshot.toInsightsContent()
        assertEquals(InsightsPhase.COLLECTING, content.phase)
        assertEquals(2, content.stateObservationCount)
        assertEquals(1, content.linkedTransitionCount)
        val step = snapshot.dataset.directedSteps().single()
        assertEquals("a", step.startingState.checkInId)
        assertEquals("b", step.endingState.checkInId)
    }

    @Test
    fun aSharedRecordedAxisProducesOneMeasuredDeltaTransition() {
        val snapshot = projectCheckInAnalysis(linkedPair(sharedMood = true))
        assertEquals(1, snapshot.summary.measuredTransitionCount)
        assertTrue(snapshot.dataset.transitions.single().hasMeasuredDelta)
        assertEquals(1, snapshot.toInsightsContent().measuredTransitionCount)
    }

    @Test
    fun aLinkedPairWithNoSharedAxesStaysLinkedButIsNotMeasured() {
        val snapshot = projectCheckInAnalysis(linkedPair(sharedMood = false))
        val transition = snapshot.dataset.transitions.single()
        assertFalse(transition.hasMeasuredDelta)
        assertTrue(transition.deltas.isEmpty())
        val content = snapshot.toInsightsContent()
        assertEquals(1, content.linkedTransitionCount)
        assertEquals(0, content.measuredTransitionCount)
    }

    @Test
    fun contextSavedWithTheEndingCheckInIsTheIntervalBetweenTheStates() {
        val snapshot = projectCheckInAnalysis(linkedWithActivity(endingActivity = "Walk"))
        val dataset = snapshot.dataset
        val step = dataset.directedSteps().single()
        assertSame(dataset.states.single { it.checkInId == "a" }, step.startingState)
        assertSame(dataset.states.single { it.checkInId == "b" }, step.endingState)
        assertEquals("b", step.interval.endingCheckInId)
        assertEquals("a", step.interval.previousCheckInId)
        assertEquals(listOf("Walk"), step.interval.activity.activities.map { it.name })
        assertEquals(step.interval, dataset.outgoingInterval("a"))
        assertEquals(step.interval, dataset.intervalRecordedWith("b"))
        assertTrue(dataset.intervalRecordedWith("a")!!.activity.activities.isEmpty())
        assertEquals(1, snapshot.toInsightsContent().intervalsContainingActivity)
    }

    @Test
    fun theNextIntervalIsNotFoldedIntoTheCurrentState() {
        val dataset = projectCheckInAnalysis(threeStates()).dataset
        val intoB = dataset.directedSteps().single { it.endingState.checkInId == "b" }
        val intoC = dataset.directedSteps().single { it.endingState.checkInId == "c" }
        val stateB = dataset.states.single { it.checkInId == "b" }
        assertSame(stateB, intoB.endingState)
        assertSame(stateB, intoC.startingState)
        assertEquals(listOf("Walk"), intoB.interval.activity.activities.map { it.name })
        assertEquals(listOf("Read"), intoC.interval.activity.activities.map { it.name })
        assertEquals("c", dataset.outgoingInterval("b")!!.endingCheckInId)
        assertEquals(intoB.interval, dataset.intervalRecordedWith("b"))
        assertTrue(dataset.intervalRecordedWith("b")!!.activity.activities.none { it.name == "Read" })
        assertEquals(setOf(DefaultCheckInAxes.MOOD), stateB.axes.keys)
        val stateFields = stateB.javaClass.declaredFields.map { it.name }
        assertTrue(stateFields.none { it.contains("interval") || it.contains("activit") })
    }

    @Test
    fun axisCoverageCountsExplicitZeroAsPresentAndMissingAsAbsent() {
        val sources = oneState(axes = listOf(axis("a", DefaultCheckInAxes.MOOD, 0)))
        val coverage = insights(sources).coreCoverage.associateBy { it.axisId }
        assertEquals(1, coverage.getValue(DefaultCheckInAxes.MOOD).recordedCount)
        assertEquals(0, coverage.getValue(DefaultCheckInAxes.MOOD).missingCount)
        assertEquals(0, coverage.getValue(DefaultCheckInAxes.ENERGY).recordedCount)
        assertEquals(1, coverage.getValue(DefaultCheckInAxes.ENERGY).missingCount)
        assertEquals(0, coverage.getValue(DefaultCheckInAxes.ENERGY).conflictingCount)
    }

    @Test
    fun emotionStaysCategoricalAndDoesNotChangeCoreAxisCoverage() {
        val sources = CheckInAnalysisSources(
            checkIns = listOf(
                record("a", 1_000, emotion = "Calm"),
                record("b", 2_000),
            ),
            axisValues = listOf(axis("b", DefaultCheckInAxes.MOOD, 3)),
            windows = listOf(window("a", 1_000), linkedWindow("b", "a", 1_000, 2_000)),
            categories = none("a") + none("b"),
        )
        val content = insights(sources)
        assertEquals(listOf("Calm"), content.recordedEmotions)
        assertEquals(1, content.observationsWithoutEmotion)
        assertEquals(CheckInAnalysisDatasetV2.CORE_AXIS_IDS, content.coreCoverage.map { it.axisId })
        val mood = content.coreCoverage.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(1, mood.recordedCount)
        assertEquals(1, mood.missingCount)
    }

    @Test
    fun analysisIssuesReachTheDiagnosticsState() {
        val sources = CheckInAnalysisSources(checkIns = listOf(record("a", 1_000)))
        val content = insights(sources)
        assertEquals(1, content.issues.size)
        assertEquals(AnalysisIssueCode.CHECK_IN_WITHOUT_CONTEXT_WINDOW, content.issues.single().code)
        assertEquals("a", content.issues.single().checkInId)
    }

    @Test
    fun repositoryUpdatesPropagateToTheInsightsState() = runBlocking {
        val dao = InMemoryCheckInDao()
        val repository = CheckInAnalysisRepository(dao, Dispatchers.Unconfined)
        val seen = mutableListOf<InsightsPhase>()
        val job = launch(Dispatchers.Unconfined) {
            repository.observeSnapshot().collect { seen += it.toInsightsContent().phase }
        }
        waitUntil { InsightsPhase.EMPTY in seen }
        dao.insertCheckIn(record("a", 1_000))
        dao.insertContextWindow(window("a", 1_000))
        dao.insertContextCategories(none("a"))
        dao.insertAxisValues(listOf(axis("a", DefaultCheckInAxes.MOOD, 4)))
        waitUntil { InsightsPhase.ONE_OBSERVATION in seen }
        job.cancel()
    }

    @Test
    fun repeatedEquivalentSourceSnapshotsDoNotProduceAnotherEmission() = runBlocking {
        val original = oneState()
        val reordered = original.copy(
            checkIns = original.checkIns.reversed(),
            axisValues = original.axisValues.reversed(),
            categories = original.categories.reversed(),
        )
        val same = flowOf(original, original.copy(), reordered, original)
            .toAnalysisSnapshots(Dispatchers.Unconfined)
            .toList()
        assertEquals(1, same.size)

        val changed = original.copy(checkIns = original.checkIns + record("b", 2_000))
        val updated = flowOf(original, changed)
            .toAnalysisSnapshots(Dispatchers.Unconfined)
            .toList()
        assertEquals(2, updated.size)
        assertEquals(1, updated[0].summary.stateObservationCount)
        assertEquals(2, updated[1].summary.stateObservationCount)
    }

    private fun insights(sources: CheckInAnalysisSources) =
        projectCheckInAnalysis(sources).toInsightsContent()

    private fun oneState(
        basis: String = CheckInContextStartBasis.WAKE_TIME,
        axes: List<CheckInAxisValue> = listOf(axis("a", DefaultCheckInAxes.MOOD, 4)),
    ) = CheckInAnalysisSources(
        checkIns = listOf(record("a", 1_000)),
        axisValues = axes,
        windows = listOf(window("a", 1_000, basis)),
        categories = none("a"),
    )

    private fun linkedPair(sharedMood: Boolean): CheckInAnalysisSources {
        val axes = if (sharedMood) {
            listOf(
                axis("a", DefaultCheckInAxes.MOOD, 4),
                axis("b", DefaultCheckInAxes.MOOD, 6),
            )
        } else {
            listOf(
                axis("a", DefaultCheckInAxes.MOOD, 4),
                axis("b", DefaultCheckInAxes.ENERGY, 3),
            )
        }
        return CheckInAnalysisSources(
            checkIns = listOf(record("a", 1_000), record("b", 2_000)),
            axisValues = axes,
            windows = listOf(window("a", 1_000), linkedWindow("b", "a", 1_000, 2_000)),
            categories = none("a") + none("b"),
        )
    }

    private fun linkedWithActivity(endingActivity: String) = CheckInAnalysisSources(
        checkIns = listOf(record("a", 1_000), record("b", 2_000)),
        axisValues = listOf(axis("a", DefaultCheckInAxes.MOOD, 4), axis("b", DefaultCheckInAxes.MOOD, 6)),
        windows = listOf(window("a", 1_000), linkedWindow("b", "a", 1_000, 2_000)),
        categories = none("a") + activityCategories("b"),
        activities = listOf(activity("walk", "b", endingActivity)),
    )

    private fun threeStates() = CheckInAnalysisSources(
        checkIns = listOf(record("a", 1_000), record("b", 2_000), record("c", 3_000)),
        axisValues = listOf(
            axis("a", DefaultCheckInAxes.MOOD, 4),
            axis("b", DefaultCheckInAxes.MOOD, 6),
            axis("c", DefaultCheckInAxes.MOOD, 5),
        ),
        windows = listOf(
            window("a", 1_000),
            linkedWindow("b", "a", 1_000, 2_000),
            linkedWindow("c", "b", 2_000, 3_000),
        ),
        categories = none("a") + activityCategories("b") + activityCategories("c"),
        activities = listOf(
            activity("walk", "b", "Walk"),
            activity("read", "c", "Read"),
        ),
    )

    private fun record(id: String, at: Long, emotion: String = "") = CheckInRecord(
        id = id,
        localDate = "2026-11-04",
        recordedAt = at,
        trigger = CheckInRecord.TRIGGER_MANUAL,
        emotion = emotion,
    )

    private fun window(
        id: String,
        end: Long,
        basis: String = CheckInContextStartBasis.WAKE_TIME,
    ) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = null,
        windowStartAt = end - 60_000,
        windowEndAt = end,
        startBasis = basis,
        completedAt = 1L,
    )

    private fun linkedWindow(id: String, previous: String, start: Long, end: Long) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = 1L,
    )

    private fun none(id: String) = CheckInContextCategories.ALL.map { category ->
        CheckInContextCategory(checkInId = id, category = category, status = CheckInContextStatus.NONE)
    }

    private fun activityCategories(id: String) = CheckInContextCategories.ALL.map { category ->
        CheckInContextCategory(
            checkInId = id,
            category = category,
            status = if (category == CheckInContextCategories.ACTIVITY) {
                CheckInContextStatus.ENTRIES
            } else {
                CheckInContextStatus.NONE
            },
        )
    }

    private fun activity(id: String, checkInId: String, name: String) = CheckInActivityItem(
        id = id,
        checkInId = checkInId,
        activityOptionId = null,
        activityName = name,
        reportedAt = 1L,
    )

    private fun axis(checkInId: String, axisId: String, value: Int) =
        CheckInAxisValue(checkInId, axisId, value)

    private suspend fun waitUntil(condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - started > 3_000) error("Timed out waiting for an analysis update")
            delay(20)
        }
    }
}
