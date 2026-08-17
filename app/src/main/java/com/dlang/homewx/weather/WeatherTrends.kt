package com.dlang.homewx.weather

import kotlin.math.abs

private const val HOUR_MILLIS = 3_600_000L

/**
 * Change in some hourly value between now and [hoursAhead] hours from now, e.g. the
 * temperature swing over the next hour or the pressure swing over the next 6 hours.
 * Picks the hourly entry closest to the target time rather than requiring an exact match.
 */
fun WeatherForecast.hourlyDelta(
    nowMillis: Long,
    hoursAhead: Int,
    currentValue: Double?,
    valueOf: (HourlyForecastEntry) -> Double?
): Double? {
    if (currentValue == null || hourly.isEmpty()) return null
    val targetMillis = nowMillis + hoursAhead * HOUR_MILLIS
    val entry = hourly.minByOrNull { abs(it.timeMillis - targetMillis) } ?: return null
    val futureValue = valueOf(entry) ?: return null
    return futureValue - currentValue
}
