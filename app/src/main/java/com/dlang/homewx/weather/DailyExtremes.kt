package com.dlang.homewx.weather

import java.util.Calendar

data class DailyExtreme(val value: Double, val atMillis: Long)

data class DailyExtremes(
    val tempHighF: DailyExtreme? = null,
    val tempLowF: DailyExtreme? = null,
    val windHighMph: DailyExtreme? = null,
    val windLowMph: DailyExtreme? = null
)

fun startOfDay(atMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = atMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

fun isSameDay(aMillis: Long, bMillis: Long): Boolean = startOfDay(aMillis) == startOfDay(bMillis)

/** [start, end] (inclusive, start-of-day millis) for the [daysAround]-day window centered on this date one year ago. */
fun historicalComparisonWindow(referenceMillis: Long, daysAround: Int = 3): Pair<Long, Long> {
    val calendar = Calendar.getInstance().apply { timeInMillis = referenceMillis }
    calendar.add(Calendar.YEAR, -1)
    val anchorMillis = calendar.timeInMillis

    calendar.timeInMillis = anchorMillis
    calendar.add(Calendar.DAY_OF_MONTH, -daysAround)
    val start = startOfDay(calendar.timeInMillis)

    calendar.timeInMillis = anchorMillis
    calendar.add(Calendar.DAY_OF_MONTH, daysAround)
    val end = startOfDay(calendar.timeInMillis)

    return start to end
}

/**
 * Folds today's hourly forecast points on top of an actual/observed-so-far extreme,
 * only replacing it on a strict improvement. Scanning [hourlyPoints] in chronological
 * order this way means the final timestamp is the *first* time the winning value is
 * reached - matching "first time it will occur" for a forecast high/low.
 */
fun bestExtreme(actual: DailyExtreme?, hourlyPoints: List<Pair<Long, Double>>, pickHigh: Boolean): DailyExtreme? {
    var best = actual
    for ((atMillis, value) in hourlyPoints) {
        val better = best == null || if (pickHigh) value > best.value else value < best.value
        if (better) best = DailyExtreme(value, atMillis)
    }
    return best
}
