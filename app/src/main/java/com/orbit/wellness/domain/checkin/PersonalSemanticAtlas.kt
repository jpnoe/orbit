package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Retrospective 2D atlas of the frozen semantic geometries.
 *
 * Direct incidence and distributional context stay the geometries they
 * already are. Layer zero and reciprocal stay the layers they already are.
 * Classical MDS reads pairwise partial cosine and does not write a coordinate
 * back into either geometry.
 *
 * Distributional context is a similarity profile. It is not graph radius 2.
 * Reciprocal is the existing one-hop layer.
 */
const val PERSONAL_SEMANTIC_ATLAS = "personal-semantic-atlas-v1"

private const val ATLAS_METHOD =
    "Classical metric MDS of chord distances from clamped partial cosine. " +
        "The two largest positive dimensions are kept. " +
        "Orientation is canonical. " +
        "The 2D coordinates are not similarities, clusters, or features."

private const val PLACED = "projected"
private const val NO_COORDINATES = "no usable coordinates"
private const val NO_POSITIVE_DIMENSION = "no positive dimension"
private const val EIGEN_SWEEPS = 48
private const val TOP_NEIGHBORS = 5

data class SemanticAtlasDataset(
    val protocol: String = PERSONAL_SEMANTIC_ATLAS,
    val method: String = ATLAS_METHOD,
    val vocabularyScope: String,
    val retrospective: Boolean,
    val views: List<SemanticAtlasView>,
    val judgments: List<String>,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_ATLAS)
        require(!causal && !predictive && !updatesModel && !suitableForRecommendation)
        require(views.map { it.geometry to it.context } == ATLAS_VIEWS)
    }
}

data class SemanticAtlasView(
    val geometry: String,
    val geometryLabel: String,
    val context: String,
    val contextLabel: String,
    val sourceDimensionality: Int,
    val points: List<SemanticAtlasPoint>,
    val edges: List<SemanticAtlasEdge>,
    val quality: SemanticAtlasQuality,
)

data class SemanticAtlasPoint(
    val id: String,
    val kind: String,
    val alias: String,
    val label: String,
    val projected: Boolean,
    val placement: String,
    val x: Double?,
    val y: Double?,
    val support: Int?,
    val emotion: String?,
    val axisValues: Map<String, Int>,
    val neighbors: List<SemanticAtlasNeighbor>,
    val incidentLabels: List<String>,
    val carriedLabels: List<String>,
) {
    init {
        require(projected == (placement == PLACED))
        if (!projected) require(x == null && y == null)
        if (projected) {
            require(x != null && x.isFinite())
            require(y == null || y.isFinite())
        }
    }
}

data class SemanticAtlasNeighbor(
    val id: String,
    val label: String,
    val kind: String,
    val similarity: Double,
    val projectedDistance: Double,
    val projectedRank: Int,
    val retained: Boolean,
)

data class SemanticAtlasEdge(
    val fromId: String,
    val toId: String,
)

data class SemanticAtlasQuality(
    val projected: Int,
    val unprojected: Int,
    val byKind: List<SemanticAtlasKindCount>,
    val sourceDimensionality: Int,
    val positiveDimensions: Int,
    val retainedDimensions: Int,
    val stress: Double?,
    val meanRetention: Double?,
    val retentionByKind: List<SemanticAtlasKindRetention>,
    val negativeEigenvalueShare: Double,
    val smallestEigenvalue: Double,
    val degeneracy: String,
    val retrospectiveFullHistory: Boolean,
    val smallSampleWarning: String,
)

data class SemanticAtlasKindCount(
    val kind: String,
    val projected: Int,
    val unprojected: Int,
)

data class SemanticAtlasKindRetention(
    val kind: String,
    val meanRetention: Double?,
    val scored: Int,
)

data class SemanticAtlasCoordinate(
    val x: Double,
    val y: Double?,
)

data class SemanticAtlasLabelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun overlaps(other: SemanticAtlasLabelBox): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
}

