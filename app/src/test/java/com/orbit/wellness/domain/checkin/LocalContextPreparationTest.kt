package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class LocalContextPreparationTest {
    @Test
    fun scalingIgnoresUnobservedMechanicalZeros() {
        val model = fitFeaturePreparation(encodeFeatures(scalingEvidence()))
        val mood = model.state.scale(FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD))
        assertEquals(2, mood.observedCount)
        assertEquals(0.5, mood.center, 0.0)
        assertEquals(0.5, mood.scale, 0.0)
        assertFalse(mood.nonvarying)
    }

    @Test
    fun observedNumericalZeroStaysObserved() {
        val context = project(scalingEvidence())
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val recorded = context.state("scale-zero").channel.substantive.read(context.model.state.substantive, mood)
        val missing = context.state("scale-missing").channel.substantive.read(context.model.state.substantive, mood)
        assertTrue(recorded.second)
        assertEquals(-0.5, recorded.first, 0.0)
        assertFalse(missing.second)
        assertEquals(0.0, missing.first, 0.0)
    }

    @Test
    fun nonvaryingFeatureBecomesFiniteTransformedZero() {
        val context = project(scalingEvidence())
        val energy = FeatureSemantic.AxisValue(DefaultCheckInAxes.ENERGY)
        val scale = context.model.state.scale(energy)
        val prepared = context.state("scale-zero").channel.substantive.read(context.model.state.substantive, energy)
        assertTrue(scale.nonvarying)
        assertEquals(0.5, scale.center, 0.0)
        assertEquals(0.0, scale.scale, 0.0)
        assertTrue(prepared.second)
        assertEquals(0.0, prepared.first, 0.0)
        assertTrue(prepared.first.isFinite())
    }

    @Test
    fun reversedSourceOrderKeepsTheFittedPreparation() {
        val forward = neighborhoodEvidence()
        val reversed = forward.copy(
            states = forward.states.reversed(),
            transitions = forward.transitions.reversed().map { frame ->
                frame.copy(
                    evidence = frame.evidence.copy(
                        usage = frame.evidence.usage.copy(items = frame.evidence.usage.items.reversed()),
                        activity = frame.evidence.activity.copy(items = frame.evidence.activity.items.reversed()),
                    ),
                )
            },
            retrospectiveTransitions = forward.retrospectiveTransitions.reversed(),
            causalExamples = forward.causalExamples.reversed(),
        )
        val first = project(forward)
        val second = project(reversed)
        assertEquals(first.model, second.model)
        assertEquals(first.states, second.states)
        assertEquals(first.transitions, second.transitions)
        assertEquals(first.retrospectiveTransitions, second.retrospectiveTransitions)
        assertEquals(first.contextualStates, second.contextualStates)
        assertEquals(first.incomingMessages, second.incomingMessages)
        assertEquals(first.outgoingMessages, second.outgoingMessages)
        assertEquals(first.causalExamples, second.causalExamples)
    }

    @Test
    fun binaryFeaturesStayBinaryBeforeBlockScaling() {
        val context = project(vocabularyEvidence())
        val factor = context.model.transition.blocks.single { it.block == SubstantiveBlock.ACTIVITY }.factor
        val segment = context.model.transition.substantive
        val row = context.transition("sparse").channel.substantive
        segment.features.filter { it.scaling == FeatureScaling.BINARY }.forEach { feature ->
            val value = row.values[feature.index]
            val observed = row.observed[feature.index]
            assertTrue(observed)
            val beforeBlock = value / factor
            assertTrue(beforeBlock == 0.0 || beforeBlock == 1.0)
        }
        assertTrue(context.transition("sparse").channel.quality.values.all { it == 0.0 || it == 1.0 })
        assertTrue(context.transition("sparse").channel.quality.observed.all { it })
    }

    @Test
    fun blockFactorFollowsSchemaDimensionCount() {
        val context = project(vocabularyEvidence())
        val activity = context.model.transition.blocks.single { it.block == SubstantiveBlock.ACTIVITY }
        assertEquals(4, activity.dimensionCount)
        assertEquals(EQUAL_BLOCK_WEIGHT, activity.weight, 0.0)
        assertEquals(1.0 / sqrt(4.0), activity.factor, 0.0)
        assertTrue(context.model.state.blocks.none { it.block == SubstantiveBlock.STATE_EMOTION })
        assertTrue(context.model.transition.blocks.none { it.block == SubstantiveBlock.WORKOUT })
        assertTrue(context.model.transition.blocks.none { it.block == SubstantiveBlock.USAGE })
        val identity = categoricalKey(null, "One")
        val present = FeatureSemantic.ActivityPresent(identity)
        val sparse = context.transition("sparse").channel.substantive.read(context.model.transition.substantive, present)
        val full = context.transition("full").channel.substantive.read(context.model.transition.substantive, present)
        assertEquals(0.5, sparse.first, 0.0)
        assertEquals(0.5, full.first, 0.0)
        assertEquals(0.25, activityEnergy(context.transition("sparse").channel, context.model.transition), 0.0)
        assertEquals(1.0, activityEnergy(context.transition("full").channel, context.model.transition), 0.0)
    }

    @Test
    fun aLargerActivityVocabularyDoesNotGainBlockWeight() {
        val small = project(activityVocabulary(listOf("Solo")))
        val large = project(activityVocabulary((1..9).map { "Activity$it" }))
        val smallBlock = small.model.transition.blocks.single { it.block == SubstantiveBlock.ACTIVITY }
        val largeBlock = large.model.transition.blocks.single { it.block == SubstantiveBlock.ACTIVITY }
        assertEquals(1, smallBlock.dimensionCount)
        assertEquals(9, largeBlock.dimensionCount)
        assertEquals(1.0, smallBlock.factor, 0.0)
        assertEquals(1.0 / sqrt(9.0), largeBlock.factor, 0.0)
        assertEquals(1.0, activityEnergy(small.transitions.single().channel, small.model.transition), 1e-12)
        assertEquals(1.0, activityEnergy(large.transitions.single().channel, large.model.transition), 1e-12)
    }

    @Test
    fun protocolAndQualityStayOutOfSubstantiveGeometry() {
        val context = project(neighborhoodEvidence())
        val substantive = context.model.state.substantive.features + context.model.transition.substantive.features
        val quality = context.model.state.quality.features + context.model.transition.quality.features
        assertTrue(substantive.none { it.role == FeatureRole.QUALITY })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.AxisObserved })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.AxisConflicting })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.WearableAmbiguous })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.ProtocolCompleted })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.StartBasis })
        assertTrue(substantive.none { it.sourceSemantic is FeatureSemantic.CategoryStatus })
        assertTrue(substantive.none { it.sourceSemantic == FeatureSemantic.EmotionObserved })
        assertTrue(substantive.none { feature ->
            val semantic = feature.sourceSemantic
            semantic is FeatureSemantic.Usage && semantic.measurement in QUALITY_USAGE
        })
        assertTrue(quality.any { it.sourceSemantic is FeatureSemantic.ProtocolCompleted })
        assertTrue(quality.any { it.sourceSemantic is FeatureSemantic.WearableAmbiguous })
        assertTrue(quality.any { feature ->
            val semantic = feature.sourceSemantic
            semantic is FeatureSemantic.Usage && semantic.measurement == UsageMeasurement.AMOUNT_COMPLETE
        })
        assertTrue(quality.any { feature ->
            val semantic = feature.sourceSemantic
            semantic is FeatureSemantic.Usage && semantic.measurement == UsageMeasurement.PARSED
        })
    }

    @Test
    fun missingAmountAndAmbiguousWearableDoNotBecomeSubstantiveZeros() {
        val context = project(neighborhoodEvidence())
        val amount = cigarettes(UsageMeasurement.AMOUNT)
        val segment = context.model.transition.substantive
        val blank = context.transition("link-eb").channel.substantive.read(segment, amount)
        val knownZero = context.transition("link-cd").channel.substantive.read(segment, amount)
        val parsed = context.transition("link-ab").channel.substantive.read(segment, amount)
        assertFalse(blank.second)
        assertEquals(0.0, blank.first, 0.0)
        assertTrue(knownZero.second)
        assertTrue(parsed.second)
        assertNotEquals(blank, knownZero)
        val wearable = context.model.transition.substantive
        val steps = context.transition("link-ab").channel.substantive.read(
            wearable,
            FeatureSemantic.WearableValue(WearableMetric.STEPS),
        )
        val resting = context.transition("link-ab").channel.substantive.read(
            wearable,
            FeatureSemantic.WearableValue(WearableMetric.RESTING_HEART_RATE),
        )
        val average = context.transition("link-ab").channel.substantive.read(
            wearable,
            FeatureSemantic.WearableValue(WearableMetric.HEART_RATE_AVERAGE),
        )
        assertTrue(steps.second)
        assertEquals(0.0, steps.first, 0.0)
        assertFalse(resting.second)
        assertFalse(average.second)
        assertFalse(context.transition("link-ab").channel.substantive.values.contains(40.0))
        assertFalse(context.transition("link-ab").channel.substantive.values.contains(100.0))
        val ambiguous = context.transition("link-ab").channel.quality.read(
            context.model.transition.quality,
            FeatureSemantic.WearableAmbiguous(WearableMetric.HEART_RATE_AVERAGE),
        )
        assertTrue(ambiguous.second)
        assertEquals(1.0, ambiguous.first, 0.0)
    }

    @Test
    fun retrospectiveTransitionKeepsItsThreeSegments() {
        val context = project(neighborhoodEvidence())
        val retrospective = context.retrospective("link-ab")
        val layout = context.model.layouts.retrospectiveTransition
        assertEquals("transition.self", layout.self.name)
        assertEquals("transition.start_state", layout.startState.name)
        assertEquals("transition.end_state", layout.endState.name)
        assertEquals(context.transition("link-ab").channel.substantive, retrospective.self)
        assertEquals(context.state("occ-a").channel.substantive, retrospective.startState)
        assertEquals(context.state("occ-b").channel.substantive, retrospective.endState)
        assertTrue(retrospective.startAvailable)
        assertTrue(retrospective.endAvailable)
        assertEquals(StateOccurrenceKey("occ-a"), retrospective.startStateKey)
        assertEquals(StateOccurrenceKey("occ-b"), retrospective.endStateKey)
    }

    @Test
    fun boundaryTransitionDoesNotInventAStartState() {
        val context = project(neighborhoodEvidence())
        val wake = context.retrospective("link-wake")
        assertNull(wake.startStateKey)
        assertFalse(wake.startAvailable)
        assertTrue(wake.startState.observed.none { it })
        assertTrue(wake.startState.values.all { it == 0.0 })
        assertTrue(wake.endAvailable)
        assertEquals(StateOccurrenceKey("occ-b"), wake.endStateKey)
        assertEquals(context.state("occ-b").channel.substantive, wake.endState)
        assertTrue(context.diagnostics.isEmpty())
    }

    @Test
    fun outgoingMessageCarriesTheTransitionAndTheOtherEndpoint() {
        val context = project(neighborhoodEvidence())
        val message = context.outgoingMessages.single {
            it.focalStateKey.raw == "occ-a" && it.transitionKey.raw == "link-ab"
        }
        assertEquals(MessageDirection.OUTGOING, message.direction)
        assertEquals(context.transition("link-ab").channel.substantive, message.transition)
        assertEquals(context.state("occ-b").channel.substantive, message.oppositeState)
        assertNotEquals(context.state("occ-a").channel.substantive, message.oppositeState)
        assertEquals(StateOccurrenceKey("occ-b"), message.oppositeStateKey)
        assertEquals(context.model.transition.substantive.size, message.transition.values.size)
    }

    @Test
    fun incomingMessageCarriesTheTransitionAndTheEarlierEndpoint() {
        val context = project(neighborhoodEvidence())
        val message = context.incomingMessages.single {
            it.focalStateKey.raw == "occ-b" && it.transitionKey.raw == "link-ab"
        }
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(context.transition("link-ab").channel.substantive, message.transition)
        assertEquals(context.state("occ-a").channel.substantive, message.oppositeState)
        assertNotEquals(context.state("occ-b").channel.substantive, message.oppositeState)
        assertEquals(StateOccurrenceKey("occ-a"), message.oppositeStateKey)
    }

    @Test
    fun incomingAndOutgoingContextsStayDistinct() {
        val context = project(neighborhoodEvidence())
        val layout = context.model.layouts.contextualState
        assertEquals("state.incoming_context.transition", layout.incomingTransition.name)
        assertEquals("state.outgoing_context.transition", layout.outgoingTransition.name)
        assertNotEquals(layout.incomingTransition.name, layout.outgoingTransition.name)
        assertNotEquals(layout.incomingOppositeState.name, layout.outgoingOppositeState.name)
        val state = context.contextual("occ-b")
        assertTrue(state.incoming.available)
        assertFalse(state.outgoing.available)
        assertNotEquals(state.incoming.transition.values, state.outgoing.transition.values)
        assertNotEquals(state.incoming.oppositeState.observed, state.outgoing.oppositeState.observed)
    }

    @Test
    fun identicalRawStatesDivergeWhenNeighborsDiffer() {
        val context = project(neighborhoodEvidence())
        val first = context.contextual("occ-a")
        val second = context.contextual("occ-c")
        assertEquals(first.self, second.self)
        assertNotEquals(first.outgoing.oppositeState.values, second.outgoing.oppositeState.values)
        assertEquals(context.state("occ-b").channel.substantive, first.outgoing.oppositeState.let {
            MaskedVector(it.values, it.observed)
        })
        assertEquals(context.state("occ-d").channel.substantive.values, second.outgoing.oppositeState.values)
    }

    @Test
    fun branchingMessagesUseAMaskAwareMean() {
        val context = project(neighborhoodEvidence())
        val pooled = context.contextual("occ-b").incoming
        val duration = FeatureSemantic.DurationHours
        val index = context.model.transition.substantive.features.indexOfFirst { it.sourceSemantic == duration }
        val members = listOf("link-ab", "link-eb", "link-wake").map { id ->
            context.transition(id).channel.substantive.values[index]
        }
        assertEquals(3, pooled.messageCount)
        assertEquals(3, pooled.transition.contributingCounts[index])
        assertEquals(members.average(), pooled.transition.values[index], 1e-12)
        assertTrue(members.sum() != pooled.transition.values[index])
    }

    @Test
    fun missingMessageDimensionsStayOutOfTheMean() {
        val context = project(missingDimensionEvidence())
        val pooled = context.contextual("focal").incoming
        val activity = FeatureSemantic.ActivityPresent(categoricalKey(null, "Stretching"))
        val index = context.model.transition.substantive.features.indexOfFirst { it.sourceSemantic == activity }
        assertEquals(2, pooled.messageCount)
        assertEquals(1, pooled.transition.contributingCounts[index])
        assertTrue(pooled.transition.observed[index])
        assertEquals(1.0, pooled.transition.values[index], 0.0)
        val neighbor = project(neighborhoodEvidence())
        val mood = FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        val moodIndex = neighbor.model.state.substantive.features.indexOfFirst { it.sourceSemantic == mood }
        val incoming = neighbor.contextual("occ-b").incoming
        val observedNeighbors = listOf("occ-a", "occ-e").map { id ->
            neighbor.state(id).channel.substantive.values[moodIndex]
        }
        assertEquals(3, incoming.messageCount)
        assertEquals(2, incoming.oppositeState.contributingCounts[moodIndex])
        assertEquals(observedNeighbors.average(), incoming.oppositeState.values[moodIndex], 1e-12)
    }

    @Test
    fun aStateWithNoOutgoingTransitionLeavesThatSegmentUnavailable() {
        val context = project(neighborhoodEvidence())
        val state = context.contextual("occ-d")
        assertTrue(state.incoming.available)
        assertFalse(state.outgoing.available)
        assertEquals(0, state.outgoing.messageCount)
        assertTrue(state.outgoing.transition.observed.none { it })
        assertTrue(state.outgoing.oppositeState.observed.none { it })
        assertTrue(state.outgoing.transition.contributingCounts.all { it == 0 })
    }

    @Test
    fun preparedAndContextualValuesStayFinite() {
        listOf(
            project(scalingEvidence()),
            project(vocabularyEvidence()),
            project(activityVocabulary((1..9).map { "Activity$it" })),
            project(neighborhoodEvidence()),
            project(missingDimensionEvidence()),
        ).forEach { context -> assertFinite(context) }
    }

    @Test
    fun causalInputIgnoresAChangeThatTouchesOnlyTheTarget() {
        val evidence = neighborhoodEvidence()
        val encoded = encodeFeatures(evidence)
        val model = fitFeaturePreparation(encoded)
        val first = projectLocalContext(evidence, encoded, model)
        val anxiety = encoded.schemas.state.indexOf(FeatureSemantic.AxisValue(DefaultCheckInAxes.ANXIETY))
        val changed = encoded.copy(
            causalExamples = encoded.causalExamples.map { example ->
                if (example.exampleKey.raw != "link-ab") {
                    example
                } else {
                    val values = example.target.values.toMutableList()
                    val observed = example.target.observed.toMutableList()
                    values[anxiety] = 0.33
                    observed[anxiety] = true
                    example.copy(target = EncodedVector(values, observed))
                }
            },
        )
        val second = projectLocalContext(evidence, changed, model)
        val original = first.causalExamples.single { it.exampleKey.raw == "link-ab" }
        val updated = second.causalExamples.single { it.exampleKey.raw == "link-ab" }
        assertEquals(original.input, updated.input)
        assertNotEquals(original.target, updated.target)
    }

    @Test
    fun causalInputUsesBaseVectorsOnly() {
        val context = project(neighborhoodEvidence())
        val example = context.causalExamples.single { it.exampleKey.raw == "link-ab" }
        val start = checkNotNull(example.input.startingState)
        assertEquals(context.state("occ-a").channel, start)
        assertEquals(context.transition("link-ab").channel, example.input.transition)
        assertEquals(context.model.state.substantive.size, start.substantive.values.size)
        assertEquals(context.model.transition.substantive.size, example.input.transition.substantive.values.size)
        val contextual = context.contextual("occ-a")
        assertEquals(contextual.self, start.substantive)
        assertTrue(contextual.outgoing.available)
        assertNotEquals(contextual.outgoing.oppositeState.values, start.substantive.values)
        val layout = context.model.layouts.causalInput
        assertEquals("causal.input.starting_state", layout.startingState.name)
        assertEquals("causal.input.transition", layout.transition.name)
        assertFalse(layout.startingState.name.contains("outgoing"))
        assertFalse(layout.transition.name.contains("retrospective"))
        assertEquals(context.state("occ-b").channel, example.target)
    }

    @Test
    fun ineligibleExamplesKeepTheirReasonsAndStayOutOfTheEligibleList() {
        val context = project(neighborhoodEvidence())
        val held = context.causalExamples.single { it.exampleKey.raw == "link-wake" }
        assertFalse(held.eligibility.eligible)
        assertEquals(listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL), held.eligibility.reasons)
        assertNull(held.input.startingState)
        assertTrue(context.eligibleCausalExamples.none { it.exampleKey == held.exampleKey })
        assertEquals(1, context.eligibleCausalExamples.size)
    }

    @Test
    fun occurrenceIdentityStaysOutsideNumericalVectors() {
        val context = project(neighborhoodEvidence())
        val names = context.model.state.substantive.features.map { it.sourceName } +
            context.model.state.quality.features.map { it.sourceName } +
            context.model.transition.substantive.features.map { it.sourceName } +
            context.model.transition.quality.features.map { it.sourceName }
        listOf("occ-a", "occ-b", "link-ab", "link-wake", "note-not-a-feature").forEach { token ->
            assertTrue(names.none { it.contains(token) })
        }
        allValues(context).forEach { value ->
            assertFalse(value == SENTINEL.toDouble())
        }
    }

    private fun project(evidence: ContextualEvidence): LocalContext {
        return projectLocalContext(evidence, encodeFeatures(evidence))
    }

    private fun LocalContext.state(id: String): PreparedState = states.single { it.key.raw == id }

    private fun LocalContext.transition(id: String): PreparedTransition = transitions.single { it.key.raw == id }

    private fun LocalContext.retrospective(id: String): RetrospectiveTransitionContext {
        return retrospectiveTransitions.single { it.key.raw == id }
    }

    private fun LocalContext.contextual(id: String): ContextualState = contextualStates.single { it.key.raw == id }

    private fun PreparedChannelSchema.scale(semantic: FeatureSemantic): FeatureScale {
        return (substantive.features + quality.features).single { it.sourceSemantic == semantic }
    }

    private fun MaskedVector.read(segment: VectorSegment, semantic: FeatureSemantic): Pair<Double, Boolean> {
        val index = segment.features.indexOfFirst { it.sourceSemantic == semantic }
        check(index >= 0) { "Missing $semantic" }
        return values[index] to observed[index]
    }

    private fun activityEnergy(channel: PreparedChannel, schema: PreparedChannelSchema): Double {
        return schema.substantive.features.zip(channel.substantive.values).sumOf { (feature, value) ->
            if (feature.substantiveBlock == SubstantiveBlock.ACTIVITY) value * value else 0.0
        }
    }

    private fun assertFinite(context: LocalContext) {
        assertTrue(allValues(context).all { it.isFinite() })
    }

    private fun allValues(context: LocalContext): List<Double> {
        val prepared = context.states.flatMap { it.channel.substantive.values + it.channel.quality.values } +
            context.transitions.flatMap { it.channel.substantive.values + it.channel.quality.values }
        val retrospective = context.retrospectiveTransitions.flatMap {
            it.self.values + it.startState.values + it.endState.values + it.quality.values
        }
        val messages = (context.incomingMessages + context.outgoingMessages).flatMap {
            it.transition.values + it.oppositeState.values
        }
        val contextual = context.contextualStates.flatMap { state ->
            state.self.values + state.quality.values +
                state.incoming.transition.values + state.incoming.oppositeState.values +
                state.outgoing.transition.values + state.outgoing.oppositeState.values
        }
        val causal = context.causalExamples.flatMap { example ->
            val start = example.input.startingState
            (start?.substantive?.values ?: emptyList()) +
                (start?.quality?.values ?: emptyList()) +
                example.input.transition.substantive.values +
                example.input.transition.quality.values +
                example.target.substantive.values +
                example.target.quality.values
        }
        return prepared + retrospective + messages + contextual + causal
    }

    private companion object {
        const val SENTINEL = 1_700_000_000_123L
        val QUALITY_USAGE = setOf(
            UsageMeasurement.ENTRIES,
            UsageMeasurement.PARSED,
            UsageMeasurement.MISSING,
            UsageMeasurement.UNPARSED,
            UsageMeasurement.AMOUNT_COMPLETE,
        )
    }
}

