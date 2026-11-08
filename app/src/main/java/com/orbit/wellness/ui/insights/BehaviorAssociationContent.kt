package com.orbit.wellness.ui.insights

import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.domain.checkin.AxisAssociation
import com.orbit.wellness.domain.checkin.BehaviorAssociationDataset
import com.orbit.wellness.domain.checkin.BehaviorAssociationProfile
import com.orbit.wellness.domain.checkin.BehaviorIdentity
import com.orbit.wellness.domain.checkin.BehaviorKind
import com.orbit.wellness.domain.checkin.BehaviorContextBalanceDataset
import com.orbit.wellness.domain.checkin.BehaviorContextBalanceProfile
import com.orbit.wellness.domain.checkin.BehaviorQuantityCoverage
import com.orbit.wellness.domain.checkin.CalendarAxisAssociation
import com.orbit.wellness.domain.checkin.CircularClockSummary
import com.orbit.wellness.domain.checkin.ContextGroupBalance
import com.orbit.wellness.domain.checkin.DescriptiveContrast
import com.orbit.wellness.domain.checkin.NearestOppositeSummary
import com.orbit.wellness.domain.checkin.OppositeContextMeasure
import com.orbit.wellness.domain.checkin.EmotionAssociation
import com.orbit.wellness.domain.checkin.EmotionDistribution
import com.orbit.wellness.domain.checkin.NumericSummary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object BehaviorAssociationCopy {
    const val STATE_MAP_PANE = "State map"
    const val ASSOCIATIONS_PANE = "Behavior associations"
    const val NOTICE = "Descriptive associations from recorded history; not causal or predictive."
    const val EMPTY = "Log activities, workouts, meals, or usage to inspect behavioral associations."
    const val NOT_ENOUGH_DATA = "Not enough data"
    const val PRESENCE_ONLY = "Presence only"
    const val BEFORE = "Before"
    const val AFTER = "After"
    const val CHANGE = "Change"
    const val PRESENT = "Present"
    const val ABSENT = "Absent"
    const val DIFFERENCE = "Difference"
    const val IDENTITY_KEY = "Identity key"
    const val SPREAD = "Spread"
    const val EMOTION_TOKENS = "Emotion tokens"
    const val DAY_WEIGHT = "Check-ins were averaged within each day before days were compared."
    const val STARTING_EMOTIONS = "Starting emotions"
    const val ENDING_EMOTIONS = "Ending emotions"
    const val UNITLESS = "Unitless"
    const val CONTEXT_BALANCE = "Context balance"
    const val CONTEXT_NOTICE =
        "These diagnostics show whether present and absent intervals occurred in comparable contexts. " +
            "Clock summaries describe logged intervals, not exact event times. " +
            "They do not adjust the association or identify a cause."
    const val WEAK_CLOCK_MEAN = "Mean clock time is weakly representative."
}

data class BehaviorAssociationContent(
    val protocol: String,
    val notice: String,
    val behaviorCount: Int,
    val intervalCount: Int,
    val calendarDayCount: Int,
    val excludedIntervalCount: Int,
    val profiles: List<BehaviorAssociationProfileView>,
    val emptyMessage: String,
)

data class BehaviorAssociationProfileView(
    val identity: BehaviorIdentity,
    val displayName: String,
    val kindLabel: String,
    val intentLabel: String?,
    val identityKey: String,
    val selectorLabel: String,
    val support: SupportPresentation,
    val axes: List<AxisPresentation>,
    val emotions: EmotionPresentation,
    val calendar: CalendarPresentation,
    val quantity: QuantityPresentation,
    val context: ContextBalancePresentation,
)

data class SupportPresentation(
    val presentIntervals: Int,
    val absentIntervals: Int,
    val unknownIntervals: Int,
    val excludedIntervals: Int,
    val presentOccurrences: Int,
)

data class AxisPresentation(
    val axisId: String,
    val label: String,
    val lenses: List<AxisLensPresentation>,
)

data class AxisLensPresentation(
    val label: String,
    val present: String?,
    val absent: String?,
    val difference: String?,
    val unavailable: Boolean,
    val presentSpread: String?,
    val absentSpread: String?,
)

data class EmotionPresentation(
    val starting: EmotionGroupPresentation,
    val ending: EmotionGroupPresentation,
)

data class EmotionGroupPresentation(
    val label: String,
    val present: EmotionSidePresentation,
    val absent: EmotionSidePresentation,
)

