package com.dlang.homewx.rivers

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rivers feature config: enabled flag, the zip code last searched, and the gauges checked in
 * Settings - stored as a small JSON array (org.json, the app's only JSON library) since this is
 * simple metadata, not a time series (that's [com.dlang.homewx.data.RiverHistoryStore]).
 */
object RiverGaugeSettings {

    private const val PREFS_NAME = "homewx_settings"
    private const val KEY_ENABLED = "river_gauges_enabled"
    private const val KEY_ZIP_CODE = "river_gauges_zip_code"
    private const val KEY_SELECTED_GAUGES = "river_gauges_selected"
    private const val KEY_GAUGE_LABEL_PREFIX = "river_gauge_label_"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun getZipCode(context: Context): String? =
        prefs(context).getString(KEY_ZIP_CODE, null)

    fun setZipCode(context: Context, zip: String?) {
        prefs(context).edit { putString(KEY_ZIP_CODE, zip) }
    }

    fun getSelectedGauges(context: Context): List<GaugeSite> {
        val raw = prefs(context).getString(KEY_SELECTED_GAUGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                GaugeSite(
                    siteId = obj.getString("siteId"),
                    name = obj.getString("name"),
                    latitude = obj.getDouble("latitude"),
                    longitude = obj.getDouble("longitude"),
                    hasGageHeight = obj.getBoolean("hasGageHeight"),
                    hasDischarge = obj.getBoolean("hasDischarge"),
                    distanceMiles = obj.getDouble("distanceMiles")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setSelectedGauges(context: Context, gauges: List<GaugeSite>) {
        val array = JSONArray()
        gauges.forEach { gauge ->
            array.put(
                JSONObject().apply {
                    put("siteId", gauge.siteId)
                    put("name", gauge.name)
                    put("latitude", gauge.latitude)
                    put("longitude", gauge.longitude)
                    put("hasGageHeight", gauge.hasGageHeight)
                    put("hasDischarge", gauge.hasDischarge)
                    put("distanceMiles", gauge.distanceMiles)
                }
            )
        }
        prefs(context).edit { putString(KEY_SELECTED_GAUGES, array.toString()) }
    }

    /** Custom display name for a gauge (the official USGS station names are long and not very
     *  readable at a glance), or null to fall back to [GaugeSite.name]. */
    fun getGaugeLabel(context: Context, siteId: String): String? =
        prefs(context).getString(KEY_GAUGE_LABEL_PREFIX + siteId, null)?.takeIf { it.isNotBlank() }

    fun setGaugeLabel(context: Context, siteId: String, label: String?) {
        prefs(context).edit {
            if (label.isNullOrBlank()) remove(KEY_GAUGE_LABEL_PREFIX + siteId) else putString(KEY_GAUGE_LABEL_PREFIX + siteId, label)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