private fun scalingEvidence(): ContextualEvidence {
    val zero = state("scale-zero", mood = 0.0, energy = 0.5)
    val one = state("scale-one", mood = 1.0, energy = 0.5)
    val missing = state("scale-missing")
    val link = transition("scale-link", hours = 1.0)
    return assemble(
        states = listOf(zero, one, missing),
        transitions = listOf(link),
        links = listOf(endpoint(link, "scale-zero", "scale-one")),
    )
}

private fun vocabularyEvidence(): ContextualEvidence {
    val names = listOf("One", "Two", "Three", "Four")
    val full = transition(
        "full",
        hours = 1.0,
        activity = CategoryEvidence(CategoryObservationKindV2.ENTRIES, names.map(::activity)),
    )
    val sparse = transition(
        "sparse",
        hours = 2.0,
        activity = CategoryEvidence(CategoryObservationKindV2.ENTRIES, listOf(activity("One"))),
    )
    val start = state("vocab-start")
    val end = state("vocab-end")
    return assemble(
        states = listOf(start, end),
        transitions = listOf(full, sparse),
        links = listOf(
            endpoint(full, "vocab-start", "vocab-end"),
            endpoint(sparse, "vocab-start", "vocab-end"),
        ),
    )
}

private fun activityVocabulary(names: List<String>): ContextualEvidence {
    val frame = transition(
        "only",
        hours = 1.0,
        activity = CategoryEvidence(CategoryObservationKindV2.ENTRIES, names.map(::activity)),
    )
    return assemble(
        states = listOf(state("solo-start"), state("solo-end")),
        transitions = listOf(frame),
        links = listOf(endpoint(frame, "solo-start", "solo-end")),
    )
}

