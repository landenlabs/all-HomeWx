package com.dlang.homewx.weather.wxdata

import android.content.Context
import com.dlang.homewx.BuildConfig
import com.dlang.homewx.weather.CurrentConditions
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.GeoLocation
import com.dlang.homewx.weather.HistoricalTempAverage
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.WeatherProvider
import com.dlang.homewx.weather.WeatherProviderException
import com.wsi.wxdata.WxAlmanacDailyFetcher
import com.wsi.wxdata.WxCurrentConditions
import com.wsi.wxdata.WxCurrentFetcher
import com.wsi.wxdata.WxData
import com.wsi.wxdata.WxDataInitializationException
import com.wsi.wxdata.WxHourlyFetcher
import com.wsi.wxdata.WxHourlyForecast
import com.wsi.wxdata.WxLocation
import com.wsi.wxdata.WxTime
import com.wsi.wxdata.WxUnit
import com.wsi.wxdata.WxWeatherSample
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weather source backed by TWC's wxdata library (see wxdata-integration-notes.md for the design
 * notes this was built from). Blocking network calls - callers must invoke from a background
 * dispatcher, matching [com.dlang.homewx.weather.openmeteo.OpenMeteoWeatherProvider].
 *
 * Historical daily average is answered via [WxAlmanacDailyFetcher], not `WxDailyHistoricalFetcher`:
 * the latter's constructor is public now (AAR 2.26.0903+), but its network call
 * (`getDaily30DayHistoricalObservable`) has no date-range parameter at all - it always returns
 * TWC's trailing 30-days-from-now window server-side, so it can never answer HomeWx's actual
 * "vs. this week last year" request ([com.dlang.homewx.weather.historicalComparisonWindow],
 * ~365 days back - confirmed by inspecting the literal request URL, not just the source).
 * [WxAlmanacDailyFetcher] *is* real, date-controllable data - but it's the 10-30 year NCDC
 * climate *normal* high/low for a calendar day (`temperatureAverageMax`/`temperatureAverageMin`),
 * not last year's actual observations - there's no year in either the request or the response.
 * A year-ago date and today's date are (almost) the same calendar day, so this is a reasonable
 * stand-in in practice, but it is a different statistic - see [com.dlang.homewx.MainActivity]'s
 * source-dependent "Normal max/min" vs. "LstYr max/min" label.
 */
class WxDataWeatherProvider : WeatherProvider {

    override fun getCurrentConditions(location: GeoLocation): CurrentConditions {
        val fetcher = WxCurrentFetcher().apply {
            setLocation(location.toWxLocation())
            setUnit(WxUnit.Imperial)
        }
        return await(fetcher.getFetchFuture()).toCurrentConditions()
    }

    /**
     * Daily entries are derived from this same hourly response rather than a separate
     * `WxDailyFetcher` call - the daily forecast API's DayPart data has no pressure field at
     * all, and only one humidity/wind value per day-or-night half, whereas the hourly data has
     * all of these per hour and lets a real daily min/max/avg be computed (see
     * [WxHourlyForecast.toDailyEntries]). Fixed at [DAILY_DERIVATION_DAYS], not [forecastDays] -
     * that's the range this derivation is built for, independent of the app's (Open-Meteo-
     * oriented) forecast-length setting.
     */
    override fun getForecast(location: GeoLocation, forecastDays: Int): WeatherForecast {
        val wxLocation = location.toWxLocation()
        val hourlyFetcher = WxHourlyFetcher().apply {
            setLocation(wxLocation)
            setUnit(WxUnit.Imperial)
            setTime(WxTime.comingHours(DAILY_DERIVATION_DAYS * 24))
        }
        val hourly = await(hourlyFetcher.getFetchFuture())
        val normals = fetchAlmanacNormals(wxLocation, DAILY_DERIVATION_DAYS)
        return WeatherForecast(hourly = hourly.toHourlyEntries(), daily = hourly.toDailyEntries(normals))
    }

    /** Climate-normal high/low for each of the next [days] calendar days, keyed by month*100+day
     *  (year-independent, matching [com.wsi.wxdata.WxAlmanacDaily.almanacRecordDate]) so a daily
     *  entry can look its normal up by calendar day instead of by array index. */
    private fun fetchAlmanacNormals(wxLocation: WxLocation, days: Int): Map<Int, NormalRange> {
        val fetcher = WxAlmanacDailyFetcher().apply {
            setLocation(wxLocation)
            setUnit(WxUnit.Imperial)
            setTime(WxTime.daysFromStartDay(WxTime.today(), days))
        }
        val almanac = await(fetcher.getFetchFuture())
        val normals = mutableMapOf<Int, NormalRange>()
        almanac.almanacRecordDate.orEmpty().forEachIndexed { i, monthDay ->
            val high = almanac.temperatureAverageMax?.getOrNull(i)?.toDouble()
            val low = almanac.temperatureAverageMin?.getOrNull(i)?.toDouble()
            if (high != null && low != null) normals[monthDay] = NormalRange(high, low)
        }
        return normals
    }

