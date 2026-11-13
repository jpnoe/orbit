package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Retrospective univariate descriptions of one selected hard partition.
 *
 * The partition, its hierarchy, and its layout are read and never rewritten.
 * Comparison sets are rebuilt from recorded transitions. Aggregated edge
 * counts are not used, because co-occurring behaviors cannot be separated
 * from those totals.
 *
 * Distributional context stays a second-order profile. This pass does not
 * add a graph radius.
 */
const val PERSONAL_SEMANTIC_REGION_DESCRIPTION = "personal-semantic-region-description-v1"

const val REGION_UNIVERSAL_DISCLOSURE =
    "Retrospective descriptive association. Behaviors co-occur and are not isolated. Not a cause, effect, prediction, or recommendation."

const val REGION_RECIPROCAL_CIRCULARITY =
    "Incoming and outgoing context contributed to this grouping. These behavior associations describe the resulting composition and are not independent confirmation."

const val REGION_DIRECT_CIRCULARITY =
    "Observed readings and directly carried concepts can contribute to this grouping. These descriptions explain the recorded partition and are not independent confirmation."

const val REGION_DISTRIBUTIONAL_CIRCULARITY =
    "Distributional profiles are a second-order similarity. These descriptions explain the recorded partition and are not independent confirmation. Distributional context is not graph radius 2."

const val REGION_SOURCE_DISTANCE_NOTE =
    "Source-space distance is authoritative. Screen position is approximate."

const val REGION_TENTATIVE = "single observation — tentative"

internal const val REGION_HEAD_CHARACTER =
    "This region is most distinctly characterized by"

internal const val REGION_HEAD_ENTRY =
    "This region is most associated with being entered after"

internal const val REGION_HEAD_MAINTENANCE =
    "This region is most associated with remaining within it during"

internal const val REGION_HEAD_EXIT =
    "This region is most associated with being exited during"

private val CORE_AXES = listOf(
    DefaultCheckInAxes.MOOD,
    DefaultCheckInAxes.ENERGY,
    DefaultCheckInAxes.CONFIDENCE,
    DefaultCheckInAxes.ANXIETY,
)

private val AXIS_NAMES = mapOf(
    DefaultCheckInAxes.MOOD to "Mood",
    DefaultCheckInAxes.ENERGY to "Energy",
    DefaultCheckInAxes.CONFIDENCE to "Confidence",
    DefaultCheckInAxes.ANXIETY to "Anxiety",
)

data class RegionDescriptionDataset(
    val protocol: String = PERSONAL_SEMANTIC_REGION_DESCRIPTION,
    val views: List<RegionDescriptionView>,
    val judgments: List<String>,
    val retrospective: Boolean = true,
    val causal: Boolean = false,
    val predictive: Boolean = false,
    val updatesModel: Boolean = false,
    val suitableForRecommendation: Boolean = false,
) {
    init {
        require(protocol == PERSONAL_SEMANTIC_REGION_DESCRIPTION)
        require(retrospective && !causal && !predictive && !updatesModel && !suitableForRecommendation)
    }
}

data class RegionDescriptionView(
    val geometry: String,
    val geometryLabel: String,
    val context: String,
    val contextLabel: String,
    val circularity: String,
    val disclosure: String = REGION_UNIVERSAL_DISCLOSURE,
    val cuts: List<RegionDescriptionCut>,
)

data class RegionDescriptionCut(
    val regions: List<RegionDescription>,
)

data class RegionDescription(
    val alias: String,
    val nodeId: String,
    val memberCount: Int,
    val medoidLabel: String,
    val threshold: Double?,
    val thresholdLabel: String,
    val emotionMixture: String,
    val insideIds: List<String>,
    val outsideIds: List<String>,
    val ungroupedIds: List<String>,
    val axes: List<RegionAxisComparison>,
    val minimumWithinSimilarity: Double?,
    val meanWithinSimilarity: Double?,
    val nearest: List<RegionProximity>,
    val characterization: List<RegionFeatureAssociation>,
    val characterizationConcise: List<RegionFeatureAssociation>,
    val entry: List<RegionBehaviorAssociation>,
    val entryConcise: List<RegionBehaviorAssociation>,
    val maintenance: List<RegionBehaviorAssociation>,
    val maintenanceConcise: List<RegionBehaviorAssociation>,
    val exit: List<RegionBehaviorAssociation>,
    val exitConcise: List<RegionBehaviorAssociation>,
    val entryOpportunities: Int,
    val entered: Int,
    val outgoing: Int,
    val maintained: Int,
    val exited: Int,
    val boundaryEndings: Int,
    val ungroupedSources: Int,
    val ungroupedDestinations: Int,
    val lineage: List<String>,
    val circularity: String,
    val disclosure: String = REGION_UNIVERSAL_DISCLOSURE,
    val characterHeading: String = REGION_HEAD_CHARACTER,
    val entryHeading: String = REGION_HEAD_ENTRY,
    val maintenanceHeading: String = REGION_HEAD_MAINTENANCE,
    val exitHeading: String = REGION_HEAD_EXIT,
)

