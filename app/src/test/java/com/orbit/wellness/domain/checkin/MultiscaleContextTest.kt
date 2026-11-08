package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MultiscaleContextTest {
    @Test
    fun nearZeroDeviationDoesNotExplode() {
        val train = timedEvidence(
            states = listOf(timed("a", 0L, mood = 1.0), timed("b", HOUR, mood = 1.0 + 1e-12)),
            transitions = emptyList(),
        )
        val encoded = encodeFeatures(train)
        val mood = encoded.schemas.state.let { schema ->
            fitFeaturePreparation(encoded).state.scale(FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD))
        }
        assertTrue(mood.nonvarying)
        assertEquals(0.0, mood.scale, 0.0)
        assertFalse(deviationChangesGeometry(1e-12, 1.0))
        assertTrue(deviationChangesGeometry(0.5, 0.5))
        val heldOut = timedEvidence(states = listOf(timed("c", 2 * HOUR, mood = 0.0)), transitions = emptyList())
        val model = fitFeaturePreparation(encoded)
        val prepared = projectLocalContext(heldOut, encodeFeatures(heldOut, encoded.schemas), model)
        val value = prepared.states.single().channel.substantive.read(
            model.state.substantive,
            FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD),
        )
        assertTrue(value.second)
        assertEquals(0.0, value.first, 0.0)
        assertTrue(abs(value.first) < 1.0)
    }

    @Test
    fun constantColumnsStayInSchemaWithoutDilutingActiveOnes() {
        val context = projectLocal(axisEvidence())
        val axes = context.model.state.blocks.single { it.block == SubstantiveBlock.STATE_AXES }
        assertEquals(4, axes.dimensionCount)
        assertEquals(1, axes.effectiveDimensionCount)
        assertEquals(1.0, axes.factor, 0.0)
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val energy = FeatureSemantic.AxisValue(DefaultCheckInAxes.ENERGY)
        assertFalse(context.model.state.scale(mood).nonvarying)
        assertTrue(context.model.state.scale(energy).nonvarying)
        assertTrue(context.model.state.substantive.features.any { it.sourceSemantic == energy })
        assertEquals(-1.0, context.stateValue("low", mood).first, 0.0)
        assertEquals(0.0, context.stateValue("low", energy).first, 0.0)
        assertTrue(context.stateValue("low", energy).second)
    }

    @Test
    fun totalAndEffectiveBlockDimensionsAreBothExposed() {
        val block = projectLocal(axisEvidence()).model.state.blocks.single { it.block == SubstantiveBlock.STATE_AXES }
        assertEquals(4, block.dimensionCount)
        assertEquals(1, block.effectiveDimensionCount)
        assertTrue(block.effectiveDimensionCount < block.dimensionCount)
    }

    @Test
    fun anEntirelyNonvaryingBlockAddsNoPairwiseGeometry() {
        val context = projectLocal(constantEmotionEvidence())
        val emotion = context.model.state.blocks.single { it.block == SubstantiveBlock.STATE_EMOTION }
        assertTrue(emotion.dimensionCount > 0)
        assertEquals(0, emotion.effectiveDimensionCount)
        assertEquals(0.0, emotion.factor, 0.0)
        val calm = FeatureSemantic.EmotionPresent("calm")
        val first = context.stateValue("p", calm)
        val second = context.stateValue("q", calm)
        assertTrue(first.second && second.second)
        assertEquals(0.0, first.first, 0.0)
        assertEquals(0.0, second.first, 0.0)
        assertEquals(first.first, second.first, 0.0)
    }

    @Test
    fun trailingDayIncludesTheFarEdgeAndExcludesTheAnchor() {
        val day = anchored().trailingDay
        assertEquals(T - DAY, day.windowStartAt)
        assertEquals(T, day.windowEndAt)
        assertEquals(3, day.stateHistory.coverage.historicalStateCount)
        assertEquals(T - DAY, day.stateHistory.coverage.earliestStateAt)
        assertEquals(T - 2 * HOUR, day.stateHistory.coverage.latestStateAt)
        assertTrue(day.stateHistory.coverage.latestStateAt!! < T)
    }

    @Test
    fun trailingWeekExcludesEvidenceAfterTheAnchor() {
        val week = anchored().trailingWeek.stateHistory.coverage
        assertEquals(T - WEEK, week.earliestStateAt)
        assertEquals(T - 2 * HOUR, week.latestStateAt)
        assertEquals(4, week.historicalStateCount)
        assertTrue(week.latestStateAt!! < T)
    }

    @Test
    fun anIntervalEndingAtTheAnchorIsIncluded() {
        val transitions = anchored().trailingDay.transitionHistory
        assertEquals(1, transitions.includedTransitionCount)
        assertEquals(HOUR, transitions.representedDurationMillis)
        val walk = transitions.dimension(activity("Walk"))
        assertEquals(1.0, walk.intervalPrevalence!!, 0.0)
        assertTrue(walk.available)
    }

    @Test
    fun anIntervalCrossingTheStartIsOverlappingAndExcluded() {
        val multi = clock()
        val day = multi.state("anchor").trailingDay.transitionHistory
        val week = multi.state("anchor").trailingWeek.transitionHistory
        assertEquals(1, day.overlappingTransitionCount)
        assertEquals(0.0, day.dimension(activity("Run")).intervalPrevalence!!, 0.0)
        assertEquals(0.5, week.dimension(activity("Run")).intervalPrevalence!!, 0.0)
        assertEquals(1, day.incompleteTransitionCount)
        assertEquals(0.0, day.dimension(activity("Hidden")).intervalPrevalence!!, 0.0)
    }

    @Test
    fun missingStateDimensionsStayOutOfTheSummary() {
        val multi = clock()
        val energy = FeatureSemantic.AxisValue(DefaultCheckInAxes.ENERGY)
        val summary = multi.state("anchor").trailingDay.stateHistory.dimension(energy)
        val expected = multi.localValue("mid", energy)
        assertEquals(1, summary.observedCount)
        assertEquals(expected.first, summary.mean, 1e-12)
        assertEquals(expected.first, summary.minimum, 1e-12)
        assertEquals(expected.first, summary.maximum, 1e-12)
        assertTrue(summary.deviation == 0.0 && summary.deviation.isFinite())
        assertTrue(summary.trend is TrendStatus.Unavailable)
    }

    @Test
    fun anObservedZeroEntersTheStateSummary() {
        val multi = clock()
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val summary = multi.state("anchor").trailingDay.stateHistory.dimension(mood)
        val included = listOf("edge", "mid", "recent").map { multi.localValue(it, mood).first }
        assertEquals(3, summary.observedCount)
        assertEquals(included.average(), summary.mean, 1e-12)
        assertTrue(included.contains(multi.localValue("edge", mood).first))
        val withoutZero = listOf("mid", "recent").map { multi.localValue(it, mood).first }.average()
        assertTrue(withoutZero != summary.mean)
    }

    @Test
    fun stateAxisTrendNeedsTwoDistinctTimestamps() {
        val sameTime = project(sameTimestampEvidence())
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        assertTrue(sameTime.state("focal").trailingDay.stateHistory.dimension(mood).trend is TrendStatus.Unavailable)
        val distinct = clock().state("anchor").trailingDay.stateHistory.dimension(mood).trend
        assertTrue(distinct is TrendStatus.Slope)
        assertTrue((distinct as TrendStatus.Slope).perHour.isFinite())
    }

    @Test
    fun emotionPrevalenceHasNoTrend() {
        val calm = clock().state("anchor").trailingDay.stateHistory.dimension(FeatureSemantic.EmotionPresent("calm"))
        assertTrue(calm.available)
        assertTrue(calm.observedCount > 0)
        assertTrue(calm.trend is TrendStatus.NotApplicable)
    }

    @Test
    fun transitionMeansIgnoreUnobservedSlots() {
        val history = project(amountEvidence())
        val amount = FeatureSemantic.Usage(
            categoricalKey(null, "Cigarettes"),
            "COPING",
            UsageMeasurement.AMOUNT,
            "unitless",
        )
        val summary = history.state("anchor").trailingDay.transitionHistory.dimension(amount)
        val observed = listOf("low", "high").map { history.transitionValue(it, amount).first }
        assertEquals(2, summary.observedCount)
        assertEquals(observed.average(), summary.mean, 1e-12)
    }

    @Test
    fun activityPresenceMeanIsIntervalPrevalence() {
        val history = project(prevalenceEvidence())
        val walk = history.state("anchor").trailingDay.transitionHistory.dimension(activity("Walk"))
        assertEquals(0.5, walk.intervalPrevalence!!, 0.0)
        assertEquals(0.5, walk.mean, 0.0)
        val representedHours = history.state("anchor").trailingDay.transitionHistory.representedDurationMillis / HOUR.toDouble()
        assertTrue(walk.intervalPrevalence != representedHours)
    }

    @Test
    fun anUnreviewedCategoryDoesNotCountAsAbsence() {
        val history = project(unreviewedEvidence())
        val walk = history.state("anchor").trailingDay.transitionHistory.dimension(activity("Walk"))
        assertEquals(1, walk.observedCount)
        assertEquals(1.0, walk.intervalPrevalence!!, 0.0)
    }

    @Test
    fun aStateWithNoHistoryHasUnavailableSummaries() {
        val alone = project(loneEvidence()).state("alone")
        assertFalse(alone.trailingDay.stateHistory.available)
        assertFalse(alone.trailingWeek.stateHistory.available)
        assertFalse(alone.trailingDay.transitionHistory.available)
        assertTrue(alone.trailingDay.stateHistory.geometry.observed.none { it })
        assertTrue(alone.currentMinusDay.values.observed.none { it })
        assertTrue(alone.currentMinusWeek.values.observed.none { it })
        assertTrue(alone.dayMinusWeek.values.observed.none { it })
    }

    @Test
    fun currentMinusDayRequiresBothSides() {
        val anchor = clock().state("anchor")
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val energy = FeatureSemantic.AxisValue(DefaultCheckInAxes.ENERGY)
        val moodDeviation = anchor.currentMinusDay.read(anchor, mood)
        val energyDeviation = anchor.currentMinusDay.read(anchor, energy)
        assertTrue(moodDeviation.second)
        assertTrue(moodDeviation.third > 0)
        assertFalse(energyDeviation.second)
        assertEquals(0, energyDeviation.third)
    }

    @Test
    fun currentMinusWeekAndDayMinusWeekUseTheSameMissingnessRule() {
        val anchor = clock().state("anchor")
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val confidence = FeatureSemantic.AxisValue(DefaultCheckInAxes.CONFIDENCE)
        assertTrue(anchor.currentMinusWeek.read(anchor, mood).second)
        assertTrue(anchor.dayMinusWeek.read(anchor, mood).second)
        assertFalse(anchor.currentMinusWeek.read(anchor, confidence).second)
        assertFalse(anchor.dayMinusWeek.read(anchor, confidence).second)
        assertEquals(0, anchor.dayMinusWeek.read(anchor, confidence).third)
    }

    @Test
    fun moodSixAfterALowerWeekIsPositiveRelativeToThatWeek() {
        val anchor = clock().state("anchor")
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val difference = anchor.currentMinusWeek.read(anchor, mood)
        assertTrue(difference.second)
        assertTrue(difference.first > 0.0)
    }

    @Test
    fun majorSegmentFactorsAreSchemaLevel() {
        val multi = clock()
        val self = multi.schema.retrospective.single { it.name == "local.self" }
        val transition = multi.schema.retrospective.single { it.name == "trailing.day.transition" }
        val expectedStateBlocks = multi.model.state.blocks.count { it.effectiveDimensionCount > 0 }
        val expectedTransitionBlocks = multi.model.transition.blocks.count { it.effectiveDimensionCount > 0 }
        assertEquals(MULTISCALE_SEGMENT_WEIGHT, self.weight, 0.0)
        assertEquals(expectedStateBlocks, self.effectiveBlockCount)
        assertEquals(1.0 / sqrt(expectedStateBlocks.toDouble()), self.factor, 0.0)
        assertEquals(expectedTransitionBlocks, transition.effectiveBlockCount)
        assertEquals(1.0 / sqrt(expectedTransitionBlocks.toDouble()), transition.factor, 0.0)
        assertTrue(multi.states.all { flatten(it, multi.schema).values.size == multi.schema.retrospective.sumOf { segment -> segment.features.size } })
    }

    @Test
    fun unavailableSegmentsDoNotRenormalizeTheOthers() {
        val multi = clock()
        val factor = multi.schema.retrospective.single { it.name == "local.self" }.factor
        val withOutgoing = scaleOfSelf(multi, "anchor")
        val withoutOutgoing = scaleOfSelf(multi, "too-old")
        assertFalse(multi.state("too-old").local.outgoing.available)
        assertTrue(multi.state("anchor").local.outgoing.available)
        assertEquals(factor, withOutgoing, 1e-9)
        assertEquals(factor, withoutOutgoing, 1e-9)
    }

    @Test
    fun reversedSourceOrderKeepsDescriptorsAndFlatVectors() {
        val evidence = clockEvidence()
        val reversed = evidence.copy(
            states = evidence.states.reversed(),
            transitions = evidence.transitions.reversed(),
            retrospectiveTransitions = evidence.retrospectiveTransitions.reversed(),
            causalExamples = evidence.causalExamples.reversed(),
        )
        val first = project(evidence)
        val second = project(reversed)
        val anchor = "anchor"
        assertEquals(first.state(anchor).trailingDay, second.state(anchor).trailingDay)
        assertEquals(first.state(anchor).trailingWeek, second.state(anchor).trailingWeek)
        assertEquals(first.state(anchor).currentMinusWeek, second.state(anchor).currentMinusWeek)
        assertEquals(flatten(first.state(anchor), first.schema), flatten(second.state(anchor), second.schema))
    }

    @Test
    fun multiscaleStateKeepsLocalDirection() {
        val evidence = clockEvidence()
        val local = projectLocal(evidence)
        val multi = projectMultiscale(evidence, local)
        val state = multi.state("anchor")
        assertEquals(local.contextualStates.single { it.key.raw == "anchor" }, state.local)
        assertNotEquals(state.local.incoming.transition.values, state.local.outgoing.transition.values)
        assertEquals("local.incoming.transition", multi.schema.retrospective[1].name)
        assertEquals("local.outgoing.transition", multi.schema.retrospective[3].name)
    }

    @Test
    fun multiscaleConstructionDoesNotRewriteTransitions() {
        val evidence = clockEvidence()
        val local = projectLocal(evidence)
        val before = local.transitions.map { it.key to it.channel }
        projectMultiscale(evidence, local)
        assertEquals(before, local.transitions.map { it.key to it.channel })
    }

    @Test
    fun causalHistoryStopsAtTheStartingState() {
        val start = checkNotNull(clock().causal("forward").input.startingState)
        assertEquals(StateOccurrenceKey("anchor"), start.key)
        assertEquals(T - 2 * HOUR, start.trailingDay.stateHistory.coverage.latestStateAt)
        assertEquals(1, start.trailingDay.transitionHistory.includedTransitionCount)
        assertEquals(0.0, start.trailingDay.transitionHistory.dimension(activity("FutureWalk")).intervalPrevalence!!, 0.0)
        assertTrue(clock().schema.causal.none { it.name.contains("outgoing") })
    }

    @Test
    fun futureChangesDoNotChangeTheCausalInput() {
        val evidence = clockEvidence()
        val encoded = encodeFeatures(evidence)
        val model = fitFeaturePreparation(encoded)
        val first = project(evidence, model, encoded.schemas)
        val changedFuture = clockEvidence(futureMood = 0.05)
        val second = project(changedFuture, model, encoded.schemas)
        val original = first.causal("forward").input
        val updated = second.causal("forward").input
        assertEquals(original, updated)
        val targetOnly = encoded.copy(
            causalExamples = encoded.causalExamples.map { example ->
                if (example.exampleKey.raw != "forward") {
                    example
                } else {
                    val anxiety = encoded.schemas.state.indexOf(FeatureSemantic.AxisValue(DefaultCheckInAxes.ANXIETY))
                    val values = example.target.values.toMutableList()
                    val observed = example.target.observed.toMutableList()
                    values[anxiety] = 0.77
                    observed[anxiety] = true
                    example.copy(target = EncodedVector(values, observed))
                }
            },
        )
        val local = projectLocalContext(evidence, targetOnly, model)
        val third = projectMultiscale(evidence, local)
        assertEquals(original, third.causalExamples.single { it.exampleKey.raw == "forward" }.input)
        assertNotEquals(
            first.causal("forward").target,
            third.causalExamples.single { it.exampleKey.raw == "forward" }.target,
        )
    }

    @Test
    fun ineligibleExamplesRemainOutsideTheEligibleList() {
        val multi = clock()
        val held = multi.causal("wake")
        assertFalse(held.eligibility.eligible)
        assertEquals(listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL), held.eligibility.reasons)
        assertNull(held.input.startingState)
        assertTrue(multi.eligibleCausalExamples.none { it.exampleKey == held.exampleKey })
        assertTrue(multi.eligibleCausalExamples.any { it.exampleKey.raw == "forward" })
    }

    @Test
    fun multiscaleValuesStayFinite() {
        val multi = clock()
        assertTrue(multi.diagnostics.isEmpty())
        multi.states.forEach { state ->
            assertTrue(flatten(state, multi.schema).values.all { it.isFinite() })
            assertTrue(state.trailingDay.stateHistory.geometry.values.all { it.isFinite() })
            assertTrue(state.trailingWeek.transitionHistory.geometry.values.all { it.isFinite() })
            assertTrue(state.currentMinusDay.values.values.all { it.isFinite() })
            assertTrue(state.currentMinusWeek.values.values.all { it.isFinite() })
            assertTrue(state.dayMinusWeek.values.values.all { it.isFinite() })
        }
        multi.causalExamples.mapNotNull { it.input.startingState }.forEach { start ->
            assertTrue(flattenCausalStart(start, multi.schema).values.all { it.isFinite() })
        }
    }

    @Test
    fun identitiesAndProtocolStayOutsideSubstantiveGeometry() {
        val multi = clock()
        val semantics = multi.schema.retrospective.flatMap { segment -> segment.features.map { it.sourceSemantic } }
        val names = multi.schema.retrospective.flatMap { segment -> segment.features.map { it.sourceName } }
        assertTrue(semantics.none { it is FeatureSemantic.ProtocolCompleted || it is FeatureSemantic.StartBasis })
        assertTrue(semantics.none { it is FeatureSemantic.CategoryStatus || it is FeatureSemantic.WearableAmbiguous })
        listOf("anchor", "forward", "note-not-a-feature").forEach { token ->
            assertTrue(names.none { it.contains(token) })
        }
        val banned = setOf(SENTINEL.toDouble(), T.toDouble())
        multi.states.forEach { state ->
            assertTrue(flatten(state, multi.schema).values.none { it in banned })
        }
    }

    private fun clock(): MultiscaleContext = project(clockEvidence())

    private fun anchored() = clock().state("anchor")

    private fun project(
        evidence: ContextualEvidence,
        model: FeaturePreparationModel? = null,
        schemas: FeatureSchemas? = null,
    ): MultiscaleContext {
        val encoded = if (schemas == null) encodeFeatures(evidence) else encodeFeatures(evidence, schemas)
        val local = if (model == null) {
            projectLocalContext(evidence, encoded)
        } else {
            projectLocalContext(evidence, encoded, model)
        }
        return projectMultiscale(evidence, local).also { projected ->
            if (model != null) {
                check(projected.schema.retrospective.isNotEmpty())
            }
        }
    }

    private fun projectLocal(evidence: ContextualEvidence): LocalContext {
        return projectLocalContext(evidence, encodeFeatures(evidence))
    }

    private val MultiscaleContext.model: FeaturePreparationModel
        get() = projectLocal(clockEvidence()).model

    private fun MultiscaleContext.state(id: String) = states.single { it.key.raw == id }

    private fun MultiscaleContext.causal(id: String) = causalExamples.single { it.exampleKey.raw == id }

    private fun LocalContext.stateValue(id: String, semantic: FeatureSemantic): Pair<Double, Boolean> {
        return states.single { it.key.raw == id }.channel.substantive.read(model.state.substantive, semantic)
    }

    private fun MultiscaleContext.localValue(id: String, semantic: FeatureSemantic): Pair<Double, Boolean> {
        val local = projectLocal(clockEvidence())
        return local.stateValue(id, semantic)
    }

    private fun MultiscaleContext.transitionValue(id: String, semantic: FeatureSemantic): Pair<Double, Boolean> {
        val local = projectLocal(amountEvidence())
        return local.transitions.single { it.key.raw == id }.channel.substantive.read(
            local.model.transition.substantive,
            semantic,
        )
    }

    private fun PreparedChannelSchema.scale(semantic: FeatureSemantic): FeatureScale {
        return (substantive.features + quality.features).single { it.sourceSemantic == semantic }
    }

    private fun MaskedVector.read(segment: VectorSegment, semantic: FeatureSemantic): Pair<Double, Boolean> {
        val index = segment.features.indexOfFirst { it.sourceSemantic == semantic }
        check(index >= 0) { "Missing $semantic" }
        return values[index] to observed[index]
    }

    private fun StateHistorySummary.dimension(semantic: FeatureSemantic) =
        dimensions.single { it.semantic == semantic }

    private fun TransitionHistorySummary.dimension(semantic: FeatureSemantic) =
        dimensions.single { it.semantic == semantic }

    private fun DeviationGroup.read(
        state: MultiscaleState,
        semantic: FeatureSemantic,
    ): Triple<Double, Boolean, Int> {
        val index = state.local.self.let { vector ->
            clock().schema.retrospective.single { it.name == "local.self" }.features
                .indexOfFirst { it.sourceSemantic == semantic }
        }
        check(index >= 0)
        return Triple(values.values[index], values.observed[index], support[index])
    }

    private fun scaleOfSelf(multi: MultiscaleContext, id: String): Double {
        val state = multi.state(id)
        val index = multi.schema.retrospective.single { it.name == "local.self" }.features
            .indexOfFirst { it.sourceSemantic == FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD) }
        val flat = flatten(state, multi.schema).values[index]
        val local = state.local.self.values[index]
        return flat / local
    }
}

