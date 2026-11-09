package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInAxisValue
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextCategory
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.insights.forecastEvidenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class WalkForwardBaselineTest {
    @Test
    fun persistenceRecentMeanAndMomentumStayOnEligibleSteps() {
        val projection = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a", mood = 6),
                Point("c", 3_000, previous = "b", mood = 5),
            ),
        )
        val evaluation = projection.evaluation
        assertEquals(listOf("b", "c"), evaluation.attempts.map { it.transitionId })
        val first = evaluation.attempts[0]
        assertEquals(4.0, prediction(first, BaselineKind.PERSISTENCE), 0.0)
        assertEquals(2.0, error(first, BaselineKind.PERSISTENCE)!!, 0.0)
        assertNull(predictionOrNull(first, BaselineKind.RECENT_MEAN))
        assertNull(predictionOrNull(first, BaselineKind.STEP_MOMENTUM))

        val second = evaluation.attempts[1]
        assertEquals(6.0, prediction(second, BaselineKind.PERSISTENCE), 0.0)
        assertEquals(-1.0, error(second, BaselineKind.PERSISTENCE)!!, 0.0)
        assertEquals(4.0, prediction(second, BaselineKind.RECENT_MEAN), 0.0)
        assertEquals(1.0, error(second, BaselineKind.RECENT_MEAN)!!, 0.0)
        assertEquals(8.0, prediction(second, BaselineKind.STEP_MOMENTUM), 0.0)
        assertEquals(-3.0, error(second, BaselineKind.STEP_MOMENTUM)!!, 0.0)

        val persistence = score(evaluation, BaselineKind.PERSISTENCE, DefaultCheckInAxes.MOOD)
        assertEquals(2, persistence.eligibleCount)
        assertEquals(2, persistence.targetObservedCount)
        assertEquals(2, persistence.predictionAvailableCount)
        assertEquals(2, persistence.predictionCount)
        assertEquals(0.5, persistence.bias!!, 1e-9)
        assertEquals(1.5, persistence.mae!!, 1e-9)
        assertEquals(sqrt(2.5), persistence.rmse!!, 1e-9)
        assertEquals(persistence.bias, persistence.residualDistribution!!.mean, 0.0)

        val support = evaluation.metrics.commonSupport.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(BaselineKind.entries, support.baselines.map { it.baseline })
        assertEquals(1, support.count)
        assertEquals(1.0, support.baselines.single { it.baseline == BaselineKind.PERSISTENCE }.mae!!, 0.0)
        assertEquals(1.0, support.baselines.single { it.baseline == BaselineKind.RECENT_MEAN }.mae!!, 0.0)
        assertEquals(3.0, support.baselines.single { it.baseline == BaselineKind.STEP_MOMENTUM }.mae!!, 0.0)

        val energy = score(evaluation, BaselineKind.PERSISTENCE, DefaultCheckInAxes.ENERGY)
        assertEquals(0, energy.targetObservedCount)
        assertEquals(0, energy.predictionCount)
        assertEquals(4, evaluation.attempts.flatMap { it.axisTarget.readings }.map { it.axisId }.distinct().size)
    }

    @Test
    fun recentMeanPrefersTheDayMeanAndDoesNotAverageTheWeek() {
        val hour = 3_600_000L
        val focalAt = 100 * hour
        val projection = projectWalkForward(
            history(
                Point("early", focalAt - 30 * hour, mood = 2),
                Point("recent", focalAt - 2 * hour, previous = "early", mood = 8),
                Point("focal", focalAt, previous = "recent", mood = 5),
                Point("next", focalAt + hour, previous = "focal", mood = 1),
            ),
        )
        val attempt = projection.evaluation.attempts.single { it.transitionId == "next" }
        val evidence = attempt.predictions.single {
            it.baseline == BaselineKind.RECENT_MEAN && it.axisId == DefaultCheckInAxes.MOOD
        }.evidence as ForecastEvidence.RecentMean
        assertEquals(RecentMeanChoice.TRAILING_DAY, evidence.used)
        assertEquals(8.0, evidence.dayMean!!, 0.0)
        assertEquals(5.0, evidence.weekMean!!, 0.0)
        assertEquals(8.0, prediction(attempt, BaselineKind.RECENT_MEAN), 0.0)
        assertEquals(2.0, prediction(attempt, BaselineKind.STEP_MOMENTUM), 0.0)
    }

    @Test
    fun boundedForecastsOwnTheOfficialResidual() {
        val lower = projectWalkForward(
            history(
                Point("high", 1_000, mood = 4),
                Point("floor", 2_000, previous = "high", mood = 0),
                Point("after", 3_000, previous = "floor", mood = 2),
            ),
        ).evaluation
        val lowerRecord = lower.attempts.single { it.transitionId == "after" }.predictions.single {
            it.baseline == BaselineKind.STEP_MOMENTUM && it.axisId == DefaultCheckInAxes.MOOD
        }
        val lowerForecast = checkNotNull(lowerRecord.forecast)
        assertEquals(-4.0, lowerForecast.rawExtrapolation, 0.0)
        assertEquals(0.0, lowerForecast.boundedForecast, 0.0)
        assertEquals(OverflowDirection.LOWER, lowerForecast.overflowDirection)
        assertEquals(4.0, lowerForecast.overflowMagnitude, 0.0)
        assertEquals(2.0, lowerRecord.residual!!, 0.0)
        assertEquals(6.0, lowerRecord.rawResidual!!, 0.0)
        val lowerScore = score(lower, BaselineKind.STEP_MOMENTUM, DefaultCheckInAxes.MOOD)
        assertEquals(1, lowerScore.predictionCount)
        assertEquals(2.0, lowerScore.mae!!, 0.0)
        assertEquals("Step momentum forecast 0 · raw -4 · lower overflow 4", com.orbit.wellness.ui.insights.forecastSummary(lowerRecord))

        val upper = projectWalkForward(
            history(
                Point("calm", 1_000, anxiety = 1),
                Point("rise", 2_000, previous = "calm", anxiety = 6),
                Point("later", 3_000, previous = "rise", anxiety = 8),
            ),
        ).evaluation
        val upperRecord = upper.attempts.single { it.transitionId == "later" }.predictions.single {
            it.baseline == BaselineKind.STEP_MOMENTUM && it.axisId == DefaultCheckInAxes.ANXIETY
        }
        val upperForecast = checkNotNull(upperRecord.forecast)
        assertEquals(11.0, upperForecast.rawExtrapolation, 0.0)
        assertEquals(10.0, upperForecast.boundedForecast, 0.0)
        assertEquals(OverflowDirection.UPPER, upperForecast.overflowDirection)
        assertEquals(1.0, upperForecast.overflowMagnitude, 0.0)
        assertEquals(-2.0, upperRecord.residual!!, 0.0)
        assertEquals(-3.0, upperRecord.rawResidual!!, 0.0)
        assertEquals(2.0, score(upper, BaselineKind.STEP_MOMENTUM, DefaultCheckInAxes.ANXIETY).mae!!, 0.0)

        val inRange = projectWalkForward(
            history(
                Point("low", 1_000, mood = 7),
                Point("top", 2_000, previous = "low", mood = 10),
                Point("after", 3_000, previous = "top", mood = 9),
            ),
        ).evaluation.attempts.single { it.transitionId == "after" }
        val held = checkNotNull(inRange.predictions.single {
            it.baseline == BaselineKind.PERSISTENCE && it.axisId == DefaultCheckInAxes.MOOD
        }.forecast)
        assertNull(held.overflowDirection)
        assertEquals(0.0, held.overflowMagnitude, 0.0)
        assertEquals(held.rawExtrapolation, held.boundedForecast, 0.0)

        val anchor = projectWalkForward(
            history(Point("a", 1_000, mood = 4), Point("b", 2_000, previous = "a", mood = 6)),
        )
        val sandbox = runSandbox(
            anchor.anchors.single { it.stateKey.raw == "a" },
            SandboxSession.fresh().withText(DefaultCheckInAxes.MOOD, SandboxField.DAY_MEAN, "12").toScenario(),
        )
        val mean = checkNotNull(sandbox.predictions.single {
            it.baseline == BaselineKind.RECENT_MEAN && it.axisId == DefaultCheckInAxes.MOOD
        }.forecast)
        assertEquals(12.0, mean.rawExtrapolation, 0.0)
        assertEquals(10.0, mean.boundedForecast, 0.0)
        assertEquals(OverflowDirection.UPPER, mean.overflowDirection)
        assertEquals(2.0, mean.overflowMagnitude, 0.0)
        assertEquals(WALK_FORWARD_BASELINE_PROTOCOL, lower.protocol)
    }

    @Test
    fun aSandboxHorizonDoesNotEnterTheAggregate() {
        val hour = 3_600_000L
        val dataset = history(
            Point("a", hour, mood = 0),
            Point("b", 2 * hour, previous = "a", mood = 2),
        )
        val projection = projectWalkForward(dataset)
        val anchor = projection.anchors.single { it.stateKey.raw == "b" }
        val expert = projection.stateSide.states.single { it.trajectory.stateKey == anchor.stateKey }
            .pathExperts.single {
                it.axisId == DefaultCheckInAxes.MOOD && it.scale == TrajectoryScaleId.IMMEDIATE_PATH
            }
        assertSame(anchor, projection.stateSide.states.single { it.trajectory.stateKey == anchor.stateKey }.trajectory)
        assertEquals(2, expert.stepDelta)
        assertEquals(2.0, expert.slopePerHour!!, 1e-9)
        val session = SandboxSession.fresh().withHorizon("2")
        assertEquals(2.0, session.horizonHours()!!, 0.0)
        assertNull(SandboxSession.fresh().horizonHours())
        assertNull(SandboxSession.fresh().withHorizon("0").horizonHours())
        assertNull(SandboxSession.fresh().withHorizon("-3").horizonHours())
        assertEquals(session.toScenario(), SandboxSession.fresh().toScenario())
        val preview = runSandbox(anchor, session.toScenario(), listOf(expert), session.horizonHours())
        val projected = preview.horizonPreviews.single().forecast
        assertEquals(6.0, checkNotNull(projected).rawExtrapolation, 1e-9)
        assertEquals(6.0, projected.boundedForecast, 1e-9)
        assertNull(projectExpertHorizon(2, expert, null))
        val overridden = runSandbox(
            anchor,
            SandboxSession.fresh().withText(DefaultCheckInAxes.MOOD, SandboxField.STARTING, "0").withHorizon("2").toScenario(),
            listOf(expert),
            2.0,
        )
        assertEquals(2.0, expert.slopePerHour, 1e-9)
        assertEquals(2, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.currentRaw)
        assertEquals(4.0, checkNotNull(overridden.horizonPreviews.single().forecast).rawExtrapolation, 1e-9)
        assertEquals(projection.evaluation, projectWalkForward(dataset).evaluation)
    }

    @Test
    fun aMissingTargetStaysPresentAndARecordedZeroStillCounts() {
        val missing = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a"),
            ),
        ).evaluation
        val attempt = missing.attempts.single()
        assertNull(attempt.axisTarget.reading(DefaultCheckInAxes.MOOD).raw)
        assertEquals(
            CheckInAnalysisDatasetV2.CORE_AXIS_IDS,
            attempt.axisTarget.readings.map { it.axisId },
        )
        assertEquals(4.0, prediction(attempt, BaselineKind.PERSISTENCE), 0.0)
        assertNull(error(attempt, BaselineKind.PERSISTENCE))
        val missingScore = score(missing, BaselineKind.PERSISTENCE, DefaultCheckInAxes.MOOD)
        assertEquals(0, missingScore.targetObservedCount)
        assertEquals(1, missingScore.predictionAvailableCount)
        assertEquals(0, missingScore.predictionCount)

        val zero = projectWalkForward(
            history(
                Point("a", 1_000, mood = 0),
                Point("b", 2_000, previous = "a", mood = 4),
            ),
        ).evaluation.attempts.single()
        assertEquals(0.0, prediction(zero, BaselineKind.PERSISTENCE), 0.0)
        assertEquals(4.0, error(zero, BaselineKind.PERSISTENCE)!!, 0.0)
        val evidence = zero.predictions.single {
            it.baseline == BaselineKind.PERSISTENCE && it.axisId == DefaultCheckInAxes.MOOD
        }.evidence as ForecastEvidence.Persistence
        assertEquals(EvidenceOrigin.OBSERVED, evidence.origin)
    }

    @Test
    fun commonSupportRequiresEveryBaseline() {
        val day = 24 * 3_600_000L
        val evaluation = projectWalkForward(
            history(
                Point("a", 0, mood = 4),
                Point("b", 8 * day, previous = "a", mood = 6),
                Point("c", 8 * day + 3_600_000L, previous = "b", mood = 5),
            ),
        ).evaluation
        val gap = evaluation.attempts.single { it.transitionId == "c" }
        assertEquals(6.0, prediction(gap, BaselineKind.PERSISTENCE), 0.0)
        assertNull(predictionOrNull(gap, BaselineKind.RECENT_MEAN))
        assertEquals(8.0, prediction(gap, BaselineKind.STEP_MOMENTUM), 0.0)
        val support = evaluation.metrics.commonSupport.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(BaselineKind.entries, support.baselines.map { it.baseline })
        assertEquals(0, support.count)
        assertTrue(support.baselines.all { it.bias == null && it.mae == null && it.rmse == null })
        assertEquals(2, score(evaluation, BaselineKind.PERSISTENCE, DefaultCheckInAxes.MOOD).predictionCount)
    }

    @Test
    fun aBoundaryIntervalIsNotAnAttempt() {
        val projection = projectWalkForward(history(Point("wake", 1_000, mood = 4)))
        assertTrue(projection.evaluation.attempts.isEmpty())
        assertEquals(0, score(projection.evaluation, BaselineKind.PERSISTENCE, DefaultCheckInAxes.MOOD).eligibleCount)
        assertEquals(listOf("wake"), projection.anchors.map { it.stateKey.raw })
    }

    @Test
    fun sandboxForecastsStayOutOfTheAggregateAndDoNotMutateTheAnchor() {
        val dataset = history(
            Point("a", 1_000, mood = 4),
            Point("b", 2_000, previous = "a", mood = 6),
        )
        val projection = projectWalkForward(dataset)
        val anchor = projection.anchors.single { it.stateKey.raw == "a" }
        val snapshot = anchor.copy()
        val session = SandboxSession.fresh()
            .withText(DefaultCheckInAxes.MOOD, SandboxField.STARTING, "9")
            .withTarget(DefaultCheckInAxes.MOOD, "1")
        val sandbox = runSandbox(anchor, session.toScenario())
        val again = projectWalkForward(dataset)

        assertEquals(snapshot, anchor)
        assertEquals(4, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.currentRaw)
        assertEquals(projection.evaluation, again.evaluation)
        assertEquals(1, projection.evaluation.attempts.size)
        assertEquals(2.0, error(projection.evaluation.attempts.single(), BaselineKind.PERSISTENCE)!!, 0.0)
        assertEquals(9.0, prediction(sandbox, BaselineKind.PERSISTENCE), 0.0)
        assertEquals(-8.0, error(sandbox, BaselineKind.PERSISTENCE)!!, 0.0)
        assertEquals(
            EvidenceOrigin.OVERRIDDEN,
            (sandbox.predictions.single {
                it.baseline == BaselineKind.PERSISTENCE && it.axisId == DefaultCheckInAxes.MOOD
            }.evidence as ForecastEvidence.Persistence).origin,
        )
        assertFalse(projection.evaluation.attempts.flatMap { it.predictions }.any { it.residual == -8.0 })
    }

    @Test
    fun anOverrideDoesNotPretendToBeObservedAndAClearedDeltaDropsMomentum() {
        val projection = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a", mood = 6),
                Point("c", 3_000, previous = "b", mood = 5),
            ),
        )
        val anchor = projection.anchors.single { it.stateKey.raw == "b" }
        assertEquals(4.0, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.trailingDay.summary!!.mean, 0.0)
        assertEquals(2, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.incomingDelta)
        val session = SandboxSession.fresh()
            .withText(DefaultCheckInAxes.MOOD, SandboxField.DAY_MEAN, "9.5")
            .clear(DefaultCheckInAxes.MOOD, SandboxField.WEEK_MEAN)
            .clear(DefaultCheckInAxes.MOOD, SandboxField.INCOMING_DELTA)
        val sandbox = runSandbox(anchor, session.toScenario())
        assertEquals(4.0, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.trailingDay.summary!!.mean, 0.0)
        assertEquals(2, anchor.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.incomingDelta)
        val reading = sandbox.input.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(9.5, reading.dayMean!!, 0.0)
        assertEquals(EvidenceOrigin.OVERRIDDEN, reading.dayOrigin)
        assertEquals(EvidenceOrigin.CLEARED, reading.weekOrigin)
        assertNull(reading.incomingDelta)
        assertEquals(EvidenceOrigin.CLEARED, reading.deltaOrigin)
        assertEquals(9.5, prediction(sandbox, BaselineKind.RECENT_MEAN), 0.0)
        assertNull(predictionOrNull(sandbox, BaselineKind.STEP_MOMENTUM))
        val label = forecastEvidenceLabel(
            sandbox.predictions.single {
                it.baseline == BaselineKind.RECENT_MEAN && it.axisId == DefaultCheckInAxes.MOOD
            },
        )
        assertTrue(label.contains("(overridden)"))
        assertFalse(label.contains("(observed)"))
        assertFalse(fieldNames(CausalAxisReading::class.java).any { it.contains("count", ignoreCase = true) })
    }

    @Test
    fun aSyntheticTargetIsLocalAndRecreationStartsClean() {
        val edited = SandboxSession.fresh()
            .withText(DefaultCheckInAxes.MOOD, SandboxField.STARTING, "9")
            .withTarget(DefaultCheckInAxes.MOOD, "3")
        val recreated = SandboxSession.fresh()
        assertNotEquals(edited, recreated)
        assertTrue(recreated.startingText.isEmpty())
        assertTrue(recreated.targetText.isEmpty())
        assertNull(recreated.syntheticTarget())
        assertEquals(3, edited.syntheticTarget()!!.reading(DefaultCheckInAxes.MOOD).raw)
        assertNull(edited.withTarget(DefaultCheckInAxes.MOOD, "").syntheticTarget())
        assertEquals(SandboxChange.Keep, recreated.change(DefaultCheckInAxes.MOOD, SandboxField.STARTING))
    }

    @Test
    fun futureAndOutgoingContextDoNotChangeTheCausalInput() {
        val original = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a", mood = 6),
            ),
        )
        val changedTarget = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a", mood = 1),
            ),
        )
        val withOutgoing = projectWalkForward(
            history(
                Point("a", 1_000, mood = 4),
                Point("b", 2_000, previous = "a", mood = 6),
                Point("c", 3_000, previous = "a", mood = 9),
            ),
        )
        assertEquals(input(original, "a"), input(changedTarget, "a"))
        assertEquals(input(original, "a"), input(withOutgoing, "a"))
        assertEquals(
            prediction(original.evaluation.attempts.single(), BaselineKind.PERSISTENCE),
            prediction(changedTarget.evaluation.attempts.single(), BaselineKind.PERSISTENCE),
            0.0,
        )
        assertNotEquals(
            error(original.evaluation.attempts.single(), BaselineKind.PERSISTENCE),
            error(changedTarget.evaluation.attempts.single(), BaselineKind.PERSISTENCE),
        )
        assertEquals(
            original.evaluation.attempts.single().predictions,
            withOutgoing.evaluation.attempts.single { it.transitionId == "b" }.predictions,
        )
        assertEquals(2, withOutgoing.evaluation.attempts.size)
    }

    @Test
    fun targetTypingDoesNotExposeRetrospectiveCoordinates() {
        val banned = listOf("coordinate", "component", "pca", "latent", "emotion", "weight", "reliability", "outgoing")
        listOf(
            ObservedAxisTarget::class.java,
            ObservedAxisReading::class.java,
            AxisPredictionRecord::class.java,
            CausalBaselineInput::class.java,
            CausalAxisReading::class.java,
            WalkForwardAttempt::class.java,
            AxisBaselineScore::class.java,
        ).forEach { type ->
            val names = fieldNames(type).joinToString(" ")
            banned.forEach { token ->
                assertFalse(type.simpleName + " " + names, names.contains(token, ignoreCase = true))
            }
        }
        val scoreNames = fieldNames(AxisBaselineScore::class.java)
        assertTrue(scoreNames.contains("predictionCount"))
        assertTrue(scoreNames.contains("residualDistribution"))
        assertTrue(scoreNames.contains("bias"))
        assertTrue(scoreNames.contains("mae"))
        assertTrue(scoreNames.contains("rmse"))
        assertTrue(ObservedAxisTarget::class.java.interfaces.contains(EvaluationTarget::class.java))
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/WalkForwardBaseline.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/WalkForwardBaseline.kt",
        ).readText()
        assertFalse(source.contains("fitRetrospective"))
        assertFalse(source.contains("projectStateMap"))
        assertFalse(source.contains("projectMultiscale"))
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains("RoomDatabase"))
        assertFalse(source.contains(".insert"))
        val forecast = source.substringAfter("fun forecastBaselines(").substringBefore("private fun predict(")
        assertFalse(forecast.contains("HypotheticalTransition"))
        assertTrue(source.contains("hypotheticalTransition"))
    }

    @Test
    fun theLabIsDebugOnlyAndSandboxStateIsNotRememberedByTheViewModel() {
        val screen = source(
            "src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/ResearchPreviewScreen.kt",
        ).readText()
        val lab = source(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        ).readText()
        val viewModel = source(
            "src/main/java/com/orbit/wellness/ui/insights/WalkForwardViewModel.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/WalkForwardViewModel.kt",
        ).readText()
        val inspection = source(
            "src/main/java/com/orbit/wellness/ui/insights/WalkForwardInspection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/WalkForwardInspection.kt",
        ).readText()
        val insights = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
        ).readText()
        val insightsScreen = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        val repository = source(
            "src/main/java/com/orbit/wellness/data/CheckInAnalysisRepository.kt",
            "app/src/main/java/com/orbit/wellness/data/CheckInAnalysisRepository.kt",
        ).readText()
        val database = source(
            "src/main/java/com/orbit/wellness/data/local/OrbitDatabase.kt",
            "app/src/main/java/com/orbit/wellness/data/local/OrbitDatabase.kt",
        ).readText()
        val nav = source(
            "src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
            "app/src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
        ).readText()
        assertTrue(screen.contains("PredictionResearchPane(walkForwardViewModel)"))
        assertTrue(lab.contains("mutableStateOf(SandboxSession.fresh())"))
        assertTrue(lab.substringAfter("when (mode)").contains("PredictionLabMode.SANDBOX -> PredictionSandboxPane"))
        assertFalse(viewModel.contains("SandboxSession"))
        assertFalse(viewModel.contains("runSandbox"))
        assertTrue(inspection.contains("distinctUntilChanged()"))
        assertTrue(inspection.contains("mapLatest"))
        assertFalse(inspection.contains("runSandbox"))
        assertFalse(insights.contains("projectWalkForward"))
        assertFalse(insights.contains("observeWalkForward"))
        assertFalse(insightsScreen.contains("Prediction lab"))
        assertFalse(insightsScreen.contains("PredictionResearchPane"))
        assertFalse(repository.contains("projectWalkForward"))
        assertTrue(database.contains("version = 23"))
        assertFalse(nav.substringBefore("if (BuildConfig.DEBUG)").contains("WalkForwardViewModel.Factory"))
        assertTrue(nav.substringAfter("if (BuildConfig.DEBUG)").contains("WalkForwardViewModel.Factory"))
        listOf("Activity", "Workout", "Diet", "Usage", "Wearable", "recommend").forEach { token ->
            assertFalse(lab.contains(token))
        }
        assertFalse(lab.contains("Dao"))
        assertFalse(lab.contains(".insert"))
        assertFalse(lab.contains("Slider"))
        assertNull(com.orbit.wellness.ui.navigation.OrbitNavigation.researchPreviewRoute(debugBuild = false))
    }

    private fun input(projection: WalkForwardProjection, id: String): CausalBaselineInput {
        return causalBaselineInput(projection.anchors.single { it.stateKey.raw == id })
    }

    private fun prediction(
        attempt: WalkForwardAttempt,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double = checkNotNull(predictionOrNull(attempt, baseline, axisId))

    private fun prediction(
        forecast: SandboxForecast,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double = checkNotNull(forecast.predictions.single { it.baseline == baseline && it.axisId == axisId }.forecast).boundedForecast

    private fun predictionOrNull(
        attempt: WalkForwardAttempt,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double? = attempt.predictions.single { it.baseline == baseline && it.axisId == axisId }.forecast?.boundedForecast

    private fun predictionOrNull(
        forecast: SandboxForecast,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double? = forecast.predictions.single { it.baseline == baseline && it.axisId == axisId }.forecast?.boundedForecast

    private fun error(
        attempt: WalkForwardAttempt,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double? = attempt.predictions.single { it.baseline == baseline && it.axisId == axisId }.residual

    private fun error(
        forecast: SandboxForecast,
        baseline: BaselineKind,
        axisId: String = DefaultCheckInAxes.MOOD,
    ): Double? = forecast.predictions.single { it.baseline == baseline && it.axisId == axisId }.residual

    private fun score(
        evaluation: WalkForwardEvaluation,
        baseline: BaselineKind,
        axisId: String,
    ): AxisBaselineScore = evaluation.metrics.scores.single { it.baseline == baseline && it.axisId == axisId }

    private fun history(vararg points: Point): CheckInAnalysisDatasetV2 {
        val windows = points.map { point ->
            if (point.previous == null) {
                window(point.id, point.at)
            } else {
                val start = points.single { it.id == point.previous }.at
                previousWindow(point.id, end = point.at, start = start, previous = point.previous)
            }
        }
        val axes = points.flatMap { point ->
            listOfNotNull(
                point.mood?.let { axis(point.id, DefaultCheckInAxes.MOOD, it) },
                point.anxiety?.let { axis(point.id, DefaultCheckInAxes.ANXIETY, it) },
            )
        }
        return dataset(
            checkIns = points.map { checkIn(it.id, it.at) }.reversed(),
            windows = windows,
            axisValues = axes,
        )
    }

    private fun dataset(
        checkIns: List<CheckInRecord>,
        windows: List<CheckInContextWindow>,
        axisValues: List<CheckInAxisValue>,
    ): CheckInAnalysisDatasetV2 {
        return CheckInAnalysisDatasetBuilder.build(
            checkIns = checkIns,
            axisValues = axisValues,
            windows = windows,
            categories = checkIns.flatMap { noneCategories(it.id) },
        )
    }

    private fun checkIn(id: String, at: Long) = CheckInRecord(
        id = id,
        localDate = DAY,
        recordedAt = at,
        trigger = CheckInRecord.TRIGGER_MANUAL,
        note = "",
        emotion = "",
    )

    private fun window(id: String, end: Long) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = null,
        windowStartAt = 0L,
        windowEndAt = end,
        startBasis = CheckInContextStartBasis.WAKE_TIME,
        completedAt = 10L,
    )

    private fun previousWindow(id: String, end: Long, start: Long, previous: String) = CheckInContextWindow(
        checkInId = id,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = end,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = 10L,
    )

    private fun noneCategories(id: String) = CheckInContextCategories.ALL.map { category ->
        CheckInContextCategory(
            checkInId = id,
            category = category,
            status = CheckInContextStatus.NONE,
        )
    }

    private fun axis(checkInId: String, axisId: String, value: Int) = CheckInAxisValue(checkInId, axisId, value)

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private data class Point(
        val id: String,
        val at: Long,
        val previous: String? = null,
        val mood: Int? = null,
        val anxiety: Int? = null,
    )

    private companion object {
        const val DAY = "2026-11-08"

        fun fieldNames(type: Class<*>): Set<String> = type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "serialVersionUID" }
            .toSet()
    }
}