data class RegionAxisComparison(
    val axisId: String,
    val label: String,
    val inside: RegionNumericSummary,
    val outside: RegionNumericSummary,
    val correlation: Double?,
    val concise: String,
    val evidence: String,
)

data class RegionNumericSummary(
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

data class RegionFeatureAssociation(
    val identity: String,
    val label: String,
    val type: String,
    val insideCount: Int,
    val insideTotal: Int,
    val outsideCount: Int,
    val outsideTotal: Int,
    val insideRate: Double?,
    val outsideRate: Double?,
    val rateDifference: Double?,
    val phi: Double?,
    val totalSupport: Int,
    val tentative: Boolean,
    val concise: String,
    val evidence: String,
)

data class RegionBehaviorAssociation(
    val identity: String,
    val label: String,
    val type: String,
    val presentCount: Int,
    val presentOutcome: Int,
    val absentCount: Int,
    val absentOutcome: Int,
    val rateDifference: Double?,
    val phi: Double?,
    val coverageCount: Int,
    val coverageTotal: Int,
    val tentative: Boolean,
    val destinations: List<RegionDestinationCount>,
    val concise: String,
    val evidence: String,
)

data class RegionDestinationCount(
    val alias: String,
    val count: Int,
)

data class RegionProximity(
    val alias: String,
    val memberCount: Int,
    val sourceDistance: Double,
    val meanSimilarity: Double,
    val minimumSimilarity: Double,
    val layoutDistance: Double?,
    val concise: String,
)

fun describePersonalSemanticRegions(
    atlas: StateGroupAtlasDataset,
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection,
): RegionDescriptionDataset {
    val catalog = behaviorCatalog(graph, projection, distributional.reading, embedding.audit)
    val transitions = transitionRecords(graph, projection, catalog)
    val views = atlas.views.map { view ->
        val cosine = cosineFor(view, embedding, distributional)
        RegionDescriptionView(
            geometry = view.geometry,
            geometryLabel = view.geometryLabel,
            context = view.context,
            contextLabel = view.contextLabel,
            circularity = circularityFor(view),
            cuts = view.cuts.mapIndexed { index, cut ->
                RegionDescriptionCut(
                    regions = cut.groups.map { group ->
                        describeRegion(view, index, cut, group, cosine, projection, transitions, catalog)
                    },
                )
            },
        )
    }
    return RegionDescriptionDataset(
        views = views,
        judgments = regionJudgments(),
    )
}

internal fun pointBiserial(values: List<Pair<Double, Boolean>>): Double? {
    if (values.size < 2) return null
    if (values.none { it.second } || values.none { !it.second }) return null
    val meanX = values.map { it.first }.average()
    val meanY = values.count { it.second }.toDouble() / values.size
    var numerator = 0.0
    var xSquare = 0.0
    var ySquare = 0.0
    values.forEach { (value, member) ->
        val dx = value - meanX
        val dy = (if (member) 1.0 else 0.0) - meanY
        numerator += dx * dy
        xSquare += dx * dx
        ySquare += dy * dy
    }
    if (xSquare <= 1e-18 || ySquare <= 1e-18) return null
    return numerator / sqrt(xSquare * ySquare)
}

internal fun phiCorrelation(present: List<Boolean>, outcome: List<Boolean>): Double? {
    if (present.size != outcome.size || present.isEmpty()) return null
    var presentOutcome = 0
    var presentOther = 0
    var absentOutcome = 0
    var absentOther = 0
    present.indices.forEach { index ->
        when {
            present[index] && outcome[index] -> presentOutcome += 1
            present[index] -> presentOther += 1
            outcome[index] -> absentOutcome += 1
            else -> absentOther += 1
        }
    }
    val presentCount = presentOutcome + presentOther
    val absentCount = absentOutcome + absentOther
    val outcomeCount = presentOutcome + absentOutcome
    val otherCount = presentOther + absentOther
    if (presentCount == 0 || absentCount == 0 || outcomeCount == 0 || otherCount == 0) return null
    val denominator = sqrt(presentCount.toDouble() * absentCount * outcomeCount * otherCount)
    if (denominator <= 1e-18) return null
    return (presentOutcome.toDouble() * absentOther - presentOther.toDouble() * absentOutcome) / denominator
}

internal fun formatRecordedDuration(millis: Long): String {
    val sign = if (millis < 0) "-" else ""
    val total = abs(millis)
    val hours = total / 3_600_000L
    val minutes = (total % 3_600_000L) / 60_000L
    val seconds = (total % 60_000L) / 1_000L
    val parts = mutableListOf<String>()
    if (hours > 0L) parts += "${hours}h"
    if (minutes > 0L) parts += "${minutes}m"
    if (seconds > 0L || parts.isEmpty()) parts += "${seconds}s"
    return sign + parts.joinToString(" ")
}

internal fun formatRecordedDuration(millis: Double): String = formatRecordedDuration(round(millis).toLong())

internal fun formatRatePoints(difference: Double): String {
    val scaled = round(difference * 1000.0).toLong()
    val sign = if (scaled < 0) "-" else "+"
    val absolute = abs(scaled)
    return "$sign${absolute / 10}.${absolute % 10} points"
}

internal fun unduplicatedRecordLabel(alias: String, label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty() || trimmed == alias) return alias
    val prefixed = "$alias · "
    if (!trimmed.startsWith(prefixed)) return "$alias · $trimmed"
    val rest = trimmed.removePrefix(prefixed).trim()
    if (rest.isEmpty() || rest == alias) return alias
    return "$alias · $rest"
}

