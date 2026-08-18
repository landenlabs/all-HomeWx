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
    val conditionText: String
)

data class DailyForecastEntry(
    val dateMillis: Long,
    val highF: Double?,
    val lowF: Double?,
    val precipitationChancePct: Int?,
    val conditionText: String,
    /** Drawable resource name (no extension) in res/drawable-nodpi, e.g. "wx_sun_30d". */
    val iconKey: String
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
