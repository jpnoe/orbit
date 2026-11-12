package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Walk-forward comparison of one semantic correction beside the frozen Null.
 *
 * The Null folds, retrospective horizons, targets, bounded forecasts, and
 * official residuals are read and not refit. Each fold's causal state
 * signatures use that fold's legal priors plus the exact anchor. A later row
 * and a same-time record that is not the anchor stay out. The correction is
 * the positive-cosine weighted mean of prior official Null residuals, shrunk
 * toward zero by κ = 1. Effective support is reported and does not drop a fold.
 *
 * The horizon is the completed interval. Membership is event-time
 * reconstruction, and strict as-known availability is false.
 */
const val WALK_FORWARD_SEMANTIC_NULL_COMPARISON = "walk-forward-semantic-null-comparison-v1"

const val SEMANTIC_NULL_KAPPA = 1.0

private const val COMPARISON_METHOD =
    "Positive cosine of causal state signatures, weights max(cosine, 0), " +
        "correction = weighted sum of prior official Null residuals divided by the weight sum plus κ = 1. " +
        "The corrected endpoint is the frozen Null bounded forecast plus that correction, constrained to 0–10. " +
        "A zero weight sum leaves the endpoint equal to the Null. Effective support does not drop a fold."

data class SemanticNullComparisonDataset(
    val protocol: String = WALK_FORWARD_SEMANTIC_NULL_COMPARISON,
    val method: String = COMPARISON_METHOD,
    val kappa: Double = SEMANTIC_NULL_KAPPA,
    val retrospectiveHorizon: Boolean = true,
    val availabilityMode: ObservationAvailabilityMode = ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION,
    val strictAsKnownAvailability: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
    val replacesNull: Boolean = false,
    val overall: SemanticNullMetrics,
    val byAxis: List<SemanticNullMetrics>,
    val folds: List<SemanticNullFold>,
    val judgments: List<String>,
) {
    init {
        require(protocol == WALK_FORWARD_SEMANTIC_NULL_COMPARISON)
        require(kappa == SEMANTIC_NULL_KAPPA)
        require(retrospectiveHorizon)
        require(availabilityMode == ObservationAvailabilityMode.EVENT_TIME_RECONSTRUCTION)
        require(!strictAsKnownAvailability && !predictive && !updatesModel && !suitableForRecommendation && !replacesNull)
        require(folds.all { it.retrospectiveHorizon })
    }
}

data class SemanticNullMetrics(
    val label: String,
    val scored: Int,
    val unscored: Int,
    val bias: Double?,
    val mae: Double?,
    val rmse: Double?,
    val nullBias: Double?,
    val nullMae: Double?,
    val nullRmse: Double?,
    val better: Int,
    val tied: Int,
    val worse: Int,
    val meanCorrectionMagnitude: Double?,
    val meanEffectiveSupport: Double?,
    val zeroSupportScored: Int,
) {
    init {
        require(better + tied + worse == scored)
        require(scored >= 0 && unscored >= 0 && zeroSupportScored >= 0)
        require(zeroSupportScored <= scored)
    }
}

data class SemanticNullNeighbor(
    val anchorAlias: String,
    val anchorLabel: String,
    val cosine: Double,
    val weight: Double,
    val officialResidual: Double,
)

data class SemanticNullAxisScore(
    val axisId: String,
    val scored: Boolean,
    val actual: Double?,
    val nullBounded: Double?,
    val nullResidual: Double?,
    val numerator: Double,
    val weightSum: Double,
    val kappa: Double,
    val correction: Double?,
    val semanticBounded: Double?,
    val semanticResidual: Double?,
    val effectiveSupport: Double,
    val neighbors: List<SemanticNullNeighbor>,
) {
    init {
        require(kappa == SEMANTIC_NULL_KAPPA)
        require(effectiveSupport >= 0.0)
        if (!scored) {
            require(correction == null && semanticBounded == null && semanticResidual == null)
        }
    }
}