data class EmotionSidePresentation(
    val eligible: Int,
    val recorded: Int,
    val missing: Int,
    val tokens: List<EmotionTokenPresentation>,
)

data class EmotionTokenPresentation(
    val token: String,
    val count: Int,
)

data class CalendarPresentation(
    val presentDays: Int,
    val absentDays: Int,
    val unknownDays: Int,
    val dayWeightNote: String,
    val axes: List<CalendarAxisPresentation>,
)

data class CalendarAxisPresentation(
    val axisId: String,
    val label: String,
    val present: String?,
    val absent: String?,
    val difference: String?,
    val unavailable: Boolean,
)

data class ContextBalancePresentation(
    val notice: String,
    val present: ContextGroupPresentation,
    val absent: ContextGroupPresentation,
    val nearest: List<NearestGapPresentation>,
)

data class ContextGroupPresentation(
    val startClock: String?,
    val endClock: String?,
    val midpointClock: String?,
    val duration: String?,
    val timeSinceWakeStart: String?,
    val timeSinceWakeEnd: String?,
    val startingAxes: List<StartingAxisPresentation>,
)

data class StartingAxisPresentation(
    val label: String,
    val summary: String?,
)

data class NearestGapPresentation(
    val label: String,
    val summary: String?,
    val unavailablePresentCount: Int,
)

sealed interface QuantityPresentation {
    data object PresenceOnly : QuantityPresentation

    data class Workout(
        val intervals: Int,
        val sessions: Int,
        val minutes: String?,
        val minutesSpread: String?,
    ) : QuantityPresentation

    data class Usage(
        val parsedIntervals: Int,
        val missingIntervals: Int,
        val units: List<UsageUnitPresentation>,
    ) : QuantityPresentation
}

data class UsageUnitPresentation(
    val unitLabel: String,
    val total: String?,
    val totalSpread: String?,
    val perHour: String?,
    val perHourSpread: String?,
)

fun BehaviorAssociationDataset.toBehaviorAssociationContent(
    balance: BehaviorContextBalanceDataset,
): BehaviorAssociationContent {
    val balanceByIdentity = balance.profiles.associateBy { it.identity }
    return BehaviorAssociationContent(
        protocol = protocol,
        notice = BehaviorAssociationCopy.NOTICE,
        behaviorCount = diagnostics.behaviorCount,
        intervalCount = diagnostics.intervalCount,
        calendarDayCount = diagnostics.calendarDayCount,
        excludedIntervalCount = diagnostics.excludedIntervalCount,
        profiles = profiles.map { profile ->
            profile.toProfileView(balanceByIdentity.getValue(profile.identity))
        },
        emptyMessage = BehaviorAssociationCopy.EMPTY,
    )
}

/**
 * Keeps a selection that is still in the projected list. A missing selection
 * becomes the first profile in the already sorted list, or null when none remain.
 */
fun retainedBehaviorSelection(
    profiles: List<BehaviorAssociationProfileView>,
    selected: BehaviorIdentity?,
): BehaviorIdentity? {
    if (profiles.isEmpty()) return null
    if (selected != null && profiles.any { it.identity == selected }) return selected
    return profiles.first().identity
}

internal fun formatAssociationNumber(value: Double): String {
    require(value.isFinite()) { "An association figure must be finite" }
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    val nearest = normalized.toLong()
    if (normalized == nearest.toDouble()) return nearest.toString()
    return String.format(Locale.ROOT, "%.2f", normalized).trimEnd('0').trimEnd('.')
}

internal fun axisLenses(axis: AxisAssociation): List<AxisLensPresentation> {
    return listOf(
        lens(BehaviorAssociationCopy.BEFORE, axis.present.starting, axis.absent.starting, axis.startingContrast),
        lens(BehaviorAssociationCopy.AFTER, axis.present.ending, axis.absent.ending, axis.endingContrast),
        lens(BehaviorAssociationCopy.CHANGE, axis.present.delta, axis.absent.delta, axis.deltaContrast),
    )
}

