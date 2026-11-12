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
import com.orbit.wellness.domain.checkin.MaskedConceptReconstructionDataset
import com.orbit.wellness.domain.checkin.ReconstructionApproach
import com.orbit.wellness.domain.checkin.ReconstructionFold
import com.orbit.wellness.domain.checkin.ReconstructionIdentityRates
import com.orbit.wellness.domain.checkin.ReconstructionIdentitySummary
import com.orbit.wellness.domain.checkin.ReconstructionMacroRates
import com.orbit.wellness.domain.checkin.ReconstructionMacroSlice
import com.orbit.wellness.domain.checkin.ReconstructionMetrics
import com.orbit.wellness.domain.checkin.ReconstructionPairSlice
import com.orbit.wellness.domain.checkin.ReconstructionPairing
import com.orbit.wellness.domain.checkin.formatDistributionalSimilarity
import kotlin.math.round

@Composable
fun MaskedConceptReconstructionSection(reconstruction: MaskedConceptReconstructionDataset) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Concept reconstruction")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Retrospective leave-one-occurrence-out. Not walk-forward and not causal.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Eligible ${reconstruction.eligible} · scored ${reconstruction.scored} · cold-start ${reconstruction.coldStart}.",
            style = MaterialTheme.typography.bodySmall,
        )
        MetricLine(reconstruction.frequency)
        MetricLine(reconstruction.layerZero)
        MetricLine(reconstruction.reciprocal)
        reconstruction.byType.forEach { slice ->
            Text(slice.label, style = MaterialTheme.typography.labelMedium)
            MetricLine(slice.frequency)
            MetricLine(slice.layerZero)
            MetricLine(slice.reciprocal)
        }
        reconstruction.bySupportBand.forEach { slice ->
            Text(slice.label, style = MaterialTheme.typography.labelMedium)
            MetricLine(slice.frequency)
            MetricLine(slice.layerZero)
            MetricLine(slice.reciprocal)
        }
        Text("Macro by identity", style = MaterialTheme.typography.labelMedium)
        Text(
            "Each scored identity has equal weight. Cold-start identities are counted apart from the average.",
            style = MaterialTheme.typography.bodySmall,
        )
        MacroSlice(reconstruction.macro, heading = false)
        reconstruction.macroByType.forEach { MacroSlice(it, heading = true) }
        reconstruction.macroBySupportBand.forEach { MacroSlice(it, heading = true) }
        Text("Paired folds", style = MaterialTheme.typography.labelMedium)
        Text(
            "Better means a smaller rank. The delta is the mean of one over the reciprocal rank minus one over the other rank.",
            style = MaterialTheme.typography.bodySmall,
        )
        PairSlice(reconstruction.paired, heading = false)
        reconstruction.pairedByType.forEach { PairSlice(it, heading = true) }
        reconstruction.pairedBySupportBand.forEach { PairSlice(it, heading = true) }
        Identities(reconstruction.identities)
        FoldCard(reconstruction.folds)
        AllFolds(reconstruction.folds)
        Notes(reconstruction.judgments)
    }
}

@Composable
private fun MacroSlice(slice: ReconstructionMacroSlice, heading: Boolean) {
    if (heading) {
        Text(slice.label, style = MaterialTheme.typography.labelMedium)
    }
    Text(
        "Scored identities ${slice.scoredIdentities} · cold-start identities ${slice.coldStartIdentities}.",
        style = MaterialTheme.typography.bodySmall,
    )
    MacroLine(slice.frequency)
    MacroLine(slice.layerZero)
    MacroLine(slice.reciprocal)
}

