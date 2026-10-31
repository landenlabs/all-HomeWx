package com.landenlabs.homewx.settings

import android.content.Context
import androidx.core.content.edit
import com.landenlabs.homewx.news.NewsSource
import com.landenlabs.homewx.news.UsStates

/**
 * Config for the daily high/low tracking - no settings screen yet, but the storage/
 * resolution mechanism is here so one can be added later without touching callers.
 */
object AppSettings {

    private const val PREFS_NAME = "homewx_settings"
    private const val KEY_WEATHER_SAMPLE_INTERVAL_MINUTES = "weather_sample_interval_minutes"
    private const val KEY_TEMP_SENSOR_OVERRIDE_ENABLED = "temp_sensor_override_enabled"
    private const val KEY_TEMP_SENSOR_OVERRIDE_SENSOR_ID = "temp_sensor_override_sensor_id"
    private const val KEY_BACKGROUND_DARKEN_PERCENT = "background_darken_percent"
    private const val KEY_HIDDEN_SENSOR_IDS = "hidden_sensor_ids"
    private const val KEY_SENSOR_LABEL_PREFIX = "sensor_label_"
    private const val KEY_LIGHT_THRESHOLD_LUX = "light_threshold_lux"
    private const val KEY_WEBVIEW_REQUEST_LOGGING_ENABLED = "webview_request_logging_enabled"
    private const val KEY_SCREEN_BRIGHTNESS_PERCENT = "screen_brightness_percent"
    private const val KEY_NIGHT_BRIGHTNESS_PERCENT = "night_brightness_percent"
    private const val KEY_FORECAST_DAYS = "forecast_days"
    private const val KEY_SMOOTHING_WINDOW_MINUTES = "smoothing_window_minutes"
    private const val KEY_FLAT_RANGE_THRESHOLD = "flat_range_threshold"
    private const val KEY_DARK_MAP_THEME = "dark_map_theme"
    private const val KEY_DAILY_WEATHER_SPLINE_ENABLED = "daily_weather_spline_enabled"
    private const val KEY_WEATHER_NOTIFICATION_ENABLED = "weather_notification_enabled"
    private const val KEY_NEWS_FEED_LABEL_PREFIX = "news_feed_label_"
    private const val KEY_NEWS_FEED_URL_PREFIX = "news_feed_url_"
    private const val KEY_DROUGHT_STATE = "drought_state"
    private const val KEY_IDLE_PANEL = "idle_panel"
    private const val KEY_SENSORS_COLLAPSED = "sensors_collapsed"
    private const val KEY_IDLE_SUB_PANEL = "idle_sub_panel"

    const val DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES = 30
    const val MIN_WEATHER_SAMPLE_INTERVAL_MINUTES = 1
    const val DEFAULT_BACKGROUND_DARKEN_PERCENT = 50
    const val DEFAULT_LIGHT_THRESHOLD_LUX = 8f
    const val MIN_LIGHT_THRESHOLD_LUX = 5f
    const val MAX_LIGHT_THRESHOLD_LUX = 100f
    const val DEFAULT_SCREEN_BRIGHTNESS_PERCENT = 100
    const val MIN_SCREEN_BRIGHTNESS_PERCENT = 10
    const val DEFAULT_NIGHT_BRIGHTNESS_PERCENT = 100
    const val MIN_NIGHT_BRIGHTNESS_PERCENT = 5
    const val DEFAULT_FORECAST_DAYS = 7
    const val MIN_FORECAST_DAYS = 1
    const val MAX_FORECAST_DAYS = 16 // Open-Meteo's own forecast_days ceiling
    const val DEFAULT_SMOOTHING_WINDOW_MINUTES = 90
    const val MIN_SMOOTHING_WINDOW_MINUTES = 5
    const val MAX_SMOOTHING_WINDOW_MINUTES = 360
    const val DEFAULT_FLAT_RANGE_THRESHOLD = 5.0f
    const val MIN_FLAT_RANGE_THRESHOLD = 0.5f
    const val MAX_FLAT_RANGE_THRESHOLD = 20.0f

    fun getWeatherSampleIntervalMinutes(context: Context): Int {
        val stored = prefs(context).getInt(KEY_WEATHER_SAMPLE_INTERVAL_MINUTES, DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES)
        return stored.coerceAtLeast(MIN_WEATHER_SAMPLE_INTERVAL_MINUTES)
    }

