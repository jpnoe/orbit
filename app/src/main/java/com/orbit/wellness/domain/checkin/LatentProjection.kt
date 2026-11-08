package com.orbit.wellness.domain.checkin

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Retrospective descriptive PCA over [flatten].
 *
 * The fit reads retrospective multiscale states only. Coordinates from this
 * protocol are not legal input for [CausalMultiscaleInput], live recommendations,
 * or a later causal claim. A refit on another dataset can rotate a near-degenerate
 * subspace; component signs are fixed only within one fit.
 */
const val RETROSPECTIVE_LATENT_PROTOCOL = "retrospective-multiscale-pca-v1"

/** Default requested component count. Rank may retain fewer. */
const val DEFAULT_LATENT_COMPONENT_COUNT = 2

/**
 * A Gram eigenvalue is kept when its population variance exceeds this fraction
 * of the active matrix's total population variance. The fraction matches the
 * order of [NUMERIC_DEVIATION_TOLERANCE]. It does not clip ordinary components.
 */
const val LATENT_SPECTRUM_TOLERANCE = 1.0e-8

/**
 * Population covariance. A Gram eigenvalue is divided by the training row count
 * `n`, the same divisor [fitFeaturePreparation] uses for a root-mean-square
 * deviation. This is not the `n - 1` sample covariance.
 */
const val LATENT_COVARIANCE_DIVISOR = "n"

enum class LatentProjectionScope {
    /**
     * Dataset-relative description of retrospective multiscale states.
     * Not causal, not diagnostic, and not stable across refits.
     */
    RETROSPECTIVE_DESCRIPTIVE,
}

enum class LatentFitStatus {
    FITTED,
    UNAVAILABLE,
}

enum class LatentDiagnosticCode {
    NO_STATES,
    INSUFFICIENT_STATES,
    NO_ACTIVE_COLUMNS,
    ZERO_RANK,
    RANK_LIMITED,
    NONFINITE_SOURCE_VALUE,
    DECOMPOSITION_FAILED,
    INCOMPATIBLE_LAYOUT,
}

data class LatentDiagnostic(
    val code: LatentDiagnosticCode,
    val detail: String,
)

/**
 * One coordinate of [flatten], in that function's segment order.
 * Occurrence ids and timestamps are not columns.
 */
data class FlatColumn(
    val flatIndex: Int,
    val segmentName: String,
    val semantic: FeatureSemantic,
    val sourceName: String,
    val sourceIndex: Int,
    val sourceBlock: FeatureBlock,
    val substantiveBlock: SubstantiveBlock?,
    val scaling: FeatureScaling,
    val segmentWeight: Double,
    val segmentFactor: Double,
    val segmentEffectiveBlockCount: Int,
    val sourceBlockWeight: Double,
    val sourceBlockFactor: Double,
    val nonvaryingInPreparation: Boolean,
)

data class FlatColumnLayout(val columns: List<FlatColumn>) {
    init {
        columns.forEachIndexed { index, column ->
            require(column.flatIndex == index) { "Flat column ${column.sourceName} is not at $index" }
        }
    }

    val width: Int
        get() = columns.size
}

data class LatentColumnStatistic(
    val flatIndex: Int,
    val observedCount: Int,
    val missingCount: Int,
    val center: Double,
    val deviation: Double,
    val variance: Double,
    val active: Boolean,
)

/**
 * One loading in a component. This is a mathematical contributor to variance
 * in the fitted dataset. It is not a cause, a diagnosis, or a stable name.
 */
data class LatentLoadingContribution(
    val flatIndex: Int,
    val loading: Double,
    val segmentName: String,
    val semantic: FeatureSemantic,
    val sourceName: String,
    val substantiveBlock: SubstantiveBlock?,
)

