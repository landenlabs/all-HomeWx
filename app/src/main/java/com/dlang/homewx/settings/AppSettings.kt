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

    const val DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES = 30
    const val MIN_WEATHER_SAMPLE_INTERVAL_MINUTES = 1

    fun getWeatherSampleIntervalMinutes(context: Context): Int {
        val stored = prefs(context).getInt(KEY_WEATHER_SAMPLE_INTERVAL_MINUTES, DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES)
        return stored.coerceAtLeast(MIN_WEATHER_SAMPLE_INTERVAL_MINUTES)
    }

    fun setWeatherSampleIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_WEATHER_SAMPLE_INTERVAL_MINUTES, minutes.coerceAtLeast(MIN_WEATHER_SAMPLE_INTERVAL_MINUTES)) }
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
