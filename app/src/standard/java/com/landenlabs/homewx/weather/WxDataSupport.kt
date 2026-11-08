package com.landenlabs.homewx.weather

import android.content.Context

/** "standard" flavor: built without the wxdata AAR, so WxData is never offered
 *  ([com.landenlabs.homewx.BuildConfig.HAS_WXDATA] is false). Same API as the wxdata flavor's copy. */
object WxDataSupport {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) = Unit

    fun createProvider(): WeatherProvider? = null
}