private fun axisEvidence(): ContextualEvidence {
    return timedEvidence(
        states = listOf(timed("low", 0L, mood = 0.0, energy = 0.5), timed("high", HOUR, mood = 1.0, energy = 0.5)),
        transitions = emptyList(),
    )
}

private fun constantEmotionEvidence(): ContextualEvidence {
    return timedEvidence(
        states = listOf(
            timed("p", 0L, mood = 0.0, emotion = "calm"),
            timed("q", HOUR, mood = 1.0, emotion = "calm"),
        ),
        transitions = emptyList(),
    )
}

private fun sameTimestampEvidence(): ContextualEvidence {
    val focal = timed("focal", T, mood = 0.4)
    val left = timed("left", T - 5 * HOUR, mood = 0.2)
    val right = timed("right", T - 5 * HOUR, mood = 0.8)
    return timedEvidence(states = listOf(focal, left, right), transitions = emptyList())
}

private fun loneEvidence(): ContextualEvidence {
    return timedEvidence(states = listOf(timed("alone", T, mood = 0.4)), transitions = emptyList())
}

private fun prevalenceEvidence(): ContextualEvidence {
    val anchor = timed("anchor", T, mood = 0.4)
    val present = interval("present", T - 2 * HOUR, T - HOUR, activities = listOf("Walk"))
    val absent = interval("absent", T - 4 * HOUR, T - 3 * HOUR)
    return timedEvidence(
        states = listOf(anchor, timed("earlier", T - 4 * HOUR, mood = 0.2)),
        transitions = listOf(present, absent),
        links = listOf(
            endpoint(present, "earlier", "anchor"),
            endpoint(absent, "earlier", "anchor"),
        ),
    )
}

