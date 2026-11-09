package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.insights.focalObservationLabel
import com.orbit.wellness.ui.insights.observationCompactLabel
import com.orbit.wellness.ui.insights.observationTechnicalLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObservationProcessTest {
    @Test
    fun partialCoverageCountsObservableTimeSeparatelyFromMembership() {
        val hour = 3_600_000L
        val focalAt = 30 * hour
        val history = chain(
            point("old-check-in", 0, mood = 1),
            point("recent-check-in", 20 * hour, mood = 2),
            point("focal", focalAt, mood = 4),
        )
        val focal = projectObservationProcess(history).context("focal")
        val day = focal.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        val week = focal.sampling(ObservationSamplingId.PRIOR_7_DAYS)
        val path = focal.sampling(ObservationSamplingId.RESOLVED_WAKING_PATH)
        assertEquals(listOf("recent-check-in"), day.occurrenceKeys.map { it.raw })
        assertEquals(1.0, checkNotNull(day.coverageFraction), 1e-12)
        assertEquals(24 * hour, day.effectiveObservableDurationMillis)
        assertEquals(1.0 / 24.0, checkNotNull(day.observationRatePerHour), 1e-12)
        assertNull(day.gaps)
        assertEquals(listOf("old-check-in", "recent-check-in"), week.occurrenceKeys.map { it.raw })
        assertEquals(30.0 / 168.0, checkNotNull(week.coverageFraction), 1e-12)
        assertEquals(30 * hour, week.effectiveObservableDurationMillis)
        assertEquals(2.0 / 30.0, checkNotNull(week.observationRatePerHour), 1e-12)
        val weekGaps = checkNotNull(week.gaps)
        assertEquals(1, weekGaps.count)
        assertEquals(20 * hour.toDouble(), weekGaps.meanMillis, 0.0)
        assertEquals(0.0, weekGaps.deviationMillis, 0.0)
        assertEquals(20 * hour.toDouble(), weekGaps.medianMillis, 0.0)
        assertEquals(listOf("old-check-in", "recent-check-in"), path.occurrenceKeys.map { it.raw })
        assertNull(path.requestedDurationMillis)
        assertNull(path.coverageFraction)
        assertEquals(30 * hour, path.effectiveObservableDurationMillis)

        val edgeAt = 48 * hour
        val edge = projectObservationProcess(
            chain(
                point("just-outside", edgeAt - 24 * hour - 1, mood = 1),
                point("window-edge", edgeAt - 24 * hour, mood = 2),
                point("focal", edgeAt, mood = 3),
            ),
        ).context("focal")
        assertEquals(listOf("window-edge"), edge.sampling(ObservationSamplingId.PRIOR_24_HOURS).occurrenceKeys.map { it.raw })
        assertEquals(
            listOf("just-outside", "window-edge"),
            edge.sampling(ObservationSamplingId.PRIOR_7_DAYS).occurrenceKeys.map { it.raw },
        )
        assertEquals(1.0, checkNotNull(edge.sampling(ObservationSamplingId.PRIOR_24_HOURS).coverageFraction), 0.0)
    }

    @Test
    fun backdatedHistoryChangesEventTimeReconstructionAndLaterHistoryDoesNot() {
        val hour = 3_600_000L
        val focalAt = 10 * hour
        val focal = point("focal", focalAt, mood = 4, trigger = "night")
        val alone = projectObservationProcess(dataset(listOf(focal), emptyList())).context("focal")
        val dayAlone = alone.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        assertEquals(0, dayAlone.observationCount)
        assertEquals(0.0, checkNotNull(dayAlone.coverageFraction), 0.0)
        assertEquals(0L, dayAlone.effectiveObservableDurationMillis)
        assertNull(dayAlone.observationRatePerHour)
        assertNull(dayAlone.gaps)
        assertNull(alone.sampling(ObservationSamplingId.RESOLVED_WAKING_PATH).effectiveObservableDurationMillis)

        val earlier = point("backdated-check-in", 0, mood = 1, trigger = "morning")
        val reconstructed = projectObservationProcess(dataset(listOf(focal, earlier), emptyList())).context("focal")
        assertNotEquals(alone, reconstructed)
        val day = reconstructed.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        assertEquals(listOf("backdated-check-in"), day.occurrenceKeys.map { it.raw })
        assertEquals(10.0 / 24.0, checkNotNull(day.coverageFraction), 1e-12)
        assertEquals(0.1, checkNotNull(day.observationRatePerHour), 1e-12)
        assertEquals(focalAt, day.timeSinceLatestMillis)
        assertNull(day.gaps)

        val later = point("later-state", focalAt + hour, mood = 9, trigger = "after")
        val withLater = projectObservationProcess(dataset(listOf(later, focal, earlier), emptyList())).context("focal")
        assertEquals(reconstructed, withLater)
        val editedLater = point("later-state", focalAt + hour, mood = 1, trigger = "rewritten")
        assertEquals(
            reconstructed,
            projectObservationProcess(dataset(listOf(focal, earlier, editedLater), emptyList())).context("focal"),
        )
        val editedEarlier = point("backdated-check-in", 0, mood = 1, trigger = "edited")
        val edited = projectObservationProcess(dataset(listOf(focal, editedEarlier), emptyList())).context("focal")
        assertNotEquals(reconstructed, edited)
        assertEquals("edited", edited.sampling(ObservationSamplingId.PRIOR_24_HOURS).triggers.single().trigger)
    }

    @Test
    fun equalTimestampsStayUnorderedAndAreNotPlacedBeforeTheFocal() {
        val hour = 3_600_000L
        val at = 5 * hour
        val focalAt = 10 * hour
        val forward = equalHistory(at, focalAt, reversed = false)
        val backward = equalHistory(at, focalAt, reversed = true)
        assertEquals(projectObservationProcess(forward), projectObservationProcess(backward))
        val focal = projectObservationProcess(forward).context("focal")
        val day = focal.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        assertEquals(listOf("aa-sample", "bb-sample", "mid-sample"), day.occurrenceKeys.map { it.raw })
        assertEquals(3, day.observationCount)
        assertEquals(2, day.distinctTimestampCount)
        assertEquals(1, day.duplicateTimestampCount)
        val gaps = checkNotNull(day.gaps)
        assertEquals(1, gaps.count)
        assertEquals(3 * hour.toDouble(), gaps.medianMillis, 0.0)
        assertEquals(0.0, gaps.deviationMillis, 0.0)
        assertFalse(day.occurrenceKeys.map { it.raw }.contains("same-time"))
        val sameTime = projectObservationProcess(forward).context("same-time")
        assertFalse(sameTime.sampling(ObservationSamplingId.PRIOR_7_DAYS).occurrenceKeys.map { it.raw }.contains("focal"))
        assertEquals(day.occurrenceKeys, sameTime.sampling(ObservationSamplingId.PRIOR_24_HOURS).occurrenceKeys)

        val onlyEqual = dataset(
            listOf(
                point("bb-sample", at, mood = 4),
                point("aa-sample", at, mood = 1),
                point("focal", focalAt, mood = 8),
            ),
            emptyList(),
        )
        val duplicates = projectObservationProcess(onlyEqual).context("focal").sampling(ObservationSamplingId.PRIOR_24_HOURS)
        assertEquals(listOf("aa-sample", "bb-sample"), duplicates.occurrenceKeys.map { it.raw })
        assertEquals(1, duplicates.distinctTimestampCount)
        assertEquals(1, duplicates.duplicateTimestampCount)
        assertNull(duplicates.gaps)
        assertEquals(
            projectObservationProcess(dataset(onlyEqual.states.reversed(), emptyList())).context("focal"),
            projectObservationProcess(onlyEqual).context("focal"),
        )
    }

    @Test
    fun wakePathStopsWhileClockWindowsKeepEarlierSamples() {
        val hour = 3_600_000L
        val history = wakeHistory(hour)
        val reversed = dataset(history.states.reversed(), history.intervals.reversed())
        assertEquals(projectObservationProcess(history), projectObservationProcess(reversed))
        val focal = projectObservationProcess(history).context("focal")
        val path = focal.sampling(ObservationSamplingId.RESOLVED_WAKING_PATH)
        val day = focal.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        val week = focal.sampling(ObservationSamplingId.PRIOR_7_DAYS)
        assertEquals(listOf("wake-boundary"), path.occurrenceKeys.map { it.raw })
        assertEquals(listOf("before-wake", "wake-boundary"), day.occurrenceKeys.map { it.raw })
        assertEquals(day.occurrenceKeys, week.occurrenceKeys)
        assertEquals(1, path.observationCount)
        assertNull(path.gaps)
        assertEquals(0.5, checkNotNull(path.observationRatePerHour), 1e-12)
        assertEquals(4.0 / 24.0, checkNotNull(day.coverageFraction), 1e-12)
        assertEquals(2, day.distinctTimestampCount)
        assertEquals(0, day.duplicateTimestampCount)
        val gaps = checkNotNull(day.gaps)
        assertEquals(1, gaps.count)
        assertEquals((2 * hour).toDouble(), gaps.minimumMillis.toDouble(), 0.0)
        assertEquals(0.0, gaps.deviationMillis, 0.0)

        val mood = day.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        val energy = day.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }
        val confidence = day.axes.single { it.axisId == DefaultCheckInAxes.CONFIDENCE }
        assertEquals(2, mood.recordedCount)
        assertEquals(0, mood.missingCount)
        assertEquals(1, energy.recordedCount)
        assertEquals(1, energy.missingCount)
        assertEquals(1, confidence.conflictingCount)
        assertEquals(1, confidence.missingCount)
        assertEquals(0, confidence.recordedCount)
        assertEquals(1, day.emotion.recordedCount)
        assertEquals(1, day.emotion.missingCount)
        assertEquals(listOf("alpha", "zeta"), day.triggers.map { it.trigger })
        assertEquals(1, path.emotion.recordedCount)
        assertEquals(1, path.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.recordedCount)
        assertEquals(0, path.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.missingCount)

        assertEquals(FocalAxisStatus.RECORDED, focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.status)
        assertEquals(8, focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertEquals(FocalAxisStatus.MISSING, focal.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }.status)
        assertEquals(FocalAxisStatus.CONFLICTING, focal.axes.single { it.axisId == DefaultCheckInAxes.ANXIETY }.status)
        assertTrue(focal.emotionPresent)
        assertEquals("night", focal.trigger)
        val compact = observationCompactLabel(day)
        val technical = observationTechnicalLabel(focal, day)
        assertTrue(compact.contains("2 observations"))
        assertTrue(compact.contains("coverage"))
        assertFalse(compact.contains("before-wake"))
        assertTrue(technical.contains("before-wake"))
        assertEquals(
            "Focal core axes recorded 1/4 · conflicts 1 · trigger night · emotion recorded",
            focalObservationLabel(focal),
        )
        assertFalse(focalObservationLabel(focal).contains("glad"))
        assertTrue(technical.contains("glad"))

        val outgoing = interval("later-state", "focal", 4 * hour, 5 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN)
        val withFuture = dataset(
            history.states + point("later-state", 5 * hour, mood = 9, trigger = "after"),
            history.intervals + outgoing,
        )
        val rewritten = dataset(
            history.states + point("later-state", 5 * hour, mood = 1, trigger = "rewritten"),
            history.intervals + outgoing.copy(
                activity = outgoing.activity.copy(kind = CategoryObservationKindV2.UNREVIEWED),
            ),
        )
        assertEquals(focal, projectObservationProcess(withFuture).context("focal"))
        assertEquals(focal, projectObservationProcess(rewritten).context("focal"))

        val ambiguous = projectObservationProcess(
            dataset(
                states = listOf(
                    point("left-start", 0, mood = 1),
                    point("right-start", hour, mood = 9),
                    point("meeting", 2 * hour, mood = 4),
                ),
                intervals = listOf(
                    interval("meeting", "left-start", 0, 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                    interval("meeting", "right-start", hour, 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
        ).context("meeting")
        assertEquals(0, ambiguous.sampling(ObservationSamplingId.RESOLVED_WAKING_PATH).observationCount)
        assertNull(ambiguous.sampling(ObservationSamplingId.RESOLVED_WAKING_PATH).observationRatePerHour)
        assertEquals(
            listOf("left-start", "right-start"),
            ambiguous.sampling(ObservationSamplingId.PRIOR_24_HOURS).occurrenceKeys.map { it.raw },
        )
        assertEquals(1, checkNotNull(ambiguous.sampling(ObservationSamplingId.PRIOR_24_HOURS).gaps).count)
    }

    @Test
    fun recordedZeroStaysRecordedAndOneObservationHasNoGapSupport() {
        val hour = 3_600_000L
        val process = projectObservationProcess(
            chain(
                point("earlier", 0, mood = 0),
                point("focal", hour, mood = 0),
            ),
        )
        val focal = process.context("focal")
        val day = focal.sampling(ObservationSamplingId.PRIOR_24_HOURS)
        assertEquals(1, day.observationCount)
        assertEquals(1, day.distinctTimestampCount)
        assertEquals(0, day.duplicateTimestampCount)
        assertNull(day.gaps)
        assertEquals(1.0, checkNotNull(day.observationRatePerHour), 1e-12)
        assertEquals(1, day.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.recordedCount)
        assertEquals(0, day.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.missingCount)
        assertEquals(FocalAxisStatus.RECORDED, focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.status)
        assertEquals(0, focal.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertFalse(focal.emotionPresent)
        assertEquals(1, day.emotion.missingCount)
        assertTrue(observationCompactLabel(day).contains("gaps unavailable"))
        val spaced = projectObservationProcess(
            chain(
                point("a", 0, mood = 1),
                point("b", hour, mood = 1),
                point("c", 4 * hour, mood = 1),
                point("focal", 6 * hour, mood = 1),
            ),
        ).context("focal").sampling(ObservationSamplingId.RESOLVED_WAKING_PATH)
        val spacedGaps = checkNotNull(spaced.gaps)
        assertEquals(2, spacedGaps.count)
        assertEquals((2 * hour).toDouble(), spacedGaps.meanMillis, 0.0)
        assertEquals(hour.toDouble(), spacedGaps.deviationMillis, 0.0)
        assertEquals((2 * hour).toDouble(), spacedGaps.medianMillis, 0.0)
        assertEquals(hour, spacedGaps.minimumMillis)
        assertEquals(3 * hour, spacedGaps.maximumMillis)
    }

    @Test
    fun theProjectionStaysSeparateFromWeightsTargetsAndReleaseNavigation() {
        val process = projectObservationProcess(dataset(listOf(point("only", 0, mood = 4)), emptyList()))
        assertEquals(OBSERVATION_PROCESS_PROTOCOL, process.protocol)
        assertEquals("observation-process-context-v1", process.protocol)
        assertEquals(ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION, process.availabilityMode)
        assertFalse(process.strictAsKnownAvailability)
        assertTrue(process.pastOnly)
        assertFalse(process.predictive)
        assertFalse(process.suitableForRecommendation)
        val banned = listOf("weight", "target", "shock", "rebound")
        (fields(ObservationProcessContext::class.java) + fields(ObservationSampling::class.java) + fields(GapSummary::class.java))
            .forEach { name ->
                assertFalse(name, banned.any { token -> name.contains(token, ignoreCase = true) })
                val namesABehaviorDifference = name.contains("effect", ignoreCase = true) &&
                    !name.contains("effective", ignoreCase = true)
                assertFalse(name, namesABehaviorDifference)
            }
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/ObservationProcessContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/ObservationProcessContext.kt",
        ).readText()
        listOf(
            "Dao",
            "RoomDatabase",
            ".insert",
            "fitRetrospective",
            "projectStateMap",
            "projectMultiscale",
            "fun combine",
            "neural",
            "shock",
            "rebound",
        ).forEach { token ->
            assertFalse(token, source.contains(token, ignoreCase = token == "neural"))
        }
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Observation process"))
        assertTrue(lab.contains("points/hour"))
        assertTrue(lab.contains("residual spread"))
        assertTrue(lab.contains("cannot establish trend stability"))
        val insights = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        val nav = source(
            "src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
            "app/src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
        ).readText()
        assertFalse(insights.contains("projectObservationProcess"))
        assertFalse(insights.contains("Observation process"))
        assertFalse(nav.contains("projectObservationProcess"))
        assertNull(com.orbit.wellness.ui.navigation.OrbitNavigation.researchPreviewRoute(debugBuild = false))
    }

    private fun ObservationProcessDataset.context(id: String): ObservationProcessContext {
        return contexts.single { it.stateKey.raw == id }
    }

    private fun ObservationProcessContext.sampling(id: ObservationSamplingId): ObservationSampling {
        return samplings.single { it.sampling == id }
    }

    private fun equalHistory(at: Long, focalAt: Long, reversed: Boolean): CheckInAnalysisDatasetV2 {
        val states = listOf(
            point("bb-sample", at, mood = 4),
            point("aa-sample", at, mood = 1),
            point("mid-sample", at + 3 * 3_600_000L, mood = 2),
            point("same-time", focalAt, mood = 3),
            point("focal", focalAt, mood = 8),
        )
        return dataset(if (reversed) states.reversed() else states, emptyList())
    }

    private fun wakeHistory(hour: Long): CheckInAnalysisDatasetV2 {
        return dataset(
            states = listOf(
                point(
                    "before-wake",
                    0,
                    mood = 0,
                    trigger = "zeta",
                    conflicting = listOf(DefaultCheckInAxes.CONFIDENCE),
                ),
                point(
                    "wake-boundary",
                    2 * hour,
                    mood = 4,
                    energy = 0,
                    trigger = "alpha",
                    emotion = "calm",
                ),
                point(
                    "focal",
                    4 * hour,
                    mood = 8,
                    trigger = "night",
                    emotion = "glad",
                    conflicting = listOf(DefaultCheckInAxes.ANXIETY),
                ),
            ),
            intervals = listOf(
                interval("wake-boundary", "before-wake", 0, 2 * hour, CheckInContextStartBasis.WAKE_TIME),
                interval("focal", "wake-boundary", 2 * hour, 4 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
    }

    private fun chain(vararg states: StateObservationV2): CheckInAnalysisDatasetV2 {
        val intervals = states.toList().zipWithNext().map { (previous, next) ->
            interval(
                next.checkInId,
                previous.checkInId,
                previous.recordedAt,
                next.recordedAt,
                CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            )
        }
        return dataset(states.toList(), intervals)
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun point(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        trigger: String = "",
        emotion: String = "",
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-11-08",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                null,
            ),
            conflictingAxisIds = conflicting,
            trigger = trigger,
            emotion = emotion,
        )
    }

    private fun interval(
        end: String,
        previous: String?,
        start: Long,
        endAt: Long,
        basis: String,
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = basis,
        completedAt = endAt,
        activity = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun fields(type: Class<*>): List<String> {
        return type.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "serialVersionUID" }
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }
}