fun projectPersonalSemanticAtlas(
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection? = null,
): SemanticAtlasDataset {
    val edges = chronologicalEdges(graph)
    val directZero = projectView(
        geometry = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
        geometryLabel = "Direct incidence",
        context = LAYER_ZERO,
        contextLabel = "Layer zero",
        sourceDimensionality = embedding.layer(LAYER_ZERO).dimensionality,
        vectors = embedding.layer(LAYER_ZERO).nodes.associate { it.id to it.coordinates },
        cosine = embedding.layer(LAYER_ZERO)::similarity,
        warning = embedding.layer(LAYER_ZERO).unreliableBecause,
        embedding = embedding,
        distributional = distributional,
        graph = graph,
        projection = projection,
        edges = edges,
    )
    val directReciprocal = projectView(
        geometry = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
        geometryLabel = "Direct incidence",
        context = RECIPROCAL_LAYER,
        contextLabel = "Reciprocal",
        sourceDimensionality = embedding.layer(RECIPROCAL_LAYER).dimensionality,
        vectors = embedding.layer(RECIPROCAL_LAYER).nodes.associate { it.id to it.coordinates },
        cosine = embedding.layer(RECIPROCAL_LAYER)::similarity,
        warning = embedding.layer(RECIPROCAL_LAYER).unreliableBecause,
        embedding = embedding,
        distributional = distributional,
        graph = graph,
        projection = projection,
        edges = edges,
    )
    val distributionalZero = projectView(
        geometry = PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT,
        geometryLabel = "Distributional context",
        context = LAYER_ZERO,
        contextLabel = "Layer zero",
        sourceDimensionality = distributional.layer(LAYER_ZERO).dimensionality,
        vectors = distributional.layer(LAYER_ZERO).nodes.associate { it.id to it.profile },
        cosine = distributional.layer(LAYER_ZERO)::similarity,
        warning = distributional.layer(LAYER_ZERO).unreliableBecause,
        embedding = embedding,
        distributional = distributional,
        graph = graph,
        projection = projection,
        edges = edges,
    )
    val distributionalReciprocal = projectView(
        geometry = PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT,
        geometryLabel = "Distributional context",
        context = RECIPROCAL_LAYER,
        contextLabel = "Reciprocal",
        sourceDimensionality = distributional.layer(RECIPROCAL_LAYER).dimensionality,
        vectors = distributional.layer(RECIPROCAL_LAYER).nodes.associate { it.id to it.profile },
        cosine = distributional.layer(RECIPROCAL_LAYER)::similarity,
        warning = distributional.layer(RECIPROCAL_LAYER).unreliableBecause,
        embedding = embedding,
        distributional = distributional,
        graph = graph,
        projection = projection,
        edges = edges,
    )
    return SemanticAtlasDataset(
        vocabularyScope = embedding.vocabularyScope,
        retrospective = embedding.retrospective,
        views = listOf(directZero, directReciprocal, distributionalZero, distributionalReciprocal),
        judgments = atlasJudgments(embedding),
    )
}

fun semanticAtlasLabelsToDraw(
    points: List<SemanticAtlasPoint>,
    boxes: Map<String, SemanticAtlasLabelBox>,
    selectedId: String?,
): Set<String> {
    val accepted = linkedSetOf<String>()
    val reserved = mutableListOf<SemanticAtlasLabelBox>()
    fun accept(id: String) {
        val box = boxes[id] ?: return
        if (reserved.any { it.overlaps(box) }) return
        accepted += id
        reserved += box
    }
    if (selectedId != null) {
        val selectedBox = boxes[selectedId]
        if (selectedBox != null) {
            accepted += selectedId
            reserved += selectedBox
        }
    }
    val occurrences = points.filter { it.kind != "concept" }.sortedWith(compareBy({ it.label }, { it.id }))
    val concepts = points.filter { it.kind == "concept" }
        .sortedWith(compareByDescending<SemanticAtlasPoint> { it.support ?: 0 }.thenBy { it.label }.thenBy { it.id })
    (occurrences + concepts).forEach { point ->
        if (point.id != selectedId) accept(point.id)
    }
    return accepted
}