private fun unreviewedEvidence(): ContextualEvidence {
    val anchor = timed("anchor", T, mood = 0.4)
    val reviewed = interval("reviewed", T - 2 * HOUR, T - HOUR, activities = listOf("Walk"))
    val hidden = interval(
        "hidden",
        T - 4 * HOUR,
        T - 3 * HOUR,
        activities = listOf("Walk"),
        kind = CategoryObservationKindV2.UNREVIEWED,
    )
    return timedEvidence(
        states = listOf(anchor, timed("earlier", T - 4 * HOUR, mood = 0.2)),
        transitions = listOf(reviewed, hidden),
        links = listOf(endpoint(reviewed, "earlier", "anchor"), endpoint(hidden, "earlier", "anchor")),
    )
}

private fun amountEvidence(): ContextualEvidence {
    val anchor = timed("anchor", T, mood = 0.4)
    val low = interval("low", T - 6 * HOUR, T - 5 * HOUR, usage = listOf(cigarettes("2")))
    val high = interval("high", T - 4 * HOUR, T - 3 * HOUR, usage = listOf(cigarettes("6")))
    val blank = interval("blank", T - 2 * HOUR, T - HOUR, usage = listOf(cigarettes("")))
    return timedEvidence(
        states = listOf(anchor, timed("earlier", T - 6 * HOUR, mood = 0.2)),
        transitions = listOf(low, high, blank),
        links = listOf(
            endpoint(low, "earlier", "anchor"),
            endpoint(high, "earlier", "anchor"),
            endpoint(blank, "earlier", "anchor"),
        ),
    )
}

