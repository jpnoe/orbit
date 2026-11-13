package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.min

/**
 * Exploratory hard groups of states in the frozen full-dimensional geometry.
 *
 * Only state-to-state partial cosine decides membership. Transitions and
 * concepts describe a group after the cut is fixed. Layout coordinates are
 * applied after that cut and are not similarities.
 *
 * Distributional context stays a second-order profile. It is not graph radius 2.
 */
const val PERSONAL_SEMANTIC_STATE_GROUP_ATLAS = "personal-semantic-state-group-atlas-v1"

const val STATE_GROUP_UI_DEFAULT =
    "UI default: the cut nearest eight groups. Not a selected partition."

private const val GROUP_METHOD =
    "Complete-linkage groups of states. Merge similarity is the minimum " +
        "cross-pair partial cosine. Layout uses average source distance after the cut."

private val CORE_AXES = listOf(
    DefaultCheckInAxes.MOOD,
    DefaultCheckInAxes.ENERGY,
    DefaultCheckInAxes.CONFIDENCE,
    DefaultCheckInAxes.ANXIETY,
)

data class StateGroupAtlasDataset(
    val protocol: String = PERSONAL_SEMANTIC_STATE_GROUP_ATLAS,
    val method: String = GROUP_METHOD,
    val vocabularyScope: String,
    val retrospective: Boolean,
    val views: List<StateGroupAtlasView>,
    val judgments: List<String>,
    val exploratoryHardPartition: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_STATE_GROUP_ATLAS)
        require(exploratoryHardPartition)
        require(!causal && !predictive && !updatesModel && !suitableForRecommendation)
        require(views.map { it.geometry to it.context } == GROUP_VIEWS)
        views.forEach { view ->
            require(view.displayDefaultCutIndex in view.cuts.indices || view.cuts.isEmpty())
        }
    }
}

data class StateGroupAtlasView(
    val geometry: String,
    val geometryLabel: String,
    val context: String,
    val contextLabel: String,
    val sourceDimensionality: Int,
    val ungrouped: List<StateGroupUngrouped>,
    val merges: List<StateGroupMerge>,
    val cuts: List<StateGroupCut>,
    val displayDefaultCutIndex: Int,
    val displayDefaultReason: String = STATE_GROUP_UI_DEFAULT,
)

data class StateGroupUngrouped(
    val id: String,
    val label: String,
    val reason: String,
)

data class StateGroupMerge(
    val nodeId: String,
    val similarity: Double,
    val leftMembers: List<String>,
    val rightMembers: List<String>,
    val members: List<String>,
)

data class StateGroupCut(
    val minimumSimilarity: Double?,
    val groups: List<StateGroup>,
    val singletonCount: Int,
    val smallestSupport: Int,
    val medianSupport: Double?,
    val largestSupport: Int,
    val minimumWithinSimilarity: Double?,
    val meanWithinSimilarity: Double?,
    val layoutStress: Double?,
    val positiveDimensions: Int,
    val retainedDimensions: Int,
    val negativeEigenvalueShare: Double,
    val layoutNote: String,
    val edges: List<StateGroupEdge>,
    val unresolvedTransitions: List<StateGroupUnresolved>,
)

data class StateGroup(
    val alias: String,
    val nodeId: String,
    val members: List<StateGroupMember>,
    val x: Double?,
    val y: Double?,
    val medoidId: String,
    val medoidLabel: String,
    val earliestLabel: String,
    val latestLabel: String,
    val minimumSimilarity: Double?,
    val meanSimilarity: Double?,
    val maximumSimilarity: Double?,
    val formedAtSimilarity: Double?,
    val tighterChildren: List<String>,
    val looserParent: String?,
    val continuesAs: String?,
    val emotions: List<StateGroupEmotionCount>,
    val dominantEmotion: String?,
    val dominantEmotionCount: Int,
    val axes: List<StateGroupAxisSummary>,
    val carriedConcepts: List<StateGroupConceptCount>,
    val incoming: StateGroupDirection,
    val outgoing: StateGroupDirection,
)

data class StateGroupMember(
    val id: String,
    val alias: String,
    val label: String,
    val recordedAt: Long,
    val localDate: String,
)

data class StateGroupEmotionCount(
    val name: String,
    val count: Int,
    val proportion: Double,
)

data class StateGroupAxisSummary(
    val axisId: String,
    val observed: Int,
    val missing: Int,
    val conflicting: Int,
    val minimum: Double?,
    val firstQuartile: Double?,
    val median: Double?,
    val mean: Double?,
    val thirdQuartile: Double?,
    val maximum: Double?,
)

data class StateGroupConceptCount(
    val label: String,
    val type: String,
    val occurrences: Int,
    val members: Int,
)

data class StateGroupDirection(
    val transitionCount: Int,
    val selfLoopCount: Int,
    val linkedGroups: List<StateGroupLinkCount>,
    val boundaryCount: Int,
    val ungroupedEndpointCount: Int,
    val duration: StateGroupNumberSummary?,
    val concepts: List<StateGroupConceptCount>,
    val categories: List<StateGroupCategoryCount>,
    val quantities: List<StateGroupQuantity>,
)

data class StateGroupLinkCount(
    val label: String,
    val count: Int,
)

