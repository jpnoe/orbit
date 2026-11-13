package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PersonalSemanticAtlasTest {
    @Test
    fun projectionIsDeterministicAndKeepsOrientation() {
        val first = classicalMds(
            mapOf(
                "b" to listOf(0.0, 1.0),
                "a" to listOf(1.0, 0.0),
            ),
        )
        val second = classicalMds(
            mapOf(
                "a" to listOf(1.0, 0.0),
                "b" to listOf(0.0, 1.0),
            ),
        )
        assertEquals(first.placed.getValue("a").x, second.placed.getValue("a").x, 1e-8)
        assertEquals(first.placed.getValue("b").x, second.placed.getValue("b").x, 1e-8)
        assertNull(first.placed.getValue("a").y)
        assertNull(first.placed.getValue("b").y)
        val sum = first.placed.getValue("a").x + first.placed.getValue("b").x
        if (abs(sum) > 1e-8) {
            assertTrue(sum > 0.0)
        } else {
            assertTrue(first.placed.getValue("a").x > 0.0)
        }
        assertNotEquals(first.placed.getValue("a").x, first.placed.getValue("b").x, 1e-6)
    }

    @Test
    fun identicalDirectionsCoincideAndATriangleKeepsItsNeighbors() {
        val identical = classicalMds(
            mapOf(
                "same-b" to listOf(1.0, 0.0),
                "other" to listOf(0.0, 1.0),
                "same-a" to listOf(1.0, 0.0),
            ),
        )
        val left = identical.placed.getValue("same-a")
        val right = identical.placed.getValue("same-b")
        assertEquals(left.x, right.x, 1e-6)
        assertEquals(left.y ?: 0.0, right.y ?: 0.0, 1e-6)
        assertEquals(0.0, hypot(left.x - right.x, (left.y ?: 0.0) - (right.y ?: 0.0)), 1e-6)

        val triangle = classicalMds(
            mapOf(
                "c" to listOf(0.0, 0.0, 1.0),
                "a" to listOf(1.0, 0.0, 0.0),
                "b" to listOf(0.0, 1.0, 0.0),
            ),
        )
        val triangleStress = checkNotNull(triangle.stress)
        assertTrue(triangleStress.isFinite())
        assertTrue(triangleStress < 1e-3)
        val distances = listOf("a" to "b", "b" to "c", "a" to "c").map { (from, to) ->
            val start = triangle.placed.getValue(from)
            val end = triangle.placed.getValue(to)
            hypot(start.x - end.x, (start.y ?: 0.0) - (end.y ?: 0.0))
        }
        assertEquals(distances[0], distances[1], 1e-3)
        assertEquals(distances[0], distances[2], 1e-3)
        if (triangle.tiedLeadingAxes) {
            assertTrue(triangle.placed.getValue("a").x > 0.0)
            assertEquals(0.0, triangle.placed.getValue("a").y ?: 0.0, 1e-3)
            assertTrue((triangle.placed.getValue("b").y ?: 0.0) >= -1e-3)
        }

        val line = classicalMds(
            mapOf(
                "far" to vector(1.3),
                "near" to vector(0.0),
                "mid" to vector(0.35),
            ),
        )
        val near = line.placed.getValue("near")
        val nearest = listOf("mid", "far").minBy { id ->
            val point = line.placed.getValue(id)
            hypot(near.x - point.x, (near.y ?: 0.0) - (point.y ?: 0.0))
        }
        assertEquals("mid", nearest)
        assertTrue(line.stress!!.isFinite())
    }

    @Test
    fun unusableVectorsStayOutOfThePlane() {
        val layout = classicalMds(
            mapOf(
                "zero" to listOf(0.0, 0.0),
                "missing" to listOf(Double.NaN, Double.NaN),
                "empty" to emptyList(),
                "kept" to listOf(1.0, 0.0),
                "other" to listOf(0.0, 1.0),
            ),
        )
        assertFalse(layout.placed.containsKey("zero"))
        assertFalse(layout.placed.containsKey("missing"))
        assertFalse(layout.placed.containsKey("empty"))
        assertTrue(layout.placed.containsKey("kept"))
    }

    @Test
    fun retentionUsesSourceSimilarityRatherThanThePlane() {
        val ids = listOf("a", "n1", "n2", "n3", "n4", "n5", "n6")
        val coordinates = ids.associateWith { id ->
            val position = if (id == "a") 0.0 else id.removePrefix("n").toDouble()
            SemanticAtlasCoordinate(position, 0.0)
        }
        val preference = mapOf("n6" to 0.9, "n5" to 0.8, "n4" to 0.7, "n3" to 0.6, "n2" to 0.5, "n1" to 0.1)
        val audits = sourceNeighborAudits(
            ids = ids,
            kinds = ids.associateWith { "state" },
            labels = ids.associateWith { it },
            similarity = { left, right ->
                val other = if (left == "a") right else if (right == "a") left else ""
                preference[other] ?: 0.0
            },
            coordinate = coordinates::getValue,
        )
        val neighbors = audits.getValue("a")
        assertEquals(listOf("n6", "n5", "n4", "n3", "n2"), neighbors.map { it.id })
        assertFalse(neighbors.single { it.id == "n6" }.retained)
        assertEquals(6, neighbors.single { it.id == "n6" }.projectedRank)
        assertTrue(neighbors.single { it.id == "n2" }.retained)
        assertEquals(0.5, neighbors.single { it.id == "n2" }.similarity, 0.0)
    }

    @Test
    fun supportChangesLabelPriorityAndNotPosition() {
        val high = samplePoint("high", "concept", 5)
        val low = samplePoint("low", "concept", 1)
        val overlap = SemanticAtlasLabelBox(0f, 0f, 12f, 12f)
        val boxes = mapOf("high" to overlap, "low" to SemanticAtlasLabelBox(1f, 1f, 13f, 13f))
        val shown = semanticAtlasLabelsToDraw(listOf(low, high), boxes, null)
        assertTrue(shown.contains("high"))
        assertFalse(shown.contains("low"))
        val forced = semanticAtlasLabelsToDraw(listOf(low, high), boxes, "low")
        assertTrue(forced.contains("low"))

        val separate = mapOf(
            "high" to SemanticAtlasLabelBox(0f, 0f, 4f, 4f),
            "low" to SemanticAtlasLabelBox(20f, 20f, 24f, 24f),
        )
        val both = semanticAtlasLabelsToDraw(listOf(low, high), separate, null)
        assertEquals(setOf("high", "low"), both)

        val atlas = handcraftedAtlas(support = 1)
        val boosted = handcraftedAtlas(support = 40)
        atlas.views.zip(boosted.views).forEach { (left, right) ->
            assertEquals(left.points.map { it.id }, right.points.map { it.id })
            left.points.zip(right.points).forEach { (before, after) ->
                assertEquals(before.x, after.x)
                assertEquals(before.y, after.y)
                assertEquals(before.label, after.label)
            }
        }
        val concept = atlas.views.first().points.single { it.id == CONCEPT }
        val moved = boosted.views.first().points.single { it.id == CONCEPT }
        assertEquals(1, concept.support)
        assertEquals(40, moved.support)
        assertTrue(concept.projected)
        val zero = atlas.views.first().points.single { it.id == "state:zero" }
        assertFalse(zero.projected)
        assertNull(zero.x)
        assertNull(zero.y)
        assertEquals("no usable coordinates", zero.placement)
        assertEquals(4, atlas.views.size)
    }

    @Test
    fun recordedHistoryKeepsFourViewsEdgesAndTheFrozenGeometries() {
        val history = history()
        val projection = projectPersonalSemanticOccurrences(history)
        val graph = projectAlternatingTemporalGraph(history)
        val embedding = projectJointSemanticEmbedding(
            projectReciprocalSignatures(projection, graph, retrospectiveVocabulary(projection)),
            graph,
            projection,
        )
        val distributional = projectDistributionalContext(
            embedding = embedding,
            supportByConcept = embedding.audit.labelByConcept.keys.associateWith { 1 },
            projection = projection,
        )
        val directBefore = embedding.layers.map { layer -> layer.nodes.map { it.id to it.coordinates.toList() } }
        val profileBefore = distributional.layers.map { layer -> layer.nodes.map { it.id to it.profile.toList() } }
        val atlas = projectPersonalSemanticAtlas(embedding, distributional, graph, projection)
        val directAfter = embedding.layers.map { layer -> layer.nodes.map { it.id to it.coordinates.toList() } }
        val profileAfter = distributional.layers.map { layer -> layer.nodes.map { it.id to it.profile.toList() } }
        assertEquals(directBefore, directAfter)
        assertEquals(profileBefore, profileAfter)

        assertEquals(
            listOf(
                PERSONAL_SEMANTIC_JOINT_EMBEDDING to LAYER_ZERO,
                PERSONAL_SEMANTIC_JOINT_EMBEDDING to RECIPROCAL_LAYER,
                PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to LAYER_ZERO,
                PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to RECIPROCAL_LAYER,
            ),
            atlas.views.map { it.geometry to it.context },
        )
        assertTrue(atlas.retrospective)
        assertFalse(atlas.causal || atlas.predictive || atlas.suitableForRecommendation)
        val direct = atlas.views.first()
        val stress = checkNotNull(direct.quality.stress)
        assertTrue(stress.isFinite())
        assertTrue(stress >= 0.0)
        assertTrue(direct.quality.projected > 0)
        assertTrue(direct.quality.smallSampleWarning.contains("Small-sample"))
        assertTrue(direct.quality.retrospectiveFullHistory)
        assertEquals(embedding.layer(LAYER_ZERO).dimensionality, direct.sourceDimensionality)
        assertEquals(distributional.layer(LAYER_ZERO).dimensionality, atlas.views[2].sourceDimensionality)

        val anchor = direct.points.single { it.alias == "S01" }
        assertTrue(anchor.projected)
        val layer = embedding.layer(LAYER_ZERO)
        val placed = direct.points.filter { it.projected }.map { it.id }
        val expected = placed.filter { it != anchor.id }
            .map { id -> id to layer.similarity(anchor.id, id).coerceIn(-1.0, 1.0) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(5)
        assertEquals(expected.map { it.first }, anchor.neighbors.map { it.id })
        anchor.neighbors.forEach { neighbor ->
            assertEquals(layer.similarity(anchor.id, neighbor.id).coerceIn(-1.0, 1.0), neighbor.similarity, 1e-9)
        }

        fun idOf(alias: String) = direct.points.single { it.alias == alias }.id
        assertTrue(direct.edges.any { it.fromId == idOf("S01") && it.toId == idOf("T01") })
        assertTrue(direct.edges.any { it.fromId == idOf("T01") && it.toId == idOf("S02") })
        assertTrue(direct.points.any { it.label.contains("Walk") })
        atlas.views.flatMap { it.points }.forEach { point ->
            assertFalse(point.label.contains(OPTION_ID))
            assertFalse(point.alias.contains(OPTION_ID))
            point.neighbors.forEach { assertFalse(it.label.contains(OPTION_ID)) }
            point.incidentLabels.forEach { assertFalse(it.contains(OPTION_ID)) }
            point.carriedLabels.forEach { assertFalse(it.contains(OPTION_ID)) }
        }

        val boosted = distributional.copy(
            supportByConcept = distributional.supportByConcept.mapValues { it.value + 7 },
        )
        val again = projectPersonalSemanticAtlas(embedding, boosted, graph, projection)
        direct.points.zip(again.views.first().points).forEach { (before, after) ->
            assertEquals(before.x, after.x)
            assertEquals(before.y, after.y)
        }
    }

    @Test
    fun theAtlasStaysOutsideTheFrozenGeometriesAndTheLabCanShowIt() {
        val source = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticAtlas.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticAtlas.kt",
        )
        assertTrue(source.contains(PERSONAL_SEMANTIC_ATLAS))
        assertFalse(source.contains("projectJointSemanticEmbedding"))
        assertFalse(source.contains("projectDistributionalContext"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        val embedding = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
        )
        assertFalse(embedding.contains(PERSONAL_SEMANTIC_ATLAS))
        val distributional = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
        )
        assertFalse(distributional.contains(PERSONAL_SEMANTIC_ATLAS))
        val architecture = read("docs/architecture.md", "../docs/architecture.md")
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_ATLAS))
        val section = read(
            "src/main/java/com/orbit/wellness/ui/insights/SemanticAtlasSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/SemanticAtlasSection.kt",
        )
        assertTrue(section.contains("Semantic atlas"))
        assertTrue(section.contains("not graph radius 2"))
        assertTrue(section.contains("Visual proximity is projected"))
        assertFalse(section.contains("Text(point.id"))
        assertFalse(section.contains("\${point.id}"))
        val lab = read(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        )
        assertTrue(lab.contains("SemanticAtlasSection"))
    }

    private fun handcraftedAtlas(support: Int): SemanticAtlasDataset {
        val zero = listOf(
            EmbeddedNode("state:a", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:b", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:zero", "state", listOf(0.0, 0.0)),
            EmbeddedNode("transition:t", "transition", listOf(0.4, 0.9)),
            EmbeddedNode(CONCEPT, "concept", listOf(1.0, Double.NaN)),
        )
        val reciprocal = zero.map { node ->
            if (node.id == "state:a") node.copy(coordinates = listOf(0.0, 1.0)) else node
        }
        val embedding = JointEmbeddingDataset(
            vocabularyScope = RETROSPECTIVE_VOCABULARY_SCOPE,
            retrospective = true,
            method = "atlas test",
            audit = EmbeddingAuditIndex(
                aliasByNode = mapOf(
                    "state:a" to "S01",
                    "state:b" to "S02",
                    "state:zero" to "S03",
                    "transition:t" to "T01",
                ),
                canonicalByNode = mapOf(
                    "state:a" to "a",
                    "state:b" to "b",
                    "state:zero" to "zero",
                    "transition:t" to "t",
                ),
                labelByConcept = mapOf(CONCEPT to "hope", "concept:EMOTION|quiet|" to "Quiet"),
                incidentTransitions = emptyMap(),
                focalConcepts = emptyMap(),
            ),
            layers = listOf(layer(LAYER_ZERO, zero), layer(RECIPROCAL_LAYER, reciprocal)),
            judgments = emptyList(),
        )
        val distributional = projectDistributionalContext(
            embedding = embedding,
            supportByConcept = mapOf(CONCEPT to support),
        )
        return projectPersonalSemanticAtlas(
            embedding = embedding,
            distributional = distributional,
            graph = AlternatingTemporalGraph(emptyList(), emptyList(), emptyList(), emptyList()),
        )
    }

    private fun layer(name: String, nodes: List<EmbeddedNode>) = JointEmbeddingLayer(
        name = name,
        dimensionality = 2,
        occurrenceCount = 3,
        unreliableBecause = "",
        omittedConstantPresence = emptyList(),
        quantityHeldBack = emptyList(),
        columns = listOf("c0", "c1"),
        nodes = nodes,
        edges = emptyList(),
    )

    private fun samplePoint(id: String, kind: String, support: Int) = SemanticAtlasPoint(
        id = id,
        kind = kind,
        alias = id,
        label = id,
        projected = true,
        placement = "projected",
        x = 0.0,
        y = 0.0,
        support = support,
        emotion = null,
        axisValues = emptyMap(),
        neighbors = emptyList(),
        incidentLabels = emptyList(),
        carriedLabels = emptyList(),
    )

    private fun history(): CheckInAnalysisDatasetV2 {
        val states = listOf(
            state("a", 0L, 2, "calm"),
            state("b", HOUR, 8, "tense"),
            state("c", 2 * HOUR, 4, "calm"),
        )
        val intervals = listOf(
            link("b", "a", 0L, HOUR, "Walk"),
            link("c", "b", HOUR, 2 * HOUR, ""),
        )
        return CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())
    }

    private fun link(end: String, previous: String, start: Long, endAt: Long, activity: String): ContextIntervalObservationV2 {
        val category = if (activity.isEmpty()) {
            ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList())
        } else {
            ActivityCategoryObservationV2(
                CategoryObservationKindV2.ENTRIES,
                endAt,
                listOf(ActivityObservationV2("act-$end", OPTION_ID, activity, endAt)),
            )
        }
        return ContextIntervalObservationV2(
            endingCheckInId = end,
            previousCheckInId = previous,
            windowStartAt = start,
            windowEndAt = endAt,
            durationMillis = endAt - start,
            startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
            completedAt = endAt,
            activity = category,
            workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
            diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
            usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        )
    }

    private fun state(id: String, at: Long, mood: Int, emotion: String): StateObservationV2 {
        val reading = AxisReadingV2(DefaultCheckInAxes.MOOD, mood, mood / 10.0)
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-01-15",
            axes = mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
            emotion = emotion,
        )
    }

    private fun vector(angle: Double) = listOf(cos(angle), sin(angle))

    private fun read(vararg paths: String) = paths.map { File(it) }.first { it.isFile }.readText()

    private companion object {
        const val HOUR = 3_600_000L
        const val OPTION_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val CONCEPT = "concept:EMOTION|hope|"
    }
}
