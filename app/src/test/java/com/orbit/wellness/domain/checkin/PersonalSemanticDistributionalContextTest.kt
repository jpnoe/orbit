package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PersonalSemanticDistributionalContextTest {
    @Test
    fun overlappingOccurrencesMakeConceptsCloseWithoutADirectCosine() {
        val fitted = fit(sharedActivityHistory())
        val coding = activity("coding")
        val songwriting = activity("songwriting")
        val reading = activity("reading")
        val control = fitted.embedding.layer(LAYER_ZERO)
        val context = fitted.context.layer(LAYER_ZERO)
        assertEquals(0.0, control.similarity(coding, songwriting), 0.0)
        assertTrue(context.similarity(coding, songwriting) > 0.0)
        assertEquals(0.0, context.similarity(coding, reading), 0.0)
        val lists = context.conceptLists(coding, fitted.embedding.audit)
        assertTrue(lists.nearestConcepts.any { it.id == songwriting })
        assertTrue(lists.nearestConcepts.none { it.id == reading })
    }

    @Test
    fun reciprocalContextConnectsAnEmotionToABehavior() {
        val fitted = fit(runningHistory())
        val hopeful = emotion("hopeful")
        val running = activity("running")
        val hope = stateNodeId("hope")
        assertEquals(0.0, fitted.embedding.layer(RECIPROCAL_LAYER).similarity(hopeful, running), 0.0)
        assertEquals(0.0, fitted.context.layer(LAYER_ZERO).similarity(hopeful, running), 0.0)
        val reciprocal = fitted.context.layer(RECIPROCAL_LAYER)
        assertTrue(reciprocal.similarity(hopeful, running) > 0.0)
        assertTrue(reciprocal.conceptLists(hopeful, fitted.embedding.audit).nearestConcepts.any { it.id == running })
        assertTrue(reciprocal.landmarkContributions(hopeful, running).any { it.landmarkId == hope && it.contribution > 0.0 })
        val lists = reciprocal.occurrenceLists(hope, fitted.embedding.audit)
        assertTrue(lists.carriedConcepts.any { it.id == hopeful })
        assertTrue(lists.noncarriedConcepts.none { it.id == hopeful })
        assertTrue(lists.noncarriedConcepts.any { it.id == running && it.landmarks.isNotEmpty() })
    }

    @Test
    fun disjointSimilarOccurrencesDoNotPullConceptsTogether() {
        val fitted = fit(similarDisjointHistory())
        val hopeful = emotion("hopeful")
        val calm = emotion("calm")
        assertTrue(fitted.embedding.layer(LAYER_ZERO).similarity(stateNodeId("a"), stateNodeId("b")) > 0.0)
        assertEquals(0.0, fitted.embedding.layer(LAYER_ZERO).similarity(hopeful, calm), 0.0)
        assertEquals(0.0, fitted.context.layer(LAYER_ZERO).similarity(hopeful, calm), 0.0)
        assertTrue(fitted.context.layer(LAYER_ZERO).conceptLists(hopeful, fitted.embedding.audit).nearestConcepts.none { it.id == calm })
        assertTrue(fitted.context.judgments.any { it.contains("not applied a second time") })
    }

    @Test
    fun anOccurrenceCannotMatchItselfThroughItsOwnLandmark() {
        val fitted = fit(runningHistory())
        val hope = stateNodeId("hope")
        val layer = fitted.context.layer(LAYER_ZERO)
        assertTrue(layer.profileValue(hope, hope).isNaN())
        assertTrue(layer.landmarkContributions(hope, hope).none { it.landmarkId == hope })
        assertTrue(layer.occurrenceLists(hope, fitted.embedding.audit).states.none { it.id == hope })
        assertEquals(hope, layer.landmarks.first())
    }

    @Test
    fun roleSwapsChangeTheContextualProfile() {
        val original = fit(roleHistory(intoUp = "coding", outOfUp = "reading"))
        val swapped = fit(roleHistory(intoUp = "reading", outOfUp = "coding"))
        val coding = activity("coding")
        val up = stateNodeId("up")
        assertFalse(
            sameProfile(
                checkNotNull(original.context.layer(RECIPROCAL_LAYER).node(up)),
                checkNotNull(swapped.context.layer(RECIPROCAL_LAYER).node(up)),
            ),
        )
        assertFalse(
            sameProfile(
                checkNotNull(original.context.layer(RECIPROCAL_LAYER).node(coding)),
                checkNotNull(swapped.context.layer(RECIPROCAL_LAYER).node(coding)),
            ),
        )
    }

    @Test
    fun maskingRemovesTheTargetBeforeTheProfile() {
        val running = ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "running"))
        val masked = fit(runningHistory(), mask = SignatureMask(excludedConcepts = setOf(running)))
        val token = conceptNodeId(running)
        assertTrue(masked.context.layers.all { layer -> layer.nodes.none { it.id == token } })
        val lists = masked.context.layer(RECIPROCAL_LAYER).occurrenceLists(stateNodeId("hope"), masked.embedding.audit)
        assertTrue(lists.carriedConcepts.none { it.id == token })
        assertTrue(lists.noncarriedConcepts.none { it.id == token })
        assertTrue(fit(runningHistory()).context.layer(RECIPROCAL_LAYER).node(token) != null)
    }

    @Test
    fun aPrefixFitLeavesALaterOccurrenceOut() {
        val prefix = fit(prefixHistory())
        val filtered = cutoffHistory().let { full ->
            fit(history(full.states.filter { it.recordedAt < 10 * HOUR }, emptyList()))
        }
        val whole = fit(cutoffHistory())
        assertSameProfiles(prefix.context.layer(LAYER_ZERO), filtered.context.layer(LAYER_ZERO))
        assertFalse(prefix.context.layer(LAYER_ZERO).landmarks.contains(stateNodeId("late")))
        assertTrue(whole.context.layer(LAYER_ZERO).landmarks.contains(stateNodeId("late")))
        assertEquals(listOf(stateNodeId("early"), stateNodeId("middle")), prefix.context.layer(LAYER_ZERO).landmarks)
        val vocabularyCutoff = fit(cutoffHistory(), cutoff = 10 * HOUR)
        assertTrue(vocabularyCutoff.context.layer(LAYER_ZERO).landmarks.contains(stateNodeId("late")))
        assertFalse(vocabularyCutoff.context.retrospective)
    }

    @Test
    fun supportCountsDoNotMoveAProfile() {
        val plain = fit(runningHistory())
        val boosted = fit(runningHistory(), supportBoost = 20)
        val running = activity("running")
        assertSameProfiles(plain.context.layer(LAYER_ZERO), boosted.context.layer(LAYER_ZERO))
        assertSameProfiles(plain.context.layer(RECIPROCAL_LAYER), boosted.context.layer(RECIPROCAL_LAYER))
        assertEquals(
            plain.context.supportByConcept.getValue(running) + 20,
            boosted.context.supportByConcept.getValue(running),
        )
    }

    @Test
    fun signedFirstOrderCoordinatesStaySigned() {
        val fitted = fit(opposedHistory())
        val layer = fitted.context.layer(LAYER_ZERO)
        val low = stateNodeId("a")
        val high = stateNodeId("b")
        assertTrue(layer.profileValue(low, high) < 0.0)
        assertTrue(layer.profileValue(low, low).isNaN())
        val shares = layer.landmarkContributions(emotion("bb"), low)
        assertTrue(shares.any { it.landmarkId == high && it.contribution < 0.0 })
        assertTrue(fitted.context.judgments.any { it.contains("not clipped") })
    }

    @Test
    fun theNeighborhoodUsesAliasesAndProvenanceKeepsTheRecord() {
        val stateId = "11111111-2222-3333-4444-555555555555"
        val optionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val fitted = fit(readableHistory(stateId, optionId, completed = true))
        val audit = fitted.embedding.audit
        val reading = fitted.context.reading
        val hopeful = stateNodeId(stateId)
        val transition = transitionNodeId(stateId)
        val coding = conceptNodeId(ConceptIdentityKey(SemanticConceptType.ACTIVITY, "option:$optionId"))
        assertEquals("S02 · Hopeful · M6 E6 C4 A4", reading.line(hopeful, audit))
        assertEquals("S01 · M0", reading.line(stateNodeId("start"), audit))
        assertEquals("T01 · Coding, Coffee, Reading +1 · S01→S02 · 2h", reading.line(transition, audit))
        assertEquals("Coding · activity · support 1", reading.line(coding, audit, fitted.context.supportByConcept))
        reading.neighborhoodByNode.values.forEach { line ->
            assertFalse(line, line.contains(stateId) || line.contains(optionId))
        }
        assertFalse(reading.line(coding, audit, fitted.context.supportByConcept).contains(optionId))
        val provenance = reading.provenance.map { it.text() }
        assertTrue(provenance.contains("S02 · state · $stateId"))
        assertTrue(provenance.contains("T01 · transition · $stateId"))
        assertTrue(provenance.any { it.contains("ACTIVITY|option:$optionId|") })
        val omitted = fit(readableHistory(stateId, optionId, completed = false))
        assertFalse(omitted.context.reading.line(transition, omitted.embedding.audit).contains("2h"))
        val withoutReading = projectDistributionalContext(fitted.embedding, fitted.context.supportByConcept)
        assertSameProfiles(fitted.context.layer(LAYER_ZERO), withoutReading.layer(LAYER_ZERO))
        assertEquals(
            fitted.context.layer(LAYER_ZERO).occurrenceLists(hopeful, audit, reading, fitted.context.supportByConcept).states.map { it.id },
            withoutReading.layer(LAYER_ZERO).occurrenceLists(hopeful, audit).states.map { it.id },
        )
        val listed = fitted.context.layer(LAYER_ZERO).occurrenceLists(
            stateNodeId("start"),
            audit,
            reading,
            fitted.context.supportByConcept,
        )
        (listed.states + listed.incidentTransitions + listed.noncarriedConcepts).forEach { neighbor ->
            assertTrue(neighbor.landmarks.size <= 4)
        }
        assertEquals("+0.027", formatDistributionalContribution(0.027))
        assertEquals("-0.021", formatDistributionalContribution(-0.021))
        assertEquals("0.451", formatDistributionalSimilarity(0.451))
        assertEquals("0.0", formatDistributionalContribution(0.0))
    }

    @Test
    fun theMeasurementStaysOutsideThePredictorAndTheLabCanShowIt() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
        ).readText()
        assertTrue(source.contains(PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT))
        assertFalse(source.contains(".causal"))
        assertFalse(source.contains("perHour"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        val control = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
        ).readText()
        assertFalse(control.contains(PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("DistributionalContextSection"))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/DistributionalContextSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/DistributionalContextSection.kt",
        ).readText()
        assertTrue(section.contains("Distributional context"))
        assertTrue(section.contains("Exact provenance"))
        assertTrue(section.contains("support"))
        val fitted = fit(runningHistory())
        assertFalse(fitted.context.predictive || fitted.context.updatesModel || fitted.context.suitableForRecommendation)
        assertEquals(PERSONAL_SEMANTIC_JOINT_EMBEDDING, fitted.context.controlProtocol)
        val layer = fitted.context.layer(LAYER_ZERO)
        assertEquals(3, layer.occurrenceCount)
        assertTrue(layer.unreliableBecause.contains("3 occurrences"))
        assertNotNull(layer.node(stateNodeId("hope")))
        assertTrue(fitted.context.judgments.any { it.contains("3 occurrences") })
    }

    private fun sameProfile(left: DistributionalNode, right: DistributionalNode): Boolean {
        if (left.profile.size != right.profile.size) return false
        return left.profile.zip(right.profile).all { (value, other) ->
            if (value.isNaN() || other.isNaN()) value.isNaN() && other.isNaN() else abs(value - other) <= 1e-8
        }
    }

    private fun assertSameProfiles(left: DistributionalLayer, right: DistributionalLayer) {
        assertEquals(left.landmarks, right.landmarks)
        assertEquals(left.nodes.map { it.id }.sorted(), right.nodes.map { it.id }.sorted())
        left.nodes.forEach { node ->
            assertTrue(sameProfile(node, checkNotNull(right.node(node.id))))
        }
    }

    private fun emotion(name: String) = conceptNodeId(ConceptIdentityKey(SemanticConceptType.EMOTION, name))

    private fun activity(name: String) = conceptNodeId(
        ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, name)),
    )

    private data class Fitted(
        val embedding: JointEmbeddingDataset,
        val context: DistributionalContextDataset,
    )

    private fun fit(
        history: CheckInAnalysisDatasetV2,
        mask: SignatureMask = SignatureMask(),
        cutoff: Long? = null,
        supportBoost: Int = 0,
    ): Fitted {
        val projection = projectPersonalSemanticOccurrences(history)
        val graph = projectAlternatingTemporalGraph(history)
        val base = if (cutoff == null) retrospectiveVocabulary(projection) else vocabularyBeforeCutoff(projection, cutoff)
        val vocabulary = if (supportBoost == 0) {
            base
        } else {
            base.copy(concepts = base.concepts.map { it.copy(backboneSupport = it.backboneSupport + supportBoost) })
        }
        val embedding = projectJointSemanticEmbedding(
            signatures = projectReciprocalSignatures(projection, graph, vocabulary, mask),
            graph = graph,
            projection = projection,
        )
        val support = vocabulary.concepts.associate { conceptNodeId(it.key) to it.backboneSupport }
        return Fitted(embedding, projectDistributionalContext(embedding, support, projection))
    }

    private fun runningHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("hope", 0L, mood = 5, emotion = "hopeful"),
                state("after", 2 * HOUR, mood = 5, emotion = "tired"),
            ),
            intervals = listOf(activityInterval("after", "hope", 0L, 2 * HOUR, listOf("running"))),
        )
    }

    private fun sharedActivityHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("s0", 0L, mood = 5, emotion = "one"),
                state("s1", 2 * HOUR, mood = 5, emotion = "two"),
                state("s2", 4 * HOUR, mood = 5, emotion = "three"),
            ),
            intervals = listOf(
                activityInterval("s1", "s0", 0L, 2 * HOUR, listOf("coding", "songwriting")),
                activityInterval("s2", "s1", 2 * HOUR, 4 * HOUR, listOf("reading")),
            ),
        )
    }

    private fun similarDisjointHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("low", 0L, mood = 2, emotion = "other"),
                state("a", 2 * HOUR, mood = 8, emotion = "hopeful"),
                state("b", 4 * HOUR, mood = 8, emotion = "calm"),
            ),
            intervals = emptyList(),
        )
    }

    private fun opposedHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("a", 0L, mood = 2, emotion = "aa"),
                state("b", 2 * HOUR, mood = 8, emotion = "bb"),
            ),
            intervals = emptyList(),
        )
    }

    private fun roleHistory(intoUp: String, outOfUp: String): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("s1", 0L, mood = 5, emotion = "src"),
                state("up", 2 * HOUR, mood = 5, emotion = "calm"),
                state("d1", 4 * HOUR, mood = 5, emotion = "dst"),
                state("s3", 6 * HOUR, mood = 5, emotion = "src"),
                state("extra", 8 * HOUR, mood = 5, emotion = "calm"),
            ),
            intervals = listOf(
                activityInterval("up", "s1", 0L, 2 * HOUR, listOf(intoUp)),
                activityInterval("d1", "up", 2 * HOUR, 4 * HOUR, listOf(outOfUp)),
                activityInterval("extra", "s3", 6 * HOUR, 8 * HOUR, listOf("coding")),
            ),
        )
    }

    private fun readableHistory(stateId: String, optionId: String, completed: Boolean): CheckInAnalysisDatasetV2 {
        val endAt = 2 * HOUR
        return history(
            states = listOf(
                state("start", 0L, mood = 0, emotion = ""),
                hopefulState(stateId, endAt),
            ),
            intervals = listOf(
                interval(
                    end = stateId,
                    previous = "start",
                    start = 0L,
                    endAt = endAt,
                    activity = ActivityCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        endAt,
                        listOf(
                            ActivityObservationV2("coding", optionId, "Coding", endAt),
                            ActivityObservationV2("coffee", null, "Coffee", endAt),
                            ActivityObservationV2("reading", null, "Reading", endAt),
                            ActivityObservationV2("songwriting", null, "Songwriting", endAt),
                        ),
                    ),
                ).copy(completedAt = if (completed) endAt else null),
            ),
        )
    }

    private fun hopefulState(id: String, at: Long): StateObservationV2 {
        val mood = AxisReadingV2(DefaultCheckInAxes.MOOD, 6, 0.6)
        val energy = AxisReadingV2(DefaultCheckInAxes.ENERGY, 6, 0.6)
        val confidence = AxisReadingV2(DefaultCheckInAxes.CONFIDENCE, 4, 0.4)
        val anxiety = AxisReadingV2(DefaultCheckInAxes.ANXIETY, 4, 0.4)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(
                mood.axisId to mood,
                energy.axisId to energy,
                confidence.axisId to confidence,
                anxiety.axisId to anxiety,
            ),
            core = CoreAxisViewV2(mood, energy, confidence, anxiety),
            emotion = "Hopeful",
        )
    }

    private fun prefixHistory(): CheckInAnalysisDatasetV2 {
        return history(
            listOf(
                state("early", HOUR, mood = 5, emotion = "calm"),
                state("middle", 2 * HOUR, mood = 8, emotion = "hopeful"),
            ),
            emptyList(),
        )
    }

    private fun cutoffHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("early", HOUR, mood = 5, emotion = "calm"),
                state("middle", 2 * HOUR, mood = 8, emotion = "hopeful"),
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

    private fun activityInterval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activities: List<String>,
    ) = interval(
        end = end,
        previous = previous,
        start = start,
        endAt = endAt,
        activity = ActivityCategoryObservationV2(
            CategoryObservationKindV2.ENTRIES,
            endAt,
            activities.map { name -> ActivityObservationV2("act-$end-$name", null, name, endAt) },
        ),
    )

    private fun interval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
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
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
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
