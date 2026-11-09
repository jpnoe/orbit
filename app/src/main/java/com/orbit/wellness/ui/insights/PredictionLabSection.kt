package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.domain.checkin.AxisPredictionRecord
import com.orbit.wellness.domain.checkin.BaselineKind
import com.orbit.wellness.domain.checkin.EvidenceOrigin
import com.orbit.wellness.domain.checkin.ForecastEvidence
import com.orbit.wellness.domain.checkin.ObservableForecast
import com.orbit.wellness.domain.checkin.OverflowDirection
import com.orbit.wellness.domain.checkin.RecentMeanChoice
import com.orbit.wellness.domain.checkin.SandboxField
import com.orbit.wellness.domain.checkin.SandboxSession
import com.orbit.wellness.domain.checkin.StateTrajectoryContext
import com.orbit.wellness.domain.checkin.TrajectoryExpert
import com.orbit.wellness.domain.checkin.TrajectoryExpertGap
import com.orbit.wellness.domain.checkin.TrajectoryHorizonPreview
import com.orbit.wellness.domain.checkin.TrajectoryScaleId
import com.orbit.wellness.domain.checkin.WalkForwardAttempt
import com.orbit.wellness.domain.checkin.WalkForwardMetrics
import com.orbit.wellness.domain.checkin.runSandbox
import com.orbit.wellness.ui.util.InlineLoading
import kotlin.math.round

internal object PredictionLabCopy {
    const val PANE = "Prediction lab"
    const val REPLAY = "Recorded replay"
    const val SANDBOX = "Sandbox"
    const val NOTICE =
        "These baselines score the four recorded axes. They are not a complete state, and they do not update a model."
    const val REPLAY_NOTICE = "Included in the aggregate. This does not change the logged history."
    const val SANDBOX_NOTICE =
        "In memory only. Overrides stay separate from the logged evidence. This is not included in the aggregate and is not written to the log."
    const val NO_ATTEMPTS = "No eligible transition was recorded."
    const val NO_ANCHORS = "No state occurrence is available."
    const val METRICS = "Aggregate"
    const val SUPPORT =
        "Common support compares Persistence, Recent mean, and Step momentum only where all three predicted the same observed target."
    const val RESIDUAL = "Residual is actual minus the bounded forecast. Positive means the axis ended higher than that forecast."
    const val EXPERTS = "Trajectory experts are evidence channels. Recorded replay does not project them onto a later check-in, and they stay out of the aggregate."
    const val HORIZON = "Forecast horizon in hours"
    const val LOCAL_ERROR = "This error stays in the sandbox."
    const val LOGGED = "Logged"
    const val USE_OBSERVED = "Use observed"
    const val CLEAR = "Clear"
    const val SYNTHETIC_TARGET = "Optional synthetic target"
}

private enum class PredictionLabMode {
    REPLAY,
    SANDBOX,
}