data class StateGroupCategoryCount(
    val category: String,
    val reviewedEmpty: Int,
    val reviewedEntries: Int,
    val unreviewed: Int,
    val absent: Int,
    val conflicting: Int,
)

data class StateGroupQuantity(
    val label: String,
    val unit: String,
    val values: List<Double>,
    val unparsedCount: Int,
)

data class StateGroupNumberSummary(
    val count: Int,
    val minimum: Double,
    val median: Double,
    val mean: Double,
    val maximum: Double,
)

data class StateGroupEdge(
    val sourceAlias: String,
    val targetAlias: String,
    val selfLoop: Boolean,
    val transitions: List<StateGroupTransitionRef>,
    val duration: StateGroupNumberSummary?,
    val axisChanges: List<StateGroupAxisChange>,
    val concepts: List<StateGroupConceptCount>,
    val categories: List<StateGroupCategoryCount>,
    val quantities: List<StateGroupQuantity>,
) {
    val transitionCount: Int get() = transitions.size
}

data class StateGroupTransitionRef(
    val id: String,
    val alias: String,
    val label: String,
)

data class StateGroupAxisChange(
    val axisId: String,
    val observed: Int,
    val missing: Int,
    val minimum: Double?,
    val median: Double?,
    val mean: Double?,
    val maximum: Double?,
)

data class StateGroupUnresolved(
    val id: String,
    val label: String,
    val reason: String,
)

fun projectPersonalSemanticStateGroupAtlas(
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection,
): StateGroupAtlasDataset {
    val facts = transitionFacts(graph, projection, embedding, distributional.reading)
    val views = listOf(
        groupView(
            geometry = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
            geometryLabel = "Direct incidence",
            context = LAYER_ZERO,
            contextLabel = "Layer zero",
            dimensionality = embedding.layer(LAYER_ZERO).dimensionality,
            vectors = embedding.layer(LAYER_ZERO).nodes.associate { it.id to it.coordinates },
            cosine = embedding.layer(LAYER_ZERO)::similarity,
            embedding = embedding,
            distributional = distributional,
            projection = projection,
            graph = graph,
            facts = facts,
        ),
        groupView(
            geometry = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
            geometryLabel = "Direct incidence",
            context = RECIPROCAL_LAYER,
            contextLabel = "Reciprocal",
            dimensionality = embedding.layer(RECIPROCAL_LAYER).dimensionality,
            vectors = embedding.layer(RECIPROCAL_LAYER).nodes.associate { it.id to it.coordinates },
            cosine = embedding.layer(RECIPROCAL_LAYER)::similarity,
            embedding = embedding,
            distributional = distributional,
            projection = projection,
            graph = graph,
            facts = facts,
        ),
        groupView(
            geometry = PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT,
            geometryLabel = "Distributional context",
            context = LAYER_ZERO,
            contextLabel = "Layer zero",
            dimensionality = distributional.layer(LAYER_ZERO).dimensionality,
            vectors = distributional.layer(LAYER_ZERO).nodes.associate { it.id to it.profile },
            cosine = distributional.layer(LAYER_ZERO)::similarity,
            embedding = embedding,
            distributional = distributional,
            projection = projection,
            graph = graph,
            facts = facts,
        ),
        groupView(
            geometry = PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT,
            geometryLabel = "Distributional context",
            context = RECIPROCAL_LAYER,
            contextLabel = "Reciprocal",
            dimensionality = distributional.layer(RECIPROCAL_LAYER).dimensionality,
            vectors = distributional.layer(RECIPROCAL_LAYER).nodes.associate { it.id to it.profile },
            cosine = distributional.layer(RECIPROCAL_LAYER)::similarity,
            embedding = embedding,
            distributional = distributional,
            projection = projection,
            graph = graph,
            facts = facts,
        ),
    )
    return StateGroupAtlasDataset(
        vocabularyScope = embedding.vocabularyScope,
        retrospective = embedding.retrospective,
        views = views,
        judgments = groupJudgments(embedding),
    )
}

internal fun completeLinkage(
    ids: List<String>,
    similarity: (String, String) -> Double,
): LinkageResult {
    val ordered = ids.distinct().sorted()
    val active = ordered.map { LinkageNode(it, listOf(it), null, null, null) }.toMutableList()
    val cuts = mutableListOf(LinkageSnapshot(active.toList(), null))
    val merges = mutableListOf<StateGroupMerge>()
    val cache = mutableMapOf<Pair<String, String>, Double>()
    while (active.size > 1) {
        var bestI = 0
        var bestJ = 1
        var bestSim = Double.NEGATIVE_INFINITY
        var bestLeft = emptyList<String>()
        var bestRight = emptyList<String>()
        var found = false
        for (left in active.indices) {
            for (right in left + 1 until active.size) {
                val sim = clusterSimilarity(active[left], active[right], similarity, cache)
                if (!sim.isFinite()) continue
                val pair = orderedMembers(active[left].members, active[right].members)
                if (!found || sim > bestSim || (sim == bestSim && memberKeyBefore(pair, bestLeft to bestRight))) {
                    found = true
                    bestI = left
                    bestJ = right
                    bestSim = sim
                    bestLeft = pair.first
                    bestRight = pair.second
                }
            }
        }
        if (!found) break
        val leftNode = active[bestI]
        val rightNode = active[bestJ]
        val members = (leftNode.members + rightNode.members).sorted()
        val node = LinkageNode(
            id = "merge:${merges.size}",
            members = members,
            left = leftNode,
            right = rightNode,
            formedAt = bestSim,
        )
        merges += StateGroupMerge(node.id, bestSim, leftNode.members, rightNode.members, members)
        val removed = setOf(bestI, bestJ)
        val next = active.filterIndexed { index, _ -> index !in removed } + node
        active.clear()
        active += next
        val continued = active.size > 1 && nextMergeSimilarity(active, similarity, cache) == bestSim
        if (!continued) cuts += LinkageSnapshot(active.toList(), bestSim)
    }
    return LinkageResult(
        merges = merges,
        cuts = cuts.map { snapshot ->
            LinkageCut(
                minimumSimilarity = snapshot.level,
                groups = snapshot.nodes.map { node ->
                    LinkageGroup(node.id, node.members, node.formedAt, node.left?.id, node.right?.id)
                },
            )
        },
    )
}