private fun BehaviorAssociationProfile.toProfileView(
    balance: BehaviorContextBalanceProfile,
): BehaviorAssociationProfileView {
    val kindLabel = identity.kind.kindLabel()
    val intentLabel = identity.intent.intentLabel()
    val displayName = displayNames.joinToString(", ").ifBlank { identity.key }
    return BehaviorAssociationProfileView(
        identity = identity,
        displayName = displayName,
        kindLabel = kindLabel,
        intentLabel = intentLabel,
        identityKey = identity.key,
        selectorLabel = listOfNotNull(displayName, kindLabel, intentLabel).joinToString(" · "),
        support = SupportPresentation(
            presentIntervals = support.presentKeys.size,
            absentIntervals = support.absentKeys.size,
            unknownIntervals = support.unknownKeys.size,
            excludedIntervals = support.excludedKeys.size,
            presentOccurrences = support.presentOccurrences,
        ),
        axes = axes.map { axis ->
            AxisPresentation(
                axisId = axis.axisId,
                label = axis.axisId.axisLabel(),
                lenses = axisLenses(axis),
            )
        },
        emotions = emotions.toPresentation(),
        calendar = calendarDays.toPresentation(),
        quantity = quantity.toPresentation(),
        context = balance.toPresentation(),
    )
}

internal fun formatClockMinute(minute: Double): String {
    var rounded = minute.roundToInt() % com.orbit.wellness.domain.checkin.CLOCK_MINUTES_PER_DAY
    if (rounded < 0) rounded += com.orbit.wellness.domain.checkin.CLOCK_MINUTES_PER_DAY
    val hour24 = rounded / 60
    val mins = rounded % 60
    val suffix = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (val hour = hour24 % 12) {
        0 -> 12
        else -> hour
    }
    return String.format(Locale.ROOT, "%d:%02d %s", hour12, mins, suffix)
}

private fun formatCircularClock(summary: CircularClockSummary?): String? {
    if (summary == null) return null
    val base = "${formatClockMinute(summary.meanMinute)} " +
        "(n=${summary.count}, concentration ${formatAssociationNumber(summary.concentration)})"
    return if (summary.meanWeaklyRepresentative) {
        "$base. ${BehaviorAssociationCopy.WEAK_CLOCK_MEAN}"
    } else {
        base
    }
}

private fun BehaviorContextBalanceProfile.toPresentation(): ContextBalancePresentation {
    return ContextBalancePresentation(
        notice = BehaviorAssociationCopy.CONTEXT_NOTICE,
        present = present.toGroup(),
        absent = absent.toGroup(),
        nearest = nearest.map { it.toGap() },
    )
}

private fun ContextGroupBalance.toGroup(): ContextGroupPresentation {
    return ContextGroupPresentation(
        startClock = formatCircularClock(startClock),
        endClock = formatCircularClock(endClock),
        midpointClock = formatCircularClock(midpointClock),
        duration = durationHours?.let { "${formatMean(it)} h" },
        timeSinceWakeStart = timeSinceWakeStartHours?.let { "${formatMean(it)} h" },
        timeSinceWakeEnd = timeSinceWakeEndHours?.let { "${formatMean(it)} h" },
        startingAxes = startingAxes.map { axis ->
            StartingAxisPresentation(
                label = axis.axisId.axisLabel(),
                summary = axis.summary?.let(::formatMean),
            )
        },
    )
}

private fun NearestOppositeSummary.toGap(): NearestGapPresentation {
    val unit = when (measure) {
        OppositeContextMeasure.END_CLOCK, OppositeContextMeasure.MIDPOINT_CLOCK -> "min"
        OppositeContextMeasure.STARTING_AXIS_RMS -> "points"
        OppositeContextMeasure.TIME_SINCE_WAKE, OppositeContextMeasure.DURATION -> "h"
    }
    return NearestGapPresentation(
        label = when (measure) {
            OppositeContextMeasure.END_CLOCK -> "End-clock gap"
            OppositeContextMeasure.MIDPOINT_CLOCK -> "Midpoint-clock gap"
            OppositeContextMeasure.STARTING_AXIS_RMS -> "Starting-state RMS gap"
            OppositeContextMeasure.TIME_SINCE_WAKE -> "Time-since-wake gap"
            OppositeContextMeasure.DURATION -> "Duration gap"
        },
        summary = distances?.let { "${formatAssociationNumber(it.mean)} $unit (n=${it.count})" },
        unavailablePresentCount = unavailablePresentCount,
    )
}

