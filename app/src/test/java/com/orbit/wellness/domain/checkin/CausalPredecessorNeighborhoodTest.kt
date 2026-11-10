package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class CausalPredecessorNeighborhoodTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun aChainStoresExactDepthsAndIncludesTheImmediateIncomingTransition() {
        val neighborhood = project(chain()).neighborhood("d")
        assertEquals(listOf(1, 2, 3), neighborhood.hops.map { it.depth })
        assertEquals(listOf("d", "c", "b"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(listOf("c", "b", "a"), neighborhood.hops.map { it.predecessor?.stateKey?.raw })
        assertEquals("d", neighborhood.hops.first().transition.key.raw)
        assertEquals(neighborhood.focal.recordedAt, neighborhood.hops.first().transition.endAt)
        assertEquals(PredecessorStopReason.END_OF_PATH, neighborhood.stop.reason)
        assertEquals(3, neighborhood.stop.depth)
        assertEquals(FocalAxisStatus.RECORDED, neighborhood.focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.status)
        assertEquals(0, neighborhood.focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertEquals(FocalAxisStatus.MISSING, neighborhood.focal.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.status)
        assertEquals(FocalAxisStatus.CONFLICTING, neighborhood.focal.axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.status)
        assertNull(neighborhood.focal.axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.raw)
        assertFalse(neighborhood.focal.emotionPresent)
        val origin = neighborhood.hops.last().predecessor
        assertEquals(true, origin?.emotionPresent)
        assertEquals("tense", origin?.emotion)
        assertEquals("morning", origin?.trigger)
        val sixHours = neighborhood.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }
        assertTrue(sixHours.completedKeys.map { it.raw }.contains("d"))
    }

    @Test
    fun laterHistoryLeavesTheFocalNeighborhoodUnchanged() {
        val original = project(chain()).neighborhood("d")
        val extended = chain().let { data ->
            val start = data.states.single { it.checkInId == "d" }.recordedAt
            data.copy(
                states = data.states + state("e", start + HOUR, mood = 9, energy = 1),
                intervals = data.intervals + linked(
                    "e",
                    "d",
                    start,
                    start + HOUR,
                    activity = entries("swim", "Swim", start),
                ),
            )
        }
        assertEquals(original, project(extended).neighborhood("d"))
    }

    @Test
    fun anUnrelatedEqualTimestampStaysOutOfTheOrderedPath() {
        val start = 10L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 2),
                state("focal", start + HOUR, mood = 4),
                state("other", start + HOUR, mood = 9),
                state("unlinked", start + HOUR, mood = 1),
            ),
            intervals = listOf(
                linked("focal", "origin", start, start + HOUR),
                linked("other", "origin", start, start + HOUR),
            ),
        )
        val focal = project(data).neighborhood("focal")
        assertEquals(listOf("focal"), focal.hops.map { it.transition.key.raw })
        assertEquals(listOf("origin"), focal.hops.map { it.predecessor?.stateKey?.raw })
        assertFalse(focal.hops.any { it.predecessor?.stateKey?.raw == "other" || it.predecessor?.stateKey?.raw == "unlinked" })
        val keys = focal.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }.completedKeys.map { it.raw }
        assertEquals(listOf("focal", "other").sorted(), keys.sorted())
        val linked = dataset(
            states = listOf(
                state("earlier-key", start, mood = 2),
                state("later-key", start, mood = 8),
            ),
            intervals = listOf(linked("later-key", "earlier-key", start - HOUR, start)),
        )
        val rejected = project(linked).neighborhood("later-key")
        assertTrue(rejected.hops.isEmpty())
        assertEquals(PredecessorStopReason.INVALID_CHRONOLOGY, rejected.stop.reason)
        assertEquals("later-key", rejected.stop.stoppingTransitionKey?.raw)
    }

    @Test
    fun aSiblingBranchIsNotSelected() {
        val quiet = project(branch(siblingMood = 1)).neighborhood("left")
        val changed = project(branch(siblingMood = 9)).neighborhood("left")
        assertEquals(quiet, changed)
        assertEquals(listOf("origin"), quiet.hops.map { it.predecessor?.stateKey?.raw })
        assertEquals(listOf("left"), quiet.hops.map { it.transition.key.raw })
        assertFalse(quiet.hops.any { it.predecessor?.stateKey?.raw == "right" })
    }

    @Test
    fun ambiguousPredecessorsAreKeptAndTraversalStops() {
        val start = 12L * DAY
        val forward = dataset(
            states = listOf(
                state("left-start", start, mood = 1),
                state("right-start", start + HOUR, mood = 9),
                state("b", start + 2 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("b", "left-start", start, start + 2 * HOUR),
                linked("b", "right-start", start + HOUR, start + 2 * HOUR),
            ),
        )
        val neighborhood = project(forward).neighborhood("b")
        val reversed = project(forward.copy(intervals = forward.intervals.reversed())).neighborhood("b")
        assertEquals(neighborhood, reversed)
        assertTrue(neighborhood.hops.isEmpty())
        assertEquals(PredecessorStopReason.AMBIGUOUS_PREDECESSOR, neighborhood.stop.reason)
        assertEquals(0, neighborhood.stop.depth)
        assertEquals(listOf("b", "b"), neighborhood.stop.candidates.map { it.transitionKey.raw })
        assertEquals(listOf("left-start", "right-start"), neighborhood.stop.candidates.map { it.previousCheckInId })
        assertNull(neighborhood.backgrounds.first().distinctEpisodeCount)
    }

    @Test
    fun aWakeOrDayStartIsKeptAndStopsThePath() {
        val start = 13L * DAY
        val data = dataset(
            states = listOf(
                state("before", start, mood = 9),
                state("wake", start + HOUR, mood = 3),
                state("day", start + 2 * HOUR, mood = 4),
            ),
            intervals = listOf(
                boundary("wake", "before", start, start + HOUR, CheckInContextStartBasis.WAKE_TIME),
                boundary("day", "before", start, start + 2 * HOUR, CheckInContextStartBasis.DAY_START),
            ),
        )
        val projected = project(data)
        listOf("wake", "day").forEach { id ->
            val neighborhood = projected.neighborhood(id)
            assertEquals(1, neighborhood.hops.size)
            assertEquals(true, neighborhood.hops.single().boundary)
            assertNull(neighborhood.hops.single().predecessor)
            assertEquals(id, neighborhood.hops.single().transition.key.raw)
            assertEquals(PredecessorStopReason.BOUNDARY, neighborhood.stop.reason)
            assertFalse(neighborhood.hops.any { it.predecessor?.stateKey?.raw == "before" })
            assertTrue(
                neighborhood.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }
                    .incompleteOrIneligibleKeys.map { it.raw }.contains(id),
            )
        }
    }

    @Test
    fun incompleteEvidenceStaysMarkedAndUnknownStaysUnknown() {
        val start = 14L * DAY
        val data = dataset(
            states = listOf(
                state("a", start, mood = 2),
                state("b", start + HOUR, mood = 3),
                state("c", start + 2 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR, complete = false, activity = unreviewedActivity()),
                linked("c", "b", start + HOUR, start + 2 * HOUR, activity = unreviewedActivity()),
            ),
        )
        val neighborhood = project(data).neighborhood("c")
        assertEquals(listOf(1, 2), neighborhood.hops.map { it.depth })
        assertEquals(PredecessorStopReason.END_OF_PATH, neighborhood.stop.reason)
        val incomplete = neighborhood.hops.single { it.transition.key.raw == "b" }.transition
        assertFalse(incomplete.completed)
        assertTrue(incomplete.eligibility.reasons.contains(CausalIneligibilityReason.INCOMPLETE_CONTEXT))
        assertEquals(CategoryKnowledge.UNKNOWN, incomplete.activityReview)
        assertEquals(CategoryKnowledge.UNKNOWN, neighborhood.hops.single { it.transition.key.raw == "c" }.transition.activityReview)
        val sixHours = neighborhood.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }
        assertEquals(listOf("c"), sixHours.completedKeys.map { it.raw })
        assertEquals(listOf("b"), sixHours.incompleteOrIneligibleKeys.map { it.raw })
        val activity = sixHours.categories.single { it.category == "activity" }
        assertEquals(0, activity.reviewedNone)
        assertEquals(2, activity.unknown)
    }

    @Test
    fun aRecordedZeroQuantityStaysObserved() {
        val start = 15L * DAY
        val data = dataset(
            states = listOf(
                state("a", start, mood = 2),
                state("b", start + HOUR, mood = 2),
            ),
            intervals = listOf(
                linked(
                    "b",
                    "a",
                    start,
                    start + HOUR,
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        start + HOUR,
                        listOf(UsageObservationV2("u0", null, "tea", "supportive", "0", start)),
                    ),
                ),
            ),
        )
        val hop = project(data).neighborhood("b").hops.single().transition
        val usage = hop.exposures.filterIsInstance<UsageBehavior>().single()
        assertEquals(UsageQuantityStatus.PARSED, usage.amounts.single().status)
        assertEquals(0.0, checkNotNull(usage.amounts.single().numericAmount), 0.0)
        assertEquals(0.0, usage.comparable.single().total, 0.0)
        val counted = project(data).neighborhood("b").backgrounds
            .single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }
            .exposures.single()
        assertEquals(0.0, counted.comparableTotals.single().total, 0.0)
    }

    @Test
    fun aCrossingIntervalStaysOutAndTheExactAnchorStaysIn() {
        val focalAt = 16L * DAY
        val data = dataset(
            states = listOf(
                state("origin", focalAt - HOUR, mood = 2),
                state("focal", focalAt, mood = 4),
                state("after", focalAt + HOUR, mood = 8),
                state("edge", focalAt - 6 * HOUR, mood = 1),
                state("inside", focalAt - 6 * HOUR + 1, mood = 3),
            ),
            intervals = listOf(
                linked("focal", "origin", focalAt - HOUR, focalAt),
                linked("after", "focal", focalAt, focalAt + 1),
                linked("edge", "origin", focalAt - 7 * HOUR, focalAt - 6 * HOUR),
                linked("inside", "edge", focalAt - 6 * HOUR, focalAt - 6 * HOUR + 1),
            ),
        )
        val focal = project(data).neighborhood("focal")
        val sixHours = focal.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_6_HOURS }
        val day = focal.backgrounds.single { it.scale == PredecessorBackgroundScale.PRIOR_24_HOURS }
        val keys = { background: PredecessorBackground ->
            (background.completedKeys + background.incompleteOrIneligibleKeys).map { it.raw }
        }
        assertTrue(keys(sixHours).contains("focal"))
        assertFalse(keys(sixHours).contains("after"))
        assertFalse(keys(sixHours).contains("edge"))
        assertTrue(keys(sixHours).contains("inside"))
        assertTrue(keys(day).contains("edge"))
        assertFalse(keys(day).contains("after"))
        assertEquals(focalAt, sixHours.windowEndAt)
    }

    @Test
    fun aLaterIntervalCannotChangeEarlierVocabularyButABackdatedOneCan() {
        val start = 17L * DAY
        val earlier = dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR, activity = entries("walk", "Walk", start)),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
            ),
        )
        val withLater = earlier.copy(
            states = earlier.states + state("d", start + 3 * HOUR, mood = 9),
            intervals = earlier.intervals + linked(
                "d",
                "c",
                start + 2 * HOUR,
                start + 3 * HOUR,
                activity = entries("swim", "Swim", start + 2 * HOUR),
            ),
        )
        val before = project(earlier).neighborhood("c")
        val afterLater = project(withLater).neighborhood("c")
        assertEquals(before, afterLater)
        assertEquals(listOf("name:walk"), exposureKeys(before, PredecessorBackgroundScale.PRIOR_6_HOURS))
        val backdated = withLater.copy(
            states = listOf(state("past", start - 2 * HOUR, mood = 5)) + withLater.states,
            intervals = listOf(
                linked(
                    "past",
                    "a",
                    start - 2 * HOUR,
                    start - HOUR,
                    activity = entries("nap", "Nap", start - 2 * HOUR),
                ),
            ) + withLater.intervals,
        )
        val reconstructed = project(backdated).neighborhood("c")
        assertEquals(listOf("name:nap", "name:walk"), exposureKeys(reconstructed, PredecessorBackgroundScale.PRIOR_6_HOURS))
        assertEquals(before.hops.map { it.transition.key.raw }, reconstructed.hops.map { it.transition.key.raw })
    }

    @Test
    fun aCycleStopsOnTheRepeatedState() {
        val start = 18L * DAY
        val data = dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
                linked("a", "c", start + 2 * HOUR, start),
            ),
        )
        val neighborhood = project(data).neighborhood("c")
        assertEquals(listOf(1, 2), neighborhood.hops.map { it.depth })
        assertEquals(listOf("c", "b"), neighborhood.hops.map { it.transition.key.raw })
        assertEquals(listOf("b", "a"), neighborhood.hops.map { it.predecessor?.stateKey?.raw })
        assertEquals(PredecessorStopReason.CYCLE, neighborhood.stop.reason)
        assertEquals("a", neighborhood.stop.stoppingTransitionKey?.raw)
        assertTrue(
            checkNotNull(neighborhood.stop.eligibility).reasons.contains(CausalIneligibilityReason.INVALID_CHRONOLOGY),
        )
        assertEquals(neighborhood, project(data.copy(states = data.states.reversed(), intervals = data.intervals.reversed())).neighborhood("c"))
    }

    @Test
    fun reversingRecordsKeepsTheOrderedProjection() {
        val samples = listOf(chain(), branch(4), unresolved(), invalidChronology())
        samples.forEach { data ->
            val forward = project(data)
            val backward = project(data.copy(states = data.states.reversed(), intervals = data.intervals.reversed()))
            assertEquals(forward, backward)
        }
    }

    @Test
    fun unresolvedIdentityStopsWithoutAdoptingAPredecessor() {
        val neighborhood = project(unresolved()).neighborhood("end")
        assertTrue(neighborhood.hops.isEmpty())
        assertEquals(PredecessorStopReason.UNRESOLVED, neighborhood.stop.reason)
        assertEquals("end", neighborhood.stop.stoppingTransitionKey?.raw)
        assertEquals("ghost", neighborhood.stop.unresolvedPreviousId)
    }

    @Test
    fun theContractStaysPastOnlyAndSeparateFromTheLockedLearner() {
        val projected = project(chain())
        assertEquals(CAUSAL_PREDECESSOR_NEIGHBORHOOD_PROTOCOL, projected.protocol)
        assertEquals(ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION, projected.availabilityMode)
        assertFalse(projected.strictAsKnownAvailability)
        assertTrue(projected.pastOnly)
        assertFalse(projected.predictive)
        assertFalse(projected.suitableForRecommendation)
        assertFalse(projected.updatesModel)
        val fields = CausalPredecessorNeighborhood::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { name ->
            listOf("forecast", "residual", "target", "outgoing", "cluster").any { name.contains(it) }
        })
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorNeighborhood.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorNeighborhood.kt",
        ).readText()
        assertTrue(source.contains("causal-predecessor-neighborhood-v1"))
        assertTrue(source.contains("retrospective-successor-neighborhood"))
        assertTrue(source.contains("bidirectional-smoothed-state"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        assertFalse(source.contains("projectCausalFeatureEncoder"))
        assertFalse(source.contains("fun projectCausalWalkForwardPreparation"))
        val learner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
        ).readText()
        assertTrue(learner.contains("null-trajectory-learner-v2"))
        assertFalse(learner.contains("causal-predecessor-neighborhood"))
        val architecture = source(
            "docs/architecture.md",
            "../docs/architecture.md",
        ).readText()
        assertTrue(architecture.contains("causal-predecessor-neighborhood-v1"))
        assertTrue(architecture.contains("retrospective-successor-neighborhood"))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Causal predecessor neighborhood"))
        val anchor = "11111111-2222-3333-4444-555555555555"
        val labeled = project(
            dataset(
                states = listOf(state(anchor, DAY, mood = 1)),
                intervals = emptyList(),
            ),
        ).neighborhood(anchor)
        assertFalse(predecessorCompactLabel(labeled).contains(anchor))
        assertTrue(predecessorTechnicalLabel(labeled).contains(anchor))
    }

    private fun project(dataset: CheckInAnalysisDatasetV2) = projectCausalPredecessorNeighborhood(dataset, zone)

    private fun CausalPredecessorNeighborhoodDataset.neighborhood(id: String) =
        neighborhoods.single { it.focal.stateKey.raw == id }

    private fun exposureKeys(
        neighborhood: CausalPredecessorNeighborhood,
        scale: PredecessorBackgroundScale,
    ) = neighborhood.backgrounds.single { it.scale == scale }.exposures.map { it.identity.key }.sorted()

    private fun chain(): CheckInAnalysisDatasetV2 {
        val start = 19L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1, emotion = "tense", trigger = "morning"),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
                state(
                    "d",
                    start + 3 * HOUR,
                    mood = 0,
                    conflicting = listOf(DefaultCheckInAxes.CONFIDENCE),
                ),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
                linked("d", "c", start + 2 * HOUR, start + 3 * HOUR),
            ),
        )
    }

    private fun branch(siblingMood: Int): CheckInAnalysisDatasetV2 {
        val start = 20L * DAY
        return dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("left", start + HOUR, mood = 4),
                state("right", start + 2 * HOUR, mood = siblingMood),
            ),
            intervals = listOf(
                linked("left", "origin", start, start + HOUR),
                linked("right", "origin", start, start + 2 * HOUR),
            ),
        )
    }

    private fun unresolved(): CheckInAnalysisDatasetV2 {
        val start = 21L * DAY
        return dataset(
            states = listOf(
                state("earlier", start, mood = 4),
                state("end", start + HOUR, mood = 1),
            ),
            intervals = listOf(linked("end", "ghost", start, start + HOUR)),
        )
    }

    private fun invalidChronology(): CheckInAnalysisDatasetV2 {
        val start = 22L * DAY
        return dataset(
            states = listOf(
                state("early", start + HOUR, mood = 1),
                state("late", start + 5 * HOUR, mood = 8),
            ),
            intervals = listOf(linked("early", "late", start, start + HOUR)),
        )
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        confidence: Int? = null,
        anxiety: Int? = null,
        emotion: String = "",
        trigger: String = "",
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.CONFIDENCE to confidence,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) ->
            if (axis in conflicting) null else reading(axis, raw)
        }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.CONFIDENCE },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
            conflictingAxisIds = conflicting,
            trigger = trigger,
            emotion = emotion,
        )
    }

    private fun linked(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        complete: Boolean = true,
        activity: ActivityCategoryObservationV2 = reviewedNoneActivity(endAt),
        usage: UsageCategoryObservationV2 = reviewedNoneUsage(endAt),
    ) = interval(end, previous, start, endAt, CheckInContextStartBasis.PREVIOUS_CHECK_IN, complete, activity, usage)

    private fun boundary(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        basis: String,
    ) = interval(end, previous, start, endAt, basis, true, reviewedNoneActivity(endAt), reviewedNoneUsage(endAt))

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        complete: Boolean,
        activity: ActivityCategoryObservationV2,
        usage: UsageCategoryObservationV2,
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = if (complete) endAt else null,
        activity = activity,
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = usage,
    )

    private fun reviewedNoneActivity(at: Long) =
        ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun reviewedNoneUsage(at: Long) =
        UsageCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun unreviewedActivity() =
        ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList())

    private fun entries(id: String, name: String, at: Long) = ActivityCategoryObservationV2(
        CategoryObservationKindV2.ENTRIES,
        at,
        listOf(ActivityObservationV2(id, null, name, at)),
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
