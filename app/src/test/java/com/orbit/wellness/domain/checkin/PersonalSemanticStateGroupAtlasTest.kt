package com.orbit.wellness.domain.checkin

import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PersonalSemanticStateGroupAtlasTest {
    @Test
    fun hierarchyIsStableNestedAndBreaksTiesByMemberIdentity() {
        val similarity = mapOf(
            pair("a", "b") to 0.9,
            pair("a", "c") to 0.2,
            pair("b", "c") to 0.2,
        )
        val forward = completeLinkage(listOf("c", "a", "b")) { left, right -> similarity.getValue(pair(left, right)) }
        val reversed = completeLinkage(listOf("b", "c", "a")) { left, right -> similarity.getValue(pair(left, right)) }
        assertEquals(forward.merges.map { it.members to it.similarity }, reversed.merges.map { it.members to it.similarity })
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b", "c")), forward.merges.map { it.members })
        assertEquals(0.9, forward.merges[0].similarity, 0.0)
        assertEquals(0.2, forward.merges[1].similarity, 0.0)
        assertNested(forward)
        assertThreshold(forward) { left, right -> similarity.getValue(pair(left, right)) }
        assertTrue(forward.cuts.zipWithNext().all { (tighter, looser) -> tighter.groups.size >= looser.groups.size })

        val tied = mapOf(
            pair("a", "b") to 0.5,
            pair("a", "c") to 0.5,
            pair("b", "c") to 0.5,
        )
        val first = completeLinkage(listOf("c", "b", "a")) { left, right -> tied.getValue(pair(left, right)) }
        val again = completeLinkage(listOf("b", "a", "c")) { left, right -> tied.getValue(pair(left, right)) }
        assertEquals(listOf("a", "b"), first.merges.first().members)
        assertEquals(first.merges.map { it.members }, again.merges.map { it.members })
        assertEquals(listOf(3, 1), first.cuts.map { it.groups.size })
    }

    @Test
    fun nearestEightPrefersTheTighterCutOnATie() {
        assertEquals(1, nearestEightIndex(listOf(10, 8, 6)))
        assertEquals(0, nearestEightIndex(listOf(9, 7)))
        assertEquals(0, nearestEightIndex(listOf(5)))
    }

    @Test
    fun groupsUseStateGeometryAndKeepBehaviorDirectional() {
        val built = fixture()
        val grouped = projectPersonalSemanticStateGroupAtlas(
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        assertEquals(
            listOf(
                PERSONAL_SEMANTIC_JOINT_EMBEDDING to LAYER_ZERO,
                PERSONAL_SEMANTIC_JOINT_EMBEDDING to RECIPROCAL_LAYER,
                PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to LAYER_ZERO,
                PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT to RECIPROCAL_LAYER,
            ),
            grouped.views.map { it.geometry to it.context },
        )
        assertFalse(grouped.causal || grouped.predictive || grouped.suitableForRecommendation)
        assertTrue(grouped.judgments.any { it.contains("Not causal") })
        assertTrue(grouped.judgments.any { it.contains("not graph radius 2") })
        grouped.views.forEach { view ->
            assertTrue(view.displayDefaultReason.contains("UI default"))
            assertTrue(view.displayDefaultReason.contains("Not a selected partition"))
            assertFalse(view.displayDefaultReason.contains("optimal"))
            assertEquals(0, view.displayDefaultCutIndex)
            view.ungrouped.forEach { assertEquals("no usable coordinates", it.reason) }
            view.cuts.forEach { cut ->
                assertTrue(cut.groups.all { group -> group.members.none { it.id == "state:d" || it.id == "state:e" } })
                assertTrue(cut.groups.all { group -> group.members.all { it.id.startsWith("state:") } })
            }
        }

        val direct = grouped.views[0]
        val reciprocal = grouped.views[1]
        val distributional = grouped.views[2]
        val distributionalReciprocal = grouped.views[3]
        assertEquals(listOf("state:d", "state:e"), direct.ungrouped.map { it.id })
        assertEquals(listOf("state:d", "state:e"), reciprocal.ungrouped.map { it.id })
        assertEquals(listOf("state:c", "state:d", "state:e"), distributional.ungrouped.map { it.id })
        assertEquals(listOf("state:d", "state:e"), distributionalReciprocal.ungrouped.map { it.id })
        assertEquals(listOf(3, 2, 1), direct.cuts.map { it.groups.size })
        assertEquals(listOf(3, 1), reciprocal.cuts.map { it.groups.size })
        assertEquals(listOf(2, 1), distributional.cuts.map { it.groups.size })
        assertEquals(listOf(3, 1), distributionalReciprocal.cuts.map { it.groups.size })
        assertNull(direct.cuts[0].minimumSimilarity)
        assertEquals(1.0, checkNotNull(direct.cuts[1].minimumSimilarity), 0.0)
        assertEquals(0.0, checkNotNull(direct.cuts[2].minimumSimilarity), 0.0)
        assertEquals(1.0, checkNotNull(direct.cuts[1].minimumWithinSimilarity), 0.0)
        assertEquals(1.0, checkNotNull(direct.cuts[1].meanWithinSimilarity), 0.0)
        assertEquals(0.0, checkNotNull(direct.cuts[2].minimumWithinSimilarity), 0.0)
        assertEquals(1.0 / 3.0, checkNotNull(direct.cuts[2].meanWithinSimilarity), 1e-9)
        assertEquals(1.0, checkNotNull(reciprocal.cuts[1].minimumWithinSimilarity), 0.0)
        assertEquals(1.0, checkNotNull(reciprocal.cuts[1].meanWithinSimilarity), 0.0)
        assertEquals(0.0, checkNotNull(distributional.cuts[1].minimumWithinSimilarity), 0.0)
        assertEquals(0.0, checkNotNull(distributional.cuts[1].meanWithinSimilarity), 0.0)
        assertEquals(1.0, checkNotNull(distributionalReciprocal.cuts[1].minimumWithinSimilarity), 0.0)
        assertEquals(1.0, checkNotNull(distributionalReciprocal.cuts[1].meanWithinSimilarity), 0.0)

        val pair = direct.cuts[1]
        val together = pair.groups.single { it.members.size == 2 }
        val alone = pair.groups.single { it.members.size == 1 }
        assertEquals(listOf("state:a", "state:b"), together.members.map { it.id })
        assertEquals("state:c", alone.members.single().id)
        assertEquals("state:a", together.medoidId)
        assertEquals("G01", together.alias)
        assertEquals(listOf("G01", "G02"), together.tighterChildren)
        assertEquals("G01", together.looserParent)
        assertNull(together.continuesAs)
        val singletonC = direct.cuts[0].groups.single { it.members.single().id == "state:c" }
        assertEquals("G02", singletonC.continuesAs)
        assertNull(singletonC.looserParent)
        assertEquals(-0.5, checkNotNull(together.x), 1e-9)
        assertEquals(0.5, checkNotNull(alone.x), 1e-9)
        assertNull(together.y)
        assertNull(alone.y)
        assertEquals(0.0, checkNotNull(pair.layoutStress), 1e-9)
        assertEquals(1, pair.positiveDimensions)
        val singletonStress = checkNotNull(direct.cuts[0].layoutStress)
        assertTrue("singleton layout stress $singletonStress", singletonStress < 1e-6)
        val full = direct.cuts[2].groups.single()
        assertEquals(0.0, checkNotNull(full.x), 0.0)
        assertEquals(0.0, checkNotNull(full.y), 0.0)
        assertNull(direct.cuts[2].layoutStress)

        val mood = together.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(1, mood.observed)
        assertEquals(1, mood.missing)
        assertEquals(0, mood.conflicting)
        assertEquals(4.0, checkNotNull(mood.minimum), 0.0)
        assertEquals(4.0, checkNotNull(mood.mean), 0.0)
        assertEquals("Hopeful", together.dominantEmotion)
        assertEquals(2, together.dominantEmotionCount)
        assertEquals(1.0, together.emotions.single { it.name == "Hopeful" }.proportion, 0.0)
        assertTrue(together.carriedConcepts.any { it.label.contains("Hopeful") })
        assertFalse(together.carriedConcepts.any { it.label.contains("Run") })

        assertTrue(together.incoming.concepts.any { it.label.contains("Walk") })
        assertFalse(together.incoming.concepts.any { it.label.contains("Run") || it.label.contains("Coffee") })
        assertTrue(together.outgoing.concepts.any { it.label.contains("Walk") })
        assertTrue(together.outgoing.concepts.any { it.label.contains("Run") })
        assertTrue(together.outgoing.concepts.any { it.label.contains("Coffee") })
        assertTrue(together.incoming.quantities.isEmpty())
        val minutes = together.outgoing.quantities.single { it.unit == "minutes" }
        val cups = together.outgoing.quantities.single { it.unit == "cups" }
        assertEquals(listOf(30.0), minutes.values)
        assertEquals(listOf(2.0), cups.values)
        assertEquals("Run", minutes.label)
        assertEquals("Coffee", cups.label)

        val loop = pair.edges.single { it.selfLoop }
        val arrow = pair.edges.single { !it.selfLoop }
        assertEquals(1, loop.transitionCount)
        assertEquals(1, arrow.transitionCount)
        assertEquals("G01", loop.sourceAlias)
        assertEquals("G01", arrow.sourceAlias)
        assertEquals("G02", arrow.targetAlias)
        assertEquals(listOf(30.0), arrow.quantities.single { it.unit == "minutes" }.values)
        assertEquals(listOf(2.0), arrow.quantities.single { it.unit == "cups" }.values)
        val moodChange = arrow.axisChanges.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(0, moodChange.observed)
        assertEquals(1, moodChange.missing)
        assertNull(moodChange.minimum)
        assertEquals(1, pair.unresolvedTransitions.size)
        assertEquals("transition:d", pair.unresolvedTransitions.single().id)
        assertTrue(pair.unresolvedTransitions.single().reason.contains("boundary"))
        assertTrue(pair.edges.none { edge -> edge.transitions.any { it.id == "transition:d" } })
        val fullLoop = direct.cuts[2].edges.single()
        assertTrue(fullLoop.selfLoop)
        assertEquals(2, fullLoop.transitionCount)
        assertEquals(0, direct.cuts[0].edges.count { it.selfLoop })
        assertEquals(2, direct.cuts[0].edges.size)
        assertEquals(1, direct.cuts[0].unresolvedTransitions.size)
        assertEquals(0, reciprocal.cuts[0].edges.count { it.selfLoop })
        assertEquals(2, reciprocal.cuts[0].edges.size)
        assertTrue(reciprocal.cuts[1].edges.single().selfLoop)
        assertEquals(2, reciprocal.cuts[1].edges.single().transitionCount)
        assertEquals(2, distributional.cuts[0].unresolvedTransitions.size)
        assertEquals(1, distributional.cuts[0].edges.size)
        assertEquals(0, distributional.cuts[0].edges.count { it.selfLoop })
        assertEquals(1, distributional.cuts[1].edges.single().transitionCount)
        assertTrue(distributional.cuts[1].edges.single().selfLoop)
        assertEquals(2, distributionalReciprocal.cuts[0].edges.size)
        assertEquals(0, distributionalReciprocal.cuts[0].edges.count { it.selfLoop })
        assertEquals(2, distributionalReciprocal.cuts[1].edges.single().transitionCount)
        assertTrue(distributionalReciprocal.cuts[1].edges.single().selfLoop)

        val repeated = projectPersonalSemanticStateGroupAtlas(
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        assertEquals(memberSets(grouped), memberSets(repeated))
        val negated = projectPersonalSemanticStateGroupAtlas(
            negate(built.embedding),
            projectDistributionalContext(negate(built.embedding), projection = built.projection),
            built.graph,
            built.projection,
        )
        assertEquals(memberSets(grouped), memberSets(negated))

        val extraNodes = built.embedding.layers.map { layer ->
            layer.copy(
                occurrenceCount = layer.occurrenceCount + 9,
                nodes = layer.nodes + listOf(
                    EmbeddedNode("transition:extra", "transition", listOf(4.0, 9.0)),
                    EmbeddedNode("concept:ACTIVITY|extra|", "concept", listOf(8.0, 1.0)),
                ),
            )
        }
        val withExtras = projectPersonalSemanticStateGroupAtlas(
            built.embedding.copy(layers = extraNodes),
            built.distributional.copy(supportByConcept = mapOf("concept:ACTIVITY|extra|" to 40)),
            built.graph,
            built.projection,
        )
        assertEquals(memberSets(grouped), memberSets(withExtras))
    }

    @Test
    fun frozenGeometriesAndTheLabStayUntouched() {
        val history = history()
        val projection = projectPersonalSemanticOccurrences(history)
        val graph = projectAlternatingTemporalGraph(history)
        val embedding = projectJointSemanticEmbedding(
            projectReciprocalSignatures(projection, graph, retrospectiveVocabulary(projection)),
            graph,
            projection,
        )
        val distributional = projectDistributionalContext(embedding, projection = projection)
        val coordinates = embedding.layers.map { layer -> layer.nodes.map { it.id to it.coordinates.toList() } }
        val profiles = distributional.layers.map { layer -> layer.nodes.map { it.id to it.profile.toList() } }
        val grouped = projectPersonalSemanticStateGroupAtlas(embedding, distributional, graph, projection)
        assertEquals(coordinates, embedding.layers.map { layer -> layer.nodes.map { it.id to it.coordinates.toList() } })
        assertEquals(profiles, distributional.layers.map { layer -> layer.nodes.map { it.id to it.profile.toList() } })
        assertEquals(4, grouped.views.size)

        val source = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticStateGroupAtlas.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticStateGroupAtlas.kt",
        )
        assertTrue(source.contains(PERSONAL_SEMANTIC_STATE_GROUP_ATLAS))
        assertFalse(source.contains("projectJointSemanticEmbedding"))
        assertFalse(source.contains("projectDistributionalContext"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        assertFalse(source.contains("orientation"))
        listOf(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticJointEmbedding.kt",
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticDistributionalContext.kt",
        ).map { path -> File(path) }.filter { it.isFile }.forEach { file ->
            assertFalse(file.readText().contains(PERSONAL_SEMANTIC_STATE_GROUP_ATLAS))
        }
        val architecture = read("docs/architecture.md", "../docs/architecture.md")
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_STATE_GROUP_ATLAS))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
        val section = read(
            "src/main/java/com/orbit/wellness/ui/insights/SemanticAtlasSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/SemanticAtlasSection.kt",
        )
        assertTrue(section.contains("Raw projection audit"))
        assertTrue(section.contains("Visual proximity is projected"))
        assertTrue(section.contains("not graph radius 2"))
        val groupedUi = read(
            "src/main/java/com/orbit/wellness/ui/insights/GroupedStateAtlasSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/GroupedStateAtlasSection.kt",
        )
        assertTrue(groupedUi.contains("X and Y are layout directions only. Group membership and similarity come from the full-dimensional source geometry. Rotating or mirroring this graph does not change its meaning."))
        assertTrue(groupedUi.contains("not graph radius 2"))
        assertTrue(groupedUi.contains("UI default"))
        assertTrue(groupedUi.contains("Not a selected partition"))
        val lab = read(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        )
        assertTrue(lab.contains("SemanticAtlasSection"))
        assertFalse(lab.contains("Slider"))
    }

    private fun assertNested(result: LinkageResult) {
        result.cuts.zipWithNext().forEach { (tighter, looser) ->
            tighter.groups.forEach { group ->
                assertTrue(looser.groups.any { parent -> group.members.all { it in parent.members } })
            }
        }
    }

    private fun assertThreshold(result: LinkageResult, similarity: (String, String) -> Double) {
        result.cuts.forEach { cut ->
            val threshold = cut.minimumSimilarity
            cut.groups.forEach { group ->
                for (left in group.members.indices) {
                    for (right in left + 1 until group.members.size) {
                        val value = similarity(group.members[left], group.members[right])
                        if (threshold != null) assertTrue(value >= threshold)
                    }
                }
            }
        }
    }

    private fun memberSets(dataset: StateGroupAtlasDataset): List<List<List<List<String>>>> {
        return dataset.views.map { view ->
            view.cuts.map { cut -> cut.groups.map { group -> group.members.map { it.id } }.sortedBy { it.first() } }
        }
    }

    private fun negate(embedding: JointEmbeddingDataset): JointEmbeddingDataset {
        return embedding.copy(
            layers = embedding.layers.map { layer ->
                layer.copy(nodes = layer.nodes.map { node -> node.copy(coordinates = node.coordinates.map { -it }) })
            },
        )
    }

    private fun fixture(): Built {
        val dataset = history()
        val projection = projectPersonalSemanticOccurrences(dataset)
        val graph = projectAlternatingTemporalGraph(dataset)
        val direct = listOf(
            EmbeddedNode("state:a", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:b", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:c", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:d", "state", listOf(0.0, 0.0)),
        )
        val reciprocal = direct.map { node ->
            if (node.id == "state:c") node.copy(coordinates = listOf(1.0, 0.0)) else node
        }
        val embedding = JointEmbeddingDataset(
            vocabularyScope = RETROSPECTIVE_VOCABULARY_SCOPE,
            retrospective = true,
            method = "group atlas test",
            audit = EmbeddingAuditIndex(
                aliasByNode = mapOf(
                    "state:a" to "S01",
                    "state:b" to "S02",
                    "state:c" to "S03",
                    "state:d" to "S04",
                    "state:e" to "S05",
                    "transition:b" to "T01",
                    "transition:c" to "T02",
                    "transition:d" to "T03",
                ),
                canonicalByNode = mapOf(
                    "state:a" to "a",
                    "state:b" to "b",
                    "state:c" to "c",
                    "state:d" to "d",
                    "state:e" to "e",
                    "transition:b" to "b",
                    "transition:c" to "c",
                    "transition:d" to "d",
                ),
                labelByConcept = projection.links.associate { link -> conceptNodeId(link.concept) to link.label },
                incidentTransitions = emptyMap(),
                focalConcepts = emptyMap(),
            ),
            layers = listOf(
                layer(LAYER_ZERO, direct),
                layer(RECIPROCAL_LAYER, reciprocal),
            ),
            judgments = emptyList(),
        )
        return Built(
            embedding = embedding,
            distributional = projectDistributionalContext(embedding, projection = projection),
            graph = graph,
            projection = projection,
        )
    }

    private fun layer(name: String, nodes: List<EmbeddedNode>) = JointEmbeddingLayer(
        name = name,
        dimensionality = 2,
        occurrenceCount = nodes.size,
        unreliableBecause = "",
        omittedConstantPresence = emptyList(),
        quantityHeldBack = emptyList(),
        columns = listOf("x", "y"),
        nodes = nodes,
        edges = emptyList(),
    )

    private fun history(): CheckInAnalysisDatasetV2 {
        val states = listOf(
            state("a", 0L, 4, "Hopeful", "2026-01-15"),
            state("b", HOUR, null, "Hopeful", "2026-01-16"),
            state("c", 2 * HOUR, 6, "Calm", "2026-01-17"),
            state("d", 3 * HOUR, 1, "", "2026-01-18"),
        )
        val intervals = listOf(
            interval(
                end = "b",
                previous = "a",
                start = 0L,
                endAt = HOUR,
                activity = ActivityCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    HOUR,
                    listOf(ActivityObservationV2("walk", null, "Walk", HOUR)),
                ),
            ),
            interval(
                end = "c",
                previous = "b",
                start = HOUR,
                endAt = 2 * HOUR,
                workout = WorkoutCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    2 * HOUR,
                    listOf(WorkoutObservationV2("run", null, "Run", 30, 2 * HOUR)),
                ),
                usage = UsageCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    2 * HOUR,
                    listOf(UsageObservationV2("coffee", null, "Coffee", "recorded", "2 cups", 2 * HOUR)),
                ),
            ),
            ContextIntervalObservationV2(
                endingCheckInId = "d",
                previousCheckInId = null,
                windowStartAt = 0L,
                windowEndAt = 3 * HOUR,
                durationMillis = 3 * HOUR,
                startBasis = CheckInContextStartBasis.WAKE_TIME,
                completedAt = 3 * HOUR,
                activity = ActivityCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                diet = DietCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                usage = UsageCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
            ),
        )
        return CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())
    }

    private fun interval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout: WorkoutCategoryObservationV2 = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage: UsageCategoryObservationV2 = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = endAt,
        activity = activity,
        workout = workout,
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = usage,
    )

    private fun state(id: String, at: Long, mood: Int?, emotion: String, date: String): StateObservationV2 {
        val reading = mood?.let { AxisReadingV2(DefaultCheckInAxes.MOOD, it, it / 10.0) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = date,
            axes = if (reading == null) emptyMap() else mapOf(reading.axisId to reading),
            core = CoreAxisViewV2(reading, null, null, null),
            emotion = emotion,
        )
    }

    private fun pair(left: String, right: String): Pair<String, String> {
        return if (left < right) left to right else right to left
    }

    private fun read(vararg paths: String) = paths.map { File(it) }.first { it.isFile }.readText()

    private data class Built(
        val embedding: JointEmbeddingDataset,
        val distributional: DistributionalContextDataset,
        val graph: AlternatingTemporalGraph,
        val projection: PersonalSemanticOccurrenceProjection,
    )

    private companion object {
        const val HOUR = 3_600_000L
    }
}
