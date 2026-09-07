package com.dlang.homewx.ui

/** Which slice of data the forecast panel's 2nd tab bar is showing - HOURLY/DAILY read from
 *  [com.dlang.homewx.weather.WeatherForecast], PAST from the recorded weather-metrics history. */
enum class ForecastRange { PAST, HOURLY, DAILY }

/** Whether the forecast panel is showing cards or line graphs for the selected [ForecastRange]. */
enum class ForecastPresentation { CARDS, GRAPH }

/** Which slice of [ForecastRange.HOURLY]'s data the graph view's strip charts plot - selected via
 *  the radio buttons above them. ALL matches the hourly cards view (every forecast hour); TODAY
 *  and PLUS_24 narrow that to just today's calendar day or the next 24 hours from now. */
enum class HourlyGraphPeriod { ALL, TODAY, PLUS_24 }
