package com.orbit.wellness.domain.checkin

import kotlin.math.sqrt

/**
 * Equal weight applied to every substantive block before the within-block factor.
 * Later research can replace this constant. It is not learned here.
 */
const val EQUAL_BLOCK_WEIGHT = 1.0

/**
 * Fitted preparation for one encoded dataset, plus the named layouts of the
 * vectors [projectLocalContext] builds from it.
 *
 * Statistics come from [EncodedFeatures.states] and [EncodedFeatures.transitions]
 * only. Causal targets are not added again. Fitting the dataset in hand describes
 * that dataset. A held-out evaluation fits on training vectors and passes the
 * model back into [projectLocalContext].
 */
data class FeaturePreparationModel(
    val state: PreparedChannelSchema,
    val transition: PreparedChannelSchema,
    val layouts: LocalContextLayouts,
)

data class PreparedChannelSchema(
    val substantive: VectorSegment,
    val quality: VectorSegment,
    val blocks: List<BlockBalance>,
    val sourceSemantics: List<FeatureSemantic>,
) {
    init {
        val covered = (substantive.features + quality.features).map { it.sourceIndex }.sorted()
        require(covered == sourceSemantics.indices.toList()) {
            "Preparation must account for every source feature once"
        }
    }
}

data class VectorSegment(
    val name: String,
    val features: List<FeatureScale>,
) {
    init {
        features.forEachIndexed { index, feature ->
            require(feature.index == index) { "${feature.sourceName} is not at $index in $name" }
        }
    }

    val size: Int
        get() = features.size
}

/**
 * Scaling for one source feature.
 *
 * [center] and [scale] describe substantive numeric features. [scale] is the
 * root-mean-square deviation of the observed values from [center]: the sum of
 * squared deviations divided by the observed count, then the square root.
 * A sample-size correction is not applied. Zero observations, or a deviation
 * of zero, set [nonvarying]. Binary features keep [center] `0` and [scale] `1`
 * and are not marked nonvarying, so a constant presence bit stays 0 or 1.
 * [blockFactor] is [blockWeight] divided by the square root of the substantive
 * column count of [substantiveBlock]. Quality features use weight `0` and
 * factor `1`; they are copied, not standardized.
 */
data class FeatureScale(
    val index: Int,
    val sourceIndex: Int,
    val sourceName: String,
    val sourceSemantic: FeatureSemantic,
    val sourceBlock: FeatureBlock,
    val role: FeatureRole,
    val scaling: FeatureScaling,
    val substantiveBlock: SubstantiveBlock?,
    val observedCount: Int,
    val center: Double,
    val scale: Double,
    val nonvarying: Boolean,
    val blockWeight: Double,
    val blockFactor: Double,
)

enum class FeatureRole {
    SUBSTANTIVE,
    QUALITY,
}

enum class FeatureScaling {
    /** Observed 0/1 stays 0/1, then the block factor is applied. */
    BINARY,
    /** Observed mean and root-mean-square deviation. */
    STANDARDIZE,
    /** Quality channel. The encoded value is copied. */
    PASSTHROUGH,
}

enum class SubstantiveBlock {
    STATE_AXES,
    STATE_EMOTION,
    DURATION,
    ACTIVITY,
    WORKOUT,
    DIET,
    USAGE,
    WEARABLE,
}

data class BlockBalance(
    val block: SubstantiveBlock,
    val dimensionCount: Int,
    val weight: Double,
    val factor: Double,
) {
    init {
        require(dimensionCount > 0) { "$block has no substantive dimensions" }
        require(factor.isFinite() && factor > 0.0) { "$block factor must be finite" }
    }
}

data class LocalContextLayouts(
    val retrospectiveTransition: RetrospectiveLayout,
    val incomingMessage: MessageLayout,
    val outgoingMessage: MessageLayout,
    val contextualState: ContextualStateLayout,
    val causalInput: CausalInputLayout,
    val causalTarget: CausalTargetLayout,
)

