package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.DefaultCheckInAxes

/**
 * Retrospective leave-one-occurrence-out reconstruction.
 *
 * Each fold removes one recorded concept link from its owner block and from
 * every placed copy of that block. Other occurrences of the same identity
 * stay. An identity-wide mask is not used. The joint-embedding and
 * distributional geometries are fit again on those signatures and are not
 * changed. This is not walk-forward and not a causal evaluation.
 */
const val PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION =
    "personal-semantic-masked-concept-reconstruction-v1"

private const val RECONSTRUCTION_METHOD =
    "Leave-one-occurrence-out. Frequency, layer-zero context, and reciprocal context rank the same candidates. " +
        "A higher score ranks first. Ties break by identity, then intent, in ascending order. " +
        "A target with no remaining occurrence is cold-start and is not scored."

data class MaskedConceptReconstructionDataset(
    val protocol: String = PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION,
    val method: String = RECONSTRUCTION_METHOD,
    val retrospective: Boolean = true,
    val walkForward: Boolean = false,
    val causal: Boolean = false,
    val eligible: Int,
    val scored: Int,
    val coldStart: Int,
    val untrusted: Int,
    val frequency: ReconstructionMetrics,
    val layerZero: ReconstructionMetrics,
    val reciprocal: ReconstructionMetrics,
    val byType: List<ReconstructionSlice>,
    val bySupportBand: List<ReconstructionSlice>,
    val macro: ReconstructionMacroSlice,
    val macroByType: List<ReconstructionMacroSlice>,
    val macroBySupportBand: List<ReconstructionMacroSlice>,
    val paired: ReconstructionPairSlice,
    val pairedByType: List<ReconstructionPairSlice>,
    val pairedBySupportBand: List<ReconstructionPairSlice>,
    val identities: List<ReconstructionIdentitySummary>,
    val folds: List<ReconstructionFold>,
    val judgments: List<String>,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_MASKED_CONCEPT_RECONSTRUCTION)
        require(retrospective && !walkForward && !causal)
        require(!predictive && !updatesModel && !suitableForRecommendation)
        require(eligible == folds.size)
        require(scored + coldStart + untrusted == eligible)
    }
}

data class ReconstructionMetrics(
    val label: String,
    val eligible: Int,
    val scored: Int,
    val coldStart: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val meanReciprocalRank: Double?,
    val meanCandidateCount: Double?,
)

data class ReconstructionSlice(
    val label: String,
    val frequency: ReconstructionMetrics,
    val layerZero: ReconstructionMetrics,
    val reciprocal: ReconstructionMetrics,
)

data class ReconstructionMacroSlice(
    val label: String,
    val scoredIdentities: Int,
    val coldStartIdentities: Int,
    val frequency: ReconstructionMacroRates,
    val layerZero: ReconstructionMacroRates,
    val reciprocal: ReconstructionMacroRates,
)

data class ReconstructionMacroRates(
    val label: String,
    val top1Rate: Double?,
    val top3Rate: Double?,
    val meanReciprocalRank: Double?,
)

data class ReconstructionPairSlice(
    val label: String,
    val versusLayerZero: ReconstructionPairing,
    val versusFrequency: ReconstructionPairing,
)

data class ReconstructionPairing(
    val baseline: String,
    val scoredFolds: Int,
    val better: Int,
    val tied: Int,
    val worse: Int,
    val meanReciprocalRankDelta: Double?,
    val top1Gained: Int,
    val top1Lost: Int,
) {
    init {
        require(better + tied + worse == scoredFolds)
    }
}

data class ReconstructionIdentitySummary(
    val label: String,
    val type: SemanticConceptType,
    val scoredFolds: Int,
    val coldStartFolds: Int,
    val untrustedFolds: Int,
    val supportRemaining: Int,
    val frequency: ReconstructionIdentityRates,
    val layerZero: ReconstructionIdentityRates,
    val reciprocal: ReconstructionIdentityRates,
)

data class ReconstructionIdentityRates(
    val top1Hits: Int,
    val top3Hits: Int,
    val meanReciprocalRank: Double?,
)

data class ReconstructionApproach(
    val name: String,
    val ranked: List<ConceptIdentityKey>,
    val rank: Int?,
    val score: Double?,
) {
    val candidateCount: Int get() = ranked.size
}

