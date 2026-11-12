package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.wellness.domain.checkin.SemanticNullAxisScore
import com.orbit.wellness.domain.checkin.SemanticNullComparisonDataset
import com.orbit.wellness.domain.checkin.SemanticNullFold
import com.orbit.wellness.domain.checkin.SemanticNullMetrics
import kotlin.math.round

@Composable
fun SemanticNullComparisonSection(comparison: SemanticNullComparisonDataset) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Semantic Null comparison")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Walk-forward correction beside the frozen Null. Retrospective horizon. Event-time reconstruction. Strict as-known false.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "κ ${formatPoint(comparison.kappa)}. Effective support is reported and does not drop a fold.",
            style = MaterialTheme.typography.bodySmall,
        )
        MetricBlock(comparison.overall)
        comparison.byAxis.forEach { metrics ->
            Text(metrics.label, style = MaterialTheme.typography.labelMedium)
            MetricBlock(metrics)
        }
        FoldCard(comparison.folds)
        AllFolds(comparison.folds)
        Notes(comparison.judgments)
    }
}

@Composable
private fun MetricBlock(metrics: SemanticNullMetrics) {
    Text(
        "Scored ${metrics.scored} · unscored ${metrics.unscored}.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (metrics.scored == 0) {
        Text("No scored folds.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "Null · bias ${formatSigned(metrics.nullBias)} · MAE ${formatPoint(metrics.nullMae)} · RMSE ${formatPoint(metrics.nullRmse)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Semantic · bias ${formatSigned(metrics.bias)} · MAE ${formatPoint(metrics.mae)} · RMSE ${formatPoint(metrics.rmse)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Better ${metrics.better} · tied ${metrics.tied} · worse ${metrics.worse}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Mean correction magnitude ${formatPoint(metrics.meanCorrectionMagnitude)} · " +
            "mean effective support ${formatPoint(metrics.meanEffectiveSupport)} · " +
            "zero support ${metrics.zeroSupportScored}.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun FoldCard(folds: List<SemanticNullFold>) {
    if (folds.isEmpty()) {
        Text("No Null fold.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var index by remember(folds) { mutableIntStateOf(0) }
    val fold = folds[index.coerceIn(0, folds.lastIndex)]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { index = (index - 1).coerceIn(0, folds.lastIndex) }) { Text("Previous") }
        TextButton(onClick = { index = (index + 1).coerceIn(0, folds.lastIndex) }) { Text("Next") }
    }
    Text(fold.anchorLabel, style = MaterialTheme.typography.bodyMedium)
    Text(fold.horizonText, style = MaterialTheme.typography.bodySmall)
    fold.axes.forEach { axis -> AxisAudit(axis) }
    Text(fold.absenceText, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AxisAudit(axis: SemanticNullAxisScore) {
    Text(axis.axisId, style = MaterialTheme.typography.labelMedium)
    if (!axis.scored) {
        Text("Not scored. The frozen Null has no bounded forecast for an observed target.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "Frozen Null forecast ${formatPoint(axis.nullBounded)} · official residual ${formatSigned(axis.nullResidual)}",
        style = MaterialTheme.typography.bodySmall,
    )
    val similar = axis.neighbors.filter { it.weight > 0.0 }
    val ignored = axis.neighbors.count { it.weight == 0.0 }
    if (similar.isEmpty()) {
        Text("No prior with positive cosine.", style = MaterialTheme.typography.bodySmall)
    } else {
        similar.forEach { neighbor ->
            Text(
                "${neighbor.anchorLabel} · cosine ${formatPoint(neighbor.cosine)} · weight ${formatPoint(neighbor.weight)} · " +
                    "official residual ${formatSigned(neighbor.officialResidual)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
    if (ignored > 0) {
        Text(
            "$ignored priors with a frozen residual had cosine at or below zero and weight 0.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "Numerator ${formatSigned(axis.numerator)} · weight sum ${formatPoint(axis.weightSum)} · κ ${formatPoint(axis.kappa)} · " +
            "correction ${formatSigned(axis.correction)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Corrected forecast ${formatPoint(axis.semanticBounded)} · actual ${formatPoint(axis.actual)} · " +
            "semantic residual ${formatSigned(axis.semanticResidual)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Effective support ${formatPoint(axis.effectiveSupport)}.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AllFolds(folds: List<SemanticNullFold>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("All folds") }
    if (!open) return
    folds.forEach { fold ->
        Text(fold.line(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Notes(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("Notes") }
    if (!open) return
    lines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
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