private fun lens(
    label: String,
    present: NumericSummary?,
    absent: NumericSummary?,
    contrast: DescriptiveContrast?,
): AxisLensPresentation {
    return AxisLensPresentation(
        label = label,
        present = present?.let(::formatMean),
        absent = absent?.let(::formatMean),
        difference = contrast?.let { formatAssociationNumber(it.difference) },
        unavailable = contrast == null,
        presentSpread = present?.let(::formatSpread),
        absentSpread = absent?.let(::formatSpread),
    )
}

private fun formatMean(summary: NumericSummary): String {
    return "${formatAssociationNumber(summary.mean)} (n=${summary.count})"
}

private fun formatSpread(summary: NumericSummary): String {
    return "deviation ${formatAssociationNumber(summary.deviation)}, " +
        "min ${formatAssociationNumber(summary.minimum)}, " +
        "max ${formatAssociationNumber(summary.maximum)}"
}

private fun EmotionAssociation.toPresentation(): EmotionPresentation {
    return EmotionPresentation(
        starting = EmotionGroupPresentation(
            label = BehaviorAssociationCopy.STARTING_EMOTIONS,
            present = presentStarting.toSide(),
            absent = absentStarting.toSide(),
        ),
        ending = EmotionGroupPresentation(
            label = BehaviorAssociationCopy.ENDING_EMOTIONS,
            present = presentEnding.toSide(),
            absent = absentEnding.toSide(),
        ),
    )
}

private fun EmotionDistribution.toSide(): EmotionSidePresentation {
    return EmotionSidePresentation(
        eligible = eligibleCount,
        recorded = recordedCount,
        missing = missingCount,
        tokens = tokens.map { EmotionTokenPresentation(token = it.token, count = it.count) },
    )
}

private fun com.orbit.wellness.domain.checkin.CalendarDayAssociation.toPresentation(): CalendarPresentation {
    return CalendarPresentation(
        presentDays = presentDayCount,
        absentDays = absentDayCount,
        unknownDays = unknownDayCount,
        dayWeightNote = BehaviorAssociationCopy.DAY_WEIGHT,
        axes = axes.map { it.toPresentation() },
    )
}

private fun CalendarAxisAssociation.toPresentation(): CalendarAxisPresentation {
    return CalendarAxisPresentation(
        axisId = axisId,
        label = axisId.axisLabel(),
        present = present?.let(::formatMean),
        absent = absent?.let(::formatMean),
        difference = contrast?.let { formatAssociationNumber(it.difference) },
        unavailable = contrast == null,
    )
}

private fun BehaviorQuantityCoverage.toPresentation(): QuantityPresentation {
    return when (this) {
        BehaviorQuantityCoverage.PresenceOnly -> QuantityPresentation.PresenceOnly
        is BehaviorQuantityCoverage.WorkoutMinutes -> QuantityPresentation.Workout(
            intervals = intervalCount,
            sessions = sessionCount,
            minutes = minutes?.let(::formatMean),
            minutesSpread = minutes?.let(::formatSpread),
        )
        is BehaviorQuantityCoverage.UsageAmounts -> QuantityPresentation.Usage(
            parsedIntervals = parsedIntervalCount,
            missingIntervals = missingOrUnparsedIntervalCount,
            units = units.map { unit ->
                UsageUnitPresentation(
                    unitLabel = unit.unit ?: BehaviorAssociationCopy.UNITLESS,
                    total = unit.totalAmount?.let(::formatMean),
                    totalSpread = unit.totalAmount?.let(::formatSpread),
                    perHour = unit.perHour?.let(::formatMean),
                    perHourSpread = unit.perHour?.let(::formatSpread),
                )
            },
        )
    }
}

private fun BehaviorKind.kindLabel(): String = when (this) {
    BehaviorKind.ACTIVITY -> "Activity"
    BehaviorKind.WORKOUT -> "Workout"
    BehaviorKind.FOOD -> "Food"
    BehaviorKind.MEAL_SIZE -> "Meal size"
    BehaviorKind.USAGE -> "Usage"
}

private fun String?.intentLabel(): String? = when (this) {
    null -> null
    SubstanceIntent.SUPPORTIVE.name -> "Supportive"
    SubstanceIntent.COPING.name -> "Coping"
    else -> this
}

private fun String.axisLabel(): String = when (this) {
    DefaultCheckInAxes.MOOD -> "Mood"
    DefaultCheckInAxes.ENERGY -> "Energy"
    DefaultCheckInAxes.CONFIDENCE -> "Confidence"
    DefaultCheckInAxes.ANXIETY -> "Anxiety"
    else -> this
}