data class ReconstructionFold(
    val id: String,
    val type: SemanticConceptType,
    val target: ConceptIdentityKey,
    val ownerAlias: String,
    val ownerKind: String,
    val hiddenText: String,
    val visibleText: String,
    val supportBefore: Int,
    val supportRemaining: Int,
    val coldStart: Boolean,
    val heldOutAbsent: Boolean,
    val absenceText: String,
    val candidates: List<ConceptIdentityKey>,
    val frequency: ReconstructionApproach,
    val layerZero: ReconstructionApproach,
    val reciprocal: ReconstructionApproach,
    val layerZeroLandmarks: List<String>,
    val reciprocalLandmarks: List<String>,
) {
    val scored: Boolean get() = heldOutAbsent && !coldStart

    fun line(): String {
        val head = "$hiddenText · $ownerAlias · $ownerKind"
        if (!heldOutAbsent) return "$head · not trusted"
        if (coldStart) return "$head · cold-start · not scored"
        return "$head · remaining $supportRemaining · " +
            "frequency ${rankText(frequency)} · layer zero ${rankText(layerZero)} · reciprocal ${rankText(reciprocal)}"
    }
}

fun evaluateMaskedConceptReconstruction(
    projection: PersonalSemanticOccurrenceProjection,
    graph: AlternatingTemporalGraph,
    signatures: ReciprocalSignatureDataset,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
): MaskedConceptReconstructionDataset {
    val baseCounts = eligibleLinks(projection).groupingBy { it.concept }.eachCount()
    val folds = holdouts(projection).map { holdout ->
        evaluateFold(holdout, baseCounts, projection, graph, signatures, audit, reading)
    }
    val identities = identitySummaries(folds)
    return MaskedConceptReconstructionDataset(
        eligible = folds.size,
        scored = folds.count { it.scored },
        coldStart = folds.count { it.coldStart },
        untrusted = folds.count { !it.heldOutAbsent },
        frequency = metrics("Frequency", folds) { it.frequency },
        layerZero = metrics("Layer zero", folds) { it.layerZero },
        reciprocal = metrics("Reciprocal", folds) { it.reciprocal },
        byType = SemanticConceptType.entries.filter { isEligibleConceptType(it) }.mapNotNull { type ->
            val matching = folds.filter { it.type == type }
            if (matching.isEmpty()) null else slice(type.auditLabel(), matching)
        },
        bySupportBand = listOf("1", "2-4", "5+").mapNotNull { band ->
            val matching = folds.filter { it.scored && supportBand(it.supportRemaining) == band }
            if (matching.isEmpty()) null else slice("remaining $band", matching)
        },
        macro = macroSlice("overall", identities),
        macroByType = SemanticConceptType.entries.filter { isEligibleConceptType(it) }.mapNotNull { type ->
            val matching = identities.filter { it.type == type }
            if (matching.isEmpty()) null else macroSlice(type.auditLabel(), matching)
        },
        macroBySupportBand = listOf("1", "2-4", "5+").mapNotNull { band ->
            val matching = identities.filter { supportBand(it.supportRemaining) == band }
            if (matching.isEmpty()) null else macroSlice("remaining $band", matching)
        },
        paired = pairSlice("overall", folds),
        pairedByType = SemanticConceptType.entries.filter { isEligibleConceptType(it) }.mapNotNull { type ->
            val matching = folds.filter { it.type == type }
            if (matching.none { it.scored }) null else pairSlice(type.auditLabel(), matching)
        },
        pairedBySupportBand = listOf("1", "2-4", "5+").mapNotNull { band ->
            val matching = folds.filter { it.scored && supportBand(it.supportRemaining) == band }
            if (matching.isEmpty()) null else pairSlice("remaining $band", matching)
        },
        identities = identities,
        folds = folds,
        judgments = judgments(),
    )
}

