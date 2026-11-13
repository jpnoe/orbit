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

class PersonalSemanticRegionDescriptionTest {
    @Test
    fun pureComparisonsKeepSignAndRefuseEmptyVariation() {
        assertEquals(
            -1.0,
            checkNotNull(pointBiserial(listOf(4.0 to true, 8.0 to false, 8.0 to false, 8.0 to false))),
            1e-9,
        )
        assertTrue(checkNotNull(pointBiserial(listOf(1.0 to false, 2.0 to true))) > 0.0)
        assertNull(pointBiserial(listOf(1.0 to true, 2.0 to true)))
        assertNull(pointBiserial(listOf(5.0 to true, 5.0 to false)))
        assertNull(pointBiserial(listOf(1.0 to true)))
        assertEquals(
            1.0,
            checkNotNull(
                phiCorrelation(
                    listOf(true, true, false, false, false),
                    listOf(true, true, false, false, false),
                ),
            ),
            1e-9,
        )
        assertEquals("+51.7 points", formatRatePoints(3.0 / 5.0 - 2.0 / 24.0))
        assertEquals("+100.0 points", formatRatePoints(1.0))
    }

    @Test
    fun durationsAndAliasesStayReadable() {
        assertEquals("2h 9m", formatRecordedDuration(2 * HOUR + 9 * 60_000L))
        assertEquals("0s", formatRecordedDuration(0L))
        assertEquals("45s", formatRecordedDuration(45_000L))
        assertEquals("S31", unduplicatedRecordLabel("S31", "S31 · S31"))
        assertEquals("S31 · Hopeful", unduplicatedRecordLabel("S31", "S31 · Hopeful"))
        assertEquals("T05", unduplicatedRecordLabel("T05", "T05"))
        assertEquals("T05", unduplicatedRecordLabel("T05", "T05 · T05"))
    }

