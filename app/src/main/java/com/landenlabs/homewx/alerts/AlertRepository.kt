// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.alerts

import android.util.Log
import com.landenlabs.homewx.alerts.nws.NwsAlertProvider
import com.landenlabs.homewx.weather.GeoLocation
import com.landenlabs.homewx.weather.WeatherSourceId
import com.landenlabs.homewx.weather.WxDataSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One poll's outcome: the alerts (most severe first) and which source supplied them. */
data class AlertResult(val alerts: List<WeatherAlert>, val sourceLabel: String)

/**
 * Active alerts for the home location. Follows the active weather source: WxData (TWC alerts) when
 * that's the source and this build has it, otherwise NWS. If WxData fails, NWS is tried before
 * giving up, since both carry the same NWS-issued US alerts. Only one source is used per poll, so
 * the same warning never shows up twice.
 */
class AlertRepository(
    private val location: () -> GeoLocation,
    private val activeSource: () -> WeatherSourceId
) {
    private val nws by lazy { NwsAlertProvider() }
    private val wxData by lazy { WxDataSupport.createAlertProvider() }

    /** Throws if every source tried failed, so "unknown" isn't shown as "no alerts". */
    suspend fun getActiveAlerts(): AlertResult = withContext(Dispatchers.IO) {
        val home = location()
        val wx = wxData.takeIf { activeSource() == WeatherSourceId.WXDATA }
        if (wx != null) {
            try {
                return@withContext AlertResult(wx.getActiveAlerts(home).sortedForDisplay(), wx.sourceLabel)
            } catch (e: Exception) {
                Log.w(TAG, "WxData alerts failed, falling back to NWS", e)
            }
        }
        AlertResult(nws.getActiveAlerts(home).sortedForDisplay(), nws.sourceLabel)
    }

    private companion object {
        const val TAG = "AlertRepository"
    }
}