private fun evaluateFold(
    holdout: Holdout,
    baseCounts: Map<ConceptIdentityKey, Int>,
    projection: PersonalSemanticOccurrenceProjection,
    graph: AlternatingTemporalGraph,
    signatures: ReciprocalSignatureDataset,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
): ReconstructionFold {
    val before = baseCounts[holdout.concept] ?: 0
    val remaining = before - 1
    val stripped = stripHeldOutConcept(signatures, holdout)
    val absent = heldOutIsAbsent(stripped, holdout, remainingOnOwner(projection, holdout))
    val candidates = candidateKeys(baseCounts, holdout, remaining)
    val coldStart = absent && remaining <= 0
    val scored = absent && remaining > 0
    val ownerId = if (holdout.ownerKind == "state") stateNodeId(holdout.ownerRaw) else transitionNodeId(holdout.ownerRaw)
    val targetId = conceptNodeId(holdout.concept)
    val context = if (scored) projectDistributionalContext(projectJointSemanticEmbedding(stripped, graph, projection)) else null
    val counts = remainingCounts(baseCounts, holdout)
    val frequency = if (context == null) {
        unscored("Frequency", candidates)
    } else {
        approach("Frequency", candidates, holdout.concept) { key -> (counts[key] ?: 0).toDouble() }
    }
    val layerZero = if (context == null) {
        unscored("Layer zero", candidates)
    } else {
        approach("Layer zero", candidates, holdout.concept) { key ->
            context.layer(LAYER_ZERO).similarity(ownerId, conceptNodeId(key))
        }
    }
    val reciprocal = if (context == null) {
        unscored("Reciprocal", candidates)
    } else {
        approach("Reciprocal", candidates, holdout.concept) { key ->
            context.layer(RECIPROCAL_LAYER).similarity(ownerId, conceptNodeId(key))
        }
    }
    return ReconstructionFold(
        id = "${tokenOf(holdout.concept)}|${holdout.ownerKind}:${holdout.ownerRaw}|${holdout.ordinal}",
        type = holdout.concept.type,
        target = holdout.concept,
        ownerAlias = audit.aliasByNode[ownerId] ?: holdout.ownerKind,
        ownerKind = holdout.ownerKind,
        hiddenText = hiddenText(holdout, audit),
        visibleText = visibleText(holdout, stripped, projection, audit),
        supportBefore = before,
        supportRemaining = remaining.coerceAtLeast(0),
        coldStart = coldStart,
        heldOutAbsent = absent,
        absenceText = if (absent) {
            "Held-out occurrence absent from the owner and from reciprocal copies."
        } else {
            "Held-out occurrence still appears on the owner or a reciprocal copy."
        },
        candidates = candidates,
        frequency = frequency,
        layerZero = layerZero,
        reciprocal = reciprocal,
        layerZeroLandmarks = landmarkLines(context?.layer(LAYER_ZERO), ownerId, targetId, audit, reading),
        reciprocalLandmarks = landmarkLines(context?.layer(RECIPROCAL_LAYER), ownerId, targetId, audit, reading),
    )
}

private fun unscored(name: String, candidates: List<ConceptIdentityKey>): ReconstructionApproach {
    return ReconstructionApproach(name, candidates.sortedWith(CANDIDATE_ORDER), rank = null, score = null)
}

private fun landmarkLines(
    layer: DistributionalLayer?,
    ownerId: String,
    targetId: String,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
): List<String> {
    if (layer == null) return emptyList()
    return layer.landmarkContributions(ownerId, targetId).take(4).map { contribution ->
        "${reading.line(contribution.landmarkId, audit)} · contribution ${formatDistributionalContribution(contribution.contribution)}"
    }
}

private fun approach(
    name: String,
    candidates: List<ConceptIdentityKey>,
    target: ConceptIdentityKey,
    score: (ConceptIdentityKey) -> Double,
): ReconstructionApproach {
    val ranked = candidates.sortedWith(compareByDescending<ConceptIdentityKey> { score(it) }.then(CANDIDATE_ORDER))
    val rank = ranked.indexOf(target) + 1
    return ReconstructionApproach(name, ranked, rank = rank, score = score(target))
}

private fun candidateKeys(
    baseCounts: Map<ConceptIdentityKey, Int>,
    holdout: Holdout,
    remaining: Int,
): List<ConceptIdentityKey> {
    val counts = remainingCounts(baseCounts, holdout)
    if (remaining > 0) counts[holdout.concept] = remaining
    return counts.filter { (key, count) -> key.type == holdout.concept.type && count > 0 }.keys
        .sortedWith(CANDIDATE_ORDER)
}

