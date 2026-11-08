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

class BehaviorAssociationTest {
    @Test
    fun reviewedPresenceAndAbsenceStayDistinctFromUnknown() {
        val associations = associations(lensDataset())
        val shower = associations.profile(BehaviorKind.ACTIVITY, "option:opt-shower")
        val run = associations.profile(BehaviorKind.ACTIVITY, "option:opt-run")
        assertEquals(BehaviorIntervalStatus.PRESENT, shower.status("present"))
        assertEquals(2, shower.intervals.single { it.key.raw == "present" }.occurrenceCount)
        assertEquals(1, shower.support.presentKeys.size)
        assertEquals(2, shower.support.presentOccurrences)
        assertEquals(setOf("absent-one", "absent-two"), shower.support.absentKeys.map { it.raw }.toSet())
        assertEquals(BehaviorIntervalStatus.UNKNOWN, shower.status("unreviewed"))
        assertFalse(shower.support.absentKeys.any { it.raw == "unreviewed" })
        assertEquals(BehaviorIntervalStatus.ABSENT, run.status("present"))
        assertEquals(BehaviorIntervalStatus.PRESENT, run.status("absent-one"))
        assertEquals(BehaviorIntervalStatus.ABSENT, run.status("absent-two"))
        assertEquals(BehaviorIntervalStatus.UNKNOWN, run.status("unreviewed"))
        assertTrue(associations.profiles.any { it.identity.key == "option:opt-hidden" })
        assertTrue(associations.profile(BehaviorKind.ACTIVITY, "option:opt-hidden").support.presentKeys.isEmpty())
        assertEquals(listOf("option:opt-hidden", "option:opt-run", "option:opt-shower"), associations.profiles.filter { it.identity.kind == BehaviorKind.ACTIVITY }.map { it.identity.key })
    }

