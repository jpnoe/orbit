package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class LatentProjectionTest {
    @Test
    fun flatLayoutMatchesFlattenWidthAndOrder() {
        val context = variedContext()
        val layout = flatColumnLayout(context.schema)
        var cursor = 0
        context.schema.retrospective.forEach { segment ->
            segment.features.forEach { feature ->
                val column = layout.columns[cursor]
                assertEquals(cursor, column.flatIndex)
                assertEquals(segment.name, column.segmentName)
                assertEquals(feature.sourceSemantic, column.semantic)
                assertEquals(feature.sourceName, column.sourceName)
                cursor += 1
            }
        }
        assertEquals(cursor, layout.width)
        context.states.forEach { state ->
            assertEquals(layout.width, flatten(state, context.schema).values.size)
        }
    }

    @Test
    fun everyColumnKeepsSegmentAndSemanticMetadata() {
        val layout = flatColumnLayout(variedContext().schema)
        assertTrue(layout.columns.isNotEmpty())
        layout.columns.forEach { column ->
            assertTrue(column.segmentName.isNotBlank())
            assertTrue(column.sourceName.isNotBlank())
            assertTrue(column.segmentWeight.isFinite())
            assertTrue(column.segmentFactor.isFinite())
            assertTrue(column.sourceBlockFactor.isFinite())
        }
        assertEquals("local.self", layout.columns.first().segmentName)
        assertTrue(layout.columns.any { it.semantic == FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD) })
    }

    @Test
    fun observedMeansExcludeMechanicalZeros() {
        val rows = listOf(
            row("a", 1L, listOf(2.0, 0.0), listOf(true, true)),
            row("b", 2L, listOf(4.0, 1.0), listOf(true, true)),
            row("c", 3L, listOf(0.0, 2.0), listOf(false, true)),
        )
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns())
        val column = fitted.columns.single { it.flatIndex == 0 }
        assertEquals(2, column.observedCount)
        assertEquals(1, column.missingCount)
        assertEquals(3.0, column.center, 0.0)
    }

    @Test
    fun anObservedZeroParticipatesInTheCenter() {
        val rows = listOf(
            row("a", 1L, listOf(0.0, 0.0)),
            row("b", 2L, listOf(0.0, 1.0)),
            row("c", 3L, listOf(4.0, 2.0)),
        )
        val column = fitRetrospectiveLatentProjection(rows, twoColumns()).columns.single { it.flatIndex == 0 }
        assertEquals(3, column.observedCount)
        assertEquals(4.0 / 3.0, column.center, 1e-12)
        assertTrue(column.active)
    }

    @Test
    fun missingValuesAreImputedToTheCenter() {
        val rows = listOf(
            row("a", 1L, listOf(2.0, 10.0)),
            row("b", 2L, listOf(4.0, 0.0), listOf(true, false)),
            row("c", 3L, listOf(6.0, 30.0)),
        )
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns())
        val missing = transformRetrospectiveLatent(
            FlatMultiscaleVector(listOf(4.0, 0.0), listOf(true, false)),
            twoColumns(),
            fitted,
        )
        val explicitMean = transformRetrospectiveLatent(
            FlatMultiscaleVector(listOf(4.0, 20.0), listOf(true, true)),
            twoColumns(),
            fitted,
        )
        assertCoordinatesClose(explicitMean.coordinates, missing.coordinates)
        assertCoordinatesClose(listOf(0.0), fitted.states.single { it.key.raw == "b" }.coordinates)
    }

    @Test
    fun constantColumnsStayInspectableButInactive() {
        val layout = FlatColumnLayout(listOf(column(0, "steady"), column(1, "moving"), column(2, "dust")))
        val rows = listOf(
            row("a", 1L, listOf(1.0, 0.0, 1.0)),
            row("b", 2L, listOf(1.0, 1.0, 1.0 + 1e-12)),
            row("c", 3L, listOf(1.0, 2.0, 1.0)),
        )
        val fitted = fitRetrospectiveLatentProjection(rows, layout)
        val steady = fitted.columns.single { it.flatIndex == 0 }
        val dust = fitted.columns.single { it.flatIndex == 2 }
        val moving = fitted.columns.single { it.flatIndex == 1 }
        assertEquals(3, fitted.layout.width)
        assertFalse(steady.active)
        assertEquals(1.0, steady.center, 0.0)
        assertFalse(dust.active)
        assertTrue(moving.active)
        assertFalse(fitted.activeColumnIndexes.contains(0))
        assertFalse(fitted.activeColumnIndexes.contains(2))
    }

    @Test
    fun protocolQualityIdsAndTimestampsStayOutOfTheColumns() {
        val context = variedContext()
        val layout = flatColumnLayout(context.schema)
        val semantics = layout.columns.map { it.semantic }
        assertTrue(semantics.none { it is FeatureSemantic.ProtocolCompleted || it is FeatureSemantic.StartBasis })
        assertTrue(semantics.none { it is FeatureSemantic.CategoryStatus || it is FeatureSemantic.WearableAmbiguous })
        assertTrue(semantics.none { it is FeatureSemantic.AxisObserved || it is FeatureSemantic.AxisConflicting })
        assertTrue(semantics.none { it == FeatureSemantic.EmotionObserved })
        listOf("s1", "s2", "note-not-a-feature", "trigger-not-a-feature").forEach { token ->
            assertTrue(layout.columns.none { it.sourceName.contains(token) })
        }
        val banned = context.states.map { it.recordedAt.toDouble() }.toSet()
        context.states.forEach { state ->
            assertTrue(flatten(state, context.schema).values.none { it in banned })
        }
    }

    @Test
    fun reversedRowOrderKeepsTheModelAndCoordinates() {
        val rows = lineRows()
        val forward = fitRetrospectiveLatentProjection(rows, twoColumns())
        val backward = fitRetrospectiveLatentProjection(rows.reversed(), twoColumns())
        assertEquals(forward, backward)
        assertEquals(
            forward.states.associate { it.key to it.coordinates },
            backward.states.associate { it.key to it.coordinates },
        )
    }

    @Test
    fun aLineOfPointsHasOneDominantComponent() {
        val fitted = fitRetrospectiveLatentProjection(lineRows(), twoColumns(), maxComponents = 2)
        assertEquals(LatentFitStatus.FITTED, fitted.status)
        assertEquals(1, fitted.components.size)
        val loading = fitted.components.single().loadings
        assertEquals(1.0, sqrt(loading.sumOf { it * it }), 1e-8)
        assertTrue(loading[0] > 0.0 && loading[1] > 0.0)
        assertEquals(loading[0], loading[1], 1e-8)
        assertTrue(fitted.components.single().explainedVarianceRatio > 0.99)
    }

    @Test
    fun orthogonalVariationKeepsDescendingComponents() {
        val rows = listOf(
            row("a", 1L, listOf(1.0, 0.0)),
            row("b", 2L, listOf(-1.0, 0.0)),
            row("c", 3L, listOf(0.0, 2.0)),
            row("d", 4L, listOf(0.0, -2.0)),
        )
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns(), maxComponents = 2)
        assertEquals(2, fitted.components.size)
        val first = fitted.components[0]
        val second = fitted.components[1]
        assertTrue(abs(first.loadings[1]) > abs(first.loadings[0]))
        assertTrue(first.loadings[1] > 0.0)
        assertTrue(abs(second.loadings[0]) > abs(second.loadings[1]))
        assertTrue(second.loadings[0] > 0.0)
        assertTrue(first.explainedVariance > second.explainedVariance)
        assertEquals(0.8, first.explainedVarianceRatio, 1e-8)
        assertEquals(0.2, second.explainedVarianceRatio, 1e-8)
    }

    @Test
    fun componentLoadingsHaveUnitLength() {
        fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns()).components.forEach { component ->
            val norm = sqrt(component.loadings.sumOf { it * it })
            assertEquals(1.0, norm, 1e-8)
        }
    }

    @Test
    fun componentsAreOrthogonal() {
        val components = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns()).components
        val dot = components[0].loadings.zip(components[1].loadings).sumOf { (left, right) -> left * right }
        assertEquals(0.0, dot, 1e-8)
    }

    @Test
    fun signOrientationMakesTheLargestLoadingPositive() {
        val flipped = doubleArrayOf(-0.2, 0.1)
        orientLoading(flipped)
        assertEquals(0.2, flipped[0], 0.0)
        assertEquals(-0.1, flipped[1], 0.0)
        val tied = doubleArrayOf(-0.5, 0.5)
        orientLoading(tied)
        assertEquals(0.5, tied[0], 0.0)
        assertEquals(-0.5, tied[1], 0.0)
        fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns()).components.forEach { component ->
            val chosen = component.loadings.withIndex().maxWith(
                compareBy<IndexedValue<Double>> { abs(it.value) }.thenByDescending { it.index },
            )
            assertTrue(chosen.value > 0.0)
        }
    }

    @Test
    fun explainedVarianceIsFiniteAndNormalized() {
        val fitted = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns())
        assertEquals(LATENT_COVARIANCE_DIVISOR, fitted.covarianceDivisor)
        assertTrue(fitted.totalVariance > 0.0)
        fitted.components.forEach { component ->
            assertTrue(component.gramEigenvalue.isFinite() && component.gramEigenvalue > 0.0)
            assertTrue(component.explainedVariance.isFinite() && component.explainedVariance > 0.0)
            assertEquals(
                component.gramEigenvalue / fitted.trainingOccurrenceCount,
                component.explainedVariance,
                1e-12,
            )
            assertEquals(component.explainedVariance / fitted.totalVariance, component.explainedVarianceRatio, 1e-12)
        }
    }

    @Test
    fun explainedVarianceRatiosSumAcrossRetainedComponents() {
        val fitted = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns(), maxComponents = 4)
        val ratio = fitted.components.sumOf { it.explainedVarianceRatio }
        assertEquals(1.0, ratio, 1e-8)
        assertTrue(fitted.components.all { it.explainedVarianceRatio >= 0.0 })
    }

    @Test
    fun oneStateDoesNotInventACoordinate() {
        val fitted = fitRetrospectiveLatentProjection(listOf(row("only", 1L, listOf(1.0, 2.0))), twoColumns())
        assertEquals(LatentFitStatus.UNAVAILABLE, fitted.status)
        assertTrue(fitted.components.isEmpty())
        assertEquals(LatentDiagnosticCode.INSUFFICIENT_STATES, fitted.diagnostics.single().code)
        val state = fitted.states.single()
        assertTrue(state.coordinates.isEmpty())
        assertEquals(LatentDiagnosticCode.INSUFFICIENT_STATES, state.exclusionReason)
        assertEquals(1.0, fitted.columns.single { it.flatIndex == 0 }.center, 0.0)
    }

    @Test
    fun twoDistinctStatesKeepAtMostOneComponent() {
        val rows = listOf(row("a", 1L, listOf(0.0, 0.0)), row("b", 2L, listOf(1.0, 2.0)))
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns(), maxComponents = 4)
        assertEquals(1, fitted.components.size)
        assertTrue(fitted.states.all { it.coordinates.size == 1 })
        assertTrue(fitted.diagnostics.any { it.code == LatentDiagnosticCode.RANK_LIMITED })
    }

    @Test
    fun identicalStatesHaveNoRank() {
        val rows = List(4) { index -> row("s$index", index.toLong(), listOf(1.0, 1.0)) }
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns())
        assertEquals(LatentFitStatus.UNAVAILABLE, fitted.status)
        assertTrue(fitted.components.isEmpty())
        assertEquals(LatentDiagnosticCode.NO_ACTIVE_COLUMNS, fitted.diagnostics.single().code)
        assertTrue(fitted.states.all { it.coordinates.isEmpty() })
    }

    @Test
    fun requestedComponentCountIsBoundedByRank() {
        val fitted = fitRetrospectiveLatentProjection(lineRows(), twoColumns(), maxComponents = 5)
        assertEquals(1, fitted.components.size)
        assertTrue(fitted.components.size <= minOf(5, lineRows().size - 1))
        assertEquals(5, fitted.requestedComponentCount)
        assertTrue(fitted.diagnostics.any { it.code == LatentDiagnosticCode.RANK_LIMITED })
    }

    @Test
    fun transformingTrainingVectorsReproducesCoordinates() {
        val rows = orthogonalRows()
        val fitted = fitRetrospectiveLatentProjection(rows, twoColumns())
        rows.forEach { source ->
            val transformed = transformRetrospectiveLatent(source.vector, twoColumns(), fitted)
            val stored = fitted.states.single { it.key == source.key }.coordinates
            assertCoordinatesClose(stored, transformed.coordinates)
        }
    }

    @Test
    fun aCompatibleVectorTransformsWithoutRefitting() {
        val fitted = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns())
        val before = fitted.components.map { it.loadings }
        val transformed = transformRetrospectiveLatent(
            FlatMultiscaleVector(listOf(0.25, -0.5), listOf(true, true)),
            twoColumns(),
            fitted,
        )
        assertEquals(before, fitted.components.map { it.loadings })
        assertEquals(fitted.components.size, transformed.coordinates.size)
        assertNull(transformed.exclusionReason)
        assertTrue(transformed.coordinates.all { it.isFinite() })
    }

    @Test
    fun anIncompatibleLayoutFailsExplicitly() {
        val fitted = fitRetrospectiveLatentProjection(lineRows(), twoColumns())
        val grown = FlatColumnLayout(twoColumns().columns + column(2, "extra"))
        val failure = runCatching {
            transformRetrospectiveLatent(
                FlatMultiscaleVector(listOf(0.0, 0.0, 0.0), listOf(true, true, true)),
                grown,
                fitted,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains(LatentDiagnosticCode.INCOMPATIBLE_LAYOUT.name))
        val reordered = FlatColumnLayout(listOf(column(0, "right"), column(1, "left")))
        val swapped = runCatching {
            transformRetrospectiveLatent(
                FlatMultiscaleVector(listOf(0.0, 0.0), listOf(true, true)),
                reordered,
                fitted,
            )
        }.exceptionOrNull()
        assertTrue(swapped is IllegalArgumentException)
        assertTrue(swapped!!.message!!.contains(LatentDiagnosticCode.INCOMPATIBLE_LAYOUT.name))
    }

    @Test
    fun theSameMoodWithDifferentTemporalContextCanSeparate() {
        val context = variedContext()
        val fitted = fitRetrospectiveLatentProjection(context, maxComponents = 8)
        val schema = context.schema
        val mood = flatColumnLayout(schema).columns.indexOfFirst { column ->
            column.segmentName == "local.self" &&
                column.semantic == FeatureSemantic.AxisValue(DefaultCheckInAxes.MOOD)
        }
        val second = context.states.single { it.key.raw == "s2" }
        val third = context.states.single { it.key.raw == "s3" }
        val secondFlat = flatten(second, schema)
        val thirdFlat = flatten(third, schema)
        assertEquals(secondFlat.values[mood], thirdFlat.values[mood], 1e-12)
        assertTrue(secondFlat.observed[mood] && thirdFlat.observed[mood])
        assertNotEquals(secondFlat.values, thirdFlat.values)
        val left = fitted.states.single { it.key.raw == "s2" }.coordinates
        val right = fitted.states.single { it.key.raw == "s3" }.coordinates
        assertTrue(left.isNotEmpty() && right.isNotEmpty())
        assertNotEquals(left, right)
    }

    @Test
    fun aMissingHeavyVectorKeepsCoverageAndFiniteCoordinates() {
        val fitted = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns())
        val transformed = transformRetrospectiveLatent(
            FlatMultiscaleVector(listOf(1.0, 0.0), listOf(true, false)),
            twoColumns(),
            fitted,
        )
        assertEquals(1, transformed.observedColumnCount)
        assertEquals(0.5, transformed.observedFraction, 0.0)
        assertNull(transformed.exclusionReason)
        assertTrue(transformed.coordinates.all { it.isFinite() })
        assertEquals(fitted.components.size, transformed.coordinates.size)
    }

    @Test
    fun changingStateKeysDoesNotChangeTheGeometry() {
        val times = listOf(10L, 20L, 30L, 40L)
        val values = orthogonalRows().map { it.vector }
        val named = times.zip(values).mapIndexed { index, (time, vector) ->
            LatentSourceRow(StateOccurrenceKey("alpha-$index"), time, vector)
        }
        val renamed = times.zip(values).mapIndexed { index, (time, vector) ->
            LatentSourceRow(StateOccurrenceKey("beta-$index"), time, vector)
        }
        val first = fitRetrospectiveLatentProjection(named, twoColumns())
        val second = fitRetrospectiveLatentProjection(renamed, twoColumns())
        assertEquals(first.components.map { it.loadings }, second.components.map { it.loadings })
        assertEquals(first.components.map { it.explainedVariance }, second.components.map { it.explainedVariance })
        assertEquals(
            first.states.map { it.coordinates },
            second.states.map { it.coordinates },
        )
        assertNotEquals(first.fittedStateKeyOrder, second.fittedStateKeyOrder)
    }

    @Test
    fun topLoadingsAreDeterministicAndPointAtRealColumns() {
        val fitted = fitRetrospectiveLatentProjection(orthogonalRows(), twoColumns())
        val again = fitRetrospectiveLatentProjection(orthogonalRows().reversed(), twoColumns())
        assertEquals(
            fitted.components.map { it.positiveLoadings to it.negativeLoadings },
            again.components.map { it.positiveLoadings to it.negativeLoadings },
        )
        fitted.components.forEach { component ->
            assertOrderedByMagnitude(component.positiveLoadings)
            assertOrderedByMagnitude(component.negativeLoadings)
            (component.positiveLoadings + component.negativeLoadings).forEach { contribution ->
                val column = fitted.layout.columns[contribution.flatIndex]
                assertEquals(column.semantic, contribution.semantic)
                assertEquals(column.segmentName, contribution.segmentName)
                assertEquals(column.sourceName, contribution.sourceName)
                assertEquals(contribution.loading, component.loadings[contribution.flatIndex], 0.0)
            }
        }
    }

    @Test
    fun latentNumbersStayFinite() {
        val fitted = fitRetrospectiveLatentProjection(variedContext(), maxComponents = 4)
        assertTrue(fitted.totalVariance.isFinite())
        fitted.columns.forEach { column ->
            assertTrue(column.center.isFinite() && column.deviation.isFinite() && column.variance.isFinite())
        }
        fitted.components.forEach { component ->
            assertTrue(component.gramEigenvalue.isFinite() && component.explainedVariance.isFinite())
            assertTrue(component.explainedVarianceRatio.isFinite())
            assertTrue(component.loadings.all { it.isFinite() })
        }
        fitted.states.forEach { state -> assertTrue(state.coordinates.all { it.isFinite() }) }
    }

    @Test
    fun theModelIsMarkedRetrospectiveAndNotACausalInput() {
        val fitted = fitRetrospectiveLatentProjection(lineRows(), twoColumns())
        assertEquals(RETROSPECTIVE_LATENT_PROTOCOL, fitted.protocol)
        assertEquals(LatentProjectionScope.RETROSPECTIVE_DESCRIPTIVE, fitted.scope)
        assertFalse(fitted.suitableForCausalPrediction)
        assertFalse(fitted.suitableForLiveRecommendation)
        assertFalse(fitted.stableAcrossRefits)
        assertTrue(fitted.datasetRelative)
        assertFalse(fitted.degenerateSubspacesStableAcrossRefits)
        assertTrue(fitted.components.all { it.mathematicalContributorsOnly })
    }

    @Test
    fun noStatesAreUnavailable() {
        val fitted = fitRetrospectiveLatentProjection(emptyList(), twoColumns())
        assertEquals(LatentFitStatus.UNAVAILABLE, fitted.status)
        assertEquals(LatentDiagnosticCode.NO_STATES, fitted.diagnostics.single().code)
        assertTrue(fitted.states.isEmpty())
        assertTrue(fitted.components.isEmpty())
    }

    @Test
    fun jacobiRecoversAKnownSymmetricMatrix() {
        val matrix = arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0))
        val eigen = symmetricJacobi(matrix, maxRotations = 20)
        assertTrue(eigen.converged)
        val values = eigen.pairs.map { it.eigenvalue }.sorted()
        assertEquals(0.5, values[0], 1e-8)
        assertEquals(1.5, values[1], 1e-8)
        eigen.pairs.forEach { pair ->
            val transformed = DoubleArray(2)
            for (row in 0 until 2) {
                transformed[row] = matrix[row][0] * pair.vector[0] + matrix[row][1] * pair.vector[1]
            }
            assertEquals(pair.eigenvalue * pair.vector[0], transformed[0], 1e-8)
            assertEquals(pair.eigenvalue * pair.vector[1], transformed[1], 1e-8)
        }
    }

    @Test
    fun aFailedDecompositionDoesNotEmitCoordinates() {
        val fitted = fitRetrospectiveLatent(
            rows = orthogonalRows(),
            layout = twoColumns(),
            maxComponents = 2,
            maxRotations = 0,
        )
        assertEquals(LatentFitStatus.UNAVAILABLE, fitted.status)
        assertEquals(LatentDiagnosticCode.DECOMPOSITION_FAILED, fitted.diagnostics.single().code)
        assertTrue(fitted.components.isEmpty())
        assertTrue(fitted.states.all { it.coordinates.isEmpty() && it.exclusionReason == LatentDiagnosticCode.DECOMPOSITION_FAILED })
    }

    private fun assertOrderedByMagnitude(loadings: List<LatentLoadingContribution>) {
        loadings.zipWithNext().forEach { (earlier, later) ->
            val magnitude = abs(earlier.loading).compareTo(abs(later.loading))
            assertTrue(magnitude > 0 || (magnitude == 0 && earlier.flatIndex < later.flatIndex))
        }
    }

    private fun assertCoordinatesClose(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertEquals(left, right, 1e-8) }
    }
}

