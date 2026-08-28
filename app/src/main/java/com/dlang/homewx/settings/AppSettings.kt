package com.dlang.homewx.settings

import android.content.Context
import androidx.core.content.edit

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
    private const val KEY_FORECAST_DAYS = "forecast_days"
    private const val KEY_SMOOTHING_WINDOW_MINUTES = "smoothing_window_minutes"
    private const val KEY_FLAT_RANGE_THRESHOLD = "flat_range_threshold"

    const val DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES = 30
    const val MIN_WEATHER_SAMPLE_INTERVAL_MINUTES = 1
    const val DEFAULT_BACKGROUND_DARKEN_PERCENT = 50
    const val DEFAULT_LIGHT_THRESHOLD_LUX = 8f
    const val MIN_LIGHT_THRESHOLD_LUX = 5f
    const val MAX_LIGHT_THRESHOLD_LUX = 100f
    const val DEFAULT_SCREEN_BRIGHTNESS_PERCENT = 100
    const val MIN_SCREEN_BRIGHTNESS_PERCENT = 10
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

    fun setLightThresholdLux(context: Context, lux: Float) {
        prefs(context).edit { putFloat(KEY_LIGHT_THRESHOLD_LUX, lux.coerceIn(MIN_LIGHT_THRESHOLD_LUX, MAX_LIGHT_THRESHOLD_LUX)) }
    }

    fun getScreenBrightnessPercent(context: Context): Int =
        prefs(context).getInt(KEY_SCREEN_BRIGHTNESS_PERCENT, DEFAULT_SCREEN_BRIGHTNESS_PERCENT)
            .coerceIn(MIN_SCREEN_BRIGHTNESS_PERCENT, 100)

    fun setScreenBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_SCREEN_BRIGHTNESS_PERCENT, percent.coerceIn(MIN_SCREEN_BRIGHTNESS_PERCENT, 100)) }
    }

    fun isWebViewRequestLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEBVIEW_REQUEST_LOGGING_ENABLED, false)

    fun setWebViewRequestLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WEBVIEW_REQUEST_LOGGING_ENABLED, enabled) }
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
