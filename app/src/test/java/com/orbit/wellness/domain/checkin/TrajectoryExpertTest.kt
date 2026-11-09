package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.insights.expertCompactLabel
import com.orbit.wellness.ui.insights.expertTechnicalLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrajectoryExpertTest {
    @Test
    fun pathAndBackgroundCanShareASlopeAndRemainDifferentEvidence() {
        val hour = 3_600_000L
        val dataset = chain(
            point("a", 0, mood = 0),
            point("b", 2 * hour, mood = 2),
            point("c", 6 * hour, mood = 6),
        )
        val side = projectCausalStateSide(dataset)
        val immediate = side.mood("c", TrajectoryScaleId.IMMEDIATE_PATH)
        val wake = side.mood("c", TrajectoryScaleId.WAKE_CYCLE_PATH)
        val background = side.mood("c", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(4, immediate.stepDelta)
        assertEquals(1.0, checkNotNull(immediate.slopePerHour), 1e-9)
        assertEquals(listOf("b", "c"), immediate.occurrenceKeys.map { it.raw })
        assertEquals(TrajectoryContinuity.CONTINUOUS_PATH, immediate.continuity)
        assertTrue(immediate.focalIncluded)
        assertEquals(2, immediate.observedCount)
        assertEquals(2, immediate.distinctTimestampCount)

        assertEquals(1.0, checkNotNull(wake.slopePerHour), 1e-9)
        assertEquals(0.0, checkNotNull(wake.fitResidualDeviation), 1e-9)
        assertEquals(listOf("a", "b", "c"), wake.occurrenceKeys.map { it.raw })
        assertEquals(3, wake.observedCount)
        assertEquals(3, wake.distinctTimestampCount)
        assertEquals(6 * hour, wake.elapsedMillis)
        val summary = checkNotNull(wake.valueSummary)
        assertEquals(8.0 / 3.0, summary.mean, 1e-9)
        assertEquals(0.0, summary.minimum, 0.0)
        assertEquals(6.0, summary.maximum, 0.0)

        assertEquals(1.0, checkNotNull(background.slopePerHour), 1e-9)
        assertEquals(listOf("a", "b"), background.occurrenceKeys.map { it.raw })
        assertEquals(TrajectoryContinuity.SAMPLED_BACKGROUND, background.continuity)
        assertFalse(background.focalIncluded)
        assertNull(background.pathBreak)
        assertNotEquals(wake, background)
        val compared = side.deviation("c", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(5.0, checkNotNull(compared.currentMinusBackgroundMean), 1e-9)
        assertEquals(5.0, checkNotNull(compared.standardizedDeviation), 1e-9)
        assertNull(compared.standardizationGap)

        val projection = projectWalkForward(dataset)
        val stored = projection.stateSide.states.single { it.focal.checkInId == "c" }
        assertSame(dataset.states.single { it.checkInId == "c" }, stored.focal)
        assertSame(projection.anchors.single { it.stateKey.raw == "c" }, stored.trajectory)
        assertEquals(6, stored.trajectory.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.floorDistance)
        assertEquals(4, stored.trajectory.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.ceilingDistance)
        assertFalse(fields(BackgroundDeviation::class.java).any { it.contains("floor") || it.contains("ceiling") })

        val irregular = fitTrajectoryTrend(
            listOf(
                TrendPoint(StateOccurrenceKey("a"), 0, 0),
                TrendPoint(StateOccurrenceKey("b"), hour, 0),
                TrendPoint(StateOccurrenceKey("c"), 3 * hour, 6),
            ),
        )
        assertEquals(15.0 / 7.0, checkNotNull(irregular.slopePerHour), 1e-9)
        assertTrue(checkNotNull(irregular.fitResidualDeviation) > 0.0)
        val sameTime = fitTrajectoryTrend(
            listOf(
                TrendPoint(StateOccurrenceKey("aa"), 5_000, 1),
                TrendPoint(StateOccurrenceKey("bb"), 5_000, 4),
            ),
        )
        assertEquals(TrajectoryExpertGap.ZERO_TIME_SPAN, sameTime.unavailable)
        assertEquals(1, sameTime.distinctTimestampCount)
        assertNull(sameTime.slopePerHour)
    }

    @Test
    fun twoObservationsAndANoisyTrendKeepTheirOwnSupport() {
        val hour = 3_600_000L
        val side = projectCausalStateSide(
            chain(
                point("a", 0, mood = 0),
                point("b", hour, mood = 0),
                point("c", 3 * hour, mood = 6),
            ),
        )
        val immediate = side.mood("c", TrajectoryScaleId.IMMEDIATE_PATH)
        val wake = side.mood("c", TrajectoryScaleId.WAKE_CYCLE_PATH)
        assertEquals(2, immediate.observedCount)
        assertEquals(0.0, checkNotNull(immediate.fitResidualDeviation), 1e-9)
        assertEquals(3, wake.observedCount)
        assertEquals(3, wake.distinctTimestampCount)
        assertTrue(checkNotNull(wake.fitResidualDeviation) > 0.0)
        assertEquals(15.0 / 7.0, checkNotNull(wake.slopePerHour), 1e-9)
    }

    @Test
    fun pathWindowsAndBackgroundWindowsUseDifferentMembership() {
        val hour = 3_600_000L
        val day = 24 * hour
        val focal = 10 * day
        val history = chain(
            point("outside-week", focal - 7 * day - 1, mood = 1),
            point("week-edge", focal - 7 * day, mood = 1),
            point("day-edge", focal - day, mood = 1),
            point("outside-six", focal - 6 * hour - 1, mood = 1),
            point("six-edge", focal - 6 * hour, mood = 1),
            point("focal", focal, mood = 1),
            point("future", focal + hour, mood = 9),
        )
        val side = projectCausalStateSide(history)
        assertEquals(
            listOf("six-edge", "focal"),
            side.mood("focal", TrajectoryScaleId.IMMEDIATE_PATH).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("six-edge", "focal"),
            side.mood("focal", TrajectoryScaleId.SIX_HOUR_PATH).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("outside-week", "week-edge", "day-edge", "outside-six", "six-edge", "focal"),
            side.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("day-edge", "outside-six", "six-edge"),
            side.mood("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("week-edge", "day-edge", "outside-six", "six-edge"),
            side.mood("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND).occurrenceKeys.map { it.raw },
        )
        assertTrue(side.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH).focalIncluded)
        assertFalse(side.mood("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND).focalIncluded)

        val withLater = chain(
            point("outside-week", focal - 7 * day - 1, mood = 1),
            point("week-edge", focal - 7 * day, mood = 1),
            point("day-edge", focal - day, mood = 1),
            point("outside-six", focal - 6 * hour - 1, mood = 1),
            point("six-edge", focal - 6 * hour, mood = 1),
            point("focal", focal, mood = 1),
            point("future", focal + hour, mood = 3),
            point("beyond", focal + 2 * hour, mood = 7),
        )
        assertEquals(
            side.state("focal"),
            projectCausalStateSide(withLater).state("focal"),
        )
        val olderMoodChanged = chain(
            point("outside-week", focal - 7 * day - 1, mood = 8),
            point("week-edge", focal - 7 * day, mood = 1),
            point("day-edge", focal - day, mood = 1),
            point("outside-six", focal - 6 * hour - 1, mood = 1),
            point("six-edge", focal - 6 * hour, mood = 1),
            point("focal", focal, mood = 1),
            point("future", focal + hour, mood = 9),
        )
        val changed = projectCausalStateSide(olderMoodChanged)
        assertNotEquals(
            side.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH),
            changed.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH),
        )
        assertEquals(
            side.mood("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND),
            changed.mood("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND),
        )
    }

    @Test
    fun wakeCycleStopsAtTheBoundaryWhileBackgroundCrossesIt() {
        val hour = 3_600_000L
        val focalAt = 10 * 24 * hour
        val history = cycles(focalAt)
        val side = projectCausalStateSide(history)
        val immediate = side.mood("focal", TrajectoryScaleId.IMMEDIATE_PATH)
        val sixHour = side.mood("focal", TrajectoryScaleId.SIX_HOUR_PATH)
        val wake = side.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH)
        val dayBackground = side.mood("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        val weekBackground = side.mood("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND)

        assertEquals(listOf("mid", "focal"), immediate.occurrenceKeys.map { it.raw })
        assertEquals(2, immediate.stepDelta)
        assertEquals(2.0, checkNotNull(immediate.slopePerHour), 1e-9)
        assertEquals(listOf("late", "mid", "focal"), sixHour.occurrenceKeys.map { it.raw })
        assertFalse(sixHour.occurrenceKeys.map { it.raw }.contains("day-boundary"))
        assertEquals(listOf("day-boundary", "late", "mid", "focal"), wake.occurrenceKeys.map { it.raw })
        assertFalse(wake.occurrenceKeys.map { it.raw }.contains("second-cycle"))
        assertEquals(PathBreakReason.BOUNDARY, wake.pathBreak)
        assertEquals(TrajectoryContinuity.CONTINUOUS_PATH, wake.continuity)

        assertEquals(listOf("second-cycle", "day-boundary", "late", "mid"), dayBackground.occurrenceKeys.map { it.raw })
        assertEquals(TrajectoryContinuity.SAMPLED_BACKGROUND, dayBackground.continuity)
        assertFalse(dayBackground.focalIncluded)
        assertNull(dayBackground.pathBreak)
        assertEquals(
            listOf("first-cycle", "wake-boundary", "second-cycle", "day-boundary", "late", "mid"),
            weekBackground.occurrenceKeys.map { it.raw },
        )
        assertFalse(dayBackground.occurrenceKeys.map { it.raw }.contains("first-cycle"))
        assertTrue(weekBackground.occurrenceKeys.map { it.raw }.contains("first-cycle"))
        assertFalse(weekBackground.occurrenceKeys.map { it.raw }.contains("focal"))

        val dayDeviation = side.deviation("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(3.5, checkNotNull(dayDeviation.currentMinusBackgroundMean), 1e-9)
        assertTrue(checkNotNull(dayDeviation.standardizedDeviation).isFinite())
        assertNull(dayDeviation.standardizationGap)
        val weekDeviation = side.deviation("focal", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND)
        assertEquals(4.5, checkNotNull(weekDeviation.currentMinusBackgroundMean), 1e-9)

        val boundary = side.mood("day-boundary", TrajectoryScaleId.IMMEDIATE_PATH)
        assertEquals(TrajectoryExpertGap.BOUNDARY, boundary.unavailable)
        assertEquals(listOf("day-boundary"), boundary.occurrenceKeys.map { it.raw })
        assertTrue(
            side.mood("day-boundary", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
                .occurrenceKeys.map { it.raw }.contains("second-cycle"),
        )

        val aside = point("aside-backdated", focalAt - 5 * hour, mood = 7)
        val withAside = projectCausalStateSide(dataset(history.states + aside, history.intervals))
        assertEquals(wake, withAside.mood("focal", TrajectoryScaleId.WAKE_CYCLE_PATH))
        assertTrue(
            withAside.mood("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
                .occurrenceKeys.map { it.raw }.contains("aside-backdated"),
        )
        assertFalse(wake.occurrenceKeys.map { it.raw }.contains("aside-backdated"))

        val compact = expertCompactLabel(wake)
        val technical = expertTechnicalLabel(wake)
        assertTrue(compact.contains("continuous path"))
        assertTrue(compact.contains("4 observations"))
        assertFalse(compact.contains("day-boundary"))
        assertTrue(technical.contains("day-boundary"))
        val backgroundLabel = expertCompactLabel(dayBackground, dayDeviation)
        assertTrue(backgroundLabel.contains("sampled background"))
        assertTrue(backgroundLabel.contains("current minus background mean 3.5"))
        assertFalse(backgroundLabel.contains("second-cycle"))
    }

    @Test
    fun equalTimestampsStayUnorderedAndAFlatBackgroundIsNotStandardized() {
        val hour = 3_600_000L
        val at = 80_000L
        val forward = equalSamples(at, hour, reversed = false)
        val backward = equalSamples(at, hour, reversed = true)
        assertEquals(projectCausalStateSide(forward), projectCausalStateSide(backward))
        val background = projectCausalStateSide(forward).mood("focal-after", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(listOf("aa-sample", "bb-sample"), background.occurrenceKeys.map { it.raw })
        assertEquals(2, background.observedCount)
        assertEquals(1, background.distinctTimestampCount)
        assertEquals(TrajectoryExpertGap.ZERO_TIME_SPAN, background.unavailable)
        assertNull(background.slopePerHour)
        assertFalse(background.focalIncluded)
        val path = projectCausalStateSide(forward).mood("focal-after", TrajectoryScaleId.IMMEDIATE_PATH)
        assertEquals(TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR, path.unavailable)
        assertEquals(listOf("focal-after"), path.occurrenceKeys.map { it.raw })

        val flat = projectCausalStateSide(
            chain(
                point("earlier", 0, mood = 5),
                point("middle", hour, mood = 5),
                point("focal", 2 * hour, mood = 8),
            ),
        )
        val flatBackground = flat.mood("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(0.0, checkNotNull(flatBackground.slopePerHour), 0.0)
        assertEquals(0.0, checkNotNull(flatBackground.valueSummary).deviation, 0.0)
        val flatDeviation = flat.deviation("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(3.0, checkNotNull(flatDeviation.currentMinusBackgroundMean), 0.0)
        assertNull(flatDeviation.standardizedDeviation)
        assertEquals(StandardizationGap.NEGLIGIBLE_VARIATION, flatDeviation.standardizationGap)

        val single = projectCausalStateSide(
            chain(point("only-earlier", 0, mood = 5), point("focal", hour, mood = 8)),
        ).deviation("focal", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(3.0, checkNotNull(single.currentMinusBackgroundMean), 0.0)
        assertNull(single.standardizedDeviation)
        assertEquals(StandardizationGap.INSUFFICIENT_SUPPORT, single.standardizationGap)
    }

    @Test
    fun missingAxesAndRecordedZeroStayDistinct() {
        val hour = 3_600_000L
        val side = projectCausalStateSide(
            chain(
                point("a", 0, energy = 3),
                point("b", hour, mood = 0),
                point("c", 2 * hour, mood = 0),
            ),
        )
        val missing = side.mood("b", TrajectoryScaleId.IMMEDIATE_PATH)
        assertEquals(TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS, missing.unavailable)
        assertEquals(listOf("b"), missing.occurrenceKeys.map { it.raw })
        assertNull(missing.stepDelta)
        assertTrue(missing.focalIncluded)
        val zero = side.mood("c", TrajectoryScaleId.IMMEDIATE_PATH)
        assertNull(zero.unavailable)
        assertEquals(0, zero.stepDelta)
        assertEquals(0.0, checkNotNull(zero.slopePerHour), 0.0)
        assertEquals(listOf("b", "c"), zero.occurrenceKeys.map { it.raw })
        val background = side.mood("c", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(listOf("b"), background.occurrenceKeys.map { it.raw })
        assertEquals(0.0, checkNotNull(background.valueSummary).minimum, 0.0)
        assertEquals(TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS, background.unavailable)
        assertFalse(background.focalIncluded)
        val recordedZero = side.deviation("c", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND)
        assertEquals(0.0, checkNotNull(recordedZero.currentMinusBackgroundMean), 0.0)
        assertEquals(StandardizationGap.INSUFFICIENT_SUPPORT, recordedZero.standardizationGap)
        val energy = side.moodEnergy("c")
        assertEquals(listOf("a"), energy.occurrenceKeys.map { it.raw })
        assertNull(
            side.state("c").backgroundDeviations.single {
                it.axisId == DefaultCheckInAxes.ENERGY &&
                    it.scale == TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND
            }.currentMinusBackgroundMean,
        )
        val alone = projectCausalStateSide(chain(point("only", 0, mood = 4)))
            .mood("only", TrajectoryScaleId.SIX_HOUR_PATH)
        assertEquals(TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR, alone.unavailable)
        assertEquals(1, alone.observedCount)
        assertTrue(expertCompactLabel(alone).contains("continuous path"))
        assertTrue(expertCompactLabel(alone).contains("no resolved predecessor"))
    }

    @Test
    fun pathAmbiguityDoesNotInvalidateTemporalBackground() {
        val hour = 3_600_000L
        val start = 40 * hour
        val ambiguous = dataset(
            states = listOf(
                point("left-start", start, mood = 1),
                point("right-start", start + hour, mood = 9),
                point("meeting", start + 2 * hour, mood = 4),
            ),
            intervals = listOf(
                interval("meeting", "left-start", start, start + 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("meeting", "right-start", start + hour, start + 2 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
        val reversed = dataset(ambiguous.states.reversed(), ambiguous.intervals.reversed())
        assertEquals(projectCausalStateSide(ambiguous), projectCausalStateSide(reversed))
        val side = projectCausalStateSide(ambiguous)
        val path = side.mood("meeting", TrajectoryScaleId.IMMEDIATE_PATH)
        assertEquals(TrajectoryExpertGap.AMBIGUOUS_PREDECESSOR, path.unavailable)
        assertEquals(PathBreakReason.AMBIGUOUS_PREDECESSOR, path.pathBreak)
        assertEquals(listOf("meeting"), path.occurrenceKeys.map { it.raw })
        val background = side.mood("meeting", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND)
        assertNull(background.unavailable)
        assertEquals(listOf("left-start", "right-start"), background.occurrenceKeys.map { it.raw })
        assertEquals(2, background.distinctTimestampCount)
        assertEquals(8.0, checkNotNull(background.slopePerHour), 1e-9)
        assertFalse(background.focalIncluded)
        val deviation = side.deviation("meeting", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND)
        assertEquals(-1.0, checkNotNull(deviation.currentMinusBackgroundMean), 1e-9)
        assertEquals(-0.25, checkNotNull(deviation.standardizedDeviation), 1e-9)

        val unresolved = projectCausalStateSide(
            dataset(
                states = listOf(point("earlier", start, mood = 4), point("end", start + hour, mood = 1)),
                intervals = listOf(
                    interval("end", "ghost", start, start + hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
        )
        assertEquals(
            TrajectoryExpertGap.UNRESOLVED,
            unresolved.mood("end", TrajectoryScaleId.WAKE_CYCLE_PATH).unavailable,
        )
        assertEquals(
            listOf("earlier"),
            unresolved.mood("end", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND).occurrenceKeys.map { it.raw },
        )

        val invalid = projectCausalStateSide(
            dataset(
                states = listOf(point("early", start + hour, mood = 1), point("late", start + 5 * hour, mood = 8)),
                intervals = listOf(
                    interval("early", "late", start, start + hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                ),
            ),
        )
        assertEquals(
            TrajectoryExpertGap.INVALID_CHRONOLOGY,
            invalid.mood("early", TrajectoryScaleId.IMMEDIATE_PATH).unavailable,
        )
        assertFalse(invalid.mood("early", TrajectoryScaleId.WAKE_CYCLE_PATH).occurrenceKeys.map { it.raw }.contains("late"))
        assertEquals(
            listOf("early"),
            invalid.mood("late", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR,
            invalid.mood("late", TrajectoryScaleId.IMMEDIATE_PATH).unavailable,
        )

        val left = projectCausalStateSide(branch(9)).mood("left", TrajectoryScaleId.WAKE_CYCLE_PATH)
        val changed = projectCausalStateSide(branch(1)).mood("left", TrajectoryScaleId.WAKE_CYCLE_PATH)
        assertEquals(left, changed)
        assertEquals(listOf("origin", "left"), left.occurrenceKeys.map { it.raw })
        val right = projectCausalStateSide(branch(9))
        assertEquals(
            listOf("origin", "right"),
            right.mood("right", TrajectoryScaleId.WAKE_CYCLE_PATH).occurrenceKeys.map { it.raw },
        )
        assertEquals(
            listOf("origin", "left"),
            right.mood("right", TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND).occurrenceKeys.map { it.raw },
        )

        val equal = projectCausalStateSide(
            dataset(
                states = listOf(point("aa", start, mood = 2), point("bb", start, mood = 8)),
                intervals = listOf(interval("bb", "aa", start - hour, start, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        )
        assertFalse(equal.mood("bb", TrajectoryScaleId.WAKE_CYCLE_PATH).occurrenceKeys.map { it.raw }.contains("aa"))
        assertFalse(equal.mood("aa", TrajectoryScaleId.WAKE_CYCLE_PATH).occurrenceKeys.map { it.raw }.contains("bb"))
        assertFalse(equal.mood("bb", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND).occurrenceKeys.map { it.raw }.contains("aa"))
        assertFalse(equal.mood("aa", TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND).occurrenceKeys.map { it.raw }.contains("bb"))
    }

    @Test
    fun futureStateAndOutgoingTransitionDoNotChangeAnEarlierBundle() {
        val hour = 3_600_000L
        val focalAt = 10 * 24 * hour
        val base = cycles(focalAt)
        val outgoing = interval(
            "later-state",
            "focal",
            focalAt,
            focalAt + hour,
            CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        )
        val withFuture = dataset(
            base.states + point("later-state", focalAt + hour, mood = 9),
            base.intervals + outgoing,
        )
        val rewritten = dataset(
            base.states + point("later-state", focalAt + hour, mood = 1),
            base.intervals + outgoing.copy(
                activity = outgoing.activity.copy(kind = CategoryObservationKindV2.UNREVIEWED),
            ),
        )
        val original = projectCausalStateSide(base).state("focal")
        assertEquals(original, projectCausalStateSide(withFuture).state("focal"))
        assertEquals(original, projectCausalStateSide(rewritten).state("focal"))
        assertEquals(projectCausalStateSide(base), projectCausalStateSide(dataset(base.states.reversed(), base.intervals.reversed())))
    }

    @Test
    fun expertsCarryFamilySupportAndNoLearnedDecision() {
        assertEquals(TrajectoryContinuity.CONTINUOUS_PATH, TrajectoryScaleId.IMMEDIATE_PATH.continuity)
        assertEquals(TrajectoryContinuity.CONTINUOUS_PATH, TrajectoryScaleId.SIX_HOUR_PATH.continuity)
        assertEquals(TrajectoryContinuity.CONTINUOUS_PATH, TrajectoryScaleId.WAKE_CYCLE_PATH.continuity)
        assertEquals(TrajectoryContinuity.SAMPLED_BACKGROUND, TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND.continuity)
        assertEquals(TrajectoryContinuity.SAMPLED_BACKGROUND, TrajectoryScaleId.TRAILING_7_DAY_BACKGROUND.continuity)
        assertEquals("trajectory-expert-v2", TRAJECTORY_EXPERT_PROTOCOL)
        assertEquals("causal-state-side-v1", CAUSAL_STATE_SIDE_PROTOCOL)
        val side = projectCausalStateSide(chain(point("only", 0, mood = 4)))
        assertEquals(CAUSAL_STATE_SIDE_PROTOCOL, side.protocol)
        assertTrue(side.pastOnly)
        assertFalse(side.predictive)
        assertFalse(side.suitableForRecommendation)
        val banned = listOf("weight", "target", "coordinate", "effect", "recommend", "shock", "rebound")
        (fields(CausalStateSide::class.java) + fields(BackgroundDeviation::class.java) + fields(TrajectoryExpert::class.java))
            .forEach { name ->
                assertFalse(banned.any { token -> name.contains(token, ignoreCase = true) })
            }
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/TrajectoryExpert.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/TrajectoryExpert.kt",
        ).readText()
        listOf(
            "trajectory-expert-v1",
            "HOURS_24",
            "DAYS_7",
            "fun combine",
            "neural",
            "fitRetrospective",
            "projectStateMap",
            "projectMultiscale",
            "Dao",
            "RoomDatabase",
            ".insert",
            "shock",
            "rebound",
            "depression",
            "recovery",
        ).forEach { token ->
            assertFalse(source.contains(token, ignoreCase = token == "neural"))
        }
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        assertTrue(lab.contains("Within current waking path"))
        assertTrue(lab.contains("Earlier temporal background"))
        assertTrue(lab.contains("continuous path"))
        assertTrue(lab.contains("sampled background"))
        assertTrue(lab.contains("Technical details"))
        assertFalse(lab.contains("HOURS_24"))
        assertFalse(lab.contains("DAYS_7"))
        val replay = lab.substringAfter("fun RecordedReplayPane").substringBefore("fun PredictionSandboxPane")
        assertFalse(replay.contains("projectExpertHorizon"))
        assertFalse(replay.contains("horizonHours"))
        assertFalse(replay.contains("Slider"))
    }

    private fun CausalStateSideDataset.state(id: String): CausalStateSide {
        return states.single { it.focal.checkInId == id }
    }

    private fun CausalStateSideDataset.mood(id: String, scale: TrajectoryScaleId): TrajectoryExpert {
        val state = state(id)
        val experts = if (scale.continuity == TrajectoryContinuity.CONTINUOUS_PATH) state.pathExperts else state.backgroundExperts
        return experts.single { it.axisId == DefaultCheckInAxes.MOOD && it.scale == scale }
    }

    private fun CausalStateSideDataset.moodEnergy(id: String): TrajectoryExpert {
        return state(id).backgroundExperts.single {
            it.axisId == DefaultCheckInAxes.ENERGY && it.scale == TrajectoryScaleId.TRAILING_24_HOUR_BACKGROUND
        }
    }

    private fun CausalStateSideDataset.deviation(id: String, scale: TrajectoryScaleId): BackgroundDeviation {
        return state(id).backgroundDeviations.single { it.axisId == DefaultCheckInAxes.MOOD && it.scale == scale }
    }

    private fun equalSamples(at: Long, hour: Long, reversed: Boolean): CheckInAnalysisDatasetV2 {
        val samples = listOf(
            point("bb-sample", at, mood = 4),
            point("aa-sample", at, mood = 1),
        )
        val ordered = if (reversed) samples.reversed() else samples
        return dataset(ordered + point("focal-after", at + hour, mood = 8), emptyList())
    }

    private fun cycles(focalAt: Long): CheckInAnalysisDatasetV2 {
        val hour = 3_600_000L
        val day = 24 * hour
        return dataset(
            states = listOf(
                point("first-cycle", focalAt - 3 * day, mood = 1),
                point("wake-boundary", focalAt - 2 * day, mood = 2),
                point("second-cycle", focalAt - 10 * hour, mood = 3),
                point("day-boundary", focalAt - 8 * hour, mood = 4),
                point("late", focalAt - 3 * hour, mood = 5),
                point("mid", focalAt - hour, mood = 6),
                point("focal", focalAt, mood = 8),
            ),
            intervals = listOf(
                interval("wake-boundary", "first-cycle", focalAt - 3 * day, focalAt - 2 * day, CheckInContextStartBasis.WAKE_TIME),
                interval("second-cycle", "wake-boundary", focalAt - 2 * day, focalAt - 10 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("day-boundary", "second-cycle", focalAt - 10 * hour, focalAt - 8 * hour, CheckInContextStartBasis.DAY_START),
                interval("late", "day-boundary", focalAt - 8 * hour, focalAt - 3 * hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("mid", "late", focalAt - 3 * hour, focalAt - hour, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("focal", "mid", focalAt - hour, focalAt, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
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

    private fun branch(siblingMood: Int): CheckInAnalysisDatasetV2 {
        val start = 90 * 3_600_000L
        return dataset(
            states = listOf(
                point("origin", start, mood = 1),
                point("left", start + 3_600_000L, mood = 4),
                point("right", start + 2 * 3_600_000L, mood = siblingMood),
            ),
            intervals = listOf(
                interval("left", "origin", start, start + 3_600_000L, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("right", "origin", start, start + 2 * 3_600_000L, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun point(id: String, at: Long, mood: Int? = null, energy: Int? = null): StateObservationV2 {
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