data class SemanticNullFold(
    val transitionId: String,
    val anchorAlias: String,
    val anchorLabel: String,
    val retrospectiveHorizon: Boolean,
    val horizonText: String,
    val axes: List<SemanticNullAxisScore>,
    val outgoingAbsent: Boolean,
    val futureRowsAbsent: Boolean,
    val sameTimeNonAnchorAbsent: Boolean,
    val fitConceptTokens: List<String>,
    val absenceText: String,
) {
    init {
        require(retrospectiveHorizon)
        require(axes.map { it.axisId } == CheckInAnalysisDatasetV2.CORE_AXIS_IDS)
    }

    fun line(): String {
        val scores = axes.joinToString(" · ") { axis ->
            if (!axis.scored) {
                "${axis.axisId} not scored"
            } else {
                "${axis.axisId} Null ${formatPoint(axis.nullBounded)} residual ${formatSigned(axis.nullResidual)} · " +
                    "semantic ${formatPoint(axis.semanticBounded)} residual ${formatSigned(axis.semanticResidual)}"
            }
        }
        return "$anchorAlias · $horizonText · $scores"
    }
}

fun evaluateWalkForwardSemanticNullComparison(
    evaluation: NullTrajectoryEvaluation,
    stateExamples: List<StateForecastExample>,
    conditionalExamples: List<ConditionalTransitionExample>,
    projection: PersonalSemanticOccurrenceProjection,
): SemanticNullComparisonDataset {
    val stateById = stateExamples.associateBy { it.identity.transitionId }
    val conditionalById = conditionalExamples.associateBy { it.identity.transitionId }
    val aliases = aliases(projection)
    val folds = evaluation.folds.map { fold ->
        compareFold(
            fold = fold,
            stateById = stateById,
            conditionalById = conditionalById,
            projection = projection,
            aliases = aliases,
            nullFolds = evaluation.folds.associateBy { it.identity.transitionId },
        )
    }
    val scores = folds.flatMap { it.axes }
    return SemanticNullComparisonDataset(
        overall = metrics("Overall", scores),
        byAxis = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
            metrics(axisId, folds.map { fold -> fold.axes.single { it.axisId == axisId } })
        },
        folds = folds,
        judgments = judgments(),
    )
}

private fun compareFold(
    fold: NullTrajectoryFold,
    stateById: Map<String, StateForecastExample>,
    conditionalById: Map<String, ConditionalTransitionExample>,
    projection: PersonalSemanticOccurrenceProjection,
    aliases: Map<String, String>,
    nullFolds: Map<String, NullTrajectoryFold>,
): SemanticNullFold {
    val held = fold.identity
    val origin = held.anchorAt
    val anchorKey = held.anchorKey
    val legal = if (origin == null || anchorKey == null) {
        emptyList()
    } else {
        fold.identity.let { identity ->
            nullFolds.values.mapNotNull { priorFold ->
                val priorState = stateById[priorFold.identity.transitionId] ?: return@mapNotNull null
                val priorConditional = conditionalById[priorFold.identity.transitionId] ?: return@mapNotNull null
                if (!priorIsLegal(priorState, priorConditional, identity)) null else priorFold
            }
        }
    }
    val legalTransitionIds = legal.map { it.identity.transitionId }.toSet()
    val transitions = projection.transitions.filter { transition ->
        transition.key.raw in legalTransitionIds && transitionAllowed(transition, origin, anchorKey, held.transitionId)
    }
    val transitionIds = transitions.map { it.key }.toSet()
    val anchorIds = legal.mapNotNull { it.identity.anchorKey }.toMutableSet()
    if (anchorKey != null) anchorIds += anchorKey
    val states = projection.states.filter { state ->
        state.key in anchorIds && stateAllowed(state, origin, anchorKey)
    }
    val pieces = pieces(states, transitions, projection)
    val coordinates = coordinates(states, transitions, pieces)
    val anchorVector = anchorKey?.let { coordinates[stateNodeId(it.raw)] }
    val fitTokens = pieces.map { conceptToken(it.concept) }.distinct().sorted()
    val outgoingAbsent = held.transitionId !in transitionIds.map { it.raw }
    val futureRowsAbsent = rowsStayInMembership(states, transitions, origin, anchorKey)
    val sameTimeNonAnchorAbsent = origin == null || anchorKey == null || states.none { state ->
        state.recordedAt == origin && state.key != anchorKey
    }
    val absenceText = absenceText(outgoingAbsent, futureRowsAbsent, sameTimeNonAnchorAbsent)
    val axes = CheckInAnalysisDatasetV2.CORE_AXIS_IDS.map { axisId ->
        val nullAxis = fold.axes.single { it.axisId == axisId }
        scoreAxis(axisId, nullAxis, legal, coordinates, anchorVector, aliases, projection)
    }
    return SemanticNullFold(
        transitionId = held.transitionId,
        anchorAlias = anchorKey?.let { aliases[stateNodeId(it.raw)] } ?: "no anchor",
        anchorLabel = anchorKey?.let { anchorText(it, projection, aliases) } ?: "no anchor",
        retrospectiveHorizon = true,
        horizonText = horizonText(fold.horizonHours),
        axes = axes,
        outgoingAbsent = outgoingAbsent,
        futureRowsAbsent = futureRowsAbsent,
        sameTimeNonAnchorAbsent = sameTimeNonAnchorAbsent,
        fitConceptTokens = fitTokens,
        absenceText = absenceText,
    )
}

