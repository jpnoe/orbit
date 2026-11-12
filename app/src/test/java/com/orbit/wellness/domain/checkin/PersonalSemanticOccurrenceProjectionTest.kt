package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalSemanticOccurrenceProjectionTest {
    @Test
    fun ordinaryLinkKeepsItsEndpointsAndRepeatedConcepts() {
        val projection = project()
        val linked = projection.transitions.single { it.key.raw == "shared" }
        assertEquals(StateOccurrenceKey("earlier"), linked.startStateKey)
        assertEquals(StateOccurrenceKey("shared"), linked.endStateKey)
        assertFalse(linked.boundary)
        assertTrue(linked.eligibility.eligible)
        assertEquals(2, projection.concepts.single { it.displayNames == listOf("Numb") }.backboneOccurrences)
        assertEquals(2, projection.concepts.single { it.displayNames == listOf("coding") }.backboneOccurrences)
        val sharedLinks = projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
            .filter { it.key.raw == "shared" }
        assertEquals(
            setOf(
                SemanticConceptType.ACTIVITY,
                SemanticConceptType.FOOD,
                SemanticConceptType.MEAL_SIZE,
                SemanticConceptType.CONTEXTUAL_USAGE,
            ),
            sharedLinks.map { it.concept.type }.toSet(),
        )
    }

    @Test
    fun aBoundaryKeepsItsEndingStateAndDoesNotInventAStart() {
        val boundary = project().transitions.single { it.key.raw == "later" }
        assertTrue(boundary.boundary)
        assertNull(boundary.startStateKey)
        assertEquals(StateOccurrenceKey("later"), boundary.endStateKey)
        assertTrue(boundary.eligibility.reasons.contains(CausalIneligibilityReason.BOUNDARY_INTERVAL))
    }

    @Test
    fun missingAndConflictingAxesStayDistinctFromARecordedZero() {
        val shared = project().states.single { it.key.raw == "shared" }
        assertEquals(0, shared.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.raw)
        assertEquals(AxisOccurrenceStatus.OBSERVED, shared.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.status)
        assertEquals(AxisOccurrenceStatus.MISSING, shared.axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }.status)
        assertEquals(AxisOccurrenceStatus.CONFLICTING, shared.axes.single { it.axisId == DefaultCheckInAxes.ANXIETY }.status)
        val anxiety = project().axes.single { it.axisId == DefaultCheckInAxes.ANXIETY }
        assertEquals(1, anxiety.conflicting)
        assertTrue(anxiety.missing > 0)
    }

    @Test
    fun reviewedEmptinessIsNotAConceptAndUnknownStaysUnknown() {
        val categories = project().categories.associateBy { it.category }
        assertTrue(categories.getValue("activity").reviewedEntries > 0)
        assertTrue(categories.getValue("workout").reviewedEmpty > 0)
        assertTrue(categories.getValue("workout").unreviewed > 0)
        assertEquals(0, categories.getValue("workout").unknown.minus(categories.getValue("workout").unreviewed + categories.getValue("workout").absent))
        val labels = projectionLabels(project())
        assertFalse(labels.contains("secret"))
        assertTrue(project().structure.conflicting >= 1)
        assertTrue(project().structure.incomplete >= 1)
        assertEquals(1, project().structure.unlinkedStates)
        assertEquals(1, project().structure.unlinkedTransitions)
    }

    @Test
    fun usageKeepsIntentAndQuantityApartFromTheName() {
        val usage = project().links.filterIsInstance<ConceptObservationLink.OnTransition>()
            .single { it.concept.type == SemanticConceptType.CONTEXTUAL_USAGE }
        assertEquals("COPING", usage.concept.intent)
        assertFalse(usage.concept.identity.contains("10 mg"))
        val amounts = (usage.quantity as OccurrenceQuantity.UsageAmounts).amounts
        assertEquals(2, amounts.size)
        assertEquals(2, project().quantity.usageParsed)
        assertEquals(1, project().quantity.usageOccurrences)
    }

    @Test
    fun externalSleepAndMedicationStayUnattached() {
        val projection = project()
        assertTrue(projection.unattached.any { it.concept.type == SemanticConceptType.SLEEP_LOG })
        assertTrue(projection.unattached.any { it.concept.type == SemanticConceptType.TIMED_SUBSTANCE && it.label == "melatonin" })
        assertTrue(projection.unattached.any { it.concept.type == SemanticConceptType.WEARABLE_SLEEP })
        assertTrue(projection.links.none { it.concept.type == SemanticConceptType.SLEEP_LOG })
        assertTrue(projection.links.none { it.concept.type == SemanticConceptType.TIMED_SUBSTANCE })
        assertTrue(projection.links.none { it.concept.type == SemanticConceptType.WEARABLE_SLEEP })
        assertEquals(0, projection.concepts.single { it.key.type == SemanticConceptType.SLEEP_LOG }.backboneOccurrences)
        assertEquals(1, projection.wearableRowsOutsideInventory)
        assertTrue(projection.concepts.none { it.displayNames.any { name -> name == "steps" } })
    }

    @Test
    fun aSharedRawStringStaysThreeTypedKeys() {
        val projection = project()
        val overlap = projection.overlaps.single { it.raw == "shared" }
        assertTrue(overlap.inState)
        assertTrue(overlap.inTransition)
        assertEquals(SemanticConceptType.EMOTION, overlap.concepts.single().type)
        val state = StateOccurrenceKey("shared")
        val transition = TransitionOccurrenceKey("shared")
        val concept = ConceptIdentityKey(SemanticConceptType.EMOTION, "shared")
        assertEquals(state.raw, transition.raw)
        assertEquals(state.raw, concept.identity)
        assertFalse(state.equals(transition))
        assertFalse(state.equals(concept))
        val byState = mapOf<Any, String>(state to "state", transition to "transition", concept to "concept")
        assertEquals(3, byState.size)
    }

    @Test
    fun singletonsAreTheConceptsSeenOnce() {
        val once = project().seenOnce().map { it.key.type }.toSet()
        assertTrue(SemanticConceptType.FOOD in once)
        assertTrue(SemanticConceptType.MEAL_SIZE in once)
        assertTrue(SemanticConceptType.CONTEXTUAL_USAGE in once)
        assertTrue(SemanticConceptType.EMOTION in project().seenTwice().map { it.key.type })
    }

    @Test
    fun theProjectionStaysOutsideLearnersAndStoredGraphs() {
        assertFalse(project().predictive || project().updatesModel || project().suitableForRecommendation)
        assertEquals(OCCURRENCE_PROJECTION_SCOPE, project().scope)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticOccurrenceProjection.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticOccurrenceProjection.kt",
        ).readText()
        assertTrue(source.contains(PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION))
        listOf(
            "projectNullTrajectoryLearner",
            "projectCausalFeatureEncoder",
            "fitRetrospectiveLatentProjection",
            "Dao",
            ".insert",
        ).forEach { token -> assertFalse(token, source.contains(token)) }
        val learner = source(
            "src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/NullTrajectoryLearner.kt",
        ).readText()
        assertFalse(learner.contains(PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION))
        val architecture = source(
            "docs/architecture.md",
            "../docs/architecture.md",
        ).readText()
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_OCCURRENCE_PROJECTION))
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("OccurrenceProjectionSection"))
        val section = source(
            "src/main/java/com/orbit/wellness/ui/insights/OccurrenceProjectionSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/OccurrenceProjectionSection.kt",
        ).readText()
        assertTrue(section.contains("Occurrence projection"))
        val replay = lab.substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("projectExpertHorizon"))
    }

    private fun projectionLabels(projection: PersonalSemanticOccurrenceProjection): String {
        return projection.concepts.flatMap { it.displayNames }.joinToString(" ")
    }

    private fun project(): PersonalSemanticOccurrenceProjection {
        return projectPersonalSemanticOccurrences(
            dataset = dataset(),
            sleep = listOf(SleepEvent(id = "night", localDate = "2026-01-15", bedtime = 0L, wakeTime = 8 * HOUR, sleepQuality = 7)),
            substances = listOf(SubstanceUseEvent(id = "dose", timestamp = 3 * HOUR, name = "melatonin", amount = "3 mg", moodScore = 5, intent = "SUPPORTIVE")),
            wearables = listOf(sleepRow(), stepsRow()),
        )
    }

    private fun dataset(): CheckInAnalysisDatasetV2 {
        val earlier = state("earlier", 0L, mood = 1, emotion = "Numb")
        val shared = state(
            id = "shared",
            at = 2 * HOUR,
            mood = 4,
            energy = 0,
            confidence = null,
            anxiety = null,
            conflicting = listOf(DefaultCheckInAxes.ANXIETY),
            emotion = "shared",
        )
        val later = state("later", 4 * HOUR, mood = 6, emotion = "Numb")
        val open = state("open", 6 * HOUR, mood = 5, emotion = "")
        val lone = state("lone", 7 * HOUR, mood = null, emotion = "")
        val dangling = state("dangling", 9 * HOUR, mood = 3, emotion = "")
        return CheckInAnalysisDatasetV2(
            states = listOf(earlier, shared, later, open, lone, dangling),
            intervals = listOf(
                interval(
                    end = "shared",
                    previous = "earlier",
                    start = 0L,
                    endAt = 2 * HOUR,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = entriesActivity("coding"),
                    diet = entriesMeal("large", "chili dog"),
                    usage = entriesUsage("YouTube", "COPING", listOf("10 mg", "2")),
                ),
                interval(
                    end = "later",
                    previous = "shared",
                    start = 2 * HOUR,
                    endAt = 4 * HOUR,
                    basis = CheckInContextStartBasis.WAKE_TIME,
                    activity = entriesActivity("coding"),
                    workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                    usage = UsageCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                ),
                interval(
                    end = "open",
                    previous = "later",
                    start = 4 * HOUR,
                    endAt = 6 * HOUR,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    completedAt = null,
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.CONFLICTING,
                        null,
                        listOf(UsageObservationV2("row", null, "secret", "COPING", "1 mg", 6 * HOUR)),
                    ),
                ),
                interval(
                    end = "dangling",
                    previous = "missing",
                    start = 8 * HOUR,
                    endAt = 9 * HOUR,
                    basis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
            ),
            transitions = emptyList(),
            issues = emptyList(),
        )
    }

    private fun state(
        id: String,
        at: Long,
        mood: Int?,
        energy: Int? = null,
        confidence: Int? = null,
        anxiety: Int? = null,
        conflicting: List<String> = emptyList(),
        emotion: String = "",
    ): StateObservationV2 {
        val readings = listOfNotNull(
            mood?.let { AxisReadingV2(DefaultCheckInAxes.MOOD, it, it / 10.0) },
            energy?.let { AxisReadingV2(DefaultCheckInAxes.ENERGY, it, it / 10.0) },
            confidence?.let { AxisReadingV2(DefaultCheckInAxes.CONFIDENCE, it, it / 10.0) },
            anxiety?.let { AxisReadingV2(DefaultCheckInAxes.ANXIETY, it, it / 10.0) },
        )
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                mood = readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                energy = readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                confidence = readings.firstOrNull { it.axisId == DefaultCheckInAxes.CONFIDENCE },
                anxiety = readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
            conflictingAxisIds = conflicting,
            emotion = emotion,
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        completedAt: Long? = endAt,
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
        completedAt = completedAt,
        activity = activity,
        workout = workout,
        diet = diet,
        usage = usage,
    )

    private fun entriesActivity(name: String) = ActivityCategoryObservationV2(
        CategoryObservationKindV2.ENTRIES,
        1L,
        listOf(ActivityObservationV2("a-$name", null, name, 1L)),
    )

    private fun entriesMeal(size: String, food: String) = DietCategoryObservationV2(
        CategoryObservationKindV2.ENTRIES,
        1L,
        listOf(
            MealObservationV2(
                id = "meal",
                size = size,
                sortOrder = 0,
                reportedAt = 1L,
                foods = listOf(FoodObservationV2("food", null, food, 0)),
            ),
        ),
    )

    private fun entriesUsage(name: String, intent: String, amounts: List<String>) = UsageCategoryObservationV2(
        CategoryObservationKindV2.ENTRIES,
        1L,
        amounts.mapIndexed { index, amount ->
            UsageObservationV2("u$index", null, name, intent, amount, 1L)
        },
    )

    private fun sleepRow() = WearableObservation.create(
        metric = WearableMetric.SLEEP_DURATION,
        value = 7.5,
        unit = WearableUnit.HOURS,
        observedStartAt = 0L,
        observedEndAt = 8 * HOUR,
        temporalKind = WearableTemporalKind.MEASURED_INTERVAL,
        method = WearableMethod.SLEEP_SESSION,
        importedAt = 9 * HOUR,
    )

    private fun stepsRow() = WearableObservation.create(
        metric = WearableMetric.STEPS,
        value = 100.0,
        unit = WearableUnit.COUNT,
        observedStartAt = 0L,
        observedEndAt = 2 * HOUR,
        temporalKind = WearableTemporalKind.AGGREGATED_WINDOW,
        method = WearableMethod.AGGREGATE_TOTAL,
        importedAt = 9 * HOUR,
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
