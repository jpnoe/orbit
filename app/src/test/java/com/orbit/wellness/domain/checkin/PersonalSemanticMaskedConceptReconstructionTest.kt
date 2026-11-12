package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalSemanticMaskedConceptReconstructionTest {
    @Test
    fun repeatedContextOutranksAMoreFrequentEmotion() {
        val result = evaluate(moodHistory())
        val fold = result.folds.single { it.ownerAlias == "S01" && it.target.identity == "hopeful" }
        assertTrue(fold.heldOutAbsent)
        assertFalse(fold.coldStart)
        assertEquals(2, fold.supportBefore)
        assertEquals(1, fold.supportRemaining)
        assertFalse(fold.visibleText.contains("hopeful"))
        assertEquals(setOf("hopeful", "tired"), fold.candidates.map { it.identity }.toSet())
        assertEquals(fold.frequency.ranked.toSet(), fold.layerZero.ranked.toSet())
        assertEquals(fold.frequency.ranked.toSet(), fold.reciprocal.ranked.toSet())
        assertEquals(fold.line() + " / " + fold.visibleText, 2, fold.frequency.rank)
        assertEquals(fold.line(), 1, fold.layerZero.rank)
        assertEquals(fold.line(), 1, fold.reciprocal.rank)
        assertEquals(5, result.eligible)
        assertEquals(5, result.scored)
        assertEquals(0, result.coldStart)
        assertTrue(result.folds.all { it.line().isNotBlank() })
        assertTrue(result.byType.any { it.label == "emotion" })
        assertTrue(result.bySupportBand.any { it.label == "remaining 1" })
    }

    @Test
    fun aSupportOneTargetIsColdStart() {
        val result = evaluate(history(listOf(state("only", 0L, mood = 5, emotion = "calm")), emptyList()))
        val fold = result.folds.single()
        assertTrue(fold.coldStart)
        assertTrue(fold.heldOutAbsent)
        assertFalse(fold.scored)
        assertNull(fold.frequency.rank)
        assertNull(fold.layerZero.rank)
        assertNull(fold.reciprocal.rank)
        assertEquals(1, result.eligible)
        assertEquals(1, result.coldStart)
        assertEquals(0, result.scored)
        assertEquals(0, result.frequency.top1Hits)
        assertTrue(fold.line().contains("cold-start"))
        assertTrue(result.judgments.any { it.contains("not an incorrect prediction") })
    }

    @Test
    fun tiesBreakByIdentity() {
        val result = evaluate(tieHistory())
        val fold = result.folds.single { it.ownerAlias == "S02" && it.target.identity == "moth" }
        assertEquals(listOf("bee", "moth"), fold.frequency.ranked.map { it.identity })
        assertEquals(1.0, checkNotNull(fold.frequency.score), 0.0)
        assertEquals(2, fold.frequency.rank)
        assertEquals(fold.frequency.ranked.map { it.identity }.toSet(), fold.layerZero.ranked.map { it.identity }.toSet())
        assertTrue(result.method.contains("ascending order"))
    }

    @Test
    fun theHeldOutLinkLeavesTheOtherOccurrence() {
        val ending = "11111111-2222-3333-4444-555555555555"
        val history = codingHistory(ending)
        val fitted = fit(history)
        val codingFeatures = fitted.signatures.transitions.sumOf { transition ->
            transition.layerZero.semantic().count { it.value.contains("name:coding") }
        }
        val result = evaluate(history)
        assertEquals(codingFeatures, fitted.signatures.transitions.sumOf { transition ->
            transition.layerZero.semantic().count { it.value.contains("name:coding") }
        })
        assertEquals(2, codingFeatures)
        val fold = result.folds.single { it.ownerAlias == "T01" && it.type == SemanticConceptType.ACTIVITY }
        assertTrue(fold.heldOutAbsent)
        assertEquals(fold.absenceText, true, fold.heldOutAbsent)
        assertFalse(fold.coldStart)
        assertEquals(1, fold.supportRemaining)
        assertFalse(fold.visibleText.contains("coding"))
        assertFalse(fold.line().contains(ending))
        assertFalse(fold.visibleText.contains(ending))
        assertTrue(result.folds.any { it.ownerAlias == "T02" && it.scored })
    }

    @Test
    fun unreviewedAndReviewedEmptyAreNotTargets() {
        val result = evaluate(unreviewedHistory())
        assertEquals(2, result.eligible)
        assertTrue(result.folds.all { it.type == SemanticConceptType.EMOTION })
        assertTrue(result.judgments.any { it.contains("Reviewed-empty") })
    }

    @Test
    fun theEvaluationStaysOutsideThePredictorAndTheLabCanShowIt() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticMaskedConceptReconstruction.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticMaskedConceptReconstruction.kt",
        ).readText()
        assertTrue(source.contains(PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION))
        assertTrue(source.contains("not a causal evaluation"))
        assertFalse(source.contains("perHour"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        val embedding = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
        ).readText()
        val distributional = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
        ).readText()
        assertFalse(embedding.contains(PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION))
        assertFalse(distributional.contains(PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("MaskedConceptReconstructionSection"))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/MaskedConceptReconstructionSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/MaskedConceptReconstructionSection.kt",
        ).readText()
        assertTrue(section.contains("Concept reconstruction"))
        assertTrue(section.contains("cold-start"))
        val result = evaluate(moodHistory())
        assertFalse(result.predictive || result.updatesModel || result.suitableForRecommendation)
        assertFalse(result.walkForward || result.causal)
        assertTrue(result.retrospective)
    }

    private fun evaluate(history: CheckInAnalysisDatasetV2): MaskedConceptReconstructionDataset {
        val fitted = fit(history)
        return evaluateMaskedConceptReconstruction(
            projection = fitted.projection,
            graph = fitted.graph,
            signatures = fitted.signatures,
            audit = fitted.embedding.audit,
            reading = fitted.distributional.reading,
        )
    }

    private data class Fitted(
        val projection: PersonalSemanticOccurrenceProjection,
        val graph: AlternatingTemporalGraph,
        val signatures: ReciprocalSignatureDataset,
        val embedding: JointEmbeddingDataset,
        val distributional: DistributionalContextDataset,
    )

    private fun fit(history: CheckInAnalysisDatasetV2): Fitted {
        val projection = projectPersonalSemanticOccurrences(history)
        val graph = projectAlternatingTemporalGraph(history)
        val vocabulary = retrospectiveVocabulary(projection)
        val signatures = projectReciprocalSignatures(projection, graph, vocabulary)
        val embedding = projectJointSemanticEmbedding(signatures, graph, projection)
        val distributional = projectDistributionalContext(
            embedding,
            vocabulary.concepts.associate { conceptNodeId(it.key) to it.backboneSupport },
            projection,
        )
        return Fitted(projection, graph, signatures, embedding, distributional)
    }

    private fun moodHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("a", 0L, mood = 8, emotion = "hopeful"),
                state("b", 2 * HOUR, mood = 8, emotion = "hopeful"),
                state("c", 4 * HOUR, mood = 2, emotion = "tired"),
                state("d", 6 * HOUR, mood = 2, emotion = "tired"),
                state("e", 8 * HOUR, mood = 2, emotion = "tired"),
            ),
            intervals = emptyList(),
        )
    }

    private fun tieHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("bee", 0L, mood = 5, emotion = "bee"),
                state("moth-a", 2 * HOUR, mood = 5, emotion = "moth"),
                state("moth-b", 4 * HOUR, mood = 5, emotion = "moth"),
            ),
            intervals = emptyList(),
        )
    }

    private fun codingHistory(ending: String): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("start", 0L, mood = 5, emotion = "one"),
                state(ending, 2 * HOUR, mood = 5, emotion = "two"),
                state("later", 4 * HOUR, mood = 5, emotion = "three"),
            ),
            intervals = listOf(
                activityInterval(ending, "start", 0L, 2 * HOUR, "coding"),
                activityInterval("later", ending, 2 * HOUR, 4 * HOUR, "coding"),
            ),
        )
    }

    private fun unreviewedHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(
                state("a", 0L, mood = 5, emotion = "hopeful"),
                state("b", 2 * HOUR, mood = 4, emotion = "calm"),
            ),
            intervals = listOf(
                interval(
                    end = "b",
                    previous = "a",
                    start = 0L,
                    endAt = 2 * HOUR,
                    activity = ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, 2 * HOUR, emptyList()),
                ),
            ),
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