private fun missingDimensionEvidence(): ContextualEvidence {
    val present = transition(
        "present",
        hours = 1.0,
        activity = CategoryEvidence(CategoryObservationKindV2.ENTRIES, listOf(activity("Stretching"))),
    )
    val hidden = transition(
        "hidden",
        hours = 1.0,
        activity = CategoryEvidence(CategoryObservationKindV2.UNREVIEWED, listOf(activity("Stretching"))),
    )
    return assemble(
        states = listOf(state("left"), state("right"), state("focal")),
        transitions = listOf(present, hidden),
        links = listOf(
            endpoint(present, "left", "focal"),
            endpoint(hidden, "right", "focal"),
        ),
    )
}

private fun neighborhoodEvidence(): ContextualEvidence {
    val origin = state("occ-a", mood = 0.2, emotion = "calm")
    val later = state("occ-b", mood = 0.8, emotion = "tense")
    val twin = state("occ-c", mood = 0.2, emotion = "calm")
    val other = state("occ-d", mood = 0.5, emotion = "calm")
    val extra = state("occ-e", mood = 0.4, emotion = "calm")
    val steps = wearable(WearableMetric.STEPS, 0.0, WearableUnit.COUNT, WearableMethod.AGGREGATE_TOTAL, "steps-zero")
    val low = wearable(
        WearableMetric.HEART_RATE_AVERAGE,
        40.0,
        WearableUnit.BEATS_PER_MINUTE,
        WearableMethod.HEART_RATE_AVERAGE,
        "average-low",
    )
    val high = wearable(
        WearableMetric.HEART_RATE_AVERAGE,
        100.0,
        WearableUnit.BEATS_PER_MINUTE,
        WearableMethod.HEART_RATE_AVERAGE,
        "average-high",
    )
    val linked = transition(
        id = "link-ab",
        hours = 1.0,
        activity = CategoryEvidence(CategoryObservationKindV2.ENTRIES, listOf(activity("Stretching"))),
        usage = CategoryEvidence(CategoryObservationKindV2.ENTRIES, listOf(cigarettes("3"))),
        wearables = listOf(
            WearableMetricEvidence.Observed(WearableMetric.STEPS, listOf(steps.id), competingSpans = false),
            WearableMetricEvidence.Unobserved(WearableMetric.RESTING_HEART_RATE),
            WearableMetricEvidence.Observed(
                WearableMetric.HEART_RATE_AVERAGE,
                listOf(low.id, high.id),
                competingSpans = true,
            ),
            WearableMetricEvidence.Unobserved(WearableMetric.HEART_RATE_SAMPLE),
        ),
    )
    val blank = transition(
        id = "link-eb",
        hours = 3.0,
        usage = CategoryEvidence(CategoryObservationKindV2.ENTRIES, listOf(cigarettes(""))),
    )
    val emptyUsage = transition(
        id = "link-cd",
        hours = 10.0,
        usage = CategoryEvidence(CategoryObservationKindV2.ENTRIES, emptyList()),
    )
    val wake = transition(
        id = "link-wake",
        hours = 2.0,
        basis = CheckInContextStartBasis.WAKE_TIME,
    )
    return assemble(
        states = listOf(origin, later, twin, other, extra),
        transitions = listOf(linked, blank, emptyUsage, wake),
        links = listOf(
            endpoint(linked, "occ-a", "occ-b"),
            endpoint(blank, "occ-e", "occ-b"),
            endpoint(emptyUsage, "occ-c", "occ-d"),
            endpoint(wake, null, "occ-b"),
        ),
        causal = listOf(
            example(linked, origin, later, eligible = true),
            example(
                wake,
                start = null,
                end = later,
                eligible = false,
                reasons = listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL),
            ),
        ),
        wearables = listOf(steps, low, high).associateBy { it.id },
    )
}

