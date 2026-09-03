package com.dlang.homewx.weather

import com.dlang.homewx.weather.openmeteo.OpenMeteoWeatherProvider
import com.dlang.homewx.weather.wxdata.WxDataWeatherProvider

object WeatherProviderFactory {
    fun create(source: WeatherSourceId): WeatherProvider = when (source) {
        WeatherSourceId.OPEN_METEO -> OpenMeteoWeatherProvider()
        WeatherSourceId.WXDATA -> WxDataWeatherProvider()
    }
}
