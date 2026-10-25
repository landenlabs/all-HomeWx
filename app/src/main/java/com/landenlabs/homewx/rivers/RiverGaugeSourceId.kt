package com.landenlabs.homewx.rivers

/** One entry today (USGS) - kept as an enum, like [com.landenlabs.homewx.weather.WeatherSourceId],
 *  so adding a second source later is an enum entry + a factory branch, not a rewrite. */
enum class RiverGaugeSourceId { USGS }
