package com.dlang.homewx.weather

data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String
)

data class CurrentConditions(
    val temperatureF: Double?,
    val feelsLikeF: Double?,
    val humidityPct: Double?,
    val windSpeedMph: Double?,
    val windDirectionDeg: Double?,
    val precipitationIn: Double?,
    val pressureInHg: Double?,
    val conditionText: String,
    /** Drawable resource name (no extension) in res/drawable-nodpi, e.g. "wx_sun_30d". */
    val iconKey: String,
    val observedAtMillis: Long
)

data class HourlyForecastEntry(
    val timeMillis: Long,
    val temperatureF: Double?,
    val windSpeedMph: Double?,
    val precipitationChancePct: Int?,
    val pressureInHg: Double?,
    val conditionText: String,
    /** Drawable resource name (no extension) in res/drawable-nodpi, e.g. "wx_sun_30d". */
    val iconKey: String
)

data class DailyForecastEntry(
    val dateMillis: Long,
    val highF: Double?,
    val lowF: Double?,
    val windMaxMph: Double?,
    val precipitationChancePct: Int?,
    val conditionText: String,
    /** Drawable resource name (no extension) in res/drawable-nodpi, e.g. "wx_sun_30d". */
    val iconKey: String,
    /** The rest of these are only populated by [com.dlang.homewx.weather.wxdata.WxDataWeatherProvider],
     *  which derives the whole daily entry from a day's worth of hourly samples - Open-Meteo's
     *  daily API has no humidity/pressure fields and doesn't report when an extreme occurred,
     *  so these stay null there. */
    val windMaxAtMillis: Long? = null,
    val windMinMph: Double? = null,
    val windAvgMph: Double? = null,
    val humidityMaxPct: Double? = null,
    val precipitationChanceAtMillis: Long? = null,
    val pressureAvgInHg: Double? = null,
    /** Climate-normal high/low for this calendar day (10-30yr NCDC average), from
     *  [com.wsi.wxdata.WxAlmanacDailyFetcher] - see [com.dlang.homewx.MainActivity]'s
     *  source-dependent "Normal max/min" label. */
    val normalHighF: Double? = null,
    val normalLowF: Double? = null
)

data class WeatherForecast(
    val hourly: List<HourlyForecastEntry>,
    val daily: List<DailyForecastEntry>
)

/** Average of daily highs/lows across a multi-day historical window (e.g. same week last year). */
data class HistoricalTempAverage(
    val avgHighF: Double,
    val avgLowF: Double,
    val sampleDays: Int
)

class WeatherProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