internal fun classicalMds(vectors: Map<String, List<Double>>): ClassicalMdsLayout {
    val usable = vectors.filterValues { usableCoordinates(it) }
    return classicalMdsDistances(usable.keys.toList()) { left, right ->
        partialCosine(usable.getValue(left), usable.getValue(right))
    }
}

internal fun classicalMdsDistances(
    ids: List<String>,
    cosine: (String, String) -> Double,
): ClassicalMdsLayout {
    val ordered = ids.distinct().sorted()
    val count = ordered.size
    if (count == 0) {
        return ClassicalMdsLayout(
            placed = emptyMap(),
            positiveDimensions = 0,
            retainedDimensions = 0,
            negativeEigenvalueShare = 0.0,
            smallestEigenvalue = 0.0,
            tiedLeadingAxes = false,
            converged = true,
            stress = null,
            degeneracy = "No usable coordinates.",
        )
    }
    val squared = Array(count) { DoubleArray(count) }
    for (row in 0 until count) {
        for (column in row + 1 until count) {
            val similarity = cosine(ordered[row], ordered[column]).coerceIn(-1.0, 1.0)
            val distance = sqrt(max(0.0, 2.0 * (1.0 - similarity)))
            val value = distance * distance
            squared[row][column] = value
            squared[column][row] = value
        }
    }
    return layoutFromSquaredDistances(ordered, squared)
}

internal fun classicalMdsFromDistances(
    ids: List<String>,
    distance: (String, String) -> Double,
): ClassicalMdsLayout {
    val ordered = ids.distinct().sorted()
    val count = ordered.size
    if (count == 0) {
        return ClassicalMdsLayout(
            placed = emptyMap(),
            positiveDimensions = 0,
            retainedDimensions = 0,
            negativeEigenvalueShare = 0.0,
            smallestEigenvalue = 0.0,
            tiedLeadingAxes = false,
            converged = true,
            stress = null,
            degeneracy = "No usable coordinates.",
        )
    }
    val squared = Array(count) { DoubleArray(count) }
    for (row in 0 until count) {
        for (column in row + 1 until count) {
            val value = distance(ordered[row], ordered[column])
            val square = value * value
            squared[row][column] = square
            squared[column][row] = square
        }
    }
    return layoutFromSquaredDistances(ordered, squared)
}

private fun layoutFromSquaredDistances(
    ordered: List<String>,
    squared: Array<DoubleArray>,
): ClassicalMdsLayout {
    val count = ordered.size
    val gram = doubleCenter(squared)
    val eigen = jacobiEigen(gram)
    val order = eigen.values.indices.sortedByDescending { eigen.values[it] }
    val scale = eigen.values.maxOf { abs(it) }.coerceAtLeast(1.0)
    val threshold = 1e-8 * scale
    val positive = order.filter { eigen.values[it] > threshold }
    val negativeMass = eigen.values.sumOf { value -> max(0.0, -value) }
    val absoluteMass = eigen.values.sumOf { abs(it) }
    val negativeShare = if (absoluteMass <= 1e-18) 0.0 else negativeMass / absoluteMass
    val smallest = eigen.values.minOrNull() ?: 0.0
    val retained = minOf(2, positive.size)
    val tied = retained == 2 && abs(eigen.values[positive[0]] - eigen.values[positive[1]]) <= 1e-5 * scale
    val placed = place(ordered, eigen.vectors, eigen.values, positive, retained, tied)
    val stress = stress(ordered, squared, placed)
    return ClassicalMdsLayout(
        placed = placed,
        positiveDimensions = positive.size,
        retainedDimensions = retained,
        negativeEigenvalueShare = negativeShare,
        smallestEigenvalue = smallest,
        tiedLeadingAxes = tied,
        converged = eigen.converged,
        stress = stress,
        degeneracy = degeneracy(positive.size, retained, tied, negativeShare, eigen.converged, count),
    )
}

