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
import com.orbit.wellness.domain.checkin.EmbeddingAuditIndex
import com.orbit.wellness.domain.checkin.EmbeddingNeighbor
import com.orbit.wellness.domain.checkin.JointEmbeddingDataset
import com.orbit.wellness.domain.checkin.JointEmbeddingLayer
import com.orbit.wellness.domain.checkin.stateNodeId
import kotlin.math.round

@Composable
fun JointEmbeddingSection(
    embedding: JointEmbeddingDataset,
    stateId: String,
) {
    var open by remember(stateId) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Joint embedding")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Retrospective measurement. Scope ${embedding.vocabularyScope}. Direct incidence is separate from column proximity.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(embedding.method, style = MaterialTheme.typography.bodySmall)
        embedding.layers.forEach { layer ->
            StateLayerCard(layer, embedding.audit, stateNodeId(stateId))
        }
        ConceptAnchor(embedding)
        Notes(embedding.judgments)
    }
}

@Composable
private fun StateLayerCard(layer: JointEmbeddingLayer, audit: EmbeddingAuditIndex, anchor: String) {
    var open by remember(layer.name) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("${layerTitle(layer.name)} · ${layer.dimensionality} dimensions")
    }
    if (!open) return
    Header(layer, audit.recordText(anchor))
    if (layer.node(anchor) == null) {
        Text("This anchor has no coordinates in this fit.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val lists = layer.occurrenceLists(anchor, audit)
    NeighborBlock("Nearest states", lists.states, audit)
    NeighborBlock(
        "Directly incident transitions",
        lists.incidentTransitions,
        audit,
        extras = lists.incidentWithoutCoordinates,
    )
    NeighborBlock("Nearest nonincident transitions", lists.nonincidentTransitions, audit)
    NeighborBlock("Directly carried concepts", lists.carriedConcepts, audit)
    lists.carriedWithoutCoordinates.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
    NeighborBlock("Nearest noncarried concepts", lists.noncarriedConcepts, audit)
}

@Composable
private fun ConceptAnchor(embedding: JointEmbeddingDataset) {
    val concepts = embedding.audit.conceptAnchorIds(embedding.layers)
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Concept anchor")
    }
    if (!open) return
    if (concepts.isEmpty()) {
        Text("None.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var index by remember(concepts) { mutableIntStateOf(0) }
    val concept = concepts[index.coerceIn(0, concepts.lastIndex)]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { index = (index - 1).coerceIn(0, concepts.lastIndex) }) { Text("Previous") }
        TextButton(onClick = { index = (index + 1).coerceIn(0, concepts.lastIndex) }) { Text("Next") }
    }
    Text(embedding.audit.conceptText(concept), style = MaterialTheme.typography.bodyMedium)
    Text(
        "Other concepts stay at similarity zero. A nonzero state or transition shares one of this concept's own columns.",
        style = MaterialTheme.typography.bodySmall,
    )
    embedding.layers.forEach { layer ->
        ConceptLayerCard(layer, embedding.audit, concept)
    }
}

@Composable
private fun ConceptLayerCard(layer: JointEmbeddingLayer, audit: EmbeddingAuditIndex, concept: String) {
    var open by remember(layer.name, concept) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(layerTitle(layer.name))
    }
    if (!open) return
    if (layer.node(concept) == null) {
        Text("This concept has no coordinates in this fit.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val lists = layer.conceptLists(concept)
    NeighborBlock("States with a column on this concept", lists.carryingStates, audit)
    NeighborBlock("Nearest states with no column on this concept", lists.otherStates, audit)
    NeighborBlock("Transitions with a column on this concept", lists.carryingTransitions, audit)
    NeighborBlock("Nearest transitions with no column on this concept", lists.otherTransitions, audit)
    NeighborBlock(
        "Nearest concepts",
        lists.otherConcepts,
        audit,
        emptyText = "None. Distinct concept rows share no columns. Similarity is zero. This is direct incidence, not learned concept proximity.",
    )
}

@Composable
private fun Header(layer: JointEmbeddingLayer, anchor: String) {
    Text(anchor, style = MaterialTheme.typography.bodyMedium)
    Text(
        "${layer.occurrenceCount} occurrences in this fit.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (layer.unreliableBecause.isNotEmpty()) {
        Text(layer.unreliableBecause, style = MaterialTheme.typography.bodySmall)
    }
    if (layer.omittedConstantPresence.isNotEmpty()) {
        Text(
            "Constant identity presence omitted: ${layer.omittedConstantPresence.joinToString(", ")}. Amount or role can remain.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (layer.quantityHeldBack.isNotEmpty()) {
        Text(
            "Quantity held back: ${layer.quantityHeldBack.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NeighborBlock(
    title: String,
    neighbors: List<EmbeddingNeighbor>,
    audit: EmbeddingAuditIndex,
    emptyText: String = "None.",
    extras: List<String> = emptyList(),
) {
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
    if (neighbors.isEmpty() && extras.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
        return
    }
    extras.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
    neighbors.forEach { neighbor ->
        Text(
            "${label(neighbor.id, audit)} · similarity ${format(neighbor.similarity)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        )
        neighbor.contributors.forEach { contributor ->
            Text(
                contributor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
            )
        }
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

private fun layerTitle(name: String): String = when (name) {
    "layer-zero" -> "Layer zero"
    "reciprocal" -> "Reciprocal layer"
    else -> name
}

private fun label(nodeId: String, audit: EmbeddingAuditIndex): String = when {
    nodeId.startsWith("concept:") -> audit.conceptText(nodeId)
    else -> audit.recordText(nodeId)
}

private fun format(value: Double): String {
    val rounded = round(value * 1000.0) / 1000.0
    return if (rounded == 0.0) "0.0" else rounded.toString()
}