private fun orthogonalRows(): List<LatentSourceRow> {
    return listOf(
        row("a", 1L, listOf(1.0, 0.0)),
        row("b", 2L, listOf(-1.0, 0.0)),
        row("c", 3L, listOf(0.0, 2.0)),
        row("d", 4L, listOf(0.0, -2.0)),
    )
}

private fun lineRows(): List<LatentSourceRow> {
    return (0..3).map { index -> row("p$index", index.toLong() + 1L, listOf(index.toDouble(), index.toDouble())) }
}

private fun twoColumns() = FlatColumnLayout(listOf(column(0, "left"), column(1, "right")))

private fun column(index: Int, name: String) = FlatColumn(
    flatIndex = index,
    segmentName = "local.self",
    semantic = FeatureSemantic.AxisValue(name),
    sourceName = "state.axis.$name.value",
    sourceIndex = index,
    sourceBlock = FeatureBlock.STATE_AXIS_VALUE,
    substantiveBlock = SubstantiveBlock.STATE_AXES,
    scaling = FeatureScaling.STANDARDIZE,
    segmentWeight = MULTISCALE_SEGMENT_WEIGHT,
    segmentFactor = 1.0,
    segmentEffectiveBlockCount = 1,
    sourceBlockWeight = EQUAL_BLOCK_WEIGHT,
    sourceBlockFactor = 1.0,
    nonvaryingInPreparation = false,
)

