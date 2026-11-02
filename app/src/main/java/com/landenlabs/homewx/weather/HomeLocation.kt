package com.landenlabs.homewx.weather

import android.content.Context
import androidx.core.content.edit

/** The home location that weather, radar centering and rain totals use - Salem, NH until the user
 *  sets a zip code in Settings > Weather. */
object HomeLocation {
    val DEFAULT = GeoLocation(latitude = 42.7883, longitude = -71.2003, label = "Salem, NH")

    private const val PREFS_NAME = "homewx_settings"
    private const val KEY_LAT = "home_location_lat"
    private const val KEY_LON = "home_location_lon"
    private const val KEY_LABEL = "home_location_label"
    private const val KEY_ZIP = "home_location_zip"
    private const val KEY_EPOCH = "home_location_epoch"

    fun current(context: Context): GeoLocation {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return DEFAULT
        return GeoLocation(
            latitude = Double.fromBits(prefs.getLong(KEY_LAT, DEFAULT.latitude.toRawBits())),
            longitude = Double.fromBits(prefs.getLong(KEY_LON, DEFAULT.longitude.toRawBits())),
            label = prefs.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT.label
        )
    }

    /** The zip the current location was looked up from, or null when using [DEFAULT]. */
    fun getZip(context: Context): String? = prefs(context).getString(KEY_ZIP, null)

    fun set(context: Context, zip: String, location: GeoLocation) {
        prefs(context).edit {
            putLong(KEY_LAT, location.latitude.toRawBits())
            putLong(KEY_LON, location.longitude.toRawBits())
            putString(KEY_LABEL, location.label)
            putString(KEY_ZIP, zip)
            putLong(KEY_EPOCH, prefs(context).getLong(KEY_EPOCH, 0L) + 1)
        }
    }

    /** Bumped whenever the location is set/reset or its history is cleared, so in-memory state
     *  (e.g. today's running high/low) can tell it is stale and start over. */
    fun epoch(context: Context): Long = prefs(context).getLong(KEY_EPOCH, 0L)

    fun bumpEpoch(context: Context) {
        prefs(context).edit { putLong(KEY_EPOCH, prefs(context).getLong(KEY_EPOCH, 0L) + 1) }
    }

    fun reset(context: Context) {
        prefs(context).edit { remove(KEY_LAT); remove(KEY_LON); remove(KEY_LABEL); remove(KEY_ZIP); putLong(KEY_EPOCH, prefs(context).getLong(KEY_EPOCH, 0L) + 1) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