internal data class ClassicalMdsLayout(
    val placed: Map<String, SemanticAtlasCoordinate>,
    val positiveDimensions: Int,
    val retainedDimensions: Int,
    val negativeEigenvalueShare: Double,
    val smallestEigenvalue: Double,
    val tiedLeadingAxes: Boolean,
    val converged: Boolean,
    val stress: Double?,
    val degeneracy: String,
)

private fun projectView(
    geometry: String,
    geometryLabel: String,
    context: String,
    contextLabel: String,
    sourceDimensionality: Int,
    vectors: Map<String, List<Double>>,
    cosine: (String, String) -> Double,
    warning: String,
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection?,
    edges: List<SemanticAtlasEdge>,
): SemanticAtlasView {
    val audit = embedding.audit
    val ids = (vectors.keys + audit.aliasByNode.keys + audit.labelByConcept.keys).distinct().sorted()
    val usable = ids.filter { usableCoordinates(vectors[it].orEmpty()) }
    val layout = classicalMdsDistances(usable, cosine)
    val labels = ids.associateWith { visibleLabel(it, audit, distributional.reading) }
    val kinds = ids.associateWith { nodeKind(it) }
    val neighbors = sourceNeighborAudits(
        ids = layout.placed.keys.toList(),
        kinds = kinds,
        labels = labels,
        similarity = cosine,
        coordinate = layout.placed::getValue,
    )
    val points = ids.map { id ->
        val coordinate = layout.placed[id]
        val kind = kinds.getValue(id)
        val state = projection?.states?.firstOrNull { stateNodeId(it.key.raw) == id }
        SemanticAtlasPoint(
            id = id,
            kind = kind,
            alias = audit.aliasByNode[id] ?: labels.getValue(id),
            label = labels.getValue(id),
            projected = coordinate != null,
            placement = when {
                coordinate != null -> PLACED
                id in usable -> NO_POSITIVE_DIMENSION
                else -> NO_COORDINATES
            },
            x = coordinate?.x,
            y = coordinate?.y,
            support = if (kind == "concept") distributional.supportByConcept[id] else null,
            emotion = state?.emotion?.name?.takeIf { it.isNotBlank() },
            axisValues = coreAxes(state),
            neighbors = neighbors[id].orEmpty(),
            incidentLabels = incidentLabels(id, kind, audit, graph, labels),
            carriedLabels = carriedLabels(id, kind, audit, labels),
        )
    }
    val quality = quality(
        points = points,
        sourceDimensionality = sourceDimensionality,
        layout = layout,
        retrospective = embedding.retrospective && embedding.vocabularyScope == RETROSPECTIVE_VOCABULARY_SCOPE,
        warning = warning,
    )
    return SemanticAtlasView(
        geometry = geometry,
        geometryLabel = geometryLabel,
        context = context,
        contextLabel = contextLabel,
        sourceDimensionality = sourceDimensionality,
        points = points,
        edges = edges,
        quality = quality,
    )
}

private fun quality(
    points: List<SemanticAtlasPoint>,
    sourceDimensionality: Int,
    layout: ClassicalMdsLayout,
    retrospective: Boolean,
    warning: String,
): SemanticAtlasQuality {
    val kinds = listOf("state", "transition", "concept")
    val byKind = kinds.map { kind ->
        val rows = points.filter { it.kind == kind }
        SemanticAtlasKindCount(
            kind = kind,
            projected = rows.count { it.projected },
            unprojected = rows.count { !it.projected },
        )
    }
    val refusal = warning.takeUnless { it.isBlank() || it.startsWith("Small-sample") }.orEmpty()
    val note = listOf(layout.degeneracy, refusal).filter { it.isNotBlank() }.joinToString(" ")
    val scored = points.filter { it.neighbors.isNotEmpty() }
    val retentionByKind = kinds.map { kind ->
        val rows = scored.filter { it.kind == kind }
        SemanticAtlasKindRetention(
            kind = kind,
            meanRetention = if (rows.isEmpty()) null else rows.map { retentionScore(it) }.average(),
            scored = rows.size,
        )
    }
    return SemanticAtlasQuality(
        projected = points.count { it.projected },
        unprojected = points.count { !it.projected },
        byKind = byKind,
        sourceDimensionality = sourceDimensionality,
        positiveDimensions = layout.positiveDimensions,
        retainedDimensions = layout.retainedDimensions,
        stress = layout.stress,
        meanRetention = if (scored.isEmpty()) null else scored.map { retentionScore(it) }.average(),
        retentionByKind = retentionByKind,
        negativeEigenvalueShare = layout.negativeEigenvalueShare,
        smallestEigenvalue = layout.smallestEigenvalue,
        degeneracy = note,
        retrospectiveFullHistory = retrospective,
        smallSampleWarning = warning.takeIf { it.startsWith("Small-sample") }.orEmpty(),
    )
}

