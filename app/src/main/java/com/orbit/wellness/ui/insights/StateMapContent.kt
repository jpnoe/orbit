package com.orbit.wellness.ui.insights

import com.orbit.wellness.domain.checkin.LatentDiagnosticCode
import com.orbit.wellness.domain.checkin.LatentFitStatus
import com.orbit.wellness.domain.checkin.LatentProjectionScope
import com.orbit.wellness.domain.checkin.StateMapAnalysis
import com.orbit.wellness.domain.checkin.StateMapEdge
import com.orbit.wellness.domain.checkin.StateMapPoint
import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import com.orbit.wellness.util.DateUtils
import java.time.ZoneId
import kotlin.math.roundToInt

object StateMapCopy {
    const val HEADING = "State map (experimental)"
    const val SUPPORT = "A retrospective map of how your logged states differ using your current history. Positions and axes may change as more observations are added."
    const val DISCLAIMER = "This map describes patterns in recorded context. It does not identify causes, diagnoses, or fixed identities."
    const val NO_STATES = "Create a check-in to begin building your state map."
    const val INSUFFICIENT_VARIATION = "Orbit needs more varied check-ins before it can draw a state map."
    const val CONSTANT_HISTORY = "Your recorded states do not yet contain enough variation for a map."
    const val COULD_NOT_BUILD = "Orbit could not build the state map from the current history."
    const val ACCUMULATING = "Context still accumulating"
    const val NOT_RECORDED = "Not recorded"
}

enum class StateMapMode {
    TWO_COMPONENT,
    ONE_COMPONENT,
    UNAVAILABLE,
}

data class StateMapContent(
    val mode: StateMapMode,
    val scope: LatentProjectionScope,
    val componentCount: Int,
    /** Percentage of active variance for each retained component, in component order. */
    val explainedVariancePercent: List<Double>,
    val points: List<StateMapPoint>,
    val edges: List<StateMapEdge>,
    val latestStateKey: StateOccurrenceKey?,
    val unavailableReason: String?,
    val heading: String = StateMapCopy.HEADING,
    val support: String = StateMapCopy.SUPPORT,
    val disclaimer: String = StateMapCopy.DISCLAIMER,
)

data class StateMapDetail(
    val whenLabel: String,
    val emotion: String?,
    val lines: List<Pair<String, String>>,
    val accumulating: Boolean,
)

fun StateMapAnalysis.toStateMapContent(): StateMapContent {
    val retained = latent.components.size
    val mode = when {
        latent.status != LatentFitStatus.FITTED || points.isEmpty() -> StateMapMode.UNAVAILABLE
        retained >= 2 -> StateMapMode.TWO_COMPONENT
        retained == 1 -> StateMapMode.ONE_COMPONENT
        else -> StateMapMode.UNAVAILABLE
    }
    val shown = if (mode == StateMapMode.UNAVAILABLE) emptyList() else points
    val shownKeys = shown.map { it.key }.toSet()
    return StateMapContent(
        mode = mode,
        scope = latent.scope,
        componentCount = if (mode == StateMapMode.UNAVAILABLE) 0 else retained,
        explainedVariancePercent = if (mode == StateMapMode.UNAVAILABLE) {
            emptyList()
        } else {
            latent.components.map { it.explainedVarianceRatio * 100.0 }
        },
        points = shown,
        edges = if (mode == StateMapMode.UNAVAILABLE) emptyList() else edges.filter { edge ->
            edge.fromKey in shownKeys && edge.toKey in shownKeys
        },
        latestStateKey = shown.maxWithOrNull(compareBy({ it.recordedAt }, { it.key.raw }))?.key,
        unavailableReason = if (mode == StateMapMode.UNAVAILABLE) stateMapUnavailableReason(latent.diagnostics.firstOrNull()?.code) else null,
    )
}

fun stateMapUnavailableReason(code: LatentDiagnosticCode?): String {
    return when (code) {
        LatentDiagnosticCode.NO_STATES -> StateMapCopy.NO_STATES
        LatentDiagnosticCode.INSUFFICIENT_STATES -> StateMapCopy.INSUFFICIENT_VARIATION
        LatentDiagnosticCode.NO_ACTIVE_COLUMNS, LatentDiagnosticCode.ZERO_RANK -> StateMapCopy.CONSTANT_HISTORY
        else -> StateMapCopy.COULD_NOT_BUILD
    }
}

fun componentAxisLabel(index: Int, explainedVariancePercent: Double): String {
    return "Component ${index + 1} · ${explainedVariancePercent.roundToInt()}%"
}

fun stateMapDetail(point: StateMapPoint, zone: ZoneId): StateMapDetail {
    return StateMapDetail(
        whenLabel = DateUtils.datedTimeLabel(point.recordedAt, zone),
        emotion = point.emotion,
        lines = listOf(
            "Mood" to axisLabel(point.mood),
            "Energy" to axisLabel(point.energy),
            "Confidence" to axisLabel(point.confidence),
            "Anxiety" to axisLabel(point.anxiety),
        ),
        accumulating = !point.outgoingContextAvailable,
    )
}

fun stateMapDescription(content: StateMapContent, selected: StateMapDetail?): String {
    val plotted = "${content.points.size} plotted states, ${content.componentCount} components"
    val selection = selected?.let { detail ->
        val emotion = detail.emotion?.let { " $it" }.orEmpty()
        ". Selected ${detail.whenLabel}$emotion"
    }.orEmpty()
    return "State map, $plotted$selection"
}

private fun axisLabel(raw: Int?): String = raw?.toString() ?: StateMapCopy.NOT_RECORDED
