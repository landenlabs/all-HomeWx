package com.landenlabs.homewx.weather

import android.content.Context
import com.landenlabs.homewx.weather.wxdata.WxDataWeatherProvider

/** "wxdata" flavor: forwards to the AAR-backed [WxDataWeatherProvider]. The "standard" flavor has a
 *  no-op copy of this object with the same API, so src/main never references the AAR directly. */
object WxDataSupport {
    /** (Re)applies the WxData config - at app start and whenever the API key changes. */
    fun initialize(context: Context) = WxDataWeatherProvider.initialize(context)

    fun createProvider(): WeatherProvider? = WxDataWeatherProvider()
}
