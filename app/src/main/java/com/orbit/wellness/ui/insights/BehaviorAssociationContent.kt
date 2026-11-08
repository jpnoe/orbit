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
import com.orbit.wellness.domain.checkin.BehaviorExposureEpisode
import com.orbit.wellness.domain.checkin.BehaviorExposureEpisodeDataset
import com.orbit.wellness.domain.checkin.BehaviorExposureProfile
import com.orbit.wellness.domain.checkin.ExposureBreakReason
import com.orbit.wellness.domain.checkin.ExposureOccurrence
import com.orbit.wellness.domain.checkin.PresenceContrastAvailability
import com.orbit.wellness.domain.checkin.UsageUnitTotal
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
    const val EXPOSURE_EPISODES = "Exposure episodes"
    const val PRESENCE_CONTRAST_UNAVAILABLE =
        "Presence contrast unavailable. No reviewed absent interval was recorded."
    const val NO_PRESENT_INTERVALS = "No present intervals"
    const val EPISODE_NOTICE =
        "A listed behavior was reported on that interval. The reported window is not a continuous duration, " +
            "and this does not identify a cause."
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
    val episodes: ExposureEpisodesPresentation,
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

data class ExposureEpisodesPresentation(
    val episodeCount: Int,
    val presenceContrastNote: String?,
    val emptyPresentNote: String?,
    val notice: String,
    val episodes: List<ExposureEpisodePresentation>,
)

