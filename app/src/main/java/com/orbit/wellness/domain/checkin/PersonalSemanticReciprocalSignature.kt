package com.orbit.wellness.domain.checkin

/**
 * Sparse symbolic signatures for layer zero and one reciprocal step.
 *
 * Layer zero is the ablation beside recursion one. Nothing here is an
 * embedding, a cluster, a region, or a Null fit. A concept column
 * exists only when the supplied vocabulary already contains that typed
 * identity. Masking removes the identity before either layer is assembled.
 * Masking this signature does not train an embedding; a later embedding has
 * to be fit under the same mask.
 */
const val PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE = "personal-semantic-reciprocal-signature-v1"

const val RETROSPECTIVE_VOCABULARY_SCOPE = "retrospective-full-history"

const val FOLD_VOCABULARY_SCOPE = "history-before-cutoff"

private val ELIGIBLE_CONCEPT_TYPES = setOf(
    SemanticConceptType.EMOTION,
    SemanticConceptType.ACTIVITY,
    SemanticConceptType.WORKOUT,
    SemanticConceptType.FOOD,
    SemanticConceptType.MEAL_SIZE,
    SemanticConceptType.CONTEXTUAL_USAGE,
)

fun isEligibleConceptType(type: SemanticConceptType): Boolean = type in ELIGIBLE_CONCEPT_TYPES

enum class FeatureNamespace {
    NUMERIC,
    CONCEPT,
    STATUS,
    QUANTITY,
    TIME,
    PROVENANCE,
}

data class SymbolicFeature(
    val namespace: FeatureNamespace,
    val channel: String,
    val name: String,
    val value: String,
)

data class SignatureBlock(
    val role: String,
    val features: List<SymbolicFeature>,
) {
    fun semantic(): List<SymbolicFeature> = features.filter { it.namespace != FeatureNamespace.PROVENANCE }

    fun placed(role: String): SignatureBlock = SignatureBlock(role, features.map { it.copy(channel = role) })
}

data class VocabularyConcept(
    val key: ConceptIdentityKey,
    val backboneSupport: Int,
    val provenance: String,
) {
    init {
        require(key.type in ELIGIBLE_CONCEPT_TYPES) { "A placeholder or unattached concept is not vocabulary" }
        require(backboneSupport > 0)
    }
}

data class ConceptVocabularySnapshot(
    val scope: String,
    val retrospective: Boolean,
    val cutoffAt: Long?,
    val concepts: List<VocabularyConcept>,
) {
    init {
        if (retrospective) {
            require(scope == RETROSPECTIVE_VOCABULARY_SCOPE)
            require(cutoffAt == null)
        } else {
            require(scope == FOLD_VOCABULARY_SCOPE)
            require(cutoffAt != null)
        }
        require(concepts.distinctBy { it.key }.size == concepts.size)
    }

    fun contains(key: ConceptIdentityKey): Boolean = concepts.any { it.key == key }

    fun supportOf(key: ConceptIdentityKey): Int? = concepts.firstOrNull { it.key == key }?.backboneSupport

    /** Audit display for one concept column. The count is not part of [SymbolicFeature.value]. */
    fun displayedSupport(featureValue: String): Int? {
        return concepts.firstOrNull { conceptToken(it.key) == featureValue }?.backboneSupport
    }
}

data class SignatureMask(
    val excludedConcepts: Set<ConceptIdentityKey> = emptySet(),
    val excludedTypes: Set<SemanticConceptType> = emptySet(),
    val omitOutgoing: Boolean = false,
    val omitDerivedDynamics: Boolean = false,
)

data class OovCoverage(
    val holderRole: String,
    val concept: ConceptIdentityKey,
    val quantityText: String?,
)

data class DynamicsAnnotationStatus(
    val available: Boolean = false,
    val reason: String,
) {
    init {
        require(!available) { "This pass does not carry a Null annotation" }
    }
}

data class ReciprocalStateSignatures(
    val key: StateOccurrenceKey,
    val layerZero: SignatureBlock,
    val retrospective: List<SignatureBlock>,
    val causal: List<SignatureBlock>,
    val retrospectiveOov: List<OovCoverage>,
    val causalOov: List<OovCoverage>,
)

