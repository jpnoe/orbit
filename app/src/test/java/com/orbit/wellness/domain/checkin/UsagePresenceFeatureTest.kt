package com.orbit.wellness.domain.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePresenceFeatureTest {
    @Test
    fun blankCigarettesAmountIsPresentAndNumericallyUnobserved() {
        val vector = vector("blank")
        assertPresent(vector, cigarettes, parsed = 0, missing = 1, unparsed = 0)
        assertUnobservedAmount(vector, cigarettes)
    }

    @Test
    fun malformedCigarettesAmountIsPresentAndNumericallyUnobserved() {
        val vector = vector("malformed")
        assertPresent(vector, cigarettes, parsed = 0, missing = 0, unparsed = 1)
        assertUnobservedAmount(vector, cigarettes)
    }

    @Test
    fun parsedCigarettesAmountIsObserved() {
        val vector = vector("three")
        assertPresent(vector, cigarettes, parsed = 1, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 3.0)
        assertEquals(3.0, vector.value(schema, rate(cigarettes)), 0.0)
        assertTrue(vector.observed(schema, rate(cigarettes)))
    }

    @Test
    fun literalZeroIsAnObservedAmount() {
        val vector = vector("zero")
        assertPresent(vector, cigarettes, parsed = 1, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 0.0)
        assertEquals(0.0, vector.value(schema, rate(cigarettes)), 0.0)
        assertTrue(vector.observed(schema, rate(cigarettes)))
    }

    @Test
    fun reviewedCategoryWithoutCigarettesIsAnObservedZero() {
        val coffeeOnly = vector("coffee-only")
        assertAbsentItem(coffeeOnly, cigarettes)
        assertEquals(1.0, coffeeOnly.value(schema, present(coffee)), 0.0)
        assertObservedAmount(coffeeOnly, coffee, 1.0, "unitless")
        val none = vector("none")
        assertAbsentItem(none, cigarettes)
        assertAbsentItem(none, coffee)
    }

    @Test
    fun hiddenUsageCategoriesLeavePresenceAndQuantityUnobserved() {
        listOf("absent", "unreviewed", "conflicting").forEach { id ->
            val vector = vector(id)
            listOf(present(cigarettes), entries(cigarettes), complete(cigarettes), amount(cigarettes)).forEach { semantic ->
                assertFalse(vector.observed(schema, semantic))
                assertEquals(0.0, vector.value(schema, semantic), 0.0)
            }
            assertFalse(vector.observed(schema, rate(cigarettes)))
            assertCounts(vector, cigarettes, parsed = 0, missing = 0, unparsed = 0, observed = false)
        }
    }

    @Test
    fun mixedParsedAndUnparsedEntriesDoNotExposeAPartialTotalOrRate() {
        val vector = vector("mixed")
        assertPresent(vector, cigarettes, entries = 2, parsed = 1, missing = 0, unparsed = 1)
        assertUnobservedAmount(vector, cigarettes)
        assertEquals(0.0, vector.value(schema, amount(cigarettes)), 0.0)
        assertFalse(vector.observed(schema, rate(cigarettes)))
        assertEquals(0.0, vector.value(schema, rate(cigarettes)), 0.0)
    }

    @Test
    fun fullyParsedEntriesInOneUnitSum() {
        val vector = vector("summed")
        assertPresent(vector, cigarettes, entries = 2, parsed = 2, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 7.0)
        assertEquals(7.0, vector.value(schema, rate(cigarettes)), 0.0)
    }

    @Test
    fun fullyParsedEntriesInDifferentUnitsStaySeparate() {
        val vector = vector("units")
        assertPresent(vector, cigarettes, entries = 2, parsed = 2, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 3.0, "unitless")
        assertObservedAmount(vector, cigarettes, 10.0, "mg")
        assertTrue(schema.features.none { feature ->
            val semantic = feature.semantic
            semantic is FeatureSemantic.Usage &&
                semantic.measurement == UsageMeasurement.PER_HOUR &&
                semantic.unit == "mg"
        })
        assertEquals(3.0, vector.value(schema, rate(cigarettes)), 0.0)
    }

    @Test
    fun supportiveAndCopingStaySeparate() {
        val copingOnly = vector("coping-only")
        assertPresent(copingOnly, cigarettes, parsed = 1, missing = 0, unparsed = 0)
        assertObservedAmount(copingOnly, cigarettes, 3.0)
        assertAbsentItem(copingOnly, cigarettes, SUPPORTIVE)
        val supportiveOnly = vector("supportive-only")
        assertPresent(supportiveOnly, cigarettes, parsed = 1, missing = 0, unparsed = 0, intent = SUPPORTIVE)
        assertObservedAmount(supportiveOnly, cigarettes, 2.0, intent = SUPPORTIVE)
        assertAbsentItem(supportiveOnly, cigarettes, COPING)
    }

    @Test
    fun zeroDurationLeavesTheRateUnobserved() {
        val vector = vector("stopped")
        assertPresent(vector, cigarettes, parsed = 1, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 6.0)
        assertFalse(vector.observed(schema, rate(cigarettes)))
    }

    @Test
    fun physicalUnitHasNoUnitlessRateDerivedFromIt() {
        val vector = vector("physical")
        assertPresent(vector, cigarettes, parsed = 1, missing = 0, unparsed = 0)
        assertObservedAmount(vector, cigarettes, 10.0, "mg")
        assertObservedAmount(vector, cigarettes, 0.0, "unitless")
        assertEquals(0.0, vector.value(schema, rate(cigarettes)), 0.0)
        assertTrue(vector.observed(schema, rate(cigarettes)))
        assertTrue(schema.features.none { feature ->
            val semantic = feature.semantic
            semantic is FeatureSemantic.Usage &&
                semantic.identity == cigarettes &&
                semantic.measurement == UsageMeasurement.PER_HOUR &&
                semantic.unit != "unitless"
        })
    }

    @Test
    fun punctuatedUnitIsParsedFittedAndEncoded() {
        val parsed = UsageAmountParser.parse("2 fl.oz")
        assertTrue(parsed is UsageAmountParse.Parsed)
        val amount = parsed as UsageAmountParse.Parsed
        assertEquals(2.0, amount.number, 0.0)
        assertEquals("fl.oz", amount.unit)
        val water = categoricalKey(null, "Water")
        val semantic = FeatureSemantic.Usage(water, COPING, UsageMeasurement.AMOUNT, "fl.oz")
        val vector = vector("fluid")
        assertEquals(2.0, vector.value(schema, semantic), 0.0)
        assertTrue(vector.observed(schema, semantic))
        val name = schema.features.single { it.semantic == semantic }.name
        assertTrue(name.endsWith("amount.fl%2e;oz"))
        assertEquals("2 fl.oz", evidence.transitions.single { it.key.raw == "fluid" }
            .evidence.usage.items.single().amountText)
    }

    @Test
    fun punctuatedUsageNameDoesNotCollideWithAnotherIdentity() {
        val dotted = categoricalKey(null, "cig.arette")
        val plain = categoricalKey(null, "cigarette")
        assertNotEquals(dotted, plain)
        val dottedPresent = present(dotted)
        val plainPresent = present(plain)
        assertNotEquals(dottedPresent, plainPresent)
        val vector = vector("dotted-name")
        assertEquals(1.0, vector.value(schema, dottedPresent), 0.0)
        assertObservedAmount(vector, dotted, 1.0)
        assertAbsentItem(vector, plain)
        val names = schema.features.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertEquals(schema.features.map { it.semantic }.size, schema.features.map { it.semantic }.distinct().size)
    }

    @Test
    fun featureOrderStaysPutWhenSourceOrderIsReversed() {
        val reversed = evidence.copy(
            transitions = evidence.transitions.reversed().map { frame ->
                frame.copy(
                    evidence = frame.evidence.copy(
                        usage = frame.evidence.usage.copy(items = frame.evidence.usage.items.reversed()),
                    ),
                )
            },
            transitionsByKey = evidence.transitions.associateBy { it.key },
        )
        val forward = encodeFeatures(evidence)
        val backward = encodeFeatures(reversed)
        assertEquals(forward.schemas.transition.features, backward.schemas.transition.features)
        evidence.transitions.forEach { frame ->
            assertEquals(vector(frame.key.raw), backward.transitions.single { it.key == frame.key }.vector)
        }
    }

    @Test
    fun causalVectorsOmitTargetEvidenceAndOccurrenceIdentity() {
        val encoded = encodeFeatures(evidence)
        val eligible = encoded.eligibleCausalExamples.single()
        val held = encoded.causalExamples.single { !it.eligibility.eligible }
        assertEquals(TransitionOccurrenceKey("ending-mood"), eligible.exampleKey)
        assertNull(held.startingState)
        assertTrue(encoded.eligibleCausalExamples.none { it.exampleKey == held.exampleKey })
        val state = encoded.schemas.state
        val start = checkNotNull(eligible.startingState)
        assertEquals(0.1, start.value(state, "state.axis.anxiety.value"), 0.0)
        assertEquals(0.9, eligible.target.value(state, "state.axis.anxiety.value"), 0.0)
        assertFalse(eligible.transition.values.contains(0.9))
        assertTrue(encoded.schemas.transition.features.none {
            it.block == FeatureBlock.STATE_AXIS_VALUE || it.block == FeatureBlock.STATE_EMOTION
        })
        val names = encoded.schemas.transition.features.map { it.name } + encoded.schemas.state.features.map { it.name }
        assertTrue(names.none { it.contains(eligible.exampleKey.raw) || it.contains("start-mood") })
    }

    private val cigarettes = categoricalKey(null, "Cigarettes")
    private val coffee = categoricalKey("coffee", "Coffee")
    private val evidence = usageEvidence()
    private val encoded = encodeFeatures(evidence)
    private val schema = encoded.schemas.transition

    private fun vector(id: String): EncodedVector = encoded.transitions.single { it.key.raw == id }.vector

    private fun present(identity: String, intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, UsageMeasurement.PRESENT)

    private fun entries(identity: String, intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, UsageMeasurement.ENTRIES)

    private fun complete(identity: String, intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, UsageMeasurement.AMOUNT_COMPLETE)

    private fun amount(identity: String, unit: String = "unitless", intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, UsageMeasurement.AMOUNT, unit)

    private fun rate(identity: String, intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, UsageMeasurement.PER_HOUR, "unitless")

    private fun count(identity: String, measurement: UsageMeasurement, intent: String = COPING) =
        FeatureSemantic.Usage(identity, intent, measurement)

    private fun assertPresent(
        vector: EncodedVector,
        identity: String,
        parsed: Int,
        missing: Int,
        unparsed: Int,
        entries: Int = parsed + missing + unparsed,
        intent: String = COPING,
    ) {
        assertEquals(1.0, vector.value(schema, present(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, present(identity, intent)))
        assertEquals(entries.toDouble(), vector.value(schema, entries(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, entries(identity, intent)))
        assertCounts(vector, identity, parsed, missing, unparsed, observed = true, intent = intent)
    }

    private fun assertAbsentItem(vector: EncodedVector, identity: String, intent: String = COPING) {
        assertEquals(0.0, vector.value(schema, present(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, present(identity, intent)))
        assertEquals(0.0, vector.value(schema, entries(identity, intent)), 0.0)
        assertEquals(1.0, vector.value(schema, complete(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, complete(identity, intent)))
        assertObservedAmount(vector, identity, 0.0, "unitless", intent)
        if (schema.features.any { it.semantic == amount(identity, "mg", intent) }) {
            assertObservedAmount(vector, identity, 0.0, "mg", intent)
        }
        assertCounts(vector, identity, parsed = 0, missing = 0, unparsed = 0, observed = true, intent = intent)
    }

    private fun assertObservedAmount(
        vector: EncodedVector,
        identity: String,
        expected: Double,
        unit: String = "unitless",
        intent: String = COPING,
    ) {
        assertEquals(1.0, vector.value(schema, complete(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, complete(identity, intent)))
        assertEquals(expected, vector.value(schema, amount(identity, unit, intent)), 0.0)
        assertTrue(vector.observed(schema, amount(identity, unit, intent)))
    }

    private fun assertUnobservedAmount(vector: EncodedVector, identity: String, intent: String = COPING) {
        assertEquals(0.0, vector.value(schema, complete(identity, intent)), 0.0)
        assertTrue(vector.observed(schema, complete(identity, intent)))
        assertFalse(vector.observed(schema, amount(identity, "unitless", intent)))
        assertFalse(vector.observed(schema, amount(identity, "mg", intent)))
        assertFalse(vector.observed(schema, rate(identity, intent)))
    }

    private fun assertCounts(
        vector: EncodedVector,
        identity: String,
        parsed: Int,
        missing: Int,
        unparsed: Int,
        observed: Boolean,
        intent: String = COPING,
    ) {
        listOf(
            UsageMeasurement.PARSED to parsed,
            UsageMeasurement.MISSING to missing,
            UsageMeasurement.UNPARSED to unparsed,
        ).forEach { (measurement, count) ->
            val semantic = count(identity, measurement, intent)
            assertEquals(observed, vector.observed(schema, semantic))
            assertEquals(if (observed) count.toDouble() else 0.0, vector.value(schema, semantic), 0.0)
        }
    }

    private fun EncodedVector.value(schema: FeatureSchema, name: String): Double = values[schema.indexOf(name)]

    private fun EncodedVector.value(schema: FeatureSchema, semantic: FeatureSemantic): Double =
        values[schema.indexOf(semantic)]

    private fun EncodedVector.observed(schema: FeatureSchema, semantic: FeatureSemantic): Boolean =
        observed[schema.indexOf(semantic)]

    private companion object {
        const val COPING = "COPING"
        const val SUPPORTIVE = "SUPPORTIVE"
    }
}

private fun usageEvidence(): ContextualEvidence {
    val frames = listOf(
        usageFrame("blank", items = listOf(cigarettes(""))),
        usageFrame("malformed", items = listOf(cigarettes("later"))),
        usageFrame("three", items = listOf(cigarettes("3"))),
        usageFrame("zero", items = listOf(cigarettes("0"))),
        usageFrame("coffee-only", items = listOf(coffee("1"))),
        usageFrame("none", kind = CategoryObservationKindV2.NONE),
        usageFrame("absent", kind = CategoryObservationKindV2.ABSENT, items = listOf(cigarettes("3"))),
        usageFrame("unreviewed", kind = CategoryObservationKindV2.UNREVIEWED, items = listOf(cigarettes("3"))),
        usageFrame("conflicting", kind = CategoryObservationKindV2.CONFLICTING, items = listOf(cigarettes("nope"))),
        usageFrame("mixed", items = listOf(cigarettes("3"), cigarettes("nope"))),
        usageFrame("summed", items = listOf(cigarettes("3"), cigarettes("4"))),
        usageFrame("units", items = listOf(cigarettes("3"), cigarettes("10 mg"))),
        usageFrame("coping-only", items = listOf(cigarettes("3"))),
        usageFrame("supportive-only", items = listOf(cigarettes("2", intent = "SUPPORTIVE"))),
        usageFrame("stopped", durationMillis = 0L, items = listOf(cigarettes("6"))),
        usageFrame("physical", durationMillis = 7_200_000L, items = listOf(cigarettes("10 mg"))),
        usageFrame("fluid", items = listOf(named("Water", "2 fl.oz"))),
        usageFrame("dotted-name", items = listOf(named("cig.arette", "1"))),
        usageFrame("plain-name", items = listOf(named("cigarette", "5"))),
    )
    val start = state("start-mood", anxiety = 0.1)
    val ending = state("ending-mood", anxiety = 0.9)
    val eligible = CausalEvidenceExample(
        exampleKey = TransitionOccurrenceKey("ending-mood"),
        input = CausalEvidenceInput(start.evidence, frames.first { it.key.raw == "three" }.evidence),
        target = CausalEvidenceTarget(ending.evidence),
        eligibility = CausalEligibility(true, emptyList()),
    )
    val ineligible = CausalEvidenceExample(
        exampleKey = TransitionOccurrenceKey("boundary-only"),
        input = CausalEvidenceInput(null, frames.first { it.key.raw == "none" }.evidence),
        target = CausalEvidenceTarget(ending.evidence),
        eligibility = CausalEligibility(false, listOf(CausalIneligibilityReason.BOUNDARY_INTERVAL)),
    )
    return ContextualEvidence(
        states = listOf(start, ending),
        transitions = frames,
        statesByKey = listOf(start, ending).associateBy { it.key },
        transitionsByKey = frames.associateBy { it.key },
        wearablesById = emptyMap(),
        retrospectiveStates = emptyList(),
        retrospectiveTransitions = emptyList(),
        causalExamples = listOf(eligible, ineligible),
        issues = emptyList(),
    )
}

private fun usageFrame(
    id: String,
    kind: CategoryObservationKindV2 = CategoryObservationKindV2.ENTRIES,
    durationMillis: Long = 3_600_000L,
    items: List<UsageEvidence> = emptyList(),
) = TransitionEvidenceFrame(
    key = TransitionOccurrenceKey(id),
    evidence = TransitionEvidencePayload(
        temporal = TemporalEvidence(
            startAt = 10L,
            endAt = 10L + durationMillis,
            durationMillis = durationMillis,
            startBasis = "previous_check_in",
            completed = true,
        ),
        activity = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        workout = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        diet = CategoryEvidence(CategoryObservationKindV2.NONE, emptyList()),
        usage = CategoryEvidence(kind, items),
        wearables = emptyList(),
    ),
    metadata = TransitionEvidenceMetadata(completedAt = 50L),
)

private fun cigarettes(amount: String, intent: String = "COPING") = named("Cigarettes", amount, intent)

private fun coffee(amount: String) = UsageEvidence(
    optionId = "coffee",
    name = "Coffee",
    intent = "COPING",
    amountText = amount,
    reportedAt = 1L,
)

private fun named(name: String, amount: String, intent: String = "COPING") = UsageEvidence(
    optionId = null,
    name = name,
    intent = intent,
    amountText = amount,
    reportedAt = 1L,
)

private fun state(id: String, anxiety: Double) = StateEvidenceFrame(
    key = StateOccurrenceKey(id),
    evidence = StateEvidencePayload(
        axes = listOf(AxisEvidence("anxiety", (anxiety * 10).toInt(), anxiety)),
        coreMissingAxisIds = listOf("mood", "energy", "focus", "confidence"),
        conflictingAxisIds = emptyList(),
        emotion = null,
    ),
    metadata = StateEvidenceMetadata(
        recordedAt = 99L,
        localDate = "2099-01-02",
        trigger = "",
        note = "",
    ),
)