private fun retentionScore(point: SemanticAtlasPoint): Double {
    if (point.neighbors.isEmpty()) return 0.0
    return point.neighbors.count { it.retained }.toDouble() / point.neighbors.size
}

internal fun sourceNeighborAudits(
    ids: List<String>,
    kinds: Map<String, String>,
    labels: Map<String, String>,
    similarity: (String, String) -> Double,
    coordinate: (String) -> SemanticAtlasCoordinate,
): Map<String, List<SemanticAtlasNeighbor>> {
    val ordered = ids.distinct().sorted()
    if (ordered.size < 2) return emptyMap()
    val limit = minOf(TOP_NEIGHBORS, ordered.size - 1)
    return ordered.associateWith { anchor ->
        val source = ordered.filter { it != anchor }
            .sortedWith(compareByDescending<String> { similarity(anchor, it).coerceIn(-1.0, 1.0) }.thenBy { it })
            .take(limit)
        val projectedOrder = ordered.filter { it != anchor }
            .sortedWith(compareBy<String> { projectedDistance(coordinate(anchor), coordinate(it)) }.thenBy { it })
        val rank = projectedOrder.withIndex().associate { it.value to it.index + 1 }
        source.map { neighbor ->
            val neighborRank = rank.getValue(neighbor)
            SemanticAtlasNeighbor(
                id = neighbor,
                label = labels[neighbor] ?: kinds[neighbor] ?: "point",
                kind = kinds[neighbor] ?: nodeKind(neighbor),
                similarity = similarity(anchor, neighbor).coerceIn(-1.0, 1.0),
                projectedDistance = projectedDistance(coordinate(anchor), coordinate(neighbor)),
                projectedRank = neighborRank,
                retained = neighborRank <= limit,
            )
        }
    }
}

private fun chronologicalEdges(graph: AlternatingTemporalGraph): List<SemanticAtlasEdge> {
    return graph.transitions.flatMap { transition ->
        val transitionId = transitionNodeId(transition.id)
        val endId = stateNodeId(transition.endStateId)
        val start = transition.startStateId?.let { startId ->
            SemanticAtlasEdge(stateNodeId(startId), transitionId)
        }
        listOfNotNull(start, SemanticAtlasEdge(transitionId, endId))
    }
}

private fun incidentLabels(
    id: String,
    kind: String,
    audit: EmbeddingAuditIndex,
    graph: AlternatingTemporalGraph,
    labels: Map<String, String>,
): List<String> {
    val named = when (kind) {
        "state" -> audit.incidentTransitions[id].orEmpty().map { labels[it] ?: return@map "" }
        "transition" -> endpointLabels(id, graph, labels)
        else -> emptyList()
    }
    return named.filter { it.isNotBlank() }.distinct().sorted()
}

private fun endpointLabels(
    id: String,
    graph: AlternatingTemporalGraph,
    labels: Map<String, String>,
): List<String> {
    val raw = id.removePrefix("transition:")
    val transition = graph.transitions.firstOrNull { it.id == raw } ?: return emptyList()
    val start = transition.startStateId?.let { labels[stateNodeId(it)] }
    val end = labels[stateNodeId(transition.endStateId)]
    return listOfNotNull(start, end)
}

