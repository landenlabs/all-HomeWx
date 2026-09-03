package com.dlang.homewx.weather

import com.dlang.homewx.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WeatherRepository(
    private val location: GeoLocation,
    private val activeSource: () -> WeatherSourceId,
    private val forecastDays: () -> Int = { AppSettings.DEFAULT_FORECAST_DAYS },
    private val providerFactory: (WeatherSourceId) -> WeatherProvider = WeatherProviderFactory::create
) {
    // Each WeatherProvider owns its own OkHttpClient (and connection pool) - cache one
    // instance per source instead of building a fresh client on every single network call,
    // which otherwise leaks sockets/file descriptors over this repository's long lifetime.
    private val providers = mutableMapOf<WeatherSourceId, WeatherProvider>()
    private fun currentProvider(): WeatherProvider =
        providers.getOrPut(activeSource()) { providerFactory(activeSource()) }

    suspend fun getCurrentConditions(): CurrentConditions = withContext(Dispatchers.IO) {
        currentProvider().getCurrentConditions(location)
    }

    suspend fun getForecast(): WeatherForecast = withContext(Dispatchers.IO) {
        currentProvider().getForecast(location, forecastDays())
    }

    suspend fun getHistoricalDailyAverage(startMillis: Long, endMillis: Long): HistoricalTempAverage? =
        withContext(Dispatchers.IO) {
            currentProvider().getHistoricalDailyAverage(location, startMillis, endMillis)
        }
}