data class ReciprocalTransitionSignatures(
    val key: TransitionOccurrenceKey,
    val layerZero: SignatureBlock,
    val retrospective: List<SignatureBlock>,
    val causal: List<SignatureBlock>,
    val retrospectiveOov: List<OovCoverage>,
    val causalOov: List<OovCoverage>,
)

data class ReciprocalSignatureDataset(
    val protocol: String = PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE,
    val vocabulary: ConceptVocabularySnapshot,
    val mask: SignatureMask,
    val states: List<ReciprocalStateSignatures>,
    val transitions: List<ReciprocalTransitionSignatures>,
    val dynamics: DynamicsAnnotationStatus,
    val judgments: List<String>,
    val retrospective: Boolean = true,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_RECIPROCAL_SIGNATURE)
        require(retrospective && !predictive && !updatesModel && !suitableForRecommendation)
        require(!dynamics.available)
    }

    fun state(key: StateOccurrenceKey): ReciprocalStateSignatures = states.first { it.key == key }

    fun transition(key: TransitionOccurrenceKey): ReciprocalTransitionSignatures = transitions.first { it.key == key }
}

fun retrospectiveVocabulary(
    projection: PersonalSemanticOccurrenceProjection,
): ConceptVocabularySnapshot {
    return snapshot(
        links = eligibleLinks(projection, cutoffAt = null),
        scope = RETROSPECTIVE_VOCABULARY_SCOPE,
        retrospective = true,
        cutoffAt = null,
    )
}

fun vocabularyBeforeCutoff(
    projection: PersonalSemanticOccurrenceProjection,
    cutoffAt: Long,
): ConceptVocabularySnapshot {
    return snapshot(
        links = eligibleLinks(projection, cutoffAt),
        scope = FOLD_VOCABULARY_SCOPE,
        retrospective = false,
        cutoffAt = cutoffAt,
    )
}

fun projectReciprocalSignatures(
    projection: PersonalSemanticOccurrenceProjection,
    graph: AlternatingTemporalGraph,
    vocabulary: ConceptVocabularySnapshot,
    mask: SignatureMask = SignatureMask(),
): ReciprocalSignatureDataset {
    val stateByKey = projection.states.associateBy { it.key }
    val transitionByKey = projection.transitions.associateBy { it.key }
    val nodeById = graph.transitions.associateBy { it.id }
    val layerZeroStates = projection.states.associate { state ->
        val oov = mutableListOf<OovCoverage>()
        state.key to (stateZero(state, vocabulary, mask, oov) to oov.toList())
    }
    val layerZeroTransitions = projection.transitions.associate { transition ->
        val oov = mutableListOf<OovCoverage>()
        val node = checkNotNull(nodeById[transition.key.raw]) { "The graph is missing ${transition.key.raw}" }
        transition.key to (transitionZero(transition, node, projection, vocabulary, mask, oov) to oov.toList())
    }
    val states = graph.states.map { node ->
        val key = StateOccurrenceKey(node.id)
        val (zero, zeroOov) = layerZeroStates.getValue(key)
        val incoming = node.incomingTransitionIds.map { TransitionOccurrenceKey(it) }
        val outgoing = node.outgoingTransitionIds.map { TransitionOccurrenceKey(it) }
        ReciprocalStateSignatures(
            key = key,
            layerZero = zero,
            retrospective = contextualState(
                zero = zero,
                incoming = incoming,
                outgoing = outgoing,
                layerZeroTransitions = layerZeroTransitions,
                omitOutgoing = mask.omitOutgoing,
            ),
            causal = contextualState(
                zero = zero,
                incoming = incoming,
                outgoing = emptyList(),
                layerZeroTransitions = layerZeroTransitions,
                omitOutgoing = true,
            ),
            retrospectiveOov = zeroOov + incoming.flatMap { layerZeroTransitions.getValue(it).second } +
                if (mask.omitOutgoing) emptyList() else outgoing.flatMap { layerZeroTransitions.getValue(it).second },
            causalOov = zeroOov + incoming.flatMap { layerZeroTransitions.getValue(it).second },
        )
    }
    val transitions = projection.transitions.map { transition ->
        val (zero, zeroOov) = layerZeroTransitions.getValue(transition.key)
        val start = transition.startStateKey?.let { stateByKey[it] }
        val end = stateByKey.getValue(transition.endStateKey)
        val startBlock = endpointBlock("start", start, layerZeroStates)
        val endBlock = endpointBlock("end", end, layerZeroStates)
        ReciprocalTransitionSignatures(
            key = transition.key,
            layerZero = zero,
            retrospective = listOf(startBlock, zero.placed("focal"), endBlock),
            causal = listOf(startBlock, causalTransition(zero), horizonBlock()),
            retrospectiveOov = zeroOov + endpointOov("start", start, layerZeroStates) + endpointOov("end", end, layerZeroStates),
            causalOov = zeroOov + endpointOov("start", start, layerZeroStates),
        )
    }
    return ReciprocalSignatureDataset(
        vocabulary = vocabulary,
        mask = mask,
        states = states,
        transitions = transitions,
        dynamics = DynamicsAnnotationStatus(
            available = false,
            reason = DYNAMICS_ABSENT,
        ),
        judgments = judgments(vocabulary, mask),
    )
}