private fun clockEvidence(futureMood: Double = 0.95): ContextualEvidence {
    val edge = timed("edge", T - DAY, mood = 0.0, emotion = "calm")
    val mid = timed("mid", T - 10 * HOUR, mood = 0.2, emotion = "calm", energy = 0.2)
    val recent = timed("recent", T - 2 * HOUR, mood = 0.8, emotion = "tense")
    val anchor = timed("anchor", T, mood = 0.6, emotion = "calm")
    val later = timed("later", T + 2 * HOUR, mood = 0.1, emotion = "calm")
    val future = timed("future", T + 3 * DAY, mood = futureMood, emotion = "calm")
    val weekEdge = timed("week-edge", T - WEEK, mood = 0.3, emotion = "calm", energy = 0.8)
    val tooOld = timed("too-old", T - WEEK - HOUR, mood = 0.4, emotion = "calm")
    val toAnchor = interval("to-anchor", T - HOUR, T, activities = listOf("Walk"))
    val crossing = interval("crossing", T - DAY - HOUR, T - DAY + HOUR, activities = listOf("Run"))
    val incomplete = interval(
        "incomplete",
        T - 3 * HOUR,
        T - 2 * HOUR,
        completed = false,
        activities = listOf("Hidden"),
    )
    val forward = interval("forward", T, T + 2 * HOUR, activities = listOf("FutureWalk"))
    val wake = interval(
        "wake",
        T - 10 * DAY,
        T - 9 * DAY,
        basis = CheckInContextStartBasis.WAKE_TIME,
    )
    return timedEvidence(
        states = listOf(edge, mid, recent, anchor, later, future, weekEdge, tooOld),
        transitions = listOf(toAnchor, crossing, incomplete, forward, wake),
        links = listOf(
            endpoint(toAnchor, "recent", "anchor"),
            endpoint(crossing, "week-edge", "edge"),
            endpoint(incomplete, "mid", "recent"),
            endpoint(forward, "anchor", "later"),
            endpoint(wake, null, "anchor"),
        ),
        causal = listOf(
            example(forward, anchor, later, eligible = true),
            example(
                wake,
                start = null,
                end = anchor,
                eligible = false,
                reasons = listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL),
            ),
        ),
    )
}