@Composable
fun PredictionResearchPane(walkForwardViewModel: WalkForwardViewModel) {
    val uiState by walkForwardViewModel.uiState.collectAsState()
    when (val state = uiState) {
        WalkForwardUiState.Loading -> InlineLoading()
        is WalkForwardUiState.Ready -> PredictionLab(
            projection = state.projection,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PredictionLab(
    projection: com.orbit.wellness.domain.checkin.WalkForwardProjection,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(PredictionLabMode.REPLAY) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(PredictionLabCopy.NOTICE, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == PredictionLabMode.REPLAY,
                onClick = { mode = PredictionLabMode.REPLAY },
                label = { Text(PredictionLabCopy.REPLAY) },
            )
            FilterChip(
                selected = mode == PredictionLabMode.SANDBOX,
                onClick = { mode = PredictionLabMode.SANDBOX },
                label = { Text(PredictionLabCopy.SANDBOX) },
            )
        }
        when (mode) {
            PredictionLabMode.REPLAY -> RecordedReplayPane(projection)
            PredictionLabMode.SANDBOX -> PredictionSandboxPane(projection.anchors, projection.experts.experts)
        }
    }
}

@Composable
private fun RecordedReplayPane(projection: com.orbit.wellness.domain.checkin.WalkForwardProjection) {
    val evaluation = projection.evaluation
    var showMetrics by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(PredictionLabCopy.REPLAY_NOTICE)
        if (evaluation.attempts.isEmpty()) {
            Text(PredictionLabCopy.NO_ATTEMPTS)
        } else {
            var index by remember { mutableStateOf(0) }
            val attempt = evaluation.attempts[index.coerceIn(0, evaluation.attempts.lastIndex)]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { index = (index - 1).coerceIn(0, evaluation.attempts.lastIndex) }) {
                    Text("Previous")
                }
                Text("Attempt ${index.coerceIn(0, evaluation.attempts.lastIndex) + 1} of ${evaluation.attempts.size}")
                TextButton(onClick = { index = (index + 1).coerceIn(0, evaluation.attempts.lastIndex) }) {
                    Text("Next")
                }
            }
            Text("Transition ${attempt.transitionId}. Anchor ${attempt.anchorKey.raw}.")
            AttemptForecasts(
                attempt = attempt,
                experts = projection.experts.experts.filter { it.stateKey == attempt.anchorKey },
            )
        }
        TextButton(onClick = { showMetrics = !showMetrics }) {
            Text(PredictionLabCopy.METRICS)
        }
        if (showMetrics) MetricsBlock(evaluation.metrics)
    }
}

