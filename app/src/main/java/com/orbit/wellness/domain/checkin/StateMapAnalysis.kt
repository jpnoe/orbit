package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.WearableObservation

/**
 * One pure pass from a check-in dataset and the wearable list captured with it.
 *
 * The functions already committed do the graph, evidence, encoding, preparation,
 * multiscale context, and retrospective PCA. This only orders those calls.
 * Nothing here is written to Room, and the retrospective coordinates are not
 * attached to [CausalMultiscaleInput].
 *
 * The Jacobi fit grows with the number of states. It is for the current
 * prototype history. States are not dropped or sampled.
 */
fun projectStateMap(
    dataset: CheckInAnalysisDatasetV2,
    wearables: List<WearableObservation> = emptyList(),
): StateMapAnalysis {
    val graph = projectAlternatingTemporalGraph(dataset)
    val evidence = projectContextualEvidence(graph, wearables)
    val encoded = encodeFeatures(evidence)
    val local = projectLocalContext(evidence, encoded)
    val multiscale = projectMultiscale(evidence, local)
    val latent = fitRetrospectiveLatentProjection(multiscale, local.model)
    val projected = latent.states.filter { it.coordinates.isNotEmpty() }.associateBy { it.key }
    val statesByKey = dataset.states.associateBy { StateOccurrenceKey(it.checkInId) }
    val multiscaleByKey = multiscale.states.associateBy { it.key }
    val points = latent.states.mapNotNull { coordinate ->
        if (coordinate.coordinates.isEmpty()) return@mapNotNull null
        val state = statesByKey[coordinate.key] ?: return@mapNotNull null
        val outgoing = multiscaleByKey[coordinate.key]?.local?.outgoing?.available == true
        StateMapPoint(
            key = coordinate.key,
            recordedAt = coordinate.recordedAt,
            x = coordinate.coordinates[0],
            y = coordinate.coordinates.getOrNull(1),
            emotion = state.emotion.trim().takeIf { it.isNotEmpty() },
            mood = state.core.mood?.raw,
            energy = state.core.energy?.raw,
            confidence = state.core.confidence?.raw,
            anxiety = state.core.anxiety?.raw,
            observedFraction = coordinate.observedFraction,
            outgoingContextAvailable = outgoing,
        )
    }
    val omitted = mutableListOf<OmittedStateEdge>()
    val edges = mutableListOf<StateMapEdge>()
    graph.transitions.forEach { transition ->
        val basis = transition.interval.startBasis
        val boundary = basis == CheckInContextStartBasis.WAKE_TIME ||
            basis == CheckInContextStartBasis.DAY_START
        if (boundary) {
            omitted += OmittedStateEdge(transition.id, OmittedStateEdgeReason.BOUNDARY)
            return@forEach
        }
        val startId = transition.startStateId
        if (startId == null) {
            omitted += OmittedStateEdge(transition.id, OmittedStateEdgeReason.UNRESOLVED)
            return@forEach
        }
        val startKey = StateOccurrenceKey(startId)
        val endKey = StateOccurrenceKey(transition.endStateId)
        val start = projected[startKey]
        val end = projected[endKey]
        if (start == null || end == null) {
            omitted += OmittedStateEdge(transition.id, OmittedStateEdgeReason.UNPROJECTED)
            return@forEach
        }
        if (start.recordedAt >= end.recordedAt) {
            omitted += OmittedStateEdge(transition.id, OmittedStateEdgeReason.INVALID_CHRONOLOGY)
            return@forEach
        }
        edges += StateMapEdge(fromKey = startKey, toKey = endKey)
    }
    return StateMapAnalysis(
        latent = latent,
        points = points,
        edges = edges,
        omittedEdges = omitted,
        localDiagnostics = local.diagnostics,
        multiscaleDiagnostics = multiscale.diagnostics,
        analysisIssues = dataset.issues,
    )
}

data class StateMapAnalysis(
    val latent: RetrospectiveLatentProjection,
    val points: List<StateMapPoint>,
    val edges: List<StateMapEdge>,
    val omittedEdges: List<OmittedStateEdge>,
    val localDiagnostics: List<ContextDiagnostic>,
    val multiscaleDiagnostics: List<MultiscaleDiagnostic>,
    val analysisIssues: List<AnalysisIssue>,
) {
    init {
        require(!latent.suitableForCausalPrediction && !latent.suitableForLiveRecommendation) {
            "A state map cannot carry a causal or recommendation projection"
        }
        require(points.all { it.x.isFinite() && (it.y?.isFinite() != false) && it.observedFraction.isFinite() }) {
            "A map point must be finite"
        }
    }
}

data class StateMapPoint(
    /** Occurrence identity. It is not drawn as text. */
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    val x: Double,
    /** Absent when the fit retained only one component. */
    val y: Double?,
    val emotion: String?,
    val mood: Int?,
    val energy: Int?,
    val confidence: Int?,
    val anxiety: Int?,
    val observedFraction: Double,
    val outgoingContextAvailable: Boolean,
)

data class StateMapEdge(
    val fromKey: StateOccurrenceKey,
    val toKey: StateOccurrenceKey,
)

data class OmittedStateEdge(
    val transitionId: String,
    val reason: OmittedStateEdgeReason,
)

enum class OmittedStateEdgeReason {
    BOUNDARY,
    UNRESOLVED,
    UNPROJECTED,
    INVALID_CHRONOLOGY,
}

/** Core-axis ids in the order the detail card shows them. */
val STATE_MAP_AXIS_IDS: List<String> = listOf(
    DefaultCheckInAxes.MOOD,
    DefaultCheckInAxes.ENERGY,
    DefaultCheckInAxes.CONFIDENCE,
    DefaultCheckInAxes.ANXIETY,
)
