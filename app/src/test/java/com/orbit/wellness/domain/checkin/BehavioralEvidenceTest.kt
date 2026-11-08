package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class BehavioralEvidenceTest {
    @Test
    fun optionIdsAndNormalizedNamesStayStable() {
        val evidence = projectBehavioralEvidence(
            dataset(
                states = listOf(state("end", HOUR, mood = 4)),
                intervals = listOf(
                    interval(
                        end = "end",
                        previous = null,
                        start = 0L,
                        endAt = HOUR,
                        basis = CheckInContextStartBasis.WAKE_TIME,
                        activity = CategoryObservationKindV2.ENTRIES,
                        activities = listOf(
                            ActivityObservationV2("a1", "opt-shower", "Shower", HOUR),
                            ActivityObservationV2("a2", null, "  Hot   Yoga ", HOUR),
                        ),
                    ),
                ),
            ),
            ZoneOffset.UTC,
        )
        val shower = evidence.catalog.single { it.identity.kind == BehaviorKind.ACTIVITY && it.identity.key == "option:opt-shower" }
        val yoga = evidence.catalog.single { it.displayNames == listOf("  Hot   Yoga ") }
        assertEquals("option:opt-shower", shower.identity.key)
        assertEquals(listOf("Shower"), shower.displayNames)
        assertEquals("name:hot yoga", yoga.identity.key)
        assertNull(yoga.identity.intent)
    }

    @Test
    fun categoryAndIntentKeepTheSameWordsApart() {
        val evidence = projectBehavioralEvidence(namedDataset(), ZoneOffset.UTC)
        val identities = evidence.catalog.map { it.identity }
        val activity = identities.single { it.kind == BehaviorKind.ACTIVITY }
        val workout = identities.single { it.kind == BehaviorKind.WORKOUT }
        assertEquals(activity.key, workout.key)
        assertEquals(BehaviorKind.ACTIVITY, activity.kind)
        assertEquals(BehaviorKind.WORKOUT, workout.kind)
        val supportive = identities.single { it.intent == SubstanceIntent.SUPPORTIVE.name }
        val coping = identities.single { it.intent == SubstanceIntent.COPING.name }
        assertEquals(supportive.key, coping.key)
        assertEquals(BehaviorKind.USAGE, supportive.kind)
        assertTrue(supportive != coping)
    }

    @Test
    fun usagePresenceSurvivesBlankMalformedAndZeroAmounts() {
        val evidence = projectBehavioralEvidence(amountDataset(), ZoneOffset.UTC)
        val tea = usage(evidence, "option:tea")
        assertEquals(
            listOf(UsageQuantityStatus.MISSING, UsageQuantityStatus.UNPARSED),
            tea.amounts.map { it.status }.sortedBy { it.name },
        )
        assertTrue(tea.comparable.isEmpty())
        assertTrue(evidence.intervals.single().exposures.any { it.identity == tea.identity })
        val water = usage(evidence, "option:water")
        assertEquals(UsageQuantityStatus.PARSED, water.amounts.single().status)
        assertEquals(0.0, water.amounts.single().numericAmount)
        assertEquals(0.0, water.comparable.single().total, 0.0)
    }

    @Test
    fun physicalUnitsStaySeparateFromUnitlessCounts() {
        val caffeine = usage(projectBehavioralEvidence(amountDataset(), ZoneOffset.UTC), "option:caffeine")
        assertEquals(1, projectBehavioralEvidence(amountDataset(), ZoneOffset.UTC).catalog.count { it.identity.key == "option:caffeine" })
        val byUnit = caffeine.comparable.associateBy { it.unit }
        assertEquals(2.0, byUnit.getValue("mg").total, 0.0)
        assertNull(byUnit.getValue("mg").perHour)
        assertEquals(4.0, byUnit.getValue(null).total, 0.0)
        assertEquals(2.0, byUnit.getValue(null).perHour!!, 1e-9)
    }

    @Test
    fun reviewStatusStaysNoneEntriesOrUnknown() {
        val none = projectBehavioralEvidence(
            dataset(
                states = listOf(state("end", HOUR, mood = 1)),
                intervals = listOf(
                    interval(
                        end = "end",
                        previous = null,
                        start = 0L,
                        endAt = HOUR,
                        basis = CheckInContextStartBasis.WAKE_TIME,
                        activity = CategoryObservationKindV2.NONE,
                        activities = listOf(ActivityObservationV2("stray", "opt-x", "Stray", HOUR)),
                    ),
                ),
            ),
            ZoneOffset.UTC,
        ).intervals.single()
        assertEquals(CategoryKnowledge.REVIEWED_NONE, none.activityReview)
        assertTrue(none.exposures.isEmpty())

        val entries = projectBehavioralEvidence(namedDataset(), ZoneOffset.UTC).intervals.single()
        assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, entries.activityReview)
        assertEquals(CategoryKnowledge.REVIEWED_ENTRIES, entries.usageReview)
        assertTrue(entries.exposures.any { it.identity.kind == BehaviorKind.ACTIVITY })

        val unknown = projectBehavioralEvidence(
            dataset(
                states = listOf(state("end", HOUR, mood = 1)),
                intervals = listOf(
                    interval(
                        end = "end",
                        previous = null,
                        start = 0L,
                        endAt = HOUR,
                        basis = CheckInContextStartBasis.WAKE_TIME,
                        activity = CategoryObservationKindV2.UNREVIEWED,
                        workout = CategoryObservationKindV2.ABSENT,
                        diet = CategoryObservationKindV2.CONFLICTING,
                        meals = listOf(meal("large", FoodObservationV2("f", "food", "Oats", 0))),
                        usage = CategoryObservationKindV2.UNREVIEWED,
                        usageItems = listOf(usageItem("tea", SubstanceIntent.SUPPORTIVE.name, "1")),
                    ),
                ),
            ),
            ZoneOffset.UTC,
        ).intervals.single()
        assertEquals(CategoryKnowledge.UNKNOWN, unknown.activityReview)
        assertEquals(CategoryKnowledge.UNKNOWN, unknown.workoutReview)
        assertEquals(CategoryKnowledge.UNKNOWN, unknown.dietReview)
        assertEquals(CategoryKnowledge.UNKNOWN, unknown.usageReview)
        assertTrue(unknown.exposures.isEmpty())
    }

    @Test
    fun boundariesAndPartialAxesDoNotInventDeltas() {
        val start = 40L * DAY
        val evidence = projectBehavioralEvidence(
            dataset(
                states = listOf(
                    state("earlier", start, mood = 2, energy = 3, anxiety = 1),
                    state("wake", start + HOUR, mood = 9, energy = 4),
                    state("day", start + 2 * HOUR, mood = 8),
                    state("later", start + 3 * HOUR, mood = 6, anxiety = 4),
                    state("moodOnly", start + 4 * HOUR, mood = 2),
                    state("energyOnly", start + 5 * HOUR, energy = 5),
                ),
                intervals = listOf(
                    interval("wake", "earlier", start, start + HOUR, CheckInContextStartBasis.WAKE_TIME),
                    interval("day", "earlier", start, start + 2 * HOUR, CheckInContextStartBasis.DAY_START),
                    interval("later", "earlier", start, start + 3 * HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                    interval("energyOnly", "moodOnly", start + 4 * HOUR, start + 5 * HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
            ZoneOffset.UTC,
        )
        val wake = evidence.intervals.single { it.key.raw == "wake" }
        val day = evidence.intervals.single { it.key.raw == "day" }
        assertNull(wake.startStateKey)
        assertNull(day.startStateKey)
        assertEquals("wake", wake.endStateKey.raw)
        assertTrue(wake.axisDeltas.measured().isEmpty())
        assertTrue(day.axisDeltas.measured().isEmpty())
        assertTrue(wake.eligibility.reasons.contains(CausalIneligibilityReason.BOUNDARY_INTERVAL))
        assertEquals(9, wake.endAxes.mood)
        val linked = evidence.intervals.single { it.key.raw == "later" }
        assertEquals("earlier", linked.startStateKey?.raw)
        assertEquals("later", linked.endStateKey.raw)
        assertEquals(4, linked.axisDeltas.mood)
        assertNull(linked.axisDeltas.energy)
        assertNull(linked.axisDeltas.confidence)
        assertEquals(3, linked.axisDeltas.anxiety)
        val unshared = evidence.intervals.single { it.key.raw == "energyOnly" }
        assertEquals("moodOnly", unshared.startStateKey?.raw)
        assertEquals("energyOnly", unshared.endStateKey.raw)
        assertTrue(unshared.axisDeltas.measured().isEmpty())
    }

    @Test
    fun identicalOccurrencesAndCalendarDaysStayDistinctAndContained() {
        val zone = ZoneOffset.UTC
        val day = LocalDate.of(2026, 1, 15)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val evidence = projectBehavioralEvidence(
            dataset(
                states = listOf(
                    state("same-a", start + HOUR, mood = 4),
                    state("same-b", start + HOUR, mood = 4),
                    state("next", next + HOUR, mood = 6),
                ),
                intervals = listOf(
                    interval("same-b", "same-a", start + HOUR, start + 2 * HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                    interval("next", null, start + 23 * HOUR, next + HOUR, CheckInContextStartBasis.WAKE_TIME, completed = true),
                    interval(
                        "same-a",
                        null,
                        start + 3 * HOUR,
                        start + 4 * HOUR,
                        CheckInContextStartBasis.DAY_START,
                        completed = false,
                    ),
                ),
            ),
            zone,
        )
        val first = evidence.calendarDays.single { it.date == day }
        val second = evidence.calendarDays.single { it.date == day.plusDays(1) }
        assertEquals(listOf("same-a", "same-b"), first.stateOccurrenceIds.map { it.raw })
        assertTrue(first.completedExposureKeys.map { it.raw }.contains("same-b"))
        assertFalse(first.completedExposureKeys.map { it.raw }.contains("next"))
        assertFalse(second.completedExposureKeys.map { it.raw }.contains("next"))
        assertTrue(first.overlappingKeys.map { it.raw }.contains("next"))
        assertTrue(second.overlappingKeys.map { it.raw }.contains("next"))
        assertEquals(listOf("same-a"), first.incompleteCoverageKeys.map { it.raw })
        assertFalse(first.completedExposureKeys.map { it.raw }.contains("same-a"))
    }

    @Test
    fun reversedInputProducesTheSameEvidence() {
        val forward = namedDataset()
        val reversed = forward.copy(
            states = forward.states.reversed(),
            intervals = forward.intervals.reversed().map { interval ->
                interval.copy(
                    activity = interval.activity.copy(activities = interval.activity.activities.reversed()),
                    workout = interval.workout.copy(workouts = interval.workout.workouts.reversed()),
                    diet = interval.diet.copy(meals = interval.diet.meals.reversed()),
                    usage = interval.usage.copy(usage = interval.usage.usage.reversed()),
                )
            },
        )
        assertEquals(
            projectBehavioralEvidence(forward, ZoneOffset.UTC),
            projectBehavioralEvidence(reversed, ZoneOffset.UTC),
        )
    }

    @Test
    fun evidenceDoesNotWriteRoomOrCarryProjectionClaims() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/BehavioralEvidence.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/BehavioralEvidence.kt",
        ).readText()
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains(".insert"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains("CausalMultiscaleInput"))
        assertFalse(source.contains("fitRetrospective"))
        assertFalse(source.contains("explainedVariance"))
        assertFalse(source.contains("recommendation"))
        val fields = IntervalBehaviorEvidence::class.java.declaredFields.map { it.name }
        listOf("coordinate", "rank", "recommend", "loading", "insight").forEach { piece ->
            assertTrue(fields.none { it.contains(piece, ignoreCase = true) })
        }
        val evidence = projectBehavioralEvidence(namedDataset(), ZoneOffset.UTC)
        assertEquals(BEHAVIOR_CATALOG_SCOPE, evidence.catalogScope)
        assertEquals(forwardIds(namedDataset()), forwardIds(namedDataset()))
    }

    private fun usage(evidence: BehavioralEvidenceDataset, key: String): UsageBehavior {
        return evidence.intervals
            .flatMap { it.exposures }
            .filterIsInstance<UsageBehavior>()
            .single { it.identity.key == key }
    }

    private fun namedDataset(): CheckInAnalysisDatasetV2 {
        val at = 10L * DAY
        return dataset(
            states = listOf(state("end", at, mood = 5)),
            intervals = listOf(
                interval(
                    end = "end",
                    previous = null,
                    start = at - 2 * HOUR,
                    endAt = at,
                    basis = CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(ActivityObservationV2("act", null, "Walk", at)),
                    workout = CategoryObservationKindV2.ENTRIES,
                    workouts = listOf(WorkoutObservationV2("wo", null, "Walk", 30, at)),
                    diet = CategoryObservationKindV2.ENTRIES,
                    meals = listOf(meal("large", FoodObservationV2("food", null, "Walk", 0))),
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(
                        usageItem("tea", SubstanceIntent.SUPPORTIVE.name, "1"),
                        usageItem("tea", SubstanceIntent.COPING.name, "1"),
                    ),
                ),
            ),
        )
    }

    private fun amountDataset(): CheckInAnalysisDatasetV2 {
        val at = 12L * DAY
        return dataset(
            states = listOf(state("end", at, mood = 5)),
            intervals = listOf(
                interval(
                    end = "end",
                    previous = null,
                    start = at - 2 * HOUR,
                    endAt = at,
                    basis = CheckInContextStartBasis.WAKE_TIME,
                    duration = 2 * HOUR,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(
                        usageItem("tea", SubstanceIntent.SUPPORTIVE.name, ""),
                        usageItem("tea", SubstanceIntent.SUPPORTIVE.name, "nope"),
                        usageItem("water", SubstanceIntent.SUPPORTIVE.name, "0"),
                        usageItem("caffeine", SubstanceIntent.COPING.name, "2 mg"),
                        usageItem("caffeine", SubstanceIntent.COPING.name, "4"),
                    ),
                ),
            ),
        )
    }

    private fun forwardIds(dataset: CheckInAnalysisDatasetV2) = dataset.states.map { it.checkInId }

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
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val moodReading = reading(DefaultCheckInAxes.MOOD, mood)
        val energyReading = reading(DefaultCheckInAxes.ENERGY, energy)
        val confidenceReading = reading(DefaultCheckInAxes.CONFIDENCE, confidence)
        val anxietyReading = reading(DefaultCheckInAxes.ANXIETY, anxiety)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = listOfNotNull(moodReading, energyReading, confidenceReading, anxietyReading).associateBy { it.axisId },
            core = CoreAxisViewV2(moodReading, energyReading, confidenceReading, anxietyReading),
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        completed: Boolean = true,
        duration: Long = endAt - start,
        activity: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        activities: List<ActivityObservationV2> = emptyList(),
        workout: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        workouts: List<WorkoutObservationV2> = emptyList(),
        diet: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        meals: List<MealObservationV2> = emptyList(),
        usage: CategoryObservationKindV2 = CategoryObservationKindV2.NONE,
        usageItems: List<UsageObservationV2> = emptyList(),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = duration,
        startBasis = basis,
        completedAt = if (completed) endAt else null,
        activity = ActivityCategoryObservationV2(activity, if (completed) endAt else null, activities),
        workout = WorkoutCategoryObservationV2(workout, if (completed) endAt else null, workouts),
        diet = DietCategoryObservationV2(diet, if (completed) endAt else null, meals),
        usage = UsageCategoryObservationV2(usage, if (completed) endAt else null, usageItems),
    )

    private fun meal(size: String, vararg foods: FoodObservationV2) = MealObservationV2(
        id = "meal-$size-${foods.firstOrNull()?.id ?: "empty"}",
        size = size,
        sortOrder = 0,
        reportedAt = 0L,
        foods = foods.toList(),
    )

    private fun usageItem(optionId: String, intent: String, amount: String) = UsageObservationV2(
        id = "$optionId-$intent-$amount",
        optionId = optionId,
        name = optionId,
        intent = intent,
        amountText = amount,
        reportedAt = 0L,
    )

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }
}