private fun scoreAxis(
    axisId: String,
    nullAxis: NullAxisForecast,
    legal: List<NullTrajectoryFold>,
    coordinates: Map<String, List<Double>>,
    anchorVector: List<Double>?,
    aliases: Map<String, String>,
    projection: PersonalSemanticOccurrenceProjection,
): SemanticNullAxisScore {
    val bounded = nullAxis.forecast?.boundedForecast
    val residual = nullAxis.officialResidual
    val scored = bounded != null && residual != null
    val neighbors = if (anchorVector == null) {
        emptyList()
    } else {
        legal.mapNotNull { prior ->
            val priorAxis = prior.axes.single { it.axisId == axisId }
            val priorResidual = priorAxis.officialResidual ?: return@mapNotNull null
            if (priorAxis.forecast == null) return@mapNotNull null
            val priorAnchor = prior.identity.anchorKey ?: return@mapNotNull null
            val priorVector = coordinates[stateNodeId(priorAnchor.raw)]
            val cosine = partialCosine(anchorVector, priorVector)
            SemanticNullNeighbor(
                anchorAlias = aliases[stateNodeId(priorAnchor.raw)] ?: "anchor",
                anchorLabel = anchorText(priorAnchor, projection, aliases),
                cosine = cosine,
                weight = if (cosine > 0.0) cosine else 0.0,
                officialResidual = priorResidual,
            )
        }.sortedWith(compareByDescending<SemanticNullNeighbor> { it.weight }.thenBy { it.anchorAlias })
    }
    val weightSum = neighbors.sumOf { it.weight }
    val numerator = neighbors.sumOf { it.weight * it.officialResidual }
    val support = effectiveSupport(neighbors)
    if (!scored) {
        return SemanticNullAxisScore(
            axisId = axisId,
            scored = false,
            actual = if (bounded != null && residual != null) bounded + residual else null,
            nullBounded = bounded,
            nullResidual = residual,
            numerator = numerator,
            weightSum = weightSum,
            kappa = SEMANTIC_NULL_KAPPA,
            correction = null,
            semanticBounded = null,
            semanticResidual = null,
            effectiveSupport = support,
            neighbors = neighbors,
        )
    }
    val correction = numerator / (weightSum + SEMANTIC_NULL_KAPPA)
    val semantic = observableForecast(bounded + correction)
    val actual = bounded + residual
    return SemanticNullAxisScore(
        axisId = axisId,
        scored = true,
        actual = actual,
        nullBounded = bounded,
        nullResidual = residual,
        numerator = numerator,
        weightSum = weightSum,
        kappa = SEMANTIC_NULL_KAPPA,
        correction = correction,
        semanticBounded = semantic.boundedForecast,
        semanticResidual = actual - semantic.boundedForecast,
        effectiveSupport = support,
        neighbors = neighbors,
    )
}

