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