private fun remainingCounts(
    baseCounts: Map<ConceptIdentityKey, Int>,
    holdout: Holdout,
): MutableMap<ConceptIdentityKey, Int> {
    val counts = baseCounts.toMutableMap()
    val next = (counts[holdout.concept] ?: 0) - 1
    if (next <= 0) counts.remove(holdout.concept) else counts[holdout.concept] = next
    return counts
}

private fun metrics(
    label: String,
    folds: List<ReconstructionFold>,
    approach: (ReconstructionFold) -> ReconstructionApproach,
): ReconstructionMetrics {
    val scored = folds.filter { it.scored }
    val hits1 = scored.count { approach(it).rank == 1 }
    val hits3 = scored.count { (approach(it).rank ?: Int.MAX_VALUE) <= 3 }
    val mrr = if (scored.isEmpty()) null else scored.sumOf { 1.0 / (approach(it).rank ?: 1) } / scored.size
    val candidates = if (scored.isEmpty()) null else scored.sumOf { it.candidates.size }.toDouble() / scored.size
    return ReconstructionMetrics(
        label = label,
        eligible = folds.size,
        scored = scored.size,
        coldStart = folds.count { it.coldStart },
        top1Hits = hits1,
        top3Hits = hits3,
        meanReciprocalRank = mrr,
        meanCandidateCount = candidates,
    )
}

private fun slice(label: String, folds: List<ReconstructionFold>): ReconstructionSlice {
    return ReconstructionSlice(
        label = label,
        frequency = metrics("Frequency", folds) { it.frequency },
        layerZero = metrics("Layer zero", folds) { it.layerZero },
        reciprocal = metrics("Reciprocal", folds) { it.reciprocal },
    )
}

private fun identitySummaries(folds: List<ReconstructionFold>): List<ReconstructionIdentitySummary> {
    return folds.groupBy { it.target }.map { (_, group) ->
        val sample = group.first()
        val remaining = group.map { it.supportRemaining }.distinct().single()
        ReconstructionIdentitySummary(
            label = identityLabel(sample),
            type = sample.type,
            scoredFolds = group.count { it.scored },
            coldStartFolds = group.count { it.coldStart },
            untrustedFolds = group.count { !it.heldOutAbsent },
            supportRemaining = remaining,
            frequency = identityRates(group) { it.frequency },
            layerZero = identityRates(group) { it.layerZero },
            reciprocal = identityRates(group) { it.reciprocal },
        )
    }.sortedWith(compareByDescending<ReconstructionIdentitySummary> { it.scoredFolds }.thenBy { it.label })
}

private fun identityLabel(fold: ReconstructionFold): String {
    val intent = fold.target.intent?.takeIf { it.isNotBlank() }
    return if (intent == null) fold.hiddenText else "${fold.hiddenText} · $intent"
}

private fun identityRates(
    folds: List<ReconstructionFold>,
    approach: (ReconstructionFold) -> ReconstructionApproach,
): ReconstructionIdentityRates {
    val scored = folds.filter { it.scored }
    if (scored.isEmpty()) return ReconstructionIdentityRates(0, 0, null)
    return ReconstructionIdentityRates(
        top1Hits = scored.count { checkNotNull(approach(it).rank) == 1 },
        top3Hits = scored.count { checkNotNull(approach(it).rank) <= 3 },
        meanReciprocalRank = scored.sumOf { 1.0 / checkNotNull(approach(it).rank) } / scored.size,
    )
}

private fun macroSlice(
    label: String,
    identities: List<ReconstructionIdentitySummary>,
): ReconstructionMacroSlice {
    val scored = identities.filter { it.scoredFolds > 0 }
    val coldStart = identities.filter { it.scoredFolds == 0 && it.coldStartFolds > 0 }
    return ReconstructionMacroSlice(
        label = label,
        scoredIdentities = scored.size,
        coldStartIdentities = coldStart.size,
        frequency = macroRates("Frequency", scored) { it.frequency },
        layerZero = macroRates("Layer zero", scored) { it.layerZero },
        reciprocal = macroRates("Reciprocal", scored) { it.reciprocal },
    )
}