    @Test
    fun selectedRegionUsesExactInsideOutsideAndTransitionSets() {
        val built = associationFixture()
        val atlas = projectPersonalSemanticStateGroupAtlas(
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        val before = fingerprint(atlas)
        val coordinates = built.embedding.layers.map { layer -> layer.nodes.map { it.coordinates.toList() } }
        val described = describePersonalSemanticRegions(
            atlas,
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        val repeated = describePersonalSemanticRegions(
            atlas,
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        assertEquals(before, fingerprint(atlas))
        assertEquals(coordinates, built.embedding.layers.map { layer -> layer.nodes.map { it.coordinates.toList() } })
        assertEquals(regionOrder(described), regionOrder(repeated))
        assertFalse(described.causal || described.predictive || described.suitableForRecommendation)
        assertTrue(described.judgments.any { it.contains("does not calculate a p-value") })
        assertTrue(described.judgments.any { it == REGION_SOURCE_DISTANCE_NOTE })

        val direct = described.views.single { it.geometry == PERSONAL_SEMANTIC_JOINT_EMBEDDING && it.context == LAYER_ZERO }
        val reciprocal = described.views.single { it.geometry == PERSONAL_SEMANTIC_JOINT_EMBEDDING && it.context == RECIPROCAL_LAYER }
        val distributional = described.views.single { it.geometry == PERSONAL_SEMANTIC_DISTRIBUTIONAL_CONTEXT && it.context == LAYER_ZERO }
        assertEquals(REGION_DIRECT_CIRCULARITY, direct.circularity)
        assertFalse(direct.circularity.contains("Incoming and outgoing context contributed"))
        assertEquals(REGION_RECIPROCAL_CIRCULARITY, reciprocal.circularity)
        assertEquals(REGION_UNIVERSAL_DISCLOSURE, direct.disclosure)
        assertEquals(REGION_DISTRIBUTIONAL_CIRCULARITY, distributional.circularity)
        assertTrue(distributional.circularity.contains("not graph radius 2"))

        val directAtlas = atlas.views.single { it.geometry == PERSONAL_SEMANTIC_JOINT_EMBEDDING && it.context == LAYER_ZERO }
        val pair = direct.cuts[1]
        val hopeful = pair.regions.single { it.alias == "G01" }
        val calm = pair.regions.single { it.alias == "G02" }
        assertEquals(listOf("state:a", "state:b"), hopeful.insideIds)
        assertEquals(listOf("state:c", "state:f", "state:h"), hopeful.outsideIds)
        assertEquals(listOf("state:d", "state:e"), hopeful.ungroupedIds)
        assertFalse(hopeful.insideIds.contains("state:d") || hopeful.outsideIds.contains("state:d"))

        val mood = hopeful.axes.single { it.axisId == DefaultCheckInAxes.MOOD }
        assertEquals(1, mood.inside.observed)
        assertEquals(1, mood.inside.missing)
        assertEquals(4.0, checkNotNull(mood.inside.minimum), 0.0)
        assertEquals(4.0, checkNotNull(mood.inside.median), 0.0)
        assertEquals(3, mood.outside.observed)
        assertEquals(8.0, checkNotNull(mood.outside.minimum), 0.0)
        assertEquals(8.0, checkNotNull(mood.outside.median), 0.0)
        assertEquals(-1.0, checkNotNull(mood.correlation), 1e-9)
        assertTrue(mood.concise.contains("inside median 4 (IQR 4–4)"))
        assertTrue(mood.concise.contains("outside median 8 (IQR 8–8)"))
        assertTrue(mood.concise.contains("membership correlation -1.00"))
        val energy = hopeful.axes.single { it.axisId == DefaultCheckInAxes.ENERGY }
        assertEquals(0, energy.inside.observed)
        assertNull(energy.inside.minimum)
        assertNull(energy.correlation)
        assertTrue(energy.concise.contains("membership correlation unavailable"))

        val hopefulEmotion = hopeful.characterization.single { it.label.contains("Hopeful") }
        assertEquals(2, hopefulEmotion.insideCount)
        assertEquals(2, hopefulEmotion.insideTotal)
        assertEquals(0, hopefulEmotion.outsideCount)
        assertEquals(3, hopefulEmotion.outsideTotal)
        assertEquals(1.0, checkNotNull(hopefulEmotion.rateDifference), 0.0)
        assertEquals(1.0, checkNotNull(hopefulEmotion.phi), 1e-9)
        assertFalse(hopefulEmotion.tentative)
        assertEquals(listOf(hopefulEmotion.identity), hopeful.characterizationConcise.map { it.identity })
        assertEquals(REGION_HEAD_CHARACTER, hopeful.characterHeading)
        assertFalse(hopeful.characterHeading.contains("caused") || hopeful.characterHeading.contains("defined objectively"))
        val calmEmotion = hopeful.characterization.single { it.label.contains("Calm") }
        assertEquals(0, calmEmotion.insideCount)
        assertEquals(-1.0, checkNotNull(calmEmotion.phi), 1e-9)
        assertTrue(hopeful.characterizationConcise.none { it.insideCount < 2 })

        assertEquals(1, hopeful.entryOpportunities)
        assertEquals(1, hopeful.entered)
        assertTrue(hopeful.entry.isEmpty())
        assertEquals(4, hopeful.outgoing)
        assertEquals(1, hopeful.maintained)
        assertEquals(3, hopeful.exited)
        assertEquals(0, hopeful.boundaryEndings)
        assertEquals(0, hopeful.ungroupedSources)
        assertEquals(0, hopeful.ungroupedDestinations)
        assertEquals(5, hopeful.entryOpportunities + hopeful.outgoing)
        assertEquals(1, directAtlas.cuts[1].unresolvedTransitions.size)

        assertEquals(4, calm.entryOpportunities)
        assertEquals(3, calm.entered)
        assertEquals(1, calm.outgoing)
        assertEquals(0, calm.maintained)
        assertEquals(1, calm.exited)
        assertEquals(5, calm.entryOpportunities + calm.outgoing)
        assertTrue(calm.exit.isEmpty())
        assertTrue(calm.maintenance.isEmpty())

        val runEntry = calm.entry.single { it.label.contains("Run") }
        val walkEntry = calm.entry.single { it.label.contains("Walk") }
        val coffeeEntry = calm.entry.single { it.label.contains("Coffee") }
        val soupEntry = calm.entry.single { it.label.contains("Soup") }
        assertFalse(runEntry.identity == coffeeEntry.identity || runEntry.identity == soupEntry.identity)
        assertEquals(3, runEntry.presentCount)
        assertEquals(3, runEntry.presentOutcome)
        assertEquals(1, runEntry.absentCount)
        assertEquals(0, runEntry.absentOutcome)
        assertEquals(1.0, checkNotNull(runEntry.rateDifference), 1e-9)
        assertEquals(3, runEntry.coverageCount)
        assertEquals(3, runEntry.coverageTotal)
        assertFalse(runEntry.tentative)
        assertTrue(runEntry.concise.contains("entered 3/3 when present vs 0/1 when absent"))
        assertTrue(runEntry.concise.contains("difference +100.0 points"))
        assertTrue(runEntry.concise.contains("entry coverage 3/3"))
        assertEquals(1, walkEntry.presentCount)
        assertEquals(0, walkEntry.presentOutcome)
        assertEquals(2, walkEntry.absentCount)
        assertEquals(2, walkEntry.absentOutcome)
        assertTrue(walkEntry.tentative)
        assertTrue(walkEntry.concise.contains(REGION_TENTATIVE))
        assertTrue(walkEntry.evidence.contains("unknown excluded 1"))
        assertTrue(walkEntry.concise.contains("entered 0/1 when present vs 2/2 when absent"))
        assertEquals(3, soupEntry.absentCount)
        assertTrue(soupEntry.tentative)
        assertEquals(
            listOf(runEntry.identity, walkEntry.identity, coffeeEntry.identity),
            calm.entryConcise.map { it.identity },
        )
        assertTrue(calm.entryConcise.none { it.label.contains("Soup") })
        assertEquals(REGION_HEAD_ENTRY, calm.entryHeading)

        val walkStay = hopeful.maintenance.single { it.label.contains("Walk") }
        val runStay = hopeful.maintenance.single { it.label.contains("Run") }
        assertEquals(1, walkStay.presentOutcome)
        assertEquals(1, walkStay.presentCount)
        assertEquals(0, runStay.presentOutcome)
        assertEquals(3, runStay.presentCount)
        assertEquals(1, runStay.absentOutcome)
        assertTrue(walkStay.concise.contains("remained 1/1 when present vs 0/2 when absent"))
        assertTrue(walkStay.concise.contains("self-loop coverage 1/1"))
        assertTrue(walkStay.concise.contains(REGION_TENTATIVE))
        assertFalse(runStay.tentative)
        assertEquals(REGION_HEAD_MAINTENANCE, hopeful.maintenanceHeading)

        val runExit = hopeful.exit.single { it.label.contains("Run") }
        val walkExit = hopeful.exit.single { it.label.contains("Walk") }
        assertEquals(3, runExit.presentOutcome)
        assertEquals(3, runExit.presentCount)
        assertEquals(0, runExit.absentOutcome)
        assertEquals(1, runExit.absentCount)
        assertEquals(0, walkExit.presentOutcome)
        assertTrue(walkExit.tentative)
        assertEquals(listOf(RegionDestinationCount("G02", 3)), runExit.destinations)
        assertTrue(runExit.evidence.contains("destinations G02 3"))
        assertTrue(runExit.concise.contains("exited 3/3 when present vs 0/1 when absent"))
        assertTrue(runExit.concise.contains("exit coverage 3/3"))
        assertEquals(REGION_HEAD_EXIT, hopeful.exitHeading)
        assertTrue(hopeful.exitConcise.first().label.contains("Run"))

        val atlasHopeful = directAtlas.cuts[1].groups.single { it.alias == "G01" }
        val atlasCalm = directAtlas.cuts[1].groups.single { it.alias == "G02" }
        val minutes = atlasHopeful.outgoing.quantities.single { it.unit == "minutes" }
        val cups = atlasHopeful.outgoing.quantities.single { it.unit == "cups" }
        assertEquals(setOf(10.0, 30.0, 45.0), minutes.values.toSet())
        assertEquals(listOf(2.0), cups.values)
        assertFalse(minutes.label == cups.label)
        val loop = directAtlas.cuts[1].edges.single { it.selfLoop }
        assertEquals("2h 9m", formatRecordedDuration(checkNotNull(loop.duration).minimum))
        assertProximity(directAtlas.cuts[1].groups, hopeful, built.embedding.layer(LAYER_ZERO)::similarity)
        assertProximity(directAtlas.cuts[1].groups, calm, built.embedding.layer(LAYER_ZERO)::similarity)
        assertEquals(1.0, checkNotNull(hopeful.nearest.single().sourceDistance), 1e-9)
        assertEquals(abs(checkNotNull(atlasHopeful.x) - checkNotNull(atlasCalm.x)), checkNotNull(hopeful.nearest.single().layoutDistance), 1e-9)
        direct.cuts[0].regions.forEach { region ->
            assertProximity(directAtlas.cuts[0].groups, region, built.embedding.layer(LAYER_ZERO)::similarity)
        }
        assertTrue(hopeful.medoidLabel.startsWith("S01 ·"))
        assertFalse(hopeful.medoidLabel.contains("S01 · S01"))
    }

    @Test
    fun lineageNamesChildrenParentsAndContinuedAliases() {
        val built = lineageFixture()
        val atlas = projectPersonalSemanticStateGroupAtlas(
            built.embedding,
            built.distributional,
            built.graph,
            built.projection,
        )
        val directAtlas = atlas.views.single { it.geometry == PERSONAL_SEMANTIC_JOINT_EMBEDDING && it.context == LAYER_ZERO }
        val pair = directAtlas.cuts[1].groups.single { it.members.size == 2 }
        val singleton = directAtlas.cuts[0].groups.single { it.members.single().id == "state:c" }
        val pairLines = regionLineage(directAtlas, 1, pair)
        val singletonLines = regionLineage(directAtlas, 0, singleton)
        assertEquals(
            "At the next tighter cut: 1-state child G01 and 1-state child G02, merged at 1.",
            pairLines[0],
        )
        assertEquals("At the next looser cut: joins 3-state parent G01 at 0.", pairLines[1])
        assertTrue(pairLines.last().startsWith("Hierarchy node "))
        assertEquals("At the next looser cut: the same 1 state continues as G02.", singletonLines[1])
        assertTrue(pairLines.none { it.contains("splits into") })
        assertTrue(singletonLines.none { it.contains("continues as G03") })
    }

    @Test
    fun descriptionDoesNotReachIntoFrozenGeometry() {
        val source = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticRegionDescription.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticRegionDescription.kt",
        )
        assertTrue(source.contains(PERSONAL_SEMANTIC_REGION_DESCRIPTION))
        assertFalse(source.contains("completeLinkage"))
        assertFalse(source.contains("classicalMdsFromDistances"))
        assertFalse(source.contains("projectJointSemanticEmbedding"))
        assertFalse(source.contains("projectDistributionalContext"))
        assertFalse(source.contains("projectNullTrajectoryLearner"))
        assertFalse(source.contains("projectPersonalSemanticStateGroupAtlas"))
        val atlasSource = read(
            "src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticStateGroupAtlas.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/PersonalSemanticStateGroupAtlas.kt",
        )
        assertFalse(atlasSource.contains(PERSONAL_SEMANTIC_REGION_DESCRIPTION))
        val architecture = read("docs/architecture.md", "../docs/architecture.md")
        assertTrue(architecture.contains(PERSONAL_SEMANTIC_REGION_DESCRIPTION))
        assertTrue(architecture.contains("bidirectional-smoothed-state"))
        val groupedUi = read(
            "src/main/java/com/orbit/wellness/ui/insights/GroupedStateAtlasSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/GroupedStateAtlasSection.kt",
        )
        assertTrue(groupedUi.contains("Show evidence"))
        assertTrue(groupedUi.contains("Technical details"))
        assertTrue(groupedUi.contains("unduplicatedRecordLabel"))
        assertTrue(groupedUi.contains("formatRecordedDuration"))
        assertTrue(groupedUi.contains("regionLineage"))
        assertFalse(groupedUi.contains("Splits into"))
        val lab = read(
            "src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/PredictionLabSection.kt",
        )
        assertFalse(lab.contains("Slider"))
        assertFalse(lab.contains("recommend"))
    }

    private fun assertProximity(
        groups: List<StateGroup>,
        region: RegionDescription,
        similarity: (String, String) -> Double,
    ) {
        val group = groups.single { it.alias == region.alias }
        region.nearest.forEach { neighbor ->
            val other = groups.single { it.alias == neighbor.alias }
            var distance = 0.0
            var mean = 0.0
            var minimum = Double.POSITIVE_INFINITY
            var pairs = 0
            group.members.forEach { left ->
                other.members.forEach { right ->
                    val value = similarity(left.id, right.id).coerceIn(-1.0, 1.0)
                    distance += 1.0 - value
                    mean += value
                    minimum = minOf(minimum, value)
                    pairs += 1
                }
            }
            assertEquals(distance / pairs, neighbor.sourceDistance, 1e-9)
            assertEquals(mean / pairs, neighbor.meanSimilarity, 1e-9)
            assertEquals(minimum, neighbor.minimumSimilarity, 1e-9)
            val dx = checkNotNull(group.x) - checkNotNull(other.x)
            val expectedLayout = if (group.y == null || other.y == null) {
                abs(dx)
            } else {
                kotlin.math.sqrt(dx * dx + (checkNotNull(group.y) - checkNotNull(other.y)) * (checkNotNull(group.y) - checkNotNull(other.y)))
            }
            assertEquals(expectedLayout, checkNotNull(neighbor.layoutDistance), 1e-6)
            assertTrue(neighbor.concise.contains("source distance"))
            assertTrue(neighbor.concise.contains("layout distance"))
        }
    }

    private fun fingerprint(dataset: StateGroupAtlasDataset): List<List<List<String>>> {
        return dataset.views.map { view ->
            view.cuts.map { cut ->
                cut.groups.map { group ->
                    group.alias + ":" + group.members.joinToString(",") { it.id } + "@" + group.x + "," + group.y
                }
            }
        }
    }

    private fun regionOrder(dataset: RegionDescriptionDataset): List<List<List<String>>> {
        return dataset.views.map { view ->
            view.cuts.map { cut -> cut.regions.map { it.alias + ":" + it.entry.joinToString(",") { behavior -> behavior.identity } } }
        }
    }

    private fun associationFixture(): Built {
        val states = listOf(
            state("a", 0L, 4, "Hopeful", "2026-01-15"),
            state("b", HOUR, null, "Hopeful", "2026-01-16"),
            state("c", 2 * HOUR, 8, "Calm", "2026-01-17"),
            state("f", 4 * HOUR, 8, "Calm", "2026-01-19"),
            state("h", 5 * HOUR, 8, "Calm", "2026-01-20"),
            state("d", 6 * HOUR, 1, "", "2026-01-21"),
        )
        val intervals = listOf(
            interval(
                end = "b",
                previous = "a",
                start = 0L,
                endAt = HOUR,
                duration = 2 * HOUR + 9 * 60_000L,
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
            interval(
                end = "f",
                previous = "a",
                start = 3 * HOUR,
                endAt = 4 * HOUR,
                workout = WorkoutCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    4 * HOUR,
                    listOf(WorkoutObservationV2("run", null, "Run", 45, 4 * HOUR)),
                ),
                diet = DietCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    4 * HOUR,
                    listOf(
                        MealObservationV2(
                            "meal",
                            "small",
                            0,
                            4 * HOUR,
                            listOf(FoodObservationV2("soup", null, "Soup", 0)),
                        ),
                    ),
                ),
            ),
            interval(
                end = "h",
                previous = "b",
                start = 4 * HOUR,
                endAt = 5 * HOUR,
                activity = ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                workout = WorkoutCategoryObservationV2(
                    CategoryObservationKindV2.ENTRIES,
                    5 * HOUR,
                    listOf(WorkoutObservationV2("run", null, "Run", 10, 5 * HOUR)),
                ),
            ),
            interval(
                end = "a",
                previous = "c",
                start = 2 * HOUR,
                endAt = 7 * HOUR,
                activity = ActivityCategoryObservationV2(CategoryObservationKindV2.UNREVIEWED, null, emptyList()),
                diet = DietCategoryObservationV2(CategoryObservationKindV2.CONFLICTING, null, emptyList()),
            ),
            ContextIntervalObservationV2(
                endingCheckInId = "d",
                previousCheckInId = null,
                windowStartAt = 0L,
                windowEndAt = 6 * HOUR,
                durationMillis = 6 * HOUR,
                startBasis = CheckInContextStartBasis.WAKE_TIME,
                completedAt = 6 * HOUR,
                activity = ActivityCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                diet = DietCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
                usage = UsageCategoryObservationV2(CategoryObservationKindV2.ABSENT, null, emptyList()),
            ),
        )
        val nodes = listOf(
            EmbeddedNode("state:a", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:b", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:c", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:f", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:h", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:d", "state", listOf(0.0, 0.0)),
        )
        return built(states, intervals, nodes, nodes, aliases = mapOf(
            "state:a" to "S01",
            "state:b" to "S02",
            "state:c" to "S03",
            "state:f" to "S06",
            "state:h" to "S07",
            "state:d" to "S04",
            "state:e" to "S05",
            "transition:b" to "T01",
            "transition:c" to "T02",
            "transition:f" to "T04",
            "transition:h" to "T05",
            "transition:a" to "T06",
            "transition:d" to "T03",
        ))
    }

    private fun lineageFixture(): Built {
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
        val direct = listOf(
            EmbeddedNode("state:a", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:b", "state", listOf(1.0, 0.0)),
            EmbeddedNode("state:c", "state", listOf(0.0, 1.0)),
            EmbeddedNode("state:d", "state", listOf(0.0, 0.0)),
        )
        return built(states, intervals, direct, direct, aliases = mapOf(
            "state:a" to "S01",
            "state:b" to "S02",
            "state:c" to "S03",
            "state:d" to "S04",
            "state:e" to "S05",
            "transition:b" to "T01",
            "transition:c" to "T02",
            "transition:d" to "T03",
        ))
    }

    private fun built(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
        direct: List<EmbeddedNode>,
        reciprocal: List<EmbeddedNode>,
        aliases: Map<String, String>,
    ): Built {
        val dataset = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())
        val projection = projectPersonalSemanticOccurrences(dataset)
        val embedding = JointEmbeddingDataset(
            vocabularyScope = RETROSPECTIVE_VOCABULARY_SCOPE,
            retrospective = true,
            method = "region description test",
            audit = EmbeddingAuditIndex(
                aliasByNode = aliases,
                canonicalByNode = aliases.mapValues { (_, alias) -> alias },
                labelByConcept = projection.links.associate { link -> conceptNodeId(link.concept) to link.label },
                incidentTransitions = emptyMap(),
                focalConcepts = emptyMap(),
            ),
            layers = listOf(
                JointEmbeddingLayer(
                    name = LAYER_ZERO,
                    dimensionality = 2,
                    occurrenceCount = direct.size,
                    unreliableBecause = "",
                    omittedConstantPresence = emptyList(),
                    quantityHeldBack = emptyList(),
                    columns = listOf("x", "y"),
                    nodes = direct,
                    edges = emptyList(),
                ),
                JointEmbeddingLayer(
                    name = RECIPROCAL_LAYER,
                    dimensionality = 2,
                    occurrenceCount = reciprocal.size,
                    unreliableBecause = "",
                    omittedConstantPresence = emptyList(),
                    quantityHeldBack = emptyList(),
                    columns = listOf("x", "y"),
                    nodes = reciprocal,
                    edges = emptyList(),
                ),
            ),
            judgments = emptyList(),
        )
        return Built(
            embedding = embedding,
            distributional = projectDistributionalContext(embedding, projection = projection),
            graph = projectAlternatingTemporalGraph(dataset),
            projection = projection,
        )
    }

    private fun interval(
        end: String,
        previous: String,
        start: Long,
        endAt: Long,
        duration: Long = endAt - start,
        activity: ActivityCategoryObservationV2 = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout: WorkoutCategoryObservationV2 = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet: DietCategoryObservationV2 = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage: UsageCategoryObservationV2 = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    ) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = duration,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = endAt,
        activity = activity,
        workout = workout,
        diet = diet,
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