@Composable
private fun PredictionSandboxPane(
    anchors: List<StateTrajectoryContext>,
    experts: List<TrajectoryExpert>,
) {
    var session by remember { mutableStateOf(SandboxSession.fresh()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(PredictionLabCopy.SANDBOX_NOTICE)
        if (anchors.isEmpty()) {
            Text(PredictionLabCopy.NO_ANCHORS)
        } else {
            SandboxEditors(anchors, experts, session) { session = it }
        }
    }
}

@Composable
private fun SandboxEditors(
    anchors: List<StateTrajectoryContext>,
    experts: List<TrajectoryExpert>,
    session: SandboxSession,
    onSession: (SandboxSession) -> Unit,
) {
    val index = session.anchorIndex.coerceIn(0, anchors.lastIndex)
    val anchor = anchors[index]
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                onSession(session.withAnchor((index - 1).coerceIn(0, anchors.lastIndex)))
            }) { Text("Previous") }
            Text("State ${index + 1} of ${anchors.size}")
            TextButton(onClick = {
                onSession(session.withAnchor((index + 1).coerceIn(0, anchors.lastIndex)))
            }) { Text("Next") }
        }
        Text("Anchor ${anchor.stateKey.raw}. A new anchor starts from its logged evidence.")
        OutlinedTextField(
            value = session.horizonText,
            onValueChange = { onSession(session.withHorizon(it)) },
            label = { Text(PredictionLabCopy.HORIZON) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (session.horizonText.isNotBlank() && session.horizonHours() == null) {
            Text("That horizon is not used.")
        }
        val anchorExperts = experts.filter { it.stateKey == anchor.stateKey }
        val forecast = runSandbox(
            anchor,
            session.toScenario(),
            anchorExperts,
            session.horizonHours(),
        )
        anchor.axes.forEach { axis ->
            val label = axisLabel(axis.axisId)
            Text(label, style = MaterialTheme.typography.titleMedium)
            SandboxEditor(
                label = "Starting $label",
                logged = axis.currentRaw?.toString() ?: "Unobserved",
                text = session.startingText[axis.axisId].orEmpty(),
                cleared = session.change(axis.axisId, SandboxField.STARTING) == com.orbit.wellness.domain.checkin.SandboxChange.Clear,
                onText = { onSession(session.withText(axis.axisId, SandboxField.STARTING, it)) },
                onClear = { onSession(session.clear(axis.axisId, SandboxField.STARTING)) },
                onUseObserved = { onSession(session.useObserved(axis.axisId, SandboxField.STARTING)) },
            )
            SandboxEditor(
                label = "Latest incoming delta",
                logged = axis.incomingDelta?.toString() ?: "Unobserved",
                text = session.deltaText[axis.axisId].orEmpty(),
                cleared = session.change(axis.axisId, SandboxField.INCOMING_DELTA) == com.orbit.wellness.domain.checkin.SandboxChange.Clear,
                onText = { onSession(session.withText(axis.axisId, SandboxField.INCOMING_DELTA, it)) },
                onClear = { onSession(session.clear(axis.axisId, SandboxField.INCOMING_DELTA)) },
                onUseObserved = { onSession(session.useObserved(axis.axisId, SandboxField.INCOMING_DELTA)) },
            )
            SandboxEditor(
                label = "Day mean",
                logged = axis.trailingDay.summary?.mean?.let { formatBaselineValue(it) } ?: "Unobserved",
                text = session.dayText[axis.axisId].orEmpty(),
                cleared = session.change(axis.axisId, SandboxField.DAY_MEAN) == com.orbit.wellness.domain.checkin.SandboxChange.Clear,
                onText = { onSession(session.withText(axis.axisId, SandboxField.DAY_MEAN, it)) },
                onClear = { onSession(session.clear(axis.axisId, SandboxField.DAY_MEAN)) },
                onUseObserved = { onSession(session.useObserved(axis.axisId, SandboxField.DAY_MEAN)) },
            )
            SandboxEditor(
                label = "Week mean",
                logged = axis.trailingWeek.summary?.mean?.let { formatBaselineValue(it) } ?: "Unobserved",
                text = session.weekText[axis.axisId].orEmpty(),
                cleared = session.change(axis.axisId, SandboxField.WEEK_MEAN) == com.orbit.wellness.domain.checkin.SandboxChange.Clear,
                onText = { onSession(session.withText(axis.axisId, SandboxField.WEEK_MEAN, it)) },
                onClear = { onSession(session.clear(axis.axisId, SandboxField.WEEK_MEAN)) },
                onUseObserved = { onSession(session.useObserved(axis.axisId, SandboxField.WEEK_MEAN)) },
            )
            Text(PredictionLabCopy.SYNTHETIC_TARGET)
            OutlinedTextField(
                value = session.targetText[axis.axisId].orEmpty(),
                onValueChange = { onSession(session.withTarget(axis.axisId, it)) },
                label = { Text("$label target") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(PredictionLabCopy.RESIDUAL)
        forecast.predictions.groupBy { it.axisId }.forEach { (axisId, records) ->
            Text(axisLabel(axisId), style = MaterialTheme.typography.titleSmall)
            records.forEach { record -> ForecastRow(record) }
            forecast.horizonPreviews.filter { it.expert.axisId == axisId }.forEach { preview ->
                ExpertLine(preview)
            }
        }
        if (forecast.syntheticTarget != null) {
            Text(PredictionLabCopy.LOCAL_ERROR)
            forecast.predictions.filter { it.residual != null }.forEach { record ->
                Text(
                    "${baselineLabel(record.baseline)} ${axisLabel(record.axisId)} residual ${formatBaselineValue(checkNotNull(record.residual))}",
                )
            }
        }
    }
}

@Composable
private fun SandboxEditor(
    label: String,
    logged: String,
    text: String,
    cleared: Boolean,
    onText: (String) -> Unit,
    onClear: () -> Unit,
    onUseObserved: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label. ${PredictionLabCopy.LOGGED} $logged.")
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text("Override") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (cleared) Text("Cleared")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClear) { Text(PredictionLabCopy.CLEAR) }
            TextButton(onClick = onUseObserved) { Text(PredictionLabCopy.USE_OBSERVED) }
        }
    }
}

@Composable
private fun AttemptForecasts(
    attempt: WalkForwardAttempt,
    experts: List<TrajectoryExpert>,
) {
    Text(PredictionLabCopy.RESIDUAL)
    Text(PredictionLabCopy.EXPERTS)
    attempt.axisTarget.readings.forEach { reading ->
        Text(
            "${axisLabel(reading.axisId)} actual ${reading.raw?.toString() ?: "Missing"}",
            style = MaterialTheme.typography.titleSmall,
        )
        attempt.predictions.filter { it.axisId == reading.axisId }.forEach { record ->
            ForecastRow(record)
        }
        experts.filter { it.axisId == reading.axisId }.forEach { expert ->
            Text(expertLabel(expert))
        }
    }
}