data class LatentComponent(
    val index: Int,
    /** Unit loading in the full flat layout. Inactive columns are zero. */
    val loadings: List<Double>,
    /** Eigenvalue of the sample Gram matrix, before dividing by [LATENT_COVARIANCE_DIVISOR]. */
    val gramEigenvalue: Double,
    /** [gramEigenvalue] / n. This is the population variance of the scores. */
    val explainedVariance: Double,
    /** [explainedVariance] / total population variance of the active matrix. */
    val explainedVarianceRatio: Double,
    /** Nonzero positive loadings, largest magnitude first, then lowest flat index. */
    val positiveLoadings: List<LatentLoadingContribution>,
    /** Nonzero negative loadings, largest magnitude first, then lowest flat index. */
    val negativeLoadings: List<LatentLoadingContribution>,
    val mathematicalContributorsOnly: Boolean = true,
) {
    init {
        require(mathematicalContributorsOnly) { "Component loadings are not causal explanations" }
        require(gramEigenvalue.isFinite() && gramEigenvalue > 0.0) { "A component needs a positive Gram eigenvalue" }
        require(explainedVariance.isFinite() && explainedVariance > 0.0) { "Explained variance must be positive" }
        require(explainedVarianceRatio.isFinite() && explainedVarianceRatio >= 0.0) {
            "Explained-variance ratio must be finite"
        }
        require(loadings.all { it.isFinite() }) { "A loading must be finite" }
    }
}

data class LatentStateCoordinate(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    /** Empty when this row was not placed in a fitted component space. */
    val coordinates: List<Double>,
    val observedColumnCount: Int,
    val observedFraction: Double,
    val exclusionReason: LatentDiagnosticCode?,
) {
    init {
        require(coordinates.all { it.isFinite() }) { "A latent coordinate must be finite" }
        require(observedFraction.isFinite()) { "Observed fraction must be finite" }
    }
}

data class LatentTransform(
    val coordinates: List<Double>,
    val observedColumnCount: Int,
    val observedFraction: Double,
    val exclusionReason: LatentDiagnosticCode?,
) {
    init {
        require(coordinates.all { it.isFinite() }) { "A transformed coordinate must be finite" }
        require(observedFraction.isFinite()) { "Observed fraction must be finite" }
    }
}

data class RetrospectiveLatentProjection(
    val protocol: String = RETROSPECTIVE_LATENT_PROTOCOL,
    val scope: LatentProjectionScope = LatentProjectionScope.RETROSPECTIVE_DESCRIPTIVE,
    val suitableForCausalPrediction: Boolean = false,
    val suitableForLiveRecommendation: Boolean = false,
    val datasetRelative: Boolean = true,
    val stableAcrossRefits: Boolean = false,
    /** Near-equal eigenvalues can rotate when the dataset changes. This fit does not remove that. */
    val degenerateSubspacesStableAcrossRefits: Boolean = false,
    val covarianceDivisor: String = LATENT_COVARIANCE_DIVISOR,
    val status: LatentFitStatus,
    val layout: FlatColumnLayout,
    val columns: List<LatentColumnStatistic>,
    val activeColumnIndexes: List<Int>,
    val components: List<LatentComponent>,
    val totalVariance: Double,
    val trainingOccurrenceCount: Int,
    val fittedStateKeyOrder: List<StateOccurrenceKey>,
    val requestedComponentCount: Int,
    val diagnostics: List<LatentDiagnostic>,
    val states: List<LatentStateCoordinate>,
) {
    init {
        require(protocol == RETROSPECTIVE_LATENT_PROTOCOL) { "Unexpected latent protocol $protocol" }
        require(scope == LatentProjectionScope.RETROSPECTIVE_DESCRIPTIVE) {
            "This projection is retrospective and descriptive"
        }
        require(!suitableForCausalPrediction && !suitableForLiveRecommendation && !stableAcrossRefits) {
            "Retrospective coordinates are not causal, recommendation, or refit-stable input"
        }
        require(datasetRelative && !degenerateSubspacesStableAcrossRefits) {
            "The fit is dataset-relative and does not stabilize a degenerate subspace"
        }
        require(covarianceDivisor == LATENT_COVARIANCE_DIVISOR) { "Covariance divisor must be n" }
        require(columns.size == layout.width) { "Column statistics must cover the flat layout" }
        require(components.all { it.loadings.size == layout.width }) { "Loadings must cover the flat layout" }
        require(totalVariance.isFinite() && totalVariance >= 0.0) { "Total variance must be finite" }
        require(activeColumnIndexes == activeColumnIndexes.sorted()) { "Active columns stay in layout order" }
        if (status == LatentFitStatus.FITTED) {
            require(components.isNotEmpty()) { "A fitted projection needs a component" }
        } else {
            require(components.isEmpty()) { "An unavailable fit has no components" }
        }
    }
}

