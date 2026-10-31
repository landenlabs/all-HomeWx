package com.landenlabs.homewx.settings

import android.content.Context
import com.landenlabs.homewx.R

/** Which info panel the app settles on when idle (on launch, on a light wake, and after a stretch
 *  with no taps). [CYCLE] is the original behavior: start at News and rotate the top tabs. */
enum class IdlePanel(val labelRes: Int) {
    CYCLE(R.string.idle_panel_cycle),
    NEWS(R.string.idle_panel_news),
    WEATHER(R.string.idle_panel_weather),
    SENSOR(R.string.idle_panel_sensor),
    MAP(R.string.idle_panel_map)
}

/** One choice in the idle sub-panel picker; [key] is what's stored and passed to the panel. */
data class IdleSubPanel(val key: String, val label: String)

/** Stored sub-panel keys. An empty key means the panel's own first tab (News: feed 1, Weather:
 *  Daily, Map: Radar), so a fixed idle panel always lands on the same view. */
object IdleSubPanelKeys {
    const val DEFAULT = ""
    const val NEWS_FEED_1 = "feed1"
    const val NEWS_FEED_2 = "feed2"
    const val NEWS_DROUGHT = "drought"
    const val NEWS_STOCKS = "stocks"
    const val WEATHER_PAST = "past"
    const val WEATHER_HOURLY = "hourly"
    const val WEATHER_DAILY = "daily"
    const val WEATHER_RAIN = "rain"
    const val MAP_RADAR = "radar"
    const val MAP_CLOUDS = "clouds"
    const val MAP_IR = "ir"
    const val MAP_RAIN_PAST = "rain_past"
    const val MAP_RAIN_FORECAST = "rain_forecast"
}

/** Sub-panels selectable for this idle panel, default first; empty for panels with no sub-tabs
 *  (Cycle, Sensor). News lists whichever feeds are currently configured. */
fun IdlePanel.subPanels(context: Context): List<IdleSubPanel> {
    fun sub(key: String, res: Int) = IdleSubPanel(key, context.getString(res))
    val default = sub(IdleSubPanelKeys.DEFAULT, R.string.idle_sub_default)
    return when (this) {
        IdlePanel.CYCLE, IdlePanel.SENSOR -> emptyList()
        IdlePanel.NEWS -> listOfNotNull(
            default,
            AppSettings.getNewsFeed(context, 1)?.let { IdleSubPanel(IdleSubPanelKeys.NEWS_FEED_1, it.label) },
            AppSettings.getNewsFeed(context, 2)?.let { IdleSubPanel(IdleSubPanelKeys.NEWS_FEED_2, it.label) },
            sub(IdleSubPanelKeys.NEWS_DROUGHT, R.string.news_tab_drought),
            sub(IdleSubPanelKeys.NEWS_STOCKS, R.string.news_tab_stocks)
        )
        IdlePanel.WEATHER -> listOf(
            default,
            sub(IdleSubPanelKeys.WEATHER_PAST, R.string.forecast_tab_past),
            sub(IdleSubPanelKeys.WEATHER_HOURLY, R.string.forecast_tab_hourly),
            sub(IdleSubPanelKeys.WEATHER_DAILY, R.string.forecast_tab_daily),
            sub(IdleSubPanelKeys.WEATHER_RAIN, R.string.forecast_tab_rain)
        )
        IdlePanel.MAP -> listOf(
            default,
            sub(IdleSubPanelKeys.MAP_RADAR, R.string.radar_layer_radar),
            sub(IdleSubPanelKeys.MAP_CLOUDS, R.string.radar_layer_clouds),
            sub(IdleSubPanelKeys.MAP_IR, R.string.radar_layer_infrared),
            sub(IdleSubPanelKeys.MAP_RAIN_PAST, R.string.radar_layer_rain72),
            sub(IdleSubPanelKeys.MAP_RAIN_FORECAST, R.string.radar_layer_rain_forecast72)
        )
    }
}
