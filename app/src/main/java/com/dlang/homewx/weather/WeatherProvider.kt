package com.dlang.homewx.weather

/**
 * One weather data source (Open-Meteo, a future private/paid source, etc).
 * Implementations are plain blocking clients - callers must invoke from a
 * background dispatcher, matching [com.dlang.homewx.data.GoveeApiClient].
 */
interface WeatherProvider {
    fun getCurrentConditions(location: GeoLocation): CurrentConditions
    fun getForecast(location: GeoLocation): WeatherForecast

    /** Null if this source has no historical archive (e.g. a future private source might not). */
    fun getHistoricalDailyAverage(location: GeoLocation, startMillis: Long, endMillis: Long): HistoricalTempAverage?
}