    override fun getHistoricalDailyAverage(location: GeoLocation, startMillis: Long, endMillis: Long): HistoricalTempAverage? {
        val fetcher = WxAlmanacDailyFetcher().apply {
            setLocation(location.toWxLocation())
            setUnit(WxUnit.Imperial)
            setTime(WxTime.daysFromStartDay(WxTime.timeWithDate(Date(startMillis)), daysBetween(startMillis, endMillis)))
        }
        val almanac = await(fetcher.getFetchFuture())

        // The almanac response has no year of its own (almanacRecordDate is MM/DD only), so the
        // requested window is matched by calendar day rather than by absolute millis.
        val targetMonthDays = monthDaysInRange(startMillis, endMillis)
        val highs = mutableListOf<Double>()
        val lows = mutableListOf<Double>()
        almanac.almanacRecordDate.orEmpty().forEachIndexed { i, monthDay ->
            if (monthDay !in targetMonthDays) return@forEachIndexed
            almanac.temperatureAverageMax?.getOrNull(i)?.let { highs.add(it.toDouble()) }
            almanac.temperatureAverageMin?.getOrNull(i)?.let { lows.add(it.toDouble()) }
        }
        if (highs.isEmpty() || lows.isEmpty()) return null

        return HistoricalTempAverage(
            avgHighF = highs.average(),
            avgLowF = lows.average(),
            sampleDays = minOf(highs.size, lows.size)
        )
    }

    private fun <T> await(future: CompletableFuture<T>): T =
        try {
            future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw WeatherProviderException("wxdata fetch failed", e)
        }

    companion object {
        private const val FETCH_TIMEOUT_SECONDS = 20L
        private const val DAILY_DERIVATION_DAYS = 7

        /**
         * Must be called once at app startup, before any [WxDataWeatherProvider] call - see
         * [com.dlang.homewx.HomeWxApp]. Safe to call unconditionally regardless of which source
         * is currently active: [WxData.initialize] is a one-time no-op on repeat calls, and this
         * particular AAR build has `USE_PROVISIONING = false`, so it resolves the override
         * config locally with no network round trip - unlike the sample app's WxDataHelper,
         * which goes through the full provisioning service.
         */
        fun initialize(context: Context) {
            WxData.getInstance().initialize(
                context,
                BuildConfig.SUN_API_KEY,
                object : WxData.InitializationListener {
                    override fun onWxDataInitializationSucceeded() = Unit
                    override fun onWxDataInitializationFailed(error: WxDataInitializationException?) = Unit
                },
                mapOf(WxData.OVER_SUN to BuildConfig.SUN_API_KEY)
            )
        }
    }
}

private fun GeoLocation.toWxLocation(): WxLocation =
    WxLocation(label, "", "", "", latitude, longitude, TimeZone.getDefault().id)

private fun Number?.finiteOrNull(): Double? = this?.toDouble()?.takeIf { !it.isNaN() }

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

private fun daysBetween(startMillis: Long, endMillis: Long): Int =
    ((endMillis - startMillis) / DAY_MILLIS).toInt() + 1

/** [com.wsi.wxdata.WxAlmanacDaily.almanacRecordDate]-style MMDD values (month*100+day,
 *  year-independent) for every calendar day in [startMillis, endMillis]. */
private fun monthDaysInRange(startMillis: Long, endMillis: Long): Set<Int> {
    val calendar = Calendar.getInstance()
    val monthDays = mutableSetOf<Int>()
    var millis = startMillis
    while (millis <= endMillis) {
        calendar.timeInMillis = millis
        monthDays.add((calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DAY_OF_MONTH))
        millis += DAY_MILLIS
    }
    return monthDays
}

/**
 * wxdata's `iconCode` is already the same TWC "wx-icons.csv IconCode" numbering that
 * [com.dlang.homewx.ui.weatherIconRes] resolves drawable-key strings against (that numbering is
 * where HomeWx's own `wx_sun_NNd`/`wx_sun_NNn` drawable names came from) - no separate mapping
 * table needed, just the day/night suffix. Codes with no matching drawable already fall back to
 * "Not Available" (44) in [com.dlang.homewx.ui.weatherIconRes].
 */
private fun iconKeyFor(iconCode: Int, dayOrNight: String?): String =
    "wx_sun_%02d%s".format(iconCode, if (dayOrNight == "N") "n" else "d")

private fun WxCurrentConditions.toCurrentConditions(): CurrentConditions = CurrentConditions(
    temperatureF = temperature?.toDouble(),
    feelsLikeF = temperatureFeelsLike?.toDouble(),
    humidityPct = relativeHumidity?.toDouble(),
    windSpeedMph = windSpeed?.toDouble(),
    windDirectionDeg = windDirection?.toDouble(),
    precipitationIn = precip1Hour?.toDouble(),
    pressureInHg = pressureAltimeter?.toDouble(),
    conditionText = wxPhraseLong ?: "",
    iconKey = iconKeyFor(iconCode ?: 44, dayOrNight),
    observedAtMillis = (validTimeUtc ?: (System.currentTimeMillis() / 1000L)) * 1000L
)