data class RetrospectiveLayout(
    val self: VectorSegment,
    val startState: VectorSegment,
    val endState: VectorSegment,
    val quality: VectorSegment,
)

data class MessageLayout(
    val direction: MessageDirection,
    val transition: VectorSegment,
    val oppositeState: VectorSegment,
)

data class ContextualStateLayout(
    val self: VectorSegment,
    val incomingTransition: VectorSegment,
    val incomingOppositeState: VectorSegment,
    val outgoingTransition: VectorSegment,
    val outgoingOppositeState: VectorSegment,
    val quality: VectorSegment,
)

data class CausalInputLayout(
    val startingState: VectorSegment,
    val startingQuality: VectorSegment,
    val transition: VectorSegment,
    val transitionQuality: VectorSegment,
)

data class CausalTargetLayout(
    val substantive: VectorSegment,
    val quality: VectorSegment,
)

enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

data class MaskedVector(
    val values: List<Double>,
    val observed: List<Boolean>,
) {
    init {
        require(values.size == observed.size) { "A prepared vector needs one mask bit per value" }
        require(values.all { it.isFinite() }) { "A prepared value must be finite" }
    }
}

data class PreparedChannel(
    val substantive: MaskedVector,
    val quality: MaskedVector,
)

data class PreparedState(
    val key: StateOccurrenceKey,
    val channel: PreparedChannel,
)

data class PreparedTransition(
    val key: TransitionOccurrenceKey,
    val channel: PreparedChannel,
)

data class RetrospectiveTransitionContext(
    val key: TransitionOccurrenceKey,
    val self: MaskedVector,
    val startState: MaskedVector,
    val endState: MaskedVector,
    val startStateKey: StateOccurrenceKey?,
    val endStateKey: StateOccurrenceKey?,
    val startAvailable: Boolean,
    val endAvailable: Boolean,
    val quality: MaskedVector,
)

data class DirectionalMessage(
    val focalStateKey: StateOccurrenceKey,
    val direction: MessageDirection,
    val transitionKey: TransitionOccurrenceKey,
    val oppositeStateKey: StateOccurrenceKey?,
    val transition: MaskedVector,
    val oppositeState: MaskedVector,
    val oppositeStateAvailable: Boolean,
)

data class PooledSegment(
    val values: List<Double>,
    val observed: List<Boolean>,
    val contributingCounts: List<Int>,
) {
    init {
        require(values.size == observed.size && values.size == contributingCounts.size) {
            "A pooled segment needs a value, a mask bit, and a count"
        }
        require(values.all { it.isFinite() }) { "A pooled value must be finite" }
    }
}

data class PooledDirectionalContext(
    val available: Boolean,
    val messageCount: Int,
    val transition: PooledSegment,
    val oppositeState: PooledSegment,
)

data class ContextualState(
    /** Occurrence identity. It is not a column of [self], [incoming], or [outgoing]. */
    val key: StateOccurrenceKey,
    val self: MaskedVector,
    val incoming: PooledDirectionalContext,
    val outgoing: PooledDirectionalContext,
    val quality: MaskedVector,
)

data class PreparedCausalInput(
    val startingState: PreparedChannel?,
    val transition: PreparedChannel,
)

data class PreparedCausalExample(
    /** Diagnostic identity. It is not a column of [input] or [target]. */
    val exampleKey: TransitionOccurrenceKey,
    val input: PreparedCausalInput,
    val target: PreparedChannel,
    val eligibility: CausalEligibility,
)

enum class ContextDiagnosticCode {
    MISSING_ENDPOINT_LINK,
    START_STATE_MISSING,
    END_STATE_UNRESOLVED,
    RETROSPECTIVE_TRANSITION_NOT_ENCODED,
}

data class ContextDiagnostic(
    val code: ContextDiagnosticCode,
    val transitionKey: TransitionOccurrenceKey?,
    val detail: String,
)

