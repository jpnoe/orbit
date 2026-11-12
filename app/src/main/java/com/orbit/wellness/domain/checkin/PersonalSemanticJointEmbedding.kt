package com.orbit.wellness.domain.checkin

import kotlin.math.sqrt

/**
 * Retrospective joint geometry for one ablation.
 *
 * Layer zero is the control. The reciprocal layer reads retrospective
 * signatures only. States, transitions, and typed concept identities are
 * rows of one column space, so cosine compares any two of them in the same
 * way. A rectangular factorization is not used. Role is a column beside the
 * shared concept identity, not a second identity.
 *
 * The column count is the dimension. It is not truncated. A short spectrum
 * can erase a missing amount or a role contrast, and a Laplacian distance
 * did not keep a stronger role nearer.
 */
const val PERSONAL_SEMANTIC_JOINT_EMBEDDING = "personal-semantic-joint-embedding-baseline-v1"

const val LAYER_ZERO = "layer-zero"

const val RECIPROCAL_LAYER = "reciprocal"

private const val FOCAL = 4.0
private const val INCOMING = 2.0
private const val OUTGOING = 1.0
private const val STARTING = 3.0
private const val ENDING = 5.0
private const val QUANTITY_BASE = 1.0
private const val MAX_NODES = 400
private const val MAX_COLUMNS = 2_000
private const val SMALL_FIT = 12

data class JointEmbeddingDataset(
    val protocol: String = PERSONAL_SEMANTIC_JOINT_EMBEDDING,
    val vocabularyScope: String,
    val retrospective: Boolean,
    val method: String,
    val layers: List<JointEmbeddingLayer>,
    val judgments: List<String>,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_JOINT_EMBEDDING)
        require(retrospective || vocabularyScope == FOLD_VOCABULARY_SCOPE)
        require(!retrospective || vocabularyScope == RETROSPECTIVE_VOCABULARY_SCOPE)
        require(!predictive && !updatesModel && !suitableForRecommendation)
    }

    fun layer(name: String): JointEmbeddingLayer = layers.first { it.name == name }
}

