package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.ui.insights.BehaviorAssociationCopy
import com.orbit.wellness.ui.insights.projectBehaviorResearch
import com.orbit.wellness.ui.insights.toBehaviorAssociationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

class BehaviorExposureEpisodeTest {
    @Test
    fun consecutivePresentIntervalsFormOneEpisode() {
        val profile = episodes(chainDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        val episode = profile.episodes.single()
        assertEquals(listOf("s1", "s2"), episode.memberKeys.map { it.raw })
        assertEquals(listOf("s1"), episode.rootKeys.map { it.raw })
        assertEquals(listOf("s2"), episode.terminalKeys.map { it.raw })
        assertEquals(2, episode.presentIntervalCount)
        assertEquals(2 * HOUR, episode.reportedWindowDurationMillis)
        assertTrue(episode.branchPoints.isEmpty())
        assertEquals(PresenceContrastAvailability.UNAVAILABLE, profile.presenceContrast)
        val first = episode.occurrences.first()
        val second = episode.occurrences.last()
        assertEquals(0, first.pathPosition)
        assertEquals(0, first.priorPresentIntervalCount)
        assertEquals(1, first.inclusivePresentIntervalCount)
        assertEquals(0L, first.priorReportedWindowMillis)
        assertEquals(HOUR, first.inclusiveReportedWindowMillis)
        assertEquals(0L, first.elapsedSinceRootMillis)
        assertEquals(1, second.pathPosition)
        assertEquals(1, second.priorPresentIntervalCount)
        assertEquals(2, second.inclusivePresentIntervalCount)
        assertEquals(HOUR, second.priorReportedWindowMillis)
        assertEquals(2 * HOUR, second.inclusiveReportedWindowMillis)
        assertEquals(HOUR, second.elapsedSinceRootMillis)
        assertEquals(3, first.axisDeltas.mood)
        assertNull(first.axisDeltas.energy)
        assertEquals(6, first.axisDeltas.anxiety)
        assertEquals("Calm", first.startEmotion?.name)
        assertEquals("Bright", first.endEmotion?.name)
        assertNull(second.endEmotion)
        assertEquals(PastOnlyHistoryStatus.AVAILABLE, second.pastOnly.status)
        assertEquals(1, second.pastOnly.priorPresentIntervalCount)
        val content = projectBehaviorResearch(chainDataset(), ZoneOffset.UTC)
        val view = content.associations.toBehaviorAssociationContent(content.balance, content.episodes)
        assertEquals(
            BehaviorAssociationCopy.PRESENCE_CONTRAST_UNAVAILABLE,
            view.profiles.single().episodes.presenceContrastNote,
        )
        assertTrue(view.profiles.single().episodes.episodes.single().trajectory.last().contains("prior 1"))
    }

    @Test
    fun reviewedAbsenceSplitsEpisodes() {
        val profile = episodes(splitDataset(CategoryObservationKindV2.NONE)).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(listOf(listOf("s1"), listOf("s3")), profile.episodes.map { it.memberKeys.map { key -> key.raw } })
        assertEquals(PresenceContrastAvailability.AVAILABLE, profile.presenceContrast)
        val first = profile.episodes.first()
        val second = profile.episodes.last()
        assertEquals(ExposureBreakReason.REVIEWED_ABSENCE, first.breaks.single().reasons.single())
        assertEquals(ExposureBreakSide.FOLLOWING, first.breaks.single().side)
        assertEquals(ExposureBreakReason.REVIEWED_ABSENCE, second.breaks.single().reasons.single())
        assertEquals(0, second.occurrences.single().priorPresentIntervalCount)
        assertEquals(PastOnlyHistoryStatus.AVAILABLE, profile.pastOnly.single { it.transitionKey.raw == "s3" }.status)
    }

    @Test
    fun unknownEvidenceBreaksWithoutBecomingAbsence() {
        val unreviewed = episodes(splitDataset(CategoryObservationKindV2.UNREVIEWED))
            .profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(ExposureBreakReason.UNKNOWN_EVIDENCE, unreviewed.episodes.first().breaks.single().reasons.single())
        assertFalse(unreviewed.episodes.any { episode -> episode.memberKeys.any { it.raw == "s2" } })
        val later = unreviewed.pastOnly.single { it.transitionKey.raw == "s3" }
        assertEquals(PastOnlyHistoryStatus.UNAVAILABLE, later.status)
        assertEquals(PastOnlyGap.UNKNOWN_PRECEDING, later.gap)
        assertNull(later.priorPresentIntervalCount)
        val conflicting = episodes(splitDataset(CategoryObservationKindV2.CONFLICTING))
            .profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(ExposureBreakReason.UNKNOWN_EVIDENCE, conflicting.episodes.last().breaks.single().reasons.single())
        assertEquals(PresenceContrastAvailability.UNAVAILABLE, conflicting.presenceContrast)
    }

    @Test
    fun incompleteAndInvalidIntervalsAreExcludedAndDiagnosed() {
        val profile = episodes(excludedDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(setOf("m", "after", "after-rev"), profile.episodes.flatMap { it.memberKeys }.map { it.raw }.toSet())
        val root = profile.episodes.single { it.rootKeys.single().raw == "m" }
        assertEquals(listOf(ExposureBreakReason.INCOMPLETE), root.breaks.single().reasons)
        val invalid = profile.episodes.single { it.rootKeys.single().raw == "after" }
        assertEquals(listOf(ExposureBreakReason.INVALID_CHRONOLOGY), invalid.breaks.single().reasons)
        assertEquals(PastOnlyGap.EXCLUDED_PRECEDING, invalid.occurrences.single().pastOnly.gap)
        val reversed = profile.episodes.single { it.rootKeys.single().raw == "after-rev" }
        assertEquals(listOf(ExposureBreakReason.REVERSED_WINDOW), reversed.breaks.single().reasons)
        val unresolved = episodes(unresolvedDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        val episode = unresolved.episodes.single()
        assertEquals(EpisodeDiagnosticKind.UNRESOLVED_ROOT, episode.diagnostics.single().kind)
        assertNull(episode.occurrences.single().startStateKey)
        assertNull(episode.occurrences.single().priorPresentIntervalCount)
        assertEquals(PastOnlyGap.UNRESOLVED_LINK, episode.occurrences.single().pastOnly.gap)
    }

    @Test
    fun aBoundaryBeginsAnEpisodeWithoutAnInventedStart() {
        val profile = episodes(boundaryDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(listOf(listOf("leak"), listOf("wake", "next"), listOf("day")), profile.episodes.map { it.memberKeys.map { key -> key.raw } })
        val wakeEpisode = profile.episodes.single { it.rootKeys.single().raw == "wake" }
        val wake = wakeEpisode.occurrences.first()
        assertNull(wake.startStateKey)
        assertNull(wake.startAxes)
        assertNull(wake.startEmotion)
        assertNull(wake.axisDeltas.mood)
        assertEquals("Steady", wake.endEmotion?.name)
        assertTrue(wake.predecessorKeys.isEmpty())
        assertEquals(listOf("wake"), wakeEpisode.occurrences.last().predecessorKeys.map { it.raw })
        assertEquals(EpisodeDiagnosticKind.BOUNDARY_ROOT, wakeEpisode.diagnostics.first().kind)
        val day = profile.episodes.single { it.rootKeys.single().raw == "day" }.occurrences.single()
        assertNull(day.startStateKey)
        assertEquals(0, day.priorPresentIntervalCount)
    }

    @Test
    fun branchingRetainsTheSharedPrefixWithoutDoubleCounting() {
        val profile = episodes(branchDataset(endMood = 1)).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        val episode = profile.episodes.single()
        assertEquals(listOf("b", "c", "d", "e"), episode.memberKeys.map { it.raw })
        assertEquals(4, episode.presentIntervalCount)
        assertEquals(6 * HOUR, episode.reportedWindowDurationMillis)
        assertEquals(listOf("c", "d"), episode.branchPoints.single().outgoingKeys.map { it.raw })
        val left = episode.occurrences.single { it.key.raw == "c" }
        val right = episode.occurrences.single { it.key.raw == "d" }
        val later = episode.occurrences.single { it.key.raw == "e" }
        assertEquals(1, left.priorPresentIntervalCount)
        assertEquals(1, right.priorPresentIntervalCount)
        assertEquals(left.pastOnly.priorReportedWindowMillis, right.pastOnly.priorReportedWindowMillis)
        assertEquals(2, later.priorPresentIntervalCount)
        assertEquals(3, later.inclusivePresentIntervalCount)
        assertEquals(2 * HOUR, later.priorReportedWindowMillis)
        assertEquals(4 * HOUR, later.inclusiveReportedWindowMillis)
        assertEquals(ExposureBreakReason.UNKNOWN_EVIDENCE, episode.breaks.single().reasons.single())
        val changed = episodes(branchDataset(endMood = 9)).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(later.pastOnly, changed.episodes.single().occurrences.single { it.key.raw == "e" }.pastOnly)
        assertEquals(9, changed.episodes.single().occurrences.single { it.key.raw == "e" }.endAxes.mood)
    }

    @Test
    fun identicalOccurrencesStayDistinctAndOrderDoesNotMatter() {
        val forward = episodes(branchDataset(endMood = 1))
        val reversed = branchDataset(endMood = 1)
        val backward = episodes(
            reversed.copy(
                states = reversed.states.reversed(),
                intervals = reversed.intervals.reversed(),
            ),
        )
        assertEquals(forward, backward)
        val twins = episodes(twinDataset()).profile(BehaviorKind.ACTIVITY, "option:opt-coding")
        assertEquals(2, twins.episodes.flatMap { it.memberKeys }.size)
        assertEquals(setOf("one", "two"), twins.episodes.flatMap { it.memberKeys }.map { it.raw }.toSet())
    }

    @Test
    fun coExposuresStayOnTheIntervalWithoutAttribution() {
        val result = episodes(coExposureDataset())
        val coding = result.profile(BehaviorKind.ACTIVITY, "option:opt-coding").episodes.single()
        assertEquals(
            setOf("option:opt-shop", "name:run", "option:oats", "name:large", "option:tea"),
            coding.coExposures.map { it.identity.key }.toSet(),
        )
        assertTrue(coding.coExposures.all { it.intervalCount == 1 })
        assertEquals(setOf(SubstanceIntent.SUPPORTIVE.name), coding.coExposures.mapNotNull { it.identity.intent }.toSet())
        val shopping = result.profile(BehaviorKind.ACTIVITY, "option:opt-shop").episodes.single()
        assertEquals("option:opt-coding", shopping.coExposures.first { it.identity.kind == BehaviorKind.ACTIVITY }.identity.key)
        val fields = EpisodeCoExposure::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("cause") || it.contains("attribut") || it.contains("effect") })
    }

    @Test
    fun usageQuantitiesKeepPresenceWhenParsingIsIncomplete() {
        val coping = episodes(usageDataset()).profile(BehaviorKind.USAGE, "option:cig", SubstanceIntent.COPING.name)
        assertEquals(PresenceContrastAvailability.UNAVAILABLE, coping.presenceContrast)
        val episode = coping.episodes.single()
        assertEquals(listOf("u1", "u2", "u3", "bad", "blank"), episode.memberKeys.map { it.raw })
        val first = episode.occurrences.single { it.key.raw == "u1" }.usage!!
        assertEquals(2.0, first.currentTotals.single().total, 0.0)
        assertEquals("mg", first.currentTotals.single().unit)
        assertNull(first.currentUnitlessPerHour)
        val second = episode.occurrences.single { it.key.raw == "u2" }.usage!!
        assertEquals(4.0, second.inclusiveTotals!!.single { it.unit == null }.total, 0.0)
        assertEquals(2.0, second.inclusiveTotals.single { it.unit == "mg" }.total, 0.0)
        assertEquals(2.0, second.inclusiveUnitlessPerHour!!, 1e-9)
        assertNull(second.priorUnitlessPerHour)
        val zero = episode.occurrences.single { it.key.raw == "u3" }.usage!!
        assertEquals(0.0, zero.currentTotals.single().total, 0.0)
        assertTrue(zero.complete)
        assertEquals(4.0, zero.inclusiveTotals!!.single { it.unit == null }.total, 0.0)
        val malformed = episode.occurrences.single { it.key.raw == "bad" }.usage!!
        assertEquals(1, malformed.unparsedCount)
        assertFalse(malformed.complete)
        assertNull(malformed.inclusiveTotals)
        assertEquals(2.0, malformed.priorTotals!!.single { it.unit == "mg" }.total, 0.0)
        val blank = episode.occurrences.single { it.key.raw == "blank" }.usage!!
        assertEquals(1, blank.missingCount)
        assertNull(blank.priorTotals)
        assertNull(blank.inclusiveTotals)
        val supportive = episodes(usageDataset()).profile(BehaviorKind.USAGE, "option:cig", SubstanceIntent.SUPPORTIVE.name)
        assertEquals(listOf("u1"), supportive.episodes.single().memberKeys.map { it.raw })
        assertEquals(1.0, supportive.episodes.single().occurrences.single().usage!!.currentTotals.single().total, 0.0)
    }

    @Test
    fun pastOnlyHistoryExcludesTheCurrentIntervalAndLaterBranches() {
        val episode = episodes(branchDataset(endMood = 1)).profile(BehaviorKind.ACTIVITY, "option:opt-coding").episodes.single()
        val later = episode.occurrences.single { it.key.raw == "e" }
        assertEquals(2, later.pastOnly.priorPresentIntervalCount)
        assertEquals(2 * HOUR, later.pastOnly.priorReportedWindowMillis)
        assertNull(later.pastOnly.gap)
        val names = PastOnlyExposureHistory::class.java.declaredFields.map { it.name }
        assertTrue(names.none { it.contains("delta") || it.contains("inclusive") || it.contains("Emotion") || it.contains("Axes") })
        val sibling = episode.occurrences.single { it.key.raw == "d" }.pastOnly
        assertEquals(1, sibling.priorPresentIntervalCount)
        assertFalse(sibling.priorReportedWindowMillis == later.inclusiveReportedWindowMillis)
    }

    @Test
    fun mismatchedInputsAreRejected() {
        val chain = chainDataset()
        val evidence = projectBehavioralEvidence(chain, ZoneOffset.UTC)
        val associations = projectBehaviorAssociations(evidence)
        assertThrows(IllegalArgumentException::class.java) {
            projectBehaviorExposureEpisodes(evidence, projectAlternatingTemporalGraph(branchDataset(1)), associations)
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectBehaviorExposureEpisodes(
                evidence,
                projectAlternatingTemporalGraph(chain),
                associations.copy(profiles = associations.profiles.drop(1)),
            )
        }
        val result = episodes(chain)
        assertEquals(BEHAVIOR_EXPOSURE_EPISODE_PROTOCOL, result.protocol)
        assertEquals(REPORTED_WINDOW_LIMIT, result.reportedWindowLimit)
        assertFalse(result.causal || result.predictive || result.suitableForRecommendation)
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/BehaviorExposureEpisode.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/BehaviorExposureEpisode.kt",
        ).readText()
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains("projectStateMap"))
    }