private const val DYNAMICS_ABSENT =
    "No dynamics annotation. Recorded Null v2 folds use the realized interval as their horizon, so they are not a forecast available from the starting information. Joining them here would also tie this signature to the predictor. A later audit can attach an already computed fold by its example identity, outside this builder."

private fun contextualState(
    zero: SignatureBlock,
    incoming: List<TransitionOccurrenceKey>,
    outgoing: List<TransitionOccurrenceKey>,
    layerZeroTransitions: Map<TransitionOccurrenceKey, Pair<SignatureBlock, List<OovCoverage>>>,
    omitOutgoing: Boolean,
): List<SignatureBlock> {
    val blocks = mutableListOf<SignatureBlock>()
    blocks += incidence(incoming.size, if (omitOutgoing) null else outgoing.size)
    incoming.forEachIndexed { index, key ->
        blocks += layerZeroTransitions.getValue(key).first.placed("incoming.$index")
    }
    blocks += zero.placed("focal")
    if (!omitOutgoing) {
        if (outgoing.isEmpty()) {
            blocks += SignatureBlock("outgoing", listOf(status("outgoing", "incidence", "none")))
        }
        outgoing.forEachIndexed { index, key ->
            blocks += layerZeroTransitions.getValue(key).first.placed("outgoing.$index")
        }
    }
    return blocks
}

private fun incidence(incoming: Int, outgoing: Int?): SignatureBlock {
    val features = mutableListOf(
        status("incidence", "incoming.count", incoming.toString()),
        status("incidence", "incoming.ambiguous", (incoming > 1).toString()),
    )
    if (outgoing != null) {
        features += status("incidence", "outgoing.count", outgoing.toString())
        features += status("incidence", "outgoing.ambiguous", (outgoing > 1).toString())
    }
    return SignatureBlock("incidence", features)
}

private fun endpointBlock(
    role: String,
    state: StateOccurrenceProjection?,
    layerZeroStates: Map<StateOccurrenceKey, Pair<SignatureBlock, List<OovCoverage>>>,
): SignatureBlock {
    if (state == null) {
        return SignatureBlock(role, listOf(status(role, "endpoint", "no-starting-state")))
    }
    return layerZeroStates.getValue(state.key).first.placed(role)
}

private fun endpointOov(
    role: String,
    state: StateOccurrenceProjection?,
    layerZeroStates: Map<StateOccurrenceKey, Pair<SignatureBlock, List<OovCoverage>>>,
): List<OovCoverage> {
    if (state == null) return emptyList()
    return layerZeroStates.getValue(state.key).second.map { it.copy(holderRole = role) }
}

private fun causalTransition(zero: SignatureBlock): SignatureBlock {
    val kept = zero.features.filter { feature ->
        feature.namespace != FeatureNamespace.TIME &&
            feature.name != "end-at" &&
            feature.name != "endpoint.end"
    }.map { it.copy(channel = "focal") }
    return SignatureBlock("focal", kept)
}

private fun horizonBlock(): SignatureBlock {
    return SignatureBlock("horizon", listOf(status("horizon", "horizon", "absent")))
}

