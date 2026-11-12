package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SleepEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalSemanticReciprocalSignatureTest {
    @Test
    fun identicalRawStatesKeepLayerZeroAndChangeTheRetrospectiveContext() {
        val signed = sign()
        val earlier = signed.state(StateOccurrenceKey("a"))
        val later = signed.state(StateOccurrenceKey("b"))
        assertEquals(earlier.layerZero.semantic(), later.layerZero.semantic())
        assertNotEquals(semantic(earlier.retrospective), semantic(later.retrospective))
    }

    @Test
    fun identicalRawTransitionsKeepLayerZeroAndChangeTheRetrospectiveContext() {
        val signed = sign()
        val first = signed.transition(TransitionOccurrenceKey("b"))
        val second = signed.transition(TransitionOccurrenceKey("y"))
        assertEquals(first.layerZero.semantic(), second.layerZero.semantic())
        assertNotEquals(semantic(first.retrospective), semantic(second.retrospective))
    }

    @Test
    fun swappingIncomingAndOutgoingChangesTheSignature() {
        val blocks = sign().state(StateOccurrenceKey("b")).retrospective
        val incoming = blocks.filter { it.role.startsWith("incoming") }.flatMap { it.semantic() }
        val outgoing = blocks.filter { it.role.startsWith("outgoing") }.flatMap { it.semantic() }
        assertNotEquals(incoming.map { it.value }, outgoing.map { it.value })
        val swapped = blocks.map { block ->
            when {
                block.role.startsWith("incoming") -> block.placed(block.role.replace("incoming", "outgoing"))
                block.role.startsWith("outgoing") -> block.placed(block.role.replace("outgoing", "incoming"))
                else -> block
            }
        }
        assertNotEquals(blocks, swapped)
        assertEquals("2", value(blocks, "incidence", "outgoing.count"))
        assertEquals("true", value(blocks, "incidence", "outgoing.ambiguous"))
        assertTrue(values(blocks).any { it.contains("name:reading") })
        assertTrue(values(blocks).any { it.contains("name:fork-mark") })
    }

    @Test
    fun theCausalStateOmitsOutgoingEvidence() {
        val causal = sign().state(StateOccurrenceKey("b")).causal
        assertTrue(causal.none { it.role.startsWith("outgoing") })
        assertFalse(values(causal).any { it.contains("name:reading") || it.contains("name:fork-mark") })
        assertTrue(values(causal).any { it.contains("name:coding") })
    }

    @Test
    fun theCausalTransitionOmitsTheEndingStateAndResidual() {
        val transition = sign().transition(TransitionOccurrenceKey("c"))
        assertTrue(transition.causal.none { it.role == "end" })
        assertFalse(values(transition.causal).any { it.contains("focused") || it == "8" || it.contains("residual") })
        assertFalse(transition.causal.flatMap { it.features }.any { it.name == "duration-millis" })
        assertEquals("absent", value(transition.causal, "horizon", "horizon"))
        assertFalse(sign().dynamics.available)
    }

    @Test
    fun aBoundaryDoesNotInventAFarSideOrAStartingState() {
        val transition = sign().transition(TransitionOccurrenceKey("a"))
        assertEquals("no-starting-state", value(transition.retrospective, "start", "endpoint"))
        assertFalse(values(transition.retrospective).any { it.contains("far-side") })
        assertFalse(values(transition.causal).any { it.contains("far-side") || it.contains("EMOTION|calm|") })
        assertTrue(values(transition.retrospective).any { it.contains("EMOTION|calm|") })
    }

    @Test
    fun theSameRawIdentityInTwoTypesStaysTwoFeatures() {
        val values = values(sign().transition(TransitionOccurrenceKey("y")).retrospective)
        val emotion = "EMOTION|name:coding|"
        val activity = "ACTIVITY|name:coding|"
        assertTrue(values.contains(emotion))
        assertTrue(values.contains(activity))
        assertNotEquals(emotion, activity)
    }

    @Test
    fun reviewStatusesStayDistinctAndConflictDoesNotBecomeAConcept() {
        val layer = sign().transition(TransitionOccurrenceKey("a")).layerZero
        val tokens = layer.features.filter { it.name.startsWith("category.") }.map { it.value }.toSet()
        assertEquals(setOf("reviewed-empty", "unreviewed", "absent", "conflicting"), tokens)
        assertFalse(values(listOf(layer)).any { it.contains("secret") })
    }

    @Test
    fun maskingOneConceptRemovesItFromEveryChannel() {
        val coding = ConceptIdentityKey(SemanticConceptType.ACTIVITY, categoricalKey(null, "coding"))
        val masked = sign(SignatureMask(excludedConcepts = setOf(coding)))
        val visible = masked.states.flatMap { it.retrospective + it.causal + listOf(it.layerZero) } +
            masked.transitions.flatMap { it.retrospective + it.causal + listOf(it.layerZero) }
        assertFalse(values(visible).any { it.contains("ACTIVITY|name:coding|") })
        assertTrue(values(visible).any { it.contains("EMOTION|name:coding|") })
        assertTrue(masked.states.all { coding !in it.retrospectiveOov.map { coverage -> coverage.concept } })
    }

    @Test
    fun aFrozenVocabularyReportsALaterConceptAsOov() {
        val projection = projection()
        val graph = projectAlternatingTemporalGraph(history())
        val early = vocabularyBeforeCutoff(projection, 25 * HOUR)
        val signed = projectReciprocalSignatures(projection, graph, early)
        val novel = ConceptIdentityKey(SemanticConceptType.EMOTION, "novel")
        assertFalse(early.contains(novel))
        assertTrue(early.contains(ConceptIdentityKey(SemanticConceptType.EMOTION, "calm")))
        val state = signed.state(StateOccurrenceKey("n"))
        assertFalse(values(listOf(state.layerZero)).any { it.contains("novel") })
        assertTrue(state.causalOov.any { it.concept == novel })
        assertFalse(early.contains(novel))
    }

    @Test
    fun rareAndRepeatedConceptsKeepSupportAndGainNoImportance() {
        val projection = projection()
        val graph = projectAlternatingTemporalGraph(history())
        val full = retrospectiveVocabulary(projection)
        val early = vocabularyBeforeCutoff(projection, 25 * HOUR)
        val calm = ConceptIdentityKey(SemanticConceptType.EMOTION, "calm")
        val focused = ConceptIdentityKey(SemanticConceptType.EMOTION, "focused")
        assertEquals(3, full.supportOf(calm))
        assertEquals(2, early.supportOf(calm))
        assertEquals(1, full.supportOf(focused))
        assertEquals(1, early.supportOf(focused))
        val fullSigned = projectReciprocalSignatures(projection, graph, full)
        val earlySigned = projectReciprocalSignatures(projection, graph, early)
        assertEquals(
            fullSigned.state(StateOccurrenceKey("a")).layerZero.semantic(),
            earlySigned.state(StateOccurrenceKey("a")).layerZero.semantic(),
        )
        val column = fullSigned.state(StateOccurrenceKey("a")).layerZero.features.single { it.value.startsWith("EMOTION|calm|") }
        assertEquals("EMOTION|calm|", column.value)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticReciprocalSignature.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticReciprocalSignature.kt",
        ).readText()
        assertFalse(source.contains("weight"))
        assertFalse(full.concepts.any { it.key.type == SemanticConceptType.SLEEP_LOG })
        assertFalse(values(fullSigned.states.flatMap { listOf(it.layerZero) }).any { it.contains("sleep-log") })
    }

    @Test
    fun theSignatureStaysOutsideThePredictor() {
        val signed = sign()
        assertFalse(signed.predictive || signed.updatesModel || signed.suitableForRecommendation)
        assertTrue(signed.vocabulary.retrospective)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticReciprocalSignature.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticReciprocalSignature.kt",
        ).readText()
        assertTrue(source.contains(PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        assertFalse(source.contains("fitRetrospectiveLatentProjection"))
        val learner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
        ).readText()
        assertFalse(learner.contains(PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("ReciprocalSignatureSection"))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/ReciprocalSignatureSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/ReciprocalSignatureSection.kt",
        ).readText()
        assertTrue(section.contains("Reciprocal signature"))
    }

    private fun sign(mask: SignatureMask = SignatureMask()): ReciprocalSignatureDataset {
        val projection = projection()
        return projectReciprocalSignatures(
            projection = projection,
            graph = projectAlternatingTemporalGraph(history()),
            vocabulary = retrospectiveVocabulary(projection),
            mask = mask,
        )
    }

    private fun projection(): PersonalSemanticOccurrenceProjection {
        return projectPersonalSemanticOccurrences(
            dataset = history(),
            sleep = listOf(SleepEvent(id = "night", localDate = "2026-01-15", bedtime = 0L, wakeTime = HOUR, sleepQuality = 7)),
        )
    }

    private fun history(): CheckInAnalysisDatasetV2 {
        return CheckInAnalysisDatasetV2(
            states = listOf(
                state("p", 0L, mood = 1, emotion = "far-side"),
                state("a", 10 * HOUR, mood = 5, emotion = "calm"),
                state("b", 12 * HOUR, mood = 5, emotion = "calm"),
                state("c", 14 * HOUR, mood = 8, emotion = "focused"),
                state("e", 16 * HOUR, mood = 2, emotion = "side"),
                state("x", 20 * HOUR, mood = 7, emotion = "name:coding"),
                state("y", 22 * HOUR, mood = 9, emotion = "beta"),
                state("late", 28 * HOUR, mood = 5, emotion = "calm"),
                state("n", 30 * HOUR, mood = 5, emotion = "novel"),
            ),
            intervals = listOf(
                interval(
                    end = "a",
                    previous = "p",
                    start = 0L,
                    endAt = 10 * HOUR,
                    basis = CheckInContextStartBasis.WAKE_TIME,
                    workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                    diet = DietCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.CONFLICTING,
                        null,
                        listOf(UsageObservationV2("row", null, "secret", "COPING", "1 mg", 10 * HOUR)),
                    ),
                ),
                link("b", "a", 10 * HOUR, 12 * HOUR, "coding"),
                link("c", "b", 12 * HOUR, 14 * HOUR, "reading"),
                link("e", "b", 12 * HOUR, 16 * HOUR, "fork-mark"),
                link("y", "x", 20 * HOUR, 22 * HOUR, "coding"),
            ),
            transitions = emptyList(),
            issues = emptyList(),
        )
    }

    private fun link(end: String, previous: String, start: Long, endAt: Long, activity: String) = interval(
        end = end,
        previous = previous,
        start = start,
        endAt = endAt,
        basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        activity = ActivityCategoryObservationV2(
            CategoryObservationKindV2.ENTRIES,
            endAt,
            listOf(ActivityObservationV2("act-$end", null, activity, endAt)),
        ),
    )

    private fun interval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        basis: String,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout: WorkoutCategoryObservationV2 = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet: DietCategoryObservationV2 = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage: UsageCategoryObservationV2 = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = endAt,
        activity = activity,
        workout = workout,
        diet = diet,
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

    private fun semantic(blocks: List<SignatureBlock>): List<SymbolicFeature> = blocks.flatMap { it.semantic() }

    private fun values(blocks: List<SignatureBlock>): List<String> = blocks.flatMap { it.features }.map { it.value }

    private fun value(blocks: List<SignatureBlock>, role: String, name: String): String {
        return blocks.first { it.role == role }.features.first { it.name == name }.value
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
