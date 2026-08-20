package com.dlang.homewx.ui

/** Which slice of data the forecast panel's 2nd tab bar is showing - HOURLY/DAILY read from
 *  [com.dlang.homewx.weather.WeatherForecast], PAST from the recorded weather-metrics history. */
enum class ForecastRange { PAST, HOURLY, DAILY }

/** Whether the forecast panel is showing cards or line graphs for the selected [ForecastRange]. */
enum class ForecastPresentation { CARDS, GRAPH }