private fun priorIsLegal(
    priorState: StateForecastExample,
    priorConditional: ConditionalTransitionExample,
    held: ExampleIdentity,
): Boolean {
    if (!priorState.available || !priorConditional.available) return false
    if (priorState.gaps.isNotEmpty() || priorConditional.gaps.isNotEmpty()) return false
    if (priorConditional.scenario.span.millis < 0) return false
    return causalPriorQualifies(priorState, held)
}

private fun transitionAllowed(
    transition: TransitionOccurrenceProjection,
    origin: Long?,
    anchorKey: StateOccurrenceKey?,
    heldTransitionId: String,
): Boolean {
    if (origin == null || anchorKey == null) return false
    if (transition.key.raw == heldTransitionId) return false
    if (transition.endAt < origin) return true
    return transition.endAt == origin && transition.endStateKey == anchorKey
}

private fun stateAllowed(
    state: StateOccurrenceProjection,
    origin: Long?,
    anchorKey: StateOccurrenceKey?,
): Boolean {
    if (origin == null || anchorKey == null) return false
    if (state.key == anchorKey) return true
    return state.recordedAt < origin
}

private fun rowsStayInMembership(
    states: List<StateOccurrenceProjection>,
    transitions: List<TransitionOccurrenceProjection>,
    origin: Long?,
    anchorKey: StateOccurrenceKey?,
): Boolean {
    if (origin == null || anchorKey == null) return states.isEmpty() && transitions.isEmpty()
    val statesLegal = states.all { stateAllowed(it, origin, anchorKey) }
    val transitionsLegal = transitions.all { candidate ->
        candidate.endAt < origin || (candidate.endAt == origin && candidate.endStateKey == anchorKey)
    }
    return statesLegal && transitionsLegal
}

private data class Piece(
    val owner: String,
    val occurrenceKind: String,
    val concept: ConceptIdentityKey,
    val role: String,
    val quantityText: String?,
)

private fun pieces(
    states: List<StateOccurrenceProjection>,
    transitions: List<TransitionOccurrenceProjection>,
    projection: PersonalSemanticOccurrenceProjection,
): List<Piece> {
    val stateIds = states.map { it.key }.toSet()
    val transitionIds = transitions.map { it.key }.toSet()
    val incomingByState = transitions.groupBy { it.endStateKey }
    val found = mutableListOf<Piece>()
    states.forEach { state ->
        val owner = stateNodeId(state.key.raw)
        projection.links.filterIsInstance<ConceptObservationLink.OnState>()
            .filter { it.key == state.key && isEligibleConceptType(it.concept.type) }
            .forEach { link ->
                found += Piece(owner, "state", link.concept, "focal", null)
            }
        incomingByState[state.key].orEmpty().forEach { transition ->
            projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
                .filter { it.key == transition.key && isEligibleConceptType(it.concept.type) }
                .forEach { link ->
                    found += Piece(owner, "state", link.concept, "incoming", quantityText(link.quantity))
                }
        }
    }
    transitions.filter { it.key in transitionIds }.forEach { transition ->
        val owner = transitionNodeId(transition.key.raw)
        projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
            .filter { it.key == transition.key && isEligibleConceptType(it.concept.type) }
            .forEach { link ->
                found += Piece(owner, "transition", link.concept, "focal", quantityText(link.quantity))
            }
    }
    return found.filter { it.occurrenceKind == "transition" || it.owner.removePrefix("state:") .let { raw -> stateIds.any { key -> key.raw == raw } } }
}

private fun coordinates(
    states: List<StateOccurrenceProjection>,
    transitions: List<TransitionOccurrenceProjection>,
    pieces: List<Piece>,
): Map<String, List<Double>> {
    val stateIds = states.map { stateNodeId(it.key.raw) }
    val transitionIds = transitions.map { transitionNodeId(it.key.raw) }
    val occurrences = (stateIds + transitionIds).distinct()
    val omitted = omittedPresence(pieces, stateIds, transitionIds)
    val quantity = quantityScales(pieces)
    val axes = axisScores(states)
    val rows = occurrences.associateWith { mutableMapOf<String, Double>() }
    pieces.forEach { piece ->
        val row = rows[piece.owner] ?: return@forEach
        val token = conceptToken(piece.concept)
        if (token !in omitted) {
            row[identityColumn(token)] = 1.0
            row[roleColumn(piece.role, token)] = 1.0
        }
        writeQuantity(row, piece, quantity)
    }
    axes.forEach { (id, scores) ->
        val row = rows[id] ?: return@forEach
        scores.forEach { (axis, score) ->
            row[axisObservedColumn(axis)] = 1.0
            row[axisValueColumn(axis)] = score
        }
    }
    val columns = varyingColumns(rows, occurrences)
    return stateIds.associateWith { id ->
        val values = rows.getValue(id)
        columns.map { column ->
            if (column.startsWith("axis-") && column !in values) Double.NaN else values[column] ?: 0.0
        }
    }
}

