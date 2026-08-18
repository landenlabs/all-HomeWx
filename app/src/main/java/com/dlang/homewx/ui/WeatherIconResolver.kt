package com.dlang.homewx.ui

import android.content.Context
import com.dlang.homewx.R

/**
 * Resolves a [com.dlang.homewx.weather.CurrentConditions.iconKey] (e.g. "wx_sun_30d")
 * to the matching drawable in res/drawable-nodpi, falling back to the "Not Available" icon.
 */
fun Context.weatherIconRes(iconKey: String): Int {
    val resId = resources.getIdentifier(iconKey, "drawable", packageName)
    return if (resId != 0) resId else R.drawable.wx_sun_44d
}

private val PRECIPITATION_ICON_CODES = setOf(4, 8, 9, 10, 11, 12, 13, 14, 16, 40, 42)
private val CLEAR_ICON_CODES = setOf(31, 32, 33, 34)
private const val WINDY_THRESHOLD_MPH = 20.0

/**
 * Picks a wx-images background photo for the weather panel from the current condition's
 * icon code plus wind speed - there's no WMO code for "windy", so wind speed is its own
 * signal. Precipitation (rain or snow) always wins; wind wins over clear/cloudy otherwise.
 */
fun weatherBackgroundRes(iconKey: String, windSpeedMph: Double?): Int {
    val iconCode = iconKey.removePrefix("wx_sun_").dropLast(1).toIntOrNull()
    return when {
        iconCode in PRECIPITATION_ICON_CODES -> R.drawable.wx_bg_rainy
        windSpeedMph != null && windSpeedMph >= WINDY_THRESHOLD_MPH -> R.drawable.wx_bg_windy
        iconCode in CLEAR_ICON_CODES -> R.drawable.wx_bg_clear
        else -> R.drawable.wx_bg_cloudy
    }
}
