package com.dlang.homewx.weather

import com.dlang.homewx.weather.openmeteo.OpenMeteoWeatherProvider

object WeatherProviderFactory {
    fun create(source: WeatherSourceId): WeatherProvider = when (source) {
        WeatherSourceId.OPEN_METEO -> OpenMeteoWeatherProvider()
    }
}