private data class LinkageSnapshot(
    val nodes: List<LinkageNode>,
    val level: Double?,
)

internal fun nearestEightIndex(groupCounts: List<Int>): Int {
    if (groupCounts.isEmpty()) return 0
    var best = 0
    var bestDistance = Int.MAX_VALUE
    groupCounts.forEachIndexed { index, count ->
        val distance = kotlin.math.abs(count - 8)
        if (distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

internal data class LinkageResult(
    val merges: List<StateGroupMerge>,
    val cuts: List<LinkageCut>,
)

internal data class LinkageCut(
    val minimumSimilarity: Double?,
    val groups: List<LinkageGroup>,
)

internal data class LinkageGroup(
    val nodeId: String,
    val members: List<String>,
    val formedAt: Double?,
    val leftNodeId: String?,
    val rightNodeId: String?,
)

private data class LinkageNode(
    val id: String,
    val members: List<String>,
    val left: LinkageNode?,
    val right: LinkageNode?,
    val formedAt: Double?,
)

private fun groupView(
    geometry: String,
    geometryLabel: String,
    context: String,
    contextLabel: String,
    dimensionality: Int,
    vectors: Map<String, List<Double>>,
    cosine: (String, String) -> Double,
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
    projection: PersonalSemanticOccurrenceProjection,
    graph: AlternatingTemporalGraph,
    facts: List<TransitionFact>,
): StateGroupAtlasView {
    val stateIds = (vectors.keys + embedding.audit.aliasByNode.keys + projection.states.map { stateNodeId(it.key.raw) })
        .filter { it.startsWith("state:") }
        .distinct()
        .sorted()
    val usable = stateIds.filter { usableVector(vectors[it].orEmpty()) }
    val ungrouped = stateIds.filter { it !in usable }.map { id ->
        StateGroupUngrouped(
            id = id,
            label = distributional.reading.line(id, embedding.audit),
            reason = "no usable coordinates",
        )
    }
    val linkage = completeLinkage(usable) { left, right -> cosine(left, right).coerceIn(-1.0, 1.0) }
    val recorded = memberRecords(projection, graph, embedding, distributional.reading)
    val cuts = linkage.cuts.mapIndexed { index, cut ->
        buildCut(cut, linkage.cuts, index, cosine, recorded, facts, embedding, distributional.reading, projection)
    }
    val counts = cuts.map { it.groups.size }
    return StateGroupAtlasView(
        geometry = geometry,
        geometryLabel = geometryLabel,
        context = context,
        contextLabel = contextLabel,
        sourceDimensionality = dimensionality,
        ungrouped = ungrouped,
        merges = linkage.merges,
        cuts = cuts,
        displayDefaultCutIndex = nearestEightIndex(counts),
    )
}

private fun buildCut(
    cut: LinkageCut,
    cuts: List<LinkageCut>,
    index: Int,
    cosine: (String, String) -> Double,
    recorded: Map<String, StateGroupMember>,
    facts: List<TransitionFact>,
    embedding: JointEmbeddingDataset,
    reading: DistributionalReadingIndex,
    projection: PersonalSemanticOccurrenceProjection,
): StateGroupCut {
    val ordered = orderedGroups(cut.groups, recorded)
    val aliases = ordered.mapIndexed { position, group -> group.nodeId to alias(position) }.toMap()
    val memberAlias = ordered.flatMap { group -> group.members.map { member -> member to aliases.getValue(group.nodeId) } }.toMap()
    val tighter = cuts.getOrNull(index - 1)
    val looser = cuts.getOrNull(index + 1)
    val placed = layoutGroups(ordered, aliases, cosine)
    val groups = ordered.map { group ->
        val members = group.members.map { id ->
            recorded[id] ?: StateGroupMember(id, embedding.audit.aliasByNode[id] ?: "state", reading.line(id, embedding.audit), Long.MAX_VALUE, "")
        }.sortedWith(compareBy({ it.recordedAt }, { it.id }))
        val pairStats = pairStats(group.members, cosine)
        val emotions = emotionCounts(members, projection)
        val dominant = emotions.firstOrNull { it.name != "none" }
        val alias = aliases.getValue(group.nodeId)
        val medoidId = medoid(group.members, cosine, recorded)
        StateGroup(
            alias = alias,
            nodeId = group.nodeId,
            members = members,
            x = placed.coordinates[alias]?.x,
            y = placed.coordinates[alias]?.y,
            medoidId = medoidId,
            medoidLabel = reading.line(medoidId, embedding.audit),
            earliestLabel = members.minByOrNull { it.recordedAt }?.label ?: alias,
            latestLabel = members.maxByOrNull { it.recordedAt }?.label ?: alias,
            minimumSimilarity = pairStats?.minimum,
            meanSimilarity = pairStats?.mean,
            maximumSimilarity = pairStats?.maximum,
            formedAtSimilarity = group.formedAt,
            tighterChildren = tighterChildren(group.members, tighter, recorded),
            looserParent = looserParent(group.members, looser, recorded),
            continuesAs = continuedAlias(group.members, looser, recorded),
            emotions = emotions,
            dominantEmotion = dominant?.name,
            dominantEmotionCount = dominant?.count ?: 0,
            axes = axisSummaries(members, projection),
            carriedConcepts = carriedConcepts(members, projection, reading, embedding.audit),
            incoming = directionSummary(group.members, facts, memberAlias, incoming = true, reading, embedding.audit),
            outgoing = directionSummary(group.members, facts, memberAlias, incoming = false, reading, embedding.audit),
        )
    }
    val within = groups.flatMap { group -> withinPairs(group.members.map { it.id }, cosine) }
    val supports = groups.map { it.members.size }
    val edges = aggregateEdges(facts, memberAlias, reading, embedding.audit)
    val unresolved = facts.filter { fact ->
        val start = fact.startId
        start == null || start !in memberAlias || fact.endId !in memberAlias
    }.map { fact ->
        val reason = when {
            fact.startId == null || fact.boundary -> "boundary or unresolved start"
            fact.startId !in memberAlias -> "starting state is ungrouped"
            else -> "ending state is ungrouped"
        }
        StateGroupUnresolved(fact.id, fact.label, reason)
    }.sortedBy { it.label }
    return StateGroupCut(
        minimumSimilarity = cut.minimumSimilarity,
        groups = groups,
        singletonCount = groups.count { it.members.size == 1 },
        smallestSupport = supports.minOrNull() ?: 0,
        medianSupport = if (supports.isEmpty()) null else percentile(supports.map { it.toDouble() }.sorted(), 0.5),
        largestSupport = supports.maxOrNull() ?: 0,
        minimumWithinSimilarity = within.minOrNull(),
        meanWithinSimilarity = if (within.isEmpty()) null else within.average(),
        layoutStress = placed.stress,
        positiveDimensions = placed.positiveDimensions,
        retainedDimensions = placed.retainedDimensions,
        negativeEigenvalueShare = placed.negativeShare,
        layoutNote = placed.note,
        edges = edges,
        unresolvedTransitions = unresolved,
    )
}

private data class PlacedGroups(
    val coordinates: Map<String, SemanticAtlasCoordinate>,
    val stress: Double?,
    val positiveDimensions: Int,
    val retainedDimensions: Int,
    val negativeShare: Double,
    val note: String,
)

private fun layoutGroups(
    groups: List<LinkageGroup>,
    aliases: Map<String, String>,
    cosine: (String, String) -> Double,
): PlacedGroups {
    val named = groups.map { aliases.getValue(it.nodeId) to it.members }
    if (named.isEmpty()) {
        return PlacedGroups(emptyMap(), null, 0, 0, 0.0, "No grouped states.")
    }
    if (named.size == 1) {
        return PlacedGroups(
            coordinates = mapOf(named.single().first to SemanticAtlasCoordinate(0.0, 0.0)),
            stress = null,
            positiveDimensions = 0,
            retainedDimensions = 0,
            negativeShare = 0.0,
            note = "One group is placed at the layout center. No projection was estimated.",
        )
    }
    if (named.size == 2) {
        val ordered = named.sortedBy { it.first }
        val distance = averageDistance(ordered[0].second, ordered[1].second, cosine)
        val left = if (distance == 0.0) 0.0 else -distance / 2.0
        val right = if (distance == 0.0) 0.0 else distance / 2.0
        return PlacedGroups(
            coordinates = mapOf(
                ordered[0].first to SemanticAtlasCoordinate(left, null),
                ordered[1].first to SemanticAtlasCoordinate(right, null),
            ),
            stress = 0.0,
            positiveDimensions = if (distance == 0.0) 0 else 1,
            retainedDimensions = if (distance == 0.0) 0 else 1,
            negativeShare = 0.0,
            note = "Two groups are placed on a horizontal line. The second layout coordinate is absent.",
        )
    }
    val layout = classicalMdsFromDistances(named.map { it.first }) { left, right ->
        val leftMembers = named.first { it.first == left }.second
        val rightMembers = named.first { it.first == right }.second
        averageDistance(leftMembers, rightMembers, cosine)
    }
    return PlacedGroups(
        coordinates = layout.placed,
        stress = layout.stress,
        positiveDimensions = layout.positiveDimensions,
        retainedDimensions = layout.retainedDimensions,
        negativeShare = layout.negativeEigenvalueShare,
        note = layout.degeneracy.ifBlank {
            "X and Y are layout directions from group distances. They are not a psychological scale."
        },
    )
}

private fun averageDistance(
    left: List<String>,
    right: List<String>,
    cosine: (String, String) -> Double,
): Double {
    var sum = 0.0
    var count = 0
    left.forEach { a ->
        right.forEach { b ->
            sum += 1.0 - cosine(a, b).coerceIn(-1.0, 1.0)
            count += 1
        }
    }
    return if (count == 0) 0.0 else sum / count
}

private fun directionSummary(
    members: List<String>,
    facts: List<TransitionFact>,
    memberAlias: Map<String, String>,
    incoming: Boolean,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): StateGroupDirection {
    val memberSet = members.toSet()
    val selected = facts.filter { fact ->
        if (incoming) fact.endId in memberSet else fact.startId != null && fact.startId in memberSet
    }
    val selfLoops = selected.count { fact ->
        val other = if (incoming) fact.startId else fact.endId
        other != null && memberAlias[other] != null && memberAlias[other] == memberAlias[if (incoming) fact.endId else fact.startId!!]
    }
    val links = selected.groupBy { fact ->
        val other = if (incoming) fact.startId else fact.endId
        when {
            other == null || (incoming && fact.boundary) -> "boundary"
            other !in memberAlias -> "ungrouped"
            else -> memberAlias.getValue(other)
        }
    }.map { (label, rows) -> StateGroupLinkCount(label, rows.size) }
        .sortedWith(compareBy({ it.label == "boundary" || it.label == "ungrouped" }, { it.label }))
    return StateGroupDirection(
        transitionCount = selected.size,
        selfLoopCount = selfLoops,
        linkedGroups = links,
        boundaryCount = links.filter { it.label == "boundary" }.sumOf { it.count },
        ungroupedEndpointCount = links.filter { it.label == "ungrouped" }.sumOf { it.count },
        duration = numberSummary(selected.mapNotNull { it.durationMillis?.toDouble() }),
        concepts = conceptCounts(selected.flatMap { it.concepts }, reading, audit),
        categories = categoryCounts(selected),
        quantities = quantityCounts(selected),
    )
}

private fun aggregateEdges(
    facts: List<TransitionFact>,
    memberAlias: Map<String, String>,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): List<StateGroupEdge> {
    val grouped = facts.mapNotNull { fact ->
        val start = fact.startId ?: return@mapNotNull null
        val source = memberAlias[start] ?: return@mapNotNull null
        val target = memberAlias[fact.endId] ?: return@mapNotNull null
        source to target to fact
    }.groupBy { it.first }
    return grouped.map { (ends, rows) ->
        val transitions = rows.map { it.second }.sortedBy { it.label }
        StateGroupEdge(
            sourceAlias = ends.first,
            targetAlias = ends.second,
            selfLoop = ends.first == ends.second,
            transitions = transitions.map { StateGroupTransitionRef(it.id, it.alias, it.label) },
            duration = numberSummary(transitions.mapNotNull { it.durationMillis?.toDouble() }),
            axisChanges = axisChanges(transitions),
            concepts = conceptCounts(transitions.flatMap { it.concepts }, reading, audit),
            categories = categoryCounts(transitions),
            quantities = quantityCounts(transitions),
        )
    }.sortedWith(compareBy({ it.sourceAlias }, { it.targetAlias }))
}

private fun axisChanges(facts: List<TransitionFact>): List<StateGroupAxisChange> {
    return CORE_AXES.map { axisId ->
        val changes = mutableListOf<Double>()
        var missing = 0
        facts.forEach { fact ->
            val start = fact.startAxes[axisId]
            val end = fact.endAxes[axisId]
            if (start == null || end == null) missing += 1 else changes += (end - start).toDouble()
        }
        val summary = numberSummary(changes)
        StateGroupAxisChange(
            axisId = axisId,
            observed = changes.size,
            missing = missing,
            minimum = summary?.minimum,
            median = summary?.median,
            mean = summary?.mean,
            maximum = summary?.maximum,
        )
    }
}

private fun carriedConcepts(
    members: List<StateGroupMember>,
    projection: PersonalSemanticOccurrenceProjection,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): List<StateGroupConceptCount> {
    val ids = members.map { it.id.removePrefix("state:") }.toSet()
    val links = projection.links.filterIsInstance<ConceptObservationLink.OnState>().filter { it.key.raw in ids }
    return links.groupBy { conceptNodeId(it.concept) }.map { (id, rows) ->
        StateGroupConceptCount(
            label = reading.line(id, audit),
            type = rows.first().concept.type.auditLabel(),
            occurrences = rows.size,
            members = rows.map { it.key.raw }.distinct().size,
        )
    }.sortedWith(compareBy({ it.type }, { it.label }))
}

private fun conceptCounts(
    concepts: List<ConceptFact>,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): List<StateGroupConceptCount> {
    return concepts.groupBy { it.id }.map { (id, rows) ->
        StateGroupConceptCount(
            label = reading.line(id, audit),
            type = rows.first().type,
            occurrences = rows.size,
            members = rows.map { it.owner }.distinct().size,
        )
    }.sortedWith(compareBy({ it.type }, { it.label }))
}

private fun categoryCounts(facts: List<TransitionFact>): List<StateGroupCategoryCount> {
    val names = listOf("activity", "workout", "diet", "contextual usage")
    return names.map { name ->
        val kinds = facts.map { it.categories[name] ?: CategoryObservationKindV2.ABSENT }
        StateGroupCategoryCount(
            category = name,
            reviewedEmpty = kinds.count { it == CategoryObservationKindV2.NONE },
            reviewedEntries = kinds.count { it == CategoryObservationKindV2.ENTRIES },
            unreviewed = kinds.count { it == CategoryObservationKindV2.UNREVIEWED },
            absent = kinds.count { it == CategoryObservationKindV2.ABSENT },
            conflicting = kinds.count { it == CategoryObservationKindV2.CONFLICTING },
        )
    }
}

private fun quantityCounts(facts: List<TransitionFact>): List<StateGroupQuantity> {
    val grouped = facts.flatMap { it.quantities }.groupBy { it.label to it.unit }
    return grouped.map { (key, rows) ->
        StateGroupQuantity(
            label = key.first,
            unit = key.second,
            values = rows.mapNotNull { it.value }.sorted(),
            unparsedCount = rows.count { it.value == null },
        )
    }.sortedWith(compareBy({ it.label }, { it.unit }))
}

private fun axisSummaries(
    members: List<StateGroupMember>,
    projection: PersonalSemanticOccurrenceProjection,
): List<StateGroupAxisSummary> {
    val states = members.mapNotNull { member -> projection.states.firstOrNull { stateNodeId(it.key.raw) == member.id } }
    return CORE_AXES.map { axisId ->
        val values = mutableListOf<Double>()
        var missing = 0
        var conflicting = 0
        states.forEach { state ->
            val axis = state.axes.firstOrNull { it.axisId == axisId }
            when {
                axis == null || axis.status == AxisOccurrenceStatus.MISSING -> missing += 1
                axis.status == AxisOccurrenceStatus.CONFLICTING -> conflicting += 1
                axis.raw != null -> values += axis.raw.toDouble()
                else -> missing += 1
            }
        }
        val sorted = values.sorted()
        StateGroupAxisSummary(
            axisId = axisId,
            observed = values.size,
            missing = missing,
            conflicting = conflicting,
            minimum = sorted.firstOrNull(),
            firstQuartile = if (sorted.isEmpty()) null else percentile(sorted, 0.25),
            median = if (sorted.isEmpty()) null else percentile(sorted, 0.5),
            mean = if (sorted.isEmpty()) null else sorted.average(),
            thirdQuartile = if (sorted.isEmpty()) null else percentile(sorted, 0.75),
            maximum = sorted.lastOrNull(),
        )
    }
}

private fun emotionCounts(
    members: List<StateGroupMember>,
    projection: PersonalSemanticOccurrenceProjection,
): List<StateGroupEmotionCount> {
    if (members.isEmpty()) return emptyList()
    val names = members.map { member ->
        projection.states.firstOrNull { stateNodeId(it.key.raw) == member.id }?.emotion?.name?.takeIf { it.isNotBlank() } ?: "none"
    }
    return names.groupingBy { it }.eachCount().map { (name, count) ->
        StateGroupEmotionCount(name, count, count.toDouble() / members.size)
    }.sortedWith(compareByDescending<StateGroupEmotionCount> { it.count }.thenBy { it.name })
}

private fun medoid(
    members: List<String>,
    cosine: (String, String) -> Double,
    recorded: Map<String, StateGroupMember>,
): String {
    return members.minWith(compareBy<String>({ meanDistance(it, members, cosine) }, { recorded[it]?.recordedAt ?: Long.MAX_VALUE }, { it }))
}

private fun meanDistance(id: String, members: List<String>, cosine: (String, String) -> Double): Double {
    val others = members.filter { it != id }
    if (others.isEmpty()) return 0.0
    return others.map { 1.0 - cosine(id, it).coerceIn(-1.0, 1.0) }.average()
}

private data class PairStats(val minimum: Double, val mean: Double, val maximum: Double)

private fun pairStats(members: List<String>, cosine: (String, String) -> Double): PairStats? {
    val pairs = withinPairs(members, cosine)
    if (pairs.isEmpty()) return null
    return PairStats(pairs.min(), pairs.average(), pairs.max())
}

private fun withinPairs(members: List<String>, cosine: (String, String) -> Double): List<Double> {
    val pairs = mutableListOf<Double>()
    for (left in members.indices) {
        for (right in left + 1 until members.size) {
            pairs += cosine(members[left], members[right]).coerceIn(-1.0, 1.0)
        }
    }
    return pairs
}

private fun tighterChildren(
    members: List<String>,
    tighter: LinkageCut?,
    recorded: Map<String, StateGroupMember>,
): List<String> {
    if (tighter == null) return emptyList()
    val parts = tighter.groups.filter { it.members.toSet().all { member -> member in members } && it.members.size < members.size }
    if (parts.isEmpty()) return emptyList()
    val aliases = aliasMap(tighter, recorded)
    return parts.map { aliases.getValue(it.nodeId) }.sorted()
}

private fun looserParent(
    members: List<String>,
    looser: LinkageCut?,
    recorded: Map<String, StateGroupMember>,
): String? {
    if (looser == null) return null
    val parent = looser.groups.firstOrNull { group ->
        val set = group.members.toSet()
        members.all { it in set } && set.size > members.size
    } ?: return null
    return aliasMap(looser, recorded).getValue(parent.nodeId)
}

private fun continuedAlias(
    members: List<String>,
    looser: LinkageCut?,
    recorded: Map<String, StateGroupMember>,
): String? {
    if (looser == null) return null
    val same = looser.groups.firstOrNull { it.members.toSet() == members.toSet() } ?: return null
    return aliasMap(looser, recorded).getValue(same.nodeId)
}

private fun aliasMap(cut: LinkageCut, recorded: Map<String, StateGroupMember>): Map<String, String> {
    return orderedGroups(cut.groups, recorded).mapIndexed { index, group -> group.nodeId to alias(index) }.toMap()
}

private fun orderedGroups(
    groups: List<LinkageGroup>,
    recorded: Map<String, StateGroupMember>,
): List<LinkageGroup> {
    return groups.sortedWith(
        compareBy(
            { earliest(it.members, recorded) },
            { earliestMemberId(it.members, recorded) },
            { it.members.joinToString("|") },
        ),
    )
}

private fun earliestMemberId(members: List<String>, recorded: Map<String, StateGroupMember>): String {
    val earliestAt = earliest(members, recorded)
    return members.filter { (recorded[it]?.recordedAt ?: Long.MAX_VALUE) == earliestAt }.minOrNull()
        ?: members.minOrNull()
        ?: ""
}

private fun earliest(members: List<String>, recorded: Map<String, StateGroupMember>): Long {
    return members.minOfOrNull { recorded[it]?.recordedAt ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
}

private fun alias(index: Int): String = "G" + (index + 1).toString().padStart(2, '0')

private fun memberRecords(
    projection: PersonalSemanticOccurrenceProjection,
    graph: AlternatingTemporalGraph,
    embedding: JointEmbeddingDataset,
    reading: DistributionalReadingIndex,
): Map<String, StateGroupMember> {
    val dates = graph.states.associate { stateNodeId(it.id) to it.state.localDate }
    return projection.states.associate { state ->
        val id = stateNodeId(state.key.raw)
        id to StateGroupMember(
            id = id,
            alias = embedding.audit.aliasByNode[id] ?: "state",
            label = reading.line(id, embedding.audit),
            recordedAt = state.recordedAt,
            localDate = dates[id].orEmpty(),
        )
    }
}

private data class TransitionFact(
    val id: String,
    val alias: String,
    val label: String,
    val startId: String?,
    val endId: String,
    val boundary: Boolean,
    val durationMillis: Long?,
    val concepts: List<ConceptFact>,
    val categories: Map<String, CategoryObservationKindV2>,
    val quantities: List<QuantityFact>,
    val startAxes: Map<String, Int>,
    val endAxes: Map<String, Int>,
)

private data class ConceptFact(
    val id: String,
    val type: String,
    val owner: String,
)

private data class QuantityFact(
    val label: String,
    val unit: String,
    val value: Double?,
)

private fun transitionFacts(
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection,
    embedding: JointEmbeddingDataset,
    reading: DistributionalReadingIndex,
): List<TransitionFact> {
    val axes = projection.states.associate { state ->
        stateNodeId(state.key.raw) to state.axes.filter { it.status == AxisOccurrenceStatus.OBSERVED && it.raw != null }
            .associate { it.axisId to it.raw!! }
    }
    val links = projection.links.filterIsInstance<ConceptObservationLink.OnTransition>().groupBy { transitionNodeId(it.key.raw) }
    return graph.transitions.map { transition ->
        val id = transitionNodeId(transition.id)
        val interval = transition.interval
        val conceptLinks = links[id].orEmpty()
        TransitionFact(
            id = id,
            alias = embedding.audit.aliasByNode[id] ?: "transition",
            label = reading.line(id, embedding.audit),
            startId = transition.startStateId?.let(::stateNodeId),
            endId = stateNodeId(transition.endStateId),
            boundary = interval.startBasis == CheckInContextStartBasis.WAKE_TIME ||
                interval.startBasis == CheckInContextStartBasis.DAY_START ||
                transition.startStateId == null,
            durationMillis = if (interval.completedAt != null && interval.durationMillis >= 0L && interval.windowEndAt >= interval.windowStartAt) {
                interval.durationMillis
            } else {
                null
            },
            concepts = conceptLinks.map { link ->
                ConceptFact(conceptNodeId(link.concept), link.concept.type.auditLabel(), id)
            },
            categories = mapOf(
                "activity" to interval.activity.kind,
                "workout" to interval.workout.kind,
                "diet" to interval.diet.kind,
                "contextual usage" to interval.usage.kind,
            ),
            quantities = conceptLinks.flatMap { link -> quantitiesOf(link) },
            startAxes = transition.startStateId?.let { axes[stateNodeId(it)].orEmpty() }.orEmpty(),
            endAxes = axes[stateNodeId(transition.endStateId)].orEmpty(),
        )
    }
}

private fun quantitiesOf(link: ConceptObservationLink.OnTransition): List<QuantityFact> {
    val label = link.label.ifBlank { link.concept.type.auditLabel() }
    return when (val quantity = link.quantity) {
        null -> emptyList()
        is OccurrenceQuantity.WorkoutMinutes -> listOf(QuantityFact(label, "minutes", quantity.minutes.toDouble()))
        is OccurrenceQuantity.UsageAmounts -> quantity.amounts.map { amount ->
            if (amount.status == UsageQuantityStatus.PARSED && amount.numericAmount != null) {
                QuantityFact(label, amount.unit ?: "count", amount.numericAmount)
            } else {
                QuantityFact(label, "unparsed", null)
            }
        }
    }
}

private fun numberSummary(values: List<Double>): StateGroupNumberSummary? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return StateGroupNumberSummary(
        count = sorted.size,
        minimum = sorted.first(),
        median = percentile(sorted, 0.5),
        mean = sorted.average(),
        maximum = sorted.last(),
    )
}

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    if (sorted.size == 1) return sorted.first()
    val index = fraction * (sorted.size - 1)
    val lower = index.toInt()
    val upper = min(lower + 1, sorted.lastIndex)
    val weight = index - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

private fun clusterSimilarity(
    left: LinkageNode,
    right: LinkageNode,
    similarity: (String, String) -> Double,
    cache: MutableMap<Pair<String, String>, Double>,
): Double {
    val key = if (left.id < right.id) left.id to right.id else right.id to left.id
    return cache.getOrPut(key) {
        when {
            left.left == null && right.left == null -> similarity(left.members.single(), right.members.single())
            left.left != null -> minOf(
                clusterSimilarity(left.left, right, similarity, cache),
                clusterSimilarity(checkNotNull(left.right), right, similarity, cache),
            )
            else -> minOf(
                clusterSimilarity(checkNotNull(right.left), left, similarity, cache),
                clusterSimilarity(checkNotNull(right.right), left, similarity, cache),
            )
        }
    }
}

private fun nextMergeSimilarity(
    active: List<LinkageNode>,
    similarity: (String, String) -> Double,
    cache: MutableMap<Pair<String, String>, Double>,
): Double? {
    var best: Double? = null
    for (left in active.indices) {
        for (right in left + 1 until active.size) {
            val sim = clusterSimilarity(active[left], active[right], similarity, cache)
            if (best == null || sim > best) best = sim
        }
    }
    return best
}

private fun orderedMembers(left: List<String>, right: List<String>): Pair<List<String>, List<String>> {
    return if (compareMembers(left, right) <= 0) left to right else right to left
}

private fun memberKeyBefore(candidate: Pair<List<String>, List<String>>, best: Pair<List<String>, List<String>>): Boolean {
    val compared = compareMembers(candidate.first, best.first)
    if (compared != 0) return compared < 0
    return compareMembers(candidate.second, best.second) < 0
}

private fun compareMembers(left: List<String>, right: List<String>): Int {
    val count = min(left.size, right.size)
    for (index in 0 until count) {
        val compared = left[index].compareTo(right[index])
        if (compared != 0) return compared
    }
    return left.size.compareTo(right.size)
}

private fun usableVector(values: List<Double>): Boolean {
    var sum = 0.0
    var saw = false
    values.forEach { value ->
        if (value.isNaN()) return@forEach
        saw = true
        sum += value * value
    }
    return saw && sum > 1e-18
}

private fun groupJudgments(embedding: JointEmbeddingDataset): List<String> {
    val scope = if (embedding.retrospective && embedding.vocabularyScope == RETROSPECTIVE_VOCABULARY_SCOPE) {
        "Retrospective full history."
    } else {
        "This grouping uses the supplied embedding scope."
    }
    return listOf(
        scope,
        "Exploratory hard partition. It is not a claim that a group is an objective mental state.",
        "Not causal. Not predictive. Not suitable for recommendations.",
        "The threshold is chosen interactively. No threshold is selected as the winner.",
        STATE_GROUP_UI_DEFAULT,
        "X and Y are layout directions only. Group membership and similarity come from the full-dimensional source geometry. Rotating or mirroring this graph does not change its meaning.",
        "Grouping uses full-dimensional state similarities. Transitions and concepts summarize groups and do not define membership.",
        "Layer zero is radius 0. Reciprocal is the existing one-hop layer. Distributional context is a second-order similarity profile and is not graph radius 2.",
        "A state without usable coordinates stays ungrouped. It is not placed at the origin and it is not given a source group.",
        "Incoming and outgoing behavior stay separate. This pass does not draw a behavior effect or a Null residual.",
        "This is a hard-group diagnostic. It does not replace a later soft, overlapping region model.",
    )
}

private val GROUP_VIEWS = listOf(
    PERSONAL_SEMANTIC_JOINT_EMBEDDING to LAYER_ZERO,
    PERSONAL_SEMANTIC_JOINT_EMBEDDING to RECIPROCAL_LAYER,
    PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to LAYER_ZERO,
    PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to RECIPROCAL_LAYER,
)