internal fun regionLineage(view: StateGroupAtlasView, cutIndex: Int, group: StateGroup): List<String> {
    val lines = mutableListOf<String>()
    val tighter = view.cuts.getOrNull(cutIndex - 1)
    when {
        tighter == null -> lines += "This is the tightest cut."
        group.tighterChildren.isEmpty() -> lines += "At the next tighter cut: this region does not split."
        else -> {
            val children = group.tighterChildren.map { alias ->
                val child = tighter.groups.first { it.alias == alias }
                "${child.members.size}-state child $alias"
            }
            val merged = group.formedAtSimilarity?.let { ", merged at ${formatRegionNumber(it)}" }.orEmpty()
            lines += "At the next tighter cut: ${joinPhrases(children)}$merged."
        }
    }
    val looser = view.cuts.getOrNull(cutIndex + 1)
    when {
        looser == null -> lines += "This is the loosest cut."
        group.looserParent != null -> {
            val parent = looser.groups.first { it.alias == group.looserParent }
            val at = parent.formedAtSimilarity?.let { " at ${formatRegionNumber(it)}" }.orEmpty()
            lines += "At the next looser cut: joins ${parent.members.size}-state parent ${parent.alias}$at."
        }
        group.continuesAs != null -> {
            val noun = if (group.members.size == 1) "state" else "states"
            lines += "At the next looser cut: the same ${group.members.size} $noun continues as ${group.continuesAs}."
        }
        else -> lines += "At the next looser cut: this region has no larger parent."
    }
    lines += "Hierarchy node ${group.nodeId}."
    return lines
}

private fun describeRegion(
    view: StateGroupAtlasView,
    cutIndex: Int,
    cut: StateGroupCut,
    group: StateGroup,
    cosine: (String, String) -> Double,
    projection: PersonalSemanticOccurrenceProjection,
    transitions: List<TransitionRecord>,
    catalog: BehaviorCatalog,
): RegionDescription {
    val inside = group.members.map { it.id }
    val outside = cut.groups.filter { it.alias != group.alias }.flatMap { other -> other.members.map { it.id } }
    val ungrouped = view.ungrouped.map { it.id }
    val aliasOf = cut.groups.flatMap { region -> region.members.map { member -> member.id to region.alias } }.toMap()
    val roles = transitions.map { record -> record to roleOf(record, aliasOf, group.alias) }
    val axes = CORE_AXES.map { axisId -> axisComparison(axisId, inside, outside, projection) }
    val features = featureAssociations(inside, outside, projection, catalog.reading, catalog.audit)
    val entryRoles = roles.filter { it.second == TransitionRole.ENTRY_OPPORTUNITY || it.second == TransitionRole.ENTERED }
    val entered = roles.filter { it.second == TransitionRole.ENTERED }
    val outgoing = roles.filter { it.second == TransitionRole.MAINTAINED || it.second == TransitionRole.EXITED }
    val maintained = roles.filter { it.second == TransitionRole.MAINTAINED }
    val exited = roles.filter { it.second == TransitionRole.EXITED }
    val entry = behaviorAssociations(
        catalog = catalog.identities,
        known = entryRoles.map { it.first },
        outcome = entered.map { it.first.id }.toSet(),
        coverage = entered.map { it.first },
        outcomeName = "entered",
        coverageName = "entry coverage",
        destinations = false,
    )
    val stay = behaviorAssociations(
        catalog = catalog.identities,
        known = outgoing.map { it.first },
        outcome = maintained.map { it.first.id }.toSet(),
        coverage = maintained.map { it.first },
        outcomeName = "remained",
        coverageName = "self-loop coverage",
        destinations = false,
    )
    val leave = behaviorAssociations(
        catalog = catalog.identities,
        known = outgoing.map { it.first },
        outcome = exited.map { it.first.id }.toSet(),
        coverage = exited.map { it.first },
        outcomeName = "exited",
        coverageName = "exit coverage",
        destinations = true,
        aliasOf = { record -> aliasOf[stateNodeId(record.endState)].orEmpty() },
    )
    return RegionDescription(
        alias = group.alias,
        nodeId = group.nodeId,
        memberCount = group.members.size,
        medoidLabel = unduplicatedRecordLabel(group.members.first { it.id == group.medoidId }.alias, group.medoidLabel),
        threshold = cut.minimumSimilarity,
        thresholdLabel = cut.minimumSimilarity?.let { formatRegionNumber(it) } ?: "above every merge",
        emotionMixture = emotionMixture(group),
        insideIds = inside.sorted(),
        outsideIds = outside.sorted(),
        ungroupedIds = ungrouped.sorted(),
        axes = axes,
        minimumWithinSimilarity = group.minimumSimilarity,
        meanWithinSimilarity = group.meanSimilarity,
        nearest = nearestRegions(group, cut.groups, cosine),
        characterization = features,
        characterizationConcise = conciseFeatures(features),
        entry = entry,
        entryConcise = conciseBehaviors(entry),
        maintenance = stay,
        maintenanceConcise = conciseBehaviors(stay),
        exit = leave,
        exitConcise = conciseBehaviors(leave),
        entryOpportunities = entryRoles.size,
        entered = entered.size,
        outgoing = outgoing.size,
        maintained = maintained.size,
        exited = exited.size,
        boundaryEndings = roles.count { it.second == TransitionRole.BOUNDARY_ENDING },
        ungroupedSources = roles.count { it.second == TransitionRole.UNGROUPED_SOURCE },
        ungroupedDestinations = roles.count { it.second == TransitionRole.UNGROUPED_DESTINATION },
        lineage = regionLineage(view, cutIndex, group),
        circularity = circularityFor(view),
    )
}

