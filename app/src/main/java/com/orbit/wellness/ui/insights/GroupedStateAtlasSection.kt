package com.orbit.wellness.ui.insights

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.domain.checkin.REGION_SOURCE_DISTANCE_NOTE
import com.orbit.wellness.domain.checkin.RegionDescription
import com.orbit.wellness.domain.checkin.RegionDescriptionDataset
import com.orbit.wellness.domain.checkin.StateGroup
import com.orbit.wellness.domain.checkin.StateGroupAtlasDataset
import com.orbit.wellness.domain.checkin.StateGroupAtlasView
import com.orbit.wellness.domain.checkin.StateGroupAxisSummary
import com.orbit.wellness.domain.checkin.StateGroupCut
import com.orbit.wellness.domain.checkin.StateGroupDirection
import com.orbit.wellness.domain.checkin.StateGroupEdge
import com.orbit.wellness.domain.checkin.StateGroupNumberSummary
import com.orbit.wellness.domain.checkin.formatRecordedDuration
import com.orbit.wellness.domain.checkin.regionLineage
import com.orbit.wellness.domain.checkin.unduplicatedRecordLabel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun GroupedStateAtlasSection(groups: StateGroupAtlasDataset, descriptions: RegionDescriptionDataset) {
    var geometry by remember { mutableIntStateOf(0) }
    var context by remember { mutableIntStateOf(0) }
    val geometryLabel = if (geometry == 0) "Direct incidence" else "Distributional context"
    val contextLabel = if (context == 0) "Layer zero" else "Reciprocal"
    val view = groups.views.first { it.geometryLabel == geometryLabel && it.contextLabel == contextLabel }
    var cutIndex by remember(view.geometry, view.context) { mutableIntStateOf(view.displayDefaultCutIndex) }
    val cut = view.cuts[cutIndex.coerceIn(0, view.cuts.lastIndex)]
    var colorMode by remember { mutableIntStateOf(0) }
    var incidentOnly by remember { mutableStateOf(false) }
    var selectedGroup by remember(view.geometry, view.context) { mutableStateOf<String?>(null) }
    var selectedEdge by remember(view.geometry, view.context) { mutableStateOf<String?>(null) }
    LaunchedEffect(cutIndex, view.geometry, view.context) {
        if (selectedGroup != null && cut.groups.none { it.alias == selectedGroup }) selectedGroup = null
        if (selectedEdge != null && cut.edges.none { edgeKey(it) == selectedEdge }) selectedEdge = null
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Grouped state atlas", style = MaterialTheme.typography.titleMedium)
        groups.judgments.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "X and Y are layout directions only. Group membership and similarity come from the full-dimensional source geometry. Rotating or mirroring this graph does not change its meaning.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { geometry = 0 }) { Text(groupMark(geometry == 0, "Direct incidence")) }
            TextButton(onClick = { geometry = 1 }) { Text(groupMark(geometry == 1, "Distributional context")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context = 0 }) { Text(groupMark(context == 0, "Layer zero")) }
            TextButton(onClick = { context = 1 }) { Text(groupMark(context == 1, "Reciprocal")) }
        }
        Text(
            "Layer zero is radius 0. Reciprocal is the existing one-hop layer. Distributional context is not graph radius 2.",
            style = MaterialTheme.typography.bodySmall,
        )
        CutControls(
            view = view,
            cutIndex = cutIndex,
            onCut = { cutIndex = it },
        )
        CutSummary(view, cut, cutIndex)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { incidentOnly = !incidentOnly }) {
                Text(if (incidentOnly) "Selected-group edges" else "All transition edges")
            }
            TextButton(onClick = { colorMode = (colorMode + 1) % GROUP_COLORS.size }) {
                Text("Color · ${GROUP_COLORS[colorMode]}")
            }
        }
        Text(
            "Color reads a dominant emotion or one axis median. It does not define the group. Node size is state support and edge width is transition count. Neither changes membership or layout distance.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (incidentOnly && selectedGroup == null) {
            Text("Select a group to see its transitions.", style = MaterialTheme.typography.bodySmall)
        }
        GroupCanvas(
            view = view,
            cut = cut,
            colorMode = GROUP_COLORS[colorMode],
            incidentOnly = incidentOnly,
            selectedGroup = selectedGroup,
            selectedEdge = selectedEdge,
            onSelectGroup = {
                selectedGroup = it
                selectedEdge = null
            },
            onSelectEdge = { selectedEdge = it },
        )
        val group = cut.groups.firstOrNull { it.alias == selectedGroup }
        val edge = cut.edges.firstOrNull { edgeKey(it) == selectedEdge }
        when {
            edge != null -> EdgeDetail(edge)
            group != null -> GroupDetail(
                group = group,
                view = view,
                cutIndex = cutIndex,
                description = descriptions.views.firstOrNull { it.geometry == view.geometry && it.context == view.context }
                    ?.cuts
                    ?.getOrNull(cutIndex)
                    ?.regions
                    ?.firstOrNull { it.alias == group.alias },
            )
            else -> Text("Tap a group or a transition arrow.", style = MaterialTheme.typography.bodySmall)
        }
        if (view.ungrouped.isNotEmpty()) {
            Text(
                "Ungrouped states · ${view.ungrouped.size}. A state without usable coordinates stays out of every cut.",
                style = MaterialTheme.typography.bodySmall,
            )
            view.ungrouped.forEach { missing ->
                Text("${missing.label} · ${missing.reason}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CutControls(
    view: StateGroupAtlasView,
    cutIndex: Int,
    onCut: (Int) -> Unit,
) {
    val last = view.cuts.lastIndex
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onCut((cutIndex - 1).coerceAtLeast(0)) }) { Text("Tighter") }
        TextButton(onClick = { onCut((cutIndex + 1).coerceAtMost(last)) }) { Text("Looser") }
    }
    if (last > 0) {
        Slider(
            value = cutIndex.toFloat(),
            onValueChange = { onCut(it.roundToInt().coerceIn(0, last)) },
            valueRange = 0f..last.toFloat(),
            steps = (last - 1).coerceAtLeast(0),
        )
    }
        Text(
            "The slider snaps to recorded merge levels. ${cutIndex + 1} of ${view.cuts.size}. The opening cut is a UI default: the cut nearest eight groups. Not a selected partition.",
            style = MaterialTheme.typography.bodySmall,
        )
}