data class JointEmbeddingLayer(
    val name: String,
    val dimensionality: Int,
    val unreliableBecause: String,
    val omittedConstantPresence: List<String>,
    val quantityHeldBack: List<String>,
    val nodes: List<EmbeddedNode>,
    val edges: List<EmbeddingEdge>,
) {
    val unreliable: Boolean get() = unreliableBecause.isNotEmpty()

    fun node(id: String): EmbeddedNode? = nodes.firstOrNull { it.id == id }

    fun similarity(left: String, right: String): Double {
        val a = node(left)?.coordinates ?: return 0.0
        val b = node(right)?.coordinates ?: return 0.0
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

    fun nearest(anchor: String, kind: String, limit: Int = 5): List<EmbeddingNeighbor> {
        if (node(anchor)?.coordinates.isNullOrEmpty()) return emptyList()
        return nodes
            .filter { it.kind == kind && it.id != anchor }
            .map { neighbor ->
                EmbeddingNeighbor(
                    id = neighbor.id,
                    kind = neighbor.kind,
                    similarity = similarity(anchor, neighbor.id),
                    contributors = explain(anchor, neighbor.id),
                )
            }
            .filter { it.similarity > 1e-8 }
            .sortedWith(compareByDescending<EmbeddingNeighbor> { it.similarity }.thenBy { it.id })
            .take(limit)
    }

    fun explain(left: String, right: String): List<String> {
        val direct = edges.filter { it.touches(left, right) }
            .sortedWith(compareByDescending<EmbeddingEdge> { it.weight }.thenBy { it.detail })
        val shared = sharedConcepts(left, right)
        return (direct.map { "${it.kind} · ${it.detail} · ${formatMass(it.weight)}" } + shared).take(4)
    }

    private fun sharedConcepts(left: String, right: String): List<String> {
        if (!left.startsWith("state:") && !left.startsWith("transition:")) return emptyList()
        if (!right.startsWith("state:") && !right.startsWith("transition:")) return emptyList()
        val concepts = edges.filter { it.kind == "presence" || it.kind == "quantity" }
            .mapNotNull { it.other(left) }
            .filter { it.startsWith("concept:") }
            .distinct()
        return concepts.mapNotNull { concept ->
            val leftMass = edges.filter { it.touches(left, concept) }.sumOf { it.weight }
            val rightMass = edges.filter { it.touches(right, concept) }.sumOf { it.weight }
            if (leftMass <= 0.0 || rightMass <= 0.0) return@mapNotNull null
            "shared · ${concept.removePrefix("concept:")} · ${formatMass(minOf(leftMass, rightMass))}"
        }.sorted()
    }
}

data class EmbeddedNode(
    val id: String,
    val kind: String,
    val coordinates: List<Double>,
)

data class EmbeddingNeighbor(
    val id: String,
    val kind: String,
    val similarity: Double,
    val contributors: List<String>,
)

data class EmbeddingEdge(
    val source: String,
    val target: String,
    val kind: String,
    val detail: String,
    val weight: Double,
) {
    fun touches(left: String, right: String): Boolean {
        return (source == left && target == right) || (source == right && target == left)
    }

    fun other(id: String): String? = when (id) {
        source -> target
        target -> source
        else -> null
    }
}

fun stateNodeId(raw: String): String = "state:$raw"

fun transitionNodeId(raw: String): String = "transition:$raw"

fun conceptNodeId(key: ConceptIdentityKey): String = "concept:${conceptToken(key)}"

fun projectJointSemanticEmbedding(
    signatures: ReciprocalSignatureDataset,
    graph: AlternatingTemporalGraph,
): JointEmbeddingDataset {
    val scale = quantityScale(signatures)
    val axes = axisScores(signatures)
    return JointEmbeddingDataset(
        vocabularyScope = signatures.vocabulary.scope,
        retrospective = signatures.vocabulary.retrospective,
        method = METHOD,
        layers = listOf(
            embedLayer(LAYER_ZERO, signatures, graph, reciprocal = false, scale = scale, axes = axes),
            embedLayer(RECIPROCAL_LAYER, signatures, graph, reciprocal = true, scale = scale, axes = axes),
        ),
        judgments = judgments(signatures),
    )
}

private const val METHOD =
    "Cosine in one shared column space. Rows are states, transitions, and typed concept identities. " +
        "The dimension is the number of columns that still vary. It is not truncated."

private fun judgments(signatures: ReciprocalSignatureDataset): List<String> {
    val scope = if (signatures.vocabulary.retrospective) {
        "Retrospective full-history fit. Causal signatures are not read."
    } else {
        "This fit uses the supplied cutoff vocabulary. It is not the full-history experiment. Causal signatures are not read."
    }
    return listOf(
        scope,
        "States, transitions, and typed concepts are rows of one column space. Neighborhoods are cosine. A rectangular factorization is not used, and the column count is not truncated.",
        "A carried concept has a shared identity column and a role column. The column records that the role is present. Focal, incoming, outgoing, starting, and ending stay distinct columns. Their audit masses are 4, 2, 1, 3, and 5, and those masses are not coordinates: a larger mass would point away from a unit concept row. Fork order and a repeated role do not increase the column.",
        "A reciprocal copy writes the concept on the context holder as well as the owner. The identity is shared, and a widely copied concept becomes more central. The graph link is shown as an explanation and is not a coordinate.",
        "Vocabulary support is not a column.",
        "Presence that does not vary within states, and does not vary within transitions, is omitted.",
        "Amount is per interval, scaled within its typed identity and unit on the focal layer-zero readings in this fit. The concept's quantity pole is that maximum. An observed zero keeps an amount-observed column. A missing amount does not. Several amounts on one occurrence, or mixed units, stay out of the fit. No amount-per-hour rate is built.",
        "Duration, record keys, timestamps, display text, provenance, and Null residuals are not coordinates. An observed axis is a state column. A missing axis is skipped and is not copied onto a transition.",
        "Scales use every occurrence in the supplied signatures. A cutoff vocabulary does not by itself remove a later state's axis reading. A walk-forward caller has to pass only the history that belongs to the fold.",
        "Fewer than $SMALL_FIT occurrences makes this neighborhood a small-sample measurement.",
    )
}

private fun embedLayer(
    name: String,
    signatures: ReciprocalSignatureDataset,
    graph: AlternatingTemporalGraph,
    reciprocal: Boolean,
    scale: QuantityFit,
    axes: Map<String, Map<String, Double>>,
): JointEmbeddingLayer {
    val occurrences = occurrenceIds(signatures)
    val found = attachments(signatures, reciprocal)
    val omitted = omittedPresence(found, occurrences)
    val edges = auditEdges(found, omitted, scale, axes, graph, reciprocal)
    val rows = featureRows(found, omitted, scale, axes, occurrences)
    val columns = varyingColumns(rows, occurrences)
    val refused = when {
        occurrences.size > MAX_NODES -> "More than $MAX_NODES occurrences. This baseline did not assign coordinates."
        columns.size > MAX_COLUMNS -> "More than $MAX_COLUMNS columns. This baseline did not assign coordinates."
        else -> ""
    }
    val nodes = if (refused.isNotEmpty()) {
        emptyList()
    } else {
        placedNodes(rows, columns, occurrences)
    }
    val small = if (occurrences.size < SMALL_FIT) {
        "Fewer than $SMALL_FIT occurrences. This neighborhood is a small-sample measurement."
    } else {
        ""
    }
    return JointEmbeddingLayer(
        name = name,
        dimensionality = if (refused.isNotEmpty()) 0 else columns.size,
        unreliableBecause = refused.ifEmpty { small },
        omittedConstantPresence = omitted.sorted(),
        quantityHeldBack = scale.heldBack.sorted(),
        nodes = nodes,
        edges = edges,
    )
}

private fun featureRows(
    found: List<Attachment>,
    omitted: Set<String>,
    scale: QuantityFit,
    axes: Map<String, Map<String, Double>>,
    occurrences: List<String>,
): Map<String, MutableMap<String, Double>> {
    val rows = occurrences.associateWith { mutableMapOf<String, Double>() }
    found.forEach { attachment ->
        val row = rows.getValue(attachment.owner)
        val token = conceptToken(attachment.concept)
        if (token !in omitted) {
            row[identityColumn(token)] = 1.0
            row[roleColumn(attachment.role, token)] = 1.0
        }
        writeQuantity(row, attachment, scale)
    }
    axes.forEach { (id, scores) ->
        val row = rows[id] ?: return@forEach
        scores.forEach { (axis, score) ->
            row[axisObservedColumn(axis)] = 1.0
            row[axisValueColumn(axis)] = score
        }
    }
    return rows
}

private fun writeQuantity(row: MutableMap<String, Double>, attachment: Attachment, scale: QuantityFit) {
    val token = conceptToken(attachment.concept)
    if (scale.heldBack.any { it.startsWith(token) }) return
    val amount = scale.scales[attachment.concept] ?: return
    val read = readAmount(attachment.quantityText)
    if (read !is AmountRead.One || read.unit != amount.unit) return
    val scaled = if (amount.max == amount.min) 0.0 else (read.number - amount.min) / (amount.max - amount.min)
    row[quantityObservedColumn(token)] = 1.0
    row[quantityValueColumn(token)] = scaled
}

private fun varyingColumns(
    rows: Map<String, MutableMap<String, Double>>,
    occurrences: List<String>,
): List<String> {
    val names = rows.values.flatMap { it.keys }.distinct()
    return names.filter { name ->
        val values = occurrences.map { rows.getValue(it)[name] }
        values.distinct().size > 1
    }.sorted()
}

private fun placedNodes(
    rows: Map<String, MutableMap<String, Double>>,
    columns: List<String>,
    occurrences: List<String>,
): List<EmbeddedNode> {
    val nodes = mutableListOf<EmbeddedNode>()
    occurrences.forEach { id ->
        val coordinates = columns.map { column ->
            if (column.startsWith("axis-") && column !in rows.getValue(id)) Double.NaN else rows.getValue(id)[column] ?: 0.0
        }
        if (coordinates.any { !it.isNaN() && it != 0.0 }) {
            nodes += EmbeddedNode(id, kindOf(id), coordinates)
        }
    }
    val tokens = columns.mapNotNull { columnToken(it) }.distinct()
    tokens.forEach { token ->
        val coordinates = columns.map { column ->
            if (columnToken(column) != token) {
                Double.NaN
            } else if (column.startsWith("quantity-value|")) {
                1.0
            } else if (column.startsWith("quantity-observed|") || column.startsWith("identity|")) {
                1.0
            } else {
                1.0
            }
        }
        if (coordinates.any { !it.isNaN() }) {
            nodes += EmbeddedNode(conceptNodeId(token), "concept", coordinates)
        }
    }
    return nodes.sortedBy { it.id }
}

private fun auditEdges(
    found: List<Attachment>,
    omitted: Set<String>,
    scale: QuantityFit,
    axes: Map<String, Map<String, Double>>,
    graph: AlternatingTemporalGraph,
    reciprocal: Boolean,
): List<EmbeddingEdge> {
    val edges = mutableListOf<EmbeddingEdge>()
    found.forEach { attachment ->
        if (conceptToken(attachment.concept) !in omitted) {
            edges += EmbeddingEdge(
                source = attachment.owner,
                target = conceptNodeId(attachment.concept),
                kind = "presence",
                detail = "${attachment.role} · ${conceptToken(attachment.concept)}",
                weight = attachment.roleWeight,
            )
        }
        quantityEdge(attachment, scale)?.let { edges += it }
    }
    edges += axisEdges(axes)
    if (reciprocal) edges += linkEdges(graph)
    return edges.sortedWith(compareBy({ it.source }, { it.target }, { it.kind }, { it.detail }))
}

private data class Attachment(
    val owner: String,
    val concept: ConceptIdentityKey,
    val role: String,
    val roleWeight: Double,
    val quantityText: String?,
)

private data class QuantityFit(
    val scales: Map<ConceptIdentityKey, AmountScale>,
    val heldBack: List<String>,
)

private data class AmountScale(val unit: String, val min: Double, val max: Double)

private sealed interface AmountRead {
    data object None : AmountRead
    data class One(val number: Double, val unit: String) : AmountRead
    data object Several : AmountRead
}

private fun quantityScale(signatures: ReciprocalSignatureDataset): QuantityFit {
    val focal = attachments(signatures, reciprocal = false)
        .filter { it.owner.startsWith("transition:") && it.role == "focal" }
    val scales = mutableMapOf<ConceptIdentityKey, AmountScale>()
    val heldBack = mutableListOf<String>()
    focal.groupBy { it.concept }.forEach { (concept, group) ->
        val reads = group.map { readAmount(it.quantityText) }
        val token = conceptToken(concept)
        if (reads.any { it is AmountRead.Several }) {
            heldBack += "$token · several amounts on one occurrence"
            return@forEach
        }
        val numbers = reads.filterIsInstance<AmountRead.One>()
        val units = numbers.map { it.unit }.distinct()
        if (units.size > 1) {
            heldBack += "$token · mixed units"
            return@forEach
        }
        if (numbers.isEmpty()) return@forEach
        scales[concept] = AmountScale(units.single(), numbers.minOf { it.number }, numbers.maxOf { it.number })
    }
    return QuantityFit(scales, heldBack)
}

private fun axisScores(signatures: ReciprocalSignatureDataset): Map<String, Map<String, Double>> {
    val observed = mutableMapOf<String, MutableMap<String, Double>>()
    signatures.states.forEach { state ->
        val readings = mutableMapOf<String, Double>()
        state.layerZero.semantic().filter { it.namespace == FeatureNamespace.NUMERIC }.forEach { feature ->
            feature.value.toDoubleOrNull()?.let { readings[feature.name] = it }
        }
        if (readings.isNotEmpty()) observed[stateNodeId(state.key.raw)] = readings
    }
    val standardized = observed.values.flatMap { it.keys }.distinct().sorted().associateWith { name ->
        val values = observed.values.mapNotNull { it[name] }
        if (values.size < 2) return@associateWith null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        if (variance <= 1e-12) return@associateWith null
        val deviation = sqrt(variance)
        observed.mapNotNull { (id, readings) -> readings[name]?.let { id to (it - mean) / deviation } }.toMap()
    }
    val scores = mutableMapOf<String, MutableMap<String, Double>>()
    standardized.forEach { (name, values) ->
        values?.forEach { (id, score) -> scores.getOrPut(id) { mutableMapOf() }[name] = score }
    }
    return scores.mapValues { it.value.toMap() }
}

private fun attachments(signatures: ReciprocalSignatureDataset, reciprocal: Boolean): List<Attachment> {
    val found = mutableListOf<Attachment>()
    signatures.states.forEach { state ->
        val blocks = if (reciprocal) state.retrospective else listOf(state.layerZero)
        found += readAttachments(stateNodeId(state.key.raw), blocks, signatures)
    }
    signatures.transitions.forEach { transition ->
        val blocks = if (reciprocal) transition.retrospective else listOf(transition.layerZero)
        found += readAttachments(transitionNodeId(transition.key.raw), blocks, signatures)
    }
    return found
}

private fun readAttachments(
    owner: String,
    blocks: List<SignatureBlock>,
    signatures: ReciprocalSignatureDataset,
): List<Attachment> {
    val found = mutableListOf<Attachment>()
    blocks.forEach { block ->
        val role = roleName(block.role) ?: return@forEach
        val quantities = block.semantic().filter { it.namespace == FeatureNamespace.QUANTITY }.associateBy { it.name }
        block.semantic().filter { it.namespace == FeatureNamespace.CONCEPT }.forEach { feature ->
            val concept = signatures.vocabulary.concepts.firstOrNull { conceptToken(it.key) == feature.value }?.key
                ?: return@forEach
            found += Attachment(owner, concept, role, roleWeight(role), quantities[feature.name]?.value)
        }
    }
    return found
}

private fun omittedPresence(found: List<Attachment>, occurrences: List<String>): Set<String> {
    val states = occurrences.filter { it.startsWith("state:") }
    val transitions = occurrences.filter { it.startsWith("transition:") }
    return found.map { it.concept }.distinct().mapNotNull { concept ->
        val mass = found.filter { it.concept == concept }.groupBy { it.owner }
            .mapValues { (_, rows) -> rows.sumOf { it.roleWeight } }
        val stateMass = states.map { mass[it] ?: 0.0 }
        val transitionMass = transitions.map { mass[it] ?: 0.0 }
        if (stateMass.sum() + transitionMass.sum() <= 0.0) return@mapNotNull null
        if (!uniform(stateMass) || !uniform(transitionMass)) return@mapNotNull null
        conceptToken(concept)
    }.toSet()
}

private fun quantityEdge(attachment: Attachment, scale: QuantityFit): EmbeddingEdge? {
    val token = conceptToken(attachment.concept)
    if (scale.heldBack.any { it.startsWith(token) }) return null
    val amount = scale.scales[attachment.concept] ?: return null
    val read = readAmount(attachment.quantityText)
    if (read !is AmountRead.One || read.unit != amount.unit) return null
    return EmbeddingEdge(
        source = attachment.owner,
        target = conceptNodeId(attachment.concept),
        kind = "quantity",
        detail = "$token · ${amount.unit} · amount ${read.number}",
        weight = QUANTITY_BASE,
    )
}

private fun axisEdges(axes: Map<String, Map<String, Double>>): List<EmbeddingEdge> {
    val ids = axes.keys.sorted()
    val edges = mutableListOf<EmbeddingEdge>()
    for (leftIndex in ids.indices) {
        for (rightIndex in leftIndex + 1 until ids.size) {
            val left = ids[leftIndex]
            val right = ids[rightIndex]
            val shared = axes.getValue(left).keys.intersect(axes.getValue(right).keys).sorted()
            if (shared.isEmpty()) continue
            edges += EmbeddingEdge(left, right, "axis", "observed ${shared.joinToString(",")}", 1.0)
        }
    }
    return edges
}

private fun linkEdges(graph: AlternatingTemporalGraph): List<EmbeddingEdge> {
    val edges = mutableListOf<EmbeddingEdge>()
    graph.states.forEach { state ->
        state.incomingTransitionIds.forEach { transition ->
            edges += EmbeddingEdge(stateNodeId(state.id), transitionNodeId(transition), "link", "incoming+ending", INCOMING + ENDING)
        }
        state.outgoingTransitionIds.forEach { transition ->
            edges += EmbeddingEdge(stateNodeId(state.id), transitionNodeId(transition), "link", "outgoing+starting", OUTGOING + STARTING)
        }
    }
    return edges
}

private fun readAmount(text: String?): AmountRead {
    if (text.isNullOrBlank()) return AmountRead.None
    if (text.startsWith("minutes:")) {
        val number = text.removePrefix("minutes:").toDoubleOrNull() ?: return AmountRead.None
        if (!number.isFinite() || number < 0.0) return AmountRead.None
        return AmountRead.One(number, "minutes")
    }
    val numbers = text.split(';').mapNotNull { piece ->
        val fields = piece.split(':', limit = 4)
        if (fields.firstOrNull() != "parsed") return@mapNotNull null
        val number = fields.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        if (!number.isFinite() || number < 0.0) return@mapNotNull null
        number to (fields.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "count")
    }
    return when (numbers.size) {
        0 -> AmountRead.None
        1 -> AmountRead.One(numbers.single().first, numbers.single().second)
        else -> AmountRead.Several
    }
}

private fun roleName(role: String): String? = when {
    role == "s0" || role == "t0" || role == "focal" -> "focal"
    role == "start" -> "starting"
    role == "end" -> "ending"
    role == "incoming" || role.startsWith("incoming.") -> "incoming"
    role == "outgoing" || role.startsWith("outgoing.") -> "outgoing"
    else -> null
}

private fun roleWeight(role: String): Double = when (role) {
    "focal" -> FOCAL
    "incoming" -> INCOMING
    "outgoing" -> OUTGOING
    "starting" -> STARTING
    "ending" -> ENDING
    else -> 0.0
}

private fun kindOf(id: String): String = when {
    id.startsWith("state:") -> "state"
    id.startsWith("transition:") -> "transition"
    else -> "concept"
}

private fun occurrenceIds(signatures: ReciprocalSignatureDataset): List<String> {
    return (signatures.states.map { stateNodeId(it.key.raw) } + signatures.transitions.map { transitionNodeId(it.key.raw) }).distinct().sorted()
}

private fun conceptToken(key: ConceptIdentityKey): String {
    return "${key.type.name}|${key.identity}|${key.intent.orEmpty()}"
}

private fun conceptNodeId(token: String): String = "concept:$token"

private fun identityColumn(token: String) = "identity|$token"

private fun roleColumn(role: String, token: String) = "role|$role|$token"

private fun quantityObservedColumn(token: String) = "quantity-observed|$token"

private fun quantityValueColumn(token: String) = "quantity-value|$token"

private fun axisObservedColumn(name: String) = "axis-observed|$name"

private fun axisValueColumn(name: String) = "axis-value|$name"

private fun columnToken(column: String): String? = when {
    column.startsWith("identity|") -> column.removePrefix("identity|")
    column.startsWith("quantity-observed|") -> column.removePrefix("quantity-observed|")
    column.startsWith("quantity-value|") -> column.removePrefix("quantity-value|")
    column.startsWith("role|") -> column.removePrefix("role|").substringAfter('|')
    else -> null
}

private fun uniform(values: List<Double>): Boolean {
    if (values.isEmpty()) return true
    val first = values.first()
    return values.all { kotlin.math.abs(it - first) <= 1e-9 }
}

private fun formatMass(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return rounded.toString()
}
