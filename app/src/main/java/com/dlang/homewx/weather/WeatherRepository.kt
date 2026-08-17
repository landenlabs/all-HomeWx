package com.dlang.homewx.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WeatherRepository(
    private val location: GeoLocation,
    private val activeSource: () -> WeatherSourceId,
    private val providerFactory: (WeatherSourceId) -> WeatherProvider = WeatherProviderFactory::create
) {
    suspend fun getCurrentConditions(): CurrentConditions = withContext(Dispatchers.IO) {
        providerFactory(activeSource()).getCurrentConditions(location)
    }

    suspend fun getForecast(): WeatherForecast = withContext(Dispatchers.IO) {
        providerFactory(activeSource()).getForecast(location)
    }

    suspend fun getHistoricalDailyAverage(startMillis: Long, endMillis: Long): HistoricalTempAverage? =
        withContext(Dispatchers.IO) {
            providerFactory(activeSource()).getHistoricalDailyAverage(location, startMillis, endMillis)
        }
}
