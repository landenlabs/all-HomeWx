// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.weather.wxdata

import com.landenlabs.homewx.alerts.AlertProvider
import com.landenlabs.homewx.alerts.AlertSeverity
import com.landenlabs.homewx.alerts.WeatherAlert
import com.landenlabs.homewx.weather.GeoLocation
import com.landenlabs.homewx.weather.WeatherProviderException
import com.wsi.wxdata.WxAlertDetail
import com.wsi.wxdata.WxAlertHeadlines
import com.wsi.wxdata.WxAlertsFetcher
import com.wsi.wxdata.WxFetcher
import com.wsi.wxdata.datetime.WxDateTime
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * TWC alert headlines + details (v3/alerts/headlines and v3/alerts/detail) via the wxdata AAR's
 * [WxAlertsFetcher].
 *
 * Uses the fetcher's listener path rather than `getFetchFuture()`: with [WxAlertsFetcher.PARM_DETAILS_B]
 * set, the per-alert detail text (description/instruction) is only loaded after that future has
 * already completed, and only the listener sees the finished result. The listener path also waits
 * for WxData's init event before fetching, and reports "no alerts" as (null data, null error)
 * instead of an EOFException. Listeners fire on the main thread; callers block on Dispatchers.IO.
 */
class WxDataAlertProvider : AlertProvider {

    override val sourceLabel = "WxData"

    override fun getActiveAlerts(location: GeoLocation): List<WeatherAlert> {
        val result = CompletableFuture<WxAlertHeadlines?>()
        // Kept in a local for the whole wait so it stays registered until it fires.
        val listener = WxFetcher.Listener<WxAlertHeadlines> { _, data, error ->
            if (error != null) result.completeExceptionally(error) else result.complete(data as? WxAlertHeadlines)
        }
        var fetcher: WxAlertsFetcher? = null
        // Created and started on the main thread: the fetcher's init handshake (isInitialized /
        // forceFetchOnWxDataInitialized) is plain fields written there by the WxData init event,
        // so calling fetch() from this IO thread could miss that event and never fetch at all.
        mainHandler.post {
            try {
                fetcher = WxAlertsFetcher().apply {
                    setLocation(location.toWxLocation())
                    addParameter(WxAlertsFetcher.PARM_DETAILS_B, true)
                    addFetchListener(listener)
                    fetch()
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        try {
            val headlines = try {
                result.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw WeatherProviderException("wxdata alerts fetch failed", e.cause ?: e)
            } catch (e: Exception) {
                throw WeatherProviderException("wxdata alerts fetch failed", e)
            }
            val now = System.currentTimeMillis()
            return headlines?.alerts.orEmpty()
                .filter { it.isValidAlert() }
                .map { it.toWeatherAlert(headlines?.details?.get(it.detailKey)) }
                .filter { it.isActive(now) }
        } finally {
            mainHandler.post {
                fetcher?.removeFetchListener(listener)
                fetcher?.destroy()
            }
        }
    }

    private fun WxAlertHeadlines.Alert.toWeatherAlert(detail: WxAlertDetail?): WeatherAlert {
        val text = detail?.alertDetail?.texts?.firstOrNull { it.languageCode?.startsWith("en") == true }
            ?: detail?.alertDetail?.texts?.firstOrNull()
        return WeatherAlert(
            id = detailKey ?: identifier ?: "${phenomena}.${significance}.${eventTrackingNumber}",
            event = eventDescription?.takeIf { it.isNotBlank() } ?: "Weather Alert",
            headline = headlineText,
            severity = AlertSeverity.parse(severity),
            urgency = urgency,
            certainty = certainty,
            description = listOfNotNull(text?.overview, text?.description)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifBlank { detail?.alertDetail?.synopsis },
            instruction = text?.instruction?.takeIf { it.isNotBlank() },
            areaDesc = listOfNotNull(areaName, adminDistrictCode).joinToString(", ").ifBlank { null },
            senderName = officeName?.let { office -> officeAdminDistrictCode?.let { "$office, $it" } ?: office },
            issuedMillis = issueTimeLocal.millisOrNull() ?: processTimeUTC?.let { it * 1000 },
            effectiveMillis = effectiveTimeLocal.millisOrNull(),
            onsetMillis = onsetTimeLocal.millisOrNull(),
            expiresMillis = expireTimeLocal.millisOrNull() ?: expireTimeUTC?.let { it * 1000 },
            endsMillis = endTimeLocal.millisOrNull() ?: endTimeUTC?.let { it * 1000 },
            source = sourceLabel
        )
    }

    private fun WxDateTime?.millisOrNull(): Long? = this?.millis

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        /** Headlines plus one detail request per alert, made one after another. */
        private const val FETCH_TIMEOUT_SECONDS = 30L
    }
}