private fun omittedPresence(pieces: List<Piece>, states: List<String>, transitions: List<String>): Set<String> {
    return pieces.map { it.concept }.distinct().mapNotNull { concept ->
        val mass = pieces.filter { it.concept == concept }.groupBy { it.owner }
            .mapValues { (_, rows) -> rows.sumOf { roleWeight(it.role) } }
        val stateMass = states.map { mass[it] ?: 0.0 }
        val transitionMass = transitions.map { mass[it] ?: 0.0 }
        if (stateMass.sum() + transitionMass.sum() <= 0.0) return@mapNotNull null
        if (!uniform(stateMass) || !uniform(transitionMass)) return@mapNotNull null
        conceptToken(concept)
    }.toSet()
}

private data class SemanticAmountScale(val unit: String, val min: Double, val max: Double)

private fun quantityScales(pieces: List<Piece>): Map<ConceptIdentityKey, SemanticAmountScale> {
    val focal = pieces.filter { it.occurrenceKind == "transition" && it.role == "focal" }
    val scales = mutableMapOf<ConceptIdentityKey, SemanticAmountScale>()
    focal.groupBy { it.concept }.forEach { (concept, group) ->
        val reads = group.map { readAmount(it.quantityText) }
        if (reads.any { it is SemanticAmountRead.Several }) return@forEach
        val numbers = reads.filterIsInstance<SemanticAmountRead.One>()
        val units = numbers.map { it.unit }.distinct()
        if (units.size != 1 || numbers.isEmpty()) return@forEach
        scales[concept] = SemanticAmountScale(units.single(), numbers.minOf { it.number }, numbers.maxOf { it.number })
    }
    return scales
}

private fun writeQuantity(row: MutableMap<String, Double>, piece: Piece, scales: Map<ConceptIdentityKey, SemanticAmountScale>) {
    val amount = scales[piece.concept] ?: return
    val read = readAmount(piece.quantityText)
    if (read !is SemanticAmountRead.One || read.unit != amount.unit) return
    val token = conceptToken(piece.concept)
    val scaled = if (amount.max == amount.min) 0.0 else (read.number - amount.min) / (amount.max - amount.min)
    row[quantityObservedColumn(token)] = 1.0
    row[quantityValueColumn(token)] = scaled
}

