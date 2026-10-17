package com.dlang.homewx.rain

import com.dlang.homewx.weather.GeoLocation
import com.dlang.homewx.weather.startOfDay
import java.util.Calendar

/** One calendar day's precipitation in inches, or null where the source has no value yet
 *  (typically today and sometimes yesterday - gridded analyses lag by a day). */
data class DailyRain(val dayMillis: Long, val inches: Double?)

/** One week's total. [isCurrent] marks the week still in progress, whose total is only "so far". */
data class WeeklyRain(val weekStartMillis: Long, val inches: Double, val isCurrent: Boolean)

/** One precipitation-history source (ACIS PRISM grid today, kept pluggable like
 *  [com.dlang.homewx.weather.WeatherProvider]). Blocking - call from a background dispatcher. */
interface RainProvider {
    /** Daily totals from [startMillis] through [endMillis] (inclusive calendar days), ascending. */
    fun getDailyRain(location: GeoLocation, startMillis: Long, endMillis: Long): List<DailyRain>
}

class RainProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val RAIN_WEEKS = 12

/** Start (local midnight) of the Monday on or before [atMillis]. */
fun startOfWeek(atMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = startOfDay(atMillis)
        val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
    }.timeInMillis

/** Start of the oldest week shown - [RAIN_WEEKS] - 1 weeks before the current one. */
fun rainWindowStart(nowMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = startOfWeek(nowMillis)
        add(Calendar.WEEK_OF_YEAR, -(RAIN_WEEKS - 1))
    }.timeInMillis

/** Sums [days] into [RAIN_WEEKS] Monday-start weeks ending with the week containing [nowMillis].
 *  Days with no value count as 0 - a missing day is almost always the latest one, still pending. */
fun buildWeeklyTotals(days: List<DailyRain>, nowMillis: Long): List<WeeklyRain> {
    val currentWeekStart = startOfWeek(nowMillis)
    val calendar = Calendar.getInstance().apply { timeInMillis = rainWindowStart(nowMillis) }
    val totalsByWeek = days.groupBy { startOfWeek(it.dayMillis) }
        .mapValues { (_, weekDays) -> weekDays.sumOf { it.inches ?: 0.0 } }
    return (0 until RAIN_WEEKS).map {
        val weekStart = calendar.timeInMillis
        calendar.add(Calendar.WEEK_OF_YEAR, 1)
        WeeklyRain(weekStart, totalsByWeek[weekStart] ?: 0.0, isCurrent = weekStart == currentWeekStart)
    }
}
