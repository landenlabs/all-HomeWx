package com.dlang.homewx.rain

import com.dlang.homewx.rain.acis.AcisGridRainProvider
import com.dlang.homewx.weather.GeoLocation
import com.dlang.homewx.weather.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Weekly rain totals over [RAIN_WEEKS] weeks, cached in memory - past days barely change, so a
 *  fetch is only repeated after [CACHE_TTL_MILLIS], when the calendar day rolls over, or when the
 *  location changes. */
class RainRepository(private val provider: RainProvider = AcisGridRainProvider()) {

    private class Cached(val location: GeoLocation, val fetchedAtMillis: Long, val weeks: List<WeeklyRain>)
    private var cached: Cached? = null

    /** The last fetched totals for [location], however stale, or null if there are none yet. */
    fun cachedWeeks(location: GeoLocation): List<WeeklyRain>? = cached?.takeIf { it.location == location }?.weeks

    fun isFresh(location: GeoLocation, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val entry = cached?.takeIf { it.location == location } ?: return false
        return nowMillis - entry.fetchedAtMillis < CACHE_TTL_MILLIS && startOfDay(entry.fetchedAtMillis) == startOfDay(nowMillis)
    }

    suspend fun refresh(location: GeoLocation): List<WeeklyRain> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val days = provider.getDailyRain(location, rainWindowStart(now), startOfDay(now))
        // Last year is a bonus overlay - if its fetch fails the bars still draw without the line.
        val lastYearDays = try {
            provider.getDailyRain(location, weeksAgo52(rainWindowStart(now)), weeksAgo52(startOfDay(now)))
        } catch (e: RainProviderException) {
            null
        }
        buildWeeklyTotals(days, now, lastYearDays).also { cached = Cached(location, now, it) }
    }

    private companion object {
        val CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(3)
    }
}