data class LocalContext(
    val model: FeaturePreparationModel,
    val states: List<PreparedState>,
    val transitions: List<PreparedTransition>,
    val retrospectiveTransitions: List<RetrospectiveTransitionContext>,
    val incomingMessages: List<DirectionalMessage>,
    val outgoingMessages: List<DirectionalMessage>,
    val contextualStates: List<ContextualState>,
    val causalExamples: List<PreparedCausalExample>,
    val eligibleCausalExamples: List<PreparedCausalExample>,
    val diagnostics: List<ContextDiagnostic>,
)

/**
 * How a typed feature enters preparation.
 *
 * Presence bits stay binary. Axis values, durations, counts, complete usage
 * amounts and rates, and wearable summaries are standardized. Masks, protocol,
 * ambiguity, amount completeness, and usage parse counts stay in the quality
 * channel. Entry counts stay there as well: presence already records that the
 * usage occurred, and the entry count is not a parsed quantity.
 */
fun preparationOf(semantic: FeatureSemantic): FeaturePlacement {
    return when (semantic) {
        is FeatureSemantic.AxisValue -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.STATE_AXES)
        is FeatureSemantic.EmotionPresent -> substantive(FeatureScaling.BINARY, SubstantiveBlock.STATE_EMOTION)
        FeatureSemantic.DurationHours -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.DURATION)
        is FeatureSemantic.ActivityPresent -> substantive(FeatureScaling.BINARY, SubstantiveBlock.ACTIVITY)
        is FeatureSemantic.WorkoutPresent -> substantive(FeatureScaling.BINARY, SubstantiveBlock.WORKOUT)
        is FeatureSemantic.WorkoutMinutes -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.WORKOUT)
        is FeatureSemantic.MealSizeCount -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.DIET)
        is FeatureSemantic.FoodPresent -> substantive(FeatureScaling.BINARY, SubstantiveBlock.DIET)
        is FeatureSemantic.FoodCount -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.DIET)
        is FeatureSemantic.Usage -> when (semantic.measurement) {
            UsageMeasurement.PRESENT -> substantive(FeatureScaling.BINARY, SubstantiveBlock.USAGE)
            UsageMeasurement.AMOUNT,
            UsageMeasurement.PER_HOUR,
            -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.USAGE)
            UsageMeasurement.ENTRIES,
            UsageMeasurement.PARSED,
            UsageMeasurement.MISSING,
            UsageMeasurement.UNPARSED,
            UsageMeasurement.AMOUNT_COMPLETE,
            -> quality()
        }
        is FeatureSemantic.WearableValue,
        is FeatureSemantic.WearableSample,
        -> substantive(FeatureScaling.STANDARDIZE, SubstantiveBlock.WEARABLE)
        is FeatureSemantic.AxisObserved,
        is FeatureSemantic.AxisConflicting,
        FeatureSemantic.EmotionObserved,
        FeatureSemantic.ProtocolCompleted,
        is FeatureSemantic.StartBasis,
        is FeatureSemantic.CategoryStatus,
        is FeatureSemantic.WearableAmbiguous,
        -> quality()
    }
}

data class FeaturePlacement(
    val role: FeatureRole,
    val scaling: FeatureScaling,
    val block: SubstantiveBlock?,
)

fun fitFeaturePreparation(encoded: EncodedFeatures): FeaturePreparationModel {
    val stateRows = encoded.states.sortedBy { it.key.raw }.map { it.vector }
    val transitionRows = encoded.transitions.sortedBy { it.key.raw }.map { it.vector }
    val state = fitChannel(
        schema = encoded.schemas.state,
        rows = stateRows,
        substantiveName = "state.self",
        qualityName = "state.quality",
    )
    val transition = fitChannel(
        schema = encoded.schemas.transition,
        rows = transitionRows,
        substantiveName = "transition.self",
        qualityName = "transition.quality",
    )
    return FeaturePreparationModel(
        state = state,
        transition = transition,
        layouts = layouts(state, transition),
    )
}