private fun carriedLabels(
    id: String,
    kind: String,
    audit: EmbeddingAuditIndex,
    labels: Map<String, String>,
): List<String> {
    val ids = if (kind == "concept") {
        audit.focalConcepts.filterValues { id in it }.keys
    } else {
        audit.focalConcepts[id].orEmpty()
    }
    return ids.mapNotNull { labels[it] }.distinct().sorted()
}

private fun visibleLabel(
    id: String,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
): String {
    return reading.line(id, audit)
}

private fun coreAxes(state: StateOccurrenceProjection?): Map<String, Int> {
    if (state == null) return emptyMap()
    val wanted = listOf(
        DefaultCheckInAxes.MOOD,
        DefaultCheckInAxes.ENERGY,
        DefaultCheckInAxes.CONFIDENCE,
        DefaultCheckInAxes.ANXIETY,
    )
    return wanted.mapNotNull { axisId ->
        val axis = state.axes.firstOrNull { it.axisId == axisId } ?: return@mapNotNull null
        val raw = axis.raw ?: return@mapNotNull null
        if (axis.status != AxisOccurrenceStatus.OBSERVED) return@mapNotNull null
        axisId to raw
    }.toMap()
}

private fun nodeKind(id: String): String = when {
    id.startsWith("state:") -> "state"
    id.startsWith("transition:") -> "transition"
    else -> "concept"
}

private fun usableCoordinates(values: List<Double>): Boolean {
    var sum = 0.0
    var sawFinite = false
    values.forEach { value ->
        if (value.isNaN()) return@forEach
        sawFinite = true
        sum += value * value
    }
    return sawFinite && sum > 1e-18
}

private fun partialCosine(left: List<Double>, right: List<Double>): Double {
    if (left.size != right.size) return 0.0
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (index in left.indices) {
        val x = left[index]
        val y = right[index]
        if (x.isNaN() || y.isNaN()) continue
        dot += x * y
        leftNorm += x * x
        rightNorm += y * y
    }
    if (leftNorm <= 1e-18 || rightNorm <= 1e-18) return 0.0
    return dot / sqrt(leftNorm * rightNorm)
}

private fun doubleCenter(squared: Array<DoubleArray>): Array<DoubleArray> {
    val count = squared.size
    val rowMean = DoubleArray(count) { row -> squared[row].average() }
    val grand = rowMean.average()
    return Array(count) { row ->
        DoubleArray(count) { column ->
            -0.5 * (squared[row][column] - rowMean[row] - rowMean[column] + grand)
        }
    }
}

private data class EigenPair(
    val values: DoubleArray,
    val vectors: Array<DoubleArray>,
    val converged: Boolean,
)

private fun jacobiEigen(matrix: Array<DoubleArray>): EigenPair {
    val count = matrix.size
    val values = Array(count) { row -> matrix[row].copyOf() }
    val vectors = Array(count) { row -> DoubleArray(count) { column -> if (row == column) 1.0 else 0.0 } }
    if (count <= 1) {
        return EigenPair(DoubleArray(count) { values[it][it] }, vectors, true)
    }
    var converged = false
    var sweep = 0
    while (sweep < EIGEN_SWEEPS) {
        var largest = 0.0
        for (left in 0 until count - 1) {
            for (right in left + 1 until count) {
                val off = values[left][right]
                if (abs(off) > largest) largest = abs(off)
                if (abs(off) <= 1e-15) continue
                rotateJacobi(values, vectors, left, right, off)
            }
        }
        val scale = values.maxOf { row -> row.maxOf { abs(it) } }.coerceAtLeast(1.0)
        if (largest <= 1e-12 * scale) {
            converged = true
            break
        }
        sweep += 1
    }
    return EigenPair(DoubleArray(count) { values[it][it] }, vectors, converged)
}