data class LatentSourceRow(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    val vector: FlatMultiscaleVector,
)

/**
 * Column metadata in the same order as [flatten]. The flat vector itself stores
 * only values and masks; this layout is the inspectable side of that contract.
 */
fun flatColumnLayout(schema: MultiscaleSchema): FlatColumnLayout {
    val columns = ArrayList<FlatColumn>()
    schema.retrospective.forEach { segment ->
        segment.features.forEach { feature ->
            columns += FlatColumn(
                flatIndex = columns.size,
                segmentName = segment.name,
                semantic = feature.sourceSemantic,
                sourceName = feature.sourceName,
                sourceIndex = feature.sourceIndex,
                sourceBlock = feature.sourceBlock,
                substantiveBlock = feature.substantiveBlock,
                scaling = feature.scaling,
                segmentWeight = segment.weight,
                segmentFactor = segment.factor,
                segmentEffectiveBlockCount = segment.effectiveBlockCount,
                sourceBlockWeight = feature.blockWeight,
                sourceBlockFactor = feature.blockFactor,
                nonvaryingInPreparation = feature.nonvarying,
            )
        }
    }
    return FlatColumnLayout(columns)
}

/**
 * Fits [RETROSPECTIVE_LATENT_PROTOCOL] on [context]'s retrospective states.
 * Causal examples are not read and must not be given these coordinates.
 */
fun fitRetrospectiveLatentProjection(
    context: MultiscaleContext,
    maxComponents: Int = DEFAULT_LATENT_COMPONENT_COUNT,
): RetrospectiveLatentProjection {
    val layout = flatColumnLayout(context.schema)
    val rows = context.states.map { state ->
        LatentSourceRow(state.key, state.recordedAt, flatten(state, context.schema))
    }
    return fitRetrospectiveLatentProjection(rows, layout, maxComponents)
}

/**
 * Fits a centered PCA model. Rows are ordered by [LatentSourceRow.recordedAt],
 * then by state key, before any matrix is built.
 *
 * The decomposition is a Jacobi rotation of the `n × n` Gram matrix `X Xᵀ`.
 * `X` has one row per training state and one column per active flat coordinate.
 * Dividing a Gram eigenvalue by `n` is the population variance. No second
 * standardization and no per-row normalization is applied.
 */
fun fitRetrospectiveLatentProjection(
    rows: List<LatentSourceRow>,
    layout: FlatColumnLayout,
    maxComponents: Int = DEFAULT_LATENT_COMPONENT_COUNT,
): RetrospectiveLatentProjection {
    return fitRetrospectiveLatent(
        rows = rows,
        layout = layout,
        maxComponents = maxComponents,
        maxRotations = defaultJacobiRotations(rows.size),
    )
}