private fun macroRates(
    label: String,
    identities: List<ReconstructionIdentitySummary>,
    rates: (ReconstructionIdentitySummary) -> ReconstructionIdentityRates,
): ReconstructionMacroRates {
    if (identities.isEmpty()) return ReconstructionMacroRates(label, null, null, null)
    return ReconstructionMacroRates(
        label = label,
        top1Rate = identities.map { rates(it).top1Hits.toDouble() / it.scoredFolds }.average(),
        top3Rate = identities.map { rates(it).top3Hits.toDouble() / it.scoredFolds }.average(),
        meanReciprocalRank = identities.map { checkNotNull(rates(it).meanReciprocalRank) }.average(),
    )
}

private fun pairSlice(label: String, folds: List<ReconstructionFold>): ReconstructionPairSlice {
    return ReconstructionPairSlice(
        label = label,
        versusLayerZero = pairing("Layer zero", folds) { it.layerZero },
        versusFrequency = pairing("Frequency", folds) { it.frequency },
    )
}

private fun pairing(
    baseline: String,
    folds: List<ReconstructionFold>,
    other: (ReconstructionFold) -> ReconstructionApproach,
): ReconstructionPairing {
    val scored = folds.filter { it.scored }
    if (scored.isEmpty()) {
        return ReconstructionPairing(baseline, 0, 0, 0, 0, null, 0, 0)
    }
    var better = 0
    var tied = 0
    var worse = 0
    var gained = 0
    var lost = 0
    var delta = 0.0
    scored.forEach { fold ->
        val reciprocal = checkNotNull(fold.reciprocal.rank)
        val baselineRank = checkNotNull(other(fold).rank)
        when {
            reciprocal < baselineRank -> better += 1
            reciprocal == baselineRank -> tied += 1
            else -> worse += 1
        }
        if (reciprocal == 1 && baselineRank != 1) gained += 1
        if (baselineRank == 1 && reciprocal != 1) lost += 1
        delta += 1.0 / reciprocal - 1.0 / baselineRank
    }
    return ReconstructionPairing(
        baseline = baseline,
        scoredFolds = scored.size,
        better = better,
        tied = tied,
        worse = worse,
        meanReciprocalRankDelta = delta / scored.size,
        top1Gained = gained,
        top1Lost = lost,
    )
}

private fun supportBand(remaining: Int): String = when {
    remaining <= 1 -> "1"
    remaining <= 4 -> "2-4"
    else -> "5+"
}

private data class Holdout(
    val ownerKind: String,
    val ownerRaw: String,
    val concept: ConceptIdentityKey,
    val ordinal: Int,
    val label: String,
)

private fun holdouts(projection: PersonalSemanticOccurrenceProjection): List<Holdout> {
    val found = mutableListOf<Holdout>()
    projection.links.filterIsInstance<ConceptObservationLink.OnState>()
        .filter { isEligibleConceptType(it.concept.type) }
        .groupBy { it.key.raw }
        .forEach { (raw, links) -> found += numbered(links, "state", raw) }
    projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
        .filter { isEligibleConceptType(it.concept.type) }
        .groupBy { it.key.raw }
        .forEach { (raw, links) -> found += numbered(links.sortedWith(LINK_ORDER), "transition", raw) }
    return found.sortedWith(
        compareBy(
            { it.concept.type.ordinal },
            { it.concept.identity },
            { it.concept.intent ?: "" },
            { it.ownerKind },
            { it.ownerRaw },
            { it.ordinal },
        ),
    )
}

private fun numbered(links: List<ConceptObservationLink>, ownerKind: String, ownerRaw: String): List<Holdout> {
    val seen = mutableMapOf<ConceptIdentityKey, Int>()
    return links.map { link ->
        val ordinal = seen.getOrDefault(link.concept, 0)
        seen[link.concept] = ordinal + 1
        Holdout(ownerKind, ownerRaw, link.concept, ordinal, link.label)
    }
}

private fun eligibleLinks(projection: PersonalSemanticOccurrenceProjection): List<ConceptObservationLink> {
    return projection.links.filter { isEligibleConceptType(it.concept.type) }
}

private fun remainingOnOwner(projection: PersonalSemanticOccurrenceProjection, holdout: Holdout): Int {
    val count = eligibleLinks(projection).count { link ->
        link.concept == holdout.concept && ownerRaw(link) == holdout.ownerRaw && ownerKind(link) == holdout.ownerKind
    }
    return (count - 1).coerceAtLeast(0)
}