fun projectLocalContext(
    evidence: ContextualEvidence,
    encoded: EncodedFeatures,
    model: FeaturePreparationModel = fitFeaturePreparation(encoded),
): LocalContext {
    require(encoded.schemas.state.features.map { it.semantic } == model.state.sourceSemantics) {
        "State schema does not match the fitted preparation"
    }
    require(encoded.schemas.transition.features.map { it.semantic } == model.transition.sourceSemantics) {
        "Transition schema does not match the fitted preparation"
    }
    val states = encoded.states
        .sortedBy { it.key.raw }
        .map { PreparedState(it.key, prepareChannel(it.vector, model.state)) }
    val transitions = encoded.transitions
        .sortedBy { it.key.raw }
        .map { PreparedTransition(it.key, prepareChannel(it.vector, model.transition)) }
    val stateByKey = states.associateBy { it.key }
    val transitionByKey = transitions.associateBy { it.key }
    val diagnostics = mutableListOf<ContextDiagnostic>()
    val retrospective = mutableListOf<RetrospectiveTransitionContext>()
    val incoming = mutableListOf<DirectionalMessage>()
    val outgoing = mutableListOf<DirectionalMessage>()
    val linkedKeys = evidence.retrospectiveTransitions.map { it.frame.key }.toSet()
    transitions.forEach { prepared ->
        if (prepared.key !in linkedKeys) {
            diagnostics += ContextDiagnostic(
                code = ContextDiagnosticCode.MISSING_ENDPOINT_LINK,
                transitionKey = prepared.key,
                detail = "Encoded transition ${prepared.key.raw} has no retrospective endpoint link",
            )
        }
    }
    evidence.retrospectiveTransitions
        .sortedBy { it.frame.key.raw }
        .forEach { link ->
            val prepared = transitionByKey[link.frame.key]
            if (prepared == null) {
                diagnostics += ContextDiagnostic(
                    code = ContextDiagnosticCode.RETROSPECTIVE_TRANSITION_NOT_ENCODED,
                    transitionKey = link.frame.key,
                    detail = "Retrospective transition ${link.frame.key.raw} has no encoded vector",
                )
                return@forEach
            }
            val start = resolveEndpoint(
                key = link.startStateKey,
                states = stateByKey,
                width = model.state.substantive.size,
                missingCode = ContextDiagnosticCode.START_STATE_MISSING,
                transitionKey = link.frame.key,
                diagnostics = diagnostics,
            )
            val end = resolveEndpoint(
                key = link.endStateKey,
                states = stateByKey,
                width = model.state.substantive.size,
                missingCode = ContextDiagnosticCode.END_STATE_UNRESOLVED,
                transitionKey = link.frame.key,
                diagnostics = diagnostics,
            )
            retrospective += RetrospectiveTransitionContext(
                key = link.frame.key,
                self = prepared.channel.substantive,
                startState = start.vector,
                endState = end.vector,
                startStateKey = start.resolvedKey,
                endStateKey = end.resolvedKey,
                startAvailable = start.available,
                endAvailable = end.available,
                quality = prepared.channel.quality,
            )
            if (end.resolvedKey != null) {
                incoming += message(
                    focal = end.resolvedKey,
                    direction = MessageDirection.INCOMING,
                    transition = prepared,
                    opposite = start,
                )
            }
            if (start.resolvedKey != null) {
                outgoing += message(
                    focal = start.resolvedKey,
                    direction = MessageDirection.OUTGOING,
                    transition = prepared,
                    opposite = end,
                )
            }
        }
    val incomingByState = incoming.groupBy { it.focalStateKey }
    val outgoingByState = outgoing.groupBy { it.focalStateKey }
    val contextual = states.map { state ->
        ContextualState(
            key = state.key,
            self = state.channel.substantive,
            incoming = pool(
                incomingByState[state.key].orEmpty(),
                model.transition.substantive.size,
                model.state.substantive.size,
            ),
            outgoing = pool(
                outgoingByState[state.key].orEmpty(),
                model.transition.substantive.size,
                model.state.substantive.size,
            ),
            quality = state.channel.quality,
        )
    }
    val causal = encoded.causalExamples
        .sortedBy { it.exampleKey.raw }
        .map { example -> prepareCausal(example, model) }
    return LocalContext(
        model = model,
        states = states,
        transitions = transitions,
        retrospectiveTransitions = retrospective.sortedBy { it.key.raw },
        incomingMessages = incoming.sortedWith(compareBy({ it.focalStateKey.raw }, { it.transitionKey.raw })),
        outgoingMessages = outgoing.sortedWith(compareBy({ it.focalStateKey.raw }, { it.transitionKey.raw })),
        contextualStates = contextual,
        causalExamples = causal,
        eligibleCausalExamples = causal.filter { it.eligibility.eligible },
        diagnostics = diagnostics.sortedWith(compareBy({ it.transitionKey?.raw ?: "" }, { it.code.name })),
    )
}

