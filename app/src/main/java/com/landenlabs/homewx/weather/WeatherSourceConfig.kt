package com.landenlabs.homewx.weather

import android.content.Context
import androidx.core.content.edit
import com.landenlabs.homewx.settings.CredentialStore

/**
 * Resolves which [WeatherSourceId] is active. A user-set preference (from a
 * future settings panel) always wins; otherwise falls back to the compile-time
 * default below. A source that needs a credential the user hasn't entered (WxData) is neither
 * offered nor used - [getActiveSource] falls back to [defaultSource] even if it was chosen earlier.
 */
object WeatherSourceConfig {

    private const val PREFS_NAME = "homewx_settings"
    private const val KEY_WEATHER_SOURCE = "weather_source"

    val defaultSource: WeatherSourceId = WeatherSourceId.OPEN_METEO

    /** The sources the user can pick: everything except WxData while it has no API key. */
    fun availableSources(context: Context): List<WeatherSourceId> =
        WeatherSourceId.values().filter { it != WeatherSourceId.WXDATA || CredentialStore.hasWxData(context) }

    fun getActiveSource(context: Context): WeatherSourceId {
        val stored = prefs(context).getString(KEY_WEATHER_SOURCE, null) ?: return defaultSource
        val source = runCatching { WeatherSourceId.valueOf(stored) }.getOrDefault(defaultSource)
        return source.takeIf { it in availableSources(context) } ?: defaultSource
    }

    fun setActiveSource(context: Context, source: WeatherSourceId) {
        prefs(context).edit { putString(KEY_WEATHER_SOURCE, source.name) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