private enum class TransitionRole {
    ENTERED,
    ENTRY_OPPORTUNITY,
    MAINTAINED,
    EXITED,
    BOUNDARY_ENDING,
    UNGROUPED_SOURCE,
    UNGROUPED_DESTINATION,
    ELSEWHERE,
}

private fun roleOf(record: TransitionRecord, aliasOf: Map<String, String>, region: String): TransitionRole {
    val endAlias = aliasOf[stateNodeId(record.endState)]
    val startAlias = if (record.boundary || record.startState == null) null else aliasOf[stateNodeId(record.startState)]
    return when {
        startAlias != null && endAlias != null && startAlias == region && endAlias == region -> TransitionRole.MAINTAINED
        startAlias != null && endAlias != null && startAlias == region -> TransitionRole.EXITED
        startAlias != null && endAlias != null && endAlias == region -> TransitionRole.ENTERED
        startAlias != null && endAlias != null -> TransitionRole.ENTRY_OPPORTUNITY
        (record.boundary || record.startState == null) && endAlias == region -> TransitionRole.BOUNDARY_ENDING
        record.startState != null && startAlias == null && endAlias == region -> TransitionRole.UNGROUPED_SOURCE
        startAlias == region && endAlias == null -> TransitionRole.UNGROUPED_DESTINATION
        else -> TransitionRole.ELSEWHERE
    }
}

private fun axisComparison(
    axisId: String,
    inside: List<String>,
    outside: List<String>,
    projection: PersonalSemanticOccurrenceProjection,
): RegionAxisComparison {
    val left = numericSummary(inside, axisId, projection)
    val right = numericSummary(outside, axisId, projection)
    val paired = observedPairs(inside, axisId, projection, member = true) + observedPairs(outside, axisId, projection, member = false)
    val correlation = pointBiserial(paired)
    val name = AXIS_NAMES.getValue(axisId)
    val concise = "$name · inside ${medianPhrase(left)} · outside ${medianPhrase(right)} · membership correlation ${correlationLabel(correlation)}"
    val evidence = "$name · inside observed ${left.observed}, missing ${left.missing}, conflicting ${left.conflicting} · " +
        "outside observed ${right.observed}, missing ${right.missing}, conflicting ${right.conflicting} · " +
        "membership correlation ${correlation?.let { formatSignedHundredths(it) } ?: "unavailable"}"
    return RegionAxisComparison(axisId, name, left, right, correlation, concise, evidence)
}

