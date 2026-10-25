package com.orbit.wellness.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DateUtils {
    private val localDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val shortDateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a")
    private val datedDateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d · h:mm a")

    fun formatLocalDate(date: LocalDate): String = date.format(localDateFormatter)

    fun parseLocalDate(value: String): LocalDate = LocalDate.parse(value, localDateFormatter)

    fun dayLabel(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("EEE"))
    }

    fun shortDateTimeLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(zone)
        val date = zoned.toLocalDate()
        val today = LocalDate.now(zone)
        val formatter = if (date == today) shortDateTimeFormatter else datedDateTimeFormatter
        return formatter.format(zoned)
    }

    fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(zone)
        return DateTimeFormatter.ofPattern("h:mm a").format(zoned)
    }

    /**
     * Session-aware label: single time when start≈end or still in progress marker,
     * otherwise start → end.
     */
    fun sessionTimeLabel(startMillis: Long, endMillis: Long?): String {
        if (endMillis == null) {
            return "${shortDateTimeLabel(startMillis)}…"
        }
        if (kotlin.math.abs(endMillis - startMillis) < 60_000L) {
            return shortDateTimeLabel(startMillis)
        }
        return "${shortDateTimeLabel(startMillis)} → ${shortDateTimeLabel(endMillis)}"
    }

    fun sleepDurationLabel(bedtimeMillis: Long, wakeTimeMillis: Long): String {
        val diffMillis = (wakeTimeMillis - bedtimeMillis).coerceAtLeast(0)
        val hours = diffMillis / (1000 * 60 * 60)
        val mins = (diffMillis / (1000 * 60)) % 60
        return if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    }

    fun startOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun endOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        return LocalDateTime.of(date, LocalTime.MAX)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    fun atTimeMillis(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    fun hourOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        return Instant.ofEpochMilli(millis).atZone(zone).hour
    }

    fun minuteOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        return Instant.ofEpochMilli(millis).atZone(zone).minute
    }

    fun localDateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }

    fun utcMidnightMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        return localDateOf(millis, zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun withDateUtcMidnight(
        millis: Long,
        utcDateMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val date = Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun withHourMinute(
        millis: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        return atTimeMillis(localDateOf(millis, zone), hour, minute, zone)
    }

    /**
     * For averaging "today", weight the last sample only until now (not midnight).
     * For past days, weight until end of that local day.
     */
    fun averageWindowEndMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = LocalDate.now(zone)
        return if (date == today) {
            System.currentTimeMillis()
        } else {
            endOfDayMillis(date, zone)
        }
    }
}
