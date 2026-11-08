package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.CheckInAnalysisSnapshot
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.WearableMethod
import com.orbit.wellness.data.local.WearableMetric
import com.orbit.wellness.data.local.WearableObservation
import com.orbit.wellness.data.local.WearableTemporalKind
import com.orbit.wellness.data.local.WearableUnit
import com.orbit.wellness.domain.checkin.ActivityCategoryObservationV2
import com.orbit.wellness.domain.checkin.AxisReadingV2
import com.orbit.wellness.domain.checkin.CategoryObservationKindV2
import com.orbit.wellness.domain.checkin.CheckInAnalysisDatasetV2
import com.orbit.wellness.domain.checkin.ContextIntervalObservationV2
import com.orbit.wellness.domain.checkin.CoreAxisViewV2
import com.orbit.wellness.domain.checkin.DietCategoryObservationV2
import com.orbit.wellness.domain.checkin.LatentFitStatus
import com.orbit.wellness.domain.checkin.LatentProjectionScope
import com.orbit.wellness.domain.checkin.OmittedStateEdgeReason
import com.orbit.wellness.domain.checkin.StateMapPoint
import com.orbit.wellness.domain.checkin.StateObservationV2
import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import com.orbit.wellness.domain.checkin.UsageCategoryObservationV2
import com.orbit.wellness.domain.checkin.WorkoutCategoryObservationV2
import com.orbit.wellness.domain.checkin.projectStateMap
import com.orbit.wellness.domain.checkin.summarizeCheckInAnalysis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class StateMapTest {
    @Test
    fun oneSnapshotFeedsEveryStage() {
        val held = rectangle()
        val before = held.dataset.states.map { it.checkInId to it.emotion }
        val analysis = projectStateMap(held.dataset, listOf(held.wearable))
        assertEquals(before, held.dataset.states.map { it.checkInId to it.emotion })
        assertEquals(held.dataset.issues, analysis.analysisIssues)
        assertEquals(held.dataset.states.map { it.checkInId }.toSet(), analysis.points.map { it.key.raw }.toSet())
        analysis.points.forEach { point ->
            val stored = analysis.latent.states.single { it.key == point.key }
            assertEquals(stored.coordinates[0], point.x, 0.0)
            assertEquals(stored.coordinates.getOrNull(1), point.y)
            assertEquals(stored.recordedAt, point.recordedAt)
        }
        val withoutWearable = projectStateMap(held.dataset)
        assertNotEquals(
            analysis.latent.sourceContract,
            withoutWearable.latent.sourceContract,
        )
    }

    @Test
    fun equalSnapshotsFitOnceAndAChangeFitsAgain() = runBlocking {
        val first = snapshot(rectangle().dataset)
        val second = snapshot(twoStates())
        val fits = AtomicInteger()
        val seen = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            observeInsightsAnalysis(
                snapshots = flow {
                    emit(first)
                    release.await()
                    emit(first)
                    emit(second)
                },
                wearables = flowOf(emptyList()),
                computation = Dispatchers.Default,
            ) { dataset, _ ->
                seen += dataset.states.joinToString(",") { it.checkInId }
                val analysis = projectStateMap(dataset)
                fits.incrementAndGet()
                analysis
            }.collect { }
        }
        withTimeout(5_000) { while (fits.get() < 1) delay(20) }
        delay(40)
        assertEquals(1, fits.get())
        release.complete(Unit)
        withTimeout(5_000) { while (fits.get() < 2) delay(20) }
        assertEquals(2, fits.get())
        assertEquals(
            listOf(
                first.dataset.states.joinToString(",") { it.checkInId },
                second.dataset.states.joinToString(",") { it.checkInId },
            ),
            seen,
        )
        job.cancel()
    }

    @Test
    fun changedWearablesTriggerAnotherProjection() = runBlocking {
        val held = rectangle()
        val wearableFits = AtomicInteger()
        val received = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            observeInsightsAnalysis(
                snapshots = flowOf(snapshot(held.dataset)),
                wearables = flow {
                    emit(emptyList())
                    release.await()
                    emit(listOf(held.wearable))
                },
                computation = Dispatchers.Default,
            ) { _, wearables ->
                received += wearables.size
                val analysis = projectStateMap(held.dataset, wearables)
                wearableFits.incrementAndGet()
                analysis
            }.collect { }
        }
        withTimeout(5_000) { while (wearableFits.get() < 1) delay(20) }
        release.complete(Unit)
        withTimeout(5_000) { while (wearableFits.get() < 2) delay(20) }
        assertEquals(listOf(0, 1), received)
        job.cancel()
    }

    @Test
    fun analysisDoesNotWriteRoom() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/StateMapAnalysis.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/StateMapAnalysis.kt",
        ).readText()
        assertFalse(source.contains("Dao"))
        assertFalse(source.contains(".insert"))
        assertFalse(source.contains("RoomDatabase"))
        val analysis = projectStateMap(rectangle().dataset)
        assertEquals(LatentProjectionScope.RETROSPECTIVE_DESCRIPTIVE, analysis.latent.scope)
        assertTrue(analysis.points.isNotEmpty())
    }

    @Test
    fun twoComponentsStayTwoDimensional() {
        val analysis = projectStateMap(rectangle().dataset)
        val content = analysis.toStateMapContent()
        assertEquals(LatentFitStatus.FITTED, analysis.latent.status)
        assertEquals(2, analysis.latent.components.size)
        assertEquals(StateMapMode.TWO_COMPONENT, content.mode)
        assertEquals(2, content.componentCount)
        assertTrue(content.points.all { it.y != null })
        assertEquals(2, content.explainedVariancePercent.size)
    }

    @Test
    fun oneComponentDoesNotInventASecondCoordinate() {
        val analysis = projectStateMap(twoStates())
        val content = analysis.toStateMapContent()
        assertEquals(1, analysis.latent.components.size)
        assertEquals(StateMapMode.ONE_COMPONENT, content.mode)
        assertTrue(content.points.isNotEmpty())
        assertTrue(content.points.all { it.y == null })
    }

    @Test
    fun unavailableFitsNameTheBroadReasonAndDrawNoPoints() {
        val empty = projectStateMap(dataset(emptyList())).toStateMapContent()
        assertEquals(StateMapMode.UNAVAILABLE, empty.mode)
        assertTrue(empty.points.isEmpty())
        assertEquals(StateMapCopy.NO_STATES, empty.unavailableReason)

        val single = projectStateMap(dataset(listOf(state("only", HOUR, mood = 4)))).toStateMapContent()
        assertEquals(StateMapCopy.INSUFFICIENT_VARIATION, single.unavailableReason)
        assertTrue(single.points.isEmpty())

        val flat = projectStateMap(
            dataset(listOf(state("p", HOUR, mood = 4), state("q", HOUR, mood = 4))),
        ).toStateMapContent()
        assertEquals(StateMapCopy.CONSTANT_HISTORY, flat.unavailableReason)
        assertTrue(flat.points.isEmpty())

        assertEquals(StateMapCopy.COULD_NOT_BUILD, stateMapUnavailableReason(null))
    }

    @Test
    fun explainedVariancePercentsFollowTheRetainedComponents() {
        val analysis = projectStateMap(rectangle().dataset)
        val content = analysis.toStateMapContent()
        analysis.latent.components.forEachIndexed { index, component ->
            assertEquals(component.explainedVarianceRatio * 100.0, content.explainedVariancePercent[index], 1e-9)
        }
        assertTrue(content.explainedVariancePercent.all { it.isFinite() && it >= 0.0 })
    }

    @Test
    fun everyPointResolvesToOneStateAndHidesTheRawId() {
        val hidden = "db-id-should-not-show"
        val analysis = projectStateMap(linkedHistory(latestId = hidden))
        val keys = analysis.points.map { it.key.raw }
        assertEquals(keys.toSet().size, keys.size)
        assertEquals(analysis.points.size, linkedHistory(latestId = hidden).states.size)
        val point = analysis.points.single { it.key.raw == hidden }
        val detail = stateMapDetail(point, ZoneOffset.UTC)
        val visible = listOf(detail.whenLabel, detail.emotion.orEmpty()) + detail.lines.map { "${it.first} ${it.second}" }
        visible.forEach { line -> assertFalse(line.contains(hidden)) }
        assertEquals("Calm", detail.emotion)
    }

    @Test
    fun outgoingContextMarksOnlyStatesThatHaveIt() {
        val analysis = projectStateMap(linkedHistory())
        val origin = analysis.points.single { it.key.raw == "origin" }
        val latest = analysis.points.single { it.key == analysis.toStateMapContent().latestStateKey }
        assertTrue(origin.outgoingContextAvailable)
        assertFalse(latest.outgoingContextAvailable)
        assertEquals("right", latest.key.raw)
        assertTrue(stateMapDetail(latest, ZoneOffset.UTC).accumulating)
        assertFalse(stateMapDetail(origin, ZoneOffset.UTC).accumulating)
    }

    @Test
    fun edgesUseOnlyProjectedEndpointsInStoredOrder() {
        val analysis = projectStateMap(linkedHistory())
        val plotted = analysis.points.map { it.key }.toSet()
        analysis.edges.forEach { edge ->
            assertTrue(edge.fromKey in plotted)
            assertTrue(edge.toKey in plotted)
            val from = analysis.points.single { it.key == edge.fromKey }
            val to = analysis.points.single { it.key == edge.toKey }
            assertTrue(from.recordedAt < to.recordedAt)
        }
        val unprojected = projectStateMap(
            dataset(
                states = listOf(state("s", HOUR, mood = 3), state("e", HOUR, mood = 3)),
                intervals = listOf(interval("e", "s", 0L, HOUR, CheckInContextStartBasis.PREVIOUS_CHECK_IN)),
            ),
        )
        assertTrue(unprojected.edges.isEmpty())
        assertTrue(unprojected.points.isEmpty())
        assertEquals(listOf(OmittedStateEdgeReason.UNPROJECTED), unprojected.omittedEdges.map { it.reason })
    }

    @Test
    fun boundariesAreNotEdgesAndMalformedLinksAreNotReversed() {
        val analysis = projectStateMap(linkedHistory())
        val omitted = analysis.omittedEdges.associate { it.transitionId to it.reason }
        assertEquals(OmittedStateEdgeReason.BOUNDARY, omitted.getValue("wake"))
        assertEquals(OmittedStateEdgeReason.BOUNDARY, omitted.getValue("day"))
        assertEquals(OmittedStateEdgeReason.INVALID_CHRONOLOGY, omitted.getValue("early"))
        assertFalse(analysis.edges.any { it.toKey.raw == "wake" || it.toKey.raw == "day" || it.toKey.raw == "early" })
        assertFalse(analysis.edges.any { it.fromKey.raw == "left" && it.toKey.raw == "early" })
        assertFalse(analysis.edges.any { it.fromKey.raw == "early" && it.toKey.raw == "left" })
    }

    @Test
    fun branchingLinksAreBothDrawn() {
        val edges = projectStateMap(linkedHistory()).edges
        assertTrue(edges.any { it.fromKey.raw == "origin" && it.toKey.raw == "left" })
        assertTrue(edges.any { it.fromKey.raw == "origin" && it.toKey.raw == "right" })
    }

    @Test
    fun displayScalingPreservesRelativePositionAndCentersAZeroRange() {
        val span = mapAxisSpan(listOf(0.0, 10.0))
        val left = mapDisplayFraction(0.0, span)
        val middle = mapDisplayFraction(5.0, span)
        val right = mapDisplayFraction(10.0, span)
        assertEquals((left + right) / 2f, middle, 1e-5f)
        assertEquals(right - middle, middle - left, 1e-5f)
        assertTrue(middle > left && middle < right)
        val flat = mapAxisSpan(listOf(3.0, 3.0))
        assertEquals(0.5f, mapDisplayFraction(3.0, flat), 1e-5f)
        assertTrue(flat.min < 3.0 && flat.max > 3.0)
    }

    @Test
    fun hitTestingChoosesTheNearestPointInsideTheRadius() {
        val points = listOf(
            plot("near", at = 1L, x = 10f, y = 10f),
            plot("far", at = 2L, x = 80f, y = 80f),
        )
        assertEquals("near", selectMapPoint(points, tapX = 16f, tapY = 12f, radius = 20f)?.raw)
        assertNull(selectMapPoint(points, tapX = 16f, tapY = 12f, radius = 2f))
    }

    @Test
    fun anExactOverlapKeepsTheMostRecentPoint() {
        val points = listOf(
            plot("older", at = 1L, x = 5f, y = 5f),
            plot("newer", at = 9L, x = 5f, y = 5f),
        )
        assertEquals("newer", selectMapPoint(points, tapX = 5f, tapY = 5f, radius = 8f)?.raw)
    }

    @Test
    fun aMissingAxisSaysNotRecorded() {
        val detail = stateMapDetail(
            StateMapPoint(
                key = StateOccurrenceKey("hidden-id"),
                recordedAt = HOUR,
                x = 0.0,
                y = null,
                emotion = null,
                mood = null,
                energy = 4,
                confidence = null,
                anxiety = null,
                observedFraction = 0.25,
                outgoingContextAvailable = true,
            ),
            ZoneOffset.UTC,
        )
        assertEquals(StateMapCopy.NOT_RECORDED, detail.lines.single { it.first == "Mood" }.second)
        assertEquals("4", detail.lines.single { it.first == "Energy" }.second)
        assertEquals(StateMapCopy.NOT_RECORDED, detail.lines.single { it.first == "Confidence" }.second)
        assertEquals(StateMapCopy.NOT_RECORDED, detail.lines.single { it.first == "Anxiety" }.second)
        assertFalse(detail.lines.joinToString(" ").contains("hidden-id"))
        assertFalse(detail.accumulating)
    }

    @Test
    fun visibleCopyKeepsTheExperimentalAndNoncausalLanguage() {
        val screen = source(
            "src/main/java/com/orbit/wellness/ui/insights/StateMapSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/StateMapSection.kt",
        ).readText()
        assertEquals("State map (experimental)", StateMapCopy.HEADING)
        assertEquals(
            "A retrospective map of how your logged states differ using your current history. Positions and axes may change as more observations are added.",
            StateMapCopy.SUPPORT,
        )
        assertEquals(
            "This map describes patterns in recorded context. It does not identify causes, diagnoses, or fixed identities.",
            StateMapCopy.DISCLAIMER,
        )
        assertEquals("Context still accumulating", StateMapCopy.ACCUMULATING)
        assertTrue(screen.contains("content.heading"))
        assertTrue(screen.contains("content.support"))
        assertTrue(screen.contains("content.disclaimer"))
        assertTrue(screen.contains("StateMapCopy.ACCUMULATING"))
        assertFalse(screen.contains("INCOMPATIBLE_"))
        assertFalse(screen.contains("Exception"))
        val content = projectStateMap(dataset(emptyList())).toStateMapContent()
        assertEquals(StateMapCopy.HEADING, content.heading)
        assertEquals(StateMapCopy.SUPPORT, content.support)
        assertEquals(StateMapCopy.DISCLAIMER, content.disclaimer)
    }

    @Test
    fun anUnchangedSharedSnapshotDoesNotFitAgain() = runBlocking {
        val fits = AtomicInteger()
        val scope = CoroutineScope(coroutineContext)
        val shared = observeInsightsAnalysis(
            snapshots = flowOf(snapshot(rectangle().dataset)),
            wearables = flowOf(emptyList()),
            computation = Dispatchers.Default,
        ) { dataset, wearables ->
            fits.incrementAndGet()
            projectStateMap(dataset, wearables)
        }.stateIn(scope, SharingStarted.Eagerly, null)
        withTimeout(5_000) {
            while (shared.value == null) delay(20)
        }
        assertEquals(1, fits.get())
        shared.first { it != null }
        delay(40)
        assertEquals(1, fits.get())
    }

    @Test
    fun projectionRunsOffTheCallerAndKeepsOnlyTheLatestResult() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "insights-analysis") }
        try {
            val names = mutableListOf<String>()
            observeInsightsAnalysis(
                snapshots = flowOf(snapshot(twoStates())),
                wearables = flowOf(emptyList()),
                computation = executor.asCoroutineDispatcher(),
            ) { dataset, wearables ->
                names += Thread.currentThread().name
                projectStateMap(dataset, wearables)
            }.first()
            assertTrue(names.single().contains("insights-analysis"))
        } finally {
            executor.shutdown()
        }

        val started = CompletableDeferred<Unit>()
        val releaseNext = CompletableDeferred<Unit>()
        var slowFinished = false
        val reasons = mutableListOf<String?>()
        val job = launch(Dispatchers.Default) {
            observeInsightsAnalysis(
                snapshots = flow {
                    emit(snapshot(dataset(emptyList())))
                    releaseNext.await()
                    emit(snapshot(dataset(listOf(state("only", HOUR, mood = 4)))))
                },
                wearables = flowOf(emptyList()),
                computation = Dispatchers.Default,
            ) { dataset, _ ->
                if (dataset.states.isEmpty()) {
                    started.complete(Unit)
                    delay(30_000)
                    slowFinished = true
                }
                projectStateMap(dataset)
            }.collect { reasons += it.stateMap.unavailableReason }
        }
        withTimeout(5_000) { started.await() }
        releaseNext.complete(Unit)
        withTimeout(5_000) {
            while (reasons.none { it == StateMapCopy.INSUFFICIENT_VARIATION }) delay(20)
        }
        assertFalse(slowFinished)
        assertFalse(reasons.contains(StateMapCopy.NO_STATES))
        job.cancel()
    }

    @Test
    fun missingHeavyHistoryStaysFinite() {
        val analysis = projectStateMap(
            dataset(
                listOf(
                    state("m1", HOUR, mood = null),
                    state("m2", HOUR * 2, mood = null),
                    state("m3", HOUR * 3, mood = 0),
                ),
            ),
        )
        assertTrue(analysis.points.all { it.x.isFinite() && (it.y?.isFinite() != false) && it.observedFraction.isFinite() })
        assertTrue(analysis.latent.components.all { it.explainedVarianceRatio.isFinite() })
        val content = analysis.toStateMapContent()
        assertTrue(content.explainedVariancePercent.all { it.isFinite() })
    }

    @Test
    fun theMapStaysOutsideCausalAndRecommendationPaths() {
        val analysis = projectStateMap(rectangle().dataset)
        assertFalse(analysis.latent.suitableForCausalPrediction)
        assertFalse(analysis.latent.suitableForLiveRecommendation)
        val files = listOf(
            source(
                "src/main/java/com/orbit/wellness/domain/checkin/StateMapAnalysis.kt",
                "app/src/main/java/com/orbit/wellness/domain/checkin/StateMapAnalysis.kt",
            ),
            source(
                "src/main/java/com/orbit/wellness/ui/insights/StateMapSection.kt",
                "app/src/main/java/com/orbit/wellness/ui/insights/StateMapSection.kt",
            ),
            source(
                "src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
                "app/src/main/java/com/orbit/wellness/ui/insights/InsightsViewModel.kt",
            ),
        )
        files.forEach { file ->
            val text = file.readText()
            assertFalse(file.name, text.contains("CausalMultiscaleInput("))
        }
        val viewModel = files[2].readText()
        val fit = viewModel.indexOf("observeInsightsAnalysis")
        val selection = viewModel.indexOf("combine(presentation, selectedKey)")
        assertTrue(fit >= 0 && selection > fit)
        assertFalse(viewModel.contains("projectStateMap"))
    }

    private fun plot(id: String, at: Long, x: Float, y: Float) = MapPlotPoint(
        key = StateOccurrenceKey(id),
        recordedAt = at,
        x = x,
        y = y,
    )

    private fun rectangle(): Held {
        val start = 80L * DAY
        val states = listOf(
            state("a", start, mood = 0, energy = 5),
            state("b", start + DAY, mood = 10, energy = 5),
            state("c", start + 2 * DAY, mood = 5, energy = 0),
            state("d", start + 3 * DAY, mood = 5, energy = 10),
        )
        val intervals = listOf(
            interval("b", "a", start, start + DAY, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            interval("c", "b", start + DAY, start + 2 * DAY, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            interval("d", "c", start + 2 * DAY, start + 3 * DAY, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
        )
        return Held(
            dataset = dataset(states, intervals),
            wearable = WearableObservation.create(
                metric = WearableMetric.STEPS,
                value = 1200.0,
                unit = WearableUnit.COUNT,
                observedStartAt = start + HOUR,
                observedEndAt = start + HOUR,
                temporalKind = WearableTemporalKind.POINT,
                method = WearableMethod.STEPS_RECORD,
                sourceRecordId = "steps-in-window",
                importedAt = start + DAY,
            ),
        )
    }

    private fun twoStates(): CheckInAnalysisDatasetV2 {
        val start = 90L * DAY
        return dataset(
            states = listOf(
                state("earlier", start, mood = 1),
                state("later", start + DAY, mood = 9),
            ),
            intervals = listOf(
                interval("later", "earlier", start, start + DAY, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
    }

    private fun linkedHistory(latestId: String = "right"): CheckInAnalysisDatasetV2 {
        val start = 100L * DAY
        val early = start
        val wake = start + HOUR
        val origin = start + 2 * HOUR
        val left = start + 3 * HOUR
        val day = start + 4 * HOUR
        val right = start + 5 * HOUR
        return dataset(
            states = listOf(
                state("early", early, mood = 1),
                state("wake", wake, mood = 2),
                state("origin", origin, mood = 3, emotion = "Steady"),
                state("left", left, mood = 6),
                state("day", day, mood = 7),
                state(latestId, right, mood = 9, emotion = "Calm"),
            ),
            intervals = listOf(
                interval("wake", null, wake - HOUR, wake, CheckInContextStartBasis.WAKE_TIME),
                interval("day", null, day - HOUR, day, CheckInContextStartBasis.DAY_START),
                interval("early", "left", left, early, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval("left", "origin", origin, left, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
                interval(latestId, "origin", origin, right, CheckInContextStartBasis.PREVIOUS_CHECK_IN),
            ),
        )
    }

    private fun snapshot(dataset: CheckInAnalysisDatasetV2) = CheckInAnalysisSnapshot(
        dataset = dataset,
        summary = summarizeCheckInAnalysis(dataset),
    )

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2> = emptyList(),
    ) = CheckInAnalysisDatasetV2(
        states = states,
        intervals = intervals,
        transitions = emptyList(),
        issues = emptyList(),
    )

    private fun state(
        id: String,
        at: Long,
        mood: Int?,
        energy: Int? = null,
        emotion: String = "",
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val moodReading = reading(DefaultCheckInAxes.MOOD, mood)
        val energyReading = reading(DefaultCheckInAxes.ENERGY, energy)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2099-01-02",
            axes = listOfNotNull(moodReading, energyReading).associateBy { it.axisId },
            core = CoreAxisViewV2(moodReading, energyReading, null, null),
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

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private data class Held(
        val dataset: CheckInAnalysisDatasetV2,
        val wearable: WearableObservation,
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }
}