private fun numericSummary(
    members: List<String>,
    axisId: String,
    projection: PersonalSemanticOccurrenceProjection,
): RegionNumericSummary {
    val values = mutableListOf<Double>()
    var missing = 0
    var conflicting = 0
    members.forEach { id ->
        when (val reading = axisReading(projection, id, axisId)) {
            null -> missing += 1
            AxisOccurrenceStatus.MISSING -> missing += 1
            AxisOccurrenceStatus.CONFLICTING -> conflicting += 1
            AxisOccurrenceStatus.OBSERVED -> {
                val raw = rawReading(projection, id, axisId)
                if (raw == null) missing += 1 else values += raw
            }
        }
    }
    val sorted = values.sorted()
    return RegionNumericSummary(
        observed = sorted.size,
        missing = missing,
        conflicting = conflicting,
        minimum = sorted.firstOrNull(),
        firstQuartile = if (sorted.isEmpty()) null else regionPercentile(sorted, 0.25),
        median = if (sorted.isEmpty()) null else regionPercentile(sorted, 0.5),
        mean = if (sorted.isEmpty()) null else sorted.average(),
        thirdQuartile = if (sorted.isEmpty()) null else regionPercentile(sorted, 0.75),
        maximum = sorted.lastOrNull(),
    )
}

private fun observedPairs(
    members: List<String>,
    axisId: String,
    projection: PersonalSemanticOccurrenceProjection,
    member: Boolean,
): List<Pair<Double, Boolean>> {
    return members.mapNotNull { id ->
        if (axisReading(projection, id, axisId) != AxisOccurrenceStatus.OBSERVED) return@mapNotNull null
        val raw = rawReading(projection, id, axisId) ?: return@mapNotNull null
        raw to member
    }
}

private fun axisReading(
    projection: PersonalSemanticOccurrenceProjection,
    stateId: String,
    axisId: String,
): AxisOccurrenceStatus? {
    val axis = projection.states.firstOrNull { stateNodeId(it.key.raw) == stateId }?.axes?.firstOrNull { it.axisId == axisId }
    return axis?.status
}

private fun rawReading(
    projection: PersonalSemanticOccurrenceProjection,
    stateId: String,
    axisId: String,
): Double? {
    return projection.states.firstOrNull { stateNodeId(it.key.raw) == stateId }
        ?.axes
        ?.firstOrNull { it.axisId == axisId && it.status == AxisOccurrenceStatus.OBSERVED }
        ?.raw
        ?.toDouble()
}

private fun featureAssociations(
    inside: List<String>,
    outside: List<String>,
    projection: PersonalSemanticOccurrenceProjection,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): List<RegionFeatureAssociation> {
    val insideRaw = inside.map { it.removePrefix("state:") }.toSet()
    val outsideRaw = outside.map { it.removePrefix("state:") }.toSet()
    val assigned = insideRaw + outsideRaw
    val links = projection.links.filterIsInstance<ConceptObservationLink.OnState>().filter { it.key.raw in assigned }
    return links.groupBy { conceptNodeId(it.concept) }.map { (identity, rows) ->
        val carriers = rows.map { it.key.raw }.toSet()
        val insideCount = carriers.count { it in insideRaw }
        val outsideCount = carriers.count { it in outsideRaw }
        val insideRate = rate(insideCount, insideRaw.size)
        val outsideRate = rate(outsideCount, outsideRaw.size)
        val difference = if (insideRate == null || outsideRate == null) null else insideRate - outsideRate
        val present = mutableListOf<Boolean>()
        val member = mutableListOf<Boolean>()
        insideRaw.forEach { raw ->
            present += raw in carriers
            member += true
        }
        outsideRaw.forEach { raw ->
            present += raw in carriers
            member += false
        }
        val phi = phiCorrelation(present, member)
        val label = reading.line(identity, audit)
        val type = rows.first().concept.type.auditLabel()
        val tentative = insideCount == 1
        val concise = featureLine(label, insideCount, insideRaw.size, outsideCount, outsideRaw.size, difference, phi, tentative)
        val evidence = "$concise · phi ${phi?.let { formatSignedHundredths(it) } ?: "unavailable"} · support ${insideCount + outsideCount}"
        RegionFeatureAssociation(
            identity = identity,
            label = label,
            type = type,
            insideCount = insideCount,
            insideTotal = insideRaw.size,
            outsideCount = outsideCount,
            outsideTotal = outsideRaw.size,
            insideRate = insideRate,
            outsideRate = outsideRate,
            rateDifference = difference,
            phi = phi,
            totalSupport = insideCount + outsideCount,
            tentative = tentative,
            concise = concise,
            evidence = evidence,
        )
    }.sortedWith(featureOrder()).let { ranked -> ranked.filter { it.totalSupport > 0 } }
}