    @Test
    fun exclusionLensesAndContrastsStaySeparate() {
        val shower = associations(lensDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-shower")
        assertEquals(BehaviorIntervalStatus.EXCLUDED, shower.status("incomplete"))
        assertEquals(BehaviorIntervalStatus.EXCLUDED, shower.status("inconsistent"))
        assertEquals(BehaviorIntervalStatus.EXCLUDED, shower.status("reversed"))
        val mood = shower.axis(DefaultCheckInAxes.MOOD)
        assertEquals(listOf("present"), mood.present.endingKeys.map { it.raw })
        assertEquals(0.0, mood.present.starting!!.mean, 0.0)
        assertEquals(10.0, mood.present.ending!!.mean, 0.0)
        assertEquals(10.0, mood.present.delta!!.mean, 0.0)
        assertEquals(3.0, mood.absent.starting!!.mean, 0.0)
        val absentEnding = checkNotNull(mood.absent.ending)
        assertEquals(2.0, absentEnding.mean, 0.0)
        assertEquals(1.0, absentEnding.deviation, 1e-9)
        assertEquals(-1.0, mood.absent.delta!!.mean, 0.0)
        assertEquals(8.0, mood.endingContrast!!.difference, 0.0)
        assertEquals(1, mood.endingContrast.presentCount)
        assertEquals(2, mood.endingContrast.absentCount)
        assertEquals(-3.0, mood.startingContrast!!.difference, 0.0)
        assertEquals(11.0, mood.deltaContrast!!.difference, 0.0)
        val anxiety = shower.axis(DefaultCheckInAxes.ANXIETY)
        assertEquals(6.0, anxiety.present.delta!!.mean, 0.0)
        assertNull(anxiety.deltaContrast)
        val energy = shower.axis(DefaultCheckInAxes.ENERGY)
        val presentEnergy = checkNotNull(energy.present.ending)
        assertEquals(0.0, presentEnergy.mean, 0.0)
        assertEquals(0.0, presentEnergy.minimum, 0.0)
        assertNull(energy.present.starting)
        assertNull(energy.present.delta)
    }

    @Test
    fun aBoundaryCanEndAStateWithoutStartingOrChangingOne() {
        val shower = associations(boundaryDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-shower")
        val mood = shower.axis(DefaultCheckInAxes.MOOD)
        assertEquals(listOf("wake"), mood.present.endingKeys.map { it.raw })
        val ending = checkNotNull(mood.present.ending)
        assertEquals(8.0, ending.mean, 0.0)
        assertEquals(0.0, ending.deviation, 0.0)
        assertTrue(mood.present.startingKeys.isEmpty())
        assertTrue(mood.present.deltaKeys.isEmpty())
        assertNull(mood.endingContrast)
    }

    @Test
    fun anEmptySummaryIsUnavailableAndOneObservationHasNoSpread() {
        assertNull(populationSummary(emptyList()))
        val single = populationSummary(listOf(0.0))
        assertEquals(1, single!!.count)
        assertEquals(0.0, single.mean, 0.0)
        assertEquals(0.0, single.deviation, 0.0)
        assertEquals(0.0, single.minimum, 0.0)
        assertEquals(5.0, populationSummary(listOf(0.0, 10.0))!!.deviation, 1e-9)
    }

    @Test
    fun quantityStaysBesidePresenceAndUnitsStayApart() {
        val associations = associations(quantityDataset())
        val activity = associations.profile(BehaviorKind.ACTIVITY, "name:walk")
        val workout = associations.profile(BehaviorKind.WORKOUT, "name:walk")
        assertEquals(2.0, activity.axis(DefaultCheckInAxes.MOOD).present.ending!!.mean, 0.0)
        assertEquals(8.0, workout.axis(DefaultCheckInAxes.MOOD).present.ending!!.mean, 0.0)
        val minutes = workout.quantity as BehaviorQuantityCoverage.WorkoutMinutes
        assertEquals(1, minutes.intervalCount)
        assertEquals(2, minutes.sessionCount)
        assertEquals(30.0, minutes.minutes!!.mean, 0.0)
        assertTrue(activity.quantity is BehaviorQuantityCoverage.PresenceOnly)
        assertTrue(associations.profile(BehaviorKind.MEAL_SIZE, "name:large").quantity is BehaviorQuantityCoverage.PresenceOnly)
        assertTrue(associations.profile(BehaviorKind.FOOD, "option:oats").quantity is BehaviorQuantityCoverage.PresenceOnly)
        val supportive = associations.profile(BehaviorKind.USAGE, "option:tea", SubstanceIntent.SUPPORTIVE.name)
        val coping = associations.profile(BehaviorKind.USAGE, "option:tea", SubstanceIntent.COPING.name)
        assertEquals(1.0, supportive.axis(DefaultCheckInAxes.MOOD).present.ending!!.mean, 0.0)
        assertEquals(9.0, coping.axis(DefaultCheckInAxes.MOOD).present.ending!!.mean, 0.0)
        val caffeine = associations.profile(BehaviorKind.USAGE, "option:caffeine", SubstanceIntent.COPING.name)
        val amounts = caffeine.quantity as BehaviorQuantityCoverage.UsageAmounts
        assertEquals(2, amounts.parsedIntervalCount)
        assertEquals(1, amounts.missingOrUnparsedIntervalCount)
        assertTrue(caffeine.support.presentKeys.map { it.raw }.contains("malformed"))
        val unitless = amounts.units.single { it.unit == null }
        val milligrams = amounts.units.single { it.unit == "mg" }
        assertEquals(5.0, unitless.totalAmount!!.mean, 0.0)
        assertEquals(2.0, unitless.perHour!!.mean, 0.0)
        assertEquals(1, unitless.perHour.count)
        assertEquals(2.0, milligrams.totalAmount!!.mean, 0.0)
        assertNull(milligrams.perHour)
    }

    @Test
    fun calendarDaysAverageInsideTheDayAndDoNotInventAbsence() {
        val days = associations(calendarDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-shower").calendarDays
        assertEquals(listOf(LocalDate.of(2026, 1, 15)), days.presentDates)
        assertEquals(listOf(LocalDate.of(2026, 1, 16)), days.absentDates)
        assertTrue(days.unknownDates.contains(LocalDate.of(2026, 1, 17)))
        assertTrue(days.unknownDates.contains(LocalDate.of(2026, 1, 19)))
        assertFalse(days.absentDates.contains(LocalDate.of(2026, 1, 17)))
        assertFalse(days.absentDates.contains(LocalDate.of(2026, 1, 19)))
        val mood = days.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(1, mood.present!!.count)
        assertEquals(5.0, mood.present.mean, 0.0)
        assertEquals(1, mood.absent!!.count)
        assertEquals(4.0, mood.absent.mean, 0.0)
        assertEquals(1.0, mood.contrast!!.difference, 0.0)
        assertEquals(1, mood.contrast.presentCount)
        assertEquals(1, mood.contrast.absentCount)
        assertNull(days.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.present)
    }

    @Test
    fun aConflictingCategoryStaysUnknownAndDoesNotBecomeAbsence() {
        val food = associations(conflictingDataset()).profile(BehaviorKind.FOOD, "option:oats")
        assertEquals(BehaviorIntervalStatus.UNKNOWN, food.status("conflict"))
        assertFalse(food.support.absentKeys.any { it.raw == "conflict" })
        assertEquals(BehaviorIntervalStatus.EXCLUDED, associations(conflictingDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-shower").status("conflict"))
    }

    @Test
    fun reversedEvidenceProducesTheSameProfiles() {
        val forward = projectBehavioralEvidence(lensDataset(), ZoneOffset.UTC)
        val reversedDataset = lensDataset().let { dataset ->
            dataset.copy(
                states = dataset.states.reversed(),
                intervals = dataset.intervals.reversed(),
            )
        }
        val reversedEvidence = forward.copy(
            catalog = forward.catalog.reversed(),
            intervals = forward.intervals.reversed(),
            calendarDays = forward.calendarDays.reversed(),
        )
        val profiles = projectBehaviorAssociations(forward)
        assertEquals(profiles, projectBehaviorAssociations(projectBehavioralEvidence(reversedDataset, ZoneOffset.UTC)))
        assertEquals(profiles, projectBehaviorAssociations(reversedEvidence))
    }

    @Test
    fun emotionCountsStayCategoricalAndOrdered() {
        val shower = associations(emotionDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-shower")
        val presentStart = shower.emotions.presentStarting
        assertEquals(1, presentStart.eligibleCount)
        assertEquals(1, presentStart.recordedCount)
        assertEquals(0, presentStart.missingCount)
        assertEquals(listOf("Calm" to 1), presentStart.tokens.map { it.token to it.count })
        val presentEnd = shower.emotions.presentEnding
        assertEquals(2, presentEnd.eligibleCount)
        assertEquals(listOf("Bright" to 1, "Steady" to 1), presentEnd.tokens.map { it.token to it.count })
        val absentStart = shower.emotions.absentStarting
        assertEquals(3, absentStart.eligibleCount)
        assertEquals(1, absentStart.recordedCount)
        assertEquals(2, absentStart.missingCount)
        assertEquals(listOf("Bright"), absentStart.tokens.map { it.token })
        val absentEnd = shower.emotions.absentEnding
        assertEquals(3, absentEnd.eligibleCount)
        assertEquals(3, absentEnd.recordedCount)
        assertEquals(0, absentEnd.missingCount)
        assertEquals(listOf("Able" to 1, "Calm" to 2), absentEnd.tokens.map { it.token to it.count })
        val tokens = listOf(presentStart, presentEnd, absentStart, absentEnd).flatMap { side ->
            side.tokens.map { it.token }
        }
        assertFalse(tokens.contains("Secret"))
        assertFalse(tokens.contains("ShouldNotStart"))
    }

    @Test
    fun theContractDeclinesPredictionRankingAndRoom() {
        val associations = associations(boundaryDataset())
        assertEquals("behavior-association-v2", BEHAVIOR_ASSOCIATION_PROTOCOL)
        assertEquals(BEHAVIOR_ASSOCIATION_PROTOCOL, associations.protocol)
        assertTrue(associations.retrospective && associations.descriptive)
        assertTrue(associations.withinIndividualHistory && associations.datasetRelative)
        assertFalse(associations.causal)
        assertFalse(associations.predictive)
        assertFalse(associations.suitableForRecommendation)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/BehaviorAssociation.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/BehaviorAssociation.kt",
        ).readText()
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains("fitRetrospective"))
        assertFalse(source.contains("explainedVariance"))
        assertFalse(source.contains("pValue"))
        assertFalse(source.contains("significant"))
        val fields = BehaviorAssociationProfile::class.java.declaredFields.map { it.name }
        listOf("coordinate", "rank", "loading", "pvalue").forEach { piece ->
            assertTrue(fields.none { it.contains(piece, ignoreCase = true) })
        }
    }

    private fun associations(dataset: CheckInAnalysisDatasetV2) = projectBehaviorAssociations(
        projectBehavioralEvidence(dataset, ZoneOffset.UTC),
    )

    private fun BehaviorAssociationDataset.profile(
        kind: BehaviorKind,
        key: String,
        intent: String? = null,
    ) = profiles.single { it.identity.kind == kind && it.identity.key == key && it.identity.intent == intent }

    private fun BehaviorAssociationProfile.axis(axis: String) = axes.single { it.axisId == axis }

    private fun BehaviorAssociationProfile.status(key: String) = intervals.single { it.key.raw == key }.status

    private fun lensDataset(): CheckInAnalysisDatasetV2 {
        val start = 20L * DAY
        return dataset(
            states = listOf(
                state("p-start", start, mood = 0, anxiety = 2),
                state("present", start + 2 * HOUR, mood = 10, energy = 0, anxiety = 8),
                state("a1-start", start + 3 * HOUR, mood = 1),
                state("absent-one", start + 4 * HOUR, mood = 1),
                state("a2-start", start + 5 * HOUR, mood = 5),
                state("absent-two", start + 6 * HOUR, mood = 3),
                state("unreviewed", start + 8 * HOUR, mood = 4),
                state("incomplete", start + 9 * HOUR, mood = 9),
                state("inconsistent", start + 10 * HOUR, mood = 9),
                state("future", start + 12 * HOUR, mood = 7),
                state("reversed", start + 11 * HOUR, mood = 1),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    start,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("s1", "opt-shower"), activity("s2", "opt-shower")),
                ),
                interval(
                    "absent-one",
                    "a1-start",
                    start + 3 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("run", "opt-run")),
                ),
                interval(
                    "absent-two",
                    "a2-start",
                    start + 5 * HOUR,
                    start + 6 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
                interval(
                    "unreviewed",
                    null,
                    start + 7 * HOUR,
                    start + 8 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.UNREVIEWED,
                ),
                interval(
                    "incomplete",
                    null,
                    start + 8 * HOUR,
                    start + 9 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    completed = false,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("s3", "opt-shower")),
                ),
                interval(
                    "inconsistent",
                    null,
                    start + 9 * HOUR,
                    start + 10 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("hidden", "opt-hidden"), activity("s4", "opt-shower")),
                ),
                interval(
                    "reversed",
                    "future",
                    start + 11 * HOUR,
                    start + 11 * HOUR + HOUR / 2,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("s5", "opt-shower")),
                ),
            ),
            issues = listOf(
                AnalysisIssue(
                    code = AnalysisIssueCode.WINDOW_END_DISAGREES_WITH_CHECK_IN,
                    checkInId = "inconsistent",
                    detail = "window end disagrees",
                ),
            ),
        )
    }

    private fun boundaryDataset() = dataset(
        states = listOf(state("wake", HOUR, mood = 8)),
        intervals = listOf(
            interval(
                "wake",
                null,
                0L,
                HOUR,
                CheckInContextStartBasis.WAKE_TIME,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("s", "opt-shower")),
            ),
        ),
    )

    private fun quantityDataset(): CheckInAnalysisDatasetV2 {
        val start = 30L * DAY
        return dataset(
            states = listOf(
                state("walk-activity", start, mood = 2),
                state("walk-workout", start + HOUR, mood = 8),
                state("meal", start + 2 * HOUR, mood = 3),
                state("supportive", start + 4 * HOUR, mood = 1),
                state("coping", start + 6 * HOUR, mood = 9),
                state("parsed", start + 8 * HOUR, mood = 4),
                state("malformed", start + 10 * HOUR, mood = 4),
                state("unitless", start + 12 * HOUR, mood = 4),
            ),
            intervals = listOf(
                interval(
                    "walk-activity",
                    null,
                    start - HOUR,
                    start,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(ActivityObservationV2("walk", null, "Walk", start)),
                ),
                interval(
                    "walk-workout",
                    null,
                    start,
                    start + HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    workout = CategoryObservationKindV2.ENTRIES,
                    workouts = listOf(
                        WorkoutObservationV2("w1", null, "Walk", 20, start),
                        WorkoutObservationV2("w2", null, "Walk", 40, start),
                    ),
                ),
                interval(
                    "meal",
                    null,
                    start + HOUR,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    diet = CategoryObservationKindV2.ENTRIES,
                    meals = listOf(meal("large", FoodObservationV2("oats", "oats", "Oats", 0))),
                ),
                interval(
                    "supportive",
                    null,
                    start + 2 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 2 * HOUR,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("tea", SubstanceIntent.SUPPORTIVE.name, "1")),
                ),
                interval(
                    "coping",
                    null,
                    start + 4 * HOUR,
                    start + 6 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("tea", SubstanceIntent.COPING.name, "1")),
                ),
                interval(
                    "parsed",
                    null,
                    start + 6 * HOUR,
                    start + 8 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 2 * HOUR,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(
                        usageItem("caffeine", SubstanceIntent.COPING.name, "2 mg"),
                        usageItem("caffeine", SubstanceIntent.COPING.name, "4"),
                    ),
                ),
                interval(
                    "malformed",
                    null,
                    start + 8 * HOUR,
                    start + 10 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("caffeine", SubstanceIntent.COPING.name, "nope")),
                ),
                interval(
                    "unitless",
                    null,
                    start + 12 * HOUR,
                    start + 12 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    duration = 0L,
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("caffeine", SubstanceIntent.COPING.name, "6")),
                ),
            ),
        )
    }

    private fun calendarDataset(): CheckInAnalysisDatasetV2 {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 1, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val second = LocalDate.of(2026, 1, 16).atStartOfDay(zone).toInstant().toEpochMilli()
        val third = LocalDate.of(2026, 1, 17).atStartOfDay(zone).toInstant().toEpochMilli()
        val fourth = LocalDate.of(2026, 1, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        val fifth = LocalDate.of(2026, 1, 19).atStartOfDay(zone).toInstant().toEpochMilli()
        return dataset(
            states = listOf(
                state("early", first + 10 * HOUR, mood = 0),
                state("later", first + 14 * HOUR, mood = 10),
                state("quiet", second + 10 * HOUR, mood = 4),
                state("crossed-end", fourth + HOUR, mood = 3),
                state("overlap-day", third + 10 * HOUR, mood = 3),
                state("open", fifth + 10 * HOUR, mood = 2),
                state("open-end", fifth + 12 * HOUR, mood = 2),
            ),
            intervals = listOf(
                interval(
                    "later",
                    "early",
                    first + 10 * HOUR,
                    first + 14 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval("early", null, first + 8 * HOUR, first + 10 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval("quiet", null, second + 8 * HOUR, second + 10 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval("overlap-day", null, third + 8 * HOUR, third + 10 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval(
                    "crossed-end",
                    null,
                    third + 23 * HOUR,
                    fourth + HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                ),
                interval("open", null, fifth + 8 * HOUR, fifth + 10 * HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval(
                    "open-end",
                    null,
                    fifth + 11 * HOUR,
                    fifth + 12 * HOUR,
                    CheckInContextStartBasis.DAY_START,
                    completed = false,
                ),
            ),
        )
    }

    private fun conflictingDataset() = dataset(
        states = listOf(
            state("listed", HOUR, mood = 2),
            state("conflict", 2 * HOUR, mood = 2),
        ),
        intervals = listOf(
            interval(
                "listed",
                null,
                0L,
                HOUR,
                CheckInContextStartBasis.WAKE_TIME,
                diet = CategoryObservationKindV2.ENTRIES,
                meals = listOf(meal("large", FoodObservationV2("oats", "oats", "Oats", 0))),
            ),
            interval(
                "conflict",
                null,
                HOUR,
                2 * HOUR,
                CheckInContextStartBasis.WAKE_TIME,
                activity = CategoryObservationKindV2.ENTRIES,
                activities = listOf(activity("s", "opt-shower")),
                diet = CategoryObservationKindV2.CONFLICTING,
                meals = listOf(meal("snack", FoodObservationV2("other", "oats", "Oats", 0))),
            ),
        ),
    )

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
        issues: List<AnalysisIssue> = emptyList(),
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), issues)

    private fun state(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        confidence: Int? = null,
        anxiety: Int? = null,
        emotion: String = "",
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.CONFIDENCE to confidence,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.CONFIDENCE },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
            emotion = emotion,
        )
    }

    private fun emotionDataset(): CheckInAnalysisDatasetV2 {
        val start = 50L * DAY
        return dataset(
            states = listOf(
                state("p-start", start, mood = 1, emotion = "Calm"),
                state("present", start + 2 * HOUR, mood = 2, emotion = "Bright"),
                state("leak", start + 3 * HOUR, mood = 1, emotion = "ShouldNotStart"),
                state("wake", start + 4 * HOUR, mood = 2, emotion = "Steady"),
                state("a1-start", start + 5 * HOUR, mood = 1, emotion = ""),
                state("absent-one", start + 6 * HOUR, mood = 1, emotion = "Calm"),
                state("a2-start", start + 7 * HOUR, mood = 1, emotion = "Bright"),
                state("absent-two", start + 8 * HOUR, mood = 1, emotion = "Able"),
                state("a3-start", start + 9 * HOUR, mood = 1, emotion = ""),
                state("absent-three", start + 10 * HOUR, mood = 1, emotion = "Calm"),
                state("secret", start + 12 * HOUR, mood = 1, emotion = "Secret"),
            ),
            intervals = listOf(
                interval(
                    "present",
                    "p-start",
                    start,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("shower", "opt-shower")),
                ),
                interval(
                    "wake",
                    "leak",
                    start + 3 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.ENTRIES,
                    activities = listOf(activity("wake-shower", "opt-shower")),
                ),
                interval(
                    "absent-one",
                    "a1-start",
                    start + 5 * HOUR,
                    start + 6 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
                interval(
                    "absent-two",
                    "a2-start",
                    start + 7 * HOUR,
                    start + 8 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
                interval(
                    "absent-three",
                    "a3-start",
                    start + 9 * HOUR,
                    start + 10 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                ),
                interval(
                    "secret",
                    null,
                    start + 11 * HOUR,
                    start + 12 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                    activity = CategoryObservationKindV2.UNREVIEWED,
                ),
            ),
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

    private fun activity(id: String, optionId: String) = ActivityObservationV2(id, optionId, optionId, 0L)

    private fun meal(size: String, vararg foods: FoodObservationV2) = MealObservationV2(
        id = "meal-$size",
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