private fun timedEvidence(
    states: List<StateEvidenceFrame>,
    transitions: List<TransitionEvidenceFrame>,
    links: List<RetrospectiveTransitionEvidence> = emptyList(),
    causal: List<CausalEvidenceExample> = emptyList(),
) = ContextualEvidence(
    states = states,
    transitions = transitions,
    statesByKey = states.associateBy { it.key },
    transitionsByKey = transitions.associateBy { it.key },
    wearablesById = emptyMap(),
    retrospectiveStates = emptyList(),
    retrospectiveTransitions = links,
    causalExamples = causal,
    issues = emptyList(),
)

private fun timed(
    id: String,
    at: Long,
    mood: Double? = null,
    energy: Double? = null,
    emotion: String? = null,
) = StateEvidenceFrame(
    key = StateOccurrenceKey(id),
    evidence = StateEvidencePayload(
        axes = listOfNotNull(
            mood?.let { AxisEvidence(DefaultCheckInAxes.MOOD, (it * 10).toInt(), it) },
            energy?.let { AxisEvidence(DefaultCheckInAxes.ENERGY, (it * 10).toInt(), it) },
        ),
        coreMissingAxisIds = emptyList(),
        conflictingAxisIds = emptyList(),
        emotion = emotion?.let(::EmotionToken),
    ),
    metadata = StateEvidenceMetadata(
        recordedAt = at,
        localDate = "2099-01-02",
        trigger = "trigger-not-a-feature",
        note = "note-not-a-feature",
    ),
)