data class ExposureEpisodePresentation(
    val title: String,
    val coverage: String,
    val structure: String,
    val trajectory: List<String>,
    val coExposures: String,
    val breaks: String,
    val quantities: List<String>,
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
    episodes: BehaviorExposureEpisodeDataset,
): BehaviorAssociationContent {
    val balanceByIdentity = balance.profiles.associateBy { it.identity }
    val episodesByIdentity = episodes.profiles.associateBy { it.identity }
    return BehaviorAssociationContent(
        protocol = protocol,
        notice = BehaviorAssociationCopy.NOTICE,
        behaviorCount = diagnostics.behaviorCount,
        intervalCount = diagnostics.intervalCount,
        calendarDayCount = diagnostics.calendarDayCount,
        excludedIntervalCount = diagnostics.excludedIntervalCount,
        profiles = profiles.map { profile ->
            profile.toProfileView(
                balanceByIdentity.getValue(profile.identity),
                episodesByIdentity.getValue(profile.identity),
            )
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
    episodes: BehaviorExposureProfile,
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
        episodes = episodes.toPresentation(),
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

private fun BehaviorExposureProfile.toPresentation(): ExposureEpisodesPresentation {
    return ExposureEpisodesPresentation(
        episodeCount = episodes.size,
        presenceContrastNote = if (presenceContrast == PresenceContrastAvailability.UNAVAILABLE) {
            BehaviorAssociationCopy.PRESENCE_CONTRAST_UNAVAILABLE
        } else {
            null
        },
        emptyPresentNote = if (episodes.isEmpty()) BehaviorAssociationCopy.NO_PRESENT_INTERVALS else null,
        notice = BehaviorAssociationCopy.EPISODE_NOTICE,
        episodes = episodes.mapIndexed { index, episode -> episode.toPresentation(index) },
    )
}

private fun BehaviorExposureEpisode.toPresentation(index: Int): ExposureEpisodePresentation {
    val spanMillis = latestEndAt - earliestStartAt
    val span = if (spanMillis >= 0) " Stored span ${formatHours(spanMillis)} h." else ""
    return ExposureEpisodePresentation(
        title = "Episode ${index + 1}",
        coverage = "Intervals $presentIntervalCount. Reported windows ${formatHours(reportedWindowDurationMillis)} h.$span",
        structure = "Roots ${rootKeys.joinToString { it.raw }.ifBlank { "none" }}. " +
            "Terminals ${terminalKeys.joinToString { it.raw }.ifBlank { "none" }}. " +
            "Branch points ${branchPoints.size}.",
        trajectory = occurrences.map { it.toTrajectoryLine() },
        coExposures = if (coExposures.isEmpty()) {
            "Co-exposures none"
        } else {
            "Co-exposures " + coExposures.joinToString("; ") { exposure ->
                "${exposure.displayNames.joinToString(", ").ifBlank { exposure.identity.key }} " +
                    "in ${exposure.intervalCount}"
            }
        },
        breaks = if (breaks.isEmpty()) {
            "Breaks none"
        } else {
            "Breaks " + breaks.joinToString("; ") { breakRecord ->
                "${breakRecord.side.name.lowercase()} ${breakRecord.key.raw}: " +
                    breakRecord.reasons.joinToString(", ") { it.label() }
            }
        },
        quantities = occurrences.mapNotNull { it.toQuantityLine() },
    )
}

private fun ExposureOccurrence.toTrajectoryLine(): String {
    val position = pathPosition?.toString() ?: "unavailable"
    val prior = priorPresentIntervalCount?.toString() ?: "unavailable"
    val inclusive = inclusivePresentIntervalCount?.toString() ?: "unavailable"
    val co = coExposures.joinToString(", ") { it.displayNames.joinToString(", ") }.ifBlank { "none" }
    return "${key.raw} position $position prior $prior inclusive $inclusive " +
        "${axisTrajectory()} emotion ${startEmotion?.name ?: "missing"}→${endEmotion?.name ?: "missing"} co $co"
}

private fun ExposureOccurrence.axisTrajectory(): String {
    fun one(label: String, start: Int?, end: Int?, delta: Int?): String? {
        if (start == null && end == null) return null
        val change = if (delta != null) " delta $delta" else ""
        return "$label ${start ?: "missing"}→${end ?: "missing"}$change"
    }
    return listOfNotNull(
        one("mood", startAxes?.mood, endAxes.mood, axisDeltas.mood),
        one("energy", startAxes?.energy, endAxes.energy, axisDeltas.energy),
        one("confidence", startAxes?.confidence, endAxes.confidence, axisDeltas.confidence),
        one("anxiety", startAxes?.anxiety, endAxes.anxiety, axisDeltas.anxiety),
    ).joinToString(" ").ifBlank { "axes missing" }
}

private fun ExposureOccurrence.toQuantityLine(): String? {
    val quantity = usage ?: return null
    val completeness = if (quantity.complete) "complete" else "quantity incomplete"
    val rate = quantity.inclusiveUnitlessPerHour?.let { " inclusive rate ${formatAssociationNumber(it)}/h" } ?: ""
    return "${key.raw} parsed ${quantity.parsedCount} missing ${quantity.missingCount} " +
        "unparsed ${quantity.unparsedCount} $completeness " +
        "current ${formatTotals(quantity.currentTotals)} " +
        "prior ${formatTotals(quantity.priorTotals)} " +
        "inclusive ${formatTotals(quantity.inclusiveTotals)}$rate"
}

private fun formatTotals(totals: List<UsageUnitTotal>?): String {
    if (totals == null) return "unavailable"
    if (totals.isEmpty()) return "none"
    return totals.joinToString(", ") { total ->
        "${total.unit ?: BehaviorAssociationCopy.UNITLESS} ${formatAssociationNumber(total.total)}"
    }
}

private fun formatHours(millis: Long): String = formatAssociationNumber(millis / 3_600_000.0)

private fun ExposureBreakReason.label(): String = when (this) {
    ExposureBreakReason.REVIEWED_ABSENCE -> "reviewed absence"
    ExposureBreakReason.UNKNOWN_EVIDENCE -> "unknown evidence"
    ExposureBreakReason.INCOMPLETE -> "incomplete"
    ExposureBreakReason.INVALID_CHRONOLOGY -> "invalid chronology"
    ExposureBreakReason.INCONSISTENT_CONTEXT -> "inconsistent context"
    ExposureBreakReason.REVERSED_WINDOW -> "reversed window"
    ExposureBreakReason.UNRESOLVED_LINK -> "unresolved link"
}

private fun String.axisLabel(): String = when (this) {
    DefaultCheckInAxes.MOOD -> "Mood"
    DefaultCheckInAxes.ENERGY -> "Energy"
    DefaultCheckInAxes.CONFIDENCE -> "Confidence"
    DefaultCheckInAxes.ANXIETY -> "Anxiety"
    else -> this
}