internal fun fitRetrospectiveLatent(
    rows: List<LatentSourceRow>,
    layout: FlatColumnLayout,
    maxComponents: Int,
    maxRotations: Int,
): RetrospectiveLatentProjection {
    require(maxComponents >= 1) { "Request at least one latent component" }
    rows.forEach { row ->
        require(row.vector.values.size == layout.width) {
            "INCOMPATIBLE_LAYOUT: training vector ${row.key.raw} does not match the flat layout"
        }
    }
    val ordered = rows.sortedWith(compareBy({ it.recordedAt }, { it.key.raw }))
    val nonfinite = ordered.any { row ->
        row.vector.values.indices.any { index ->
            row.vector.observed[index] && !row.vector.values[index].isFinite()
        }
    }
    if (nonfinite) {
        return unavailable(
            layout = layout,
            ordered = ordered,
            columns = inactiveStatistics(layout, ordered.size),
            requested = maxComponents,
            reason = LatentDiagnostic(
                LatentDiagnosticCode.NONFINITE_SOURCE_VALUE,
                "An observed flat value was not finite",
            ),
        )
    }
    val statistics = columnStatistics(ordered, layout)
    if (ordered.isEmpty()) {
        return unavailable(
            layout, ordered, statistics, maxComponents,
            LatentDiagnostic(LatentDiagnosticCode.NO_STATES, "The retrospective fit has no states"),
        )
    }
    if (ordered.size < 2) {
        return unavailable(
            layout, ordered, statistics, maxComponents,
            LatentDiagnostic(
                LatentDiagnosticCode.INSUFFICIENT_STATES,
                "One state has no variance to estimate",
            ),
        )
    }
    val active = statistics.filter { it.active }
    if (active.isEmpty()) {
        return unavailable(
            layout, ordered, statistics, maxComponents,
            LatentDiagnostic(
                LatentDiagnosticCode.NO_ACTIVE_COLUMNS,
                "No flat column varies enough to enter PCA, so the fit has rank zero",
            ),
        )
    }
    val centered = centeredMatrix(ordered, active)
    val gram = gramMatrix(centered)
    val eigen = symmetricJacobi(gram, maxRotations)
    if (!eigen.converged) {
        return unavailable(
            layout, ordered, statistics, maxComponents,
            LatentDiagnostic(
                LatentDiagnosticCode.DECOMPOSITION_FAILED,
                "Jacobi rotation did not converge",
            ),
        )
    }
    val totalVariance = eigen.pairs.sumOf { it.eigenvalue } / ordered.size.toDouble()
    val meaningful = eigen.pairs
        .filter { pair ->
            val variance = pair.eigenvalue / ordered.size.toDouble()
            variance.isFinite() && totalVariance > 0.0 && variance > LATENT_SPECTRUM_TOLERANCE * totalVariance
        }
        .sortedWith(compareByDescending<SymmetricEigenpair> { it.eigenvalue }.thenBy { it.ordinal })
    val cap = minOf(maxComponents, ordered.size - 1, active.size)
    val retained = meaningful.take(cap)
    if (retained.isEmpty()) {
        return unavailable(
            layout, ordered, statistics, maxComponents,
            LatentDiagnostic(
                LatentDiagnosticCode.ZERO_RANK,
                "Active columns produced no meaningful positive eigenvalue",
            ),
        )
    }
    val components = retained.mapIndexed { index, pair ->
        component(
            index = index,
            pair = pair,
            centered = centered,
            active = active,
            layout = layout,
            rowCount = ordered.size,
            totalVariance = totalVariance,
        )
    }
    val diagnostics = mutableListOf<LatentDiagnostic>()
    if (components.size < maxComponents) {
        diagnostics += LatentDiagnostic(
            code = LatentDiagnosticCode.RANK_LIMITED,
            detail = "Retained ${components.size} of $maxComponents requested components; " +
                "the centered rank is at most ${ordered.size - 1}",
        )
    }
    val coordinates = ordered.map { row ->
        val vector = row.vector
        coordinate(
            key = row.key,
            recordedAt = row.recordedAt,
            vector = vector,
            statistics = statistics,
            components = components,
            exclusion = null,
        )
    }
    return RetrospectiveLatentProjection(
        status = LatentFitStatus.FITTED,
        layout = layout,
        columns = statistics,
        activeColumnIndexes = active.map { it.flatIndex },
        components = components,
        totalVariance = totalVariance,
        trainingOccurrenceCount = ordered.size,
        fittedStateKeyOrder = ordered.map { it.key },
        requestedComponentCount = maxComponents,
        diagnostics = diagnostics,
        states = coordinates,
    )
}

/**
 * Projects one compatible flat vector with the fitted centers and loadings.
 * A different width, segment order, semantic, or factor fails instead of being
 * truncated or reordered. The model is not refit.
 */
fun transformRetrospectiveLatent(
    vector: FlatMultiscaleVector,
    layout: FlatColumnLayout,
    projection: RetrospectiveLatentProjection,
): LatentTransform {
    requireCompatibleLatentLayout(layout, projection.layout)
    require(vector.values.size == layout.width) {
        "INCOMPATIBLE_LAYOUT: vector width ${vector.values.size} does not match ${layout.width}"
    }
    val coverage = coverageOf(vector)
    if (projection.status != LatentFitStatus.FITTED) {
        return LatentTransform(
            coordinates = emptyList(),
            observedColumnCount = coverage.first,
            observedFraction = coverage.second,
            exclusionReason = projection.diagnostics.firstOrNull()?.code
                ?: LatentDiagnosticCode.ZERO_RANK,
        )
    }
    val scores = projection.components.map { component ->
        score(vector, projection.columns, component.loadings)
    }
    return LatentTransform(
        coordinates = scores,
        observedColumnCount = coverage.first,
        observedFraction = coverage.second,
        exclusionReason = null,
    )
}