private fun behaviorAssociations(
    catalog: List<CatalogIdentity>,
    known: List<TransitionRecord>,
    outcome: Set<String>,
    coverage: List<TransitionRecord>,
    outcomeName: String,
    coverageName: String,
    destinations: Boolean,
    aliasOf: (TransitionRecord) -> String = { "" },
): List<RegionBehaviorAssociation> {
    return catalog.mapNotNull { identity ->
        val decided = known.filter { it.presence[identity.id] != Presence.UNKNOWN }
        val present = decided.filter { it.presence[identity.id] == Presence.PRESENT }
        if (present.isEmpty()) return@mapNotNull null
        val absent = decided.filter { it.presence[identity.id] == Presence.ABSENT }
        val presentOutcome = present.count { it.id in outcome }
        val absentOutcome = absent.count { it.id in outcome }
        val presentRate = rate(presentOutcome, present.size)
        val absentRate = rate(absentOutcome, absent.size)
        val difference = if (presentRate == null || absentRate == null) null else presentRate - absentRate
        val phi = phiCorrelation(
            decided.map { it.presence[identity.id] == Presence.PRESENT },
            decided.map { it.id in outcome },
        )
        val coverageCount = coverage.count { it.presence[identity.id] == Presence.PRESENT }
        val destinationCounts = if (!destinations) {
            emptyList()
        } else {
            present.filter { it.id in outcome }
                .groupingBy { aliasOf(it) }
                .eachCount()
                .map { (alias, count) -> RegionDestinationCount(alias, count) }
                .sortedWith(compareByDescending<RegionDestinationCount> { it.count }.thenBy { it.alias })
        }
        val tentative = present.size == 1
        val concise = behaviorLine(
            label = identity.label,
            outcomeName = outcomeName,
            presentOutcome = presentOutcome,
            presentCount = present.size,
            absentOutcome = absentOutcome,
            absentCount = absent.size,
            difference = difference,
            coverageName = coverageName,
            coverageCount = coverageCount,
            coverageTotal = coverage.size,
            tentative = tentative,
        )
        val destinationText = if (destinationCounts.isEmpty()) {
            ""
        } else {
            " · destinations " + destinationCounts.joinToString(", ") { "${it.alias} ${it.count}" }
        }
        val evidence = "$concise · phi ${phi?.let { formatSignedHundredths(it) } ?: "unavailable"} · " +
            "known ${decided.size}, unknown excluded ${known.size - decided.size}$destinationText"
        RegionBehaviorAssociation(
            identity = identity.id,
            label = identity.label,
            type = identity.type,
            presentCount = present.size,
            presentOutcome = presentOutcome,
            absentCount = absent.size,
            absentOutcome = absentOutcome,
            rateDifference = difference,
            phi = phi,
            coverageCount = coverageCount,
            coverageTotal = coverage.size,
            tentative = tentative,
            destinations = destinationCounts,
            concise = concise,
            evidence = evidence,
        )
    }.sortedWith(behaviorOrder())
}

private fun nearestRegions(
    group: StateGroup,
    groups: List<StateGroup>,
    cosine: (String, String) -> Double,
): List<RegionProximity> {
    return groups.filter { it.alias != group.alias }.map { other ->
        var distance = 0.0
        var similarity = 0.0
        var minimum = Double.POSITIVE_INFINITY
        var pairs = 0
        group.members.forEach { left ->
            other.members.forEach { right ->
                val value = cosine(left.id, right.id)
                distance += 1.0 - value
                similarity += value
                if (value < minimum) minimum = value
                pairs += 1
            }
        }
        val meanDistance = if (pairs == 0) 0.0 else distance / pairs
        val meanSimilarity = if (pairs == 0) 0.0 else similarity / pairs
        val minSimilarity = if (pairs == 0) 0.0 else minimum
        val layout = layoutDistance(group, other)
        RegionProximity(
            alias = other.alias,
            memberCount = other.members.size,
            sourceDistance = meanDistance,
            meanSimilarity = meanSimilarity,
            minimumSimilarity = minSimilarity,
            layoutDistance = layout,
            concise = "${other.alias} · ${other.members.size} states · source distance ${formatRegionNumber(meanDistance)} · " +
                "mean similarity ${formatRegionNumber(meanSimilarity)} · minimum similarity ${formatRegionNumber(minSimilarity)} · " +
                "layout distance ${layout?.let { formatRegionNumber(it) } ?: "absent"}",
        )
    }.sortedWith(compareBy<RegionProximity> { it.sourceDistance }.thenBy { it.alias }).take(3)
}

private fun layoutDistance(left: StateGroup, right: StateGroup): Double? {
    val leftX = left.x ?: return null
    val rightX = right.x ?: return null
    val dx = leftX - rightX
    val leftY = left.y
    val rightY = right.y
    return if (leftY == null || rightY == null) abs(dx) else sqrt(dx * dx + (leftY - rightY) * (leftY - rightY))
}

private fun conciseFeatures(features: List<RegionFeatureAssociation>): List<RegionFeatureAssociation> {
    val repeated = features.filter { it.insideCount >= 2 }
    val singles = features.filter { it.insideCount == 1 }
    return (repeated + singles).take(3)
}