private fun row(
    id: String,
    at: Long,
    values: List<Double>,
    observed: List<Boolean> = List(values.size) { true },
) = LatentSourceRow(StateOccurrenceKey(id), at, FlatMultiscaleVector(values, observed))

private fun variedContext(): MultiscaleContext {
    val hour = 3_600_000L
    val start = 30L * 24L * hour
    val states = listOf(
        latentState("s1", start, mood = 0.0),
        latentState("s2", start + hour, mood = 0.2),
        latentState("s3", start + 2 * hour, mood = 0.2),
        latentState("s4", start + 3 * hour, mood = 0.8),
    )
    val evidence = ContextualEvidence(
        states = states,
        transitions = emptyList(),
        statesByKey = states.associateBy { it.key },
        transitionsByKey = emptyMap(),
        wearablesById = emptyMap(),
        retrospectiveStates = emptyList(),
        retrospectiveTransitions = emptyList(),
        causalExamples = emptyList(),
        issues = emptyList(),
    )
    return projectMultiscale(evidence, projectLocalContext(evidence, encodeFeatures(evidence)))
}

private fun latentState(id: String, at: Long, mood: Double) = StateEvidenceFrame(
    key = StateOccurrenceKey(id),
    evidence = StateEvidencePayload(
        axes = listOf(AxisEvidence(DefaultCheckInAxes.MOOD, (mood * 10).toInt(), mood)),
        coreMissingAxisIds = emptyList(),
        conflictingAxisIds = emptyList(),
        emotion = null,
    ),
    metadata = StateEvidenceMetadata(
        recordedAt = at,
        localDate = "2099-01-02",
        trigger = "trigger-not-a-feature",
        note = "note-not-a-feature",
    ),
)
