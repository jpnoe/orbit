package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class WalkForwardSemanticNullComparisonTest {
    @Test
    fun positiveAndNegativeCorrectionsFollowThePriorsThatRemain() {
        val result = evaluate(moodChain())
        val positive = result.folds.single { it.anchorAlias == "S04" }.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(positive.scored)
        val opposite = positive.neighbors.single { it.anchorAlias == "S02" }
        assertTrue(opposite.cosine < 0.0)
        assertEquals(0.0, opposite.weight, 0.0)
        assertTrue(positive.neighbors.single { it.anchorAlias == "S01" }.officialResidual > 0.0)
        val positiveCorrection = checkNotNull(positive.correction)
        assertTrue(positiveCorrection > 0.0)
        assertEquals(positive.numerator / (positive.weightSum + SEMANTIC_NULL_KAPPA), positiveCorrection, 1e-9)
        assertEquals(0.0, positive.numerator - positive.neighbors.sumOf { it.weight * it.officialResidual }, 1e-9)
        val positiveBounded = checkNotNull(positive.nullBounded)
        assertEquals(
            observableForecast(positiveBounded + positiveCorrection).boundedForecast,
            checkNotNull(positive.semanticBounded),
            0.0,
        )

        val negative = result.folds.single { it.anchorAlias == "S05" }.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(negative.scored)
        assertTrue(negative.neighbors.any { it.cosine < 0.0 && it.weight == 0.0 })
        assertTrue(negative.neighbors.any { it.weight > 0.0 && it.officialResidual < 0.0 })
        val negativeCorrection = checkNotNull(negative.correction)
        assertTrue(negativeCorrection < 0.0)
        assertEquals(negative.numerator / (negative.weightSum + SEMANTIC_NULL_KAPPA), negativeCorrection, 1e-9)
    }

    @Test
    fun zeroSupportFallsBackExactlyToTheNull() {
        val result = evaluate(moodChain())
        val fold = result.folds.single { it.anchorAlias == "S02" }
        val mood = fold.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(mood.scored)
        val correction = checkNotNull(mood.correction)
        val nullBounded = checkNotNull(mood.nullBounded)
        assertEquals(0.0, mood.weightSum, 0.0)
        assertEquals(0.0, correction, 0.0)
        assertEquals(0.0, mood.effectiveSupport, 0.0)
        assertEquals(nullBounded, checkNotNull(mood.semanticBounded), 0.0)
        assertTrue(result.overall.zeroSupportScored > 0)
        assertEquals(result.overall.scored, result.overall.better + result.overall.tied + result.overall.worse)
    }

    @Test
    fun aLargeCorrectionIsConstrainedToZeroThroughTen() {
        val result = evaluate(clampHistory())
        val fold = result.folds.single { it.anchorAlias == "S04" }
        val mood = fold.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertTrue(mood.scored)
        val correction = checkNotNull(mood.correction)
        val nullBounded = checkNotNull(mood.nullBounded)
        assertTrue(correction > 0.0)
        assertTrue(nullBounded + correction > 10.0)
        assertEquals(10.0, checkNotNull(mood.semanticBounded), 0.0)
        assertEquals(checkNotNull(mood.actual) - 10.0, checkNotNull(mood.semanticResidual), 1e-9)
    }

    @Test
    fun aMissingNullForecastStaysUnscored() {
        val result = evaluate(wakeHistory())
        val fold = result.folds.single { it.anchorAlias == "no anchor" }
        assertTrue(fold.axes.all { !it.scored })
        assertTrue(fold.axes.all { it.semanticBounded == null && it.correction == null })
        assertTrue(result.byAxis.single { it.label == DefaultCheckInAxes.MOOD }.unscored >= 1)
    }

    @Test
    fun membershipKeepsTheExactAnchorAndDropsTheRest() {
        val result = evaluate(membershipHistory())
        val fold = result.folds.single { it.anchorAlias == "S02" }
        assertTrue(fold.outgoingAbsent)
        assertTrue(fold.futureRowsAbsent)
        assertTrue(fold.sameTimeNonAnchorAbsent)
        assertTrue(fold.fitConceptTokens.any { it.contains("name:arrived") })
        assertFalse(fold.fitConceptTokens.any { it.contains("name:sideways") })
        assertFalse(fold.fitConceptTokens.any { it.contains("secret") })
        assertFalse(fold.fitConceptTokens.any { it.contains("name:future-only") })
        assertTrue(fold.absenceText.contains("Outgoing behavior is absent"))
        assertTrue(result.judgments.any { it.contains("strictly before") })
        assertTrue(result.retrospectiveHorizon)
        assertFalse(result.strictAsKnownAvailability)
        assertEquals(ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION, result.availabilityMode)
        assertFalse(result.replacesNull)
    }

    @Test
    fun theComparisonStaysOutsideTheFrozenNullAndTheLabCanShowIt() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/WalkForwardSemanticNullComparison.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/WalkForwardSemanticNullComparison.kt",
        ).readText()
        assertTrue(source.contains(WALK_FORWARD_SEMANTIC_NULL_COMPARISON))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        val nullLearner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
        ).readText()
        assertFalse(nullLearner.contains(WALK_FORWARD_SEMANTIC_NULL_COMPARISON))
        val embedding = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
        ).readText()
        assertFalse(embedding.contains(WALK_FORWARD_SEMANTIC_NULL_COMPARISON))
        val architecture = source("docs/architecture.md", "../docs/architecture.md").readText()
        assertTrue(architecture.contains(WALK_FORWARD_SEMANTIC_NULL_COMPARISON))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/SemanticNullComparisonSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/SemanticNullComparisonSection.kt",
        ).readText()
        assertTrue(section.contains("Semantic Null comparison"))
        assertTrue(section.contains("Retrospective horizon"))
        assertTrue(section.contains("Strict as-known false"))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("SemanticNullComparisonSection"))
    }

    private fun evaluate(history: CheckInAnalysisDatasetV2): SemanticNullComparisonDataset {
        val bundle = projectForecastExamples(history)
        val projection = projectPersonalSemanticOccurrences(history)
        val evaluation = projectNullTrajectoryLearner(
            bundle.stateForecasts.examples,
            bundle.conditionalTransitions.examples,
            ZoneId.of("UTC"),
        )
        return evaluateWalkForwardSemanticNullComparison(
            evaluation = evaluation,
            stateExamples = bundle.stateForecasts.examples,
            conditionalExamples = bundle.conditionalTransitions.examples,
            projection = projection,
        )
    }

    private fun moodChain(): CheckInAnalysisDatasetV2 {
        val states = listOf(
            state("a", 0L, 0),
            state("b", HOUR, 10),
            state("c", 2 * HOUR, 5),
            state("d", 3 * HOUR, 0),
            state("e", 4 * HOUR, 10),
            state("f", 5 * HOUR, 10),
        )
        val intervals = listOf(
            link("b", "a", 0L, HOUR),
            link("c", "b", HOUR, 2 * HOUR),
            link("d", "c", 2 * HOUR, 3 * HOUR),
            link("e", "d", 3 * HOUR, 4 * HOUR),
            link("f", "e", 4 * HOUR, 5 * HOUR),
        )
        return history(states, intervals)
    }

    private fun clampHistory(): CheckInAnalysisDatasetV2 {
        val names = (1..8).map { "shared-$it" }
        val states = listOf(
            state("pre", -HOUR, 5),
            state("low", 0L, 0),
            state("high", HOUR, 10),
            state("held", 2 * HOUR, 9),
            state("end", 3 * HOUR, 9),
        )
        val intervals = listOf(
            link("low", "pre", -HOUR, 0L, names),
            link("high", "low", 0L, HOUR),
            link("held", "high", HOUR, 2 * HOUR, names),
            link("end", "held", 2 * HOUR, 3 * HOUR),
        )
        return history(states, intervals)
    }

    private fun wakeHistory(): CheckInAnalysisDatasetV2 {
        return history(
            states = listOf(state("woken", HOUR, 4)),
            intervals = listOf(
                interval(
                    end = "woken",
                    previous = null,
                    start = 0L,
                    endAt = HOUR,
                    startBasis = CheckInContextStartBasis.WAKE_TIME,
                ),
            ),
        )
    }

    private fun membershipHistory(): CheckInAnalysisDatasetV2 {
        val states = listOf(
            state("start", 0L, 5, emotion = "one"),
            state("mid", 2 * HOUR, 5, emotion = "two"),
            state("other", 2 * HOUR, 5, emotion = "secret"),
            state("later", 4 * HOUR, 5, emotion = "three"),
        )
        val intervals = listOf(
            link("mid", "start", 0L, 2 * HOUR, listOf("arrived")),
            link("other", "start", 0L, 2 * HOUR, listOf("sideways")),
            link("later", "mid", 2 * HOUR, 4 * HOUR, listOf("future-only")),
        )
        return history(states, intervals)
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

    private fun link(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activities: List<String> = emptyList(),
    ) = interval(
        end = end,
        previous = previous,
        start = start,
        endAt = endAt,
        activity = if (activities.isEmpty()) {
            ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList())
        } else {
            ActivityCategoryObservationV2(
                CategoryObservationKindV2.ENTRIES,
                endAt,
                activities.map { name -> ActivityObservationV2("act-$end-$name", null, name, endAt) },
            )
        },
    )

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        startBasis: String = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = startBasis,
        completedAt = endAt,
        activity = activity,
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun state(id: String, at: Long, mood: Int, emotion: String = ""): StateObservationV2 {
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
