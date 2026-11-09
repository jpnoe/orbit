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
import java.time.ZoneId

class CausalFeatureEncoderTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun targetsDoNotChangeInputsSchemasOrEncodedState() {
        val original = projectForecastExamples(chain(moods = listOf(4, 5, 8)))
        val changed = projectForecastExamples(chain(moods = listOf(4, 5, 1), anxiety = listOf(null, null, 8)))
        val before = original.stateForecasts.examples.single { it.identity.endingKey.raw == "c" }
        val after = changed.stateForecasts.examples.single { it.identity.endingKey.raw == "c" }
        assertEquals(before.input, after.input)
        val row = CausalStateTrainingRow("b", checkNotNull(before.input))
        val schema = fitCausalStateSchema(listOf(row), zone)
        assertEquals(schema, fitCausalStateSchema(listOf(CausalStateTrainingRow("b", checkNotNull(after.input))), zone))
        assertEquals(encodeCausalState(checkNotNull(before.input), schema, zone), encodeCausalState(checkNotNull(after.input), schema, zone))
        val beforeTarget = encodeCausalAxisTargets(before.target, before.targetConflicts)
        val afterTarget = encodeCausalAxisTargets(after.target, after.targetConflicts)
        assertNotEquals(beforeTarget, afterTarget)
        assertEquals(1, afterTarget.axes.single { it.axisId == DefaultCheckInAxes.MOOD }.raw)
        assertEquals(CausalTargetStatus.OBSERVED, afterTarget.axes.single { it.axisId == DefaultCheckInAxes.ANXIETY }.status)
        assertEquals(8, afterTarget.axes.single { it.axisId == DefaultCheckInAxes.ANXIETY }.raw)
        assertEquals(listOf("inputId", "input"), fields(CausalStateTrainingRow::class.java))
        assertFalse(fields(CausalFeatureSchema::class.java).contains("target"))
    }

    @Test
    fun heldOutValuesDoNotMoveTrainingCentersOrExpandTheSchema() {
        val mild = projectForecastExamples(chain(moods = listOf(0, 2), triggers = listOf("alpha", "alpha"), emotions = listOf("calm", "calm")))
        val extreme = projectForecastExamples(chain(moods = listOf(0, 10), triggers = listOf("alpha", "beta"), emotions = listOf("calm", "glad")))
        val prefix = mild.walkForward.stateSide.states.map { side ->
            CausalStateTrainingRow(
                side.focal.checkInId,
                StateForecastInput(side, mild.observationProcess.contexts.single { it.stateKey == side.trajectory.stateKey }),
            )
        }
        val schema = fitCausalStateSchema(prefix, zone)
        val reversed = fitCausalStateSchema(prefix.reversed(), zone)
        assertEquals(schema, reversed)
        val withExtreme = fitCausalStateSchema(
            listOf(prefix.first(), CausalStateTrainingRow("held", inputFor(extreme, "b"))),
            zone,
        )
        val mood = schema.definition(CausalFeatureIdentity.Structural("current.mood.raw"))
        assertEquals(1.0, schema.support[mood.index].center, 0.0)
        assertNotEquals(schema.support[mood.index].center, withExtreme.support[withExtreme.definition(mood.identity).index].center)
        val encoded = encodeCausalState(inputFor(extreme, "b"), schema, zone)
        assertEquals(9.0, encoded.values[mood.index], 1e-9)
        assertEquals(CausalFeatureStatus.OBSERVED, encoded.statuses[mood.index])
        assertTrue(encoded.values[mood.index] > 1.0)
        assertEquals(1.0, value(encoded, schema, CausalFeatureIdentity.FocalTrigger(null)), 0.0)
        assertEquals(0.0, value(encoded, schema, CausalFeatureIdentity.FocalTrigger("alpha")), 0.0)
        assertEquals(1.0, value(encoded, schema, CausalFeatureIdentity.FocalEmotion(null)), 0.0)
        assertFalse(schema.features.any { it.identity == CausalFeatureIdentity.FocalTrigger("beta") })
        assertFalse(schema.features.any { it.identity == CausalFeatureIdentity.FocalEmotion("glad") })
        assertEquals(schema.features.size, encodeCausalState(inputFor(mild, "a"), schema, zone).values.size)
    }

    @Test
    fun recordedZeroMissingConflictAndSlopeAvailabilityStayDistinct() {
        val zero = inputFor(projectForecastExamples(dataset(listOf(point("zero", 0, mood = 0)), emptyList())), "zero")
        val missing = inputFor(
            projectForecastExamples(dataset(listOf(point("missing", 0, energy = 3)), emptyList())),
            "missing",
        )
        val conflict = inputFor(
            projectForecastExamples(
                dataset(
                    listOf(point("conflict", 0, mood = 4, conflicting = listOf(DefaultCheckInAxes.ANXIETY))),
                    emptyList(),
                ),
            ),
            "conflict",
        )
        val zeroRaw = projectCausalRawFeatures(zero, zone)
        val missingRaw = projectCausalRawFeatures(missing, zone)
        assertEquals(CausalFeatureStatus.OBSERVED, channel(zeroRaw, "current.mood.raw").status)
        assertEquals(0.0, channel(zeroRaw, "current.mood.raw").value)
        assertEquals(CausalFeatureStatus.MISSING, channel(missingRaw, "current.mood.raw").status)
        assertNull(channel(missingRaw, "current.mood.raw").value)
        assertEquals(CausalFeatureStatus.CONFLICTING, channel(projectCausalRawFeatures(conflict, zone), "current.anxiety.raw").status)
        val schema = fitCausalStateSchema(
            listOf(CausalStateTrainingRow("zero", zero), CausalStateTrainingRow("missing", missing)),
            zone,
        )
        val encodedZero = encodeCausalState(zero, schema, zone)
        val encodedMissing = encodeCausalState(missing, schema, zone)
        val mood = schema.definition(CausalFeatureIdentity.Structural("current.mood.raw"))
        assertEquals(CausalFeatureStatus.OBSERVED, encodedZero.statuses[mood.index])
        assertEquals(CausalFeatureStatus.MISSING, encodedMissing.statuses[mood.index])
        val flat = projectForecastExamples(chain(moods = listOf(4, 4)))
        val later = projectCausalRawFeatures(inputFor(flat, "b"), zone)
        val earlier = projectCausalRawFeatures(inputFor(flat, "a"), zone)
        assertEquals(CausalFeatureStatus.OBSERVED, channel(later, "path.immediate.mood.slope").status)
        assertEquals(0.0, channel(later, "path.immediate.mood.slope").value)
        assertEquals(CausalFeatureStatus.UNAVAILABLE, channel(earlier, "path.immediate.mood.slope").status)
        assertNull(channel(earlier, "path.immediate.mood.slope").value)
    }

    @Test
    fun insufficientSupportAndNegligibleVariationStayDistinct() {
        val single = projectCausalRawFeatures(inputFor(projectForecastExamples(chain(moods = listOf(4, 6))), "b"), zone)
        assertEquals(1.0, channel(single, "background.trailing-24-hour.mood.insufficient").value)
        assertEquals(0.0, channel(single, "background.trailing-24-hour.mood.negligible").value)
        assertEquals(CausalFeatureStatus.UNAVAILABLE, channel(single, "background.trailing-24-hour.mood.standardized").status)
        val flat = projectCausalRawFeatures(inputFor(projectForecastExamples(chain(moods = listOf(4, 4, 6))), "c"), zone)
        assertEquals(0.0, channel(flat, "background.trailing-24-hour.mood.insufficient").value)
        assertEquals(1.0, channel(flat, "background.trailing-24-hour.mood.negligible").value)
        assertEquals(CausalFeatureStatus.UNAVAILABLE, channel(flat, "background.trailing-24-hour.mood.standardized").status)
    }

    @Test
    fun stateVectorsOmitScenarioChannelsAndScenariosShareColumns() {
        val walked = projectForecastExamples(withActivity(chain(moods = listOf(4, 5)), "b", "Walk"))
        val ran = projectForecastExamples(withActivity(chain(moods = listOf(4, 5)), "b", "Run"))
        val trainingScenario = walked.conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val heldScenario = ran.conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val schema = fitCausalConditionalSchema(
            listOf(
                CausalConditionalTrainingRow(
                    "b",
                    checkNotNull(trainingScenario.input),
                    trainingScenario.scenario,
                ),
            ),
            zone,
        )
        assertEquals(
            listOf(
                CausalFeatureFamily.CURRENT_STATE,
                CausalFeatureFamily.TRAJECTORY_CONTEXT,
                CausalFeatureFamily.CONTINUOUS_PATH_EXPERT,
                CausalFeatureFamily.SAMPLED_BACKGROUND_EXPERT,
                CausalFeatureFamily.OBSERVATION_PROCESS,
                CausalFeatureFamily.LOCAL_CLOCK,
                CausalFeatureFamily.SCENARIO_CATEGORY,
                CausalFeatureFamily.SCENARIO_EXPOSURE,
                CausalFeatureFamily.SCENARIO_HORIZON,
            ),
            schema.features.map { it.family }.distinct(),
        )
        val stateSchema = fitCausalStateSchema(
            listOf(CausalStateTrainingRow("b", checkNotNull(trainingScenario.input))),
            zone,
        )
        assertTrue(stateSchema.features.none { it.family.name.startsWith("SCENARIO") })
        val stateVector = encodeCausalState(checkNotNull(trainingScenario.input), stateSchema, zone)
        assertEquals(stateSchema.features.size, stateVector.values.size)
        val sandbox = sandboxTransitionScenario(
            SandboxScenarioRequest(
                anchorKey = checkNotNull(trainingScenario.input).stateSide.trajectory.stateKey,
                horizonMillis = trainingScenario.scenario.span.millis.coerceAtLeast(1),
                activity = trainingScenario.scenario.activity,
                workout = trainingScenario.scenario.workout,
                diet = trainingScenario.scenario.diet,
                usage = trainingScenario.scenario.usage,
                exposures = trainingScenario.scenario.exposures,
            ),
        )
        val recordedVector = encodeCausalConditional(
            checkNotNull(trainingScenario.input),
            trainingScenario.scenario,
            schema,
            zone,
        )
        val sandboxVector = encodeCausalConditional(checkNotNull(trainingScenario.input), sandbox, schema, zone)
        assertEquals(schema.features.size, recordedVector.values.size)
        assertEquals(recordedVector.values.size, sandboxVector.values.size)
        val walk = trainingScenario.scenario.exposures.single().identity
        val run = heldScenario.scenario.exposures.single().identity
        assertEquals(1.0, value(recordedVector, schema, CausalFeatureIdentity.BehaviorPresent(walk)), 0.0)
        assertEquals(1.0, value(sandboxVector, schema, CausalFeatureIdentity.BehaviorPresent(walk)), 0.0)
        val heldVector = encodeCausalConditional(checkNotNull(heldScenario.input), heldScenario.scenario, schema, zone)
        assertEquals(1.0, value(heldVector, schema, CausalFeatureIdentity.BehaviorOov(BehaviorKind.ACTIVITY)), 0.0)
        assertFalse(schema.features.any { it.identity == CausalFeatureIdentity.BehaviorPresent(run) })
        assertEquals(CausalFeatureStatus.OBSERVED, channel(projectCausalRawFeatures(checkNotNull(trainingScenario.input), zone, trainingScenario.scenario), "scenario.category.activity").status)
        assertEquals(1.0, channel(projectCausalRawFeatures(checkNotNull(trainingScenario.input), zone, trainingScenario.scenario), "scenario.category.activity").value)
        val unknown = trainingScenario.scenario.copy(activity = CategoryKnowledge.UNKNOWN)
        val unknownChannel = channel(
            projectCausalRawFeatures(checkNotNull(trainingScenario.input), zone, unknown),
            "scenario.category.activity",
        )
        assertEquals(CausalFeatureStatus.UNKNOWN, unknownChannel.status)
        assertNull(unknownChannel.value)
        val none = trainingScenario.scenario.copy(activity = CategoryKnowledge.REVIEWED_NONE, exposures = emptyList())
        val noneChannel = channel(
            projectCausalRawFeatures(checkNotNull(trainingScenario.input), zone, none),
            "scenario.category.activity",
        )
        assertEquals(CausalFeatureStatus.OBSERVED, noneChannel.status)
        assertEquals(0.0, noneChannel.value)
        assertTrue(schema.features.none { feature ->
            val name = feature.name
            name.contains("provenance") || name.contains("sandbox") || name.contains("forecast-residual") ||
                name.contains("coordinate") || name.contains("note") || name.contains("recordedAt")
        })
        assertEquals(CausalDeferredHead.entries, schema.deferred)
        assertEquals(schema.deferred, stateSchema.deferred)
        assertEquals(
            listOf(
                CausalDeferredHead.WEARABLE_PHYSIOLOGY,
                CausalDeferredHead.EMOTION_TARGET,
                CausalDeferredHead.LATENT_TARGET,
                CausalDeferredHead.SLEEP_PHYSIOLOGY,
            ),
            schema.deferred,
        )
        assertTrue(schema.features.none { it.name.contains("sleep") || it.name.contains("wearable") || it.name.contains("latent") || it.name.contains("target") })
        assertTrue(stateSchema.features.none { it.name.contains("sleep") || it.name.contains("wearable") || it.name.contains("latent") || it.name.contains("target") })
    }

    @Test
    fun usageZeroMissingAndUnparsedStayDistinctWithoutMixingUnits() {
        val data = withUsage(chain(moods = listOf(3, 4)))
        val example = projectForecastExamples(data).conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val schema = fitCausalConditionalSchema(
            listOf(CausalConditionalTrainingRow("b", checkNotNull(example.input), example.scenario)),
            zone,
        )
        val encoded = encodeCausalConditional(checkNotNull(example.input), example.scenario, schema, zone)
        val identity = example.scenario.exposures.filterIsInstance<UsageBehavior>().single().identity
        val zero = schema.definition(CausalFeatureIdentity.UsageAmount(identity, "mg"))
        assertEquals(CausalFeatureStatus.OBSERVED, encoded.statuses[zero.index])
        assertEquals(0.0, encoded.values[zero.index], 0.0)
        val missingOnly = example.scenario.copy(
            exposures = listOf(
                (example.scenario.exposures.single() as UsageBehavior).copy(
                    amounts = listOf(
                        UsageQuantityEvidence("", UsageQuantityStatus.MISSING, null, null, null),
                    ),
                ),
            ),
        )
        val missingEncoded = encodeCausalConditional(checkNotNull(example.input), missingOnly, schema, zone)
        assertEquals(CausalFeatureStatus.MISSING, missingEncoded.statuses[zero.index])
        assertEquals(1.0, value(missingEncoded, schema, CausalFeatureIdentity.UsageMissing(identity)), 0.0)
        val otherUnit = example.scenario.copy(
            exposures = listOf(
                (example.scenario.exposures.single() as UsageBehavior).copy(
                    amounts = listOf(
                        UsageQuantityEvidence("2 ml", UsageQuantityStatus.PARSED, 2.0, "ml", null),
                    ),
                ),
            ),
        )
        val otherEncoded = encodeCausalConditional(checkNotNull(example.input), otherUnit, schema, zone)
        assertEquals(CausalFeatureStatus.MISSING, otherEncoded.statuses[zero.index])
        assertEquals(1.0, value(otherEncoded, schema, CausalFeatureIdentity.UsageAmountOov), 0.0)
        assertEquals(0.0, encoded.values[schema.definition(CausalFeatureIdentity.UsageAmountOov).index], 0.0)
    }

    @Test
    fun reversedTrainingOrderPreservesNontrivialCentersAndEncodings() {
        val times = listOf(0L, 1L, 4L, 17L)
        val ids = listOf("a", "b", "c", "d")
        val states = ids.mapIndexed { index, id ->
            point(id, times[index], mood = index, energy = index + 2)
        }
        val bundle = projectForecastExamples(
            dataset(
                states,
                states.zipWithNext().map { (previous, next) ->
                    interval(next.checkInId, previous.checkInId, previous.recordedAt, next.recordedAt)
                },
            ),
        )
        val rows = listOf("d", "c", "b").map { id -> CausalStateTrainingRow(id, inputFor(bundle, id)) }
        val forward = fitCausalStateSchema(rows, zone)
        val backward = fitCausalStateSchema(rows.reversed(), zone)
        assertEquals(forward, backward)
        val encoded = encodeCausalState(inputFor(bundle, "d"), forward, zone)
        assertEquals(encoded, encodeCausalState(inputFor(bundle, "d"), backward, zone))
        val slope = forward.definition(CausalFeatureIdentity.Structural("path.immediate.mood.slope"))
        val presented = rows.map { row ->
            checkNotNull(channel(projectCausalRawFeatures(row.input, zone), "path.immediate.mood.slope").value)
        }
        assertTrue(presented.sum() != presented.asReversed().sum())
        val sorted = rows.sortedBy { it.inputId }.map { row ->
            checkNotNull(channel(projectCausalRawFeatures(row.input, zone), "path.immediate.mood.slope").value)
        }
        assertEquals(sorted.sum() / sorted.size, forward.support[slope.index].center, 0.0)
        assertTrue(forward.support[slope.index].center % 1.0 != 0.0)
    }

    @Test
    fun categoryKnowledgeStaysOnEachBehaviorKindOov() {
        val example = projectForecastExamples(withKnowledge(chain(moods = listOf(3, 4))))
            .conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val input = checkNotNull(example.input)
        val scenario = example.scenario
        val schema = fitCausalConditionalSchema(
            listOf(CausalConditionalTrainingRow("b", input, scenario)),
            zone,
        )
        val activity = scenario.exposures.single { it.identity.kind == BehaviorKind.ACTIVITY } as ReportedBehavior
        val food = scenario.exposures.single { it.identity.kind == BehaviorKind.FOOD } as ReportedBehavior
        val usage = scenario.exposures.single { it.identity.kind == BehaviorKind.USAGE } as UsageBehavior
        val activityOov = CausalFeatureIdentity.BehaviorOov(BehaviorKind.ACTIVITY)
        val foodOov = CausalFeatureIdentity.BehaviorOov(BehaviorKind.FOOD)
        val mealOov = CausalFeatureIdentity.BehaviorOov(BehaviorKind.MEAL_SIZE)
        val amountOov = CausalFeatureIdentity.UsageAmountOov
        assertEquals(
            BehaviorKind.entries,
            schema.features.mapNotNull { (it.identity as? CausalFeatureIdentity.BehaviorOov)?.kind },
        )
        assertTrue(schema.definition(foodOov).index < schema.definition(mealOov).index)
        fun encoded(next: TransitionScenario) = encodeCausalConditional(input, next, schema, zone)
        val known = encoded(scenario)
        assertObserved(known, schema, activityOov, 0.0)
        assertObserved(known, schema, CausalFeatureIdentity.BehaviorOov(BehaviorKind.WORKOUT), 0.0)
        assertObserved(known, schema, foodOov, 0.0)
        assertObserved(known, schema, mealOov, 0.0)
        assertObserved(known, schema, amountOov, 0.0)
        assertObserved(known, schema, CausalFeatureIdentity.BehaviorPresent(activity.identity), 1.0)
        val unknownActivity = encoded(scenario.copy(activity = CategoryKnowledge.UNKNOWN))
        assertStatus(unknownActivity, schema, activityOov, CausalFeatureStatus.UNKNOWN)
        assertEquals(0.0, value(unknownActivity, schema, activityOov), 0.0)
        assertObserved(unknownActivity, schema, foodOov, 0.0)
        val absent = encoded(
            scenario.copy(
                activity = CategoryKnowledge.REVIEWED_NONE,
                exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.ACTIVITY },
            ),
        )
        assertObserved(absent, schema, activityOov, 0.0)
        val unseenActivity = activity.copy(
            identity = behaviorIdentity(BehaviorKind.ACTIVITY, null, "Run"),
            displayName = "Run",
        )
        val onlyActivity = encoded(
            scenario.copy(exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.ACTIVITY } + unseenActivity),
        )
        assertObserved(onlyActivity, schema, activityOov, 1.0)
        assertObserved(onlyActivity, schema, foodOov, 0.0)
        assertObserved(onlyActivity, schema, mealOov, 0.0)
        assertObserved(onlyActivity, schema, amountOov, 0.0)
        assertFalse(schema.features.any { it.identity == CausalFeatureIdentity.BehaviorPresent(unseenActivity.identity) })
        val unseenFood = food.copy(
            identity = behaviorIdentity(BehaviorKind.FOOD, null, "Pear"),
            displayName = "Pear",
        )
        val onlyFood = encoded(
            scenario.copy(exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.FOOD } + unseenFood),
        )
        assertObserved(onlyFood, schema, foodOov, 1.0)
        assertObserved(onlyFood, schema, mealOov, 0.0)
        assertObserved(onlyFood, schema, activityOov, 0.0)
        val unknownDiet = encoded(scenario.copy(diet = CategoryKnowledge.UNKNOWN))
        assertStatus(unknownDiet, schema, foodOov, CausalFeatureStatus.UNKNOWN)
        assertStatus(unknownDiet, schema, mealOov, CausalFeatureStatus.UNKNOWN)
        assertObserved(unknownDiet, schema, activityOov, 0.0)
        val unknownUsage = encoded(scenario.copy(usage = CategoryKnowledge.UNKNOWN))
        assertStatus(unknownUsage, schema, amountOov, CausalFeatureStatus.UNKNOWN)
        assertEquals(0.0, value(unknownUsage, schema, amountOov), 0.0)
        val absentUsage = encoded(
            scenario.copy(
                usage = CategoryKnowledge.REVIEWED_NONE,
                exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.USAGE },
            ),
        )
        assertObserved(absentUsage, schema, amountOov, 0.0)
        val otherUnit = usage.copy(
            amounts = listOf(UsageQuantityEvidence("2 ml", UsageQuantityStatus.PARSED, 2.0, "ml", null)),
        )
        val unseenUnit = encoded(
            scenario.copy(exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.USAGE } + otherUnit),
        )
        assertObserved(unseenUnit, schema, amountOov, 1.0)
        assertEquals(
            CausalFeatureStatus.MISSING,
            unseenUnit.statuses[schema.definition(CausalFeatureIdentity.UsageAmount(usage.identity, "mg")).index],
        )
        assertObserved(unseenUnit, schema, activityOov, 0.0)
        val unknownWithUnseenUnit = encoded(
            scenario.copy(
                usage = CategoryKnowledge.UNKNOWN,
                exposures = scenario.exposures.filter { it.identity.kind != BehaviorKind.USAGE } + otherUnit,
            ),
        )
        assertStatus(unknownWithUnseenUnit, schema, amountOov, CausalFeatureStatus.UNKNOWN)
    }

    @Test
    fun firstWorkoutActivatesOnlyThePermanentWorkoutOov() {
        val walked = projectForecastExamples(withActivity(chain(moods = listOf(4, 5)), "b", "Walk"))
        val training = walked.conditionalTransitions.examples.single { it.identity.endingKey.raw == "b" }
        val schema = fitCausalConditionalSchema(
            listOf(
                CausalConditionalTrainingRow(
                    "b",
                    checkNotNull(training.input),
                    training.scenario,
                ),
            ),
            zone,
        )
        assertEquals(
            BehaviorKind.entries,
            schema.features.mapNotNull { (it.identity as? CausalFeatureIdentity.BehaviorOov)?.kind },
        )
        assertFalse(schema.features.any { (it.identity as? CausalFeatureIdentity.BehaviorPresent)?.identity?.kind == BehaviorKind.WORKOUT })
        val workout = WorkoutBehavior(
            identity = behaviorIdentity(BehaviorKind.WORKOUT, null, "Run"),
            displayName = "Run",
            minutes = 20,
        )
        val held = training.scenario.copy(
            activity = CategoryKnowledge.REVIEWED_NONE,
            workout = CategoryKnowledge.REVIEWED_ENTRIES,
            exposures = listOf(workout),
        )
        val encoded = encodeCausalConditional(checkNotNull(training.input), held, schema, zone)
        assertObserved(encoded, schema, CausalFeatureIdentity.BehaviorOov(BehaviorKind.WORKOUT), 1.0)
        BehaviorKind.entries.filter { it != BehaviorKind.WORKOUT }.forEach { kind ->
            assertObserved(encoded, schema, CausalFeatureIdentity.BehaviorOov(kind), 0.0)
        }
        assertFalse(schema.features.any { it.identity == CausalFeatureIdentity.BehaviorPresent(workout.identity) })
        assertFalse(schema.features.any { it.identity is CausalFeatureIdentity.WorkoutMinutes })
    }

    @Test
    fun theEncoderDoesNotFitTheFullHistoryOrReleaseAModel() {
        val source = source(
            "src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt",
            "app/src/main/java/com/orbit/wellness/domain/checkin/CausalFeatureEncoder.kt",
        ).readText()
        listOf(
            "Dao",
            "RoomDatabase",
            ".insert",
            "LatentProjection",
            "FeatureSemantic",
            "projectLocalContext",
            "BEHAVIOR_CATALOG",
            "projectBehavioralEvidence",
            "projectForecastExamples",
            "fun fitCausalFeatures(",
        ).forEach { token -> assertFalse(token, source.contains(token)) }
        assertTrue(source.contains("READ_TIME_ZONE"))
        assertTrue(source.contains("WEARABLE_PHYSIOLOGY"))
        assertTrue(source.contains("SLEEP_PHYSIOLOGY"))
        val insights = source(
            "src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
            "app/src/main/java/com/orbit/wellness/ui/insights/InsightsScreen.kt",
        ).readText()
        val nav = source(
            "src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
            "app/src/main/java/com/orbit/wellness/ui/navigation/OrbitNavHost.kt",
        ).readText()
        assertFalse(insights.contains("causal-feature-encoder"))
        assertFalse(nav.contains("causal-feature-encoder"))
        assertFalse(source.contains("predictive = true"))
        assertEquals(CAUSAL_FEATURE_ENCODER_PROTOCOL, "causal-feature-encoder-v3")
    }

    private fun assertObserved(
        vector: CausalEncodedVector,
        schema: CausalFeatureSchema,
        identity: CausalFeatureIdentity,
        expected: Double,
    ) {
        assertStatus(vector, schema, identity, CausalFeatureStatus.OBSERVED)
        assertEquals(expected, value(vector, schema, identity), 0.0)
    }

    private fun assertStatus(
        vector: CausalEncodedVector,
        schema: CausalFeatureSchema,
        identity: CausalFeatureIdentity,
        status: CausalFeatureStatus,
    ) {
        assertEquals(status, vector.statuses[schema.definition(identity).index])
    }

    private fun value(vector: CausalEncodedVector, schema: CausalFeatureSchema, identity: CausalFeatureIdentity): Double {
        return vector.values[schema.definition(identity).index]
    }

    private fun channel(projection: CausalRawProjection, id: String): CausalRawFeature {
        return projection.channels.single { it.id == id }
    }

    private fun inputFor(bundle: ForecastExampleBundle, id: String): StateForecastInput {
        return StateForecastInput(
            bundle.walkForward.stateSide.states.single { it.focal.checkInId == id },
            bundle.observationProcess.contexts.single { it.stateKey.raw == id },
        )
    }

    private fun chain(
        moods: List<Int>,
        triggers: List<String> = List(moods.size) { "" },
        emotions: List<String> = List(moods.size) { "" },
        anxiety: List<Int?> = List(moods.size) { null },
    ): CheckInAnalysisDatasetV2 {
        val ids = listOf("a", "b", "c", "d")
        val states = moods.mapIndexed { index, mood ->
            point(ids[index], index * HOUR, mood = mood, trigger = triggers[index], emotion = emotions[index], anxiety = anxiety[index])
        }
        return dataset(states, states.zipWithNext().map { (previous, next) ->
            interval(next.checkInId, previous.checkInId, previous.recordedAt, next.recordedAt)
        })
    }

    private fun withActivity(data: CheckInAnalysisDatasetV2, end: String, name: String): CheckInAnalysisDatasetV2 {
        return data.copy(intervals = data.intervals.map { item ->
            if (item.endingCheckInId != end) {
                item
            } else {
                item.copy(
                    activity = ActivityCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        item.windowEndAt,
                        listOf(ActivityObservationV2(name.lowercase(), null, name, item.windowStartAt)),
                    ),
                )
            }
        })
    }

    private fun withKnowledge(data: CheckInAnalysisDatasetV2): CheckInAnalysisDatasetV2 {
        return data.copy(intervals = data.intervals.map { item ->
            if (item.endingCheckInId != "b") {
                item
            } else {
                item.copy(
                    activity = ActivityCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        item.windowEndAt,
                        listOf(ActivityObservationV2("walk", null, "Walk", item.windowStartAt)),
                    ),
                    diet = DietCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        item.windowEndAt,
                        listOf(
                            MealObservationV2(
                                "meal",
                                "large",
                                0,
                                item.windowStartAt,
                                listOf(FoodObservationV2("apple", null, "Apple", 0)),
                            ),
                        ),
                    ),
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        item.windowEndAt,
                        listOf(UsageObservationV2("zero", null, "tea", "supportive", "0 mg", item.windowStartAt)),
                    ),
                )
            }
        })
    }

    private fun withUsage(data: CheckInAnalysisDatasetV2): CheckInAnalysisDatasetV2 {
        return data.copy(intervals = data.intervals.map { item ->
            if (item.endingCheckInId != "b") {
                item
            } else {
                item.copy(
                    usage = UsageCategoryObservationV2(
                        CategoryObservationKindV2.ENTRIES,
                        item.windowEndAt,
                        listOf(
                            UsageObservationV2("zero", null, "tea", "supportive", "0 mg", item.windowStartAt),
                            UsageObservationV2("blank", null, "tea", "supportive", "", item.windowStartAt),
                            UsageObservationV2("bad", null, "tea", "supportive", "nope", item.windowStartAt),
                        ),
                    ),
                )
            }
        })
    }

    private fun dataset(
        states: List<StateObservationV2>,
        intervals: List<ContextIntervalObservationV2>,
    ) = CheckInAnalysisDatasetV2(states, intervals, emptyList(), emptyList())

    private fun point(
        id: String,
        at: Long,
        mood: Int? = null,
        energy: Int? = null,
        anxiety: Int? = null,
        trigger: String = "",
        emotion: String = "",
        conflicting: List<String> = emptyList(),
    ): StateObservationV2 {
        fun reading(axis: String, raw: Int?) = raw?.let { AxisReadingV2(axis, it, it / 10.0) }
        val readings = listOf(
            DefaultCheckInAxes.MOOD to mood,
            DefaultCheckInAxes.ENERGY to energy,
            DefaultCheckInAxes.ANXIETY to anxiety,
        ).mapNotNull { (axis, raw) -> reading(axis, raw) }
        return StateObservationV2(
            checkInId = id,
            recordedAt = at,
            localDate = "2026-11-08",
            axes = readings.associateBy { it.axisId },
            core = CoreAxisViewV2(
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD },
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ENERGY },
                null,
                readings.firstOrNull { it.axisId == DefaultCheckInAxes.ANXIETY },
            ),
            conflictingAxisIds = conflicting,
            trigger = trigger,
            emotion = emotion,
        )
    }

    private fun interval(end: String, previous: String, start: Long, endAt: Long) = ContextIntervalObservationV2(
        endingCheckInId = end,
        previousCheckInId = previous,
        windowStartAt = start,
        windowEndAt = endAt,
        durationMillis = endAt - start,
        startBasis = CheckInContextStartBasis.PREVIOUS_CHECK_IN,
        completedAt = endAt,
        activity = ActivityCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        workout = WorkoutCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        diet = DietCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
        usage = UsageCategoryObservationV2(CategoryObservationKindV2.NONE, endAt, emptyList()),
    )

    private fun fields(type: Class<*>): List<String> {
        return type.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "serialVersionUID" }
    }

    private fun source(vararg paths: String) = paths.map { File(it) }.first { it.isFile }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