fun requireCompatibleLatentLayout(actual: FlatColumnLayout, expected: FlatColumnLayout) {
    require(actual.width == expected.width) {
        "INCOMPATIBLE_LAYOUT: width ${actual.width} does not match fitted width ${expected.width}"
    }
    actual.columns.zip(expected.columns).forEach { (left, right) ->
        require(left.sameCoordinate(right)) {
            "INCOMPATIBLE_LAYOUT: column ${left.flatIndex} (${left.segmentName}/${left.sourceName}) " +
                "does not match ${right.segmentName}/${right.sourceName}"
        }
    }
}

/**
 * Largest-magnitude loading becomes positive. Equal magnitudes keep the lowest
 * flat index as the deciding loading.
 */
internal fun orientLoading(loadings: DoubleArray) {
    var chosen = -1
    var magnitude = -1.0
    loadings.forEachIndexed { index, value ->
        val absolute = abs(value)
        if (absolute > magnitude) {
            magnitude = absolute
            chosen = index
        }
    }
    if (chosen >= 0 && loadings[chosen] < 0.0) {
        for (index in loadings.indices) loadings[index] = -loadings[index]
    }
}

internal data class SymmetricEigenpair(
    val eigenvalue: Double,
    val vector: DoubleArray,
    val ordinal: Int,
)

internal data class SymmetricEigen(
    val pairs: List<SymmetricEigenpair>,
    val converged: Boolean,
)

/**
 * Jacobi rotations of a real symmetric matrix. The largest remaining
 * off-diagonal entry is cleared first. Equal entries are visited in index order.
 */
internal fun symmetricJacobi(matrix: Array<DoubleArray>, maxRotations: Int): SymmetricEigen {
    val n = matrix.size
    val a = Array(n) { row -> matrix[row].copyOf() }
    val vectors = Array(n) { row -> DoubleArray(n) { column -> if (row == column) 1.0 else 0.0 } }
    var rotations = 0
    var converged = false
    while (rotations <= maxRotations) {
        var pivotRow = -1
        var pivotColumn = -1
        var pivot = 0.0
        for (row in 0 until n) {
            for (column in row + 1 until n) {
                val value = abs(a[row][column])
                if (value > pivot) {
                    pivot = value
                    pivotRow = row
                    pivotColumn = column
                }
            }
        }
        if (pivotRow < 0 || pivot <= JACOBI_OFF_DIAGONAL_TOLERANCE) {
            converged = true
            break
        }
        if (rotations == maxRotations) break
        jacobiRotate(a, vectors, pivotRow, pivotColumn)
        rotations += 1
    }
    val pairs = (0 until n).map { column ->
        var norm = 0.0
        for (row in 0 until n) norm += vectors[row][column] * vectors[row][column]
        norm = sqrt(norm)
        val vector = DoubleArray(n) { row -> if (norm == 0.0) 0.0 else vectors[row][column] / norm }
        SymmetricEigenpair(eigenvalue = a[column][column], vector = vector, ordinal = column)
    }
    return SymmetricEigen(pairs, converged)
}

