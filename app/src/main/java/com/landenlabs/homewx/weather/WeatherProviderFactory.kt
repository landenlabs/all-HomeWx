package com.landenlabs.homewx.weather

import com.landenlabs.homewx.weather.openmeteo.OpenMeteoWeatherProvider
import com.landenlabs.homewx.weather.wxdata.WxDataWeatherProvider

object WeatherProviderFactory {
    fun create(source: WeatherSourceId): WeatherProvider = when (source) {
        WeatherSourceId.OPEN_METEO -> OpenMeteoWeatherProvider()
        WeatherSourceId.WXDATA -> WxDataWeatherProvider()
    }
}