private fun ownerRaw(link: ConceptObservationLink): String = when (link) {
    is ConceptObservationLink.OnState -> link.key.raw
    is ConceptObservationLink.OnTransition -> link.key.raw
}

private fun ownerKind(link: ConceptObservationLink): String = when (link) {
    is ConceptObservationLink.OnState -> "state"
    is ConceptObservationLink.OnTransition -> "transition"
}

private fun stripHeldOutConcept(
    signatures: ReciprocalSignatureDataset,
    holdout: Holdout,
): ReciprocalSignatureDataset {
    val provenanceName = if (holdout.ownerKind == "state") "state-key" else "transition-key"
    fun SignatureBlock.cleaned(): SignatureBlock = strip(provenanceName, holdout.ownerRaw, tokenOf(holdout.concept), holdout.ordinal)
    return signatures.copy(
        states = signatures.states.map { state ->
            state.copy(
                layerZero = state.layerZero.cleaned(),
                retrospective = state.retrospective.map { it.cleaned() },
                causal = state.causal.map { it.cleaned() },
            )
        },
        transitions = signatures.transitions.map { transition ->
            transition.copy(
                layerZero = transition.layerZero.cleaned(),
                retrospective = transition.retrospective.map { it.cleaned() },
                causal = transition.causal.map { it.cleaned() },
            )
        },
    )
}

private fun SignatureBlock.strip(provenanceName: String, ownerRaw: String, token: String, ordinal: Int): SignatureBlock {
    if (!owns(provenanceName, ownerRaw)) return this
    var seen = 0
    var removeIndex = -1
    features.forEachIndexed { index, feature ->
        if (feature.namespace == FeatureNamespace.CONCEPT && feature.value == token) {
            if (seen == ordinal) removeIndex = index
            seen += 1
        }
    }
    if (removeIndex < 0) return this
    val featureName = features[removeIndex].name
    return copy(
        features = features.filterIndexed { index, feature ->
            index != removeIndex && !(feature.namespace == FeatureNamespace.QUANTITY && feature.name == featureName)
        },
    )
}

private fun heldOutIsAbsent(
    signatures: ReciprocalSignatureDataset,
    holdout: Holdout,
    remainingOnOwner: Int,
): Boolean {
    val provenanceName = if (holdout.ownerKind == "state") "state-key" else "transition-key"
    val token = tokenOf(holdout.concept)
    val owned = ownedBlocks(signatures, provenanceName, holdout.ownerRaw)
    if (owned.isEmpty()) return false
    return owned.all { block ->
        block.features.count { it.namespace == FeatureNamespace.CONCEPT && it.value == token } == remainingOnOwner
    }
}

private fun ownedBlocks(
    signatures: ReciprocalSignatureDataset,
    provenanceName: String,
    ownerRaw: String,
): List<SignatureBlock> {
    val blocks = mutableListOf<SignatureBlock>()
    signatures.states.forEach { state ->
        blocks += state.layerZero
        blocks += state.retrospective
        blocks += state.causal
    }
    signatures.transitions.forEach { transition ->
        blocks += transition.layerZero
        blocks += transition.retrospective
        blocks += transition.causal
    }
    return blocks.filter { it.owns(provenanceName, ownerRaw) }
}

private fun SignatureBlock.owns(provenanceName: String, ownerRaw: String): Boolean {
    return features.any { feature ->
        feature.namespace == FeatureNamespace.PROVENANCE && feature.name == provenanceName && feature.value == ownerRaw
    }
}

private fun hiddenText(holdout: Holdout, audit: EmbeddingAuditIndex): String {
    val label = audit.labelByConcept[conceptNodeId(holdout.concept)]?.takeIf { it.isNotBlank() } ?: holdout.label
    val readable = label.takeIf { '|' !in it && !it.startsWith("option:") } ?: holdout.concept.type.auditLabel()
    return "$readable · ${holdout.concept.type.auditLabel()}"
}