private fun assemble(
    states: List<StateEvidenceFrame>,
    transitions: List<TransitionEvidenceFrame>,
    links: List<RetrospectiveTransitionEvidence>,
    causal: List<CausalEvidenceExample> = emptyList(),
    wearables: Map<String, WearableObservation> = emptyMap(),
) = ContextualEvidence(
    states = states,
    transitions = transitions,
    statesByKey = states.associateBy { it.key },
    transitionsByKey = transitions.associateBy { it.key },
    wearablesById = wearables,
    retrospectiveStates = emptyList(),
    retrospectiveTransitions = links,
    causalExamples = causal,
    issues = emptyList(),
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

private fun state(
    id: String,
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
        recordedAt = LocalContextPreparationTestSentinel.VALUE,
        localDate = "2099-01-02",
        trigger = "trigger-not-a-feature",
        note = "note-not-a-feature",
    ),
)

private fun transition(
    id: String,
    hours: Double,
    activity: CategoryEvidence<ActivityEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
    usage: CategoryEvidence<UsageEvidence> = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
    wearables: List<WearableMetricEvidence> = emptyList(),
    basis: String = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
): TransitionEvidenceFrame {
    val duration = (hours * 3_600_000L).toLong()
    return TransitionEvidenceFrame(
        key = TransitionOccurrenceKey(id),
        evidence = TransitionEvidencePayload(
            temporal = TemporalEvidence(
                startAt = 10L,
                endAt = 10L + duration,
                durationMillis = duration,
                startBasis = basis,
                completed = true,
            ),
            activity = activity,
            workout = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
            diet = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
            usage = usage,
            wearables = wearables,
        ),
        metadata = TransitionEvidenceMetadata(completedAt = 50L),
    )
}

private fun activity(name: String) = ActivityEvidence(optionId = null, name = name, reportedAt = 1L)

private fun cigarettes(measurement: UsageMeasurement) = FeatureSemantic.Usage(
    identity = categoricalKey(null, "Cigarettes"),
    intent = "COPING",
    measurement = measurement,
    unit = if (measurement == UsageMeasurement.AMOUNT || measurement == UsageMeasurement.PER_HOUR) "unitless" else null,
)

private fun cigarettes(amount: String) = UsageEvidence(
    optionId = null,
    name = "Cigarettes",
    intent = "COPING",
    amountText = amount,
    reportedAt = 1L,
)

private fun wearable(metric: String, value: Double, unit: String, method: String, recordId: String) =
    WearableObservation.create(
        metric = metric,
        value = value,
        unit = unit,
        observedStartAt = 0L,
        observedEndAt = 1_000L,
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = method,
        sourceRecordId = recordId,
        importedAt = 1L,
    )

private object LocalContextPreparationTestSentinel {
    const val VALUE = 1_700_000_000_123L
}