    fun setWeatherSampleIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_WEATHER_SAMPLE_INTERVAL_MINUTES, minutes.coerceAtLeast(MIN_WEATHER_SAMPLE_INTERVAL_MINUTES)) }
    }

    fun getForecastDays(context: Context): Int =
        prefs(context).getInt(KEY_FORECAST_DAYS, DEFAULT_FORECAST_DAYS).coerceIn(MIN_FORECAST_DAYS, MAX_FORECAST_DAYS)

    fun setForecastDays(context: Context, days: Int) {
        prefs(context).edit { putInt(KEY_FORECAST_DAYS, days.coerceIn(MIN_FORECAST_DAYS, MAX_FORECAST_DAYS)) }
    }

    /** Sensor history chart smoothing: trailing time window, in minutes, that a rolling average is taken over. */
    fun getSmoothingWindowMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SMOOTHING_WINDOW_MINUTES, DEFAULT_SMOOTHING_WINDOW_MINUTES)
            .coerceIn(MIN_SMOOTHING_WINDOW_MINUTES, MAX_SMOOTHING_WINDOW_MINUTES)

    fun setSmoothingWindowMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_SMOOTHING_WINDOW_MINUTES, minutes.coerceIn(MIN_SMOOTHING_WINDOW_MINUTES, MAX_SMOOTHING_WINDOW_MINUTES)) }
    }

    /** Sensor history chart smoothing: a point only gets averaged when its own trailing window's
     *  value range is below this - otherwise it's left raw so a real trend isn't smeared. */
    fun getFlatRangeThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_FLAT_RANGE_THRESHOLD, DEFAULT_FLAT_RANGE_THRESHOLD)
            .coerceIn(MIN_FLAT_RANGE_THRESHOLD, MAX_FLAT_RANGE_THRESHOLD)

    fun setFlatRangeThreshold(context: Context, threshold: Float) {
        prefs(context).edit { putFloat(KEY_FLAT_RANGE_THRESHOLD, threshold.coerceIn(MIN_FLAT_RANGE_THRESHOLD, MAX_FLAT_RANGE_THRESHOLD)) }
    }

    fun isTempSensorOverrideEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TEMP_SENSOR_OVERRIDE_ENABLED, false)

    fun getTempSensorOverrideSensorId(context: Context): String? =
        prefs(context).getString(KEY_TEMP_SENSOR_OVERRIDE_SENSOR_ID, null)

    fun setTempSensorOverride(context: Context, enabled: Boolean, sensorId: String?) {
        prefs(context).edit {
            putBoolean(KEY_TEMP_SENSOR_OVERRIDE_ENABLED, enabled)
            putString(KEY_TEMP_SENSOR_OVERRIDE_SENSOR_ID, sensorId)
        }
    }

    fun getBackgroundDarkenPercent(context: Context): Int =
        prefs(context).getInt(KEY_BACKGROUND_DARKEN_PERCENT, DEFAULT_BACKGROUND_DARKEN_PERCENT).coerceIn(0, 100)

    fun setBackgroundDarkenPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_BACKGROUND_DARKEN_PERCENT, percent.coerceIn(0, 100)) }
    }

    fun getLightThresholdLux(context: Context): Float =
        prefs(context).getFloat(KEY_LIGHT_THRESHOLD_LUX, DEFAULT_LIGHT_THRESHOLD_LUX)
            .coerceIn(MIN_LIGHT_THRESHOLD_LUX, MAX_LIGHT_THRESHOLD_LUX)

    /** Slider at its maximum pins the app to the night layout regardless of light or taps. */
    fun isForcedNightMode(context: Context): Boolean =
        getLightThresholdLux(context) >= MAX_LIGHT_THRESHOLD_LUX

    fun setLightThresholdLux(context: Context, lux: Float) {
        prefs(context).edit { putFloat(KEY_LIGHT_THRESHOLD_LUX, lux.coerceIn(MIN_LIGHT_THRESHOLD_LUX, MAX_LIGHT_THRESHOLD_LUX)) }
    }

    fun getScreenBrightnessPercent(context: Context): Int =
        prefs(context).getInt(KEY_SCREEN_BRIGHTNESS_PERCENT, DEFAULT_SCREEN_BRIGHTNESS_PERCENT)
            .coerceIn(MIN_SCREEN_BRIGHTNESS_PERCENT, 100)

    fun setScreenBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_SCREEN_BRIGHTNESS_PERCENT, percent.coerceIn(MIN_SCREEN_BRIGHTNESS_PERCENT, 100)) }
    }

    /** Multiplier (percent) applied to the night-mode layout's brightness - see NightModePanel. */
    fun getNightBrightnessPercent(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_BRIGHTNESS_PERCENT, DEFAULT_NIGHT_BRIGHTNESS_PERCENT)
            .coerceIn(MIN_NIGHT_BRIGHTNESS_PERCENT, 100)

    fun setNightBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_NIGHT_BRIGHTNESS_PERCENT, percent.coerceIn(MIN_NIGHT_BRIGHTNESS_PERCENT, 100)) }
    }

    fun isWebViewRequestLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEBVIEW_REQUEST_LOGGING_ENABLED, false)

    fun setWebViewRequestLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WEBVIEW_REQUEST_LOGGING_ENABLED, enabled) }
    }

    /** Whether the Daily forecast graphs (temp/wind/precipitation) draw a smoothed spline curve
     *  through each day's point instead of straight line segments. */
    fun isDailyWeatherSplineEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAILY_WEATHER_SPLINE_ENABLED, true)

    fun setDailyWeatherSplineEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DAILY_WEATHER_SPLINE_ENABLED, enabled) }
    }

    /** Whether the optional current-conditions / next-6-hours notification is shown. */
    fun isWeatherNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEATHER_NOTIFICATION_ENABLED, false)

    fun setWeatherNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WEATHER_NOTIFICATION_ENABLED, enabled) }
    }

    /** Whether the Radar tab's base map uses the dark (Esri Dark Gray Canvas) tiles instead of the
     *  standard light OpenStreetMap ones. */
    fun isDarkMapTheme(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK_MAP_THEME, true)

    fun setDarkMapTheme(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DARK_MAP_THEME, enabled) }
    }

    /** The configured RSS feeds, in tab order: feed 1 always, feed 2 only if it has a URL. A feed
     *  slot never saved falls back to its default; one saved with a blank URL is switched off. */
    fun getNewsSources(context: Context): List<NewsSource> =
        listOfNotNull(getNewsFeed(context, 1), getNewsFeed(context, 2))

    /** Slot [slot] (1 or 2) as saved, the default if never saved, or null if it's switched off. */
    fun getNewsFeed(context: Context, slot: Int): NewsSource? {
        val prefs = prefs(context)
        val default = if (slot == 1) NewsSource.DEFAULT_FEED_1 else NewsSource.DEFAULT_FEED_2
        if (!prefs.contains(KEY_NEWS_FEED_URL_PREFIX + slot)) return default
        val url = prefs.getString(KEY_NEWS_FEED_URL_PREFIX + slot, null)?.takeIf { it.isNotBlank() } ?: return null
        val label = prefs.getString(KEY_NEWS_FEED_LABEL_PREFIX + slot, null)?.takeIf { it.isNotBlank() }
        return NewsSource(label ?: android.net.Uri.parse(url).host.orEmpty().removePrefix("www."), url)
    }

    /** Saves slot [slot]; a null/blank [url] switches the slot off (feed 1 is validated as
     *  required by the caller). */
    fun setNewsFeed(context: Context, slot: Int, label: String?, url: String?) {
        prefs(context).edit {
            putString(KEY_NEWS_FEED_URL_PREFIX + slot, url?.trim().orEmpty())
            putString(KEY_NEWS_FEED_LABEL_PREFIX + slot, label?.trim().orEmpty())
        }
    }

    fun resetNewsFeeds(context: Context) {
        prefs(context).edit {
            for (slot in 1..2) {
                remove(KEY_NEWS_FEED_URL_PREFIX + slot)
                remove(KEY_NEWS_FEED_LABEL_PREFIX + slot)
            }
        }
    }

    /** Whether the SENSORS rows under the weather panel are collapsed; open until the user taps the title. */
    fun isSensorsCollapsed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENSORS_COLLAPSED, false)

    fun setSensorsCollapsed(context: Context, collapsed: Boolean) {
        prefs(context).edit { putBoolean(KEY_SENSORS_COLLAPSED, collapsed) }
    }

    /** The panel to settle on when idle; [IdlePanel.CYCLE] (the original rotation) if never set. */
    fun getIdlePanel(context: Context): IdlePanel =
        IdlePanel.values().firstOrNull { it.name == prefs(context).getString(KEY_IDLE_PANEL, null) } ?: IdlePanel.CYCLE

    /** Key into [IdlePanel.subPanels] for the idle panel's sub-tab; empty for its default tab. */
    fun getIdleSubPanel(context: Context): String =
        prefs(context).getString(KEY_IDLE_SUB_PANEL, null).orEmpty()

    fun setIdlePanel(context: Context, panel: IdlePanel, subPanelKey: String) {
        prefs(context).edit {
            putString(KEY_IDLE_PANEL, panel.name)
            putString(KEY_IDLE_SUB_PANEL, subPanelKey)
        }
    }

    /** 2-letter code of the state whose US Drought Monitor map the Drought tab shows. */
    fun getDroughtState(context: Context): String =
        prefs(context).getString(KEY_DROUGHT_STATE, null)?.takeIf { code -> UsStates.all.any { it.first == code } }
            ?: UsStates.DEFAULT_CODE

    fun setDroughtState(context: Context, code: String) {
        prefs(context).edit { putString(KEY_DROUGHT_STATE, code) }
    }

    fun getHiddenSensorIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN_SENSOR_IDS, emptySet()).orEmpty()

    fun setHiddenSensorIds(context: Context, ids: Set<String>) {
        prefs(context).edit { putStringSet(KEY_HIDDEN_SENSOR_IDS, ids) }
    }

    /** Custom display name for a sensor, or null to fall back to its Govee device name. */
    fun getSensorLabel(context: Context, sensorId: String): String? =
        prefs(context).getString(KEY_SENSOR_LABEL_PREFIX + sensorId, null)?.takeIf { it.isNotBlank() }

    fun setSensorLabel(context: Context, sensorId: String, label: String?) {
        prefs(context).edit {
            if (label.isNullOrBlank()) remove(KEY_SENSOR_LABEL_PREFIX + sensorId) else putString(KEY_SENSOR_LABEL_PREFIX + sensorId, label)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