@Composable
private fun ForecastRow(record: AxisPredictionRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(forecastSummary(record))
        Text(forecastEvidenceLabel(record))
        record.residual?.let { error ->
            Text("Residual ${formatBaselineValue(error)}")
        }
        val rawResidual = record.rawResidual
        if (rawResidual != null && rawResidual != record.residual) {
            Text("Raw residual ${formatBaselineValue(rawResidual)}")
        }
    }
}

@Composable
private fun ExpertLine(preview: TrajectoryHorizonPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(expertLabel(preview.expert))
        preview.forecast?.let { forecast ->
            Text(horizonForecastLabel(preview.expert.scale, forecast))
        }
    }
}

@Composable
private fun MetricsBlock(metrics: WalkForwardMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.scores.forEach { score ->
            Text(
                "${baselineLabel(score.baseline)} ${axisLabel(score.axisId)}: " +
                    "eligible ${score.eligibleCount}, " +
                    "targets observed ${score.targetObservedCount}, " +
                    "predictions available ${score.predictionAvailableCount}, " +
                    "paired ${score.predictionCount}, " +
                    "bias ${score.bias?.let { formatBaselineValue(it) } ?: "none"}, " +
                    "MAE ${score.mae?.let { formatBaselineValue(it) } ?: "none"}, " +
                    "RMSE ${score.rmse?.let { formatBaselineValue(it) } ?: "none"}",
            )
            val distribution = score.residualDistribution
            if (distribution != null) {
                Text(
                    "Residuals ${distribution.count}: mean ${formatBaselineValue(distribution.mean)}, " +
                        "deviation ${formatBaselineValue(distribution.deviation)}, " +
                        "min ${formatBaselineValue(distribution.minimum)}, " +
                        "max ${formatBaselineValue(distribution.maximum)}",
                )
            }
        }
        Text(PredictionLabCopy.SUPPORT)
        metrics.commonSupport.forEach { comparison ->
            val baselines = comparison.baselines.joinToString("; ") { baseline ->
                "${baselineLabel(baseline.baseline)} MAE ${baseline.mae?.let { formatBaselineValue(it) } ?: "none"}, " +
                    "RMSE ${baseline.rmse?.let { formatBaselineValue(it) } ?: "none"}, " +
                    "bias ${baseline.bias?.let { formatBaselineValue(it) } ?: "none"}"
            }
            Text("${axisLabel(comparison.axisId)} count ${comparison.count}: $baselines")
        }
    }
}

internal fun forecastEvidenceLabel(record: AxisPredictionRecord): String = when (val evidence = record.evidence) {
    is ForecastEvidence.Persistence ->
        "Starting ${valueOrMissing(evidence.startingRaw)} (${originLabel(evidence.origin)})"
    is ForecastEvidence.RecentMean -> {
        val used = when (evidence.used) {
            RecentMeanChoice.TRAILING_DAY -> "day mean"
            RecentMeanChoice.TRAILING_WEEK -> "week mean"
            null -> "neither"
        }
        "Day mean ${meanOrNone(evidence.dayMean)} (${originLabel(evidence.dayOrigin)}). " +
            "Week mean ${meanOrNone(evidence.weekMean)} (${originLabel(evidence.weekOrigin)}). " +
            "Used $used."
    }
    is ForecastEvidence.StepMomentum ->
        "Starting ${valueOrMissing(evidence.startingRaw)} (${originLabel(evidence.startingOrigin)}). " +
            "Latest incoming delta ${valueOrMissing(evidence.incomingDelta)} (${originLabel(evidence.deltaOrigin)})."
}

internal fun formatBaselineValue(value: Double): String {
    val scaled = round(value * 1000.0) / 1000.0
    val asLong = scaled.toLong()
    return if (scaled == asLong.toDouble()) asLong.toString() else scaled.toString()
}

