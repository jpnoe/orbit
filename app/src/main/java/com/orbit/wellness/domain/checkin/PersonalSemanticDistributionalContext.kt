package com.orbit.wellness.domain.checkin

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Distributional profiles on the joint-embedding control.
 *
 * Each state, transition, and concept is the vector of its first-order
 * cosine to the occurrences in that fit. Those occurrences are one shared
 * landmark space, so a later cosine compares all three kinds in the same
 * way. The joint embedding is not modified. A second pass through the
 * occurrence kernel is not applied, and a rectangular factorization is not
 * used.
 *
 * The occurrence's own landmark is missing. Partial cosine skips it, so a
 * self similarity of one cannot dominate the profile. First-order signs are
 * copied. A negative coordinate is not clipped and is not replaced with zero.
 */
const val PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT = "personal-semantic-distributional-context-baseline-v1"

private const val PERSONAL_SEMANTIC_DISTRIBUTIONAL_METHOD =
    "Cosine of first-order similarities to the same occurrence landmarks. " +
        "States, transitions, and concepts use that one space. " +
        "The occurrence's own landmark is missing, and negative similarities are kept."

data class DistributionalContextDataset(
    val protocol: String = PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT,
    val vocabularyScope: String,
    val retrospective: Boolean,
    val method: String,
    val controlProtocol: String = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
    val supportByConcept: Map<String, Int>,
    val layers: List<DistributionalLayer>,
    val judgments: List<String>,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT)
        require(controlProtocol == PERSONAL_SEMANTIC_JOINT_EMBEDDING)
        require(!predictive && !updatesModel && !suitableForRecommendation)
    }

    fun layer(name: String): DistributionalLayer = layers.first { it.name == name }
}

data class DistributionalNode(
    val id: String,
    val kind: String,
    val profile: List<Double>,
)

data class LandmarkContribution(
    val landmarkId: String,
    val contribution: Double,
)

data class DistributionalNeighbor(
    val id: String,
    val kind: String,
    val similarity: Double,
    val landmarks: List<LandmarkContribution>,
)

data class DistributionalOccurrenceLists(
    val states: List<DistributionalNeighbor>,
    val incidentTransitions: List<DistributionalNeighbor>,
    val incidentWithoutCoordinates: List<String>,
    val nonincidentTransitions: List<DistributionalNeighbor>,
    val carriedConcepts: List<DistributionalNeighbor>,
    val carriedWithoutCoordinates: List<String>,
    val noncarriedConcepts: List<DistributionalNeighbor>,
)

data class DistributionalConceptLists(
    val directStates: List<DistributionalNeighbor>,
    val nearestStates: List<DistributionalNeighbor>,
    val directTransitions: List<DistributionalNeighbor>,
    val nearestTransitions: List<DistributionalNeighbor>,
    val nearestConcepts: List<DistributionalNeighbor>,
)

data class DistributionalLayer(
    val name: String,
    val landmarks: List<String>,
    val occurrenceCount: Int,
    val unreliableBecause: String,
    val nodes: List<DistributionalNode>,
) {
    val dimensionality: Int get() = landmarks.size

    fun node(id: String): DistributionalNode? = nodes.firstOrNull { it.id == id }

    fun profileValue(id: String, landmarkId: String): Double {
        val profile = node(id)?.profile ?: return Double.NaN
        val index = landmarks.indexOf(landmarkId)
        if (index < 0 || index > profile.lastIndex) return Double.NaN
        return profile[index]
    }

    fun similarity(left: String, right: String): Double {
        val a = node(left)?.profile ?: return 0.0
        val b = node(right)?.profile ?: return 0.0
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in a.indices) {
            val x = a[index]
            val y = b[index]
            if (x.isNaN() || y.isNaN()) continue
            dot += x * y
            leftNorm += x * x
            rightNorm += y * y
        }
        if (leftNorm <= 1e-18 || rightNorm <= 1e-18) return 0.0
        return dot / sqrt(leftNorm * rightNorm)
    }

    fun landmarkContributions(left: String, right: String): List<LandmarkContribution> {
        val a = node(left)?.profile ?: return emptyList()
        val b = node(right)?.profile ?: return emptyList()
        if (a.size != b.size || a.size != landmarks.size) return emptyList()
        var leftNorm = 0.0
        var rightNorm = 0.0
        val products = DoubleArray(a.size)
        for (index in a.indices) {
            val x = a[index]
            val y = b[index]
            if (x.isNaN() || y.isNaN()) continue
            leftNorm += x * x
            rightNorm += y * y
            products[index] = x * y
        }
        if (leftNorm <= 1e-18 || rightNorm <= 1e-18) return emptyList()
        val scale = sqrt(leftNorm * rightNorm)
        return landmarks.indices.mapNotNull { index ->
            val share = products[index] / scale
            if (abs(share) <= 1e-8) null else LandmarkContribution(landmarks[index], share)
        }.sortedWith(compareByDescending<LandmarkContribution> { abs(it.contribution) }.thenBy { it.landmarkId })
    }

    fun occurrenceLists(anchor: String, audit: EmbeddingAuditIndex): DistributionalOccurrenceLists {
        val incident = audit.incidentTransitions[anchor].orEmpty()
        val focal = audit.focalConcepts[anchor].orEmpty()
        val carried = present(anchor, focal)
        val omitted = focal.filter { node(it) == null }.map { concept ->
            "${audit.conceptText(concept)} · no distributional coordinate"
        }
        val incidentMissing = incident.filter { node(it) == null }.map { transition ->
            "${audit.recordText(transition)} · no distributional coordinates"
        }
        return DistributionalOccurrenceLists(
            states = ranked(anchor, "state") { it != anchor }.take(5),
            incidentTransitions = present(anchor, incident),
            incidentWithoutCoordinates = incidentMissing,
            nonincidentTransitions = ranked(anchor, "transition") { it !in incident }.take(5),
            carriedConcepts = carried,
            carriedWithoutCoordinates = omitted,
            noncarriedConcepts = ranked(anchor, "concept") { it !in focal }.take(5),
        )
    }

    fun conceptLists(conceptId: String, audit: EmbeddingAuditIndex): DistributionalConceptLists {
        val direct = audit.focalConcepts.filterValues { conceptId in it }.keys
        val directStates = direct.filter { it.startsWith("state:") }.toSet()
        val directTransitions = direct.filter { it.startsWith("transition:") }.toSet()
        return DistributionalConceptLists(
            directStates = present(conceptId, directStates),
            nearestStates = ranked(conceptId, "state") { it !in directStates }.take(5),
            directTransitions = present(conceptId, directTransitions),
            nearestTransitions = ranked(conceptId, "transition") { it !in directTransitions }.take(5),
            nearestConcepts = ranked(conceptId, "concept") { it != conceptId }.take(5),
        )
    }

    private fun present(anchor: String, ids: Set<String>): List<DistributionalNeighbor> {
        return ids.mapNotNull { id -> neighborOrNull(anchor, id) }
            .sortedWith(compareByDescending<DistributionalNeighbor> { it.similarity }.thenBy { it.id })
    }

    private fun ranked(anchor: String, kind: String, include: (String) -> Boolean): List<DistributionalNeighbor> {
        if (node(anchor) == null) return emptyList()
        return nodes
            .filter { it.kind == kind && it.id != anchor && include(it.id) }
            .mapNotNull { neighborOrNull(anchor, it.id) }
            .filter { abs(it.similarity) > 1e-8 }
            .sortedWith(compareByDescending<DistributionalNeighbor> { it.similarity }.thenBy { it.id })
    }

    private fun neighborOrNull(anchor: String, id: String): DistributionalNeighbor? {
        val neighbor = node(id) ?: return null
        return DistributionalNeighbor(
            id = neighbor.id,
            kind = neighbor.kind,
            similarity = similarity(anchor, neighbor.id),
            landmarks = landmarkContributions(anchor, neighbor.id).take(4),
        )
    }
}