private fun conciseBehaviors(behaviors: List<RegionBehaviorAssociation>): List<RegionBehaviorAssociation> {
    val repeated = behaviors.filter { it.presentCount >= 2 }
    val singles = behaviors.filter { it.presentCount == 1 }
    return (repeated + singles).take(3)
}

private fun featureOrder(): Comparator<RegionFeatureAssociation> {
    return compareBy<RegionFeatureAssociation> { featureBucket(it.insideCount) }
        .thenByDescending { it.phi?.let { value -> abs(value) } ?: Double.NEGATIVE_INFINITY }
        .thenByDescending { it.insideCount }
        .thenBy { it.identity }
}

private fun behaviorOrder(): Comparator<RegionBehaviorAssociation> {
    return compareBy<RegionBehaviorAssociation> { if (it.presentCount >= 2) 0 else 1 }
        .thenByDescending { it.rateDifference?.let { value -> abs(value) } ?: Double.NEGATIVE_INFINITY }
        .thenByDescending { it.phi?.let { value -> abs(value) } ?: Double.NEGATIVE_INFINITY }
        .thenByDescending { it.presentCount }
        .thenBy { it.identity }
}

private fun featureBucket(insideCount: Int): Int = when {
    insideCount >= 2 -> 0
    insideCount == 1 -> 1
    else -> 2
}

private fun featureLine(
    label: String,
    insideCount: Int,
    insideTotal: Int,
    outsideCount: Int,
    outsideTotal: Int,
    difference: Double?,
    phi: Double?,
    tentative: Boolean,
): String {
    val gap = difference?.let { formatRatePoints(it) } ?: "difference unavailable"
    val relation = phi?.let { formatSignedHundredths(it) } ?: "unavailable"
    val caution = if (tentative) " · $REGION_TENTATIVE" else ""
    return "$label · inside $insideCount/$insideTotal · outside $outsideCount/$outsideTotal · difference $gap · phi $relation$caution"
}

private fun behaviorLine(
    label: String,
    outcomeName: String,
    presentOutcome: Int,
    presentCount: Int,
    absentOutcome: Int,
    absentCount: Int,
    difference: Double?,
    coverageName: String,
    coverageCount: Int,
    coverageTotal: Int,
    tentative: Boolean,
): String {
    val absent = if (absentCount == 0) "absent comparison unavailable" else "$absentOutcome/$absentCount when absent"
    val gap = difference?.let { "difference ${formatRatePoints(it)}" } ?: "difference unavailable"
    val caution = if (tentative) " · $REGION_TENTATIVE" else ""
    return "$label · $outcomeName $presentOutcome/$presentCount when present vs $absent · $gap · " +
        "$coverageName $coverageCount/$coverageTotal$caution"
}

private fun emotionMixture(group: StateGroup): String {
    if (group.emotions.isEmpty()) return "No emotion was recorded."
    return group.emotions.joinToString(" · ") { emotion ->
        "${emotion.name} ${emotion.count}/${group.members.size}"
    }
}

private fun medianPhrase(summary: RegionNumericSummary): String {
    val median = summary.median ?: return "median absent"
    val low = summary.firstQuartile ?: return "median ${formatRegionNumber(median)}"
    val high = summary.thirdQuartile ?: return "median ${formatRegionNumber(median)}"
    return "median ${formatRegionNumber(median)} (IQR ${formatRegionNumber(low)}–${formatRegionNumber(high)})"
}

private fun correlationLabel(correlation: Double?): String = correlation?.let { formatSignedHundredths(it) } ?: "unavailable"

internal fun formatRegionNumber(value: Double): String {
    val rounded = round(value * 1000.0) / 1000.0
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.removeSuffix(".0") else text
}

internal fun formatSignedHundredths(value: Double): String {
    val scaled = round(value * 100.0).toLong()
    val sign = if (scaled < 0) "-" else "+"
    val absolute = abs(scaled)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}

private fun rate(count: Int, total: Int): Double? = if (total == 0) null else count.toDouble() / total