@Composable
private fun CutSummary(view: StateGroupAtlasView, cut: StateGroupCut, cutIndex: Int) {
    val threshold = cut.minimumSimilarity?.let { formatGroup(it) } ?: "above every merge"
    Text("${view.geometryLabel} · ${view.contextLabel}", style = MaterialTheme.typography.labelMedium)
    Text("Minimum similarity $threshold.", style = MaterialTheme.typography.bodySmall)
    Text(
        "Groups ${cut.groups.size}. Singletons ${cut.singletonCount}. " +
            "Support smallest ${cut.smallestSupport}, median ${formatGroup(cut.medianSupport)}, largest ${cut.largestSupport}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Within-group similarity minimum ${formatGroup(cut.minimumWithinSimilarity)}, mean ${formatGroup(cut.meanWithinSimilarity)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Layout stress ${formatGroup(cut.layoutStress)}. Positive dimensions ${cut.positiveDimensions}. Retained ${cut.retainedDimensions}. Negative eigenvalue share ${formatGroup(cut.negativeEigenvalueShare)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (cut.layoutNote.isNotBlank()) {
        Text(cut.layoutNote, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Ungrouped ${view.ungrouped.size}. ${view.ungrouped.firstOrNull()?.reason ?: "Every state in this view had usable coordinates."}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (cutIndex == view.displayDefaultCutIndex) {
        Text(view.displayDefaultReason, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Directed edges ${cut.edges.size}. Self-loops ${cut.edges.count { it.selfLoop }}. Unresolved transitions ${cut.unresolvedTransitions.size}.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GroupCanvas(
    view: StateGroupAtlasView,
    cut: StateGroupCut,
    colorMode: String,
    incidentOnly: Boolean,
    selectedGroup: String?,
    selectedEdge: String?,
    onSelectGroup: (String) -> Unit,
    onSelectEdge: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textPx = with(density) { 11.sp.toPx() }
    val placed = cut.groups.filter { it.x != null }
    val edges = cut.edges.filter { edge ->
        !incidentOnly || edge.sourceAlias == selectedGroup || edge.targetAlias == selectedGroup
    }
    var scale by remember(view.geometry, view.context) { mutableFloatStateOf(1f) }
    var pan by remember(view.geometry, view.context) { mutableStateOf(Offset.Zero) }
    Column {
        TextButton(onClick = {
            scale = 1f
            pan = Offset.Zero
        }) { Text("Fit") }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .pointerInput(view.geometry, view.context, cut.minimumSimilarity, incidentOnly, selectedGroup) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val panChange = event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            if (panChange.getDistance() > viewConfiguration.touchSlop || abs(zoomChange - 1f) > 0.01f) {
                                moved = true
                            }
                            if (moved) {
                                pan += panChange
                                scale = (scale * zoomChange).coerceIn(0.4f, 8f)
                                event.changes.forEach { change -> if (change.pressed) change.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved) {
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            val screens = screens(placed, width, height, scale, pan)
                            val edgeHit = nearestEdge(edges, screens, down.position)
                            val groupHit = nearestGroup(placed, screens, down.position)
                            if (edgeHit != null && (groupHit == null || edgeHit.second <= groupHit.second)) {
                                onSelectEdge(edgeHit.first)
                            } else if (groupHit != null) {
                                onSelectGroup(groupHit.first)
                            }
                        }
                    }
                },
        ) {
            val screens = screens(placed, size.width, size.height, scale, pan)
            edges.forEach { edge ->
                drawGroupEdge(edge, screens, scheme.outline, edgeKey(edge) == selectedEdge)
            }
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = textPx
                color = scheme.onSurface.toArgb()
            }
            placed.forEach { group ->
                val center = screens[group.alias] ?: return@forEach
                val color = groupColor(group, colorMode, scheme.primary)
                drawCircle(color, radiusOf(group), center)
                if (group.alias == selectedGroup) {
                    drawCircle(scheme.onSurface, radiusOf(group) + 3f, center, style = Stroke(width = 2f))
                }
                val label = compactLabel(group)
                drawContext.canvas.nativeCanvas.drawText(label, center.x + radiusOf(group) + 6f, center.y - 4f, paint)
            }
        }
    }
}

@Composable
private fun GroupDetail(
    group: StateGroup,
    view: StateGroupAtlasView,
    cutIndex: Int,
    description: RegionDescription?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (description != null) {
            ConciseRegionCard(description)
            Disclosure("Show evidence") { EvidenceBlock(description) }
        }
        Disclosure("Technical details") { TechnicalRegionBlock(group, view, cutIndex) }
    }
}

@Composable
private fun ConciseRegionCard(region: RegionDescription) {
    Text("${region.alias} · ${region.memberCount} states", style = MaterialTheme.typography.bodyMedium)
    Text("Medoid · ${region.medoidLabel}", style = MaterialTheme.typography.bodySmall)
    Text("Minimum similarity ${region.thresholdLabel}.", style = MaterialTheme.typography.bodySmall)
    Text(region.emotionMixture, style = MaterialTheme.typography.bodySmall)
    region.axes.forEach { axis -> Text(axis.concise, style = MaterialTheme.typography.bodySmall) }
    Text(
        "Within-group similarity minimum ${formatGroup(region.minimumWithinSimilarity)}, mean ${formatGroup(region.meanWithinSimilarity)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Nearest regions", style = MaterialTheme.typography.labelMedium)
    if (region.nearest.isEmpty()) {
        Text("No other region is in this cut.", style = MaterialTheme.typography.bodySmall)
    }
    region.nearest.forEach { neighbor -> Text(neighbor.concise, style = MaterialTheme.typography.bodySmall) }
    Text(REGION_SOURCE_DISTANCE_NOTE, style = MaterialTheme.typography.bodySmall)
    AssociationBlock(region.characterHeading, region.characterizationConcise.map { it.concise })
    AssociationBlock(region.entryHeading, region.entryConcise.map { it.concise })
    AssociationBlock(region.maintenanceHeading, region.maintenanceConcise.map { it.concise })
    AssociationBlock(region.exitHeading, region.exitConcise.map { it.concise })
    Text(
        "Boundary endings ${region.boundaryEndings}. Ungrouped sources ${region.ungroupedSources}. Ungrouped destinations ${region.ungroupedDestinations}. Ungrouped states ${region.ungroupedIds.size}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(region.circularity, style = MaterialTheme.typography.bodySmall)
    Text(region.disclosure, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AssociationBlock(heading: String, lines: List<String>) {
    Text(heading, style = MaterialTheme.typography.labelMedium)
    if (lines.isEmpty()) {
        Text("No repeated comparison is available.", style = MaterialTheme.typography.bodySmall)
    }
    lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun EvidenceBlock(region: RegionDescription) {
    Text(
        "Inside ${region.insideIds.size}. Outside ${region.outsideIds.size}. Ungrouped ${region.ungroupedIds.size}.",
        style = MaterialTheme.typography.bodySmall,
    )
    region.axes.forEach { axis -> Text(axis.evidence, style = MaterialTheme.typography.bodySmall) }
    region.characterization.forEach { feature -> Text(feature.evidence, style = MaterialTheme.typography.bodySmall) }
    Text(
        "Entry opportunities ${region.entryOpportunities}. Entered ${region.entered}. Outgoing ${region.outgoing}. Self-loops ${region.maintained}. Exits ${region.exited}.",
        style = MaterialTheme.typography.bodySmall,
    )
    region.entry.forEach { behavior -> Text(behavior.evidence, style = MaterialTheme.typography.bodySmall) }
    region.maintenance.forEach { behavior -> Text(behavior.evidence, style = MaterialTheme.typography.bodySmall) }
    region.exit.forEach { behavior -> Text(behavior.evidence, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun TechnicalRegionBlock(group: StateGroup, view: StateGroupAtlasView, cutIndex: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(compactLabel(group), style = MaterialTheme.typography.bodyMedium)
        regionLineage(view, cutIndex, group).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${group.members.size} states. ${group.earliestLabel} through ${group.latestLabel}.",
            style = MaterialTheme.typography.bodySmall,
        )
        group.members.forEach { member ->
            Text(
                "${unduplicatedRecordLabel(member.alias, member.label)} · ${member.localDate}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Medoid · ${unduplicatedRecordLabel(group.members.first { it.id == group.medoidId }.alias, group.medoidLabel)}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Pairwise similarity minimum ${formatGroup(group.minimumSimilarity)}, mean ${formatGroup(group.meanSimilarity)}, maximum ${formatGroup(group.maximumSimilarity)}.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Emotion distribution", style = MaterialTheme.typography.labelMedium)
        group.emotions.forEach { emotion ->
            Text(
                "${emotion.name} · ${emotion.count} · ${formatGroup(emotion.proportion)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Observed core axes. Missing stays missing.", style = MaterialTheme.typography.labelMedium)
        group.axes.forEach { axis -> Text(axisLine(axis), style = MaterialTheme.typography.bodySmall) }
        Text("Concepts carried by member states", style = MaterialTheme.typography.labelMedium)
        if (group.carriedConcepts.isEmpty()) {
            Text("No concept is carried directly by these states.", style = MaterialTheme.typography.bodySmall)
        }
        group.carriedConcepts.forEach { concept ->
            Text(
                "${concept.label} · ${concept.type} · ${concept.occurrences} · ${concept.members} members",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DirectionBlock("Incoming recorded transitions", group.incoming)
        DirectionBlock("Outgoing recorded transitions", group.outgoing)
        Text(
            "These are retrospective incidence descriptions. Diet review covers food and meal size together. Concept identities stay separate.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DirectionBlock(title: String, direction: StateGroupDirection) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    Text(
        "Transitions ${direction.transitionCount}. Self-loops ${direction.selfLoopCount}. Boundary ${direction.boundaryCount}. Ungrouped endpoints ${direction.ungroupedEndpointCount}.",
        style = MaterialTheme.typography.bodySmall,
    )
    direction.linkedGroups.forEach { link ->
        Text("${link.label} · ${link.count}", style = MaterialTheme.typography.bodySmall)
    }
    Text(durationText(direction.duration), style = MaterialTheme.typography.bodySmall)
    direction.concepts.forEach { concept ->
        Text(
            "${concept.type} · ${concept.label} · ${concept.occurrences}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    direction.categories.forEach { category ->
        Text(
            "${category.category} · reviewed empty ${category.reviewedEmpty} · entries ${category.reviewedEntries} · unreviewed ${category.unreviewed} · missing ${category.absent} · conflicting ${category.conflicting}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    direction.quantities.forEach { quantity ->
        val numbers = if (quantity.values.isEmpty()) "" else quantity.values.joinToString(", ") { formatGroup(it) }
        Text(
            "${quantity.label} · ${quantity.unit} · $numbers · unparsed ${quantity.unparsedCount}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EdgeDetail(edge: StateGroupEdge) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val arrow = if (edge.selfLoop) "${edge.sourceAlias} self-loop" else "${edge.sourceAlias} → ${edge.targetAlias}"
        Text("$arrow · ${edge.transitionCount}", style = MaterialTheme.typography.bodyMedium)
        edge.transitions.forEach { transition ->
            Text(unduplicatedRecordLabel(transition.alias, transition.label), style = MaterialTheme.typography.bodySmall)
        }
        Text(durationText(edge.duration), style = MaterialTheme.typography.bodySmall)
        edge.axisChanges.forEach { change ->
            Text(
                "${change.axisId} change · observed ${change.observed} · missing ${change.missing} · minimum ${formatGroup(change.minimum)} · median ${formatGroup(change.median)} · mean ${formatGroup(change.mean)} · maximum ${formatGroup(change.maximum)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        edge.concepts.forEach { concept ->
            Text("${concept.type} · ${concept.label} · ${concept.occurrences}", style = MaterialTheme.typography.bodySmall)
        }
        edge.categories.forEach { category ->
            Text(
                "${category.category} · reviewed empty ${category.reviewedEmpty} · entries ${category.reviewedEntries} · unreviewed ${category.unreviewed} · missing ${category.absent} · conflicting ${category.conflicting}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        edge.quantities.forEach { quantity ->
            val numbers = if (quantity.values.isEmpty()) "" else quantity.values.joinToString(", ") { formatGroup(it) }
            Text("${quantity.label} · ${quantity.unit} · $numbers · unparsed ${quantity.unparsedCount}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Retrospective incidence. Not a cause, effect, recommendation, or prediction.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun screens(
    groups: List<StateGroup>,
    width: Float,
    height: Float,
    scale: Float,
    pan: Offset,
): Map<String, Offset> {
    val xs = groups.mapNotNull { it.x }
    val ys = groups.mapNotNull { it.y }
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0
    val x0 = if (maxX - minX < 1e-6) minX - 0.5 else minX
    val x1 = if (maxX - minX < 1e-6) maxX + 0.5 else maxX
    val y0 = if (maxY - minY < 1e-6) minY - 0.5 else minY
    val y1 = if (maxY - minY < 1e-6) maxY + 0.5 else maxY
    val yFallback = if (ys.isEmpty()) 0.0 else (minY + maxY) / 2.0
    val pad = 48f
    val cx = width / 2f
    val cy = height / 2f
    return groups.mapNotNull { group ->
        val x = group.x ?: return@mapNotNull null
        val y = group.y ?: yFallback
        val fitX = pad + ((x - x0) / (x1 - x0) * (width - 2 * pad)).toFloat()
        val fitY = pad + ((y1 - y) / (y1 - y0) * (height - 2 * pad)).toFloat()
        group.alias to Offset(cx + (fitX - cx) * scale + pan.x, cy + (fitY - cy) * scale + pan.y)
    }.toMap()
}

private fun nearestGroup(
    groups: List<StateGroup>,
    screens: Map<String, Offset>,
    tap: Offset,
): Pair<String, Float>? {
    return groups.mapNotNull { group ->
        val center = screens[group.alias] ?: return@mapNotNull null
        val distance = hypot(center.x - tap.x, center.y - tap.y)
        if (distance <= radiusOf(group) + 18f) group.alias to distance else null
    }.minByOrNull { it.second }
}

private fun nearestEdge(
    edges: List<StateGroupEdge>,
    screens: Map<String, Offset>,
    tap: Offset,
): Pair<String, Float>? {
    return edges.mapNotNull { edge ->
        val from = screens[edge.sourceAlias] ?: return@mapNotNull null
        val to = screens[edge.targetAlias] ?: return@mapNotNull null
        val distance = if (edge.selfLoop) {
            hypot(tap.x - from.x, tap.y - (from.y - 22f))
        } else {
            segmentDistance(tap, from, to)
        }
        if (distance <= 22f) edgeKey(edge) to distance else null
    }.minByOrNull { it.second }
}

private fun segmentDistance(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = dx * dx + dy * dy
    if (length < 1e-4f) return hypot(point.x - start.x, point.y - start.y)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / length).coerceIn(0f, 1f)
    return hypot(point.x - (start.x + dx * t), point.y - (start.y + dy * t))
}

private fun DrawScope.drawGroupEdge(
    edge: StateGroupEdge,
    screens: Map<String, Offset>,
    color: Color,
    selected: Boolean,
) {
    val from = screens[edge.sourceAlias] ?: return
    val to = screens[edge.targetAlias] ?: return
    val width = (1.6f + edge.transitionCount * 1.3f).coerceAtMost(12f)
    val paint = if (selected) color.copy(alpha = 1f) else color.copy(alpha = 0.7f)
    if (edge.selfLoop) {
        val loop = Offset(from.x, from.y - 22f)
        drawCircle(paint, 12f, loop, style = Stroke(width = width))
        drawArrow(Offset(loop.x + 10f, loop.y), Offset(loop.x + 4f, loop.y + 10f), paint)
        return
    }
    val shrink = 16f
    val angle = atan2(to.y - from.y, to.x - from.x)
    val tip = Offset(to.x - cos(angle) * shrink, to.y - sin(angle) * shrink)
    drawLine(paint, from, tip, strokeWidth = width)
    drawArrow(tip, Offset(tip.x - cos(angle) * 12f, tip.y - sin(angle) * 12f), paint)
}

private fun DrawScope.drawArrow(tip: Offset, tail: Offset, color: Color) {
    val angle = atan2(tip.y - tail.y, tip.x - tail.x)
    val left = Offset(tip.x - cos(angle - 0.45f) * 12f, tip.y - sin(angle - 0.45f) * 12f)
    val right = Offset(tip.x - cos(angle + 0.45f) * 12f, tip.y - sin(angle + 0.45f) * 12f)
    drawPath(Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }, color)
}

private fun radiusOf(group: StateGroup): Float = (9f + 4f * sqrt(group.members.size.toFloat())).coerceAtMost(28f)

private fun compactLabel(group: StateGroup): String {
    val count = if (group.members.size == 1) "1 state" else "${group.members.size} states"
    val emotion = group.dominantEmotion?.let { " · $it ${group.dominantEmotionCount}/${group.members.size}" }.orEmpty()
    return "${group.alias} · $count$emotion"
}

private fun groupColor(group: StateGroup, mode: String, fallback: Color): Color {
    return when (mode) {
        "mood" -> axisMedianColor(group, DefaultCheckInAxes.MOOD) ?: fallback
        "energy" -> axisMedianColor(group, DefaultCheckInAxes.ENERGY) ?: fallback
        "confidence" -> axisMedianColor(group, DefaultCheckInAxes.CONFIDENCE) ?: fallback
        "anxiety" -> axisMedianColor(group, DefaultCheckInAxes.ANXIETY) ?: fallback
        else -> group.dominantEmotion?.let { emotionColor(it) } ?: fallback
    }
}

private fun axisMedianColor(group: StateGroup, axisId: String): Color? {
    val median = group.axes.firstOrNull { it.axisId == axisId }?.median ?: return null
    val fraction = (median.coerceIn(0.0, 10.0) / 10.0).toFloat()
    return Color(red = 0.25f + 0.6f * fraction, green = 0.45f * (1f - fraction), blue = 0.55f * (1f - fraction))
}

private fun emotionColor(name: String): Color {
    val palette = listOf(
        Color(0xFF33658A),
        Color(0xFFC46B3A),
        Color(0xFF3E7C59),
        Color(0xFF7A4E8A),
        Color(0xFF8C4A4A),
        Color(0xFF3F6F8C),
    )
    return palette[abs(name.hashCode()) % palette.size]
}

private fun axisLine(axis: StateGroupAxisSummary): String {
    return "${axis.axisId} · observed ${axis.observed} · missing ${axis.missing} · conflicting ${axis.conflicting} · " +
        "minimum ${formatGroup(axis.minimum)} · first quartile ${formatGroup(axis.firstQuartile)} · " +
        "median ${formatGroup(axis.median)} · mean ${formatGroup(axis.mean)} · " +
        "third quartile ${formatGroup(axis.thirdQuartile)} · maximum ${formatGroup(axis.maximum)}"
}

private fun edgeKey(edge: StateGroupEdge): String = "${edge.sourceAlias}>${edge.targetAlias}"

@Composable
private fun Disclosure(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hidden = if (title.startsWith("Show ")) "Hide ${title.removePrefix("Show ")}" else "Hide ${title.replaceFirstChar { it.lowercase() }}"
    TextButton(onClick = { open = !open }) { Text(if (open) hidden else title) }
    if (open) content()
}

private fun durationText(duration: StateGroupNumberSummary?): String {
    if (duration == null) return "No completed duration."
    return "Duration minimum ${formatRecordedDuration(duration.minimum)}, median ${formatRecordedDuration(duration.median)}, mean ${formatRecordedDuration(duration.mean)}, maximum ${formatRecordedDuration(duration.maximum)}."
}

private fun groupMark(selected: Boolean, label: String): String = if (selected) "● $label" else label

private fun formatGroup(value: Double?): String {
    if (value == null || value.isNaN()) return "absent"
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return rounded.toString()
}

private val GROUP_COLORS = listOf("dominant emotion", "mood", "energy", "confidence", "anxiety")