private fun visibleText(
    holdout: Holdout,
    stripped: ReciprocalSignatureDataset,
    projection: PersonalSemanticOccurrenceProjection,
    audit: EmbeddingAuditIndex,
): String {
    val ownerId = if (holdout.ownerKind == "state") stateNodeId(holdout.ownerRaw) else transitionNodeId(holdout.ownerRaw)
    val alias = audit.aliasByNode[ownerId] ?: holdout.ownerKind
    val block = if (holdout.ownerKind == "state") {
        stripped.states.first { it.key.raw == holdout.ownerRaw }.layerZero
    } else {
        stripped.transitions.first { it.key.raw == holdout.ownerRaw }.layerZero
    }
    val labels = block.semantic()
        .filter { it.namespace == FeatureNamespace.CONCEPT }
        .map { feature ->
            audit.labelByConcept["concept:${feature.value}"]?.takeIf { name -> name.isNotBlank() && '|' !in name }
                ?: feature.value.substringBefore('|').lowercase()
        }
    val parts = mutableListOf(alias)
    if (holdout.ownerKind == "state") {
        val state = projection.states.first { it.key.raw == holdout.ownerRaw }
        val axes = visibleAxes(state)
        if (axes.isNotEmpty()) parts += axes
    } else {
        val transition = projection.transitions.first { it.key.raw == holdout.ownerRaw }
        val start = transition.startStateKey?.let { audit.aliasByNode[stateNodeId(it.raw)] }
        val end = audit.aliasByNode[stateNodeId(transition.endStateKey.raw)]
        if (start != null && end != null) parts += "$start→$end"
        else if (end != null && transition.boundary) parts += "boundary→$end"
        else if (end != null) parts += "→$end"
    }
    if (labels.isNotEmpty()) parts += labels.joinToString(", ")
    return parts.joinToString(" · ")
}

private fun visibleAxes(state: StateOccurrenceProjection): String {
    return listOf(
        DefaultCheckInAxes.MOOD to "M",
        DefaultCheckInAxes.ENERGY to "E",
        DefaultCheckInAxes.CONFIDENCE to "C",
        DefaultCheckInAxes.ANXIETY to "A",
    ).mapNotNull { (axisId, letter) ->
        val axis = state.axes.firstOrNull { it.axisId == axisId } ?: return@mapNotNull null
        val raw = axis.raw ?: return@mapNotNull null
        if (axis.status != AxisOccurrenceStatus.OBSERVED) return@mapNotNull null
        "$letter$raw"
    }.joinToString(" ")
}

private fun rankText(approach: ReconstructionApproach): String {
    val rank = approach.rank ?: return "not scored"
    return "rank $rank"
}

private fun tokenOf(key: ConceptIdentityKey): String {
    return "${key.type.name}|${key.identity}|${key.intent.orEmpty()}"
}

private fun judgments(): List<String> {
    return listOf(
        "Retrospective leave-one-occurrence-out. This is not walk-forward and not a causal evaluation.",
        "The held-out link is removed from its owner block and from every placed copy of that block. Other occurrences of the identity remain. An identity-wide mask is not used.",
        "Frequency counts occurrences that remain after that removal. Layer-zero and reciprocal context use the frozen geometries on the rebuilt signatures.",
        "Candidates are identities of the same concept type. Reviewed-empty, unreviewed, absent, and conflicting categories are not targets. Quantity is not predicted.",
        "A higher score ranks first. Ties break by identity, then intent, in ascending order.",
        "A target with no remaining occurrence is cold-start and is not an incorrect prediction.",
        "Each scored identity has equal weight in the macro averages. A cold-start identity is counted apart from that average.",
        "Paired counts compare reciprocal rank with layer zero and with frequency on the same scored folds. Better means a smaller rank. The reciprocal-rank delta is the mean of one over the reciprocal rank minus one over the other rank.",
        "The occurrence-weighted metrics stay beside those summaries. The summaries do not select a winner, fit a mixture, or change a rank.",
        "This evaluation does not cluster, name a region, recommend, or read a Null residual.",
    )
}

private val LINK_ORDER = compareBy<ConceptObservationLink>(
    { it.concept.type.ordinal },
    { it.concept.identity },
    { it.concept.intent ?: "" },
    { it.label },
)

private val CANDIDATE_ORDER = compareBy<ConceptIdentityKey>({ it.identity }, { it.intent ?: "" })