private fun valueOrMissing(value: Int?): String = value?.toString() ?: "missing"

private fun meanOrNone(value: Double?): String = value?.let { formatBaselineValue(it) } ?: "none"

private fun originLabel(origin: EvidenceOrigin): String = when (origin) {
    EvidenceOrigin.OBSERVED -> "observed"
    EvidenceOrigin.OVERRIDDEN -> "overridden"
    EvidenceOrigin.CLEARED -> "cleared"
    EvidenceOrigin.UNOBSERVED -> "unobserved"
}

internal fun forecastSummary(record: AxisPredictionRecord): String {
    val name = baselineLabel(record.baseline)
    val forecast = record.forecast ?: return "$name unavailable"
    return "$name ${forecastPhrase(forecast)}"
}

internal fun horizonForecastLabel(scale: TrajectoryScaleId, forecast: ObservableForecast): String {
    return "${scaleLabel(scale)} ${forecastPhrase(forecast)}"
}

internal fun expertLabel(expert: TrajectoryExpert): String {
    val name = scaleLabel(expert.scale)
    val support = "${expert.observedCount} states"
    val delta = expert.stepDelta?.let { " · step delta $it" }.orEmpty()
    val slope = expert.slopePerHour
    if (slope == null) {
        return "$name unavailable (${gapLabel(expert.unavailable)}) · $support$delta"
    }
    val span = expert.elapsedMillis?.let { " · span ${formatBaselineValue(it / 3_600_000.0)} h" }.orEmpty()
    return "$name slope ${formatBaselineValue(slope)} points/hour$delta · $support$span"
}

private fun forecastPhrase(forecast: ObservableForecast): String {
    val bounded = formatBaselineValue(forecast.boundedForecast)
    val raw = if (forecast.rawExtrapolation != forecast.boundedForecast) {
        " · raw ${formatBaselineValue(forecast.rawExtrapolation)}"
    } else {
        ""
    }
    val overflow = when (forecast.overflowDirection) {
        OverflowDirection.LOWER -> " · lower overflow ${formatBaselineValue(forecast.overflowMagnitude)}"
        OverflowDirection.UPPER -> " · upper overflow ${formatBaselineValue(forecast.overflowMagnitude)}"
        null -> ""
    }
    return "forecast $bounded$raw$overflow"
}

private fun scaleLabel(scale: TrajectoryScaleId): String = when (scale) {
    TrajectoryScaleId.IMMEDIATE -> "Immediate"
    TrajectoryScaleId.HOURS_6 -> "6-hour"
    TrajectoryScaleId.HOURS_24 -> "24-hour"
    TrajectoryScaleId.DAYS_7 -> "7-day"
}

private fun gapLabel(gap: TrajectoryExpertGap?): String = when (gap) {
    TrajectoryExpertGap.NO_RESOLVED_PREDECESSOR -> "no resolved predecessor"
    TrajectoryExpertGap.BOUNDARY -> "boundary"
    TrajectoryExpertGap.UNRESOLVED -> "unresolved link"
    TrajectoryExpertGap.INVALID_CHRONOLOGY -> "invalid chronology"
    TrajectoryExpertGap.AMBIGUOUS_PREDECESSOR -> "ambiguous predecessor"
    TrajectoryExpertGap.INSUFFICIENT_OBSERVATIONS -> "fewer than two readings"
    TrajectoryExpertGap.ZERO_TIME_SPAN -> "zero time span"
    null -> "unavailable"
}

private fun baselineLabel(kind: BaselineKind): String = when (kind) {
    BaselineKind.PERSISTENCE -> "Persistence"
    BaselineKind.RECENT_MEAN -> "Recent mean"
    BaselineKind.STEP_MOMENTUM -> "Step momentum"
}

private fun axisLabel(axisId: String): String = when (axisId) {
    DefaultCheckInAxes.MOOD -> "Mood"
    DefaultCheckInAxes.ENERGY -> "Energy"
    DefaultCheckInAxes.CONFIDENCE -> "Confidence"
    DefaultCheckInAxes.ANXIETY -> "Anxiety"
    else -> axisId
}