fun projectDistributionalContext(
    embedding: JointEmbeddingDataset,
    supportByConcept: Map<String, Int> = emptyMap(),
): DistributionalContextDataset {
    return DistributionalContextDataset(
        vocabularyScope = embedding.vocabularyScope,
        retrospective = embedding.retrospective,
        method = PERSONAL_SEMANTIC_DISTRIBUTIONAL_METHOD,
        supportByConcept = supportByConcept,
        layers = embedding.layers.map { layer -> projectLayer(layer, embedding.audit) },
        judgments = judgments(embedding),
    )
}

private fun projectLayer(layer: JointEmbeddingLayer, audit: EmbeddingAuditIndex): DistributionalLayer {
    val landmarks = layer.nodes
        .filter { it.kind == "state" || it.kind == "transition" }
        .sortedWith(compareBy({ audit.aliasByNode[it.id] ?: it.id }, { it.id }))
        .map { it.id }
    val nodes = layer.nodes.map { node ->
        DistributionalNode(
            id = node.id,
            kind = node.kind,
            profile = landmarks.map { landmark ->
                if (node.id == landmark) Double.NaN else layer.similarity(node.id, landmark)
            },
        )
    }
    return DistributionalLayer(
        name = layer.name,
        landmarks = landmarks,
        occurrenceCount = layer.occurrenceCount,
        unreliableBecause = layer.unreliableBecause,
        nodes = nodes,
    )
}

private fun judgments(embedding: JointEmbeddingDataset): List<String> {
    val lines = mutableListOf<String>()
    lines += if (embedding.retrospective) {
        "Retrospective full-history measurement. Landmarks are the occurrences in the supplied joint embedding."
    } else {
        "Cutoff measurement. Landmarks are the occurrences in the supplied joint embedding. A later occurrence stays out when that embedding was fit without it."
    }
    lines += "States, transitions, and concepts share one landmark space. The distance is the cosine of their first-order similarities to those occurrences. This is not a rectangular factorization."
    lines += "The occurrence's own landmark is missing. Partial cosine skips it, so identity similarity does not dominate the profile. Neighbors of an occurrence are explained by the other landmarks."
    lines += "First-order signs are kept. A negative similarity is not clipped and is not written as zero."
    lines += "A concept coordinate is nonzero only where that concept has a first-order value. Overlapping occurrence landmarks can make two concepts close while their direct cosine stays zero. Concepts on disjoint occurrences stay at similarity zero even when those occurrences are similar. The occurrence kernel is not applied a second time."
    lines += "Vocabulary support is audit metadata and is not a landmark coordinate. Aliases and recorded labels stay on the audit."
    lines += "The joint-embedding baseline stays the first-order control."
    embedding.layers.filter { it.unreliable }.forEach { layer ->
        lines += "${layer.name}: ${layer.unreliableBecause}"
    }
    lines += "This measurement does not cluster, name a region, recommend, or estimate a causal effect."
    return lines
}