private fun rotateJacobi(
    values: Array<DoubleArray>,
    vectors: Array<DoubleArray>,
    left: Int,
    right: Int,
    off: Double,
) {
    val diagonalLeft = values[left][left]
    val diagonalRight = values[right][right]
    val tau = (diagonalRight - diagonalLeft) / (2.0 * off)
    val tangent = if (tau >= 0.0) {
        1.0 / (tau + sqrt(1.0 + tau * tau))
    } else {
        -1.0 / (-tau + sqrt(1.0 + tau * tau))
    }
    val cosine = 1.0 / sqrt(1.0 + tangent * tangent)
    val sine = tangent * cosine
    values[left][left] = diagonalLeft - tangent * off
    values[right][right] = diagonalRight + tangent * off
    values[left][right] = 0.0
    values[right][left] = 0.0
    for (row in values.indices) {
        if (row == left || row == right) continue
        val acrossLeft = values[row][left]
        val acrossRight = values[row][right]
        values[row][left] = cosine * acrossLeft - sine * acrossRight
        values[left][row] = values[row][left]
        values[row][right] = cosine * acrossRight + sine * acrossLeft
        values[right][row] = values[row][right]
    }
    for (row in vectors.indices) {
        val acrossLeft = vectors[row][left]
        val acrossRight = vectors[row][right]
        vectors[row][left] = cosine * acrossLeft - sine * acrossRight
        vectors[row][right] = sine * acrossLeft + cosine * acrossRight
    }
}

private fun place(
    ids: List<String>,
    vectors: Array<DoubleArray>,
    eigenvalues: DoubleArray,
    positive: List<Int>,
    retained: Int,
    tied: Boolean,
): Map<String, SemanticAtlasCoordinate> {
    if (retained == 0) return emptyMap()
    val first = axisCoordinate(ids, vectors, eigenvalues, positive[0])
    val signedFirst = if (tied && retained == 2) first else canonicalizeSign(ids, first)
    if (retained == 1) {
        return ids.indices.associate { index -> ids[index] to SemanticAtlasCoordinate(signedFirst[index], null) }
    }
    val second = axisCoordinate(ids, vectors, eigenvalues, positive[1])
    val signedSecond = if (tied) second else canonicalizeSign(ids, second)
    val oriented = if (tied) canonicalizePlane(ids, signedFirst, signedSecond) else signedFirst to signedSecond
    return ids.indices.associate { index ->
        ids[index] to SemanticAtlasCoordinate(oriented.first[index], oriented.second[index])
    }
}

private fun axisCoordinate(
    ids: List<String>,
    vectors: Array<DoubleArray>,
    eigenvalues: DoubleArray,
    axis: Int,
): DoubleArray {
    val magnitude = sqrt(eigenvalues[axis])
    return DoubleArray(ids.size) { row -> vectors[row][axis] * magnitude }
}

private fun canonicalizeSign(ids: List<String>, coordinates: DoubleArray): DoubleArray {
    val sum = coordinates.sum()
    val flip = when {
        sum > 1e-9 -> false
        sum < -1e-9 -> true
        else -> {
            val first = ids.indices.firstOrNull { abs(coordinates[it]) > 1e-9 }
            first != null && coordinates[first] < 0.0
        }
    }
    return if (flip) DoubleArray(coordinates.size) { -coordinates[it] } else coordinates
}

private fun canonicalizePlane(
    ids: List<String>,
    x: DoubleArray,
    y: DoubleArray,
): Pair<DoubleArray, DoubleArray> {
    var anchor = -1
    var anchorRadius = -1.0
    for (index in x.indices) {
        val radius = hypot(x[index], y[index])
        val closerId = anchor < 0 || ids[index] < ids[anchor]
        if (radius > anchorRadius + 1e-9 || (abs(radius - anchorRadius) <= 1e-9 && closerId)) {
            anchor = index
            anchorRadius = radius
        }
    }
    if (anchor < 0 || anchorRadius <= 1e-12) return x to y
    val cosine = x[anchor] / anchorRadius
    val sine = -y[anchor] / anchorRadius
    val rotatedX = DoubleArray(x.size) { cosine * x[it] - sine * y[it] }
    val rotatedY = DoubleArray(y.size) { sine * x[it] + cosine * y[it] }
    var second = -1
    var secondRadius = -1.0
    for (index in x.indices) {
        if (index == anchor) continue
        val radius = hypot(rotatedX[index], rotatedY[index])
        val closerId = second < 0 || ids[index] < ids[second]
        if (radius > secondRadius + 1e-9 || (abs(radius - secondRadius) <= 1e-9 && closerId)) {
            second = index
            secondRadius = radius
        }
    }
    if (second >= 0 && rotatedY[second] < -1e-9) {
        for (index in rotatedY.indices) rotatedY[index] = -rotatedY[index]
    }
    return rotatedX to rotatedY
}