private fun prepareCausal(example: EncodedCausalExample, model: FeaturePreparationModel): PreparedCausalExample {
    return PreparedCausalExample(
        exampleKey = example.exampleKey,
        input = PreparedCausalInput(
            startingState = example.startingState?.let { prepareChannel(it, model.state) },
            transition = prepareChannel(example.transition, model.transition),
        ),
        target = prepareChannel(example.target, model.state),
        eligibility = example.eligibility,
    )
}

private data class ResolvedEndpoint(
    val resolvedKey: StateOccurrenceKey?,
    val vector: MaskedVector,
    val available: Boolean,
)

private fun resolveEndpoint(
    key: StateOccurrenceKey?,
    states: Map<StateOccurrenceKey, PreparedState>,
    width: Int,
    missingCode: ContextDiagnosticCode,
    transitionKey: TransitionOccurrenceKey,
    diagnostics: MutableList<ContextDiagnostic>,
): ResolvedEndpoint {
    if (key == null) {
        return ResolvedEndpoint(null, unavailable(width), available = false)
    }
    val state = states[key]
    if (state == null) {
        diagnostics += ContextDiagnostic(
            code = missingCode,
            transitionKey = transitionKey,
            detail = "Endpoint ${key.raw} does not resolve for ${transitionKey.raw}",
        )
        return ResolvedEndpoint(null, unavailable(width), available = false)
    }
    return ResolvedEndpoint(key, state.channel.substantive, available = true)
}

private fun message(
    focal: StateOccurrenceKey,
    direction: MessageDirection,
    transition: PreparedTransition,
    opposite: ResolvedEndpoint,
): DirectionalMessage {
    return DirectionalMessage(
        focalStateKey = focal,
        direction = direction,
        transitionKey = transition.key,
        oppositeStateKey = opposite.resolvedKey,
        transition = transition.channel.substantive,
        oppositeState = opposite.vector,
        oppositeStateAvailable = opposite.available,
    )
}

private fun pool(messages: List<DirectionalMessage>, transitionWidth: Int, stateWidth: Int): PooledDirectionalContext {
    if (messages.isEmpty()) {
        return PooledDirectionalContext(
            available = false,
            messageCount = 0,
            transition = emptyPool(transitionWidth),
            oppositeState = emptyPool(stateWidth),
        )
    }
    val ordered = messages.sortedBy { it.transitionKey.raw }
    return PooledDirectionalContext(
        available = true,
        messageCount = ordered.size,
        transition = poolSegment(ordered.map { it.transition }),
        oppositeState = poolSegment(ordered.map { it.oppositeState }),
    )
}

private fun poolSegment(columns: List<MaskedVector>): PooledSegment {
    val width = columns.first().values.size
    val sums = DoubleArray(width)
    val counts = IntArray(width)
    columns.forEach { column ->
        require(column.values.size == width) { "Messages in one pool have different widths" }
        column.values.forEachIndexed { index, value ->
            if (column.observed[index]) {
                sums[index] += value
                counts[index] += 1
            }
        }
    }
    val values = List(width) { index -> if (counts[index] == 0) 0.0 else sums[index] / counts[index] }
    val observed = List(width) { index -> counts[index] > 0 }
    return PooledSegment(values, observed, counts.toList())
}

private fun emptyPool(width: Int): PooledSegment {
    return PooledSegment(List(width) { 0.0 }, List(width) { false }, List(width) { 0 })
}

