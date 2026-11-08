package com.landenlabs.homewx.weather

import com.landenlabs.homewx.weather.openmeteo.OpenMeteoWeatherProvider

object WeatherProviderFactory {
    fun create(source: WeatherSourceId): WeatherProvider = when (source) {
        WeatherSourceId.OPEN_METEO -> OpenMeteoWeatherProvider()
        // Never selected without the wxdata flavor (see WeatherSourceConfig), but stay safe.
        WeatherSourceId.WXDATA -> WxDataSupport.createProvider() ?: OpenMeteoWeatherProvider()
    }
}
