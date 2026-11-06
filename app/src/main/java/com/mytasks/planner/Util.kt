package com.mytasks.planner

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatMinute(m: Int): String =
    LocalTime.of((m / 60).coerceIn(0, 23), (m % 60).coerceIn(0, 59))
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** 1:05:12 or 05:12 */
fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))

fun dayLabel(epochDay: Long, today: Long): String = when (epochDay - today) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    else -> formatDay(epochDay)
}

fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

fun repeatDescription(s: SeriesEntity): String {
    val base = when (s.repeatType) {
        RepeatType.DAILY -> "Every day"
        RepeatType.WEEKDAYS -> WEEKDAY_SHORT.filterIndexed { i, _ -> ((s.weekdayMask shr i) and 1) == 1 }
            .joinToString(", ")
        RepeatType.WEEKLY -> "Weekly on " + WEEKDAY_SHORT[LocalDate.ofEpochDay(s.startDate).dayOfWeek.value - 1]
        else -> "Every ${s.intervalDays} days"
    }
    return if (s.endDate >= 0) "$base · until ${formatDay(s.endDate)}" else base
}