private fun axisScores(states: List<StateOccurrenceProjection>): Map<String, Map<String, Double>> {
    val observed = states.associate { state ->
        stateNodeId(state.key.raw) to state.axes
            .filter { it.status == AxisOccurrenceStatus.OBSERVED && it.raw != null }
            .associate { it.axisId to it.raw!!.toDouble() }
    }.filterValues { it.isNotEmpty() }
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

private fun varyingColumns(rows: Map<String, MutableMap<String, Double>>, occurrences: List<String>): List<String> {
    val names = rows.values.flatMap { it.keys }.distinct()
    return names.filter { name ->
        occurrences.map { rows.getValue(it)[name] }.distinct().size > 1
    }.sorted()
}

private fun partialCosine(left: List<Double>?, right: List<Double>?): Double {
    if (left == null || right == null || left.size != right.size || left.isEmpty()) return 0.0
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

private fun effectiveSupport(neighbors: List<SemanticNullNeighbor>): Double {
    val weightSum = neighbors.sumOf { it.weight }
    val squareSum = neighbors.sumOf { it.weight * it.weight }
    if (squareSum <= 1e-18) return 0.0
    return weightSum * weightSum / squareSum
}

private fun metrics(label: String, scores: List<SemanticNullAxisScore>): SemanticNullMetrics {
    val scored = scores.filter { it.scored }
    val semanticErrors = scored.map { checkNotNull(it.semanticResidual) }
    val nullErrors = scored.map { checkNotNull(it.nullResidual) }
    var better = 0
    var tied = 0
    var worse = 0
    scored.forEach { score ->
        val semantic = kotlin.math.abs(checkNotNull(score.semanticResidual))
        val baseline = kotlin.math.abs(checkNotNull(score.nullResidual))
        when {
            semantic + 1e-9 < baseline -> better += 1
            kotlin.math.abs(semantic - baseline) <= 1e-9 -> tied += 1
            else -> worse += 1
        }
    }
    return SemanticNullMetrics(
        label = label,
        scored = scored.size,
        unscored = scores.size - scored.size,
        bias = mean(semanticErrors),
        mae = mean(semanticErrors.map { kotlin.math.abs(it) }),
        rmse = rootMeanSquare(semanticErrors),
        nullBias = mean(nullErrors),
        nullMae = mean(nullErrors.map { kotlin.math.abs(it) }),
        nullRmse = rootMeanSquare(nullErrors),
        better = better,
        tied = tied,
        worse = worse,
        meanCorrectionMagnitude = mean(scored.map { kotlin.math.abs(checkNotNull(it.correction)) }),
        meanEffectiveSupport = mean(scored.map { it.effectiveSupport }),
        zeroSupportScored = scored.count { it.effectiveSupport == 0.0 },
    )
}

private fun absenceText(outgoing: Boolean, future: Boolean, sameTime: Boolean): String {
    return if (outgoing && future && sameTime) {
        "Outgoing behavior is absent from the anchor signature. Future rows and same-time non-anchor records are absent from this fit."
    } else {
        "This fit still contains an outgoing behavior, a future row, or a same-time non-anchor record."
    }
}

private fun anchorText(
    key: StateOccurrenceKey,
    projection: PersonalSemanticOccurrenceProjection,
    aliases: Map<String, String>,
): String {
    val alias = aliases[stateNodeId(key.raw)] ?: "anchor"
    val state = projection.states.firstOrNull { it.key == key } ?: return alias
    val parts = mutableListOf(alias)
    state.emotion?.name?.takeIf { it.isNotBlank() }?.let { parts += it }
    val axes = listOf(
        DefaultCheckInAxes.MOOD to "M",
        DefaultCheckInAxes.ENERGY to "E",
        DefaultCheckInAxes.CONFIDENCE to "C",
        DefaultCheckInAxes.ANXIETY to "A",
    ).mapNotNull { (axisId, letter) ->
        val axis = state.axes.firstOrNull { it.axisId == axisId } ?: return@mapNotNull null
        val raw = axis.raw ?: return@mapNotNull null
        if (axis.status != AxisOccurrenceStatus.OBSERVED) return@mapNotNull null
        "$letter$raw"
    }
    if (axes.isNotEmpty()) parts += axes.joinToString(" ")
    return parts.joinToString(" · ")
}

private fun horizonText(hours: Double?): String {
    if (hours == null) return "retrospective horizon unavailable"
    val rounded = round(hours * 100.0) / 100.0
    val text = if (rounded == 0.0) "0.0" else rounded.toString()
    return "retrospective horizon ${text}h"
}

private fun aliases(projection: PersonalSemanticOccurrenceProjection): Map<String, String> {
    val alias = mutableMapOf<String, String>()
    projection.states.sortedWith(compareBy({ it.recordedAt }, { it.key.raw })).forEachIndexed { index, state ->
        alias[stateNodeId(state.key.raw)] = "S" + (index + 1).toString().padStart(2, '0')
    }
    return alias
}

private fun judgments(): List<String> {
    return listOf(
        "Walk-forward comparison beside null-trajectory-learner-v2. The Null folds, retrospective horizons, targets, bounded forecasts, official residuals, and common support are unchanged.",
        "Event-time reconstruction. Strict as-known availability is false. The horizon is the completed interval and was not selected in advance.",
        "Each causal state signature is built from that fold's legal Null priors plus the exact anchor. A later row stays out. A record at the anchor time stays out unless it is the anchor.",
        "The existing cutoff vocabulary counts only occurrences strictly before a timestamp, so it drops the anchor. This comparison does not use that snapshot.",
        "An ineligible interval is outside the Null prior set, so its concepts are not coordinates even when its timestamp is earlier.",
        "Similarity is partial cosine. A column that does not vary in the fold is dropped. A missing axis is skipped. Provenance, timestamps, and outgoing behavior are not coordinates.",
        "The weight is the positive part of that cosine. Prior official Null residuals are read and not refit. κ = 1. A zero weight sum leaves the bounded endpoint equal to the Null.",
        "The corrected endpoint is constrained to 0–10. Effective support is reported and does not drop or tune a fold.",
        "This comparison does not standardize a surprise, choose a local window, read a shape, condition on the outgoing behavior, or replace the Null forecast.",
    )
}

private fun conceptToken(key: ConceptIdentityKey): String {
    return "${key.type.name}|${key.identity}|${key.intent.orEmpty()}"
}

private fun identityColumn(token: String) = "identity|$token"

private fun roleColumn(role: String, token: String) = "role|$role|$token"

private fun quantityObservedColumn(token: String) = "quantity-observed|$token"

private fun quantityValueColumn(token: String) = "quantity-value|$token"

private fun axisObservedColumn(name: String) = "axis-observed|$name"

private fun axisValueColumn(name: String) = "axis-value|$name"

private fun roleWeight(role: String): Double = when (role) {
    "focal" -> 4.0
    "incoming" -> 2.0
    else -> 0.0
}

private fun uniform(values: List<Double>): Boolean {
    if (values.isEmpty()) return true
    val first = values.first()
    return values.all { kotlin.math.abs(it - first) <= 1e-9 }
}

private fun quantityText(quantity: OccurrenceQuantity?): String? = when (quantity) {
    null -> null
    is OccurrenceQuantity.WorkoutMinutes -> "minutes:${quantity.minutes}"
    is OccurrenceQuantity.UsageAmounts -> quantity.amounts.joinToString(";") { amount ->
        listOf(amount.status.name.lowercase(), amount.numericAmount?.toString() ?: "", amount.unit ?: "", amount.rawAmountText)
            .joinToString(":")
    }
}

private sealed interface SemanticAmountRead {
    data object None : SemanticAmountRead
    data class One(val number: Double, val unit: String) : SemanticAmountRead
    data object Several : SemanticAmountRead
}

private fun readAmount(text: String?): SemanticAmountRead {
    if (text.isNullOrBlank()) return SemanticAmountRead.None
    if (text.startsWith("minutes:")) {
        val number = text.removePrefix("minutes:").toDoubleOrNull() ?: return SemanticAmountRead.None
        if (!number.isFinite() || number < 0.0) return SemanticAmountRead.None
        return SemanticAmountRead.One(number, "minutes")
    }
    val numbers = text.split(';').mapNotNull { piece ->
        val fields = piece.split(':', limit = 4)
        if (fields.firstOrNull() != "parsed") return@mapNotNull null
        val number = fields.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        if (!number.isFinite() || number < 0.0) return@mapNotNull null
        number to (fields.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "count")
    }
    return when (numbers.size) {
        0 -> SemanticAmountRead.None
        1 -> SemanticAmountRead.One(numbers.single().first, numbers.single().second)
        else -> SemanticAmountRead.Several
    }
}

private fun mean(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return values.sum() / values.size
}

private fun rootMeanSquare(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return sqrt(values.sumOf { it * it } / values.size)
}

private fun formatPoint(value: Double?): String {
    if (value == null) return "none"
    val rounded = round(value * 1000.0) / 1000.0
    return if (rounded == 0.0) "0.0" else rounded.toString()
}

private fun formatSigned(value: Double?): String {
    if (value == null) return "none"
    val rounded = round(value * 1000.0) / 1000.0
    val text = if (rounded == 0.0) "0.0" else rounded.toString()
    return if (rounded > 0.0) "+$text" else text
}
