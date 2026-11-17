// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.alerts.nws

import com.landenlabs.homewx.BuildConfig
import com.landenlabs.homewx.alerts.AlertProvider
import com.landenlabs.homewx.alerts.AlertSeverity
import com.landenlabs.homewx.alerts.WeatherAlert
import com.landenlabs.homewx.weather.GeoLocation
import com.landenlabs.homewx.weather.WeatherProviderException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Free, keyless National Weather Service alerts (https://www.weather.gov/documentation/services-web-api).
 * `GET /alerts/active?point=lat,lon` returns every alert whose county/zone covers that point,
 * as GeoJSON. US only. NWS requires a User-Agent identifying the app, and its Akamai front end
 * sometimes answers 503 - that surfaces as an exception so the caller can tell "alerts unknown"
 * apart from "no alerts".
 */
class NwsAlertProvider : AlertProvider {

    override val sourceLabel = "NWS"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun getActiveAlerts(location: GeoLocation): List<WeatherAlert> {
        // NWS redirects anything over 4 decimal places to the rounded point; ask for it directly.
        val point = String.format(Locale.US, "%.4f,%.4f", location.latitude, location.longitude)
        val request = Request.Builder()
            .url("$BASE_URL/alerts/active?point=$point")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/geo+json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WeatherProviderException("NWS alerts HTTP ${response.code}")
            }
            response.body?.string() ?: throw WeatherProviderException("NWS alerts: empty response")
        }
        return parse(body, System.currentTimeMillis())
    }

    companion object {
        private const val BASE_URL = "https://api.weather.gov"
        private val USER_AGENT = "HomeWx/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

        /** Parses an /alerts/active GeoJSON body. Drops tests/drafts, cancellations and alerts
         *  that have already ended. Internal so it can be unit tested without the network. */
        internal fun parse(json: String, nowMillis: Long): List<WeatherAlert> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            val alerts = mutableListOf<WeatherAlert>()
            for (i in 0 until features.length()) {
                val p = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                if (!p.optString("status").equals("Actual", ignoreCase = true)) continue
                if (p.optString("messageType").equals("Cancel", ignoreCase = true)) continue
                val alert = WeatherAlert(
                    id = p.optStringOrNull("id") ?: features.optJSONObject(i)?.optStringOrNull("id") ?: "nws-$i",
                    event = p.optStringOrNull("event") ?: "Weather Alert",
                    headline = p.optStringOrNull("headline"),
                    severity = AlertSeverity.parse(p.optStringOrNull("severity")),
                    urgency = p.optStringOrNull("urgency"),
                    certainty = p.optStringOrNull("certainty"),
                    description = p.optStringOrNull("description"),
                    instruction = p.optStringOrNull("instruction"),
                    areaDesc = p.optStringOrNull("areaDesc"),
                    senderName = p.optStringOrNull("senderName"),
                    issuedMillis = parseTime(p.optStringOrNull("sent")),
                    effectiveMillis = parseTime(p.optStringOrNull("effective")),
                    onsetMillis = parseTime(p.optStringOrNull("onset")),
                    expiresMillis = parseTime(p.optStringOrNull("expires")),
                    endsMillis = parseTime(p.optStringOrNull("ends")),
                    source = "NWS"
                )
                if (alert.isActive(nowMillis)) alerts.add(alert)
            }
            return alerts
        }

        private fun parseTime(text: String?): Long? =
            text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        /** optString() turns JSON null into the string "null"; treat that and blanks as missing. */
        private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }
    }
}
