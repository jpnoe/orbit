package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class PredecessorContextLearnerTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun categoryKnowledgeUsesEntriesAndUnknownBits() {
        listOf("activity", "workout", "diet", "usage").forEach { category ->
            val absence = view(categoryDataset(category, CategoryObservationKindV2.NONE), "b")
            val entries = view(categoryDataset(category, CategoryObservationKindV2.ENTRIES), "b")
            val unknown = view(categoryDataset(category, CategoryObservationKindV2.UNREVIEWED), "b")
            assertEquals(CategoryKnowledge.REVIEWED_NONE, absence.audit.knowledge(category))
            assertEquals(0.0, absence.value(entriesChannel(category)), 0.0)
            assertEquals(0.0, absence.value(unknownChannel(category)), 0.0)
            assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, entries.audit.knowledge(category))
            assertEquals(1.0, entries.value(entriesChannel(category)), 0.0)
            assertEquals(0.0, entries.value(unknownChannel(category)), 0.0)
            assertEquals(CategoryKnowledge.UNKNOWN, unknown.audit.knowledge(category))
            assertEquals(0.0, unknown.value(entriesChannel(category)), 0.0)
            assertEquals(1.0, unknown.value(unknownChannel(category)), 0.0)
        }
    }

    @Test
    fun aMissingIntervalStaysDistinctFromARealZeroExposureCount() {
        val missing = view(dataset(listOf(state("only", 0L, mood = 1)), emptyList()), "only")
        assertEquals(true, missing.audit.immediateIntervalUnavailable)
        assertNull(missing.audit.immediateDurationHours)
        assertNull(missing.audit.immediateExposureCount)
        assertNull(missing.audit.activity)
        assertEquals(1.0, missing.value(PredecessorFeatureChannel.IMMEDIATE_INTERVAL_UNAVAILABLE), 0.0)
        assertEquals(0.0, missing.value(PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES), 0.0)
        assertEquals(0.0, missing.value(PredecessorFeatureChannel.ACTIVITY_UNKNOWN), 0.0)
        val zero = view(linkedPair(), "b")
        assertEquals(false, zero.audit.immediateIntervalUnavailable)
        assertEquals(0, zero.audit.immediateExposureCount)
        assertEquals(CategoryKnowledge.REVIEWED_NONE, zero.audit.activity)
        assertEquals(0.0, zero.value(PredecessorFeatureChannel.IMMEDIATE_INTERVAL_UNAVAILABLE), 0.0)
        assertEquals(0.0, checkNotNull(zero.value(PredecessorFeatureChannel.IMMEDIATE_EXPOSURE_COUNT)), 0.0)
    }

    @Test
    fun aFocalBoundaryCanHaveObservedZeroHours() {
        val start = 4L * DAY
        val wake = view(
            dataset(
                states = listOf(state("before", start, mood = 2), state("focal", start + HOUR, mood = 4)),
                intervals = listOf(boundary("focal", "before", start, start + HOUR, CheckInContextStartBasis.WAKE_TIME)),
            ),
            "focal",
        )
        assertEquals(1.0, checkNotNull(wake.audit.hoursSinceBoundary), 0.0)
        assertEquals(start, wake.audit.boundaryAt)
        assertEquals(false, wake.audit.boundaryUnavailable)
        assertEquals(0, wake.audit.resolvedPredecessorCount)
        assertEquals(1.0, checkNotNull(wake.audit.immediateDurationHours), 0.0)
        assertEquals(1.0, wake.value(PredecessorFeatureChannel.HOURS_SINCE_BOUNDARY), 0.0)
        assertEquals(0.0, wake.value(PredecessorFeatureChannel.BOUNDARY_UNAVAILABLE), 0.0)
        val coincident = view(
            dataset(
                states = listOf(state("focal", start, mood = 4)),
                intervals = listOf(boundary("focal", "before", start, start, CheckInContextStartBasis.WAKE_TIME)),
            ),
            "focal",
        )
        assertEquals(0.0, checkNotNull(coincident.audit.hoursSinceBoundary), 0.0)
        assertEquals(start, coincident.audit.boundaryAt)
        assertEquals(0, coincident.audit.resolvedPredecessorCount)
        val open = view(chain(), "e")
        assertEquals(true, open.audit.boundaryUnavailable)
        assertNull(open.audit.hoursSinceBoundary)
        assertEquals(1.0, open.value(PredecessorFeatureChannel.BOUNDARY_UNAVAILABLE), 0.0)
    }

    @Test
    fun hoursSinceBoundaryUseTheRecordedWakeStart() {
        val boundaryStart = 1_794_255_600_000L
        val boundaryEnd = 1_794_257_156_159L
        val focalAt = 1_794_266_819_645L
        val projected = view(
            dataset(
                states = listOf(
                    state("post", boundaryEnd, mood = 3),
                    state("focal", focalAt, mood = 4),
                ),
                intervals = listOf(
                    boundary("post", null, boundaryStart, boundaryEnd, CheckInContextStartBasis.WAKE_TIME),
                    linked("focal", "post", boundaryEnd, focalAt),
                ),
            ),
            "focal",
        )
        val fromStart = (focalAt - boundaryStart) / 3_600_000.0
        val fromEnd = (focalAt - boundaryEnd) / 3_600_000.0
        assertEquals(fromStart, checkNotNull(projected.audit.hoursSinceBoundary), 0.0)
        assertTrue(fromStart > fromEnd)
        assertEquals(boundaryStart, projected.audit.boundaryAt)
        assertEquals(false, projected.audit.boundaryUnavailable)
        assertEquals(1, projected.audit.resolvedPredecessorCount)
        assertTrue(predecessorFeatureCompactLabel(projected).contains("hours since recorded wake or day start"))
        assertTrue(predecessorFeatureAuditLabel(projected).contains("recorded boundary $boundaryStart"))
    }

    @Test
    fun theOrderedIncomingIntervalWinsOverAnEqualTimestampSibling() {
        val start = 5L * DAY
        val data = dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("focal", start + HOUR, mood = 2),
                state("other", start + HOUR, mood = 9),
            ),
            intervals = listOf(
                linked("focal", "origin", start, start + HOUR),
                linked("other", "origin", start - HOUR, start + HOUR),
            ),
        )
        val focal = view(data, "focal")
        assertEquals(1.0, checkNotNull(focal.audit.immediateDurationHours), 0.0)
        assertEquals("focal", project(data).neighborhood("focal").hops.single().transition.key.raw)
        val sameTime = dataset(
            states = listOf(state("earlier-key", start, mood = 2), state("later-key", start, mood = 8)),
            intervals = listOf(linked("later-key", "earlier-key", start - HOUR, start)),
        )
        assertEquals(true, view(sameTime, "later-key").audit.immediateIntervalUnavailable)
    }

    @Test
    fun pathStopsKeepTheImmediateIntervalIdentifiableOnlyWhenItWasAccepted() {
        val ambiguous = view(ambiguousDataset(), "b")
        assertEquals(true, ambiguous.audit.immediateIntervalUnavailable)
        assertEquals(0, ambiguous.audit.resolvedPredecessorCount)
        assertNull(ambiguous.audit.immediateExposureCount)
        val unresolved = view(unresolvedDataset(), "end")
        assertEquals(true, unresolved.audit.immediateIntervalUnavailable)
        val invalid = view(invalidDataset(), "early")
        assertEquals(true, invalid.audit.immediateIntervalUnavailable)
        val cycle = view(cycleDataset(), "c")
        assertEquals(false, cycle.audit.immediateIntervalUnavailable)
        assertEquals(2, cycle.audit.resolvedPredecessorCount)
        assertEquals(true, cycle.audit.boundaryUnavailable)
        val start = 6L * DAY
        val incomplete = view(
            dataset(
                states = listOf(state("a", start, mood = 1), state("b", start + HOUR, mood = 2)),
                intervals = listOf(linked("b", "a", start, start + HOUR, complete = false, activity = unreviewedActivity())),
            ),
            "b",
        )
        assertEquals(false, incomplete.audit.immediateIntervalUnavailable)
        assertEquals(CategoryKnowledge.UNKNOWN, incomplete.audit.activity)
        assertEquals(1.0, checkNotNull(incomplete.audit.immediateDurationHours), 0.0)
        assertEquals(0.0, incomplete.value(PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES), 0.0)
        assertEquals(1.0, incomplete.value(PredecessorFeatureChannel.ACTIVITY_UNKNOWN), 0.0)
    }

    @Test
    fun laterAndOutgoingChangesLeaveTheAnchorViewAndForecastUnchanged() {
        val original = learn(chain())
        val held = original.ending("e")
        val withLater = chain().let { data ->
            val at = data.states.single { it.checkInId == "e" }.recordedAt
            data.copy(
                states = data.states + state("f", at + HOUR, mood = 9),
                intervals = data.intervals + linked("f", "e", at, at + HOUR, activity = namedActivity()),
            )
        }
        val later = learn(withLater)
        assertEquals(held.featureView, later.ending("e").featureView)
        assertEquals(moodForecast(held), moodForecast(later.ending("e")))
        val outgoing = learn(chain()) { scenario ->
            if (scenario.transitionKey?.raw == "e") {
                scenario.copy(activity = CategoryKnowledge.REVIEWED_ENTRIES, exposures = listOf(secretExposure()))
            } else {
                scenario
            }
        }
        assertEquals(held.featureView, outgoing.ending("e").featureView)
        assertEquals(moodForecast(held), moodForecast(outgoing.ending("e")))
        val sibling = branch(activityOnRight = false)
        val changedSibling = branch(activityOnRight = true)
        assertEquals(view(sibling, "left"), view(changedSibling, "left"))
    }

    @Test
    fun anEarlierIncomingChangeUpdatesTheAnchorChannels() {
        val original = view(chain(), "d")
        val edited = chain().let { data ->
            data.copy(intervals = data.intervals.map { interval ->
                if (interval.endingCheckInId == "d") interval.copy(activity = namedActivity()) else interval
            })
        }
        val updated = view(edited, "d")
        assertEquals(CategoryKnowledge.REVIEWED_NONE, original.audit.activity)
        assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, updated.audit.activity)
        assertEquals(0.0, original.value(PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES), 0.0)
        assertEquals(1.0, updated.value(PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES), 0.0)
        assertEquals(1, updated.audit.immediateExposureCount)
    }

    @Test
    fun aHeldOutTargetChangesTheResidualOnly() {
        val original = learn(chain()).ending("e")
        val changed = learn(chain(), moodOn = "e" to 9).ending("e")
        assertEquals(moodForecast(original), moodForecast(changed))
        assertNotEquals(moodAxis(original).officialResidual, moodAxis(changed).officialResidual)
        assertEquals(original.featureView, changed.featureView)
    }

    @Test
    fun reversedInputsKeepTheSchemaCoefficientsAndFolds() {
        val forward = learn(chain())
        val backward = learn(chain().let { it.copy(states = it.states.reversed(), intervals = it.intervals.reversed()) })
        assertEquals(forward, backward)
        assertEquals(
            PredecessorContextColumn.entries.map { it.name }.take(NullGateColumn.entries.size),
            NullGateColumn.entries.map { it.name },
        )
        assertEquals(
            PredecessorFeatureChannel.entries.map { it.name },
            PredecessorContextColumn.entries.drop(NullGateColumn.entries.size).map { it.name },
        )
    }

    @Test
    fun nonvaryingPredecessorColumnsGetZeroCoefficients() {
        val axis = moodAxis(learn(chain()).ending("e"))
        assertNull(axis.fallback)
        val parameters = checkNotNull(axis.parameters)
        listOf(
            PredecessorContextColumn.IMMEDIATE_DURATION_HOURS,
            PredecessorContextColumn.IMMEDIATE_EXPOSURE_COUNT,
            PredecessorContextColumn.ACTIVITY_REVIEWED_ENTRIES,
            PredecessorContextColumn.ACTIVITY_UNKNOWN,
            PredecessorContextColumn.WORKOUT_REVIEWED_ENTRIES,
            PredecessorContextColumn.WORKOUT_UNKNOWN,
            PredecessorContextColumn.DIET_REVIEWED_ENTRIES,
            PredecessorContextColumn.DIET_UNKNOWN,
            PredecessorContextColumn.USAGE_REVIEWED_ENTRIES,
            PredecessorContextColumn.USAGE_UNKNOWN,
            PredecessorContextColumn.HOURS_SINCE_BOUNDARY,
            PredecessorContextColumn.BOUNDARY_UNAVAILABLE,
        ).forEach { column ->
            val parameter = parameters.single { it.column == column }
            assertEquals(true, parameter.nonvarying)
            assertEquals(0.0, parameter.coefficient, 0.0)
        }
    }

    @Test
    fun aRejectedPredecessorViewFallsBackToTheLockedNullForecast() {
        val data = chain()
        val bundle = projectForecastExamples(data)
        val locked = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            zone,
        )
        val rejected = projectPredecessorContextLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            CausalPredecessorNeighborhoodDataset(neighborhoods = emptyList()),
            zone,
        )
        rejected.folds.zip(locked.folds).forEach { (fold, nullFold) ->
            fold.axes.zip(nullFold.axes).forEach { (axis, nullAxis) ->
                if (nullAxis.forecast != null) {
                    assertEquals(PredecessorForecastFallback.NULL_V2, axis.fallback)
                    assertEquals(PredecessorNullFallbackCause.VIEW_UNAVAILABLE, axis.nullFallbackCause)
                    assertEquals(nullAxis.forecast, axis.forecast)
                    assertEquals(nullAxis.expectedResidual, axis.expectedResidual)
                }
            }
        }
        val again = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            zone,
        )
        assertEquals(locked, again)
        assertEquals(NULL_TRAJECTORY_PROTOCOL, locked.protocol)
        assertEquals(NULL_TRAJECTORY_GATE, locked.gate)
    }

    @Test
    fun theCandidateDoesNotReadIdentitiesQuantitiesBackgroundsOrSuccessors() {
        val named = categoryDataset("activity", CategoryObservationKindV2.ENTRIES, named = true)
        val projected = view(named, "b")
        assertFalse(projected.toString().contains("SecretWalk"))
        assertFalse(projected.toString().contains("12 mg"))
        assertFalse(predecessorFeatureCompactLabel(projected).contains("SecretWalk"))
        assertEquals(1, projected.audit.immediateExposureCount)
        val background = chain().let { data ->
            val first = data.states.first()
            data.copy(
                states = listOf(state("side", first.recordedAt - 2 * DAY, mood = 8)) + data.states,
                intervals = listOf(
                    linked("side", "a", first.recordedAt - 2 * DAY, first.recordedAt - DAY, activity = namedActivity()),
                ) + data.intervals,
            )
        }
        assertEquals(view(chain(), "e"), view(background, "e"))
        val feature = source(
            "src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorFeatureView.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/CausalPredecessorFeatureView.kt",
        ).readText()
        val learner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PredecessorContextLearner.kt",
        ).readText()
        listOf(".backgrounds", ".comparable", "amountText", "successorNeighborhood", "displayName").forEach { token ->
            assertFalse(feature.contains(token))
            assertFalse(learner.contains(token))
        }
        assertTrue(feature.contains("causal-predecessor-feature-view-v2"))
        assertTrue(learner.contains("predecessor-context-learner-v2"))
        assertTrue(learner.contains("predecessor-context-gate-v2"))
        assertTrue(learner.contains("projectNullTrajectoryLearner"))
        assertFalse(learner.contains("fun projectNullTrajectoryLearner"))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Predecessor-context learner"))
        val replay = lab.substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("projectExpertHorizon"))
    }

    private fun learn(
        data: CheckInAnalysisDatasetV2,
        moodOn: Pair<String, Int>? = null,
        scenario: (TransitionScenario) -> TransitionScenario = { it },
    ): PredecessorContextEvaluation {
        val bundle = projectForecastExamples(data)
        val states = bundle.stateForecasts.examples.map { example ->
            if (moodOn != null && example.identity.endingKey.raw == moodOn.first) {
                example.copy(target = withMood(example.target, moodOn.second))
            } else {
                example
            }
        }
        val conditional = bundle.conditionalTransitions.examples.map { example ->
            val next = example.copy(scenario = scenario(example.scenario))
            if (moodOn != null && next.identity.endingKey.raw == moodOn.first) {
                next.copy(target = withMood(next.target, moodOn.second))
            } else {
                next
            }
        }
        return projectPredecessorContextLearner(
            states,
            conditional,
            projectCausalPredecessorNeighborhood(data, zone),
            zone,
        )
    }

    private fun PredecessorContextEvaluation.ending(id: String) = folds.single { it.identity.endingKey.raw == id }

    private fun moodAxis(fold: PredecessorContextFold) = fold.axes.single { it.axisId == DefaultCheckInAxes.MOOD }

    private fun moodForecast(fold: PredecessorContextFold) = moodAxis(fold).forecast

    private fun view(data: CheckInAnalysisDatasetV2, id: String): PredecessorFeatureView {
        return projectPredecessorFeatureView(project(data).neighborhood(id))
    }

    private fun PredecessorFeatureView.value(channel: PredecessorFeatureChannel): Double = checkNotNull(reading(channel))

    private fun project(data: CheckInAnalysisDatasetV2) = projectCausalPredecessorNeighborhood(data, zone)

    private fun CausalPredecessorNeighborhoodDataset.neighborhood(id: String) =
        neighborhoods.single { it.focal.stateKey.raw == id }

    private fun PredecessorFeatureAudit.knowledge(category: String): CategoryKnowledge? = when (category) {
        "activity" -> activity
        "workout" -> workout
        "diet" -> diet
        "usage" -> usage
        else -> error(category)
    }

    private fun entriesChannel(category: String) = when (category) {
        "activity" -> PredecessorFeatureChannel.ACTIVITY_REVIEWED_ENTRIES
        "workout" -> PredecessorFeatureChannel.WORKOUT_REVIEWED_ENTRIES
        "diet" -> PredecessorFeatureChannel.DIET_REVIEWED_ENTRIES
        "usage" -> PredecessorFeatureChannel.USAGE_REVIEWED_ENTRIES
        else -> error(category)
    }

    private fun unknownChannel(category: String) = when (category) {
        "activity" -> PredecessorFeatureChannel.ACTIVITY_UNKNOWN
        "workout" -> PredecessorFeatureChannel.WORKOUT_UNKNOWN
        "diet" -> PredecessorFeatureChannel.DIET_UNKNOWN
        "usage" -> PredecessorFeatureChannel.USAGE_UNKNOWN
        else -> error(category)
    }

    private fun chain(): CheckInAnalysisDatasetV2 {
        val start = 8L * DAY
        val ids = listOf("a", "b", "c", "d", "e")
        val states = ids.mapIndexed { index, id -> state(id, start + index * HOUR, mood = index + 1) }
        return dataset(
            states,
            states.zipWithNext().map { (previous, next) ->
                linked(next.checkInId, previous.checkInId, previous.recordedAt, next.recordedAt)
            },
        )
    }

    private fun linkedPair() = dataset(
        states = listOf(state("a", 0L, mood = 1), state("b", HOUR, mood = 2)),
        intervals = listOf(linked("b", "a", 0L, HOUR)),
    )

    private fun categoryDataset(
        category: String,
        kind: CategoryObservationKindV2,
        named: Boolean = false,
    ): CheckInAnalysisDatasetV2 {
        val endAt = HOUR
        val activity = if (category == "activity") activityCategory(kind, endAt, named) else reviewedNoneActivity(endAt)
        val workout = if (category == "workout") workoutCategory(kind, endAt) else reviewedNoneWorkout(endAt)
        val diet = if (category == "diet") dietCategory(kind, endAt) else reviewedNoneDiet(endAt)
        val usage = if (category == "usage") usageCategory(kind, endAt, named) else reviewedNoneUsage(endAt)
        return dataset(
            states = listOf(state("a", 0L, mood = 1), state("b", endAt, mood = 2)),
            intervals = listOf(
                ContextIntervalObservationV2(
                    endingCheckInId = "b",
                    previousCheckInId = "a",
                    windowStartAt = 0L,
                    windowEndAt = endAt,
                    durationMillis = endAt,
                    startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    completedAt = endAt,
                    activity = activity,
                    workout = workout,
                    diet = diet,
                    usage = usage,
                ),
            ),
        )
    }

    private fun branch(activityOnRight: Boolean): CheckInAnalysisDatasetV2 {
        val start = 9L * DAY
        return dataset(
            states = listOf(
                state("origin", start, mood = 1),
                state("left", start + HOUR, mood = 2),
                state("right", start + 2 * HOUR, mood = 3),
            ),
            intervals = listOf(
                linked("left", "origin", start, start + HOUR),
                linked(
                    "right",
                    "origin",
                    start,
                    start + 2 * HOUR,
                    activity = if (activityOnRight) namedActivity() else reviewedNoneActivity(start + 2 * HOUR),
                ),
            ),
        )
    }

    private fun ambiguousDataset(): CheckInAnalysisDatasetV2 {
        val start = 10L * DAY
        return dataset(
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
    }

    private fun unresolvedDataset(): CheckInAnalysisDatasetV2 {
        val start = 11L * DAY
        return dataset(
            states = listOf(state("earlier", start, mood = 4), state("end", start + HOUR, mood = 1)),
            intervals = listOf(linked("end", "ghost", start, start + HOUR)),
        )
    }

    private fun invalidDataset(): CheckInAnalysisDatasetV2 {
        val start = 12L * DAY
        return dataset(
            states = listOf(state("early", start + HOUR, mood = 1), state("late", start + 5 * HOUR, mood = 8)),
            intervals = listOf(linked("early", "late", start, start + HOUR)),
        )
    }

    private fun cycleDataset(): CheckInAnalysisDatasetV2 {
        val start = 13L * DAY
        return dataset(
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
    }

    private fun withMood(target: ObservedAxisTarget, raw: Int) = ObservedAxisTarget(
        target.readings.map { reading ->
            if (reading.axisId == DefaultCheckInAxes.MOOD) reading.copy(raw = raw) else reading
        },
    )

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun state(id: String, at: Long, mood: Int): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
        )
    }

    private fun linked(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        complete: Boolean = true,
        activity: ActivityCategoryObservationV2 = reviewedNoneActivity(endAt),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = if (complete) endAt else null,
        activity = activity,
        workout = reviewedNoneWorkout(endAt),
        diet = reviewedNoneDiet(endAt),
        usage = reviewedNoneUsage(endAt),
    )

    private fun boundary(end: String, previous: String?, start: Long, endAt: Long, basis: String) =
        linked(end, previous ?: end, start, endAt).copy(
            previousCheckInId = previous,
            startBasis = basis,
        )

    private fun reviewedNoneActivity(at: Long) =
        ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun reviewedNoneWorkout(at: Long) =
        WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun reviewedNoneDiet(at: Long) =
        DietCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun reviewedNoneUsage(at: Long) =
        UsageCategoryObservationV2(CategoryObservationKindV2.NONE, at, emptyList())

    private fun unreviewedActivity() =
        ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList())

    private fun namedActivity() = ActivityCategoryObservationV2(
        CategoryObservationKindV2.ENTRIES,
        HOUR,
        listOf(ActivityObservationV2("secret", null, "SecretWalk", 0L)),
    )

    private fun secretExposure() = ReportedBehavior(
        behaviorIdentity(BehaviorKind.ACTIVITY, null, "SecretWalk"),
        "SecretWalk",
    )

    private fun activityCategory(kind: CategoryObservationKindV2, at: Long, named: Boolean) =
        ActivityCategoryObservationV2(
            kind,
            if (kind == CategoryObservationKindV2.UNREVIEWED) null else at,
            if (kind == CategoryObservationKindV2.ENTRIES && named) {
                listOf(ActivityObservationV2("secret", null, "SecretWalk", 0L))
            } else {
                emptyList()
            },
        )

    private fun workoutCategory(kind: CategoryObservationKindV2, at: Long) =
        WorkoutCategoryObservationV2(kind, if (kind == CategoryObservationKindV2.UNREVIEWED) null else at, emptyList())

    private fun dietCategory(kind: CategoryObservationKindV2, at: Long) =
        DietCategoryObservationV2(kind, if (kind == CategoryObservationKindV2.UNREVIEWED) null else at, emptyList())

    private fun usageCategory(kind: CategoryObservationKindV2, at: Long, named: Boolean) =
        UsageCategoryObservationV2(
            kind,
            if (kind == CategoryObservationKindV2.UNREVIEWED) null else at,
            if (kind == CategoryObservationKindV2.ENTRIES && named) {
                listOf(UsageObservationV2("u", null, "tea", "supportive", "12 mg", 0L))
            } else {
                emptyList()
            }
        )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