private fun WxHourlyForecast.toHourlyEntries(): List<HourlyForecastEntry> =
    validTimeUtc.orEmpty().indices.mapNotNull { i ->
        val sample = getSample(i) ?: return@mapNotNull null
        HourlyForecastEntry(
            timeMillis = validTimeUtc[i] * 1000L,
            temperatureF = sample.temperature.finiteOrNull(),
            windSpeedMph = sample.windSpeed.finiteOrNull(),
            precipitationChancePct = sample.precipPercent.takeIf { !it.isNaN() }?.roundToInt(),
            pressureInHg = sample.pressureAltimeter.finiteOrNull(),
            conditionText = sample.weatherNarrative ?: "",
            iconKey = iconKeyFor(sample.iconCode, dayOrNight?.getOrNull(i))
        )
    }

/** Climate-normal high/low for one calendar day, from [com.wsi.wxdata.WxAlmanacDaily]. */
private data class NormalRange(val highF: Double, val lowF: Double)

/** Groups this hourly forecast's samples by local calendar day and aggregates each day into one
 *  [DailyForecastEntry] - see the comment on [com.dlang.homewx.weather.wxdata.WxDataWeatherProvider.getForecast]
 *  for why this replaced a separate daily-forecast fetch. */
private fun WxHourlyForecast.toDailyEntries(normals: Map<Int, NormalRange>): List<DailyForecastEntry> {
    val calendar = Calendar.getInstance()
    val byDay = LinkedHashMap<Int, MutableList<Pair<Long, WxWeatherSample>>>()
    validTimeUtc.orEmpty().indices.forEach { i ->
        val sample = getSample(i) ?: return@forEach
        val timeMillis = validTimeUtc[i] * 1000L
        calendar.timeInMillis = timeMillis
        val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        byDay.getOrPut(dayKey) { mutableListOf() }.add(timeMillis to sample)
    }
    return byDay.values.map { it.toDailyEntry(calendar, normals) }
}

/** Aggregates one day's hourly samples into a [DailyForecastEntry]: min/max temperature, min/max
 *  wind speed (max with the time it first occurs) and its day-average, max humidity, max
 *  precipitation chance (with the time it first occurs), average pressure, and the icon/
 *  condition from the sample nearest local noon. [maxByOrNull] keeps the first element it sees
 *  among ties, which is what makes "first occurs" correct here since the list is chronological. */
private fun List<Pair<Long, WxWeatherSample>>.toDailyEntry(
    calendar: Calendar,
    normals: Map<Int, NormalRange>
): DailyForecastEntry {
    val temps = mapNotNull { (_, s) -> s.temperature.finiteOrNull() }
    val winds = mapNotNull { (t, s) -> s.windSpeed.finiteOrNull()?.let { t to it } }
    val humidities = mapNotNull { (_, s) -> s.relativeHumidity.finiteOrNull() }
    val precipChances = mapNotNull { (t, s) -> s.precipPercent.takeIf { !it.isNaN() }?.let { t to it.toDouble() } }
    // pressureAltimeter is flagged "Restricted" in the wxdata source - this API key's plan
    // doesn't return it for the Hourly Forecast endpoint (it comes back NaN every hour), so
    // fall back to pressureMeanSeaLevel, which isn't restricted and is actually populated.
    val pressures = mapNotNull { (_, s) -> s.pressureAltimeter.finiteOrNull() ?: s.pressureMeanSeaLevel.finiteOrNull() }

    val maxWind = winds.maxByOrNull { it.second }
    val minWind = winds.minByOrNull { it.second }
    val maxPrecip = precipChances.maxByOrNull { it.second }

    calendar.timeInMillis = first().first
    calendar.set(Calendar.HOUR_OF_DAY, 12)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val noonMillis = calendar.timeInMillis
    val noonSample = minByOrNull { (t, _) -> abs(t - noonMillis) }?.second ?: first().second

    calendar.timeInMillis = noonMillis
    val monthDay = (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DAY_OF_MONTH)
    val normal = normals[monthDay]

    return DailyForecastEntry(
        dateMillis = noonMillis,
        highF = temps.maxOrNull(),
        lowF = temps.minOrNull(),
        windMaxMph = maxWind?.second,
        precipitationChancePct = maxPrecip?.second?.roundToInt(),
        conditionText = noonSample.weatherNarrative ?: "",
        // A whole-day forecast has no day/night distinction of its own - use the day icon
        // variant, matching OpenMeteoWeatherProvider's daily mapping.
        iconKey = iconKeyFor(noonSample.iconCode, "D"),
        windMaxAtMillis = maxWind?.first,
        windMinMph = minWind?.second,
        windAvgMph = winds.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
        humidityMaxPct = humidities.maxOrNull(),
        precipitationChanceAtMillis = maxPrecip?.first,
        pressureAvgInHg = pressures.takeIf { it.isNotEmpty() }?.average(),
        normalHighF = normal?.highF,
        normalLowF = normal?.lowF
    )
}
