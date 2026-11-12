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
import com.orbit.wellness.domain.checkin.DistributionalContextDataset
import com.orbit.wellness.domain.checkin.DistributionalLayer
import com.orbit.wellness.domain.checkin.DistributionalNeighbor
import com.orbit.wellness.domain.checkin.DistributionalReadingIndex
import com.orbit.wellness.domain.checkin.EmbeddingAuditIndex
import com.orbit.wellness.domain.checkin.formatDistributionalContribution
import com.orbit.wellness.domain.checkin.formatDistributionalSimilarity
import com.orbit.wellness.domain.checkin.stateNodeId

@Composable
fun DistributionalContextSection(
    context: DistributionalContextDataset,
    audit: EmbeddingAuditIndex,
    stateId: String,
) {
    var open by remember(stateId) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Distributional context")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Retrospective measurement on the joint-embedding control. Direct incidence stays separate from distributional proximity.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(context.method, style = MaterialTheme.typography.bodySmall)
        context.layers.forEach { layer ->
            StateLayerCard(layer, audit, context.reading, context.supportByConcept, stateNodeId(stateId))
        }
        ConceptAnchor(context, audit)
        ProvenanceIndex(context.reading)
        Notes(context.judgments)
    }
}

@Composable
private fun StateLayerCard(
    layer: DistributionalLayer,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
    support: Map<String, Int>,
    anchor: String,
) {
    var open by remember(layer.name) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("${layerTitle(layer.name)} · ${layer.dimensionality} occurrence landmarks")
    }
    if (!open) return
    Header(layer, reading.line(anchor, audit))
    if (layer.node(anchor) == null) {
        Text("This anchor has no distributional coordinates in this fit.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val lists = layer.occurrenceLists(anchor, audit, reading, support)
    NeighborBlock("Nearest states", lists.states, audit, reading, support)
    NeighborBlock(
        "Directly incident transitions",
        lists.incidentTransitions,
        audit,
        reading,
        support,
        extras = lists.incidentWithoutCoordinates,
    )
    NeighborBlock("Nearest nonincident transitions", lists.nonincidentTransitions, audit, reading, support)
    NeighborBlock("Directly carried concepts", lists.carriedConcepts, audit, reading, support)
    lists.carriedWithoutCoordinates.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
    NeighborBlock("Nearest noncarried concepts", lists.noncarriedConcepts, audit, reading, support)
}

@Composable
private fun ConceptAnchor(context: DistributionalContextDataset, audit: EmbeddingAuditIndex) {
    val concepts = context.layers
        .flatMap { layer -> layer.nodes.filter { it.kind == "concept" }.map { it.id } }
        .distinct()
        .sortedWith(compareBy({ context.reading.neighborhoodByNode[it] ?: it }, { it }))
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
    Text(context.reading.line(concept, audit, context.supportByConcept), style = MaterialTheme.typography.bodyMedium)
    Text(
        "Nearest concepts share occurrence landmarks. Disjoint occurrences stay apart. A directly carried record stays on its own list.",
        style = MaterialTheme.typography.bodySmall,
    )
    context.layers.forEach { layer ->
        ConceptLayerCard(layer, audit, context.reading, context.supportByConcept, concept)
    }
}

@Composable
private fun ConceptLayerCard(
    layer: DistributionalLayer,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
    support: Map<String, Int>,
    concept: String,
) {
    var open by remember(layer.name, concept) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(layerTitle(layer.name))
    }
    if (!open) return
    if (layer.node(concept) == null) {
        Text("This concept has no distributional coordinates in this fit.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val lists = layer.conceptLists(concept, audit)
    NeighborBlock("Directly carried states", lists.directStates, audit, reading, support)
    NeighborBlock("Nearest states", lists.nearestStates, audit, reading, support)
    NeighborBlock("Directly carried transitions", lists.directTransitions, audit, reading, support)
    NeighborBlock("Nearest transitions", lists.nearestTransitions, audit, reading, support)
    NeighborBlock("Nearest concepts", lists.nearestConcepts, audit, reading, support)
}

@Composable
private fun Header(layer: DistributionalLayer, anchor: String) {
    Text(anchor, style = MaterialTheme.typography.bodyMedium)
    Text(
        "${layer.occurrenceCount} occurrences in this fit.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (layer.unreliableBecause.isNotEmpty()) {
        Text(layer.unreliableBecause, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NeighborBlock(
    title: String,
    neighbors: List<DistributionalNeighbor>,
    audit: EmbeddingAuditIndex,
    reading: DistributionalReadingIndex,
    support: Map<String, Int>,
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
            "${reading.line(neighbor.id, audit, support)} · similarity ${formatDistributionalSimilarity(neighbor.similarity)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        )
        neighbor.landmarks.forEach { landmark ->
            Text(
                "${reading.line(landmark.landmarkId, audit)} · contribution ${formatDistributionalContribution(landmark.contribution)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
            )
        }
    }
}

@Composable
private fun ProvenanceIndex(reading: DistributionalReadingIndex) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Exact provenance")
    }
    if (!open) return
    if (reading.provenance.isEmpty()) {
        Text("None.", style = MaterialTheme.typography.bodySmall)
        return
    }
    reading.provenance.forEach { entry ->
        Text(entry.text(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
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