private fun stateZero(
    state: StateOccurrenceProjection,
    vocabulary: ConceptVocabularySnapshot,
    mask: SignatureMask,
    oov: MutableList<OovCoverage>,
): SignatureBlock {
    val features = mutableListOf<SymbolicFeature>()
    state.axes.forEach { axis ->
        features += status("s0", "axis.${axis.axisId}", axis.status.name.lowercase())
        if (axis.status == AxisOccurrenceStatus.OBSERVED) {
            features += SymbolicFeature(FeatureNamespace.NUMERIC, "s0", "axis.${axis.axisId}", axis.raw.toString())
        }
    }
    val emotion = state.emotion
    if (emotion != null) {
        val key = ConceptIdentityKey(SemanticConceptType.EMOTION, emotion.name)
        admitConcept(key, "s0", "focal", vocabulary, mask, oov, quantityText = null)?.let { features += it }
    } else {
        features += status("s0", "emotion", "absent")
    }
    features += SymbolicFeature(FeatureNamespace.PROVENANCE, "s0", "state-key", state.key.raw)
    features += SymbolicFeature(FeatureNamespace.PROVENANCE, "s0", "recorded-at", state.recordedAt.toString())
    return SignatureBlock("s0", features)
}

private fun transitionZero(
    transition: TransitionOccurrenceProjection,
    node: TransitionOccurrenceNode,
    projection: PersonalSemanticOccurrenceProjection,
    vocabulary: ConceptVocabularySnapshot,
    mask: SignatureMask,
    oov: MutableList<OovCoverage>,
): SignatureBlock {
    val features = mutableListOf<SymbolicFeature>()
    features += status("t0", "start-basis", transition.startBasis)
    features += status("t0", "boundary", transition.boundary.toString())
    features += status("t0", "completed", transition.completed.toString())
    features += status("t0", "chronology", chronology(transition))
    features += status("t0", "endpoint.start", if (transition.startStateKey == null) "absent" else "present")
    features += status("t0", "endpoint.end", "present")
    node.interval.categoriesInOrder().forEach { category ->
        features += status("t0", "category.${category.category}", categoryToken(category.kind))
    }
    val links = projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
        .filter { it.key == transition.key }
        .sortedWith(compareBy({ it.concept.type.ordinal }, { it.concept.identity }, { it.concept.intent ?: "" }, { it.label }))
    links.forEachIndexed { index, link ->
        val admitted = admitConcept(
            key = link.concept,
            channel = "t0",
            holderRole = "focal",
            vocabulary = vocabulary,
            mask = mask,
            oov = oov,
            quantityText = quantityText(link.quantity),
            name = "occurrence.$index",
        )
        if (admitted != null) {
            features += admitted
            quantityText(link.quantity)?.let { text ->
                features += SymbolicFeature(FeatureNamespace.QUANTITY, "t0", "occurrence.$index", text)
            }
        }
    }
    features += SymbolicFeature(FeatureNamespace.TIME, "t0", "duration-millis", transition.durationMillis.toString())
    features += SymbolicFeature(FeatureNamespace.PROVENANCE, "t0", "transition-key", transition.key.raw)
    features += SymbolicFeature(FeatureNamespace.PROVENANCE, "t0", "start-at", transition.startAt.toString())
    features += SymbolicFeature(FeatureNamespace.PROVENANCE, "t0", "end-at", transition.endAt.toString())
    return SignatureBlock("t0", features)
}

private fun admitConcept(
    key: ConceptIdentityKey,
    channel: String,
    holderRole: String,
    vocabulary: ConceptVocabularySnapshot,
    mask: SignatureMask,
    oov: MutableList<OovCoverage>,
    quantityText: String?,
    name: String = "emotion",
): SymbolicFeature? {
    if (key in mask.excludedConcepts || key.type in mask.excludedTypes) return null
    if (key.type !in ELIGIBLE_CONCEPT_TYPES || !vocabulary.contains(key)) {
        oov += OovCoverage(holderRole, key, quantityText)
        return null
    }
    return SymbolicFeature(
        namespace = FeatureNamespace.CONCEPT,
        channel = channel,
        name = name,
        value = conceptToken(key),
    )
}