private fun unavailable(width: Int): MaskedVector {
    return MaskedVector(List(width) { 0.0 }, List(width) { false })
}

private fun fitChannel(
    schema: FeatureSchema,
    rows: List<EncodedVector>,
    substantiveName: String,
    qualityName: String,
): PreparedChannelSchema {
    rows.forEach { row ->
        require(row.values.size == schema.size) { "Encoded vector does not match ${substantiveName.substringBefore('.')}" }
    }
    val fitted = schema.features.map { feature ->
        val placement = preparationOf(feature.semantic)
        val samples = rows.mapNotNull { row ->
            val value = row.values[feature.index]
            if (row.observed[feature.index] && value.isFinite()) value else null
        }
        val statistic = statistic(placement.scaling, samples)
        FittedFeature(feature, placement, statistic)
    }
    val blocks = SubstantiveBlock.entries.mapNotNull { block ->
        val count = fitted.count { it.placement.block == block }
        if (count == 0) {
            null
        } else {
            BlockBalance(
                block = block,
                dimensionCount = count,
                weight = EQUAL_BLOCK_WEIGHT,
                factor = EQUAL_BLOCK_WEIGHT / sqrt(count.toDouble()),
            )
        }
    }
    val factorByBlock = blocks.associate { it.block to it }
    val substantive = fitted
        .filter { it.placement.role == FeatureRole.SUBSTANTIVE }
        .sortedWith(compareBy({ it.placement.block!!.ordinal }, { it.feature.index }))
        .mapIndexed { index, item ->
            val balance = factorByBlock.getValue(checkNotNull(item.placement.block))
            item.scale(
                index = index,
                blockWeight = balance.weight,
                blockFactor = balance.factor,
            )
        }
    val quality = fitted
        .filter { it.placement.role == FeatureRole.QUALITY }
        .sortedBy { it.feature.index }
        .mapIndexed { index, item ->
            item.scale(index = index, blockWeight = 0.0, blockFactor = 1.0)
        }
    return PreparedChannelSchema(
        substantive = VectorSegment(substantiveName, substantive),
        quality = VectorSegment(qualityName, quality),
        blocks = blocks,
        sourceSemantics = schema.features.map { it.semantic },
    )
}

private data class FittedFeature(
    val feature: FeatureDefinition,
    val placement: FeaturePlacement,
    val statistic: ColumnStatistic,
) {
    fun scale(index: Int, blockWeight: Double, blockFactor: Double): FeatureScale {
        return FeatureScale(
            index = index,
            sourceIndex = feature.index,
            sourceName = feature.name,
            sourceSemantic = feature.semantic,
            sourceBlock = feature.block,
            role = placement.role,
            scaling = placement.scaling,
            substantiveBlock = placement.block,
            observedCount = statistic.observedCount,
            center = statistic.center,
            scale = statistic.scale,
            nonvarying = statistic.nonvarying,
            blockWeight = blockWeight,
            blockFactor = blockFactor,
        )
    }
}

private data class ColumnStatistic(
    val observedCount: Int,
    val center: Double,
    val scale: Double,
    val nonvarying: Boolean,
)

private fun statistic(scaling: FeatureScaling, samples: List<Double>): ColumnStatistic {
    if (scaling != FeatureScaling.STANDARDIZE) {
        return ColumnStatistic(observedCount = samples.size, center = 0.0, scale = 1.0, nonvarying = false)
    }
    if (samples.isEmpty()) {
        return ColumnStatistic(observedCount = 0, center = 0.0, scale = 0.0, nonvarying = true)
    }
    val count = samples.size
    val center = samples.sum() / count
    val deviation = sqrt(samples.sumOf { value ->
        val delta = value - center
        delta * delta
    } / count)
    return if (deviation == 0.0) {
        ColumnStatistic(observedCount = count, center = center, scale = 0.0, nonvarying = true)
    } else {
        ColumnStatistic(observedCount = count, center = center, scale = deviation, nonvarying = false)
    }
}