private fun interval(
    id: String,
    start: Long,
    end: Long,
    completed: Boolean = true,
    activities: List<String> = emptyList(),
    usage: List<UsageEvidence> = emptyList(),
    kind: CategoryObservationKindV2 = CategoryObservationKindV2.ENTRIES,
    basis: String = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
) = TransitionEvidenceFrame(
    key = TransitionOccurrenceKey(id),
    evidence = TransitionEvidencePayload(
        temporal = TemporalEvidence(
            startAt = start,
            endAt = end,
            durationMillis = end - start,
            startBasis = basis,
            completed = completed,
        ),
        activity = CategoryEvidence(
            kind,
            activities.map { ActivityEvidence(optionId = null, name = it, reportedAt = 1L) },
        ),
        workout = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        diet = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        usage = CategoryEvidence(
            if (usage.isEmpty()) CategoryObservationKindV2.NONE else CategoryObservationKindV2.ENTRIES,
            usage,
        ),
        wearables = emptyList(),
    ),
    metadata = TransitionEvidenceMetadata(completedAt = if (completed) end else null),
)

private fun activity(name: String) = FeatureSemantic.ActivityPresent(categoricalKey(null, name))

private fun cigarettes(amount: String) = UsageEvidence(
    optionId = null,
    name = "Cigarettes",
    intent = "COPING",
    amountText = amount,
    reportedAt = 1L,
)

private fun endpoint(frame: TransitionEvidenceFrame, start: String?, end: String) = RetrospectiveTransitionEvidence(
    frame = frame,
    startStateKey = start?.let(::StateOccurrenceKey),
    endStateKey = StateOccurrenceKey(end),
)

private fun example(
    frame: TransitionEvidenceFrame,
    start: StateEvidenceFrame?,
    end: StateEvidenceFrame,
    eligible: Boolean,
    reasons: List<CausalIneligibilityReason> = emptyList(),
) = CausalEvidenceExample(
    exampleKey = frame.key,
    input = CausalEvidenceInput(start?.evidence, frame.evidence),
    target = CausalEvidenceTarget(end.evidence),
    eligibility = CausalEligibility(eligible, reasons),
)

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
private const val T = 30 * DAY
private const val SENTINEL = 1_700_000_000_123L
