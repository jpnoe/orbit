package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PersonalSemanticJointEmbeddingTest {
    @Test
    fun reciprocalContextConnectsAnEmotionStateToABehaviorConcept() {
        val fitted = embed(runningHistory())
        val hope = stateNodeId("hope")
        val running = conceptNodeId(ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "running")))
        val control = fitted.layer(LAYER_ZERO)
        val reciprocal = fitted.layer(RECIPROCAL_LAYER)
        assertFalse(control.edges.any { it.touches(hope, running) })
        assertTrue(reciprocal.edges.any { it.touches(hope, running) && it.detail.contains("outgoing") })
        val disconnected = control.similarity(hope, running)
        val connected = reciprocal.similarity(hope, running)
        assertTrue("reciprocal $connected layer-zero $disconnected", connected > disconnected)
        assertFalse(control.nearest(hope, "concept").any { it.id == running })
        assertTrue(reciprocal.nearest(hope, "concept").any { it.id == running })
        assertTrue(fitted.retrospective)
        assertTrue(fitted.judgments.first().contains("Retrospective full-history"))
    }

    @Test
    fun roleMassDistinguishesASwappedContext() {
        val reciprocal = embed(swappedHistory()).layer(RECIPROCAL_LAYER)
        val coding = conceptNodeId(ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "coding")))
        val reading = conceptNodeId(ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "reading")))
        val up = stateNodeId("up")
        val down = stateNodeId("down")
        assertTrue(reciprocal.edges.any { it.touches(up, coding) && it.detail.contains("incoming") })
        assertTrue(reciprocal.edges.any { it.touches(down, coding) && it.detail.contains("outgoing") })
        assertTrue(reciprocal.similarity(up, coding) > 0.0)
        assertTrue(reciprocal.similarity(down, coding) > 0.0)
        assertTrue(reciprocal.similarity(up, reading) > 0.0)
        assertTrue(reciprocal.similarity(down, reading) > 0.0)
        assertFalse(sameCoordinates(checkNotNull(reciprocal.node(up)), checkNotNull(reciprocal.node(down))))
        assertTrue(reciprocal.similarity(up, down) < 0.999)
    }

    @Test
    fun changingSupportDoesNotMoveAnOccurrence() {
        val plain = embed(runningHistory())
        val boosted = embed(runningHistory(), supportBoost = 20)
        assertTrue(boosted.judgments.any { it.contains("support") })
        assertSameGeometry(plain.layer(LAYER_ZERO), boosted.layer(LAYER_ZERO))
        assertSameGeometry(plain.layer(RECIPROCAL_LAYER), boosted.layer(RECIPROCAL_LAYER))
    }

    @Test
    fun constantPresenceDoesNotSeparateOccurrences() {
        val withMarker = embed(constantHistory(includeMarker = true)).layer(LAYER_ZERO)
        val withoutMarker = embed(constantHistory(includeMarker = false)).layer(LAYER_ZERO)
        val marker = "ACTIVITY|name:always|"
        assertTrue(withMarker.omittedConstantPresence.contains(marker))
        assertTrue(withMarker.omittedConstantPresence.contains("EMOTION|calm|"))
        assertFalse(withMarker.edges.any { it.detail.contains(marker) })
        assertSameGeometry(withMarker, withoutMarker)
    }

    @Test
    fun varyingQuantityContributesWhenPresenceIsConstant() {
        val layer = embed(cigaretteHistory(missing = false)).layer(LAYER_ZERO)
        val cigarettes = cigarettes()
        assertTrue(layer.omittedConstantPresence.contains(cigarettesToken()))
        assertFalse(layer.edges.any { it.kind == "presence" && it.detail.contains(cigarettesToken()) })
        val zero = transitionNodeId("zero")
        val ten = transitionNodeId("ten")
        assertTrue(layer.edges.any { it.touches(zero, cigarettes) && it.kind == "quantity" && it.detail.contains("amount 0.0") })
        assertTrue(layer.edges.any { it.touches(ten, cigarettes) && it.kind == "quantity" && it.detail.contains("amount 10.0") })
        val zeroSimilarity = layer.similarity(zero, cigarettes)
        val tenSimilarity = layer.similarity(ten, cigarettes)
        assertTrue("ten $tenSimilarity zero $zeroSimilarity", tenSimilarity > zeroSimilarity)
    }

    @Test
    fun missingQuantityIsNotTreatedAsZero() {
        val layer = embed(cigaretteHistory(missing = true)).layer(LAYER_ZERO)
        val cigarettes = cigarettes()
        val zero = transitionNodeId("zero")
        val missing = transitionNodeId("missing")
        assertTrue(layer.edges.any { it.touches(zero, cigarettes) && it.kind == "quantity" && it.detail.contains("amount 0.0") })
        assertFalse(layer.edges.any { it.touches(missing, cigarettes) })
        assertNotNull(layer.node(zero))
        assertNull(layer.node(missing))
    }

    @Test
    fun mixedUnitsStayOutOfTheFit() {
        val layer = embed(mixedUnitHistory()).layer(LAYER_ZERO)
        assertTrue(layer.quantityHeldBack.any { it.contains("mixed units") })
        assertFalse(layer.edges.any { it.kind == "quantity" })
    }

    @Test
    fun maskingRemovesAConceptBeforeTheFit() {
        val running = ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "running"))
        val masked = embed(runningHistory(), mask = SignatureMask(excludedConcepts = setOf(running)))
        val open = embed(runningHistory())
        val token = conceptNodeId(running)
        assertTrue(open.layer(RECIPROCAL_LAYER).edges.any { it.source == token || it.target == token })
        assertFalse(masked.layers.flatMap { it.edges }.any { it.source == token || it.target == token || it.detail.contains("name:running") })
        assertFalse(masked.layers.flatMap { it.nodes }.any { it.id == token })
    }

    @Test
    fun aCutoffVocabularyDoesNotRestoreALaterConcept() {
        val fitted = embed(cutoffHistory(), cutoff = 10 * HOUR)
        assertFalse(fitted.retrospective)
        assertEquals(FOLD_VOCABULARY_SCOPE, fitted.vocabularyScope)
        val novel = conceptNodeId(ConceptIdentityKey(SemanticConceptType.EMOTION, "novel"))
        val calm = conceptNodeId(ConceptIdentityKey(SemanticConceptType.EMOTION, "calm"))
        assertTrue(fitted.layers.all { layer -> layer.nodes.none { it.id == novel } && layer.edges.none { it.detail.contains("novel") } })
        assertTrue(fitted.layer(LAYER_ZERO).node(calm) != null)
        assertTrue(fitted.judgments.any { it.contains("cutoff vocabulary") })
    }

    @Test
    fun theAuditNamesDirectIncidenceAndDoesNotLearnConceptProximity() {
        val fitted = embed(runningHistory())
        val hope = stateNodeId("hope")
        val after = transitionNodeId("after")
        val running = conceptNodeId(ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "running")))
        val hopeful = conceptNodeId(ConceptIdentityKey(SemanticConceptType.EMOTION, "hopeful"))
        assertEquals("S01", fitted.audit.aliasByNode[hope])
        assertEquals("hope", fitted.audit.canonicalByNode[hope])
        assertEquals("T01", fitted.audit.aliasByNode[after])
        assertEquals("running", fitted.audit.labelByConcept[running])
        assertEquals(setOf(after), fitted.audit.incidentTransitions[hope])
        val control = fitted.layer(LAYER_ZERO).occurrenceLists(hope, fitted.audit)
        assertTrue(
            control.incidentTransitions.any { it.id == after } ||
                control.incidentWithoutCoordinates.any { it.contains("after") },
        )
        assertTrue(control.nonincidentTransitions.isEmpty())
        assertTrue(control.carriedConcepts.any { it.id == hopeful })
        assertTrue(control.noncarriedConcepts.none { it.id == running })
        val reciprocal = fitted.layer(RECIPROCAL_LAYER)
        assertTrue(reciprocal.occurrenceLists(hope, fitted.audit).noncarriedConcepts.any { it.id == running })
        assertEquals(0.0, reciprocal.similarity(running, hopeful), 0.0)
        assertTrue(reciprocal.conceptLists(running).otherConcepts.isEmpty())
        assertTrue(fitted.judgments.any { it.contains("unfactorized direct-incidence") })
    }

    @Test
    fun axisValueContributionKeepsItsSign() {
        val layer = embed(constantHistory(includeMarker = false)).layer(LAYER_ZERO)
        val contribution = layer.axisValueContribution(stateNodeId("a"), stateNodeId("b"))
        assertNotNull(contribution)
        assertTrue(contribution!! < 0.0)
        val line = layer.explanationLines(stateNodeId("a"), stateNodeId("b")).single { it.startsWith("axis mood") }
        assertTrue(line.contains("value contribution -"))
    }

    @Test
    fun omittedIdentityStaysDistinctFromQuantity() {
        val layer = embed(cigaretteHistory(missing = false)).layer(LAYER_ZERO)
        val lines = layer.explanationLines(transitionNodeId("ten"), cigarettes())
        assertTrue(lines.any { it.startsWith("identity presence omitted") })
        assertTrue(lines.any { it.startsWith("quantity") && it.contains("contribution") })
        assertTrue(lines.none { it.startsWith("identity ·") })
    }

    @Test
    fun theBaselineStaysOutsideThePredictorAndTheLabCanShowIt() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
        ).readText()
        assertTrue(source.contains(PERSONAL_SEMANTIC_JOINT_EMBEDDING))
        assertFalse(source.contains(".causal"))
        assertFalse(source.contains("backboneSupport"))
        assertFalse(source.contains("perHour"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_JOINT_EMBEDDING))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("JointEmbeddingSection"))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/JointEmbeddingSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/JointEmbeddingSection.kt",
        ).readText()
        assertTrue(section.contains("Joint embedding"))
        val fitted = embed(runningHistory())
        assertFalse(fitted.predictive || fitted.updatesModel || fitted.suitableForRecommendation)
        val small = fitted.layer(LAYER_ZERO)
        assertEquals(3, small.occurrenceCount)
        assertTrue(small.unreliableBecause.contains("3 occurrences"))
        assertNotNull(small.node(stateNodeId("hope")))
        assertTrue(small.nearest(stateNodeId("hope"), "concept").isNotEmpty())
    }

    private fun sameCoordinates(left: EmbeddedNode, right: EmbeddedNode): Boolean {
        if (left.coordinates.size != right.coordinates.size) return false
        return left.coordinates.zip(right.coordinates).all { (value, other) ->
            if (value.isNaN() || other.isNaN()) value.isNaN() && other.isNaN() else abs(value - other) <= 1e-8
        }
    }

    private fun assertSameGeometry(left: JointEmbeddingLayer, right: JointEmbeddingLayer) {
        assertEquals(left.nodes.map { it.id }.sorted(), right.nodes.map { it.id }.sorted())
        left.nodes.forEach { node ->
            val other = checkNotNull(right.node(node.id))
            assertEquals(node.coordinates.size, other.coordinates.size)
            node.coordinates.forEachIndexed { index, value ->
                val otherValue = other.coordinates[index]
                if (value.isNaN() || otherValue.isNaN()) {
                    assertTrue(value.isNaN() && otherValue.isNaN())
                } else {
                    assertEquals(value, otherValue, 1e-8)
                }
            }
        }
    }

    private fun cigarettes() = conceptNodeId(
        ConceptIdentityKey(SemanticConceptType.CONTEXTUAL_USAGE, categoricalKey(null, "cigarettes"), "recorded"),
    )

    private fun cigarettesToken() = "CONTEXTUAL_USAGE|name:cigarettes|recorded"

    private fun embed(
        history: CheckInAnalysisDatasetV2,
        mask: SignatureMask = SignatureMask(),
        cutoff: Long? = null,
        supportBoost: Int = 0,
    ): JointEmbeddingDataset {
        val projection = projectPersonalSemanticOccurrences(history)
        val graph = projectAlternatingTemporalGraph(history)
        val base = if (cutoff == null) retrospectiveVocabulary(projection) else vocabularyBeforeCutoff(projection, cutoff)
        val vocabulary = if (supportBoost == 0) {
            base
        } else {
            base.copy(concepts = base.concepts.map { it.copy(backboneSupport = it.backboneSupport + supportBoost) })
        }
        return projectJointSemanticEmbedding(
            signatures = projectReciprocalSignatures(projection, graph, vocabulary, mask),
            graph = graph,
            projection = projection,
        )
    }

    private fun runningHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("hope", 0L, mood = 5, emotion = "hopeful"),
                state("after", 2 * HOUR, mood = 5, emotion = "tired"),
            ),
            intervals = listOf(activityInterval("after", "hope", 0L, 2 * HOUR, "running")),
        )
    }

    private fun swappedHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("s1", 0L, mood = 5, emotion = "src"),
                state("up", 2 * HOUR, mood = 5, emotion = "calm"),
                state("d1", 4 * HOUR, mood = 5, emotion = "dst"),
                state("s2", 6 * HOUR, mood = 5, emotion = "src"),
                state("down", 8 * HOUR, mood = 5, emotion = "calm"),
                state("d2", 10 * HOUR, mood = 5, emotion = "dst"),
            ),
            intervals = listOf(
                activityInterval("up", "s1", 0L, 2 * HOUR, "coding"),
                activityInterval("d1", "up", 2 * HOUR, 4 * HOUR, "reading"),
                activityInterval("down", "s2", 6 * HOUR, 8 * HOUR, "reading"),
                activityInterval("d2", "down", 8 * HOUR, 10 * HOUR, "coding"),
            ),
        )
    }

    private fun constantHistory(includeMarker: Boolean): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("a", 0L, mood = 2, emotion = "calm"),
                state("b", 2 * HOUR, mood = 8, emotion = "calm"),
            ),
            intervals = listOf(
                if (includeMarker) {
                    activityInterval("b", "a", 0L, 2 * HOUR, "always")
                } else {
                    interval("b", "a", 0L, 2 * HOUR)
                },
            ),
        )
    }

    private fun cigaretteHistory(missing: Boolean): CheckInAnalysisDatasetV2 {
        val states = mutableListOf(
            state("s0", 0L, mood = 5, emotion = "one"),
            state("zero", 2 * HOUR, mood = 5, emotion = "two"),
            state("s1", 4 * HOUR, mood = 5, emotion = "three"),
            state("ten", 6 * HOUR, mood = 5, emotion = "four"),
        )
        val intervals = mutableListOf(
            usageInterval("zero", "s0", 0L, 2 * HOUR, "0"),
            usageInterval("ten", "s1", 4 * HOUR, 6 * HOUR, "10"),
        )
        if (missing) {
            states += state("s2", 8 * HOUR, mood = 5, emotion = "five")
            states += state("missing", 10 * HOUR, mood = 5, emotion = "six")
            intervals += usageInterval("missing", "s2", 8 * HOUR, 10 * HOUR, "")
        }
        return history(states, intervals)
    }

    private fun mixedUnitHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("s0", 0L, mood = 5, emotion = "one"),
                state("mg", 2 * HOUR, mood = 5, emotion = "two"),
                state("s1", 4 * HOUR, mood = 5, emotion = "three"),
                state("ml", 6 * HOUR, mood = 5, emotion = "four"),
            ),
            intervals = listOf(
                usageInterval("mg", "s0", 0L, 2 * HOUR, "1 mg"),
                usageInterval("ml", "s1", 4 * HOUR, 6 * HOUR, "2 ml"),
            ),
        )
    }

    private fun cutoffHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("early", HOUR, mood = 5, emotion = "calm"),
                state("late", 30 * HOUR, mood = 9, emotion = "novel"),
            ),
            intervals = emptyList(),
        )
    }

    private fun history(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(
        states = states,
        intervals = intervals,
        transitions = emptyList(),
        issues = emptyList(),
    )

    private fun activityInterval(end: String, previous: String, start: Long, endAt: Long, activity: String) = interval(
        end = end,
        previous = previous,
        start = start,
        endAt = endAt,
        activity = ActivityCategoryObservationV2(
            CategoryObservationKindV2.ENTRIES,
            endAt,
            listOf(ActivityObservationV2("act-$end", null, activity, endAt)),
        ),
    )

    private fun usageInterval(end: String, previous: String, start: Long, endAt: Long, amount: String) = interval(
        end = end,
        previous = previous,
        start = start,
        endAt = endAt,
        usage = UsageCategoryObservationV2(
            CategoryObservationKindV2.ENTRIES,
            endAt,
            listOf(UsageObservationV2("use-$end", null, "cigarettes", "recorded", amount, endAt)),
        ),
    )

    private fun interval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage: UsageCategoryObservationV2 = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = endAt,
        activity = activity,
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = usage,
    )

    private fun state(id: String, at: Long, mood: Int, emotion: String): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
            emotion = emotion,
        )
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