private fun regionPercentile(sorted: List<Double>, fraction: Double): Double {
    if (sorted.size == 1) return sorted.first()
    val index = fraction * (sorted.size - 1)
    val lower = index.toInt()
    val upper = minOf(lower + 1, sorted.lastIndex)
    val weight = index - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

private fun joinPhrases(parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    2 -> "${parts[0]} and ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

private data class CatalogIdentity(
    val id: String,
    val label: String,
    val type: String,
    val conceptType: SemanticConceptType,
)

private data class BehaviorCatalog(
    val identities: List<CatalogIdentity>,
    val reading: DistributionalReadingIndex,
    val audit: EmbeddingAuditIndex,
)

private enum class Presence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

private data class TransitionRecord(
    val id: String,
    val startState: String?,
    val endState: String,
    val boundary: Boolean,
    val presence: Map<String, Presence>,
)

private fun behaviorCatalog(
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection,
    reading: DistributionalReadingIndex,
    audit: EmbeddingAuditIndex,
): BehaviorCatalog {
    val identities = projection.links.filterIsInstance<ConceptObservationLink.OnTransition>()
        .map { it.concept }
        .filter { categoryOf(it.type) != null }
        .distinct()
        .map { concept ->
            val id = conceptNodeId(concept)
            CatalogIdentity(id, reading.line(id, audit), concept.type.auditLabel(), concept.type)
        }
        .sortedBy { it.id }
    return BehaviorCatalog(identities, reading, audit)
}

private fun transitionRecords(
    graph: AlternatingTemporalGraph,
    projection: PersonalSemanticOccurrenceProjection,
    catalog: BehaviorCatalog,
): List<TransitionRecord> {
    val links = projection.links.filterIsInstance<ConceptObservationLink.OnTransition>().groupBy { transitionNodeId(it.key.raw) }
    return graph.transitions.map { transition ->
        val id = transitionNodeId(transition.id)
        val present = links[id].orEmpty().map { conceptNodeId(it.concept) }.toSet()
        val presence = catalog.identities.associate { identity ->
            identity.id to presenceOf(transition.interval, identity, present)
        }
        TransitionRecord(
            id = id,
            startState = transition.startStateId,
            endState = transition.endStateId,
            boundary = transition.interval.startBasis == CheckInContextStartBasis.WAKE_TIME ||
                transition.interval.startBasis == CheckInContextStartBasis.DAY_START ||
                transition.startStateId == null,
            presence = presence,
        )
    }
}

private fun presenceOf(
    interval: ContextIntervalObservationV2,
    identity: CatalogIdentity,
    present: Set<String>,
): Presence {
    val kind = categoryKind(interval, identity.conceptType)
    return when (kind) {
        CategoryObservationKindV2.ENTRIES -> if (identity.id in present) Presence.PRESENT else Presence.ABSENT
        CategoryObservationKindV2.NONE -> Presence.ABSENT
        else -> Presence.UNKNOWN
    }
}

private fun categoryOf(type: SemanticConceptType): String? = when (type) {
    SemanticConceptType.ACTIVITY -> "activity"
    SemanticConceptType.WORKOUT -> "workout"
    SemanticConceptType.FOOD -> "diet"
    SemanticConceptType.MEAL_SIZE -> "diet"
    SemanticConceptType.CONTEXTUAL_USAGE -> "contextual usage"
    else -> null
}

private fun categoryKind(interval: ContextIntervalObservationV2, type: SemanticConceptType): CategoryObservationKindV2 {
    return when (type) {
        SemanticConceptType.ACTIVITY -> interval.activity.kind
        SemanticConceptType.WORKOUT -> interval.workout.kind
        SemanticConceptType.FOOD, SemanticConceptType.MEAL_SIZE -> interval.diet.kind
        SemanticConceptType.CONTEXTUAL_USAGE -> interval.usage.kind
        else -> CategoryObservationKindV2.ABSENT
    }
}

private fun cosineFor(
    view: StateGroupAtlasView,
    embedding: JointEmbeddingDataset,
    distributional: DistributionalContextDataset,
): (String, String) -> Double {
    if (view.geometry == PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT) {
        val layer = distributional.layer(view.context)
        return { left, right -> layer.similarity(left, right).coerceIn(-1.0, 1.0) }
    }
    val layer = embedding.layer(view.context)
    return { left, right -> layer.similarity(left, right).coerceIn(-1.0, 1.0) }
}

private fun circularityFor(view: StateGroupAtlasView): String = when {
    view.context == RECIPROCAL_LAYER -> REGION_RECIPROCAL_CIRCULARITY
    view.geometry == PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT -> REGION_DISTRIBUTIONAL_CIRCULARITY
    else -> REGION_DIRECT_CIRCULARITY
}

private fun regionJudgments(): List<String> = listOf(
    "Retrospective full history. The selected cut is an exploratory hard partition.",
    REGION_UNIVERSAL_DISCLOSURE,
    "No correlation is called significant. This pass does not calculate a p-value or a confidence claim.",
    "The description does not change state similarity, hierarchy, membership, layout, threshold selection, node size, or node position.",
    REGION_SOURCE_DISTANCE_NOTE,
    "A state without a group stays out of inside and outside. A transition without two grouped ends stays out of the association denominators.",
    "Reviewed-empty is absence. Unreviewed, missing, and conflicting category states stay unknown and are not treated as absence.",
    "This pass does not add a graph radius, choose a threshold, fit a predictor, or claim a behavior effect.",
)
