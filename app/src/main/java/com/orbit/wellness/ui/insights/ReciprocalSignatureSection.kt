package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.wellness.domain.checkin.ReciprocalSignatureDataset
import com.orbit.wellness.domain.checkin.SignatureBlock
import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import com.orbit.wellness.domain.checkin.SymbolicFeature
import com.orbit.wellness.domain.checkin.TransitionOccurrenceKey

@Composable
fun ReciprocalSignatureSection(
    signatures: ReciprocalSignatureDataset,
    stateId: String,
    transitionId: String,
) {
    var open by remember(stateId, transitionId) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Reciprocal signature")
    }
    if (!open) return
    val state = signatures.states.firstOrNull { it.key == StateOccurrenceKey(stateId) }
    val transition = signatures.transitions.firstOrNull { it.key == TransitionOccurrenceKey(transitionId) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Layer zero is the control. Retrospective context adds the other side. The causal view withholds it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Vocabulary ${signatures.vocabulary.scope}. ${signatures.vocabulary.concepts.size} concepts. " +
                "Retrospective ${signatures.vocabulary.retrospective}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Mask excludes ${signatures.mask.excludedConcepts.size} identities and ${signatures.mask.excludedTypes.size} types.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(signatures.dynamics.reason, style = MaterialTheme.typography.bodySmall)
        if (state == null || transition == null) {
            Text("This attempt has no signature.")
            return
        }
        FeatureCard("S0", listOf(state.layerZero), signatures)
        FeatureCard("Retrospective S1", state.retrospective, signatures)
        FeatureCard("Causal S1", state.causal, signatures)
        FeatureCard("T0", listOf(transition.layerZero), signatures)
        FeatureCard("Retrospective T1", transition.retrospective, signatures)
        FeatureCard("Causal T1", transition.causal, signatures)
        CoverageCard("OOV", (state.retrospectiveOov + transition.retrospectiveOov).map { coverage ->
            "${coverage.holderRole} · ${coverage.concept.type.name.lowercase()} · ${coverage.concept.identity}"
        })
    }
}

@Composable
private fun FeatureCard(
    title: String,
    blocks: List<SignatureBlock>,
    signatures: ReciprocalSignatureDataset,
) {
    var open by remember(title) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(title)
    }
    if (!open) return
    blocks.forEach { block ->
        Text(block.role, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        block.features.groupBy { it.namespace }.forEach { (namespace, features) ->
            Text(
                namespace.name.lowercase(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
            features.forEach { feature ->
                Text(
                    featureLine(feature, signatures),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverageCard(title: String, lines: List<String>) {
    var open by remember(title) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("$title · ${lines.size}")
    }
    if (!open) return
    if (lines.isEmpty()) {
        Text("None.", style = MaterialTheme.typography.bodySmall)
        return
    }
    lines.distinct().forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

private fun featureLine(feature: SymbolicFeature, signatures: ReciprocalSignatureDataset): String {
    val support = signatures.vocabulary.displayedSupport(feature.value)?.let { " · support $it" }.orEmpty()
    return "${feature.name} · ${feature.value}$support"
}