    private fun episodes(dataset: CheckInAnalysisDatasetV2): BehaviorExposureEpisodeDataset {
        val graph = projectAlternatingTemporalGraph(dataset)
        val evidence = projectBehavioralEvidence(graph, dataset, ZoneOffset.UTC)
        return projectBehaviorExposureEpisodes(evidence, graph, projectBehaviorAssociations(evidence))
    }

    private fun BehaviorExposureEpisodeDataset.profile(
        kind: BehaviorKind,
        key: String,
        intent: String? = null,
    ) = profiles.single { it.identity.kind == kind && it.identity.key == key && it.identity.intent == intent }

    private fun chainDataset(): CheckInAnalysisDatasetV2 {
        val start = 80L * DAY
        return dataset(
            states = listOf(
                state("s0", start, mood = 1, anxiety = 2, emotion = "Calm"),
                state("s1", start + HOUR, mood = 4, energy = 5, anxiety = 8, emotion = "Bright"),
                state("s2", start + 2 * HOUR, mood = 7, emotion = ""),
            ),
            intervals = listOf(
                linked("s1", "s0", start, start + HOUR),
                linked("s2", "s1", start + HOUR, start + 2 * HOUR),
            ),
        )
    }

    private fun splitDataset(middle: CategoryObservationKindV2): CheckInAnalysisDatasetV2 {
        val start = 90L * DAY
        return dataset(
            states = listOf(
                state("s0", start, mood = 1),
                state("s1", start + HOUR, mood = 2),
                state("s2", start + 2 * HOUR, mood = 3),
                state("s3", start + 3 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("s1", "s0", start, start + HOUR),
                linked("s2", "s1", start + HOUR, start + 2 * HOUR, activity = middle),
                linked("s3", "s2", start + 2 * HOUR, start + 3 * HOUR),
            ),
        )
    }

    private fun excludedDataset(): CheckInAnalysisDatasetV2 {
        val start = 100L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("m", start + HOUR, mood = 2),
                state("open", start + 2 * HOUR, mood = 2),
                state("late", start + 6 * HOUR, mood = 2),
                state("early", start + 3 * HOUR, mood = 2),
                state("after", start + 4 * HOUR, mood = 3),
                state("rev", start + 5 * HOUR, mood = 3),
                state("after-rev", start + 7 * HOUR, mood = 4),
            ),
            intervals = listOf(
                linked("m", "a", start, start + HOUR),
                linked("open", "m", start + HOUR, start + 2 * HOUR, completed = false),
                linked("early", "late", start + 2 * HOUR, start + 3 * HOUR),
                linked("after", "early", start + 3 * HOUR, start + 4 * HOUR),
                interval(
                    "rev",
                    null,
                    start + 6 * HOUR,
                    start + 5 * HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                ),
                linked("after-rev", "rev", start + 5 * HOUR, start + 7 * HOUR),
            ),
        )
    }

    private fun unresolvedDataset(): CheckInAnalysisDatasetV2 {
        val start = 110L * DAY
        return dataset(
            states = listOf(state("solo", start + HOUR, mood = 1)),
            intervals = listOf(linked("solo", "ghost", start, start + HOUR)),
        )
    }

    private fun boundaryDataset(): CheckInAnalysisDatasetV2 {
        val start = 120L * DAY
        return dataset(
            states = listOf(
                state("before", start - HOUR, mood = 1),
                state("leak", start, mood = 9, emotion = "Leak"),
                state("wake", start + HOUR, mood = 3, emotion = "Steady"),
                state("next", start + 2 * HOUR, mood = 6, emotion = "On"),
                state("day", start + 4 * HOUR, mood = 2),
            ),
            intervals = listOf(
                linked("leak", "before", start - HOUR, start),
                interval(
                    "wake",
                    "leak",
                    start,
                    start + HOUR,
                    CheckInContextStartBasis.WAKE_TIME,
                ),
                linked("next", "wake", start + HOUR, start + 2 * HOUR),
                interval(
                    "day",
                    null,
                    start + 3 * HOUR,
                    start + 4 * HOUR,
                    CheckInContextStartBasis.DAY_START,
                ),
            ),
        )
    }

    private fun branchDataset(endMood: Int): CheckInAnalysisDatasetV2 {
        val start = 130L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 3),
                state("d", start + 3 * HOUR, mood = 4),
                state("e", start + 4 * HOUR, mood = endMood),
                state("f", start + 5 * HOUR, mood = 1),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                linked("c", "b", start + HOUR, start + 2 * HOUR),
                linked("d", "b", start + HOUR, start + 3 * HOUR),
                linked("e", "c", start + 2 * HOUR, start + 4 * HOUR),
                linked("f", "b", start + HOUR, start + 5 * HOUR, activity = CategoryObservationKindV2.UNREVIEWED),
            ),
        )
    }

    private fun twinDataset(): CheckInAnalysisDatasetV2 {
        val start = 140L * DAY
        return dataset(
            states = listOf(
                state("one", start + HOUR, mood = 4, emotion = "Calm"),
                state("two", start + 3 * HOUR, mood = 4, emotion = "Calm"),
            ),
            intervals = listOf(
                interval("one", null, start, start + HOUR, CheckInContextStartBasis.WAKE_TIME),
                interval("two", null, start + 2 * HOUR, start + 3 * HOUR, CheckInContextStartBasis.WAKE_TIME),
            ),
        )
    }

    private fun coExposureDataset(): CheckInAnalysisDatasetV2 {
        val start = 150L * DAY
        return dataset(
            states = listOf(
                state("a", start, mood = 1),
                state("b", start + HOUR, mood = 2),
                state("c", start + 2 * HOUR, mood = 8),
            ),
            intervals = listOf(
                linked("b", "a", start, start + HOUR),
                interval(
                    "c",
                    "b",
                    start + HOUR,
                    start + 2 * HOUR,
                    CheckInContextStartBasis.PREVIOUS_CHECK_IN,
                    activities = listOf(activity("coding", "opt-coding"), activity("shop", "opt-shop", "Shopping")),
                    workout = CategoryObservationKindV2.ENTRIES,
                    workouts = listOf(WorkoutObservationV2("run", null, "Run", 20, start)),
                    diet = CategoryObservationKindV2.ENTRIES,
                    meals = listOf(meal("large", FoodObservationV2("oats", "oats", "Oats", 0))),
                    usage = CategoryObservationKindV2.ENTRIES,
                    usageItems = listOf(usageItem("tea", SubstanceIntent.SUPPORTIVE.name, "1")),
                ),
            ),
        )
    }

    private fun usageDataset(): CheckInAnalysisDatasetV2 {
        val start = 160L * DAY
        return dataset(
            states = listOf(
                state("u0", start, mood = 0),
                state("u1", start + HOUR, mood = 1),
                state("u2", start + 2 * HOUR, mood = 2),
                state("u3", start + 3 * HOUR, mood = 3),
                state("bad", start + 4 * HOUR, mood = 4),
                state("blank", start + 5 * HOUR, mood = 5),
            ),
            intervals = listOf(
                usage("u1", "u0", start, "2 mg", supportive = "1"),
                usage("u2", "u1", start + HOUR, "4"),
                usage("u3", "u2", start + 2 * HOUR, "0"),
                usage("bad", "u3", start + 3 * HOUR, "1e3"),
                usage("blank", "bad", start + 4 * HOUR, ""),
            ),
        )
    }

    private fun usage(
        end: String,
        previous: String,
        start: Long,
        amount: String,
        supportive: String? = null,
    ): ContextIntervalObservationV2 {
        val items = mutableListOf(usageItem("cig", SubstanceIntent.COPING.name, amount))
        if (supportive != null) items += usageItem("cig", SubstanceIntent.SUPPORTIVE.name, supportive)
        return interval(
            end,
            previous,
            start,
            start + HOUR,
            CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            activity = CategoryObservationKindV2.NONE,
            usage = CategoryObservationKindV2.ENTRIES,
            usageItems = items,
        )
    }

    private fun linked(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activity: CategoryObservationKindV2 = CategoryObservationKindV2.ENTRIES,
        completed: Boolean = true,
    ) = interval(
        end,
        previous,
        start,
        endAt,
        CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completed = completed,
        activity = activity,
        activities = if (activity == CategoryObservationKindV2.ENTRIES) listOf(activity("coding", "opt-coding")) else emptyList(),
    )

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

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
        completed: Boolean = true,
        activity: CategoryObservationKindV2 = CategoryObservationKindV2.ENTRIES,
        activities: List<ActivityObservationV2> = listOf(activity("coding", "opt-coding")),
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
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = if (completed) endAt else null,
        activity = ActivityCategoryObservationV2(activity, if (completed) endAt else null, activities),
        workout = WorkoutCategoryObservationV2(workout, if (completed) endAt else null, workouts),
        diet = DietCategoryObservationV2(diet, if (completed) endAt else null, meals),
        usage = UsageCategoryObservationV2(usage, if (completed) endAt else null, usageItems),
    )

    private fun activity(id: String, optionId: String, name: String = optionId) = ActivityObservationV2(id, optionId, name, 0L)

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
        const val DAY = 24 * HOUR
    }
}
