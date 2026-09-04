package com.dlang.homewx.ui

import android.content.Context
import com.dlang.homewx.R

/**
 * TWC's icon codes run 0-47, but res/drawable-nodpi only ships art for a subset of them
 * (confirmed by checking the actual daily_snapshot DB: a "Mostly Cloudy" day was saved with
 * iconKey="wx_sun_27n" - conditionText comes from a separate field so it rendered fine while
 * the icon silently fell back to "Not Available", even though 27 is an extremely common
 * condition, not an edge case). Rather than ship new art, codes with no dedicated drawable are
 * mapped here to the closest code that does have one, by visual/semantic similarity. Genuinely
 * rare/severe codes with no reasonable substitute (tornado, hurricane, ...) are left unmapped
 * and still fall through to 44.
 */
private val ICON_CODE_SUBSTITUTES = mapOf(
    3 to 4,    // Strong Storms -> Thunderstorms
    5 to 16,   // Rain/Snow -> Snow
    6 to 10,   // Rain/Sleet -> Freezing Rain
    7 to 16,   // Wintry Mix -> Snow
    15 to 16,  // Blowing/Drifting Snow -> Snow
    17 to 4,   // Hail -> Thunderstorms
    18 to 10,  // Sleet -> Freezing Rain
    19 to 20,  // Blowing Dust/Sandstorm -> Foggy
    21 to 20,  // Haze -> Foggy
    22 to 20,  // Smoke -> Foggy
    25 to 16,  // Frigid/Ice Crystals -> Snow
    27 to 26,  // Mostly Cloudy (Night) -> Cloudy
    28 to 26,  // Mostly Cloudy (Day) -> Cloudy
    35 to 12,  // Mixed Rain/Hail -> Rain
    36 to 32,  // Hot -> Sunny
    37 to 4,   // Isolated Thunderstorms -> Thunderstorms
    38 to 4,   // Scattered Thunderstorms -> Thunderstorms
    39 to 11,  // Scattered Showers (Day) -> Showers
    41 to 14,  // Scattered Snow Showers (Day) -> Snow Showers
    43 to 42,  // Blizzard -> Heavy Snow
    45 to 11,  // Scattered Showers (Night) -> Showers
    46 to 14,  // Scattered Snow Showers (Night) -> Snow Showers
    47 to 4    // Scattered Thunderstorms (Night) -> Thunderstorms
)

/**
 * Resolves a [com.dlang.homewx.weather.CurrentConditions.iconKey] (e.g. "wx_sun_30d")
 * to the matching drawable in res/drawable-nodpi, substituting the closest available code (see
 * [ICON_CODE_SUBSTITUTES]) before finally falling back to the "Not Available" icon.
 */
fun Context.weatherIconRes(iconKey: String): Int {
    resolveIconDrawable(iconKey)?.let { return it }

    val code = iconKey.removePrefix("wx_sun_").dropLast(1).toIntOrNull()
    val suffix = iconKey.takeLast(1)
    val substituteCode = code?.let { ICON_CODE_SUBSTITUTES[it] }
    if (substituteCode != null) {
        resolveIconDrawable("wx_sun_%02d%s".format(substituteCode, suffix))?.let { return it }
    }
    return R.drawable.wx_sun_44d
}

private fun Context.resolveIconDrawable(iconKey: String): Int? {
    val resId = resources.getIdentifier(iconKey, "drawable", packageName)
    return resId.takeIf { it != 0 }
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