private fun component(
    index: Int,
    pair: SymmetricEigenpair,
    centered: Array<DoubleArray>,
    active: List<LatentColumnStatistic>,
    layout: FlatColumnLayout,
    rowCount: Int,
    totalVariance: Double,
): LatentComponent {
    val sigma = sqrt(pair.eigenvalue)
    val dense = DoubleArray(active.size)
    for (column in active.indices) {
        var sum = 0.0
        for (row in centered.indices) sum += centered[row][column] * pair.vector[row]
        dense[column] = if (sigma == 0.0) 0.0 else sum / sigma
    }
    normalize(dense)
    val full = DoubleArray(layout.width)
    active.forEachIndexed { activeIndex, statistic -> full[statistic.flatIndex] = dense[activeIndex] }
    orientLoading(full)
    val variance = pair.eigenvalue / rowCount.toDouble()
    return LatentComponent(
        index = index,
        loadings = full.toList(),
        gramEigenvalue = pair.eigenvalue,
        explainedVariance = variance,
        explainedVarianceRatio = if (totalVariance == 0.0) 0.0 else variance / totalVariance,
        positiveLoadings = contributions(full, layout, positive = true),
        negativeLoadings = contributions(full, layout, positive = false),
    )
}

private fun contributions(
    loadings: DoubleArray,
    layout: FlatColumnLayout,
    positive: Boolean,
): List<LatentLoadingContribution> {
    return loadings.indices.mapNotNull { index ->
        val loading = loadings[index]
        val keep = if (positive) loading > 0.0 else loading < 0.0
        if (!keep) return@mapNotNull null
        val column = layout.columns[index]
        LatentLoadingContribution(
            flatIndex = index,
            loading = loading,
            segmentName = column.segmentName,
            semantic = column.semantic,
            sourceName = column.sourceName,
            substantiveBlock = column.substantiveBlock,
        )
    }.sortedWith(compareByDescending<LatentLoadingContribution> { abs(it.loading) }.thenBy { it.flatIndex })
}

private fun coordinate(
    key: StateOccurrenceKey,
    recordedAt: Long,
    vector: FlatMultiscaleVector,
    statistics: List<LatentColumnStatistic>,
    components: List<LatentComponent>,
    exclusion: LatentDiagnosticCode?,
): LatentStateCoordinate {
    val coverage = coverageOf(vector)
    val scores = if (exclusion != null) {
        emptyList()
    } else {
        components.map { component -> score(vector, statistics, component.loadings) }
    }
    return LatentStateCoordinate(
        key = key,
        recordedAt = recordedAt,
        coordinates = scores,
        observedColumnCount = coverage.first,
        observedFraction = coverage.second,
        exclusionReason = exclusion,
    )
}

private fun score(
    vector: FlatMultiscaleVector,
    statistics: List<LatentColumnStatistic>,
    loadings: List<Double>,
): Double {
    var sum = 0.0
    statistics.forEach { column ->
        val loading = loadings[column.flatIndex]
        if (loading == 0.0) return@forEach
        val centered = centeredValue(vector, column)
        sum += centered * loading
    }
    return sum
}

private fun centeredValue(vector: FlatMultiscaleVector, column: LatentColumnStatistic): Double {
    if (!vector.observed[column.flatIndex]) return 0.0
    return vector.values[column.flatIndex] - column.center
}

private fun centeredMatrix(
    rows: List<LatentSourceRow>,
    active: List<LatentColumnStatistic>,
): Array<DoubleArray> {
    return Array(rows.size) { rowIndex ->
        val vector = rows[rowIndex].vector
        DoubleArray(active.size) { columnIndex ->
            val column = active[columnIndex]
            if (!vector.observed[column.flatIndex]) 0.0 else vector.values[column.flatIndex] - column.center
        }
    }
}

private fun gramMatrix(centered: Array<DoubleArray>): Array<DoubleArray> {
    val rows = centered.size
    return Array(rows) { left ->
        DoubleArray(rows) { right ->
            var dot = 0.0
            for (column in centered[left].indices) dot += centered[left][column] * centered[right][column]
            dot
        }
    }
}

private fun columnStatistics(
    rows: List<LatentSourceRow>,
    layout: FlatColumnLayout,
): List<LatentColumnStatistic> {
    return layout.columns.map { column ->
        val samples = ArrayList<Double>()
        rows.forEach { row ->
            if (row.vector.observed[column.flatIndex]) samples += row.vector.values[column.flatIndex]
        }
        val count = samples.size
        val center = if (count == 0) 0.0 else samples.sum() / count
        val deviation = if (count == 0) {
            0.0
        } else {
            sqrt(samples.sumOf { value ->
                val delta = value - center
                delta * delta
            } / count)
        }
        LatentColumnStatistic(
            flatIndex = column.flatIndex,
            observedCount = count,
            missingCount = rows.size - count,
            center = center,
            deviation = deviation,
            variance = deviation * deviation,
            active = count >= 2 && deviation.isFinite() && deviationChangesGeometry(deviation, center),
        )
    }
}

