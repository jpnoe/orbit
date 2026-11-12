package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.abs
import kotlin.math.round
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
    val reading: DistributionalReadingIndex = DistributionalReadingIndex(),
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

    fun occurrenceLists(
        anchor: String,
        audit: EmbeddingAuditIndex,
        reading: DistributionalReadingIndex = DistributionalReadingIndex(),
        support: Map<String, Int> = emptyMap(),
    ): DistributionalOccurrenceLists {
        val incident = audit.incidentTransitions[anchor].orEmpty()
        val focal = audit.focalConcepts[anchor].orEmpty()
        val carried = present(anchor, focal)
        val omitted = focal.filter { node(it) == null }.map { concept ->
            "${reading.line(concept, audit, support)} · no distributional coordinate"
        }
        val incidentMissing = incident.filter { node(it) == null }.map { transition ->
            "${reading.line(transition, audit)} · no distributional coordinates"
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
    projection: PersonalSemanticOccurrenceProjection? = null,
): DistributionalContextDataset {
    return DistributionalContextDataset(
        vocabularyScope = embedding.vocabularyScope,
        retrospective = embedding.retrospective,
        method = PERSONAL_SEMANTIC_DISTRIBUTIONAL_METHOD,
        supportByConcept = supportByConcept,
        reading = distributionalReading(embedding.audit, projection),
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
    lines += "Vocabulary support is audit metadata and is not a landmark coordinate. The neighborhood uses aliases and recorded labels. Canonical ids and typed keys stay in the provenance index."
    lines += "The joint-embedding baseline stays the first-order control."
    embedding.layers.filter { it.unreliable }.forEach { layer ->
        lines += "${layer.name}: ${layer.unreliableBecause}"
    }
    lines += "This measurement does not cluster, name a region, recommend, or estimate a causal effect."
    return lines
}

data class ProvenanceEntry(
    val alias: String,
    val kind: String,
    val canonical: String,
) {
    fun text(): String = when (kind) {
        "concept" -> "$alias · $canonical"
        else -> "$alias · $kind · $canonical"
    }
}

data class DistributionalReadingIndex(
    val neighborhoodByNode: Map<String, String> = emptyMap(),
    val provenance: List<ProvenanceEntry> = emptyList(),
) {
    fun line(nodeId: String, audit: EmbeddingAuditIndex, support: Map<String, Int> = emptyMap()): String {
        if (nodeId.startsWith("concept:")) {
            val base = neighborhoodByNode[nodeId] ?: conceptFallback(nodeId)
            val count = support[nodeId] ?: return base
            return "$base · support $count"
        }
        return neighborhoodByNode[nodeId] ?: audit.aliasByNode[nodeId] ?: occurrenceKind(nodeId)
    }
}

fun formatDistributionalSimilarity(value: Double): String = formatAuditNumber(value, leadingPlus = false)

fun formatDistributionalContribution(value: Double): String = formatAuditNumber(value, leadingPlus = true)

private fun distributionalReading(
    audit: EmbeddingAuditIndex,
    projection: PersonalSemanticOccurrenceProjection?,
): DistributionalReadingIndex {
    val neighborhood = mutableMapOf<String, String>()
    val provenance = mutableListOf<ProvenanceEntry>()
    val states = projection?.states?.associateBy { stateNodeId(it.key.raw) }.orEmpty()
    val transitions = projection?.transitions?.associateBy { transitionNodeId(it.key.raw) }.orEmpty()
    val labelsByTransition = projection?.links.orEmpty()
        .filterIsInstance<ConceptObservationLink.OnTransition>()
        .groupBy { transitionNodeId(it.key.raw) }
    audit.aliasByNode.toList().sortedBy { it.second }.forEach { (nodeId, alias) ->
        val canonical = audit.canonicalByNode[nodeId] ?: return@forEach
        val kind = occurrenceKind(nodeId)
        neighborhood[nodeId] = when (kind) {
            "state" -> stateSummary(alias, states[nodeId])
            "transition" -> transitionSummary(alias, transitions[nodeId], labelsByTransition[nodeId].orEmpty(), audit)
            else -> alias
        }
        provenance += ProvenanceEntry(alias, kind, canonical)
    }
    audit.labelByConcept.toList().sortedBy { it.second }.forEach { (nodeId, label) ->
        val token = nodeId.removePrefix("concept:")
        val heading = conceptSummary(label, token)
        neighborhood[nodeId] = heading
        provenance += ProvenanceEntry(heading, "concept", token)
    }
    return DistributionalReadingIndex(neighborhood, provenance)
}

private fun stateSummary(alias: String, state: StateOccurrenceProjection?): String {
    if (state == null) return alias
    val parts = mutableListOf(alias)
    state.emotion?.name?.takeIf { it.isNotBlank() }?.let { parts += it }
    val axes = axisSummary(state.axes)
    if (axes.isNotEmpty()) parts += axes
    return parts.joinToString(" · ")
}

private fun axisSummary(axes: List<AxisOccurrence>): String {
    return listOf(
        DefaultCheckInAxes.MOOD to "M",
        DefaultCheckInAxes.ENERGY to "E",
        DefaultCheckInAxes.CONFIDENCE to "C",
        DefaultCheckInAxes.ANXIETY to "A",
    ).mapNotNull { (axisId, letter) ->
        val axis = axes.firstOrNull { it.axisId == axisId } ?: return@mapNotNull null
        val raw = axis.raw
        if (axis.status != AxisOccurrenceStatus.OBSERVED || raw == null) return@mapNotNull null
        "$letter$raw"
    }.joinToString(" ")
}

private fun transitionSummary(
    alias: String,
    transition: TransitionOccurrenceProjection?,
    links: List<ConceptObservationLink.OnTransition>,
    audit: EmbeddingAuditIndex,
): String {
    if (transition == null) return alias
    val parts = mutableListOf(alias)
    val labels = links.map { it.label }.filter { it.isNotBlank() }.distinct()
    if (labels.isNotEmpty()) {
        val shown = labels.take(PRINCIPAL_LABELS)
        val extra = labels.size - shown.size
        parts += shown.joinToString(", ") + if (extra > 0) " +$extra" else ""
    }
    val startAlias = transition.startStateKey?.let { audit.aliasByNode[stateNodeId(it.raw)] }
    val endAlias = audit.aliasByNode[stateNodeId(transition.endStateKey.raw)]
    endpointSummary(startAlias, endAlias, transition.boundary)?.let { parts += it }
    durationSummary(transition)?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun endpointSummary(startAlias: String?, endAlias: String?, boundary: Boolean): String? {
    return when {
        startAlias != null && endAlias != null -> "$startAlias→$endAlias"
        endAlias != null && boundary -> "boundary→$endAlias"
        endAlias != null -> "→$endAlias"
        startAlias != null -> "$startAlias→"
        else -> null
    }
}

private fun durationSummary(transition: TransitionOccurrenceProjection): String? {
    if (!transition.completed || transition.durationMillis <= 0L) return null
    if (transition.endAt < transition.startAt) return null
    if (transition.durationMillis != transition.endAt - transition.startAt) return null
    val minutes = transition.durationMillis / 60_000L
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return when {
        hours > 0L && remainder == 0L -> "${hours}h"
        hours > 0L -> "${hours}h ${remainder}m"
        minutes > 0L -> "${minutes}m"
        else -> {
            val seconds = transition.durationMillis / 1_000L
            if (seconds > 0L) "${seconds}s" else null
        }
    }
}

private fun conceptSummary(label: String, token: String): String {
    val type = friendlyType(token)
    val intent = conceptIntent(token)
    val readable = label.takeIf { it.isNotBlank() && '|' !in it && !it.startsWith("option:") }
    val head = readable ?: type
    return listOfNotNull(head, type.takeIf { it != head }, intent).joinToString(" · ")
}

private fun conceptFallback(nodeId: String): String = friendlyType(nodeId.removePrefix("concept:"))

private fun friendlyType(token: String): String {
    val name = token.substringBefore('|')
    return SemanticConceptType.entries.firstOrNull { it.name == name }?.auditLabel() ?: "concept"
}

private fun conceptIntent(token: String): String? {
    val rest = token.substringAfter('|', "")
    if ('|' !in rest) return null
    return rest.substringAfterLast('|').takeIf { it.isNotBlank() }
}

private fun occurrenceKind(nodeId: String): String = when {
    nodeId.startsWith("state:") -> "state"
    nodeId.startsWith("transition:") -> "transition"
    else -> "concept"
}

private fun formatAuditNumber(value: Double, leadingPlus: Boolean): String {
    val rounded = round(value * 1000.0) / 1000.0
    if (rounded == 0.0) return "0.0"
    val text = rounded.toString()
    return if (leadingPlus && rounded > 0.0) "+$text" else text
}

private const val PRINCIPAL_LABELS = 3