private fun stress(
    ids: List<String>,
    squared: Array<DoubleArray>,
    placed: Map<String, SemanticAtlasCoordinate>,
): Double? {
    if (placed.size < 2) return null
    var numerator = 0.0
    var denominator = 0.0
    for (left in ids.indices) {
        for (right in left + 1 until ids.size) {
            val source = sqrt(squared[left][right])
            val image = projectedDistance(placed.getValue(ids[left]), placed.getValue(ids[right]))
            val gap = image - source
            numerator += gap * gap
            denominator += source * source
        }
    }
    if (denominator <= 1e-18) return if (numerator <= 1e-18) 0.0 else null
    return sqrt(numerator / denominator)
}

private fun projectedDistance(left: SemanticAtlasCoordinate, right: SemanticAtlasCoordinate): Double {
    val dx = left.x - right.x
    val dy = (left.y ?: 0.0) - (right.y ?: 0.0)
    return hypot(dx, dy)
}

private fun degeneracy(
    positive: Int,
    retained: Int,
    tied: Boolean,
    negativeShare: Double,
    converged: Boolean,
    count: Int,
): String {
    val lines = mutableListOf<String>()
    if (positive == 0) lines += "No positive dimension remains, so usable nodes stay unprojected."
    if (positive == 1) lines += "One positive dimension remains. The second coordinate is absent."
    if (tied) lines += "The two leading eigenvalues are tied. The plane is rotated into a stable orientation."
    if (negativeShare > 1e-4) {
        lines += "Negative eigenvalues are part of the spectrum. Partial-cosine chord distances are not Euclidean."
    }
    if (!converged && count > 1) lines += "The eigenvalue sweep stopped before the off-diagonal was negligible."
    if (lines.isEmpty() && retained == 2) return ""
    return lines.joinToString(" ")
}

private fun atlasJudgments(embedding: JointEmbeddingDataset): List<String> {
    val scope = if (embedding.retrospective && embedding.vocabularyScope == RETROSPECTIVE_VOCABULARY_SCOPE) {
        "Retrospective full-history diagnostic."
    } else {
        "This atlas uses the supplied embedding scope. It is not labeled as full history."
    }
    return listOf(
        scope,
        "Not causal. Not predictive. Not suitable for recommendation.",
        "No cluster assignment, no region names, and no behavior effect.",
        "Layer zero is radius 0. Reciprocal is the existing one-hop layer. This atlas does not add recursive radius 2.",
        "Distributional context is a second-order similarity profile. It is not graph radius 2.",
        "The map is classical MDS of chord distance from the existing partial cosine. Visual proximity is projected. The original cosine is authoritative.",
        "A node without usable coordinates stays unprojected. The atlas does not assign it the origin.",
        "Concept support changes label priority only. It does not move a point or change a similarity.",
        "The 2D coordinates are not written back into similarity, clustering, prediction, recommendation, or a later fit.",
    )
}

private val ATLAS_VIEWS = listOf(
    PERSONAL_SEMANTIC_JOINT_EMBEDDING to LAYER_ZERO,
    PERSONAL_SEMANTIC_JOINT_EMBEDDING to RECIPROCAL_LAYER,
    PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to LAYER_ZERO,
    PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to RECIPROCAL_LAYER,
)