private fun inactiveStatistics(layout: FlatColumnLayout, rowCount: Int): List<LatentColumnStatistic> {
    return layout.columns.map { column ->
        LatentColumnStatistic(
            flatIndex = column.flatIndex,
            observedCount = 0,
            missingCount = rowCount,
            center = 0.0,
            deviation = 0.0,
            variance = 0.0,
            active = false,
        )
    }
}

private fun unavailable(
    layout: FlatColumnLayout,
    ordered: List<LatentSourceRow>,
    columns: List<LatentColumnStatistic>,
    requested: Int,
    reason: LatentDiagnostic,
): RetrospectiveLatentProjection {
    val states = ordered.map { row ->
        val coverage = coverageOf(row.vector)
        LatentStateCoordinate(
            key = row.key,
            recordedAt = row.recordedAt,
            coordinates = emptyList(),
            observedColumnCount = coverage.first,
            observedFraction = coverage.second,
            exclusionReason = reason.code,
        )
    }
    return RetrospectiveLatentProjection(
        status = LatentFitStatus.UNAVAILABLE,
        layout = layout,
        columns = columns,
        activeColumnIndexes = emptyList(),
        components = emptyList(),
        totalVariance = 0.0,
        trainingOccurrenceCount = ordered.size,
        fittedStateKeyOrder = ordered.map { it.key },
        requestedComponentCount = requested,
        diagnostics = listOf(reason),
        states = states,
    )
}

private fun coverageOf(vector: FlatMultiscaleVector): Pair<Int, Double> {
    val observed = vector.observed.count { it }
    val fraction = if (vector.values.isEmpty()) 0.0 else observed.toDouble() / vector.values.size
    return observed to fraction
}

private fun normalize(vector: DoubleArray) {
    var norm = 0.0
    for (value in vector) norm += value * value
    norm = sqrt(norm)
    if (norm == 0.0) return
    for (index in vector.indices) vector[index] = vector[index] / norm
}

private fun jacobiRotate(
    a: Array<DoubleArray>,
    vectors: Array<DoubleArray>,
    p: Int,
    q: Int,
) {
    val app = a[p][p]
    val aqq = a[q][q]
    val apq = a[p][q]
    val tau = (aqq - app) / (2.0 * apq)
    val root = sqrt(1.0 + tau * tau)
    val t = if (tau >= 0.0) 1.0 / (tau + root) else -1.0 / (-tau + root)
    val c = 1.0 / sqrt(1.0 + t * t)
    val s = t * c
    for (k in a.indices) {
        if (k == p || k == q) continue
        val aik = a[p][k]
        val aiq = a[q][k]
        val nextP = c * aik - s * aiq
        val nextQ = s * aik + c * aiq
        a[p][k] = nextP
        a[k][p] = nextP
        a[q][k] = nextQ
        a[k][q] = nextQ
    }
    a[p][p] = app - t * apq
    a[q][q] = aqq + t * apq
    a[p][q] = 0.0
    a[q][p] = 0.0
    for (k in vectors.indices) {
        val vip = vectors[k][p]
        val viq = vectors[k][q]
        vectors[k][p] = c * vip - s * viq
        vectors[k][q] = s * vip + c * viq
    }
}

private fun FlatColumn.sameCoordinate(other: FlatColumn): Boolean {
    return flatIndex == other.flatIndex &&
        segmentName == other.segmentName &&
        semantic == other.semantic &&
        sourceName == other.sourceName &&
        substantiveBlock == other.substantiveBlock &&
        segmentWeight == other.segmentWeight &&
        segmentFactor == other.segmentFactor &&
        sourceBlockFactor == other.sourceBlockFactor
}

private fun defaultJacobiRotations(rowCount: Int): Int {
    val n = rowCount.coerceAtLeast(1)
    return 32 * n * n
}

private const val JACOBI_OFF_DIAGONAL_TOLERANCE = 1.0e-12