private fun quantityText(quantity: OccurrenceQuantity?): String? = when (quantity) {
    null -> null
    is OccurrenceQuantity.WorkoutMinutes -> "minutes:${quantity.minutes}"
    is OccurrenceQuantity.UsageAmounts -> quantity.amounts.joinToString(";") { amount ->
        listOf(amount.status.name.lowercase(), amount.numericAmount?.toString() ?: "", amount.unit ?: "", amount.rawAmountText)
            .joinToString(":")
    }
}

private fun chronology(transition: TransitionOccurrenceProjection): String {
    val reasons = transition.eligibility.reasons
    return when {
        transition.boundary -> "boundary"
        CausalIneligibilityReason.START_UNRESOLVED in reasons -> "start-unresolved"
        CausalIneligibilityReason.INVALID_CHRONOLOGY in reasons -> "invalid"
        else -> "linked"
    }
}

private fun categoryToken(kind: CategoryObservationKindV2): String = when (kind) {
    CategoryObservationKindV2.NONE -> "reviewed-empty"
    CategoryObservationKindV2.ENTRIES -> "reviewed-entries"
    CategoryObservationKindV2.UNREVIEWED -> "unreviewed"
    CategoryObservationKindV2.ABSENT -> "absent"
    CategoryObservationKindV2.CONFLICTING -> "conflicting"
}

private fun conceptToken(key: ConceptIdentityKey): String {
    return "${key.type.name}|${key.identity}|${key.intent.orEmpty()}"
}

private fun status(channel: String, name: String, value: String): SymbolicFeature {
    return SymbolicFeature(FeatureNamespace.STATUS, channel, name, value)
}

private fun eligibleLinks(
    projection: PersonalSemanticOccurrenceProjection,
    cutoffAt: Long?,
): List<ConceptIdentityKey> {
    val stateTime = projection.states.associate { it.key to it.recordedAt }
    val transitionTime = projection.transitions.associate { it.key to it.endAt }
    return projection.links.mapNotNull { link ->
        if (link.concept.type !in ELIGIBLE_CONCEPT_TYPES) return@mapNotNull null
        val at = when (link) {
            is ConceptObservationLink.OnState -> stateTime[link.key]
            is ConceptObservationLink.OnTransition -> transitionTime[link.key]
        }
        if (cutoffAt != null && (at == null || at >= cutoffAt)) return@mapNotNull null
        link.concept
    }
}

private fun snapshot(
    links: List<ConceptIdentityKey>,
    scope: String,
    retrospective: Boolean,
    cutoffAt: Long?,
): ConceptVocabularySnapshot {
    val concepts = links.groupingBy { it }.eachCount()
        .entries
        .sortedWith(compareBy({ it.key.type.ordinal }, { it.key.identity }, { it.key.intent ?: "" }))
        .map { (key, support) ->
            VocabularyConcept(key = key, backboneSupport = support, provenance = "backbone")
        }
    return ConceptVocabularySnapshot(
        scope = scope,
        retrospective = retrospective,
        cutoffAt = cutoffAt,
        concepts = concepts,
    )
}

private fun judgments(vocabulary: ConceptVocabularySnapshot, mask: SignatureMask): List<String> {
    return listOf(
        "Layer zero stays beside recursion one. Reciprocal blocks are placed copies of those layer-zero signatures.",
        "Each recorded concept occurrence stays its own feature. Usage amounts already grouped on one identity stay listed on that occurrence. No sum, mean, or importance is applied.",
        "Reviewed-empty, unreviewed, absent, and conflicting stay separate category tokens.",
        "Stored duration is time metadata on layer-zero transitions. The causal transition does not copy it, and no a-priori horizon is stored in its place.",
        "Epoch time stays provenance and is not a semantic coordinate.",
        "Vocabulary scope is ${vocabulary.scope}. Support stays on that snapshot. It is not part of a concept column or of signature equality. A cutoff snapshot counts only occurrences before the cutoff. A concept outside the snapshot is OOV coverage, not a new column.",
        "Masking removes a typed identity before assembly. It does not fit an embedding under that mask.",
        "Applied mask excludes ${mask.excludedConcepts.size} identities and ${mask.excludedTypes.size} types.",
        DYNAMICS_ABSENT,
    )
}
