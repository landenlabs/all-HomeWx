package com.landenlabs.homewx.rain

import com.landenlabs.homewx.rain.acis.AcisGridRainProvider
import com.landenlabs.homewx.weather.GeoLocation
import com.landenlabs.homewx.weather.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Weekly rain totals over [RAIN_WEEKS] weeks plus monthly totals, cached in memory - past days barely change, so a
 *  fetch is only repeated after [CACHE_TTL_MILLIS], when the calendar day rolls over, or when the
 *  location changes. */
class RainRepository(private val provider: RainProvider = AcisGridRainProvider()) {

    private class Cached(val location: GeoLocation, val fetchedAtMillis: Long, val totals: RainTotals)
    private var cached: Cached? = null

    /** The last fetched totals for [location], however stale, or null if there are none yet. */
    fun cachedTotals(location: GeoLocation): RainTotals? = cached?.takeIf { it.location == location }?.totals

    fun isFresh(location: GeoLocation, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val entry = cached?.takeIf { it.location == location } ?: return false
        return nowMillis - entry.fetchedAtMillis < CACHE_TTL_MILLIS && startOfDay(entry.fetchedAtMillis) == startOfDay(nowMillis)
    }

    suspend fun refresh(location: GeoLocation): RainTotals = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // One fetch covers both the weekly bars and the monthly table.
        val windowStart = minOf(rainWindowStart(now), rainMonthsWindowStart(now))
        val days = provider.getDailyRain(location, windowStart, startOfDay(now))
        // Last year is a bonus overlay - if its fetch fails the bars still draw without the line.
        val lastYearDays = try {
            provider.getDailyRain(location, minOf(weeksAgo52(rainWindowStart(now)), yearAgo(rainMonthsWindowStart(now))), weeksAgo52(startOfDay(now)))
        } catch (e: RainProviderException) {
            null
        }
        RainTotals(buildWeeklyTotals(days, now, lastYearDays), buildMonthlyTotals(days, now, lastYearDays))
            .also { cached = Cached(location, now, it) }
    }

    private companion object {
        val CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(3)
    }
}
