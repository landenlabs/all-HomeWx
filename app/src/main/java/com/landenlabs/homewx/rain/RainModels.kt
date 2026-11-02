package com.landenlabs.homewx.rain

import com.landenlabs.homewx.weather.GeoLocation
import com.landenlabs.homewx.weather.startOfDay
import java.util.Calendar

/** One calendar day's precipitation in inches, or null where the source has no value yet
 *  (typically today and sometimes yesterday - gridded analyses lag by a day). */
data class DailyRain(val dayMillis: Long, val inches: Double?)

/** One week's total. [isCurrent] marks the week still in progress, whose total is only "so far".
 *  [lastYearInches] is the same week 52 weeks earlier (for the current week, only through the
 *  matching weekday so the comparison is like for like), or null if that wasn't fetched. */
data class WeeklyRain(val weekStartMillis: Long, val inches: Double, val isCurrent: Boolean, val lastYearInches: Double? = null)

/** One precipitation-history source (ACIS PRISM grid today, kept pluggable like
 *  [com.landenlabs.homewx.weather.WeatherProvider]). Blocking - call from a background dispatcher. */
interface RainProvider {
    /** Daily totals from [startMillis] through [endMillis] (inclusive calendar days), ascending. */
    fun getDailyRain(location: GeoLocation, startMillis: Long, endMillis: Long): List<DailyRain>
}

/** One full calendar month's total, with the same calendar month a year earlier ([lastYearInches],
 *  null if that wasn't fetched). */
data class MonthlyRain(val monthStartMillis: Long, val inches: Double, val lastYearInches: Double? = null)

/** What the Rain range shows: the weekly bars plus the monthly table. */
data class RainTotals(val weeks: List<WeeklyRain>, val months: List<MonthlyRain>)

class RainProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val RAIN_WEEKS = 12
const val RAIN_MONTHS = 3

/** First day (local midnight) of the oldest month in the monthly table. */
fun rainMonthsWindowStart(nowMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = startOfDay(nowMillis)
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, -RAIN_MONTHS)
    }.timeInMillis

/** [atMillis] shifted back one calendar year. */
fun yearAgo(atMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = atMillis
        add(Calendar.YEAR, -1)
    }.timeInMillis

/** The [RAIN_MONTHS] complete calendar months before the one containing [nowMillis], oldest
 *  first, each beside the same month a year earlier. Missing days count as 0, as for weeks. */
fun buildMonthlyTotals(days: List<DailyRain>, nowMillis: Long, lastYearDays: List<DailyRain>? = null): List<MonthlyRain> {
    fun monthTotal(source: List<DailyRain>, monthStart: Long): Double {
        val monthEnd = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, 1) }.timeInMillis
        return source.filter { it.dayMillis in monthStart until monthEnd }.sumOf { it.inches ?: 0.0 }
    }
    val calendar = Calendar.getInstance().apply { timeInMillis = rainMonthsWindowStart(nowMillis) }
    return (0 until RAIN_MONTHS).map {
        val monthStart = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        MonthlyRain(
            monthStart, monthTotal(days, monthStart),
            lastYearInches = lastYearDays?.let { monthTotal(it, yearAgo(monthStart)) }
        )
    }
}

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
fun buildWeeklyTotals(days: List<DailyRain>, nowMillis: Long, lastYearDays: List<DailyRain>? = null): List<WeeklyRain> {
    val currentWeekStart = startOfWeek(nowMillis)
    val calendar = Calendar.getInstance().apply { timeInMillis = rainWindowStart(nowMillis) }
    val totalsByWeek = days.groupBy { startOfWeek(it.dayMillis) }
        .mapValues { (_, weekDays) -> weekDays.sumOf { it.inches ?: 0.0 } }
    val lastYearTotals = lastYearDays?.groupBy { startOfWeek(it.dayMillis) }
        ?.mapValues { (_, weekDays) -> weekDays.sumOf { it.inches ?: 0.0 } }
    return (0 until RAIN_WEEKS).map {
        val weekStart = calendar.timeInMillis
        calendar.add(Calendar.WEEK_OF_YEAR, 1)
        WeeklyRain(
            weekStart, totalsByWeek[weekStart] ?: 0.0, isCurrent = weekStart == currentWeekStart,
            lastYearInches = lastYearTotals?.let { it[startOfWeek(weeksAgo52(weekStart))] ?: 0.0 }
        )
    }
}

/** [atMillis] shifted back 52 weeks (364 days), so a Monday stays a Monday - "same week last year". */
fun weeksAgo52(atMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = atMillis
        add(Calendar.WEEK_OF_YEAR, -52)
    }.timeInMillis
