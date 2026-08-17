package com.dlang.homewx.weather

import android.content.Context
import androidx.core.content.edit

/**
 * Resolves which [WeatherSourceId] is active. A user-set preference (from a
 * future settings panel) always wins; otherwise falls back to the compile-time
 * default below.
 */
object WeatherSourceConfig {

    private const val PREFS_NAME = "homewx_settings"
    private const val KEY_WEATHER_SOURCE = "weather_source"

    val defaultSource: WeatherSourceId = WeatherSourceId.OPEN_METEO

    fun getActiveSource(context: Context): WeatherSourceId {
        val stored = prefs(context).getString(KEY_WEATHER_SOURCE, null) ?: return defaultSource
        return runCatching { WeatherSourceId.valueOf(stored) }.getOrDefault(defaultSource)
    }

    fun setActiveSource(context: Context, source: WeatherSourceId) {
        prefs(context).edit { putString(KEY_WEATHER_SOURCE, source.name) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