@Composable
private fun MacroLine(rates: ReconstructionMacroRates) {
    val body = if (rates.top1Rate == null) {
        "no scored identities"
    } else {
        "top-1 rate ${formatRate(rates.top1Rate)} · top-3 rate ${formatRate(rates.top3Rate)} · MRR ${formatRate(rates.meanReciprocalRank)}"
    }
    Text("${rates.label} · $body", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PairSlice(slice: ReconstructionPairSlice, heading: Boolean) {
    if (heading) {
        Text(slice.label, style = MaterialTheme.typography.labelMedium)
    }
    PairLine("Reciprocal and layer zero", slice.versusLayerZero)
    PairLine("Reciprocal and frequency", slice.versusFrequency)
}

@Composable
private fun PairLine(title: String, pairing: ReconstructionPairing) {
    val body = if (pairing.scoredFolds == 0) {
        "no scored folds"
    } else {
        "better ${pairing.better} · tied ${pairing.tied} · worse ${pairing.worse} · " +
            "delta ${formatSignedRate(pairing.meanReciprocalRankDelta)} · " +
            "top-1 gained ${pairing.top1Gained} · lost ${pairing.top1Lost} · scored folds ${pairing.scoredFolds}"
    }
    Text("$title · $body", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Identities(identities: List<ReconstructionIdentitySummary>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Identities")
    }
    if (!open) return
    Text(
        "Sorted by scored folds. Occurrence-weighted metrics above still count every fold.",
        style = MaterialTheme.typography.bodySmall,
    )
    identities.forEach { identity ->
        val head = if (identity.scoredFolds > 0) {
            "${identity.label} · ${identity.scoredFolds} scored · remaining ${identity.supportRemaining}"
        } else if (identity.coldStartFolds > 0) {
            "${identity.label} · cold-start · not scored"
        } else {
            "${identity.label} · not trusted"
        }
        Text(head, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        if (identity.scoredFolds > 0) {
            IdentityLine("Frequency", identity.scoredFolds, identity.frequency)
            IdentityLine("Layer zero", identity.scoredFolds, identity.layerZero)
            IdentityLine("Reciprocal", identity.scoredFolds, identity.reciprocal)
        }
    }
}

@Composable
private fun IdentityLine(title: String, scored: Int, rates: ReconstructionIdentityRates) {
    Text(
        "$title ${rates.top1Hits}/$scored · top-3 ${rates.top3Hits}/$scored · MRR ${formatRate(rates.meanReciprocalRank)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun FoldCard(folds: List<ReconstructionFold>) {
    if (folds.isEmpty()) {
        Text("No eligible concept occurrence.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var index by remember(folds) { mutableIntStateOf(0) }
    val fold = folds[index.coerceIn(0, folds.lastIndex)]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { index = (index - 1).coerceIn(0, folds.lastIndex) }) { Text("Previous") }
        TextButton(onClick = { index = (index + 1).coerceIn(0, folds.lastIndex) }) { Text("Next") }
    }
    Text("Hidden ${fold.hiddenText}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Visible ${fold.visibleText}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Before ${fold.supportBefore} · remaining ${fold.supportRemaining} · candidates ${fold.candidates.size}",
        style = MaterialTheme.typography.bodySmall,
    )
    ApproachLine("Frequency", fold.frequency, count = true)
    ApproachLine("Layer zero", fold.layerZero, count = false)
    fold.layerZeroLandmarks.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
    ApproachLine("Reciprocal", fold.reciprocal, count = false)
    fold.reciprocalLandmarks.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
    Text(fold.absenceText, style = MaterialTheme.typography.bodySmall)
    if (fold.coldStart) {
        Text("Cold-start. Not scored.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApproachLine(title: String, approach: ReconstructionApproach, count: Boolean) {
    val rank = approach.rank
    val score = approach.score
    val detail = when {
        rank == null || score == null -> "not scored"
        count -> "rank $rank · count ${score.toInt()}"
        else -> "rank $rank · similarity ${formatDistributionalSimilarity(score)}"
    }
    Text("$title · $detail", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MetricLine(metrics: ReconstructionMetrics) {
    val body = if (metrics.scored == 0) {
        "no scored folds"
    } else {
        "top-1 ${metrics.top1Hits}/${metrics.scored} · top-3 ${metrics.top3Hits}/${metrics.scored} · " +
            "MRR ${formatRate(metrics.meanReciprocalRank)} · candidates ${formatRate(metrics.meanCandidateCount)}"
    }
    Text("${metrics.label} · $body", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AllFolds(folds: List<ReconstructionFold>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("All folds")
    }
    if (!open) return
    folds.forEach { fold ->
        Text(fold.line(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Notes(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Notes")
    }
    if (!open) return
    lines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatRate(value: Double?): String {
    if (value == null) return "none"
    val rounded = round(value * 1000.0) / 1000.0
    return if (rounded == 0.0) "0.0" else rounded.toString()
}

private fun formatSignedRate(value: Double?): String {
    if (value == null) return "none"
    val rounded = round(value * 1000.0) / 1000.0
    val text = if (rounded == 0.0) "0.0" else rounded.toString()
    return if (rounded > 0.0) "+$text" else text
}