private fun prepareChannel(vector: EncodedVector, schema: PreparedChannelSchema): PreparedChannel {
    require(vector.values.size == schema.sourceSemantics.size) { "Encoded vector does not match the preparation" }
    return PreparedChannel(
        substantive = render(vector, schema.substantive),
        quality = render(vector, schema.quality),
    )
}

private fun render(vector: EncodedVector, segment: VectorSegment): MaskedVector {
    val values = ArrayList<Double>(segment.size)
    val observed = ArrayList<Boolean>(segment.size)
    segment.features.forEach { feature ->
        val raw = vector.values[feature.sourceIndex]
        val wasObserved = vector.observed[feature.sourceIndex] && raw.isFinite()
        if (!wasObserved) {
            values += 0.0
            observed += false
            return@forEach
        }
        val centered = when (feature.scaling) {
            FeatureScaling.BINARY,
            FeatureScaling.PASSTHROUGH,
            -> raw
            FeatureScaling.STANDARDIZE -> if (feature.nonvarying) 0.0 else (raw - feature.center) / feature.scale
        }
        val balanced = if (feature.role == FeatureRole.QUALITY) centered else centered * feature.blockFactor
        require(balanced.isFinite()) { "${feature.sourceName} did not stay finite" }
        values += balanced
        observed += true
    }
    return MaskedVector(values, observed)
}

private fun layouts(state: PreparedChannelSchema, transition: PreparedChannelSchema): LocalContextLayouts {
    val stateGeometry = state.substantive.features
    val transitionGeometry = transition.substantive.features
    val self = state.substantive
    val transitionSelf = transition.substantive
    val start = VectorSegment("transition.start_state", reindex(stateGeometry))
    val end = VectorSegment("transition.end_state", reindex(stateGeometry))
    val incomingTransition = VectorSegment("state.incoming_context.transition", reindex(transitionGeometry))
    val incomingState = VectorSegment("state.incoming_context.opposite_state", reindex(stateGeometry))
    val outgoingTransition = VectorSegment("state.outgoing_context.transition", reindex(transitionGeometry))
    val outgoingState = VectorSegment("state.outgoing_context.opposite_state", reindex(stateGeometry))
    return LocalContextLayouts(
        retrospectiveTransition = RetrospectiveLayout(
            self = transitionSelf,
            startState = start,
            endState = end,
            quality = transition.quality,
        ),
        incomingMessage = MessageLayout(
            direction = MessageDirection.INCOMING,
            transition = incomingTransition,
            oppositeState = incomingState,
        ),
        outgoingMessage = MessageLayout(
            direction = MessageDirection.OUTGOING,
            transition = outgoingTransition,
            oppositeState = outgoingState,
        ),
        contextualState = ContextualStateLayout(
            self = self,
            incomingTransition = incomingTransition,
            incomingOppositeState = incomingState,
            outgoingTransition = outgoingTransition,
            outgoingOppositeState = outgoingState,
            quality = state.quality,
        ),
        causalInput = CausalInputLayout(
            startingState = self.named("causal.input.starting_state"),
            startingQuality = state.quality.named("causal.input.starting_quality"),
            transition = transitionSelf.named("causal.input.transition"),
            transitionQuality = transition.quality.named("causal.input.transition_quality"),
        ),
        causalTarget = CausalTargetLayout(
            substantive = self.named("causal.target"),
            quality = state.quality.named("causal.target.quality"),
        ),
    )
}

private fun VectorSegment.named(name: String): VectorSegment = VectorSegment(name, features)

private fun reindex(features: List<FeatureScale>): List<FeatureScale> {
    return features.mapIndexed { index, feature -> feature.copy(index = index) }
}

private fun substantive(scaling: FeatureScaling, block: SubstantiveBlock): FeaturePlacement {
    return FeaturePlacement(FeatureRole.SUBSTANTIVE, scaling, block)
}

private fun quality(): FeaturePlacement {
    return FeaturePlacement(FeatureRole.QUALITY, FeatureScaling.PASSTHROUGH, block = null)
}
